package io.github.seedsleuth.loot;

import io.github.seedsleuth.sim.ChunkSnapshot;
import io.github.seedsleuth.sim.SimulationWorker;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.Chunk;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Predicts chest contents while connected to a multiplayer server.
 *
 * <p>The server never sends loot, so the loot is regenerated: a {@link SimulationWorker} runs the
 * real chunk generator for the cracked seed on its own thread, and the chests it produces carry
 * the same loot seeds the server's chests do.
 *
 * <p>This class is the client-thread half. It feeds the worker the chunks the server sends, then
 * drains finished {@link ChunkSnapshot}s and does the two things that can only be done here:
 * comparing against the real world, and rolling the loot.
 */
public final class SimulatedLootRadar {
    /** Below this, the simulation is not reproducing the server and results are suppressed. */
    public static final int MINIMUM_AGREEMENT = 90;
    /** Agreement is averaged over this many recent chunks, not the whole session. */
    private static final int WINDOW = 128;
    /** Several independent terrain samples are required before any prediction is published. */
    private static final int MINIMUM_AGREEMENT_SAMPLES = 8;
    /** Snapshots consumed per tick; each costs a handful of loot rolls. */
    private static final int SNAPSHOTS_PER_TICK = 48;

    private SimulationWorker worker;
    private long seed;
    private boolean seedSet;
    private int dimension;

    private final Set<Long> requested = new HashSet<Long>();

    private final int[] surfaceWindow = new int[WINDOW];
    private final int[] densityWindow = new int[WINDOW];
    private int surfaceSum;
    private int densitySum;
    private int agreementSamples;
    private int deferredComparisons;
    private int chunksSimulated;
    private String status = "idle";

    public boolean isConfigured() {
        return worker != null;
    }

    public long getSeed() {
        return seed;
    }

    public String getStatus() {
        return status;
    }

    public int getChunksSimulated() {
        return chunksSimulated;
    }

    public int getRestarts() {
        return worker == null ? 0 : worker.getRestarts();
    }

    /** Chunks simulated before the server's copy arrived, so no comparison was possible. */
    public int getDeferredComparisons() {
        return deferredComparisons;
    }

    public int getQueueDepth() {
        return worker == null ? 0 : worker.getPending();
    }

    /**
     * How well simulated surface heights match the server's. This is the figure that says
     * whether the seed is right; a wrong seed scores near zero.
     */
    public int getSurfaceAgreement() {
        return agreementSamples == 0 ? -1 : surfaceSum / windowSize();
    }

    /**
     * How well the underground matches. This is diagnostic terrain information only and is not
     * used to hide or classify loot results.
     */
    public int getDensityAgreement() {
        return agreementSamples == 0 ? -1 : densitySum / windowSize();
    }

    public boolean isTrustworthy() {
        return agreementSamples >= MINIMUM_AGREEMENT_SAMPLES
            && getSurfaceAgreement() >= MINIMUM_AGREEMENT;
    }

    /** One-line reading of the two agreement figures. */
    public String describeConfidence() {
        int surface = getSurfaceAgreement();
        int density = getDensityAgreement();
        if (surface < 0) {
            return "comparing against server terrain...";
        }
        if (agreementSamples < MINIMUM_AGREEMENT_SAMPLES) {
            return "verifying seed (" + agreementSamples + "/" + MINIMUM_AGREEMENT_SAMPLES
                + " terrain samples)...";
        }
        if (surface < MINIMUM_AGREEMENT) {
            return "surface " + surface + "% - WRONG WORLD, ignore these finds";
        }
        return "seed confirmed (" + surface + "% surface, " + density
            + "% underground agreement)";
    }

    public void configure(long worldSeed, WorldType worldType, String generatorOptions, int dimension) {
        if (seedSet && seed == worldSeed && this.dimension == dimension && worker != null) {
            return;
        }
        reset();
        this.seed = worldSeed;
        this.dimension = dimension;
        this.seedSet = true;
        this.worker = new SimulationWorker(worldSeed, worldType, generatorOptions, dimension);
        this.worker.start();
        this.status = "ready";
    }

    public void reset() {
        if (worker != null) {
            worker.stop();
        }
        worker = null;
        seedSet = false;
        requested.clear();
        surfaceSum = 0;
        densitySum = 0;
        agreementSamples = 0;
        deferredComparisons = 0;
        chunksSimulated = 0;
        status = "idle";
    }

    /**
     * Queues one chunk, in response to the server actually sending it.
     *
     * <p>Mirroring the server's chunk stream is better targeting than sweeping a radius: it
     * simulates exactly the ground you are being shown.
     */
    public boolean enqueueChunk(int chunkX, int chunkZ) {
        if (worker == null) {
            return false;
        }
        if (!requested.add(Long.valueOf(pack(chunkX, chunkZ)))) {
            return false;
        }
        if (!worker.request(chunkX, chunkZ)) {
            requested.remove(Long.valueOf(pack(chunkX, chunkZ)));
            return false;
        }
        return true;
    }

    /** Tops up around a point, for chunks that arrived before the radar was switched on. */
    public void enqueueAround(int centreChunkX, int centreChunkZ, int radius) {
        if (worker == null) {
            return;
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                enqueueChunk(centreChunkX + dx, centreChunkZ + dz);
            }
        }
    }

    /**
     * Drains finished chunks from the worker.
     *
     * <p>Cheap by design: the expensive half is already done on the other thread, so this is a
     * comparison and a few loot rolls per chunk.
     *
     * @return number of new finds
     */
    public int tick(Minecraft minecraft, LootScanner scanner) {
        if (worker == null || minecraft.player == null || minecraft.world == null) {
            return 0;
        }
        if (worker.getFailure() != null) {
            scanner.setLastError(worker.getFailure());
        }

        int found = 0;
        for (int index = 0; index < SNAPSHOTS_PER_TICK; index++) {
            ChunkSnapshot snapshot = worker.poll();
            if (snapshot == null) {
                break;
            }
            chunksSimulated++;
            if (snapshot.failed) {
                continue;
            }
            compareWithServer(minecraft, snapshot);
            // A seed can be valid yet describe another world or generator. Do not publish loot
            // until the simulated terrain has matched several server chunks.
            if (isTrustworthy()) {
                found += harvest(scanner, snapshot);
            }
        }
        int pendingChunks = worker.getPending();
        status = pendingChunks == 0 ? "caught up" : (pendingChunks + " chunks queued");
        return found;
    }

    /**
     * The chunk the server sent, but only once its data has actually been unpacked.
     *
     * <p>{@code ChunkEvent.Load} fires before {@code chunk.read(...)}. Between those two points
     * the chunk exists and {@code isBlockLoaded} says yes, but every block is air and the biome
     * array is still -1. Comparing against one of those poisons the terrain agreement score.
     */
    private static Chunk realChunkWithData(Minecraft minecraft, int chunkX, int chunkZ) {
        if (minecraft.world == null) {
            return null;
        }
        if (!minecraft.world.isBlockLoaded(new BlockPos((chunkX << 4) + 8, 64,
            (chunkZ << 4) + 8))) {
            return null;
        }
        Chunk chunk = minecraft.world.getChunk(chunkX, chunkZ);
        if (chunk == null) {
            return null;
        }
        byte[] biomes = chunk.getBiomeArray();
        return (biomes != null && biomes[(8 << 4) | 8] != -1) ? chunk : null;
    }

    private void compareWithServer(Minecraft minecraft, ChunkSnapshot snapshot) {
        try {
            Chunk real = realChunkWithData(minecraft, snapshot.chunkX, snapshot.chunkZ);
            if (real == null) {
                // Not unpacked yet. Comparing now would poison the average with a chunk of air.
                deferredComparisons++;
                return;
            }
            int surfaceMatched = 0;
            int cursor = 0;
            for (int localX = 0; localX < 16; localX += 2) {
                for (int localZ = 0; localZ < 16; localZ += 2) {
                    if (snapshot.heights[cursor++] == real.getHeightValue(localX, localZ)) {
                        surfaceMatched++;
                    }
                }
            }
            int deepMatched = 0;
            cursor = 0;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int localX = 0; localX < 16; localX += 3) {
                for (int localZ = 0; localZ < 16; localZ += 3) {
                    for (int y = 8; y < 60; y += 6) {
                        pos.setPos((snapshot.chunkX << 4) + localX, y,
                            (snapshot.chunkZ << 4) + localZ);
                        boolean realSolid = real.getBlockState(pos).getMaterial().isSolid();
                        if (snapshot.deepSolid[cursor++] == realSolid) {
                            deepMatched++;
                        }
                    }
                }
            }
            recordAgreement(surfaceMatched * 100 / snapshot.heights.length,
                deepMatched * 100 / snapshot.deepSolid.length);
        } catch (Throwable ignored) {
            // A comparison failure only costs us a confidence sample.
        }
    }

    /**
     * Keeps a rolling window rather than a lifetime average, so the figure describes the ground
     * you are over now. Flying from wilderness into somebody's base should move it.
     */
    private void recordAgreement(int surface, int density) {
        int slot = agreementSamples % WINDOW;
        if (agreementSamples >= WINDOW) {
            surfaceSum -= surfaceWindow[slot];
            densitySum -= densityWindow[slot];
        }
        surfaceWindow[slot] = surface;
        densityWindow[slot] = density;
        surfaceSum += surface;
        densitySum += density;
        agreementSamples++;
    }

    private int windowSize() {
        return Math.min(agreementSamples, WINDOW);
    }

    private int harvest(LootScanner scanner, ChunkSnapshot snapshot) {
        int found = 0;
        for (ChunkSnapshot.Chest chest : snapshot.chests) {
            if (scanner.alreadySeen(dimension, chest.pos)) {
                continue;
            }
            scanner.countChest();
            try {
                List<ItemStack> loot =
                    scanner.rollLoot(null, null, chest.table, chest.lootSeed);
                List<String> matches = scanner.matchesIn(loot);
                if (matches.isEmpty()) {
                    continue;
                }
                if (scanner.record(chest.pos, dimension, chest.table, matches) != null) {
                    found++;
                }
            } catch (Throwable error) {
                scanner.setLastError("Simulated loot roll failed for " + chest.table + ": "
                    + error.getClass().getSimpleName());
            }
        }
        return found;
    }

    private static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
