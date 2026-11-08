package io.github.verycooltimo.murim.client.flow;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector4f;

/** Soft focus around the seated player. No texture, post-chain or world lighting changes.
 * API: MeditationVfxRenderer.project and GuiShapes.arc (GUI quads with interpolated vertex colour).
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class FlowFocusMask {
    private static float centreX, centreY;
    private static boolean ready;

    private FlowFocusMask() { }

    @SubscribeEvent
    static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        ready = false;
        Minecraft mc = Minecraft.getInstance();
        if (!FlowClient.active() || mc.player == null) return;
        Vec3 chest = BoneAnchorLayer.position(mc.player, BoneAnchorLayer.Bone.CHEST);
        if (chest == null) return;
        Vec3 camera = event.getCamera().getPosition();
        Vector4f v = new Vector4f((float) (chest.x - camera.x), (float) (chest.y - camera.y),
                (float) (chest.z - camera.z), 1);
        event.getModelViewMatrix().transform(v);
        event.getProjectionMatrix().transform(v);
        if (v.w() <= 1.0E-4F) return;
        centreX = v.x() / v.w() * .5F + .5F;
        centreY = .5F - v.y() / v.w() * .5F;
        ready = true;
    }

    static void render(GuiGraphics g, float visibility) {
        if (!ready || visibility <= 0) return;
        float w = g.guiWidth(), h = g.guiHeight();
        float cx = Math.max(0, Math.min(w, centreX * w));
        float cy = Math.max(0, Math.min(h, centreY * h));
        float clearRadius = .35F * h;
        // Flush first so the black, alpha-blended GUI pass owns its blend state; RGB is always 0.
        g.flush();
        var matrix = g.pose().last().pose();
        var consumer = g.bufferSource().getBuffer(RenderType.gui());
        for (int y = 0; y < 48; y++) {
            for (int x = 0; x < 64; x++) {
                for (int corner = 0; corner < 4; corner++) {
                    float px = (x + (corner >= 2 ? 1 : 0)) * w / 64;
                    float py = (y + (corner == 1 || corner == 2 ? 1 : 0)) * h / 48;
                    double dx = px - cx, dy = py - cy;
                    double radius = Math.hypot(dx, dy);
                    // Distance to the viewport boundary along this ray. Every edge reaches .45,
                    // even on ultrawide screens; the centre stays fully clear for .35 screen heights.
                    double edge = radius == 0 ? clearRadius : radius * Math.min(
                            dx == 0 ? Double.POSITIVE_INFINITY : (dx > 0 ? w - cx : cx) / Math.abs(dx),
                            dy == 0 ? Double.POSITIVE_INFINITY : (dy > 0 ? h - cy : cy) / Math.abs(dy));
                    double t = Math.max(0, Math.min(1, (radius - clearRadius) / Math.max(1, edge - clearRadius)));
                    int alpha = (int) Math.round(255 * .45D * visibility * t * t * (3 - 2 * t));
                    consumer.addVertex(matrix, px, py, 0).setColor(alpha << 24);
                }
            }
        }
        g.flush();
    }
}
