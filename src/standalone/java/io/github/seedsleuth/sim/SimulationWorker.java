package io.github.seedsleuth.sim;

import net.minecraft.world.WorldType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the world simulation on its own thread and hands back plain results.
 *
 * <h2>Why a thread and not a tick budget</h2>
 * Sliced on the client thread the simulation gets a tenth of each tick, so it generates perhaps
 * twenty chunks a second and falls steadily behind a player flying at speed. On its own thread it
 * runs continuously and comfortably outpaces the server's chunk stream. What made that
 * impossible before was {@code IntCache}; {@link GenLayerLock} solves it.
 *
 * <p>The simulated world is touched by this thread and no other. Requests arrive as coordinates
 * and results leave as {@link ChunkSnapshot}, so there is no shared mutable state to get wrong.
 */
public final class SimulationWorker {
    private static final int MAX_PENDING = 8192;

    private final ConcurrentLinkedQueue<long[]> requests = new ConcurrentLinkedQueue<long[]>();
    private final ConcurrentLinkedQueue<ChunkSnapshot> results =
        new ConcurrentLinkedQueue<ChunkSnapshot>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger completed = new AtomicInteger();
    private final AtomicLong restarts = new AtomicLong();
    private final AtomicReference<String> failure = new AtomicReference<String>();
    /** Bumped whenever the world is rebuilt, so stale results can be discarded. */
    private final AtomicInteger epoch = new AtomicInteger();

    private final long seed;
    private final WorldType worldType;
    private final String generatorOptions;
    private final int dimension;

    private Thread thread;
    private WorldSimulator simulator;
    private final Set<Long> emitted = new HashSet<Long>();

    public SimulationWorker(long seed, WorldType worldType, String generatorOptions, int dimension) {
        this.seed = seed;
        this.worldType = worldType;
        this.generatorOptions = generatorOptions;
        this.dimension = dimension;
    }

    public long getSeed() {
        return seed;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "SeedSleuth-worldgen");
        thread.setDaemon(true);
        // Below normal: predicting loot must never cost the player frames.
        thread.setPriority(Thread.MIN_PRIORITY + 2);
        thread.start();
    }

    public void stop() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    /** @return false when the backlog is already full */
    public boolean request(int chunkX, int chunkZ) {
        if (pending.get() >= MAX_PENDING) {
            return false;
        }
        requests.add(new long[] {chunkX, chunkZ});
        pending.incrementAndGet();
        return true;
    }

    public ChunkSnapshot poll() {
        return results.poll();
    }

    public int getPending() {
        return pending.get();
    }

    public int getCompleted() {
        return completed.get();
    }

    public int getRestarts() {
        return (int) restarts.get();
    }

    public String getFailure() {
        return failure.get();
    }

    private void loop() {
        simulator = new WorldSimulator(seed, worldType, generatorOptions, dimension);
        while (running.get()) {
            long[] request = requests.poll();
            if (request == null) {
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            pending.decrementAndGet();
            try {
                process((int) request[0], (int) request[1]);
            } catch (Throwable error) {
                failure.set(error.getClass().getSimpleName() + " in worker: "
                    + error.getMessage());
            }
        }
    }

    private void process(int chunkX, int chunkZ) {
        // No deadline: this thread has nothing else to do, so run the chunk to completion.
        simulator.advance(chunkX, chunkZ, Long.MAX_VALUE);
        completed.incrementAndGet();

        // Populating a chunk finishes some of its neighbours too. Emit each one exactly once,
        // and do it now while it is certainly still cached.
        List<ChunkSnapshot> batch = new ArrayList<ChunkSnapshot>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int readyX = chunkX + dx;
                int readyZ = chunkZ + dz;
                if (!simulator.isComplete(readyX, readyZ)) {
                    continue;
                }
                if (!emitted.add(Long.valueOf(pack(readyX, readyZ)))) {
                    continue;
                }
                batch.add(simulator.snapshot(readyX, readyZ));
            }
        }
        results.addAll(batch);

        if (simulator.getFailure() != null) {
            failure.set(simulator.getFailure());
        }
        if (simulator.isOverCapacity()) {
            // Chunks cannot be evicted piecemeal without silently half-generating them, so the
            // whole world is rebuilt instead. Already-emitted results stay valid.
            simulator = new WorldSimulator(seed, worldType, generatorOptions, dimension);
            emitted.clear();
            restarts.incrementAndGet();
            epoch.incrementAndGet();
        }
    }

    private static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
