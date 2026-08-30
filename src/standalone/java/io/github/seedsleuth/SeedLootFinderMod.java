package io.github.seedsleuth;

import io.github.seedsleuth.client.LootFinderController;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

/** Client-only, seed-input loot finder for vanilla-compatible 1.12.2 worlds. */
@Mod(modid = SeedLootFinderMod.MOD_ID, name = SeedLootFinderMod.NAME,
    version = SeedLootFinderMod.VERSION, clientSideOnly = true, acceptableRemoteVersions = "*")
public final class SeedLootFinderMod {
    public static final String MOD_ID = "seedlootfinder";
    public static final String NAME = "Seed Loot Finder";
    public static final String VERSION = "1.0.1";

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        new LootFinderController().register();
    }
}
