package io.github.seedsleuth.client;

import io.github.seedsleuth.loot.LootScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.tileentity.TileEntityBeaconRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.List;

/** Draws a gold beacon-style guide beam from each nearby matching chest to build height. */
public final class LootBeaconRenderer {
    private static final double MAX_DISTANCE = 512.0D;
    private static final int MAX_BEAMS = 24;
    private static final float[] LOOT_BEAM = {1.0F, 0.78F, 0.0F};
    private final LootFinderController controller;
    private final Minecraft minecraft = Minecraft.getMinecraft();

    public LootBeaconRenderer(LootFinderController controller) {
        this.controller = controller;
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (minecraft.world == null || minecraft.player == null
            || !controller.getScanner().isEnabled()) return;
        List<LootScanner.LootFind> finds = controller.getScanner().nearest(
            minecraft.player.dimension, (int)minecraft.player.posX, (int)minecraft.player.posZ,
            MAX_BEAMS);
        if (finds.isEmpty()) return;
        Entity view = minecraft.getRenderViewEntity();
        if (view == null) return;

        float partial = event.getPartialTicks();
        double cameraX = view.lastTickPosX + (view.posX - view.lastTickPosX) * partial;
        double cameraY = view.lastTickPosY + (view.posY - view.lastTickPosY) * partial;
        double cameraZ = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partial;
        GlStateManager.pushMatrix();
        GlStateManager.disableLighting();
        minecraft.getTextureManager().bindTexture(TileEntityBeaconRenderer.TEXTURE_BEACON_BEAM);
        try {
            for (LootScanner.LootFind find : finds) {
                BlockPos pos = find.pos;
                double x = pos.getX() + 0.5D - cameraX;
                double z = pos.getZ() + 0.5D - cameraZ;
                if (x * x + z * z > MAX_DISTANCE * MAX_DISTANCE) continue;
                int height = Math.max(16, 256 - pos.getY());
                TileEntityBeaconRenderer.renderBeamSegment(x - 0.5D, pos.getY() - cameraY,
                    z - 0.5D, partial, 1.0D, minecraft.world.getTotalWorldTime(), 0, height,
                    LOOT_BEAM);
            }
        } catch (Throwable error) {
            controller.getScanner().setLastError("Beam rendering failed: "
                + error.getClass().getSimpleName());
        } finally {
            GlStateManager.enableLighting();
            GlStateManager.popMatrix();
        }
    }
}
