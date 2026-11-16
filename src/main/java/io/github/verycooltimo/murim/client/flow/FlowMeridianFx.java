package io.github.verycooltimo.murim.client.flow;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer;
import io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone;
import io.github.verycooltimo.murim.client.vfx.MurimRenderTypes;
import io.github.verycooltimo.murim.client.vfx.TechniqueLight;
import io.github.verycooltimo.murim.client.vfx.VfxDraw;
import io.github.verycooltimo.murim.client.vfx.fx.FxQuality;
import io.github.verycooltimo.murim.client.vfx.fx.FxTune;
import io.github.verycooltimo.murim.client.ClientPillState;
import io.github.verycooltimo.murim.cultivation.PerfectLift;
import io.github.verycooltimo.murim.cultivation.flow.FlowGame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/**
 * The picture of the high pill game «Провести поток» (director's brief 15.11, stage 1): the body is the game board. The meridian ({@link FlowMeridian})
 * lies on the player's own model, over the torso and both arms to the palms, as a fat flat channel: a cel body in the pill colour with a white-hot
 * centre third and a soft glow; not-yet-passed part cool and pale; passed part lit, its heat decays behind the clot over about 3 s to a warm steady glow.
 * The clot, the narrow spots (ring + iris + hot knot), the forks (a live branch with beads, a blocked branch in ash pieces), the hold / surge rings and
 * the dantian seed (it swells and beats with the pressure) are read from {@link FlowGame}, which is not changed. The floor carries one lit disc.
 * Nothing is drawn on the screen: no haze, no flash, no vignette, no bar.
 *
 * <p>This object is the effect director: it keeps its own clock, follows the game's {@code s} through a critically damped follower (a wrong fork's step back
 * becomes a recoil, not a pop) and OUTLIVES {@code FlowClient.game}: when the game ends it eases the lit channels out over about 1.5 s. The lamp state for
 * the model's rim ({@link FlowBodyRim}) is computed here once a frame. {@code MURIM_FLOWFX=0} switches all of it off (cost measurements).
 * Cost: a few thousand vertices rebuilt per frame; the {@link FxQuality} governor thins the rings and the floor disc.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
final class FlowMeridianFx {
    private static final boolean OFF = "0".equals(System.getenv("MURIM_FLOWFX"));
    /** Cost attribution (dev): MURIM_FLOWFX_MODE = norim | nofloor | nobody switches one part off. */
    private static final String MODE = String.valueOf(System.getenv("MURIM_FLOWFX_MODE"));
    /** Seconds from the end of the stream to the last light: a hold, then the ease. */
    private static final double HOLD_END = .35D, EASE_END = 1.5D;
    private static final int N = FlowMeridian.SAMPLES;
    private static final float[] GREEN = {.4F, 1F, .47F}, KNOT = FlowLook.KNOT;

    private static final boolean EVENTS = true;
    private static FlowGame.State lastState = FlowGame.State.RUN;
    private static final FxQuality QUALITY = new FxQuality();
    private static FlowGame bound;
    private static long lastNanos;
    private static double dtNow, clock, endClock = -1, pd, holdK, seedPhase, lastSd;
    private static final double[] SD = new double[2];
    private static double[] age = new double[N];
    private static double[] sigma = new double[0], sigmaV = new double[0];
    private static double[] flare = new double[0];
    private static double fade, floorY, dipT = 99, handF, liftEnd, introSpace;
    private static boolean[] forkSeen = new boolean[0];
    private static final java.util.List<Integer> pendingNodes = new java.util.ArrayList<>();
    private static FlowBodyRim.State lamp = FlowBodyRim.State.NONE;
    private static double cpuSum, cpuMax;
    private static int cpuFrames;

    private FlowMeridianFx() { }

    static boolean on() { return !OFF; }

    static FlowBodyRim.State lamp() { return lamp; }

    /** True while the effect still shows after the game object is gone (the ease-out). */
    static boolean lingering() {
        return !OFF && bound != null && FlowClient.game() == null && endClock < endAtNow();
    }

    /** When the effect is over (seconds after the end): the finale's own end, or, when the body lifts, 2 s after it has landed. */
    static double endAtNow() { return Math.max(FlowSpaceMath.effectEnd(ending()), liftEnd); }

    /** -1 while the stream runs, then 0 clean finale, 1 backlash, 2 any other end. */
    static int ending() {
        if (bound == null || endClock < 0) return -1;
        return bound.state == FlowGame.State.DONE ? 0 : (bound.state == FlowGame.State.BURST ? 1 : 2);
    }

    /** Seconds since the end of the stream, -1 while it runs. */
    static double sinceEnd() { return endClock; }

    /** The game camera's keys (blocks, degrees, focus nudge); the distance is -1 when this effect has no say. */
    static float cameraDistance() {
        if (bound == null) return -1;
        double k = FlowScrollBoard.camK();
        if (k <= 0) return (float) FlowSpaceMath.cameraDistance(progress(), ending(), endClock);
        // the scroll framing: the figure in the left two thirds, 40-50 % of the frame; it eases in and out with k (the finale takes the whole frame)
        double run = FlowSpaceMath.lerp(FlowSpaceMath.lerp(FlowLook.CAM_START, FlowLook.CAM_END, smooth(0, 1, progress())), FxTune.f("flow.scroll.camDist", 3.5F), k);
        if (ending() == 0 && endClock > 0) return (float) FlowSpaceMath.lerp(run, FlowLook.CAM_PULL, smooth(.4, .4 + FlowLook.CAM_PULL_TIME, endClock));
        return (float) run;
    }
    static float cameraPitch() {
        double k = FlowScrollBoard.camK();
        if (k <= 0) return (float) FlowSpaceMath.cameraPitch(ending(), endClock);
        double run = FlowSpaceMath.lerp(FlowLook.CAM_PITCH_RUN, FxTune.f("flow.scroll.camPitch", 18F), k);
        if (ending() == 0 && endClock > 0) return (float) FlowSpaceMath.lerp(run, FlowLook.CAM_PITCH_PULL, smooth(.4, .4 + FlowLook.CAM_PULL_TIME, endClock));
        return (float) run;
    }
    static float cameraNudge() {
        int e = ending();
        if (e < 0 || e == 2) return 0;
        return (float) FlowSpaceMath.cameraNudge(endClock - (e == 0 ? FlowSpaceMath.burstAt() : FlowLook.BURST_BALL));
    }

    /** The drawn progress 0..1 (smoothed), for the camera. */
    static double progress() { return bound == null ? 0 : Math.max(0, Math.min(1, SD[0])); }

    /** The name plate of the player stays hidden while the picture runs. */
    @SubscribeEvent
    static void onNameTag(net.neoforged.neoforge.client.event.RenderNameTagEvent event) {
        if (!OFF && (bound != null) && event.getEntity() == Minecraft.getInstance().player) event.setCanRender(net.neoforged.neoforge.common.util.TriState.FALSE);
    }

    static void clear() {
        bound = null; clock = 0; endClock = -1; pd = 0; holdK = 0; seedPhase = 0; lastSd = 0; SD[0] = SD[1] = 0; fade = 0; liftEnd = 0; introSpace = 0;
        java.util.Arrays.fill(age, 0);
        dipT = 99; handF = 0; pendingNodes.clear();
        FlowSpaceFx.reset();
        FlowScore.end();
        lamp = FlowBodyRim.State.NONE;
        FlowBodyRim.lampK = 0; FlowBodyRim.lampR = 0;
        cpuSum = cpuMax = 0; cpuFrames = 0;
    }

    private static double smooth(double e0, double e1, double x) {
        double k = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return k * k * (3 - 2 * k);
    }

    // ------------------------------------------------------------------ state, once a frame

    @SubscribeEvent
    static void onFrame(RenderFrameEvent.Pre event) {
        if (OFF) { lamp = FlowBodyRim.State.NONE; return; }
        FlowGame g = FlowClient.gameForFx();
        if (FlowClient.game() != null && g == null) { bound = null; lamp = FlowBodyRim.State.NONE; return; }
        if (g != null && g != bound) {
            clear();
            bound = g;
            FlowScore.begin(g.narrows.size() + g.forks.size());
            sigma = new double[g.forks.size()];
            sigmaV = new double[g.forks.size()];
            flare = new double[g.narrows.size()];
            forkSeen = new boolean[g.forks.size()];
            java.util.Arrays.fill(flare, -1);
            lastNanos = 0;
            var pl = Minecraft.getInstance().player;
            floorY = pl == null ? 0 : pl.getY();
        }
        if (bound == null) { lamp = FlowBodyRim.State.NONE; return; }
        g = bound;
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0 : Math.min(.25, (now - lastNanos) / 1e9);
        dt = io.github.verycooltimo.murim.client.vfx.fx.FxTuneScene.simDt(dt);
        if (lastNanos != 0) QUALITY.update((now - lastNanos) / 1e6, dt);
        lastNanos = now;
        if (Minecraft.getInstance().isPaused()) dt = 0;
        clock += dt;
        dtNow = dt;
        boolean ended = g.state != FlowGame.State.RUN || FlowClient.game() == null;
        double prevEnd = endClock;
        if (ended) endClock = endClock < 0 ? 0 : endClock + dt;
        impulses(prevEnd, endClock);
        // the effect ends 2 s after the figure has landed; while the lift runs the end follows what is really left of it (a lift that is lost shortens it)
        if (ending() == 0 && ClientPillState.finaleLift() && ClientPillState.finaleLiftHeight() >= FlowLook.LIFT_VISIBLE) liftEnd = endClock + ClientPillState.finaleTicksLeft() / 20.0 + 1.5;
        else if (ending() == 0 && liftEnd > 0 && !ClientPillState.finaleLift()) liftEnd = Math.min(liftEnd, endClock + 1.5);
        double endAt = endAtNow();
        double ic = clock - FlowLook.INTRO_LEAD, introEnd = FlowLook.INTRO_DISSOLVE + FlowLook.INTRO_FLAME;
        introSpace = smooth(FlowLook.INTRO_DISSOLVE, introEnd, ic);
        double in = smooth(FlowLook.INTRO_DISSOLVE * .8, introEnd, ic), out = endClock < 0 ? 1 : 1 - smooth(endAt - .8, endAt, endClock);
        fade = smooth(introEnd - .2, introEnd + .2, ic) * out;
        if (endClock > endAt + .05 && FlowClient.game() == null) { clear(); return; }
        // the channels fade from the hands inward: they hold through the contraction, then go
        boolean lifted = ending() == 0 && liftEnd > 0;
        // clean finale: the channels go out from the hands inward after the burst; with a lift they hold through the rise and go out during the descent
        double fStart = ending() == 0 ? (lifted ? FlowSpaceMath.liftAt() + PerfectLift.DESCENT / 20.0 : FlowSpaceMath.burstAt() + 1.2) : (ending() == 1 ? FlowLook.BURST_BALL : 0);
        double fLen = ending() == 0 ? (lifted ? 1.5 : 1.4) : (ending() == 1 ? 2.0 : 1.2);
        handF = endClock < 0 ? 0 : FlowSpaceMath.clamp01((endClock - fStart) / fLen);
        dipT += dt;

        double target = FlowClient.game() == null ? SD[0] : g.s;
        FlowMeridian.follow(SD, target, 12.5, dt);
        double sd = Math.max(0, Math.min(1, SD[0]));
        for (int i = 0; i < N; i++) {
            double lit = litAt(i / (double) (N - 1), sd);
            age[i] = lit > .5 ? age[i] + dt : (lit < .02 ? 0 : age[i]);
        }
        for (int i = 0; i < g.narrows.size() && i < flare.length; i++) {
            FlowGame.Narrow nd = g.narrows.get(i);
            if (flare[i] >= 0) flare[i] += dt;
            if (flare[i] > 1.2) flare[i] = -1;
            if (lastSd < nd.s && sd >= nd.s && g.state == FlowGame.State.RUN) {
                if (nd.open) { flare[i] = 0; pendingNodes.add(i); FlowScore.event(FlowScore.Event.NODE_PASSED); }
                else { FlowSpaceFx.mistake(false); dipT = 0; FlowScore.event(FlowScore.Event.NODE_CLOSED); nudge(); }
                if (EVENTS) MurimMod.LOGGER.info("Flow event node {} open={} s={}", i, nd.open, String.format("%.3f", nd.s));
            }
        }
        if (EVENTS) {
            for (int i = 0; i < g.forks.size(); i++) {
                FlowGame.Fork f = g.forks.get(i);
                if (f.done && !forkSeen[i]) {
                    forkSeen[i] = true;
                    if (f.wrong) { FlowSpaceFx.mistake(true); dipT = 0; FlowScore.event(FlowScore.Event.FORK_WRONG); nudge(); } else { FlowSpaceFx.rightFork(); FlowScore.event(FlowScore.Event.FORK_RIGHT); }
                }
                if (lastSd < f.s - FlowMeridian.FORK_HALF && sd >= f.s - FlowMeridian.FORK_HALF) MurimMod.LOGGER.info("Flow event fork {} enter", i);
                if (lastSd < f.s && sd >= f.s) MurimMod.LOGGER.info("Flow event fork {} peak wrong={}", i, f.wrong);
            }
            if (g.state != lastState) MurimMod.LOGGER.info("Flow event state {} s={} pressure={}", g.state, String.format("%.3f", g.s), String.format("%.0f", g.pressure));
            lastState = g.state;
        }
        lastSd = sd;
        pd += (g.pressure - pd) * Math.min(1, dt * 4);
        FlowScore.update(dt, sd, pd / 100);
        boolean holding = g.state == FlowGame.State.RUN && g.hold && g.bounceT < 0;
        holdK += ((holding ? 1 : 0) - holdK) * Math.min(1, dt * 9);
        double p = pd / 100;
        seedPhase += dt * (.8 + 1.0 * p);
        float[] c = FlowMeridian.pressureColour(g.pill.colour() == 0 ? 0xE8F4EC : PillScene.hue(g.pill.colour()), pd);
        float prog = (float) sd;
        float f = (float) fade;
        if (MODE.equals("norim")) { lamp = FlowBodyRim.State.NONE; return; }
        double lampOut = endClock < 0 ? 1 : 1 - smooth(endAt - 1.0, endAt, endClock);
        double dip = 1 - FlowLook.LAMP_DIP * smooth(0, FlowLook.LAMP_DIP_IN, dipT) * (1 - smooth(FlowLook.LAMP_DIP_IN, FlowLook.LAMP_DIP_IN + FlowLook.LAMP_DIP_BACK, dipT));
        float lk = (float) (in * lampOut * dip);
        // the crisp rim goes out around the burst (a lifted figure would otherwise show its box edges alone)
        double rimOut = ending() == 0 ? 1 - smooth(FlowSpaceMath.burstAt() - .3, FlowSpaceMath.burstAt() + .5, endClock)
                : (ending() == 1 ? 1 - smooth(FlowLook.BURST_BALL, FlowLook.BURST_BALL + 1.0, endClock) : (endClock < 0 ? 1 : 1 - smooth(0, 1.0, endClock)));
        float[] lc = FlowSpaceFx.lampColour(g.pill.colour() == 0 ? PillScene.hue(0xE8F4EC) : PillScene.hue(g.pill.colour()), p);
        lamp = new FlowBodyRim.State(lk * lerp(FlowLook.TINT_START, FlowLook.TINT_END, prog), lk * (float) rimOut * lerp(FlowLook.RIM_GAIN_START, FlowLook.RIM_GAIN_END, prog),
                lk * lerp(FlowLook.INNER_START, FlowLook.INNER_END, prog), lc[0], lc[1], lc[2]);
        // the whole body as a lamp from the finish: light spreads out of the dantian over LAMP_SPREAD s, holds, goes out limbs first during the descent
        double lk2 = ending() == 0 ? smooth(0, FlowLook.LAMP_SPREAD, endClock) * (1 - handF) : 0;
        FlowBodyRim.lampK = (float) lk2;
        FlowBodyRim.lampR = (float) (lk2 * FlowLook.LAMP_REACH);
        FlowBodyRim.lampTone = lc;
        updateSpace(Minecraft.getInstance(), g, dt, sd, p);
    }

    /** A small damped nudge of the camera on a mistake (0.15 s). */
    private static void nudge() { io.github.verycooltimo.murim.client.CameraShakeHandler.quake(FxTune.f("flow.scroll.nudge", .22F), 3); }

    /** The camera's impulses of the ending (CameraShakeHandler: damped, then still): a stronger one when the core forms, a strong one (0.35 s) on the burst. */
    private static void impulses(double from, double to) {
        int e = ending();
        if (e < 0 || to <= from) return;
        double burstAt = e == 0 ? FlowSpaceMath.burstAt() : FlowLook.BURST_BALL;
        if (e == 0 && from < FlowLook.FIN_CONTRACT && to >= FlowLook.FIN_CONTRACT)
            io.github.verycooltimo.murim.client.CameraShakeHandler.quake(FxTune.f("flow.scroll.coreShake", .45F), 8);
        if (e <= 1 && from < burstAt && to >= burstAt)
            io.github.verycooltimo.murim.client.CameraShakeHandler.quake(FxTune.f("flow.scroll.burstShake", 1F), 7);
    }

    private static float lerp(float a, float b, float k) { return a + (b - a) * k; }

    /** The space layers: their state is stepped here once a frame (pending node events are placed on the body now). */
    private static void updateSpace(Minecraft mc, FlowGame g, double dt, double sd, double p01) {
        if (mc.player == null) return;
        FlowMeridian.Anchors an = anchors(mc);
        if (an == null) return;
        Vec3 o = an.body().o();
        FlowBodyRim.lampAt = o.add(0, .14, 0);
        int e = ending();
        float[] pill = lampPill(g);
        if (!pendingNodes.isEmpty()) {
            FlowMeridian.Path path = FlowMeridian.build(an);
            for (int i : pendingNodes) FlowSpaceFx.nodePassed(path.p[FlowMeridian.Path.index(g.narrows.get(i).s)]);
            pendingNodes.clear();
        }
        Vec3 core = o.add(an.body().out().scale(.05));
        Vec3 hand = BoneAnchorLayer.position(mc.player, Bone.RIGHT_FINGERS);
        FlowSpaceFx.update(new FlowSpaceFx.Params(dt, clock, sd, p01, pill, e, endClock, fade, o, floorY, core, introSpace, hand == null ? o : hand), QUALITY);
    }

    private static float[] lampPill(FlowGame g) {
        int rgb = g.pill.colour() == 0 ? PillScene.hue(0xE8F4EC) : PillScene.hue(g.pill.colour());
        return new float[] {((rgb >> 16) & 255) / 255F, ((rgb >> 8) & 255) / 255F, (rgb & 255) / 255F};
    }

    private static double litAt(double u, double sd) {
        return smooth(0, 1, Math.max(0, Math.min(1, (sd - u) / .02 + .5)));
    }

    // ------------------------------------------------------------------ geometry helpers

    private static Vec3 lerpV(Vec3 a, Vec3 b, double k) { return a.lerp(b, k); }

    /** One ribbon along {@code pts[i0..i1]}: the profile is a list of cross offsets (in halves) and alphas; per-sample alpha and colour. */
    private static void ribbon(VertexConsumer vc, PoseStack.Pose pose, Vec3[] pts, Vec3[] side, int i0, int i1, double half, double[] off, float[] pa,
                               float[] a, float[][] col) {
        for (int i = i0; i < i1; i++) {
            if (a[i] < .004F && a[i + 1] < .004F) continue;
            for (int j = 0; j < off.length - 1; j++) {
                if (pa[j] <= 0 && pa[j + 1] <= 0) continue;
                vtx(vc, pose, pts[i].add(side[i].scale(half * off[j])), col, i, a[i] * pa[j]);
                vtx(vc, pose, pts[i + 1].add(side[i + 1].scale(half * off[j])), col, i + 1, a[i + 1] * pa[j]);
                vtx(vc, pose, pts[i + 1].add(side[i + 1].scale(half * off[j + 1])), col, i + 1, a[i + 1] * pa[j + 1]);
                vtx(vc, pose, pts[i].add(side[i].scale(half * off[j + 1])), col, i, a[i] * pa[j + 1]);
            }
        }
    }

    private static final Vec3 NRM = new Vec3(0, 1, 0);

    private static Vec3 e2Of(Vec3 e1, Vec3 e2, int k) { return k == 0 ? e2 : e2.scale(Math.cos(.4 * k)).add(e1.scale(Math.sin(.4 * k))); }

    private static void vtx(VertexConsumer vc, PoseStack.Pose pose, Vec3 p, float[][] col, int i, float alpha) {
        VfxDraw.vertex(vc, pose, p, NRM, 0, 0, alpha, col[0][i], col[1][i], col[2][i]);
    }

    private static void vtx(VertexConsumer vc, PoseStack.Pose pose, Vec3 p, float[] rgb, float alpha) {
        VfxDraw.vertex(vc, pose, p, NRM, 0, 0, alpha, rgb[0], rgb[1], rgb[2]);
    }

    private static Vec3[] sides(Vec3[] pts, Vec3 cam, Vec3 out) {
        Vec3[] s = new Vec3[N];
        Vec3 prev = new Vec3(1, 0, 0);
        for (int i = 0; i < N; i++) {
            Vec3 t = pts[Math.min(N - 1, i + 1)].subtract(pts[Math.max(0, i - 1)]);
            Vec3 v = t.lengthSqr() < 1e-12 ? prev : t.normalize().cross(cam.subtract(pts[i]).normalize());
            s[i] = v.lengthSqr() < 1e-6 ? prev : v.normalize();
            if (s[i].dot(prev) < 0) s[i] = s[i].scale(-1);
            prev = s[i];
        }
        return s;
    }

    private static float[][] fill(int n, float v) {
        float[][] c = new float[3][n];
        for (float[] x : c) java.util.Arrays.fill(x, v);
        return c;
    }

    /** A flat disc (fan of rings) facing along {@code e1 x e2}: alpha profile soft (gaussian-ish) or flat with a soft rim. */
    private static void disc(VertexConsumer vc, PoseStack.Pose pose, Vec3 c, Vec3 e1, Vec3 e2, double r, float[] rgb, float alpha, boolean soft, int rings, int segs) {
        if (alpha <= .004F || r <= 0) return;
        float[] prof = new float[rings + 1];
        for (int k = 0; k <= rings; k++) {
            double x = k / (double) rings;
            prof[k] = (float) (soft ? Math.exp(-x * x * 4.2) * (1 - x * x * x * x) : 1 - smooth(.72, 1, x)) ;
        }
        prof[rings] = 0;
        for (int k = 0; k < rings; k++) {
            double r0 = r * k / rings, r1 = r * (k + 1) / rings;
            for (int s = 0; s < segs; s++) {
                double a0 = 2 * Math.PI * s / segs, a1 = 2 * Math.PI * (s + 1) / segs;
                Vec3 d0 = e1.scale(Math.cos(a0)).add(e2.scale(Math.sin(a0))), d1 = e1.scale(Math.cos(a1)).add(e2.scale(Math.sin(a1)));
                vtx(vc, pose, c.add(d0.scale(r0)), rgb, alpha * prof[k]);
                vtx(vc, pose, c.add(d0.scale(r1)), rgb, alpha * prof[k + 1]);
                vtx(vc, pose, c.add(d1.scale(r1)), rgb, alpha * prof[k + 1]);
                vtx(vc, pose, c.add(d1.scale(r0)), rgb, alpha * prof[k]);
            }
        }
    }

    /** A ring (annulus) of the given radius and thickness, with a soft outer skirt. */
    private static void ring(VertexConsumer vc, PoseStack.Pose pose, Vec3 c, Vec3 e1, Vec3 e2, double r, double thick, float[] rgb, float alpha, int segs) {
        if (alpha <= .004F) return;
        double[] rad = {r - thick * .95, r - thick * .5, r + thick * .5, r + thick * .95};
        float[] pa = {0, 1, 1, 0};
        for (int k = 0; k < 3; k++) {
            if (rad[k] < 0) continue;
            for (int s = 0; s < segs; s++) {
                double a0 = 2 * Math.PI * s / segs, a1 = 2 * Math.PI * (s + 1) / segs;
                Vec3 d0 = e1.scale(Math.cos(a0)).add(e2.scale(Math.sin(a0))), d1 = e1.scale(Math.cos(a1)).add(e2.scale(Math.sin(a1)));
                vtx(vc, pose, c.add(d0.scale(rad[k])), rgb, alpha * pa[k]);
                vtx(vc, pose, c.add(d0.scale(rad[k + 1])), rgb, alpha * pa[k + 1]);
                vtx(vc, pose, c.add(d1.scale(rad[k + 1])), rgb, alpha * pa[k + 1]);
                vtx(vc, pose, c.add(d1.scale(rad[k])), rgb, alpha * pa[k]);
            }
        }
    }

    /** Part of an ellipse tilted out of the plane of {@code e1, e2} towards {@code n}: a thin luminous arc, fading in and out along its length. */
    private static void arc(VertexConsumer vc, PoseStack.Pose pose, Vec3 c, Vec3 e1, Vec3 e2, Vec3 n, double r, double a0, double sweep, double tilt, double thick, float[] rgb, float alpha) {
        if (alpha <= .004F) return;
        int segs = 40;
        Vec3 e2t = e2.scale(Math.cos(tilt)).add(n.scale(Math.sin(tilt)));
        for (int i = 0; i < segs; i++) {
            double aa = a0 + sweep * i / segs, ab = a0 + sweep * (i + 1) / segs;
            float pa = (float) Math.sin(Math.PI * (i + .5) / segs), pb = (float) Math.sin(Math.PI * (i + 1.5) / segs);
            Vec3 da = e1.scale(Math.cos(aa)).add(e2t.scale(Math.sin(aa))), db = e1.scale(Math.cos(ab)).add(e2t.scale(Math.sin(ab)));
            double[] rr = {r - thick, r, r + thick};
            float[] ca = {0F, 1F, 0F};
            for (int q = 0; q < 2; q++) {
                vtx(vc, pose, c.add(da.scale(rr[q])), rgb, alpha * pa * ca[q]);
                vtx(vc, pose, c.add(da.scale(rr[q + 1])), rgb, alpha * pa * ca[q + 1]);
                vtx(vc, pose, c.add(db.scale(rr[q + 1])), rgb, alpha * pb * ca[q + 1]);
                vtx(vc, pose, c.add(db.scale(rr[q])), rgb, alpha * pb * ca[q]);
            }
        }
    }

    /** A slow four-point glint: two thin diamonds. */
    private static void star(VertexConsumer vc, PoseStack.Pose pose, Vec3 c, Vec3 e1, Vec3 e2, double len, double ang, float[] rgb, float alpha) {
        for (int k = 0; k < 2; k++) {
            double a = ang + k * Math.PI / 2;
            Vec3 d = e1.scale(Math.cos(a)).add(e2.scale(Math.sin(a))), q = e1.scale(-Math.sin(a)).add(e2.scale(Math.cos(a)));
            vtx(vc, pose, c.add(d.scale(len)), rgb, 0);
            vtx(vc, pose, c.add(q.scale(len * .17)), rgb, alpha);
            vtx(vc, pose, c.add(d.scale(-len)), rgb, 0);
            vtx(vc, pose, c.add(q.scale(-len * .17)), rgb, alpha);
        }
    }

    // ------------------------------------------------------------------ the floor light

    private static double nextLight;
    private static Vec3 clotWorld;

    /**
     * The floor disc in the pill colour, radius 1 -> 3 blocks, with falloff, from the engine's own ground light ({@link TechniqueLight}: it samples the real
     * floor top, lights the floor cells and the body). A light has a fixed radius, so a new one with the current radius is added every 0.15 s and the
     * envelopes overlap (attack 3, hold 2, release 4 ticks): the disc grows and breathes with the progress and the pressure without a pop.
     */
    static void updateFloor(Minecraft mc) {
        if (OFF || MODE.equals("nofloor") || bound == null || (fade <= .004 && introSpace <= .004) || mc.player == null || clock < nextLight) return;
        Vec3 o = BoneAnchorLayer.position(mc.player, Bone.MERIDIAN_O);
        if (o == null) return;
        nextLight = clock + .15;
        float[] c = FlowMeridian.pressureColour(PillScene.hue(bound.pill.colour()), pd);
        double sd = Math.max(0, Math.min(1, SD[0]));
        float radius = (float) (FlowLook.FLOOR_R0 + (FlowLook.FLOOR_R1 - FlowLook.FLOOR_R0) * smooth(0, 1, sd));
        int rgb = (Math.round(c[0] * 255) << 16) | (Math.round(c[1] * 255) << 8) | Math.round(c[2] * 255);
        float gain = 1F;
        if (ending() == 0) {
            double te = endClock, b = FlowSpaceMath.burstAt();
            gain += (float) (FlowLook.FIN_LIGHT_GAIN * smooth(0, FlowLook.FIN_CONTRACT, te)) * (te > b ? (float) (1 - smooth(b, b + 1.5, te)) : 1F);
            radius += (float) (2.5 * smooth(b - .2, b + .2, te) * (1 - smooth(b + .4, b + 1.6, te)));
        }
        TechniqueLight.add(new Vec3(o.x, mc.player.getY() + FlowLook.FLOOR_LIGHT_LIFT, o.z), rgb, radius, (float) Math.min(1, FlowLook.FLOOR_STRENGTH * Math.max(fade, introSpace * (ending() < 0 ? 1 : fade)) * gain), 3, 2, 4);
        Vec3 armAt = FlowSpaceFx.armLight();
        if (armAt != null) TechniqueLight.add(armAt, rgb, FlowLook.ARM_LIGHT_R, (float) (FlowLook.ARM_LIGHT_I * fade), 3, 2, 4);
        Vec3 cl = clotWorld;
        if (cl != null && endClock < 0) TechniqueLight.add(cl, rgb, FlowLook.CLOT_LIGHT_R, (float) (FlowLook.CLOT_LIGHT_I * fade), 3, 2, 4);
    }

    // ------------------------------------------------------------------ the body

    static FlowMeridian.Anchors anchors(Minecraft mc) {
        var p = mc.player;
        Vec3 o = BoneAnchorLayer.position(p, Bone.MERIDIAN_O), x = BoneAnchorLayer.position(p, Bone.MERIDIAN_X), y = BoneAnchorLayer.position(p, Bone.MERIDIAN_Y),
                n = BoneAnchorLayer.position(p, Bone.MERIDIAN_N), rt = BoneAnchorLayer.position(p, Bone.MERIDIAN_RIGHT_TOP),
                rb = BoneAnchorLayer.position(p, Bone.MERIDIAN_RIGHT_BOT), rl = BoneAnchorLayer.position(p, Bone.MERIDIAN_RIGHT_LAT),
                lt = BoneAnchorLayer.position(p, Bone.MERIDIAN_LEFT_TOP), lb = BoneAnchorLayer.position(p, Bone.MERIDIAN_LEFT_BOT),
                ll = BoneAnchorLayer.position(p, Bone.MERIDIAN_LEFT_LAT);
        if (o == null || x == null || y == null || n == null || rt == null || rb == null || rl == null || lt == null || lb == null || ll == null) return null;
        return FlowMeridian.Anchors.of(o, x, y, n, rt, rb, rl, lt, lb, ll);
    }

    /** +1 or -1: which side of the fork's lateral direction is the branch the key points to (-1 = A = the screen's left). */
    private static int branchSign(FlowMeridian.Path path, int i, Vec3 out, Vec3 right, Vec3 up, int key) {
        Vec3 l = FlowMeridian.lateral(path, i, out);
        double dr = l.dot(right);
        int leftSign = Math.abs(dr) > .3 ? (dr < 0 ? 1 : -1) : (l.dot(up) > 0 ? 1 : -1);
        return key < 0 ? leftSign : -leftSign;
    }

    /** The space layers and the body's crisp rim, in the scene's pass (the scene's depth: the figure stands inside the vortex and the sheets). */
    static void drawSpace(Minecraft mc, PoseStack.Pose pose, Vec3 cam, MultiBufferSource.BufferSource buffers) {
        if (OFF || bound == null || mc.player == null) return;
        long t0 = System.nanoTime();
        if (!MODE.equals("norim") && !MODE.equals("nohull")) FlowBodyRim.drawHull(pose, cam, buffers);
        if (!MODE.equals("nospace")) FlowSpaceFx.draw(pose, cam, buffers, QUALITY.tier());
        spaceSum += (System.nanoTime() - t0) / 1e6;
        if (++spaceFrames == 240) {
            MurimMod.LOGGER.info(String.format("Flow space cpu build avg=%.3f ms tier=%d", spaceSum / spaceFrames, QUALITY.tier()));
            spaceSum = 0; spaceFrames = 0;
        }
    }

    private static boolean extentLogged;
    private static double spaceSum;
    private static int spaceFrames;

    static void drawBody(Minecraft mc, PoseStack.Pose pose, Vec3 cam, MultiBufferSource.BufferSource buffers) {
        if (OFF || MODE.equals("nobody") || bound == null || fade <= .004 || mc.player == null) return;
        FlowMeridian.Anchors an = anchors(mc);
        if (an == null) return;
        long t0 = System.nanoTime();
        FlowGame g = bound;
        FlowMeridian.Body body = an.body();
        FlowMeridian.Path path = FlowMeridian.build(an);
        Vec3 out = body.out();
        double sd = Math.max(0, Math.min(1, SD[0]));
        // when the game ends cleanly the board (channels, rings, clot) goes out over BOARD_OUT s; nothing of the interface stays on the body
        float fd = (float) (fade * (ending() == 0 ? 1 - smooth(0, FlowLook.BOARD_OUT, endClock) : 1));
        // One palette: the pill's colour and white (red from the pressure, orange-red for a shut node, green on a poison span).
        float[] base = FlowMeridian.pressureColour(PillScene.hue(g.pill.colour()), pd);
        float p01 = (float) Math.max(0, Math.min(1, pd / 100));
        float[] pale = new float[] {base[0] * FlowLook.PALE_DEEP, base[1] * FlowLook.PALE_DEEP, base[2] * FlowLook.PALE_DEEP};
        float[] ash = new float[] {base[0] * .25F + .75F * .45F, base[1] * .25F + .75F * .43F, base[2] * .25F + .75F * .40F};

        // ---- per sample. Only the first pass over an arm is drawn; the way back (second pass) only makes it burn whiter.
        float[] L1 = new float[N], H1 = new float[N], L2 = new float[N], H2 = new float[N], vis = new float[N], mask = new float[N];
        float[][] colBody = new float[3][N], colCore = new float[3][N], colPale = new float[3][N];
        double[] bumpMax = new double[N];
        float[] lit = new float[N], heat = new float[N];
        boolean holdBright = false;
        for (int i = 0; i < N; i++) {
            double u = i / (double) (N - 1);
            lit[i] = (float) litAt(u, sd);
            heat[i] = (float) (lit[i] * (holdBright ? 1 : FlowMeridian.heat(age[i])));
        }
        // the lit channels fade from the hands inward (finale, backlash, other ends)
        float[] pf = new float[N];
        {
            int[] tn = path.turns;
            double maxHd = 1;
            for (int i = 0; i < N; i++) maxHd = Math.max(maxHd, Math.min(Math.abs(i - tn[0]), Math.abs(i - tn[1])));
            for (int i = 0; i < N; i++) pf[i] = (float) FlowSpaceMath.handFade(handF, Math.min(Math.abs(i - tn[0]), Math.abs(i - tn[1])) / maxHd);
        }
        double[] forkU = new double[g.forks.size()];
        for (int q = 0; q < forkU.length; q++) forkU[q] = path.canon(g.forks.get(q).s);
        for (int i = 0; i < N; i++) {
            double u = i / (double) (N - 1);
            vis[i] = path.second[i] ? 0F : pf[i];
            L1[i] = lit[i]; H1[i] = heat[i];
            int m = path.mate[i];
            if (!path.second[i] && m >= 0) { L2[i] = lit[m]; H2[i] = heat[m]; }
            double pz = 0;
            for (double[] sp : g.poison) pz = Math.max(pz, smooth(sp[0] - .008, sp[0] + .008, u) * (1 - smooth(sp[1] - .008, sp[1] + .008, u)));
            float w2 = L2[i] * (.35F + .65F * H2[i]);
            for (int k = 0; k < 3; k++) {
                float b = base[k] + (GREEN[k] - base[k]) * (float) pz;
                colBody[k][i] = b + (1 - b) * .5F * w2;
                colCore[k][i] = b + (1 - b) * (.84F + .1F * p01);
                colPale[k][i] = pale[k] + (GREEN[k] * .6F + .4F * pale[k] - pale[k]) * (float) pz;
            }
            for (int q = 0; q < forkU.length; q++) bumpMax[i] = Math.max(bumpMax[i], FlowMeridian.bump(u, forkU[q], FlowMeridian.FORK_HALF));
            mask[i] = (float) (1 - smooth(0, .2, bumpMax[i])) * vis[i];
        }
        if (!extentLogged) {
            extentLogged = true;
            double lo = 1e9, hi = -1e9;
            for (int i = 0; i < N; i++) { lo = Math.min(lo, path.p[i].y); hi = Math.max(hi, path.p[i].y); }
            MurimMod.LOGGER.info(String.format("Flow meridian extent y %.3f..%.3f = %.3f blocks (dantian %.3f, collar max)", lo - floorY, hi - floorY, hi - lo, path.p[0].y - floorY));
        }
        Vec3[] side = sides(path.p, cam, out);
        double[] full = {-1, -.82, .82, 1};
        float[] flat = {0, 1, 1, 0};
        double[] glowOff = {-FlowLook.GLOW_REACH, 0, FlowLook.GLOW_REACH};
        float[] glowProf = {0, 1, 0};

        // The old board on the body (channels, nodes, forks, clot, rings) is drawn only with flow.board.onBody = 1; the scroll (FlowScrollBoard) is the board otherwise.
        boolean onBody = !FlowScrollBoard.scrollMode();
        VertexConsumer vc;
        int segs = QUALITY.tier() >= 2 ? 28 : 44;
        float[] white = {1F, 1F, 1F};
        if (onBody) {
        // the clot's side in every fork follows the player's pick smoothly
        for (int q = 0; q < g.forks.size() && q < sigma.length; q++) {
            FlowGame.Fork f = g.forks.get(q);
            int mid = FlowMeridian.Path.index(forkU[q]);
            int key = f.done ? f.goodKey : (g.side != 0 ? g.side : f.chosen);
            double tgt = key == 0 ? 0 : branchSign(path, mid, out, body.right(), body.up(), key);
            double[] st = {sigma[q], sigmaV[q]};
            FlowMeridian.follow(st, tgt, 9, dtNow);
            sigma[q] = st[0]; sigmaV[q] = st[1];
        }

        // ---- glow (additive) under everything: it lights the model's surface round the channel
        vc = buffers.getBuffer(MurimRenderTypes.flowGlow());
        if (QUALITY.tier() < 3) {
            float[] ga = new float[N];
            for (int i = 0; i < N; i++) ga[i] = fd * mask[i] * (L1[i] * (FlowLook.GLOW_BASE + FlowLook.GLOW_HEAT * H1[i]) + L2[i] * FlowLook.GLOW_SIDE * (.4F + .6F * H2[i]) + FlowLook.GLOW_PRESSURE * p01 * L1[i]);
            ribbon(vc, pose, path.p, side, 0, N - 1, FlowLook.CHANNEL_HALF, glowOff, glowProf, ga, colBody);
        }
        record Branch(int fork, int i0, int i1, Vec3[] pts, Vec3[] side, boolean live, float tk) { }
        java.util.List<Branch> branches = new java.util.ArrayList<>();
        int fi = 0;
        for (FlowGame.Fork f : g.forks) {
            int i0 = Math.max(0, FlowMeridian.Path.index(forkU[fi] - FlowMeridian.FORK_HALF)), i1 = Math.min(N - 1, FlowMeridian.Path.index(forkU[fi] + FlowMeridian.FORK_HALF));
            int mid = FlowMeridian.Path.index(forkU[fi]);
            int live = branchSign(path, mid, out, body.right(), body.up(), f.goodKey);
            for (int sg : new int[] {-1, 1}) {
                Vec3[] bp = path.p.clone();
                for (int i = i0; i <= i1; i++) {
                    double u = i / (double) (N - 1);
                    double d = FlowMeridian.forkSpread(path.zone[i]) * FlowMeridian.bump(u, forkU[fi], FlowMeridian.FORK_HALF);
                    bp[i] = path.p[i].add(FlowMeridian.lateral(path, i, out).scale(sg * d));
                }
                float tk = (float) Math.max(0, Math.min(1, 1 - Math.abs((fi < sigma.length ? sigma[fi] : 0) - sg)));
                branches.add(new Branch(fi, i0, i1, bp, sides(bp, cam, out), sg == live, tk));
            }
            fi++;
        }
        for (Branch b : branches) {
            if (b.live && QUALITY.tier() < 3) {
                float[] ga = new float[N];
                for (int i = b.i0; i <= b.i1; i++) {
                    float s = (float) smooth(0, .2, bumpMax[i]) * vis[i];
                    ga[i] = fd * s * (.07F + .5F * (b.tk * L1[i] * (.25F + .5F * H1[i])));
                }
                ribbon(vc, pose, b.pts, b.side, b.i0, b.i1, FlowLook.CHANNEL_HALF, glowOff, glowProf, ga, colBody);
            }
        }
        buffers.endBatch(MurimRenderTypes.flowGlow());

        // ---- flat bodies (alpha blended): pale map, lit body (pill colour), white-hot core
        vc = buffers.getBuffer(MurimRenderTypes.flowSolid());
        float[] pa = new float[N], ba = new float[N], ca = new float[N];
        for (int i = 0; i < N; i++) {
            pa[i] = fd * mask[i] * FlowLook.PALE_ALPHA * (1 - L1[i]);
            ba[i] = fd * mask[i] * L1[i] * (FlowLook.LIT_ALPHA + FlowLook.LIT_HEAT_ALPHA * H1[i]);
            float hot = Math.min(1F, H1[i] + .55F * L2[i] * (.4F + .6F * H2[i]));
            ca[i] = fd * mask[i] * L1[i] * (.14F + .86F * hot) * .96F;
        }
        ribbon(vc, pose, path.p, side, 0, N - 1, FlowLook.CHANNEL_HALF, full, flat, pa, colPale);
        ribbon(vc, pose, path.p, side, 0, N - 1, FlowLook.CHANNEL_HALF, full, flat, ba, colBody);
        for (Branch b : branches) {
            float[] a1 = new float[N], a2 = new float[N];
            float[][] cols = new float[3][N], palec = new float[3][N];
            for (int i = b.i0; i <= b.i1; i++) {
                double u = i / (double) (N - 1);
                float s = (float) smooth(0, .2, bumpMax[i]) * vis[i];
                if (b.live) {
                    float bl = L1[i] * b.tk;
                    a1[i] = fd * s * (.5F * (1 - bl));
                    a2[i] = fd * s * bl * (.60F + .38F * H1[i]);
                    for (int k = 0; k < 3; k++) { cols[k][i] = colBody[k][i]; palec[k][i] = pale[k]; }
                } else {
                    double x = (u - (forkU[b.fork] - FlowMeridian.FORK_HALF)) / (2 * FlowMeridian.FORK_HALF);
                    double gap = 0;
                    for (double gx : new double[] {.27, .52, .77}) gap += Math.exp(-Math.pow((x - gx) / .03, 2));
                    a1[i] = fd * s * .75F * (float) Math.max(0, 1 - gap);
                    for (int k = 0; k < 3; k++) palec[k][i] = ash[k] + (KNOT[k] - ash[k]) * b.tk;
                }
            }
            ribbon(vc, pose, b.pts, b.side, b.i0, b.i1, FlowLook.CHANNEL_HALF, full, flat, a1, palec);
            if (b.live) ribbon(vc, pose, b.pts, b.side, b.i0, b.i1, FlowLook.CHANNEL_HALF, full, flat, a2, cols);
        }
        double[] coreOff = {-1, -.7, .7, 1};
        ribbon(vc, pose, path.p, side, 0, N - 1, FlowLook.CHANNEL_HALF * FlowLook.CORE_SHARE, coreOff, flat, ca, colCore);
        for (Branch b : branches) {
            if (!b.live) continue;
            float[] a3 = new float[N];
            for (int i = b.i0; i <= b.i1; i++) a3[i] = fd * (float) smooth(0, .2, bumpMax[i]) * vis[i] * L1[i] * b.tk * (.14F + .86F * H1[i]) * .96F;
            ribbon(vc, pose, b.pts, b.side, b.i0, b.i1, FlowLook.CHANNEL_HALF * FlowLook.CORE_SHARE, coreOff, flat, a3, colCore);
        }
        buffers.endBatch(MurimRenderTypes.flowSolid());

        // ---- additive details: nodes (a thin crisp ring with a bead, on the face), beads on the channel, clot, rings, seed
        vc = buffers.getBuffer(MurimRenderTypes.flowGlow());
        for (int ni = 0; ni < g.narrows.size(); ni++) {
            FlowGame.Narrow nd = g.narrows.get(ni);
            int i = FlowMeridian.Path.index(nd.s);
            Vec3 c = path.p[i].add(out.scale(.01));
            Vec3 e1 = path.t[i], e2 = FlowMeridian.lateral(path, i, out);
            double ap = FlowMeridian.aperture(g.t, nd.period, nd.phase);
            double passed = smooth(nd.s - .01, nd.s + .02, sd) < .5 ? 0 : 1;
            double r = FlowMeridian.nodeRadius(ap);
            // a ring on an arm is never wider than the arm (a quarter block): its diameter fits the face it lies on
            if (path.zone[i] == FlowMeridian.Zone.ARM) r = Math.min(r, FlowLook.RING_ARM_MAX);
            double knot = FlowMeridian.nodeKnot(ap);
            double fl = ni < flare.length ? flare[ni] : -1;
            if (fl >= 0 && fl < .5) r *= 1 + .18 * Math.sin(Math.PI * fl / .5);
            double open = 1 - knot;
            float[] col = new float[3];
            for (int k = 0; k < 3; k++) col[k] = KNOT[k] + ((base[k] * .6F + .4F) - KNOT[k]) * (float) smooth(.25, .85, open);
            float dim = (float) (passed > 0 && g.state == FlowGame.State.RUN ? FlowLook.NODE_PASSED_DIM : 1) * pf[i];
            ring(vc, pose, c, e1, e2, r, FlowLook.RING_THICK, col, fd * FlowLook.RING_ALPHA * dim, segs);
            if (knot > .01) {
                disc(vc, pose, c, e1, e2, r * .5 * (.5 + .5 * knot), KNOT, fd * (float) knot * .85F * dim, true, 4, 16);
                disc(vc, pose, c, e1, e2, .014, new float[] {1F, .85F, .6F}, fd * (float) knot * .9F * dim, true, 3, 12);
            } else {
                disc(vc, pose, c, e1, e2, .017, white, fd * .9F * dim, true, 3, 14);
            }
            if (fl >= 0 && fl < 1.0) {
                double k = fl / 1.0;
                double rr = r * (1 + .7 * (1 - Math.pow(1 - k, 2)));
                if (path.zone[i] == FlowMeridian.Zone.ARM) rr = Math.min(rr, FlowLook.RING_RIPPLE_ARM_MAX);
                float al = (float) (fd * .8 * Math.min(1, fl * 4) * Math.pow(1 - k, 1.5));
                ring(vc, pose, c, e1, e2, rr, FlowLook.RING_THICK, base, al * pf[i], segs);
            }
        }
        // small beads of light sit on the lit channel and creep along it
        for (int k = 0; k < 14; k++) {
            double u = (k / 14.0 + clock * .012) % 1.0;
            int i = FlowMeridian.Path.index(u);
            if (path.second[i]) continue;
            float al = fd * L1[i] * mask[i] * .9F;
            if (al > .01) disc(vc, pose, path.p[i].add(out.scale(.012)), side[i], path.t[i], .011, white, al * .8F, true, 3, 10);
        }
        // beads flowing in the live branches
        for (Branch b : branches) {
            if (!b.live) continue;
            for (int k = 0; k < 3; k++) {
                double ph = (clock * .3 + k / 3.0 + b.fork * .17) % 1.0;
                int i = (int) Math.round(b.i0 + ph * (b.i1 - b.i0));
                float al = (float) (fd * Math.sin(Math.PI * ph) * .9) * vis[i];
                Vec3 f = cam.subtract(b.pts[i]).normalize();
                Vec3 e1 = f.cross(new Vec3(0, 1, 0)).normalize(), e2 = e1.cross(f).normalize();
                disc(vc, pose, b.pts[i].add(out.scale(.012)), e1, e2, .013, white, al * .8F, true, 3, 12);
            }
        }
        // the clot
        if (g.state == FlowGame.State.RUN || endClock < HOLD_END) {
            int ci = FlowMeridian.Path.index(sd);
            double cu = path.canon(sd);
            double lat = 0;
            for (int fk = 0; fk < g.forks.size(); fk++) {
                lat += (fk < sigma.length ? sigma[fk] : 0) * FlowMeridian.forkSpread(path.zone[ci]) * FlowMeridian.bump(cu, forkU[fk], FlowMeridian.FORK_HALF);
            }
            Vec3 c = path.at(sd).add(FlowMeridian.lateral(path, ci, out).scale(lat)).add(out.scale(.018));
            clotWorld = c.add(out.scale(.1));
            Vec3 f = cam.subtract(c).normalize();
            Vec3 e1 = f.cross(new Vec3(0, 1, 0)).normalize(), e2 = e1.cross(f).normalize();
            float cf = g.state == FlowGame.State.RUN ? fd : fd * (float) (1 - smooth(0, HOLD_END, endClock));
            double rc = FlowLook.CLOT_R * (1 + .06 * Math.sin(clock * 2 * Math.PI * .6));
            double bounce = g.bounceT >= 0 ? .6 : 1;
            disc(vc, pose, c, e1, e2, rc * 1.5, base, cf * .22F * (float) bounce, true, 6, 24);
            disc(vc, pose, c, e1, e2, rc, base, cf * .85F * (float) bounce, false, 4, 24);
            disc(vc, pose, c, e1, e2, rc * .5, white, cf * .98F, true, 4, 20);
            star(vc, pose, c, e1, e2, rc * 1.8, clock * .26, white, cf * (.28F + .14F * (float) Math.sin(clock * 2 * Math.PI * .5)));
            double hk = holdK;
            if (hk > .01) ring(vc, pose, c, e1, e2, rc * (2.6 - .9 * hk), .008, base, cf * (float) hk * .9F, segs);
            if (g.surging()) {
                double k = g.surgeT / .38;
                ring(vc, pose, c, e1, e2, rc * (3.0 - 1.2 * k), .008, KNOT, cf * (float) Math.min(1, Math.min(k * 3.5, (1 - k) * 4)) * .95F, segs);
            }
        }
        } else {
            vc = buffers.getBuffer(MurimRenderTypes.flowGlow());
        }
        // the dantian seed
        {
            double p = pd / 100;
            double r = (.10 + .20 * p) * (1 + .07 * Math.sin(seedPhase * 2 * Math.PI));
            Vec3 c = path.p[0].add(out.scale(.03));
            Vec3 f = cam.subtract(c).normalize();
            Vec3 e1 = f.cross(new Vec3(0, 1, 0)).normalize(), e2 = e1.cross(f).normalize();
            float[] cw = {base[0] + (1 - base[0]) * .55F, base[1] + (1 - base[1]) * .55F, base[2] + (1 - base[2]) * .55F};
            int en = ending();
            float sa = fd * (en < 0 ? 1F : (en == 0 ? (float) (1 - FlowSpaceMath.ball(endClock, false)) : (en == 1 ? (float) (1 - FlowSpaceMath.ball(endClock, true)) : (float) (1 - smooth(0, 1.2, endClock)))));
            disc(vc, pose, c, e1, e2, r * 1.6, base, sa * .2F, true, 6, 24);
            disc(vc, pose, c, e1, e2, r * .95, cw, sa * .6F, false, 4, 24);
            disc(vc, pose, c, e1, e2, r * .42, white, sa * .95F, true, 4, 20);
            star(vc, pose, c, e1, e2, r * 2.2, -clock * .2, white, sa * .5F);
        }
        // the black core (ref «5_black_circle»): a big black ball, a white-hot corona as wide as the torso, large thin luminous arcs turning round it and soft petal shapes
        // fading out of it; it grows over FIN_BALL s, rides the lift and bursts in the air
        double ballK = 0, ballGrow = 1;
        int en2 = ending();
        if (en2 == 0 && endClock >= FlowLook.FIN_CONTRACT - .1) {
            ballK = smooth(FlowLook.FIN_CONTRACT - .1, FlowLook.FIN_CONTRACT + .2, endClock) * (1 - smooth(FlowSpaceMath.burstAt() - .04, FlowSpaceMath.burstAt() + .06, endClock));
            ballGrow = .2 + .8 * smooth(FlowLook.FIN_CONTRACT, FlowLook.FIN_CONTRACT + FlowLook.FIN_BALL, endClock);
        }
        if (en2 == 1) { ballK = smooth(0, .15, endClock) * (1 - smooth(FlowLook.BURST_BALL + .3, FlowLook.BURST_BALL + .6, endClock)); ballGrow = .3 + .7 * smooth(0, FlowLook.BURST_BALL, endClock); }
        Vec3 bc = path.p[0].add(out.scale(.08));
        Vec3 bf = cam.subtract(bc).normalize();
        Vec3 be1 = bf.cross(new Vec3(0, 1, 0)).normalize(), be2 = be1.cross(bf).normalize();
        if (ballK > .01) {
            float k = (float) ballK;
            double grow = ballGrow;
            float[] wh = {1F, 1F, 1F}, hotc = {base[0] * .3F + .7F, base[1] * .3F + .7F, base[2] * .3F + .7F};
            // the floor lit white under it
            disc(vc, pose, new Vec3(bc.x, floorY + .045, bc.z), new Vec3(1, 0, 0), new Vec3(0, 0, 1), FlowLook.CORE_FLOOR_R, hotc, k * FlowLook.CORE_FLOOR_ALPHA * .7F, true, 6, 32);
            // corona: white core -> pale pill tone, as wide as the torso (radius 0.9)
            disc(vc, pose, bc, be1, be2, .9 * grow, base, k * .55F, true, 7, 36);
            disc(vc, pose, bc, be1, be2, .55 * grow, hotc, k * .6F, true, 6, 32);
            disc(vc, pose, bc, be1, be2, .3 * grow, wh, k * .9F, true, 5, 28);
            // soft petal shapes fading out of it
            for (int i = 0; i < 8; i++) {
                double fr = (clock * .45 + i / 8.0) % 1, ang = i * Math.PI / 4 + .3 * Math.sin(i * 2.1);
                Vec3 rad = be1.scale(Math.cos(ang)).add(be2.scale(Math.sin(ang))), tan = be1.scale(-Math.sin(ang)).add(be2.scale(Math.cos(ang)));
                double rho = (.3 + .75 * fr) * grow;
                disc(vc, pose, bc.add(rad.scale(rho)), rad.scale(.26 * grow), tan.scale(.1 * grow), 1, hotc, k * .5F * (float) Math.pow(1 - fr, 1.3) * (float) Math.min(1, fr * 6), true, 3, 14);
            }
            // large thin luminous arcs, tilted and slowly turning
            for (int i = 0; i < 3; i++) {
                arc(vc, pose, bc, be1, be2, bf, (.55 + .25 * i) * grow, clock * (.5 + .2 * i) + i * 2.1, 3.6, .5 + .4 * i, .016, i == 1 ? hotc : wh, k * .85F);
            }
        }
        buffers.endBatch(MurimRenderTypes.flowGlow());
        if (ballK > .01) {
            VertexConsumer sv = buffers.getBuffer(MurimRenderTypes.flowSolid());
            disc(sv, pose, bc.add(bf.scale(.05)), be1, be2, FlowLook.BALL_R * (.4 + .6 * ballGrow), new float[] {0, 0, 0}, (float) Math.min(1, ballK * 1.6) * .98F, false, 4, 32);
            buffers.endBatch(MurimRenderTypes.flowSolid());
        }
        cpuSum += (System.nanoTime() - t0) / 1e6;
        cpuFrames++;
        if (cpuFrames == 240) {
            MurimMod.LOGGER.info(String.format("Flow meridian cpu build avg=%.3f ms tier=%d", cpuSum / cpuFrames, QUALITY.tier()));
            cpuSum = 0; cpuFrames = 0;
        }
    }
}
