package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Three second command approximation: same scale/beats, 192 rain petals and short CPU trails.
 * Reuses PlumVfx meshing, FallingPetalVfx tumbling and ImpactFlash; no post shader or bloom. */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class PlumRainfallVfx {
    private static final VfxColour PINK = new VfxColour(1.0F, 70 / 255.0F, 123 / 255.0F);
    private static final VfxColour WHITE = new VfxColour(1.0F, 248 / 255.0F, 252 / 255.0F);
    private static final RenderType PETALS = texture("rainfall_petals");
    private static final RenderType LOBES = texture("rainfall_lobe");
    private static final RenderType LINES = texture("qi_shockwave");
    private static final List<Cast> CASTS = new ArrayList<>();
    private static ClientLevel lastLevel;

    private record Petal(int stream, double birth, double size, float spin, double phase, Vec3 offset) { }
    private static final class Cast {
        final Vec3 ground;
        final List<Petal> petals = new ArrayList<>();
        int age;
        Cast(Vec3 ground) {
            this.ground = ground;
            Random r = new Random(240024);
            for (int i = 0; i < 192; i++) {
                int stream = i % 16;
                petals.add(new Petal(stream, 30 + stream % 4 * 6 + i / 16 * .8,
                        .10 + r.nextDouble() * .14, (float) (.03 + r.nextDouble() * .08),
                        r.nextDouble() * Math.PI * 2, new Vec3((r.nextDouble() - .5) * .7, 0, (r.nextDouble() - .5) * .7)));
            }
        }
    }

    public static void emit(ClientLevel level, Vec3 ground) {
        changeLevel(level);
        if (CASTS.size() >= 8) { CASTS.remove(0); }
        CASTS.add(new Cast(ground));
    }

    private static RenderType texture(String name) {
        return RenderType.entityTranslucent(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/" + name + ".png"));
    }

    private static void changeLevel(ClientLevel level) {
        if (lastLevel != level) { CASTS.clear(); lastLevel = level; }
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        changeLevel(mc.level);
        if (mc.level == null || mc.isPaused()) { return; }
        CASTS.removeIf(c -> ++c.age >= 60);
    }

    @SubscribeEvent
    static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        CASTS.clear();
        lastLevel = null;
    }

    @SubscribeEvent
    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || CASTS.isEmpty()) { return; }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level != lastLevel) { return; }
        var buffers = mc.renderBuffers().bufferSource();
        Vec3 camera = event.getCamera().getPosition();
        double partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        PoseStack stack = event.getPoseStack();
        stack.pushPose();
        try {
            stack.translate(-camera.x, -camera.y, -camera.z);
            VertexConsumer lobes = buffers.getBuffer(LOBES);
            for (Cast c : CASTS) { bloom(lobes, stack.last(), c, (c.age + partial) * 3); }
            buffers.endBatch(LOBES);
            VertexConsumer petals = buffers.getBuffer(PETALS);
            for (Cast c : CASTS) {
                double frame = (c.age + partial) * 3;
                for (Petal p : c.petals) {
                    double t = frame - p.birth;
                    if (t < 0 || t >= 120) { continue; }
                    Vec3 at = c.ground.add(path(p.stream, t)).add(p.offset);
                    float fade = (float) Math.min(1, Math.min(t / 3, (120 - t) / 16));
                    float green = (float) PlumVfx.curve(t, 0, 110 / 255.0, 65, 190 / 255.0, 84, 1);
                    float blue = (float) PlumVfx.curve(t, 0, 160 / 255.0, 65, 220 / 255.0, 84, 1);
                    PlumVfx.petal(petals, stack.last(), camera, at,
                            p.size * FallingPetalVfx.tumblingWidth(t / 3, p.phase), p.stream % 4,
                            (float) (t * p.spin + p.phase), fade, 1, green, blue);
                }
                double hit = frame - 132;
                if (hit >= 0 && hit < 40) {
                    for (int i = 0; i < 32; i++) {
                        double a = i * Math.PI * 2 / 32;
                        double h = Math.max(.07, .15 + .06 * hit - .002 * hit * hit);
                        Vec3 at = c.ground.add(Math.cos(a) * hit * .05, h, Math.sin(a) * hit * .05);
                        PlumVfx.petal(petals, stack.last(), camera, at, .18, i % 4,
                                (float) (a + hit * .1), (float) Math.min(1, (40 - hit) / 12), 1, .75F, .83F);
                    }
                }
            }
            buffers.endBatch(PETALS);
            VertexConsumer lines = buffers.getBuffer(LINES);
            for (Cast c : CASTS) {
                double frame = (c.age + partial) * 3;
                if (frame < 100) {
                    double r = PlumVfx.curve(frame, 0, .15, 24, 4.8, 50, 5, 72, 4.8, 100, .1);
                    float fade = (float) Math.min(1, (100 - frame) / 15);
                    ring(lines, stack.last(), c.ground.add(0, 9.3, 0), r, .055, fade);
                    ring(lines, stack.last(), c.ground.add(0, 9.5, 0), r * .72, .04, fade);
                }
                for (Petal p : c.petals) {
                    double t = frame - p.birth;
                    if (t < 2 || t > 102) { continue; }
                    Vec3[] pts = new Vec3[4];
                    for (int j = 0; j < pts.length; j++) {
                        pts[j] = c.ground.add(path(p.stream, Math.max(0, t - (3 - j) * 1.5))).add(p.offset);
                    }
                    PlumVfx.strip(lines, stack.last(), camera, pts, new double[]{0, .007, .012, .018}, .75F, WHITE);
                }
                double hit = frame - 132;
                if (hit >= 0 && hit < 48) {
                    float fade = (float) Math.min(1, (48 - hit) / 15);
                    double r = PlumVfx.curve(hit, 0, .3, 12, 3, 48, 5);
                    ring(lines, stack.last(), c.ground, r, .07, fade);
                    ring(lines, stack.last(), c.ground.add(0, .006, 0), r * .94, .035, fade);
                    if (hit < 9) { ImpactFlash.draw(lines, stack.last(), c.ground.add(0, .25, 0), camera, (float) hit / 9, .45, WHITE, PINK); }
                    if (hit < 18) {
                        for (int i = 0; i < 24; i++) {
                            double a = i * Math.PI * 2 / 24;
                            Vec3 from = c.ground.add(Math.cos(a) * .4, .2, Math.sin(a) * .4);
                            Vec3 to = from.add(Math.cos(a) * (1 + hit * .1), .4 + i % 3 * .3, Math.sin(a) * (1 + hit * .1));
                            PlumVfx.strip(lines, stack.last(), camera, new Vec3[]{from, from.lerp(to, .35), to},
                                    new double[]{0, .04, 0}, (float) (1 - hit / 18), WHITE);
                        }
                    }
                }
            }
            buffers.endBatch(LINES);
        } finally { stack.popPose(); }
    }

    private static Vec3 path(int stream, double t) {
        double a = stream * Math.PI * 2 / 16 + PlumVfx.curve(t, 0, 0, 24, .7, 54, 1.4, 72, 1.65, 84, 1.8, 92, 1.85, 102, 1.9, 120, 2);
        double r = (3 + stream % 3 * .8) * PlumVfx.curve(t, 0, 1, 24, .9, 54, .7, 72, .4, 84, .12, 92, .17, 102, .23, 120, .26);
        double y = PlumVfx.curve(t, 0, 9.5, 24, 9.2, 54, 8.1, 72, 5.8, 84, .15, 92, .11, 102, .07, 120, .07);
        return new Vec3(Math.cos(a) * r, y, Math.sin(a) * r);
    }

    private static void bloom(VertexConsumer v, PoseStack.Pose pose, Cast c, double t) {
        if (t >= 100) { return; }
        float fade = (float) Math.min(1, Math.min(t / 3, (100 - t) / 22));
        for (int i = 0; i < 10; i++) {
            double k = i >= 5 ? .78 : 1;
            double a = Math.toRadians(i % 5 * 72 + (i >= 5 ? 36 : 0) + PlumVfx.curve(t, 0, 0, 48, 12, 100, 20));
            double tilt = Math.toRadians(PlumVfx.curve(t, 0, 8, 24, 42, 48, 60, 100, 66));
            double w = PlumVfx.curve(t, 0, .1, 24, 2.7 * k, 48, 3.1 * k, 78, 3.1 * k, 100, .1) / 2;
            double len = PlumVfx.curve(t, 0, .15, 24, 4.1 * k, 48, 5 * k, 78, 5 * k, 100, .1);
            Vec3 base = c.ground.add(0, 9.5 + i / 5 * .2, 0);
            Vec3 right = new Vec3(Math.cos(a) * w, 0, -Math.sin(a) * w);
            Vec3 tip = base.add(Math.sin(a) * Math.sin(tilt) * len, Math.cos(tilt) * len, Math.cos(a) * Math.sin(tilt) * len);
            Vec3 normal = new Vec3(0, -1, 0);
            VfxDraw.vertex(v, pose, base.subtract(right), normal, 0, 1, fade, 1, 1, 1);
            VfxDraw.vertex(v, pose, base.add(right), normal, 1, 1, fade, 1, 1, 1);
            VfxDraw.vertex(v, pose, tip.add(right), normal, 1, 0, fade, 1, 1, 1);
            VfxDraw.vertex(v, pose, tip.subtract(right), normal, 0, 0, fade, 1, 1, 1);
        }
    }

    private static void ring(VertexConsumer v, PoseStack.Pose pose, Vec3 at, double r, double w, float alpha) {
        for (int i = 0; i < 48; i++) {
            double a = i * Math.PI * 2 / 48, b = (i + 1) * Math.PI * 2 / 48;
            Vec3 n = new Vec3(0, 1, 0);
            for (double[] p : new double[][]{{a, r - w}, {b, r - w}, {b, r + w}, {a, r + w}}) {
                VfxDraw.vertex(v, pose, at.add(Math.cos(p[0]) * p[1], 0, Math.sin(p[0]) * p[1]), n, .5F, .5F, alpha, 1, .97F, .99F);
            }
        }
    }

    private PlumRainfallVfx() { }
}
