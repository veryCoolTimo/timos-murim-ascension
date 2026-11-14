package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.HitImpactPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * The one own hit impact of combat outside techniques: bandit hits, boss hits, a successful block (art review 11.11).
 * Three layers, rule 04-vfx-rendering: a flash (3 ticks, peak at the first tick), sparks with a short streak
 * (5-9 ticks, flying along the blow and bouncing up), smoke (starts one tick later, grows in the first 15 % of its
 * life, then only thins). A hit is warm (white-gold, ember, dark smoke); a block is cold (steel, blue-white, grey).
 *
 * <p>Particles have position, velocity, drag and gravity; the streak is drawn from the previous position, and
 * the sample is taken at the fractional age (tick + partial), so it does not step at 60 fps.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class HitImpactVfx {

    private enum Layer { FLASH, SPARK, SMOKE }

    private static final class P {
        final Layer layer;
        final boolean cold;
        double x, y, z, px, py, pz;
        double vx, vy, vz;
        int age;
        int delay;
        final int life;
        final float size;
        final float seed;

        P(Layer layer, boolean cold, double x, double y, double z, double vx, double vy, double vz, int delay, int life, float size, float seed) {
            this.layer = layer;
            this.cold = cold;
            this.x = this.px = x;
            this.y = this.py = y;
            this.z = this.pz = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.delay = delay;
            this.life = life;
            this.size = size;
            this.seed = seed;
        }
    }

    private static final List<P> LIVE = new ArrayList<>();
    private static final Random RANDOM = new Random();
    /** Safety cap: a crowd of bandits must not fill the particle budget. */
    private static final int CAP = 600;
    private static int ticks;

    private HitImpactVfx() {
    }

    public static void spawn(HitImpactPayload hit) {
        if (LIVE.size() > CAP - 40) {
            return;
        }
        boolean cold = hit.kind() == HitImpactPayload.BLOCK;
        float k = Math.max(0.6F, hit.power());
        double at_x = hit.x(), at_y = hit.y(), at_z = hit.z();
        // Direction of the blow (attacker to victim); sparks fly along it and to the sides, smoke stays.
        double dx = hit.dx(), dz = hit.dz();
        double sx = -dz, sz = dx;
        Random r = RANDOM;
        // Flash: one big core and a second smaller, longer one; 3 and 4 ticks (150 and 200 ms).
        LIVE.add(new P(Layer.FLASH, cold, at_x, at_y, at_z, 0, 0, 0, 0, 3, 0.55F * k, r.nextFloat()));
        LIVE.add(new P(Layer.FLASH, cold, at_x, at_y, at_z, 0, 0.02, 0, 0, 4, 0.34F * k, r.nextFloat()));
        // Sparks: a cone around the line of the blow, some thrown sideways.
        int sparks = Math.round((cold ? 12 : 10) * k) + r.nextInt(4);
        for (int i = 0; i < sparks; i++) {
            double along = (cold ? -0.1 : 0.25) + r.nextDouble() * 0.9;
            double side = (r.nextDouble() - 0.5) * 1.5;
            double up = (r.nextDouble() - 0.25) * 0.9;
            double speed = (0.28 + 0.34 * r.nextDouble()) * (0.8 + 0.3 * k);
            double vx = (dx * along + sx * side) * speed, vz = (dz * along + sz * side) * speed, vy = up * speed;
            LIVE.add(new P(Layer.SPARK, cold, at_x, at_y, at_z, vx, vy, vz, 0, 5 + r.nextInt(5), (0.05F + 0.04F * r.nextFloat()) * (0.8F + 0.3F * k), r.nextFloat()));
        }
        // Smoke: starts one tick after the flash, a few dark puffs that drift along the blow and rise.
        int puffs = 4 + Math.round(2 * k);
        for (int i = 0; i < puffs; i++) {
            double drift = 0.02 + 0.035 * r.nextDouble();
            LIVE.add(new P(Layer.SMOKE, cold, at_x + (r.nextDouble() - 0.5) * 0.25, at_y + (r.nextDouble() - 0.5) * 0.25, at_z + (r.nextDouble() - 0.5) * 0.25,
                    dx * drift + sx * (r.nextDouble() - 0.5) * 0.03, 0.012 + 0.02 * r.nextDouble(), dz * drift + sz * (r.nextDouble() - 0.5) * 0.03,
                    1 + r.nextInt(2), 22 + r.nextInt(14), (0.2F + 0.14F * r.nextFloat()) * k, r.nextFloat()));
        }
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            LIVE.clear();
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        ticks++;
        Iterator<P> it = LIVE.iterator();
        while (it.hasNext()) {
            P p = it.next();
            if (p.delay > 0) {
                p.delay--;
                continue;
            }
            p.px = p.x;
            p.py = p.y;
            p.pz = p.z;
            if (++p.age >= p.life) {
                it.remove();
                continue;
            }
            switch (p.layer) {
                case SPARK -> {
                    p.vx *= 0.82;
                    p.vz *= 0.82;
                    p.vy = p.vy * 0.85 - 0.028;
                }
                case SMOKE -> {
                    double s = p.seed * 9.0;
                    p.vx = p.vx * 0.93 + Math.sin(ticks * 0.21 + s) * 0.0025;
                    p.vz = p.vz * 0.93 + Math.cos(ticks * 0.17 + s) * 0.0025;
                    p.vy = p.vy * 0.95 + 0.0012;
                }
                default -> {
                }
            }
            p.x += p.vx;
            p.y += p.vy;
            p.z += p.vz;
        }
    }

    @SubscribeEvent
    static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || LIVE.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        PoseStack stack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        stack.pushPose();
        try {
            stack.translate(-camera.x, -camera.y, -camera.z);
            PoseStack.Pose pose = stack.last();

            // Smoke first (under the light layers): flat translucent, dark.
            RenderType smokeType = MurimRenderTypes.impurity();
            VertexConsumer smoke = buffers.getBuffer(smokeType);
            for (P p : LIVE) {
                if (p.layer != Layer.SMOKE || p.delay > 0) {
                    continue;
                }
                float t = Math.min(1.0F, (p.age + partial) / p.life);
                // Peak in the first 15 % of life, then only thinning and a slow shrink.
                float rise = Math.min(1.0F, t / 0.15F);
                float eased = 1.0F - (1.0F - rise) * (1.0F - rise);
                float size = p.size * (0.45F + 0.55F * eased) * (t < 0.15F ? 1.0F : 1.0F - 0.3F * (t - 0.15F) / 0.85F);
                float alpha = (t < 0.15F ? 0.55F + 0.25F * eased : 0.8F * (1.0F - (t - 0.15F) / 0.85F)) * 0.75F;
                float shade = p.cold ? 0.55F : 0.12F + 0.1F * p.seed;
                VfxDraw.billboard(smoke, pose, head(p, partial), camera, size, alpha,
                        shade * (p.cold ? 0.95F : 1.0F), shade * (p.cold ? 1.0F : 0.85F), shade * (p.cold ? 1.1F : 0.8F));
            }
            buffers.endBatch(smokeType);

            // Light layers: additive core for flash, streaks for sparks.
            RenderType coreType = MurimRenderTypes.mote();
            VertexConsumer core = buffers.getBuffer(coreType);
            for (P p : LIVE) {
                if (p.layer != Layer.FLASH || p.delay > 0) {
                    continue;
                }
                float t = Math.min(1.0F, (p.age + partial) / p.life);
                float fade = 1.0F - t;
                float size = p.size * (1.0F - 0.55F * t);
                Vec3 c = head(p, partial);
                if (p.cold) {
                    VfxDraw.billboard(core, pose, c, camera, size * 1.5, 0.5F * fade, 0.55F, 0.75F, 1.0F);
                    VfxDraw.billboard(core, pose, c, camera, size * 0.7, 1.0F * fade, 0.92F, 0.97F, 1.0F);
                } else {
                    VfxDraw.billboard(core, pose, c, camera, size * 1.5, 0.55F * fade, 1.0F, 0.55F, 0.16F);
                    VfxDraw.billboard(core, pose, c, camera, size * 0.7, 1.0F * fade, 1.0F, 0.93F, 0.72F);
                }
            }
            buffers.endBatch(coreType);
            // Sparks: a hard streak (air band, solid core) from the position three ticks back, white-hot at birth, ember at the end.
            RenderType streakType = MurimRenderTypes.flowLine();
            VertexConsumer streak = buffers.getBuffer(streakType);
            for (P p : LIVE) {
                if (p.layer != Layer.SPARK || p.delay > 0) {
                    continue;
                }
                float t = Math.min(1.0F, (p.age + partial) / p.life);
                float fade = (1.0F - t) * (1.0F - t * 0.4F);
                Vec3 to = head(p, partial);
                Vec3 from = to.subtract(p.vx * 3.2, p.vy * 3.2, p.vz * 3.2);
                double w = p.size * (1.0F - 0.5F * t);
                if (p.cold) {
                    VfxDraw.segment(streak, pose, from, to, camera, w * 1.8, 0.45F * fade, 0.45F, 0.65F, 1.0F);
                    VfxDraw.segment(streak, pose, from, to, camera, w * 0.8, fade, 0.9F, 0.97F, 1.0F);
                } else {
                    VfxDraw.segment(streak, pose, from, to, camera, w * 1.8, 0.5F * fade, 1.0F, 0.4F, 0.08F);
                    VfxDraw.segment(streak, pose, from, to, camera, w * 0.8, fade, 1.0F, 0.95F - 0.35F * t, 0.7F - 0.5F * t);
                }
            }
            buffers.endBatch(streakType);

        } finally {
            stack.popPose();
        }
    }

    private static Vec3 head(P p, float partial) {
        return new Vec3(p.px + (p.x - p.px) * partial, p.py + (p.y - p.py) * partial, p.pz + (p.z - p.pz) * partial);
    }
}
