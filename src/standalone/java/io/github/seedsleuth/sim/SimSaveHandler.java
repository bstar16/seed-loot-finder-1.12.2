package io.github.seedsleuth.sim;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.datafix.DataFixesManager;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.chunk.storage.IChunkLoader;
import net.minecraft.world.gen.structure.template.TemplateManager;
import net.minecraft.world.storage.IPlayerFileData;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.WorldInfo;

import java.io.File;

/**
 * A save handler that saves nothing.
 *
 * <p>{@link net.minecraft.world.World}'s constructor demands one, but the simulated world lives
 * entirely in memory and must never touch disk -- it is a scratch copy of a server's terrain,
 * not a world the player owns.
 */
final class SimSaveHandler implements ISaveHandler {
    @Override
    public WorldInfo loadWorldInfo() {
        return null;
    }

    @Override
    public void checkSessionLock() {
        // Nothing to lock.
    }

    @Override
    public IChunkLoader getChunkLoader(WorldProvider provider) {
        return null;
    }

    @Override
    public void saveWorldInfoWithPlayer(WorldInfo worldInformation, NBTTagCompound tagCompound) {
        // Deliberately empty.
    }

    @Override
    public void saveWorldInfo(WorldInfo worldInformation) {
        // Deliberately empty.
    }

    @Override
    public IPlayerFileData getPlayerNBTManager() {
        return null;
    }

    @Override
    public void flush() {
        // Deliberately empty.
    }

    @Override
    public File getWorldDirectory() {
        return null;
    }

    @Override
    public File getMapFileFromName(String mapName) {
        return null;
    }

    /**
     * A real template manager, not null: fossils, igloos, mansions, and end cities all fetch
     * their NBT templates through this during population, and a null here turns every chunk
     * containing one into a populate failure. Templates load from the game jar's
     * {@code assets/minecraft/structures/}; the directory below is a fallback path that is never
     * written because this world never saves.
     */
    @Override
    public TemplateManager getStructureTemplateManager() {
        if (templates == null) {
            templates = new TemplateManager(
                new File(System.getProperty("java.io.tmpdir"), "seedsleuth-templates").getPath(),
                DataFixesManager.createFixer());
        }
        return templates;
    }

    private TemplateManager templates;
}
