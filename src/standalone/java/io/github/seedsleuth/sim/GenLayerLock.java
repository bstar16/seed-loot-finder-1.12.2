package io.github.seedsleuth.sim;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Serialises every use of vanilla's biome layers anywhere in this mod.
 *
 * <h2>Why this exists</h2>
 * {@code GenLayer} allocates through {@code IntCache}, a process-global array pool. Its methods
 * are synchronized, but its protocol is not: {@code resetIntCache()} hands every array back to
 * the free list while another thread may still be reading one. Two threads generating biomes at
 * once quietly get each other's data -- no exception, just wrong terrain.
 *
 * <p>That single fact is what forced chunk generation and the 16-bit biome stage onto the client
 * thread. Routing all of it through one lock lifts that restriction: generation can run on a
 * worker thread at full speed, and the client-side users simply never overlap with it.
 *
 * <h2>The client must never block</h2>
 * A worker holds this for the length of a chunk generation, which is far too long to stall a
 * frame for. Client-thread callers therefore use {@link #tryAcquire()} and skip their slice when
 * it is busy; both of them are already incremental, so a skipped tick costs nothing.
 *
 * <h2>Residual risk</h2>
 * Vanilla itself can still reach {@code IntCache} without taking this lock, through
 * {@code Chunk.getBiome}'s fallback when a biome byte is 255. That is a "biome not sent" marker
 * and no 1.12.2 server emits it -- ids stop in the 160s -- so in practice the fallback never
 * fires on a multiplayer client. It is the one hole, and it is documented rather than pretended
 * away.
 */
public final class GenLayerLock {
    private static final ReentrantLock LOCK = new ReentrantLock();

    private GenLayerLock() {
    }

    /** Blocks until the biome layers are free. For worker threads only. */
    public static void acquire() {
        LOCK.lock();
    }

    /**
     * Takes the lock only if it is free right now.
     *
     * @return true when acquired; the caller must then release it
     */
    public static boolean tryAcquire() {
        return LOCK.tryLock();
    }

    public static void release() {
        LOCK.unlock();
    }

    public static boolean isBusy() {
        return LOCK.isLocked();
    }
}
