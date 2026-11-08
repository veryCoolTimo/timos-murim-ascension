package io.github.verycooltimo.murim.client.decor;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/** Small paper tablet with dark wooden edging. Coordinates follow the caller's text plane (+z faces the reader).
 * API: client/VaultDoorRenderer#vertex and client/npc/GuestRenderer#render; reuses the existing plaster texture.
 */
public final class ScrollPanel {
    private static final ResourceLocation PAPER = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/block/white_plaster.png");
    private ScrollPanel() { }

    public static void draw(PoseStack pose, MultiBufferSource buffers, float left, float top, float right, float bottom,
                            float border, float alpha) {
        rect(pose, buffers, left, top, right, bottom, -0.012F, 0.19F, 0.12F, 0.07F, alpha);
        rect(pose, buffers, left + border, top + border, right - border, bottom - border, -0.006F,
                0.96F, 0.87F, 0.67F, alpha);
    }

    private static void rect(PoseStack pose, MultiBufferSource buffers, float x0, float y0, float x1, float y1, float z,
                             float r, float g, float b, float alpha) {
        var vc = buffers.getBuffer(RenderType.entityTranslucent(PAPER));
        float[][] points = {{x0, y0, 0, 0}, {x1, y0, 1, 0}, {x1, y1, 1, 1}, {x0, y1, 0, 1}};
        for (float[] p : points) {
            vc.addVertex(pose.last(), p[0], p[1], z).setColor(r, g, b, alpha).setUv(p[2], p[3])
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose.last(), 0, 0, 1);
        }
    }
}
