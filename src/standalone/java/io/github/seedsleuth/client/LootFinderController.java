package io.github.seedsleuth.client;

import io.github.seedsleuth.loot.LootScanner;
import io.github.seedsleuth.loot.SimulatedLootRadar;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldType;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Keeps this mod deliberately narrow: an entered seed, targets, and nearby predicted loot. */
public final class LootFinderController {
    private final LootScanner scanner = new LootScanner();
    private final SimulatedLootRadar radar = new SimulatedLootRadar();
    private Long seed;
    private int radius = 3;

    public void register() {
        MinecraftForge.EVENT_BUS.register(this);
        ClientCommandHandler.instance.registerCommand(new LootFinderCommand(this));
    }

    public void setSeed(long value) {
        seed = Long.valueOf(value);
        scanner.rescan();
        radar.reset();
    }

    public Long getSeed() { return seed; }
    public LootScanner getScanner() { return scanner; }
    public int getRadius() { return radius; }
    public void setRadius(int value) { radius = Math.max(1, Math.min(8, value)); radar.reset(); scanner.rescan(); }

    public void setEnabled(boolean value) {
        scanner.setEnabled(value);
        if (!value) radar.reset();
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !scanner.isEnabled()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null) return;
        if (LootScanner.canScan(mc)) {
            scanner.tick(mc); // Integrated server: read the actual stored table/seed.
            return;
        }
        if (seed == null) {
            scanner.setLastError("Enter the world's seed first: /lootfinder seed <seed>");
            return;
        }
        if (mc.player.dimension != 0 && mc.player.dimension != 1) {
            scanner.setLastError("Prediction currently supports the Overworld and the End only.");
            return;
        }
        WorldType type = mc.world.getWorldType();
        String options = mc.world.getWorldInfo().getGeneratorOptions();
        radar.configure(seed.longValue(), type, options, mc.player.dimension);
        radar.enqueueAround(((int)Math.floor(mc.player.posX)) >> 4,
            ((int)Math.floor(mc.player.posZ)) >> 4, radius);
        radar.tick(mc, scanner);
    }

    @SubscribeEvent
    public void overlay(RenderGameOverlayEvent.Text event) {
        if (!scanner.isEnabled() || Minecraft.getMinecraft().player == null) return;
        event.getLeft().add(TextFormatting.GOLD + "Loot Finder: " + scanner.hitCount()
            + " match(es), " + scanner.getChestsExamined() + " containers checked");
        if (!LootScanner.canScan(Minecraft.getMinecraft())) {
            event.getLeft().add(TextFormatting.GRAY + "Seed: " + (seed == null ? "not set" : seed)
                + " | " + radar.describeConfidence());
        }
        int x = (int) Minecraft.getMinecraft().player.posX;
        int z = (int) Minecraft.getMinecraft().player.posZ;
        for (LootScanner.LootFind find : scanner.nearest(Minecraft.getMinecraft().player.dimension, x, z, 6))
            event.getLeft().add(TextFormatting.YELLOW + "  " + find.describe());
        if (scanner.hitCount() == 0 && scanner.getLastError() != null)
            event.getLeft().add(TextFormatting.RED + "  " + scanner.getLastError());
    }

    @SubscribeEvent
    public void unload(WorldEvent.Unload event) {
        radar.reset();
        scanner.rescan();
    }

    public void say(String message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player != null) mc.player.sendMessage(new TextComponentString(TextFormatting.GOLD + "[LootFinder] " + TextFormatting.RESET + message));
    }
}
