package io.github.seedsleuth.sim;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the client thread needs to know about a simulated chunk, as plain data.
 *
 * <p>The worker owns the simulated world and nothing else may touch it, so results cross the
 * thread boundary as a flat value object rather than as live {@code Chunk} or {@code TileEntity}
 * references. That keeps the rule simple and unbreakable: the simulated world exists on exactly
 * one thread.
 */
public final class ChunkSnapshot {
    /** One loot container the generator produced, reduced to the three facts that matter. */
    public static final class Chest {
        public final BlockPos pos;
        public final ResourceLocation table;
        public final long lootSeed;
        public Chest(BlockPos pos, ResourceLocation table, long lootSeed) {
            this.pos = pos;
            this.table = table;
            this.lootSeed = lootSeed;
        }
    }

    public final int chunkX;
    public final int chunkZ;
    /** Surface height of an 8x8 sample of columns, for identifying the seed. */
    public final int[] heights;
    /** Solid-vs-air at a sparse deep grid, for detecting digging. */
    public final boolean[] deepSolid;
    public final List<Chest> chests;
    public final boolean failed;

    ChunkSnapshot(int chunkX, int chunkZ, int[] heights, boolean[] deepSolid,
                  List<Chest> chests, boolean failed) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.heights = heights;
        this.deepSolid = deepSolid;
        this.chests = chests;
        this.failed = failed;
    }

    static ChunkSnapshot failure(int chunkX, int chunkZ) {
        return new ChunkSnapshot(chunkX, chunkZ, new int[0], new boolean[0],
            new ArrayList<Chest>(), true);
    }
}
