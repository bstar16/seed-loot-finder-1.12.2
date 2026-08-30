package io.github.seedsleuth.sim;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldProviderEnd;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.WorldInfo;

/**
 * A private, in-memory copy of a world, used to re-run vanilla generation for a known seed.
 *
 * <p>This is what lets loot be predicted while still connected to a multiplayer server. The
 * server never sends chest contents, but chest contents are a pure function of the seed and the
 * chunk coordinates, so generating the same chunk here produces the same chest with the same
 * loot seed.
 *
 * <p>It is deliberately inert: no entities tick, no light updates propagate outward, no
 * neighbour notifications fire, nothing is ever saved. The only things that matter are the
 * blocks the generator writes and the tile entities it attaches to them.
 *
 * <p>Weather decoration is NOT suppressed. Snow layers and ice are placed at the very end of
 * {@code populate} and consume no randomness, so skipping them cannot move a loot seed -- but
 * they do sit on top of the terrain, and a missing snow layer shifts a column's height by one.
 * Since the surface heightmap is what proves the simulation is reproducing the right world,
 * dropping them would make cold biomes look like a mismatch. {@code generateSkylightMap} runs
 * during chunk generation, so vanilla's light checks work here unmodified.
 */
public final class SimWorld extends World {
    private final SimChunkProvider simProvider;

    public SimWorld(long seed, WorldType worldType, String generatorOptions, int dimension) {
        super(new SimSaveHandler(), buildInfo(seed, worldType, generatorOptions),
            dimension == 1 ? new WorldProviderEnd() : new WorldProviderSurface(), new Profiler(), false);
        this.provider.setWorld(this);
        this.provider.setDimension(dimension);
        this.simProvider = new SimChunkProvider();
        this.chunkProvider = this.simProvider;
        this.simProvider.setEmptyChunk(new Chunk(this, 0, 0));
    }

    private static WorldInfo buildInfo(long seed, WorldType worldType, String generatorOptions) {
        WorldSettings settings = new WorldSettings(seed, GameType.SURVIVAL, true, false,
            worldType == null ? WorldType.DEFAULT : worldType);
        if (generatorOptions != null && !generatorOptions.isEmpty()) {
            settings = settings.setGeneratorOptions(generatorOptions);
        }
        return new WorldInfo(settings, "seedsleuth-sim");
    }

    SimChunkProvider simProvider() {
        return simProvider;
    }

    @Override
    protected IChunkProvider createChunkProvider() {
        return simProvider;
    }

    @Override
    protected boolean isChunkLoaded(int x, int z, boolean allowEmpty) {
        return simProvider.hasTerrain(x, z);
    }

    /**
     * Writes a block straight into its chunk.
     *
     * <p>Vanilla's implementation would also relight, notify neighbours and fire block-update
     * events. None of that changes which chest gets which loot seed, and all of it is expensive
     * or actively dangerous outside a real world, so it is skipped. {@code Chunk.setBlockState}
     * still runs, which is what creates the tile entities this whole class exists to read.
     */
    @Override
    public boolean setBlockState(BlockPos pos, IBlockState newState, int flags) {
        if (isOutsideBuildHeight(pos)) {
            return false;
        }
        Chunk chunk = this.getChunk(pos);
        if (chunk == null) {
            return false;
        }
        return chunk.setBlockState(pos, newState) != null;
    }

    @Override
    public boolean isAirBlock(BlockPos pos) {
        return getBlockState(pos).getBlock() == Blocks.AIR;
    }

    /**
     * Every position inside the build height counts as loaded; the chunk provider generates on
     * demand. This is what lets vanilla's light and freeze checks work here at all.
     */
    @Override
    public boolean isBlockLoaded(BlockPos pos, boolean allowEmpty) {
        return !isOutsideBuildHeight(pos);
    }

    /**
     * A real, empty map storage rather than null.
     *
     * <p>{@code MapGenStructure.initializeStructureData} asks for it before generating any
     * structure, so a null here is an NPE the moment a stronghold or village start is built.
     * It is backed by a save handler that writes nothing, so this stays in memory.
     */
    @Override
    public net.minecraft.world.storage.MapStorage getPerWorldStorage() {
        if (perWorldStorage == null) {
            perWorldStorage = new net.minecraft.world.storage.MapStorage(getSaveHandler());
        }
        return perWorldStorage;
    }

    /** Nothing observes this world, so there is nothing to tell. */
    @Override
    public void notifyBlockUpdate(BlockPos pos, IBlockState oldState, IBlockState newState,
                                  int flags) {
        // Deliberately empty.
    }

    @Override
    public void notifyNeighborsRespectDebug(BlockPos pos, net.minecraft.block.Block blockType,
                                            boolean updateObservers) {
        // Deliberately empty.
    }

    @Override
    public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {
        // Deliberately empty.
    }

    @Override
    public void playSound(net.minecraft.entity.player.EntityPlayer player, double x, double y,
                          double z, net.minecraft.util.SoundEvent sound,
                          net.minecraft.util.SoundCategory category, float volume, float pitch) {
        // Deliberately empty.
    }

    @Override
    public net.minecraft.entity.Entity getEntityByID(int id) {
        return null;
    }

    /**
     * Adds the entity to its chunk and nothing else.
     *
     * <p>Vanilla's implementation posts {@code EntityJoinWorldEvent} on the Forge bus. This
     * world runs on the simulation worker thread, and handing another mod's event handler a
     * private world on a background thread is a crash (or worse) waiting to happen. Mineshaft
     * pieces spawn their chest minecarts through here; the chunk entity list is all the
     * snapshot reads.
     */
    @Override
    public boolean spawnEntity(net.minecraft.entity.Entity entity) {
        int chunkX = net.minecraft.util.math.MathHelper.floor(entity.posX / 16.0D);
        int chunkZ = net.minecraft.util.math.MathHelper.floor(entity.posZ / 16.0D);
        if (!isChunkLoaded(chunkX, chunkZ, true)) {
            return false;
        }
        getChunk(chunkX, chunkZ).addEntity(entity);
        loadedEntityList.add(entity);
        return true;
    }
}
