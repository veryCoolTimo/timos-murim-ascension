package io.github.verycooltimo.murim.client.flow;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.vfx.*;
import io.github.verycooltimo.murim.cultivation.flow.FlowGame;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Body-bound game; API and render-stage pattern: MeditationVfxRenderer, BodyMeridians, VfxDraw. */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class FlowBodyRenderer {
    private static final int RED = 0xE2485A;
    private final FlowBodySurface body;
    private final PoseStack.Pose pose;
    private VertexConsumer consumer;
    private final float visibility;
    // Render-thread resources only; released as soon as the flow ends, rebuilt on resize.
    private static RenderTarget worldDepth;
    private static RenderTarget sceneDepth;
    private static boolean depthReady;

    private FlowBodyRenderer(FlowBodySurface body, PoseStack.Pose pose, VertexConsumer consumer, float visibility) {
        this.body = body;
        this.pose = pose;
        this.consumer = consumer;
        this.visibility = visibility;
    }

    @SubscribeEvent
    static void onRender(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) {
            depthReady = false;
            if (!FlowClient.active()) {
                releaseDepth();
                return;
            }
            // API: NeoForge 1.21.1 RenderLevelStageEvent.Stage; terrain precedes entities.
            // API: Mojang 1.21.1 client mappings, RenderTarget#copyDepthFrom and TextureTarget ctor.
            RenderTarget main = mc.getMainRenderTarget();
            if (worldDepth == null || worldDepth.width != main.width || worldDepth.height != main.height) {
                releaseDepth();
                worldDepth = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
                sceneDepth = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
            }
            worldDepth.copyDepthFrom(main);
            main.bindWrite(false);
            depthReady = true;
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || !FlowClient.active() || !depthReady) return;
        if (mc.player == null) return;
        Vec3 bottom = BoneAnchorLayer.position(mc.player, BoneAnchorLayer.Bone.FLOW_BOTTOM);
        Vec3 top = BoneAnchorLayer.position(mc.player, BoneAnchorLayer.Bone.FLOW_TOP);
        Vec3 front = BoneAnchorLayer.position(mc.player, BoneAnchorLayer.Bone.FLOW_FRONT);
        if (bottom == null || top == null || front == null) return;
        FlowBodySurface surface = new FlowBodySurface(bottom, top, front);
        Vec3 camera = event.getCamera().getPosition();
        PoseStack stack = event.getPoseStack();
        RenderTarget main = mc.getMainRenderTarget();
        // AFTER_LEVEL has finished Fabulous composition as well. Preserve every entity's depth.
        mc.renderBuffers().bufferSource().endBatch();
        sceneDepth.copyDepthFrom(main);
        stack.pushPose();
        try {
            main.copyDepthFrom(worldDepth);
            main.bindWrite(false);
            stack.translate(-camera.x, -camera.y, -camera.z);
            var buffers = mc.renderBuffers().bufferSource();
            FlowBodyRenderer renderer = new FlowBodyRenderer(surface, stack.last(),
                    buffers.getBuffer(MurimRenderTypes.flowLine()), FlowClient.visibility());
            renderer.draw(FlowClient.game());
            buffers.endBatch(MurimRenderTypes.flowLine());
            renderer.consumer = buffers.getBuffer(MurimRenderTypes.flowMote());
            renderer.drawClot(FlowClient.game());
            buffers.endBatch(MurimRenderTypes.flowMote());
        } finally {
            main.copyDepthFrom(sceneDepth);
            main.bindWrite(false);
            depthReady = false;
            stack.popPose();
        }
    }

    private static void releaseDepth() {
        if (worldDepth != null) worldDepth.destroyBuffers();
        if (sceneDepth != null) sceneDepth.destroyBuffers();
        worldDepth = sceneDepth = null;
    }

    private void draw(FlowGame g) {
        int colour = g.pill.colour();
        for (int i = 0; i < 240; i++) {
            double s = i / 240.0D;
            double[] a = g.route.at(s), b = g.route.at((i + 1) / 240.0D);
            line(a[0], a[1], b[0], b[1], s < g.s ? .022D : .0175D,
                    s < g.s ? colour : 0xD8DCE8, s < g.s ? .85F : .28F);
        }
        for (double[] span : g.poison) {
            for (double s = span[0]; s < span[1]; s += .012D) {
                double[] a = g.route.at(s), b = g.route.at(Math.min(span[1], s + .006D));
                line(a[0], a[1], b[0], b[1], .020D, 0x65FF78, .9F);
            }
        }
        for (FlowGame.Narrow n : g.narrows) {
            double[] p = g.route.at(n.s);
            ring(p[0], p[1], .060D + .004D * Math.sin(g.t * 6.283D / n.period + n.phase),
                    n.open ? colour : RED, .95F);
        }
        for (FlowGame.Fork f : g.forks) {
            double[] p = g.route.at(f.s), dir = g.route.dir(f.s);
            for (int side : new int[] {-1, 1}) {
                boolean blocked = side != f.good;
                double x = p[0] + dir[0] * .075D - dir[1] * side * .085D;
                double y = p[1] + dir[1] * .075D + dir[0] * side * .085D;
                line(p[0], p[1], x, y, .0175D, blocked ? RED : colour, blocked ? .8F : .85F);
                if (blocked) ring(x, y, .025D, RED, .85F);
            }
        }
        if (g.state == FlowGame.State.DONE) {
            float fade = (float) Math.exp(-g.end * 2.5D);
            double size = .035D * Math.min(1, g.end * 18);
            star(.5D, .8D, size, g.pill.core(), fade);
        } else if (g.state == FlowGame.State.TORN || g.state == FlowGame.State.BURST) {
            float fade = (float) Math.max(0, 1 - g.end / .7D);
            for (int i = 0; i < 7; i++) {
                double a = Math.PI * (i + .5D) / 7;
                line(.5D, .8D, .5D + Math.cos(a) * .09D, .8D - Math.sin(a) * .18D, .0175D, RED, fade);
            }
        }
    }

    private void drawClot(FlowGame g) {
        if (g.state != FlowGame.State.RUN) return;
        int colour = g.pill.colour();
        for (FlowGame.Trail t : g.trail) {
            if (t.age < .24D) dot(t.x, t.y, .025D, colour, (float) (.35D * (1 - t.age / .24D)));
        }
        double[] p = g.route.at(g.s);
        float flicker = g.surging() ? .8F + .15F * (float) Math.sin(g.t * 35) : .95F;
        dot(p[0], p[1], .09D, colour, .32F * flicker);
        dot(p[0], p[1], .03D, g.pill.core(), flicker);
    }

    private void dot(double x, double y, double radius, int rgb, float alpha) {
        double rx = radius / body.xScale(), ry = radius / body.yScale();
        VfxColour c = colour(rgb);
        vertex(body.at(x - rx, y - ry), 0, 0, c, alpha);
        vertex(body.at(x - rx, y + ry), 0, 1, c, alpha);
        vertex(body.at(x + rx, y + ry), 1, 1, c, alpha);
        vertex(body.at(x + rx, y - ry), 1, 0, c, alpha);
    }

    private void star(double x, double y, double size, int rgb, float alpha) {
        VfxColour c = colour(rgb);
        for (int i = 0; i < 4; i++) {
            double angle = i * Math.PI / 2;
            double dx = Math.cos(angle), dy = Math.sin(angle);
            vertex(body.at(x - dy * size * .16D, y + dx * size * .16D), 0, .5F, c, alpha);
            vertex(body.at(x + dx * size, y + dy * size), .5F, 0, c, alpha);
            vertex(body.at(x + dy * size * .16D, y - dx * size * .16D), 1, .5F, c, alpha);
            vertex(body.at(x, y), .5F, 1, c, alpha);
        }
    }

    private static VfxColour colour(int rgb) {
        return new VfxColour(((rgb >> 16) & 255) / 255F, ((rgb >> 8) & 255) / 255F, (rgb & 255) / 255F);
    }

    private void ring(double x, double y, double radius, int rgb, float alpha) {
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8, b = (i + 1) * Math.PI / 8;
            line(x + Math.cos(a) * radius / body.xScale(), y + Math.sin(a) * radius / body.yScale(),
                    x + Math.cos(b) * radius / body.xScale(), y + Math.sin(b) * radius / body.yScale(), .007D, rgb, alpha);
        }
    }

    private void line(double x0, double y0, double x1, double y1, double width, int rgb, float alpha) {
        double dx = (x1 - x0) * body.xScale(), dy = (y1 - y0) * body.yScale();
        double length = Math.hypot(dx, dy);
        if (length < 1.0E-8D) return;
        double nx = -dy / length * width / body.xScale(), ny = dx / length * width / body.yScale();
        VfxColour c = colour(rgb);
        vertex(body.at(x0 - nx, y0 - ny), 0, 0, c, alpha);
        vertex(body.at(x1 - nx, y1 - ny), 1, 0, c, alpha);
        vertex(body.at(x1 + nx, y1 + ny), 1, 1, c, alpha);
        vertex(body.at(x0 + nx, y0 + ny), 0, 1, c, alpha);
    }

    private void vertex(Vec3 p, float u, float v, VfxColour c, float alpha) {
        VfxDraw.vertex(consumer, pose, p, body.front(), u, v, alpha * visibility, c.red(), c.green(), c.blue());
    }
}
