package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.MountHuaSitePayload;
import io.github.verycooltimo.murim.world.hua.MountHuaPlan;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3f;

/**
 * Mist and the sea of clouds on Mount Hua (reference photos 04, 11: a flat cloud layer at the foot
 * of the walls, mist lying in the gorges). A small simulation, not painted cards: each puff has a
 * velocity (shared wind that slowly turns + its own turbulence), rises and sinks a little, lives
 * 30–60 s, fades in and out, and dies early if the wind pushes it into rock.
 *
 * <p>Two kinds: <b>cloud sea</b> — flat band at nominal height 95–135 over open air (only where
 * the ground is lower), <b>valley mist</b> — hugging the ground of gorges and lower slopes.
 * Budget: ≤ 180 puffs × 3 quads, scaled by the vanilla particle setting (MINIMAL → off).
 *
 * <p>Render: {@link RenderLevelStageEvent.Stage#AFTER_PARTICLES}, {@link MurimRenderTypes#mist()}
 * (translucent, no depth write), puffs sorted back to front; tinted by the sky brightness so the
 * mist is not glowing at night. Client-only; the site comes from {@link MountHuaSitePayload}.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class HuaMist {

    private static final int MAX_PUFFS = 240;
    private static final double SPAWN_MIN = 24;
    private static final double SPAWN_MAX = 420;
    private static final double ACTIVE_RADIUS = 1100;

    private static final class Puff {
        double x;
        double y;
        double z;
        double vx;
        double vy;
        double vz;
        double size;
        int age;
        int life;
        float phase;
        boolean flipU;
        float bright;
    }

    private static MountHuaSite site;
    private static final List<Puff> PUFFS = new ArrayList<>();
    private static final Random RANDOM = new Random();
    private static int ticks;

    private HuaMist() {
    }

    public static void onSite(MountHuaSitePayload payload) {
        site = MountHuaSite.placement(payload.centerX(), payload.centerZ(), payload.baseY(), payload.rotation());
        PUFFS.clear();
    }

    @SubscribeEvent
    static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        site = null;
        PUFFS.clear();
    }

    private static boolean inMassif(double x, double z) {
        double u = site.localU(x, z);
        double v = site.localV(x, z);
        if (v < MountHuaPlan.SCARP_V - 10) {
            return false;
        }
        double du = u / 360.0;
        double dv = Math.max(0, v - 40) / 470.0;
        return du * du + dv * dv < 1.1;
    }

    private static int ground(ClientLevel level, double x, double z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (site == null || level == null || mc.player == null || mc.isPaused()) {
            return;
        }
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        double dc = Math.hypot(cam.x - site.centerX(), cam.z - site.centerZ());
        ParticleStatus status = mc.options.particles().get();
        int budget = status == ParticleStatus.MINIMAL ? 0 : status == ParticleStatus.DECREASED ? MAX_PUFFS / 2 : MAX_PUFFS;
        if (dc > ACTIVE_RADIUS) {
            PUFFS.clear();
            return;
        }
        ticks++;
        // Shared wind: slowly turning, 0.6–1.0 m/s.
        double windAngle = ticks * 0.0007 + Math.sin(ticks * 0.0021) * 0.6;
        double windSpeed = 0.03 + 0.02 * Math.sin(ticks * 0.0013 + 1.7);
        double wx = Math.cos(windAngle) * windSpeed;
        double wz = Math.sin(windAngle) * windSpeed;
        for (int i = PUFFS.size() - 1; i >= 0; i--) {
            Puff p = PUFFS.get(i);
            p.age++;
            double turb = Math.sin(p.age * 0.021 + p.phase);
            p.vx += (wx + 0.012 * turb - p.vx) * 0.02;
            p.vz += (wz + 0.012 * Math.cos(p.age * 0.017 + p.phase) - p.vz) * 0.02;
            p.vy = 0.004 * Math.sin(p.age * 0.013 + p.phase * 2);
            p.x += p.vx;
            p.y += p.vy;
            p.z += p.vz;
            if (p.age % 20 == 0 && ground(level, p.x, p.z) > p.y + 2 && p.life - p.age > 40) {
                p.life = p.age + 40;
            }
            if (p.age >= p.life || Math.hypot(p.x - cam.x, p.z - cam.z) > SPAWN_MAX + 80) {
                PUFFS.remove(i);
            }
        }
        for (int attempt = 0; attempt < 6 && PUFFS.size() < budget; attempt++) {
            spawn(level, cam);
        }
    }

    private static void spawn(ClientLevel level, Vec3 cam) {
        double a = RANDOM.nextDouble() * Math.PI * 2;
        double r = SPAWN_MIN + Math.sqrt(RANDOM.nextDouble()) * (SPAWN_MAX - SPAWN_MIN);
        double x = cam.x + Math.cos(a) * r;
        double z = cam.z + Math.sin(a) * r;
        if (!inMassif(x, z)) {
            return;
        }
        int g = ground(level, x, z);
        if (g <= level.getMinBuildHeight() + 2) {
            return; // chunk not loaded yet
        }
        double y;
        // More valley mist, fewer high puffs (codex r2: puffs on the summits looked stuck on).
        // Over the pillar basin the cloud sea is continuous (author ref 03).
        boolean basin = MountHuaPlan.inPillarBasin(site.localU(x, z), site.localV(x, z));
        boolean sea = RANDOM.nextFloat() < (basin ? 0.95F : 0.5F);
        if (sea) {
            y = site.worldY(100 + RANDOM.nextDouble() * 22);
            if (g > y - 15) {
                return;
            }
        } else {
            // Broad layers lying in the valleys of the lower slopes and the foothills.
            if (g > site.worldY(70)) {
                return;
            }
            y = g + 2 + RANDOM.nextDouble() * 5;
        }
        Puff p = new Puff();
        p.x = x;
        p.y = y;
        p.z = z;
        p.size = sea ? 18 + RANDOM.nextDouble() * 22 : 16 + RANDOM.nextDouble() * 14;
        p.life = 600 + RANDOM.nextInt(600);
        p.phase = RANDOM.nextFloat() * 6.28F;
        p.flipU = RANDOM.nextBoolean();
        p.bright = 0.9F + RANDOM.nextFloat() * 0.1F;
        PUFFS.add(p);
    }

    @SubscribeEvent
    static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || PUFFS.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        Vector3f left = event.getCamera().getLeftVector();
        Vector3f up = event.getCamera().getUpVector();
        float sky = mc.level.getSkyDarken(partial);
        PUFFS.sort(Comparator.comparingDouble((Puff p) -> -((p.x - cam.x) * (p.x - cam.x)
                + (p.y - cam.y) * (p.y - cam.y) + (p.z - cam.z) * (p.z - cam.z))));
        PoseStack ps = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        ps.pushPose();
        try {
            ps.translate(-cam.x, -cam.y, -cam.z);
            PoseStack.Pose pose = ps.last();
            VertexConsumer vc = buffers.getBuffer(MurimRenderTypes.mist());
            for (Puff p : PUFFS) {
                double t = p.age + partial;
                float env = (float) Math.min(Math.min(t / 100.0, (p.life - t) / 150.0), 1.0);
                if (env <= 0) {
                    continue;
                }
                double px = p.x + p.vx * partial;
                double py = p.y + p.vy * partial;
                double pz = p.z + p.vz * partial;
                double dist = Math.sqrt((px - cam.x) * (px - cam.x) + (py - cam.y) * (py - cam.y) + (pz - cam.z) * (pz - cam.z));
                // Thin out close to the eye (it must not wall off the view) and at the far edge.
                float near = (float) Math.min(1.0, Math.max(0.0, (dist - p.size * 0.6) / (p.size * 1.2)));
                float far = (float) Math.min(1.0, Math.max(0.0, (SPAWN_MAX + 40 - dist) / 80.0));
                float alpha = 0.36F * env * near * far;
                if (alpha <= 0.01F) {
                    continue;
                }
                float c = sky * p.bright;
                // Three overlapping lobes per puff: a wide flat body and two smaller tops.
                // Cloud-sea puffs are flat (a layer, ref 04); valley mist a little rounder.
                double flat = 0.3;
                quad(vc, pose, px, py, pz, p.size, p.size * flat, left, up, alpha, c, p.flipU);
                quad(vc, pose, px + p.size * 0.22, py + p.size * 0.12, pz, p.size * 0.6, p.size * 0.42,
                        left, up, alpha * 0.8F, c, !p.flipU);
                quad(vc, pose, px - p.size * 0.25, py + p.size * 0.08, pz + p.size * 0.1, p.size * 0.55, p.size * 0.38,
                        left, up, alpha * 0.75F, c, p.flipU);
            }
            buffers.endBatch(MurimRenderTypes.mist());
        } finally {
            ps.popPose();
        }
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose pose, double x, double y, double z, double halfW,
            double halfH, Vector3f left, Vector3f up, float alpha, float c, boolean flip) {
        Vec3 l = new Vec3(left.x() * halfW, left.y() * halfW, left.z() * halfW);
        Vec3 u = new Vec3(up.x() * halfH, up.y() * halfH, up.z() * halfH);
        Vec3 centre = new Vec3(x, y, z);
        float u0 = flip ? 1 : 0;
        float u1 = flip ? 0 : 1;
        vertex(vc, pose, centre.add(l).add(u), u0, 0, alpha, c);
        vertex(vc, pose, centre.add(l).subtract(u), u0, 1, alpha, c);
        vertex(vc, pose, centre.subtract(l).subtract(u), u1, 1, alpha, c);
        vertex(vc, pose, centre.subtract(l).add(u), u1, 0, alpha, c);
    }

    /** POSITION_COLOR_TEX_LIGHTMAP vertex at full brightness (tint comes from the colour). */
    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, Vec3 p, float uu, float vv, float alpha, float c) {
        vc.addVertex(pose.pose(), (float) p.x, (float) p.y, (float) p.z)
                .setColor(c, c, c, Math.min(1.0F, alpha))
                .setUv(uu, vv)
                .setLight(0x00F000F0);
    }
}
