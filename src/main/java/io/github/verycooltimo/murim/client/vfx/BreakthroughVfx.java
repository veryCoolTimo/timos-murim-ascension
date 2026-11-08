package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.ClientConfig;
import io.github.verycooltimo.murim.client.ClientMeditationState;
import io.github.verycooltimo.murim.cultivation.BreakthroughScript;
import io.github.verycooltimo.murim.cultivation.Realm;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** Keeps omen, lanterns and guardian light; all four scenes and tails dispatch through BtScenes. */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class BreakthroughVfx {

    private static final double TAU = Math.PI * 2.0D;
    private static final Vec3 UP = new Vec3(0.0D, 1.0D, 0.0D);
    private static final VfxColour WHITE = new VfxColour(1.0F, 1.0F, 1.0F);

    /** Palette of the rim per rank (index rank - 1): stops that the rim cycles through along a band and in time. */
    private static final int[][] RIM = {
        {0x66E4FF, 0xB9A8FF, 0x7CFFD6, 0xA6D8FF},                                  // third: ice, lavender, mint
        {0xFFC34A, 0xFF9C5E, 0xFF7A8F, 0xFFE58A},                                  // second: gold, peach, rose
        {0xFF72C8, 0xA880FF, 0x7FD0FF, 0xFFB7E4},                                  // first: plum pink, violet, ice, blush
        {0xFF5FB7, 0xFF5A2E, 0xFFD35A, 0x34F36F, 0x40F0C8, 0x55EAFF, 0x9A6BFF}};    // Peak: the rainbow of the refs

    /** One band: how wide, where it winds, where it starts and how long it takes to grow. */

    /** What one frame needs, in world coordinates (the pose carries the camera translation). */
    record Ctx(Minecraft mc, AbstractClientPlayer player, PoseStack.Pose pose, Vec3 camera, Vec3 axis, Vec3 core,
               Vec3 facing, Vec3 side, Vec3 chest, Vec3 head, double ground, double top, boolean body, float partial) {

        MultiBufferSource.BufferSource buffers() {
            return mc.renderBuffers().bufferSource();
        }
    }

    private BreakthroughVfx() {
    }

    // ------------------------------------------------------------------ entry

    @SubscribeEvent
    static void onRenderStage(RenderLevelStageEvent event) {
        if(io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.benchActive())return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        int warn = ClientMeditationState.warningAge();
        int scene = ClientMeditationState.breakthroughAge();
        int rankUp = ClientMeditationState.rankUpAge();
        int glow = ClientMeditationState.afterglowAge();
        int guard = ClientMeditationState.guardAge();
        if (warn < 0 && scene < 0 && rankUp < 0 && glow < 0 && guard < 0) {
            lanterns = List.of();
            scanned = false;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.player instanceof AbstractClientPlayer player)) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        io.github.verycooltimo.murim.client.vfx.bt.BtFireField.camera(event.getModelViewMatrix(), event.getProjectionMatrix(), cam);
        io.github.verycooltimo.murim.client.vfx.bt.BtImpactFrame.capture(
                scene >= 0 || rankUp >= 0 ? io.github.verycooltimo.murim.client.vfx.bt.BtBody.of(player, cam) : null,
                cam, event.getModelViewMatrix(), event.getProjectionMatrix());
        PoseStack stack = event.getPoseStack();
        stack.pushPose();
        try {
            stack.translate(-cam.x, -cam.y, -cam.z);
            Ctx c = context(mc, player, stack.last(), cam, partial);
            if (warn >= 0) {
                omen(c, ClientMeditationState.breakthroughTarget(), warn + partial);
            }
            io.github.verycooltimo.murim.client.vfx.bt.BtScenes.render(mc, stack, cam, partial);
            if (guard >= 0) {
                guardClang(c, Math.max(1, ClientMeditationState.breakthroughTarget()), guard + partial);
            }
        } finally {
            stack.popPose();
        }
    }

    private static Ctx context(Minecraft mc, AbstractClientPlayer player, PoseStack.Pose pose, Vec3 cam, float partial) {
        Vec3 dantian = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.DANTIAN);
        Vec3 chest = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.CHEST);
        Vec3 head = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.HEAD);
        Vec3 pos = new Vec3(Mth.lerp(partial, player.xo, player.getX()), Mth.lerp(partial, player.yo, player.getY()),
                Mth.lerp(partial, player.zo, player.getZ()));
        boolean body = dantian != null && chest != null;
        Vec3 axis = body ? new Vec3(chest.x, dantian.y, chest.z) : pos.add(0.0D, 0.55D, 0.0D);
        Vec3 toCamera = cam.subtract(body ? dantian : axis);
        // The core sits on the belly's surface, on the camera's side: inside the torso the depth test would cut it.
        Vec3 core = body ? dantian.add(0.0D, 0.03D, 0.0D)
                .add(toCamera.lengthSqr() > 1.0E-6D ? toCamera.normalize().scale(0.2D) : Vec3.ZERO) : axis;
        float bodyYaw = Mth.rotLerp(partial, player.yBodyRotO, player.yBodyRot);
        Vec3 facing = Vec3.directionFromRotation(0.0F, bodyYaw);
        Vec3 side = facing.cross(UP).normalize();
        double ground = Mth.lerp(partial, player.yo, player.getY()) + 0.02D;
        double top = head != null ? head.y + 0.3D : ground + 1.35D;
        return new Ctx(mc, player, pose, cam, axis, core, facing, side, chest, head, ground, top, body, partial);
    }

    // ------------------------------------------------------------------ small helpers

    private static float smooth(float x) {
        float k = Mth.clamp(x, 0.0F, 1.0F);
        return k * k * (3.0F - 2.0F * k);
    }

    private static VfxColour hex(int c) {
        return new VfxColour((c >> 16 & 255) / 255.0F, (c >> 8 & 255) / 255.0F, (c & 255) / 255.0F);
    }

    private static VfxColour mix(VfxColour a, VfxColour b, float t) {
        return new VfxColour(Mth.lerp(t, a.red(), b.red()), Mth.lerp(t, a.green(), b.green()), Mth.lerp(t, a.blue(), b.blue()));
    }

    /** The rim colour of a rank at position {@code t} of its cycle (any real number; it wraps). */
    static VfxColour rim(int rank, double t) {
        int[] stops = RIM[Mth.clamp(rank, 1, 4) - 1];
        double x = ((t % 1.0D) + 1.0D) % 1.0D * stops.length;
        int a = (int) Math.floor(x) % stops.length;
        int b = (a + 1) % stops.length;
        return mix(hex(stops[a]), hex(stops[b]), smooth((float) (x - Math.floor(x))));
    }

    private static VfxColour hot(VfxColour rim) {
        return mix(rim, WHITE, 0.9F);
    }

    private static double radius(int rank) {
        return BreakthroughScript.radius(rank);
    }

    // ------------------------------------------------------------------ ribbons

    /**
     * A ribbon through {@code pts} in three passes: a soft halo, the crisp rim in the rank's colour, a crisp near-white
     * core. {@code flat} lays it on the floor (the side runs along the ground), otherwise it faces the camera.
     *
     * @param last number of segments drawn (the rest of the points are not used yet)
     */
    private static void ribbon(Ctx c, Vec3[] pts, double[] halfW, float[] alpha, VfxColour[] rim, int last, boolean flat,
                               float haloAlpha, double coreFrac) {
        int n = Math.min(last, pts.length - 1);
        if (n < 1) {
            return;
        }
        Vec3[] sides = new Vec3[n + 1];
        for (int i = 0; i <= n; i++) {
            Vec3 tangent = pts[Math.min(n, i + 1)].subtract(pts[Math.max(0, i - 1)]);
            Vec3 toward = flat ? UP : c.camera().subtract(pts[i]);
            Vec3 s = tangent.cross(toward);
            sides[i] = s.lengthSqr() < 1.0E-12D ? c.side() : s.normalize();
        }
        for (int pass = 0; pass < 3; pass++) {
            RenderType type = pass == 0 ? MurimRenderTypes.ribbon() : BreakthroughTypes.band();
            double widthK = pass == 0 ? 1.5D : pass == 1 ? 1.0D : coreFrac;
            float alphaK = pass == 0 ? haloAlpha : pass == 1 ? 0.92F : 1.0F;
            VertexConsumer vc = c.buffers().getBuffer(type);
            for (int i = 0; i < n; i++) {
                float aa = alpha[i] * alphaK;
                float ab = alpha[i + 1] * alphaK;
                if ((aa <= 0.002F && ab <= 0.002F) || (halfW[i] <= 0.0D && halfW[i + 1] <= 0.0D)) {
                    continue;
                }
                // The rim is the rank's colour lifted toward pastel; the core is near white.
                VfxColour ca = pass == 2 ? hot(rim[i]) : pass == 1 ? mix(rim[i], WHITE, 0.22F) : rim[i];
                VfxColour cb = pass == 2 ? hot(rim[i + 1]) : pass == 1 ? mix(rim[i + 1], WHITE, 0.22F) : rim[i + 1];
                Vec3 mid = pts[i].add(pts[i + 1]).scale(0.5D);
                Vec3 normal = flat ? UP : c.camera().subtract(mid).normalize();
                double wa = halfW[i] * widthK;
                double wb = halfW[i + 1] * widthK;
                VfxDraw.vertex(vc, c.pose(), pts[i].subtract(sides[i].scale(wa)), normal, 0.0F, 0.0F, aa, ca.red(), ca.green(), ca.blue());
                VfxDraw.vertex(vc, c.pose(), pts[i + 1].subtract(sides[i + 1].scale(wb)), normal, 1.0F, 0.0F, ab, cb.red(), cb.green(), cb.blue());
                VfxDraw.vertex(vc, c.pose(), pts[i + 1].add(sides[i + 1].scale(wb)), normal, 1.0F, 1.0F, ab, cb.red(), cb.green(), cb.blue());
                VfxDraw.vertex(vc, c.pose(), pts[i].add(sides[i].scale(wa)), normal, 0.0F, 1.0F, aa, ca.red(), ca.green(), ca.blue());
            }
            c.buffers().endBatch(type);
        }
    }

    /** Soft round glow quads (additive). */
    private static void motes(Ctx c, List<double[]> glows) {
        if (glows.isEmpty()) {
            return;
        }
        VertexConsumer vc = c.buffers().getBuffer(MurimRenderTypes.mote());
        for (double[] g : glows) {
            // x, y, z, size, alpha, r, g, b
            VfxDraw.billboard(vc, c.pose(), new Vec3(g[0], g[1], g[2]), c.camera(), g[3], (float) g[4], (float) g[5], (float) g[6], (float) g[7]);
        }
        c.buffers().endBatch(MurimRenderTypes.mote());
    }

    private static void glow(List<double[]> out, Vec3 at, double size, float alpha, VfxColour col) {
        out.add(new double[] {at.x, at.y, at.z, size, alpha, col.red(), col.green(), col.blue()});
    }

    // ------------------------------------------------------------------ the scene

    // ------------------------------------------------------------------ bands

    private static double ground(Ctx c) {
        return c.ground();
    }

    // ------------------------------------------------------------------ orbs

    // ------------------------------------------------------------------ speed lines

    // ------------------------------------------------------------------ floor

    // ------------------------------------------------------------------ omen

    private static List<Vec3> lanterns = List.of();
    private static boolean scanned;

    /**
     * The 2.5 seconds before the scene (author 18.10: birds and wind go quiet, lanterns flicker, dust and leaves spiral
     * inward). The particles and the sound are {@link BreakthroughParticles}; here the lanterns gutter and the pressure
     * of the coming scene draws itself in as two rings of light that close on the body.
     */
    private static void omen(Ctx c, int rank, float t) {
        float env = smooth(t / 10.0F);
        lanterns(c, env);
        double r = radius(rank);
        double reach = Math.min(0.7D * r, 6.0D);
        for (int k = 0; k < 2; k++) {
            float ph = ((t + k * 11.0F) % 22.0F) / 22.0F;
            double rr = Mth.lerp(ph, reach, 0.65D);
            float a = 0.5F * env * (float) Math.sin(ph * Math.PI);
            int n = 48;
            Vec3[] pts = new Vec3[n + 1];
            double[] w = new double[n + 1];
            float[] al = new float[n + 1];
            VfxColour[] col = new VfxColour[n + 1];
            for (int i = 0; i <= n; i++) {
                double ang = TAU * i / n;
                pts[i] = new Vec3(c.axis().x + Math.cos(ang) * rr, c.ground() + 0.015D, c.axis().z + Math.sin(ang) * rr);
                w[i] = 0.1D + 0.12D * ph;
                al[i] = a;
                col[i] = rim(rank, i / (double) n * 0.5D + t * 0.01D);
            }
            ribbon(c, pts, w, al, col, n, true, 0.2F, 0.5D);
        }
        // The core wakes: a small warm point that beats with the heart.
        float beat = (float) Math.exp(-((t % 12.0F) / 12.0F) * 8.0D);
        List<double[]> g = new ArrayList<>();
        glow(g, c.core(), 0.1D + 0.07D * beat, (0.4F + 0.5F * beat) * env, rim(rank, 0.0D));
        motes(c, g);
    }

    /** Lanterns near the player flicker: the glow round each gutters on its own irregular beat; {@code strength} 0..1. */
    private static void lanterns(Ctx c, float strength) {
        if (strength <= 0.01F) {
            return;
        }
        if (!scanned) {
            scanLanterns(c);
        }
        if (lanterns.isEmpty()) {
            return;
        }
        float t = c.mc().level == null ? 0.0F : (c.mc().level.getGameTime() + c.partial());
        List<double[]> g = new ArrayList<>();
        VfxColour warm = hex(0xFFA84A);
        for (int i = 0; i < lanterns.size(); i++) {
            long step = (long) Math.floor((t + i * 3.7F) / 2.0F);
            float n = new Random(step * 31L + i * 17L + 5L).nextFloat();
            // Mostly a lively flame, now and then a gutter that all but goes out.
            float flick = n < 0.2F ? 0.04F : 0.4F + 0.6F * n;
            Vec3 at = lanterns.get(i);
            glow(g, at, 0.7D * (0.6D + 0.4D * flick), 0.42F * flick * strength, warm);
            glow(g, at, 0.2D, 0.8F * flick * strength, hex(0xFFE2A8));
        }
        motes(c, g);
    }

    private static void scanLanterns(Ctx c) {
        scanned = true;
        if (c.mc().level == null) {
            return;
        }
        BlockPos centre = c.player().blockPosition();
        List<Vec3> found = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(centre.offset(-18, -6, -18), centre.offset(18, 8, 18))) {
            BlockState state = c.mc().level.getBlockState(p);
            if (state.isAir() || state.getLightEmission() < 7) {
                continue;
            }
            if (BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().contains("lantern")) {
                found.add(Vec3.atCenterOf(p));
            }
        }
        found.sort(Comparator.comparingDouble(v -> v.distanceToSqr(c.axis())));
        lanterns = found.size() > 40 ? List.copyOf(found.subList(0, 40)) : List.copyOf(found);
    }

    // ------------------------------------------------------------------ the impact

    // ------------------------------------------------------------------ the guardian

    /** A guardian took the first blow: a clang of light on the near side of the scene for ten ticks. */
    private static void guardClang(Ctx c, int rank, float age) {
        float k = Mth.clamp(age / 10.0F, 0.0F, 1.0F);
        float a = 1.0F - k;
        Vec3 at = c.axis().add(c.facing().scale(1.1D)).add(0.0D, 0.85D, 0.0D);
        List<double[]> g = new ArrayList<>();
        glow(g, at, 0.7D * (1.0D - 0.5D * k), 0.5F * a, rim(rank, 0.1D));
        glow(g, at, 0.3D * (1.0D - 0.5D * k), 0.95F * a, WHITE);
        motes(c, g);
        Vec3 toward = c.camera().subtract(at);
        if (toward.lengthSqr() < 1.0E-6D) {
            return;
        }
        toward = toward.normalize();
        Vec3 right = toward.cross(UP);
        right = right.lengthSqr() < 1.0E-6D ? c.side() : right.normalize();
        Vec3 up = right.cross(toward).normalize();
        VertexConsumer vc = c.buffers().getBuffer(MurimRenderTypes.strand());
        for (int i = 0; i < 12; i++) {
            double ang = i * TAU / 12.0D + 0.3D;
            Vec3 dir = right.scale(Math.cos(ang)).add(up.scale(Math.sin(ang)));
            VfxDraw.segment(vc, c.pose(), at.add(dir.scale(0.2D + 0.5D * k)), at.add(dir.scale(0.5D + 1.4D * k)), c.camera(),
                    0.04D * (1.0D - 0.6D * k), a, 1.0F, 1.0F, 1.0F);
        }
        c.buffers().endBatch(MurimRenderTypes.strand());
    }

    // ------------------------------------------------------------------ the afterglow

    /**
     * The 30 seconds after the breakthrough (author 18.10): the body keeps a soft aura in the rank's colour with sparks that
     * rise off it. Needs the body
     * (third-person camera): in first person there is nothing to wrap.
     */

}
