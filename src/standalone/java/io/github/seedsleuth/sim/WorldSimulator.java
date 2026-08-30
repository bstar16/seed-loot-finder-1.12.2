package io.github.seedsleuth.sim;

import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityLockableLoot;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkGeneratorOverworld;
import net.minecraft.world.gen.ChunkGeneratorEnd;
import net.minecraft.world.gen.IChunkGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * Re-runs vanilla world generation for a known seed, chunk by chunk, inside the client.
 *
 * <h2>Why the bookkeeping is fiddly</h2>
 * {@code populate(x, z)} does not fill chunk (x, z). It writes into the 16x16 area offset by
 * eight blocks, which straddles chunks (x, z) through (x+1, z+1). So a chest that ends up in
 * chunk C could have been placed by any of the four populate passes at C-1..C, and each of those
 * passes needs its own 2x2 of generated terrain. Fully finishing one chunk therefore means
 * generating a 3x3 of terrain and running four populate passes.
 *
 * <p>That is roughly a tenth of a second of work per chunk, which is why callers hand in a time
 * budget and come back next tick.
 */
public final class WorldSimulator {
    private final SimWorld world;
    private final SimChunkProvider provider;
    private final IChunkGenerator generator;
    private final long seed;
    private final int dimension;
    private int chunksCompleted;
    private String failure;

    public WorldSimulator(long seed, WorldType worldType, String generatorOptions, int dimension) {
        this.seed = seed;
        this.dimension = dimension;
        this.world = new SimWorld(seed, worldType, generatorOptions, dimension);
        this.provider = world.simProvider();
        this.generator = dimension == 1
            ? new ChunkGeneratorEnd(world, true, seed, world.getSpawnPoint())
            : new ChunkGeneratorOverworld(world, seed, true,
                generatorOptions == null ? "" : generatorOptions);
        this.provider.setGenerator(generator);
    }

    public long getSeed() {
        return seed;
    }

    public int getChunksCompleted() {
        return chunksCompleted;
    }

    public int getCachedChunks() {
        return provider.cachedChunks();
    }

    /**
     * True when the working set has outgrown what can be held consistently, so the caller should
     * drop this simulation and start a fresh one rather than let chunks be evicted.
     */
    public boolean isOverCapacity() {
        return provider.isOverCapacity();
    }

    public String getFailure() {
        return failure;
    }

    public boolean isComplete(int chunkX, int chunkZ) {
        for (int dx = -1; dx <= 0; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
                if (!provider.isPopulated(chunkX + dx, chunkZ + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean hasFailed(int chunkX, int chunkZ) {
        return provider.hasFailed(chunkX, chunkZ);
    }

    /**
     * Advances chunk (chunkX, chunkZ) toward being fully populated, stopping when the deadline
     * passes.
     *
     * @return true when the chunk is finished and its chests can be read
     */
    public boolean advance(int chunkX, int chunkZ, long deadlineNanos) {
        if (isComplete(chunkX, chunkZ)) {
            return true;
        }
        for (int dx = -1; dx <= 0; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
                int populateX = chunkX + dx;
                int populateZ = chunkZ + dz;
                if (provider.isPopulated(populateX, populateZ)) {
                    continue;
                }
                if (!runPopulate(populateX, populateZ)) {
                    provider.markFailed(chunkX, chunkZ);
                    return true;
                }
                if (System.nanoTime() >= deadlineNanos) {
                    return isComplete(chunkX, chunkZ);
                }
            }
        }
        if (isComplete(chunkX, chunkZ)) {
            chunksCompleted++;
            return true;
        }
        return false;
    }

    private boolean runPopulate(int chunkX, int chunkZ) {
        // Terrain and population both drive vanilla's biome layers, which are not thread safe.
        GenLayerLock.acquire();
        try {
            // populate() reaches into (x+1, z+1), so both must already have terrain.
            for (int dx = 0; dx <= 1; dx++) {
                for (int dz = 0; dz <= 1; dz++) {
                    world.getChunk(chunkX + dx, chunkZ + dz);
                }
            }
            generator.populate(chunkX, chunkZ);
            provider.markPopulated(chunkX, chunkZ);
            return true;
        } catch (Throwable error) {
            failure = "populate(" + chunkX + "," + chunkZ + ") failed: "
                + error.getClass().getSimpleName()
                + (error.getMessage() == null ? "" : (": " + error.getMessage()));
            provider.markPopulated(chunkX, chunkZ);
            return false;
        } finally {
            GenLayerLock.release();
        }
    }

    /**
     * Reduces a finished chunk to plain data the client thread can safely read.
     *
     * <p>Called on the worker while the chunk is certainly still cached. Nothing that references
     * the simulated world escapes.
     */
    public ChunkSnapshot snapshot(int chunkX, int chunkZ) {
        Chunk chunk = provider.getLoadedChunk(chunkX, chunkZ);
        if (chunk == null || provider.hasFailed(chunkX, chunkZ)) {
            return ChunkSnapshot.failure(chunkX, chunkZ);
        }
        try {
            int[] heights = new int[64];
            int cursor = 0;
            for (int localX = 0; localX < 16; localX += 2) {
                for (int localZ = 0; localZ < 16; localZ += 2) {
                    heights[cursor++] = chunk.getHeightValue(localX, localZ);
                }
            }

            boolean[] deepSolid = new boolean[36 * 9];
            cursor = 0;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int localX = 0; localX < 16; localX += 3) {
                for (int localZ = 0; localZ < 16; localZ += 3) {
                    for (int y = 8; y < 60; y += 6) {
                        pos.setPos((chunkX << 4) + localX, y, (chunkZ << 4) + localZ);
                        deepSolid[cursor++] = chunk.getBlockState(pos).getMaterial().isSolid();
                    }
                }
            }

            List<ChunkSnapshot.Chest> chests = new ArrayList<ChunkSnapshot.Chest>();
            for (TileEntity tile : new ArrayList<TileEntity>(chunk.getTileEntityMap().values())) {
                if (!(tile instanceof TileEntityLockableLoot)) {
                    continue;
                }
                TileEntityLockableLoot container = (TileEntityLockableLoot) tile;
                ResourceLocation table = container.getLootTable();
                if (table == null) {
                    continue;
                }
                Long lootSeed = LootSeedReader.read(container);
                if (lootSeed == null) {
                    continue;
                }
                BlockPos at = tile.getPos();
                chests.add(new ChunkSnapshot.Chest(at, table, lootSeed.longValue()));
            }
            // Deliberately exclude mineshaft minecart chests. They are entities generated by the
            // recursively assembled shaft graph, so an incomplete local window can assign a
            // plausible but wrong cart/loot seed. Only TileEntityLockableLoot containers are
            // reported by this standalone build.
            return new ChunkSnapshot(chunkX, chunkZ, heights, deepSolid, chests, false);
        } catch (Throwable error) {
            failure = "snapshot(" + chunkX + "," + chunkZ + ") failed: "
                + error.getClass().getSimpleName();
            return ChunkSnapshot.failure(chunkX, chunkZ);
        }
    }

    /** Tile entities the generator attached inside a finished chunk. */
    /** The already-generated chunk, or null. Never triggers generation. */
    public Chunk chunkAt(int chunkX, int chunkZ) {
        return provider.getLoadedChunk(chunkX, chunkZ);
    }

    public List<TileEntity> tileEntitiesIn(int chunkX, int chunkZ) {
        Chunk chunk = provider.getLoadedChunk(chunkX, chunkZ);
        if (chunk == null) {
            return new ArrayList<TileEntity>();
        }
        return new ArrayList<TileEntity>(chunk.getTileEntityMap().values());
    }

    /** Result of comparing a simulated chunk against the one the server sent. */
    public static final class Agreement {
        /** Percentage of columns whose surface height matches exactly. Identifies the seed. */
        public final int surface;
        /** Percentage of deep sample points that agree on solid-vs-air. Detects digging. */
        public final int density;

        Agreement(int surface, int density) {
            this.surface = surface;
            this.density = density;
        }
    }

    /**
     * Compares simulated terrain against the chunk the server actually sent.
     *
     * <p>This is the honesty check, and it deliberately measures two different things.
     *
     * <p><b>Surface height</b> answers "is this the same world?". Terrain height is enormously
     * sensitive to the seed -- a wrong seed produces a completely different landscape -- so
     * matching heights is strong evidence the simulation is reproducing this world. Comparing
     * solid-vs-air underground cannot do this job: below y=60 nearly everything is stone in any
     * overworld, so even an unrelated seed scores highly.
     *
     * <p><b>Deep density</b> answers "has anyone been here?". Players dig; the simulation does
     * not know about it. A high surface score with a lower density score is the signature of the
     * right seed in a world that has been mined -- which is exactly when a predicted chest is
     * long gone.
     *
     * @return the two figures, or null if the chunks could not be compared
     */
    public Agreement agreementWith(Chunk real, int chunkX, int chunkZ) {
        Chunk simulated = provider.getLoadedChunk(chunkX, chunkZ);
        if (simulated == null || real == null) {
            return null;
        }
        try {
            int surfaceMatched = 0;
            int surfaceSampled = 0;
            for (int localX = 0; localX < 16; localX += 2) {
                for (int localZ = 0; localZ < 16; localZ += 2) {
                    surfaceSampled++;
                    if (simulated.getHeightValue(localX, localZ)
                        == real.getHeightValue(localX, localZ)) {
                        surfaceMatched++;
                    }
                }
            }

            int deepMatched = 0;
            int deepSampled = 0;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int localX = 0; localX < 16; localX += 3) {
                for (int localZ = 0; localZ < 16; localZ += 3) {
                    for (int y = 8; y < 60; y += 6) {
                        pos.setPos((chunkX << 4) + localX, y, (chunkZ << 4) + localZ);
                        IBlockState simState = simulated.getBlockState(pos);
                        IBlockState realState = real.getBlockState(pos);
                        deepSampled++;
                        if (simState.getMaterial().isSolid()
                            == realState.getMaterial().isSolid()) {
                            deepMatched++;
                        }
                    }
                }
            }
            if (surfaceSampled == 0 || deepSampled == 0) {
                return null;
            }
            return new Agreement((surfaceMatched * 100) / surfaceSampled,
                (deepMatched * 100) / deepSampled);
        } catch (Throwable error) {
            return null;
        }
    }

    public SimWorld getWorld() {
        return world;
    }

    public void clear() {
        provider.clear();
        chunksCompleted = 0;
    }
}
