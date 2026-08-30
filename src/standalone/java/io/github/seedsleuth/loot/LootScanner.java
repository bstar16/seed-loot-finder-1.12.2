package io.github.seedsleuth.loot;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityLockableLoot;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.storage.loot.LootContext;
import net.minecraft.world.storage.loot.LootTable;
import net.minecraft.world.storage.loot.LootTableManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Reports which nearby chests contain something worth the trip, without opening them.
 *
 * <h2>Why this only works in singleplayer</h2>
 * A generated chest stores a loot table and a seed, and rolls its contents the first time a
 * player opens it. Neither field is ever sent to a client, so on a multiplayer server the chest
 * really is empty as far as your game is concerned and no client mod can see inside it.
 *
 * <p>What makes this useful anyway is that chest contents are a pure function of the world seed
 * and the chunk coordinates. A singleplayer world created on a cracked seed generates the same
 * chests, with the same loot seeds, holding the same items. Scan there, write down the
 * coordinates, collect them on the real server.
 *
 * <p>In singleplayer the integrated server runs in this JVM, so the tile entities are reachable
 * directly. Loot is rolled into a throwaway list, never into the world, so scanning does not
 * consume the chest.
 */
public final class LootScanner {
    /** Chests examined per tick. Rolling a loot table is not free. */
    private static final int CHESTS_PER_TICK = 24;
    private static final int MAX_HITS = 256;

    private final List<LootTarget> targets = new ArrayList<LootTarget>();
    private final List<LootFind> hits = new ArrayList<LootFind>();
    private final Set<Long> examined = new HashSet<Long>();
    /**
     * Chests the player has already walked up to. Unlike {@link #examined}, this survives
     * {@link #rescan()}, so a visited waypoint never comes back.
     */
    private final Set<Long> dismissed = new HashSet<Long>();
    private boolean enabled;
    private int chestsExamined;
    private String lastError;

    public static final class LootFind {
        public BlockPos pos;
        public final String table;
        public final List<String> matches;
        public final int dimension;

        LootFind(BlockPos pos, int dimension, String table, List<String> matches) {
            this.pos = pos;
            this.dimension = dimension;
            this.table = table;
            this.matches = matches;
        }

        public String describe() {
            StringBuilder result = new StringBuilder();
            result.append(pos.getX()).append(' ').append(pos.getY()).append(' ').append(pos.getZ());
            result.append("  ");
            for (int index = 0; index < matches.size(); index++) {
                if (index > 0) {
                    result.append(", ");
                }
                result.append(matches.get(index));
            }
            return result.toString();
        }
    }

    public LootScanner() {
        installDefaultTargets();
    }

    /** Sensible defaults for "what am I actually flying out here for". */
    private void installDefaultTargets() {
        addTargetQuietly("enchanted_golden_apple");
        addTargetQuietly("book:mending");
    }

    private void addTargetQuietly(String spec) {
        try {
            targets.add(LootTarget.parse(spec));
        } catch (RuntimeException ignored) {
            // A default that will not parse is not worth crashing over.
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
        if (!value) {
            lastError = null;
        }
    }

    public List<LootTarget> getTargets() {
        return new ArrayList<LootTarget>(targets);
    }

    public void addTarget(String spec) {
        targets.add(LootTarget.parse(spec));
        rescan();
    }

    public boolean removeTarget(int index) {
        if (index < 0 || index >= targets.size()) {
            return false;
        }
        targets.remove(index);
        rescan();
        return true;
    }

    /** Forgets what has been looked at, so changed targets are applied to known chunks again. */
    public void rescan() {
        examined.clear();
        hits.clear();
        chestsExamined = 0;
    }

    public int getChestsExamined() {
        return chestsExamined;
    }

    public String getLastError() {
        return lastError;
    }

    public int hitCount() {
        return hits.size();
    }

    /** True when an integrated server is available, i.e. singleplayer or a LAN host. */
    public static boolean canScan(Minecraft minecraft) {
        return minecraft.getIntegratedServer() != null;
    }

    /**
     * Examines a bounded number of unseen loot chests in loaded chunks.
     *
     * @return number of new finds
     */
    public int tick(Minecraft minecraft) {
        if (!enabled || targets.isEmpty()) {
            return 0;
        }
        MinecraftServer server = minecraft.getIntegratedServer();
        if (server == null) {
            lastError = "No integrated server: open a singleplayer world on the cracked seed";
            return 0;
        }
        if (minecraft.player == null) {
            return 0;
        }
        int dimension = minecraft.player.dimension;
        WorldServer world = server.getWorld(dimension);
        if (world == null) {
            return 0;
        }

        // The integrated server owns these collections and mutates them on its own thread, so
        // everything is snapshotted defensively; a concurrent change just costs us a tick.
        List<Chunk> chunks = snapshotChunks(world);
        int budget = CHESTS_PER_TICK;
        int found = 0;
        for (Chunk chunk : chunks) {
            if (budget <= 0) {
                break;
            }
            for (TileEntity tile : snapshotTiles(chunk)) {
                if (budget <= 0) {
                    break;
                }
                if (!(tile instanceof TileEntityLockableLoot) || tile.isInvalid()) {
                    continue;
                }
                TileEntityLockableLoot container = (TileEntityLockableLoot) tile;
                ResourceLocation table = container.getLootTable();
                if (table == null) {
                    // Already opened, or a player-placed chest: nothing to predict.
                    continue;
                }
                BlockPos pos = tile.getPos();
                long key = key(dimension, pos);
                if (!examined.add(Long.valueOf(key))) {
                    continue;
                }
                budget--;
                chestsExamined++;
                if (inspect(world, container, table, pos, dimension)) {
                    found++;
                }
            }
        }
        return found;
    }

    private List<Chunk> snapshotChunks(WorldServer world) {
        try {
            return new ArrayList<Chunk>(world.getChunkProvider().getLoadedChunks());
        } catch (ConcurrentModificationException retryNextTick) {
            return Collections.emptyList();
        } catch (RuntimeException error) {
            lastError = "Chunk list unavailable: " + error.getClass().getSimpleName();
            return Collections.emptyList();
        }
    }

    private static List<TileEntity> snapshotTiles(Chunk chunk) {
        try {
            return new ArrayList<TileEntity>(chunk.getTileEntityMap().values());
        } catch (ConcurrentModificationException retryNextTick) {
            return Collections.emptyList();
        } catch (RuntimeException ignored) {
            return Collections.emptyList();
        }
    }

    /**
     * Rolls a chest's contents.
     *
     * <p>{@code manager} may be null when running against the simulator, in which case a
     * standalone {@link LootTableManager} reads the tables straight out of the jar.
     * {@code LootContext} nominally wants a {@code WorldServer}, but chest tables only ever ask
     * it for {@code getLuck()}, so a null world is safe here and is what makes prediction
     * possible without a server at all.
     */
    public List<ItemStack> rollLoot(LootTableManager manager, WorldServer world,
                                    ResourceLocation tableId, long lootSeed) {
        LootTableManager tables = manager != null ? manager : standaloneTables();
        if (tables == null) {
            return Collections.emptyList();
        }
        LootTable table = tables.getLootTableFromLocation(tableId);
        if (table == null) {
            return Collections.emptyList();
        }
        LootContext context = world != null
            ? new LootContext.Builder(world).build()
            : new LootContext(0.0F, null, tables, null, null, null);
        // generateLootForPools returns the stacks without touching the chest.
        return table.generateLootForPools(new Random(lootSeed), context);
    }

    private static LootTableManager standaloneManager;

    private LootTableManager standaloneTables() {
        if (standaloneManager == null) {
            try {
                standaloneManager = new LootTableManager(null);
            } catch (Throwable error) {
                lastError = "Could not read loot tables: " + error.getClass().getSimpleName();
                return null;
            }
        }
        return standaloneManager;
    }

    /** Matches a rolled stack list against the target list. */
    public List<String> matchesIn(List<ItemStack> loot) {
        List<String> matches = new ArrayList<String>();
        for (ItemStack stack : loot) {
            for (LootTarget target : targets) {
                String description = target.match(stack);
                if (description != null) {
                    matches.add(description);
                    break;
                }
            }
        }
        return matches;
    }

    /** Records a find discovered by the simulator. */
    public LootFind record(BlockPos pos, int dimension, ResourceLocation tableId,
                           List<String> matches) {
        if (matches.isEmpty() || hits.size() >= MAX_HITS) {
            return null;
        }
        if (dismissed.contains(Long.valueOf(key(dimension, pos)))) {
            return null;
        }
        if (!examined.add(Long.valueOf(key(dimension, pos)))) {
            return null;
        }
        LootFind find = new LootFind(pos, dimension, tableId.getPath(), matches);
        hits.add(find);
        return find;
    }

    public void countChest() {
        chestsExamined++;
    }

    public boolean alreadySeen(int dimension, BlockPos pos) {
        return examined.contains(Long.valueOf(key(dimension, pos)));
    }

    public void setLastError(String message) {
        this.lastError = message;
    }

    /** Reads the protected loot seed from a generated container. */
    public Long lootSeedOf(TileEntityLockableLoot container) {
        return readLootSeed(container);
    }

    private boolean inspect(WorldServer world, TileEntityLockableLoot container,
                            ResourceLocation tableId, BlockPos pos, int dimension) {
        Long seed = readLootSeed(container);
        if (seed == null) {
            return false;
        }
        List<ItemStack> loot;
        try {
            loot = rollLoot(world.getLootTableManager(), world, tableId, seed.longValue());
        } catch (Throwable error) {
            lastError = "Loot roll failed for " + tableId + ": " + error.getClass().getSimpleName();
            return false;
        }
        List<String> matches = new ArrayList<String>();
        for (ItemStack stack : loot) {
            for (LootTarget target : targets) {
                String description = target.match(stack);
                if (description != null) {
                    matches.add(description);
                    break;
                }
            }
        }
        if (matches.isEmpty()) {
            return false;
        }
        if (hits.size() >= MAX_HITS || dismissed.contains(Long.valueOf(key(dimension, pos)))) {
            return false;
        }
        hits.add(new LootFind(pos, dimension, tableId.getPath(), matches));
        return true;
    }

    /**
     * Drops every find within {@code radius} blocks of the player and remembers it as visited.
     * Getting close enough to see the chest is the natural end of a waypoint's life; leaving the
     * beam up past that point only buries the next one.
     *
     * @return the finds that were removed
     */
    public List<LootFind> clearWithin(int dimension, double x, double y, double z, double radius) {
        List<LootFind> removed = new ArrayList<LootFind>();
        double radiusSquared = radius * radius;
        for (Iterator<LootFind> iterator = hits.iterator(); iterator.hasNext(); ) {
            LootFind find = iterator.next();
            if (find.dimension != dimension) {
                continue;
            }
            double dx = find.pos.getX() + 0.5D - x;
            double dy = find.pos.getY() + 0.5D - y;
            double dz = find.pos.getZ() + 0.5D - z;
            if (dx * dx + dy * dy + dz * dz <= radiusSquared) {
                iterator.remove();
                dismissed.add(Long.valueOf(key(find.dimension, find.pos)));
                removed.add(find);
            }
        }
        return removed;
    }

    /** The persistence form of a visited waypoint. */
    public static String visitKey(LootFind find) {
        return find.dimension + ":" + find.pos.getX() + ":" + find.pos.getY() + ":"
            + find.pos.getZ();
    }

    /**
     * Resets for a newly joined world, then applies that world's saved state.
     *
     * <p>Everything in memory belongs to the previous world -- finds, examined chunks, visited
     * waypoints -- and must not leak into this one as beams at meaningless coordinates. Targets
     * come from the session once it has ever been configured (even to an empty list, which is a
     * deliberate choice, not an unconfigured one); otherwise the constructor defaults stand.
     */
    public void beginSession(List<String> targetSpecs, boolean targetsConfigured,
                             List<String> visited) {
        hits.clear();
        examined.clear();
        dismissed.clear();
        chestsExamined = 0;
        lastError = null;
        targets.clear();
        if (targetsConfigured || !targetSpecs.isEmpty()) {
            for (String spec : targetSpecs) {
                addTargetQuietly(spec);
            }
        } else {
            installDefaultTargets();
        }
        restoreVisited(visited);
    }

    /** Reloads visited waypoints saved by an earlier session. */
    public void restoreVisited(List<String> entries) {
        for (String entry : entries) {
            String[] parts = entry.split(":");
            if (parts.length != 4) {
                continue;
            }
            try {
                dismissed.add(Long.valueOf(key(Integer.parseInt(parts[0]),
                    new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3])))));
            } catch (NumberFormatException ignored) {
                // A hand-edited file line that does not parse is not worth failing the session.
            }
        }
    }

    /** Replaces the target list with saved specs; unparseable entries are skipped. */
    public void restoreTargets(List<String> specs) {
        targets.clear();
        for (String spec : specs) {
            addTargetQuietly(spec);
        }
        rescan();
    }

    /** The target list as specs, for persisting. */
    public List<String> targetSpecs() {
        List<String> specs = new ArrayList<String>(targets.size());
        for (LootTarget target : targets) {
            specs.add(target.getSpec());
        }
        return specs;
    }

    /** Delegates to the shared reader so there is exactly one copy of this reflection. */
    private Long readLootSeed(TileEntityLockableLoot container) {
        Long seed = io.github.seedsleuth.sim.LootSeedReader.read(container);
        if (seed == null && io.github.seedsleuth.sim.LootSeedReader.hasFailed()) {
            lastError = "Could not read the chest's loot seed field";
        }
        return seed;
    }

    private static long key(int dimension, BlockPos pos) {
        return ((long) dimension << 58) ^ ((long) pos.getX() << 38)
            ^ ((long) pos.getZ() << 12) ^ pos.getY();
    }

    /**
     * Finds in the given dimension, nearest first, so the HUD shows what is actually reachable.
     * The dimension filter matters in singleplayer, where nether and overworld finds share one
     * list but their coordinates mean different places.
     */
    public List<LootFind> nearest(int dimension, final int fromX, final int fromZ, int limit) {
        List<LootFind> sorted = new ArrayList<LootFind>(hits.size());
        for (LootFind find : hits) {
            if (find.dimension == dimension) {
                sorted.add(find);
            }
        }
        Collections.sort(sorted, new Comparator<LootFind>() {
            @Override
            public int compare(LootFind left, LootFind right) {
                return Long.compare(distance(left), distance(right));
            }

            private long distance(LootFind find) {
                long dx = (long) find.pos.getX() - fromX;
                long dz = (long) find.pos.getZ() - fromZ;
                return dx * dx + dz * dz;
            }
        });
        return sorted.size() > limit ? sorted.subList(0, limit) : sorted;
    }

    public List<LootFind> all() {
        return new ArrayList<LootFind>(hits);
    }
}
