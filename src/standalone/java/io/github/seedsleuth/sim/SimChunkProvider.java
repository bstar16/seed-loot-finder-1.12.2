package io.github.seedsleuth.sim;

import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.IChunkGenerator;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Holds the chunks the simulator has generated so far.
 *
 * <p>Terrain is produced on demand, but population is never triggered from here: populating a
 * chunk writes into its neighbours, so {@link WorldSimulator} drives that explicitly and in the
 * right order. Asking for a chunk only ever gets you raw terrain.
 */
final class SimChunkProvider implements IChunkProvider {
    /**
     * Chunks kept in memory before the whole simulation is restarted.
     *
     * <p>This used to be an LRU that quietly evicted the oldest chunk. That was wrong: the
     * populate-state flags live in a separate set, so an evicted chunk would be pulled back in by
     * a later neighbouring {@code populate} as bare terrain while still being flagged complete.
     * Bare terrain has no trees, which wrecks the heightmap comparison, and no chests, which
     * silently loses finds. Re-running populate on it is not a fix either -- that duplicates
     * every feature.
     *
     * <p>There is no way to partially invalidate this consistently, so the simulation is dropped
     * wholesale instead. Restarting is cheap and obviously correct; half-generated chunks are
     * neither.
     */
    private static final int CACHE_LIMIT = 3072;

    private final LinkedHashMap<Long, Chunk> chunks = new LinkedHashMap<Long, Chunk>(512);
    private final Set<Long> populated = new HashSet<Long>();
    private final Set<Long> generating = new HashSet<Long>();
    private final Map<Long, Boolean> failed = new HashMap<Long, Boolean>();
    private IChunkGenerator generator;
    private Chunk emptyChunk;

    void setGenerator(IChunkGenerator generator) {
        this.generator = generator;
    }

    void setEmptyChunk(Chunk emptyChunk) {
        this.emptyChunk = emptyChunk;
    }

    static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    @Override
    public Chunk getLoadedChunk(int x, int z) {
        return chunks.get(Long.valueOf(key(x, z)));
    }

    @Override
    public Chunk provideChunk(int x, int z) {
        Long id = Long.valueOf(key(x, z));
        Chunk existing = chunks.get(id);
        if (existing != null) {
            return existing;
        }
        if (generator == null || failed.containsKey(id)) {
            return emptyChunk;
        }
        // Terrain generation must never re-enter for the same chunk; if it somehow does, hand
        // back the empty chunk rather than recursing forever.
        if (!generating.add(id)) {
            return emptyChunk;
        }
        try {
            Chunk chunk = generator.generateChunk(x, z);
            chunk.setTerrainPopulated(false);
            chunks.put(id, chunk);
            return chunk;
        } catch (Throwable error) {
            failed.put(id, Boolean.TRUE);
            return emptyChunk;
        } finally {
            generating.remove(id);
        }
    }

    boolean hasTerrain(int x, int z) {
        return chunks.containsKey(Long.valueOf(key(x, z)));
    }

    boolean isPopulated(int x, int z) {
        return populated.contains(Long.valueOf(key(x, z)));
    }

    void markPopulated(int x, int z) {
        populated.add(Long.valueOf(key(x, z)));
    }

    boolean hasFailed(int x, int z) {
        return failed.containsKey(Long.valueOf(key(x, z)));
    }

    void markFailed(int x, int z) {
        failed.put(Long.valueOf(key(x, z)), Boolean.TRUE);
    }

    int cachedChunks() {
        return chunks.size();
    }

    /** True when the working set has grown past what can be held consistently. */
    boolean isOverCapacity() {
        return chunks.size() > CACHE_LIMIT;
    }

    void clear() {
        chunks.clear();
        populated.clear();
        failed.clear();
        generating.clear();
    }

    @Override
    public boolean tick() {
        return false;
    }

    @Override
    public String makeString() {
        return "SeedSleuth simulation: " + chunks.size() + " chunks";
    }

    @Override
    public boolean isChunkGeneratedAt(int x, int z) {
        return chunks.containsKey(Long.valueOf(key(x, z)));
    }
}
