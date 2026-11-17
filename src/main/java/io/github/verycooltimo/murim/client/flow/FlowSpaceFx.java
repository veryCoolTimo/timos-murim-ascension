package io.github.verycooltimo.murim.client.flow;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.client.vfx.MurimRenderTypes;
import io.github.verycooltimo.murim.client.vfx.fx.FxCultivationMotes;
import io.github.verycooltimo.murim.client.vfx.fx.FxDust;
import io.github.verycooltimo.murim.client.vfx.fx.FxDustSim;
import io.github.verycooltimo.murim.client.vfx.fx.FxEssence;
import io.github.verycooltimo.murim.client.vfx.fx.FxFlame;
import io.github.verycooltimo.murim.client.vfx.fx.FxFlameLook;
import io.github.verycooltimo.murim.client.vfx.fx.FxQuality;
import io.github.verycooltimo.murim.client.vfx.fx.FxTune;
import io.github.verycooltimo.murim.client.ClientPillState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * THE SPACE of the high pill game (director's brief section B, stage 2): it answers the game. The vortex of broad cloth arms (ref «absorbing2»),
 * the cloth aura of big translucent flame sheets (ref «cultivating it»), the dots riding the arms, the embers, the dust rings, the events (a node
 * passed: a ripple runs out along the nearest arm and a ring of dust lies on the floor; the right fork: the vortex gains an arm; a mistake: the vortex
 * stalls and shudders once and a sheet tears into deep-red chips), the backlash (arms and sheets thrown out as red sheets and chips, then ash) and the
 * clean finale (the arms wind into the dantian, the radial burst of 240 rays, the pale brightening of at most 0.3 s, the dust ring to 5 blocks, settling motes).
 * It is driven once a frame by {@link FlowMeridianFx} and drawn in the scene's pass (the scene's depth, so the figure stands inside it). Nothing is drawn on
 * the screen except the single pale brightening. All numbers: {@link FlowLook}; pure maths: {@link FlowSpaceMath}.
 */
final class FlowSpaceFx {
    private FlowSpaceFx() { }

    /** What the director hands over every frame. {@code ending}: -1 the run, 0 clean finale, 1 backlash, 2 other; {@code te} seconds since the end (-1 in the run). */
    record Params(double dt, double clock, double progress, double pressure01, float[] pill, int ending, double te, double fade, Vec3 centre, double floorY, Vec3 core, double intro, Vec3 hand) { }

    /** The strength of the space layers: they grow out of the intro in the run, then follow the end fade. */
    /** Curtains start rising 0.4 s into the intro (dark fire from the floor) and take the full intro to grow. */
    private static double sheetEnv() {
        if (P.ending() >= 0) return P.fade();
        double ic = P.clock() - FlowLook.INTRO_LEAD;
        return FlowSpaceMath.smooth(.3, FlowLook.INTRO_DISSOLVE + FlowLook.INTRO_FLAME, ic);
    }

    private static double env() { return P.ending() < 0 ? P.intro() : P.fade(); }

    private static final Vec3 NRM = new Vec3(0, 1, 0);
    private static final FxDust.Look RED_CHIP = new FxDust.Look(0xC8444A, 0x7A1A22, 0x2E0A0E, 0x120406, 1F, .4F, .55F, 5, -.5F, .85F, .2F, .3F, .75F);
    private static final FxDust.Look ASH = new FxDust.Look(0xA89A96, 0x625654, 0x302A2A, 0x141010, 0F, .5F, .6F, 5, -.4F, .9F, .4F, 0F, .3F);

    // ------------------------------------------------------------------ state
    private static final Random RNG = new Random(15);
    private static Params P;
    private static double rot, stallT = 99, brightT = 99;
    private static int armsActive = FlowLook.ARMS_START;
    /** -1: the high pill game (the numbers of FlowLook as they are); 0 common, 1 middle: the calm absorption, everything scaled by FlowLook.CALM_*. */
    private static int grade = -1;

    static void setGrade(int g) { grade = g; }
    static int grade() { return grade; }

    /** The level the fire sheets (count, height) follow: in the scroll game the stepped score ({@link FlowScore}), otherwise the drawn progress. */
    private static double fireLevel() { return FlowScore.fire(P == null ? 0 : P.progress()); }

    /** A mistake in the scroll game: the fire drops (FlowScore) and sheds dark flakes, no flash, no torn sheet. */
    static void shedFlakes(int n) {
        if (P == null) return;
        for (int i = 0; i < n; i++) {
            double a = RNG.nextDouble() * 6.2832, r = .7 + RNG.nextDouble() * .8, h = .5 + RNG.nextDouble() * 1.3;
            chips.add(new double[] {Math.sin(a) * r, h, Math.cos(a) * r, Math.sin(a) * .35, .25 + RNG.nextDouble() * .5, Math.cos(a) * .35,
                    0, 1.3 + RNG.nextDouble() * .7, .03 + RNG.nextDouble() * .04, RNG.nextDouble(), 1});
        }
    }

    private static double gd(double[] v, double high) { return grade < 0 ? high : v[grade]; }

    private static double vortexRim(double progress) {
        return grade < 0 ? FlowSpaceMath.vortexRadius(progress) : FlowSpaceMath.lerp(FlowLook.CALM_R0[grade], FlowLook.CALM_R1[grade], FlowSpaceMath.smooth(0, 1, progress));
    }
    private static final double[] armGrow = new double[FlowLook.ARMS_MAX], armFlare = new double[FlowLook.ARMS_MAX], armBright = new double[FlowLook.ARMS_MAX];
    private static final double[] sheetTear = new double[FlowLook.SHEETS_MAX + 2];
    private static final double[] sheetBorn = new double[FlowLook.SHEETS_MAX + 2];
    private static final List<double[]> ripples = new ArrayList<>();       // {arm, age}
    private static final List<double[]> rings = new ArrayList<>();         // {x, z, r0, r1, life, size0, size1, seed, age, puffs}
    private static final List<double[]> chips = new ArrayList<>();         // {x,y,z, vx,vy,vz, age, life, size, seed, ash(0/1)}
    private static final List<double[]> motes = new ArrayList<>();         // essence {x,y,z, vx,vy,vz, age, life, size, seed} (world)
    private static double liftCarry;
    private static final List<double[]> rays = new ArrayList<>();          // {dx,dy,dz, len, width, speed, delay, seed}
    private static FxCultivationMotes embers;
    private static boolean burstSpawned, throwSpawned, ashSpawned, finalDust, pulseSpawned;
    private static double lastTe = -1;
    private static int tearNext;

    static void reset() {
        rot = 0; stallT = 99; brightT = 99; armsActive = FlowLook.ARMS_START;
        java.util.Arrays.fill(armGrow, 0); java.util.Arrays.fill(armFlare, 99); java.util.Arrays.fill(armBright, 99);
        java.util.Arrays.fill(sheetTear, 99); java.util.Arrays.fill(sheetBorn, -1);
        ripples.clear(); rings.clear(); chips.clear(); rays.clear(); motes.clear(); liftCarry = 0;
        embers = null; burstSpawned = throwSpawned = ashSpawned = finalDust = pulseSpawned = false; lastTe = -1; tearNext = 0; P = null;
    }

    // ------------------------------------------------------------------ colours
    private static float[] tone(float[] pill, double p01) {
        double red = FlowSpaceMath.smooth(.55, .9, p01);
        float[] r = {.93F, .22F, .2F};
        float[] o = new float[3];
        for (int i = 0; i < 3; i++) o[i] = (float) FlowSpaceMath.lerp(pill[i], r[i], red);
        return o;
    }

    /** The lamp's colour: the pill colour, red from the pressure (as the vortex). */
    static float[] lampColour(int rgb, double p01) {
        return tone(new float[] {((rgb >> 16) & 255) / 255F, ((rgb >> 8) & 255) / 255F, (rgb & 255) / 255F}, p01);
    }

    private static float[] mix(float[] a, float[] b, double k) {
        return new float[] {(float) FlowSpaceMath.lerp(a[0], b[0], k), (float) FlowSpaceMath.lerp(a[1], b[1], k), (float) FlowSpaceMath.lerp(a[2], b[2], k)};
    }

    private static final float[] WHITE = {1F, 1F, 1F};

    /** The deeper tone of the torn top: darker and more saturated than the body (the colour squared, not just dimmed). */
    private static float[] deepSat(float[] c) { return new float[] {c[0] * c[0] * .85F, c[1] * c[1] * .8F, c[2] * c[2] * .8F}; }

    private static float[] deep(float[] c, double v) { return new float[] {(float) (c[0] * v), (float) (c[1] * v), (float) (c[2] * v)}; }

    private static int rgb(float[] c) { return (Math.round(Math.min(1, c[0]) * 255) << 16) | (Math.round(Math.min(1, c[1]) * 255) << 8) | Math.round(Math.min(1, c[2]) * 255); }

    // ------------------------------------------------------------------ events (called by the director)

    /** A node was passed: a ripple runs along the nearest arm outward, the arm brightens, a low ring of dust lies on the floor. */
    static void nodePassed(Vec3 nodeWorld) {
        if (P == null) return;
        double a = Math.atan2(nodeWorld.x - P.centre().x, nodeWorld.z - P.centre().z), best = 1e9;
        int arm = 0;
        double[] p = new double[5];
        for (int i = 0; i < armsActive; i++) {
            FlowSpaceMath.armPoint(FlowSpaceMath.arm(i), .5, rot, vortexRim(P.progress()), p);
            double d = Math.abs(Math.atan2(Math.sin(p[4] - a), Math.cos(p[4] - a)));
            if (d < best) { best = d; arm = i; }
        }
        ripples.add(new double[] {arm, 0});
        armBright[arm] = 0;
    }

    /** The right branch was taken: the vortex gains an arm (or, at the most, the arm flares). */
    static void rightFork() {
        if (armsActive < FlowLook.ARMS_MAX) { armFlare[armsActive] = 0; armsActive++; }
        else armFlare[RNG.nextInt(FlowLook.ARMS_MAX)] = 0;
    }

    /** A mistake: the vortex stalls and shudders once; a strong one (a wrong branch) also tears one cloth sheet into deep-red chips. */
    static void mistake(boolean strong) {
        stallT = 0;
        if (FlowAura.game()) return;   // the aura drops with the score; no flakes
        if (FlowScore.active()) { shedFlakes(strong ? 26 : 16); return; }
        if (!strong || P == null) return;
        int n = Math.max(1, FlowSpaceMath.sheetCount(fireLevel()));
        // the one the camera sees best: its middle nearest to the side of the figure (azimuth 90 or 270), not already tearing
        int k = -1;
        double best = 1e9;
        for (int i = 0; i < n; i++) {
            if (sheetTear[i] < FlowLook.TEAR_TIME + 1.5) continue;
            double a = Math.toRadians(sheet(i, P.clock()).az);
            double d = Math.abs(Math.abs(Math.sin(a)) - 1);
            if (d < best) { best = d; k = i; }
        }
        if (k < 0) return;
        sheetTear[k] = 0;
        Sheet s = sheet(k, 0);
        for (int i = 0; i < FlowLook.TEAR_CHIPS; i++) {
            double a = Math.toRadians(s.az - s.span / 2 + s.span * RNG.nextDouble()), h = .3 + RNG.nextDouble() * s.h * .8, r = s.r0 + FlowLook.SHEET_FLARE * h;
            double ox = Math.sin(a), oz = Math.cos(a);
            chips.add(new double[] {ox * r, h, oz * r, ox * (1.2 + RNG.nextDouble() * 2) + (RNG.nextDouble() - .5), .5 + RNG.nextDouble() * 1.6, oz * (1.2 + RNG.nextDouble() * 2) + (RNG.nextDouble() - .5),
                    0, 1.3 + RNG.nextDouble() * 1.0, .07 + RNG.nextDouble() * .07, RNG.nextDouble(), 0});
        }
    }

    // ------------------------------------------------------------------ the sheets (cloth aura, ref «cultivating it»)

    private record Sheet(double az, double span, double r0, double h, double seed, boolean front, double lean, int tongues) { }

    /**
     * Sheet {@code k}: 0..4 tall curtains behind and beside the figure, 5 and 6 low ones in front of the knees (high pressure only). Left and right differ in
     * count, height, arc, radius and lean on purpose; each drifts round the figure at its own slow speed.
     */
    private static Sheet sheet(int k, double clock) {
        double[] az0 = {184, 126, 238, 84, 292, 22, 338};
        double[] span = {96, 78, 100, 62, 84, 56, 48};
        double[] r0 = {1.35, 1.05, 1.3, .95, 1.1, 1.0, .95};
        double[] hMul = {1.0, .95, .74, .66, .88, 1, 1};
        double[] lean = {.3, .42, .22, .5, .16, .2, .2};
        int[] tongues = {6, 4, 5, 7, 4, 5, 4};
        boolean front = k >= 5;
        double drift = FlowLook.SHEET_DRIFT * (.7 + .25 * k) * (k % 2 == 0 ? 1 : -.6) * (front ? .3 : 1);
        double p = P == null ? 0 : fireLevel(), p01 = P == null ? 0 : P.pressure01();
        double h = front ? FlowLook.SHEET_LOW_H : FlowSpaceMath.sheetHeight(p, p01, FlowScore.active() ? FxTune.f("flow.scroll.fireWeight", .8F) : .55) * hMul[k] * gd(FlowLook.CALM_CURTAIN_H, 1.6) / 1.6;
        double thrown = P != null && P.ending() == 1 ? FlowSpaceMath.thrown(P.te()) : 0;
        double contract = P != null && P.ending() == 0 ? FlowSpaceMath.contraction(P.te()) : 0;
        return new Sheet(az0[k] + drift * clock, span[k], (r0[k] * gd(FlowLook.CALM_CURTAIN_R, 1) * (1 - .5 * contract)) + FlowLook.BURST_RADIUS_GAIN * 1.6 * thrown, h, 3 + k * 7.7, front, lean[k], tongues[k]);
    }

    private static double hash(double x) { double v = Math.sin(x * 127.1 + 311.7) * 43758.5453; return v - Math.floor(v); }

    /** The torn top edge of a curtain: {@code n} rounded tongues of clearly different height; 0..1 along the arc. The body below is whole (no holes). */
    private static double tongues(double seed, int n, double x, double clock) {
        double best = 0;
        for (int i = 0; i < n; i++) {
            double c = (i + .5) / n + (hash(seed + i * 3.1) - .5) * .6 / n + .015 * Math.sin(clock * .21 + i * 1.7 + seed);
            double w = 1.5 * (.6 + .6 * hash(seed + i * 5.3)) / n;
            double f = .3 + .7 * hash(seed + i * 7.9);
            f *= 1 + .05 * Math.sin(clock * .5 + i * 2.1 + seed);
            double u = (x - c) / w;
            best = Math.max(best, f * Math.pow(Math.max(0, 1 - u * u), 1.4));
        }
        return Math.min(1, best);
    }

    /**
     * One curtain bent round the figure: {@code span} degrees of arc at radius {@code r0}, widest in the middle third of its height (the side edges bow in
     * towards the bottom and the top, so there is no straight side), alpha strongest low in the middle and soft at the sides, the top edge torn into
     * {@code n} tongues, the base faded out over the lowest SHEET_BASE_FADE block, a texture that scrolls upward, a pale core low and the deeper tone along the torn top.
     * {@code ring}: a closed ring (the low fire of the intro).
     */
    private static void curtain(VertexConsumer vc, PoseStack.Pose pose, double az, double span, double r0, double h, double seed, int n, double lean, double ga, float[] c, boolean ring, int cols, int rows) {
        double clock = P.clock();
        double la = hash(seed + 4) * 6.283 + clock * (.05 + .03 * hash(seed + 2)), lq = lean * (1 + .25 * Math.sin(clock * .23 + seed));
        double lx = Math.sin(la) * lq, lz = Math.cos(la) * lq;
        Vec3[][] pt = new Vec3[cols + 1][rows + 1];
        double[] topH = new double[cols + 1];
        for (int j = 0; j <= cols; j++) {
            double x = j / (double) cols;
            topH[j] = h * (.32 + .68 * tongues(seed, n, x, clock));
            for (int r = 0; r <= rows; r++) {
                double v = r / (double) rows, y = .05 + topH[j] * v;
                double wv = ring ? 1 : .6 + .4 * Math.sin(Math.PI * Math.min(1, .1 + .8 * v));
                double a = Math.toRadians(ring ? az + 360 * x : az + span / 2 * (2 * x - 1) * wv);
                double rr = r0 + FlowLook.SHEET_FLARE * y + (ring ? 0 : FlowLook.SHEET_BULGE * Math.sin(Math.PI * v));
                pt[j][r] = new Vec3(P.centre().x + Math.sin(a) * rr + lx * y * v, P.floorY() + y, P.centre().z + Math.cos(a) * rr + lz * y * v);
            }
        }
        for (int j = 0; j < cols; j++) {
            for (int r = 0; r < rows; r++) {
                int[] jj = {j, j + 1, j + 1, j}, rr = {r, r, r + 1, r + 1};
                for (int q = 0; q < 4; q++) {
                    double v = rr[q] / (double) rows, x = jj[q] / (double) cols, xn = 2 * x - 1;
                    double y = .05 + topH[jj[q]] * v;
                    double flow = .7 + .3 * Math.sin(2 * Math.PI * (v * 2.0 - clock * .45 + seed + x * 1.7));
                    double side = ring ? 1 : 1 - Math.pow(Math.abs(xn), 2.2);
                    double a = ga * (1 - Math.pow(v, 2.6)) * flow * FlowSpaceMath.smooth(0, FlowLook.SHEET_BASE_FADE, y - .05) * side * (.55 + .45 * Math.sin(Math.PI * Math.min(1, v * .6 + .15)));
                    float[] col = v < .2 ? mix(c, WHITE, .4 * (1 - v / .2)) : (v < .5 ? c : mix(c, deepSat(c), FlowSpaceMath.smooth(.5, .8, v)));
                    Vec3 p = pt[jj[q]][rr[q]];
                    put(vc, pose, p.x, p.y, p.z, col, (float) Math.min(1, Math.max(0, a)));
                }
            }
        }
    }

    private static void drawSheets(PoseStack.Pose pose, Vec3 cam, MultiBufferSource.BufferSource buffers, int tier) {
        int nTall = FlowSpaceMath.sheetCount(fireLevel());
        if (grade >= 0) nTall = Math.min(nTall, FlowLook.CALM_CURTAINS[grade]);
        if (tier >= 2) nTall = Math.min(nTall, 3);
        if (tier >= 3) nTall = Math.min(nTall, 2);
        double lowGrow = FlowSpaceMath.smooth(.5, .8, P.pressure01()) * (tier >= 2 ? 0 : 1);
        float[] c = tone(P.pill(), P.pressure01());
        if (P.ending() == 1) c = mix(c, new float[] {.9F, .18F, .16F}, FlowSpaceMath.smooth(0, FlowLook.BURST_BALL + .6, P.te()));
        double cx = cam.x - P.centre().x, cz = cam.z - P.centre().z, cl = Math.max(1e-6, Math.hypot(cx, cz));
        cx /= cl; cz /= cl;
        List<Integer> order = new ArrayList<>();
        for (int k = 0; k < nTall; k++) order.add(k);
        if (grade < 0) for (int k = 5; k < 5 + FlowLook.LOW_SHEETS; k++) if (lowGrow > .01) order.add(k);
        final double fcx = cx, fcz = cz;
        order.sort(Comparator.comparingDouble(k -> { double a = Math.toRadians(sheet(k, P.clock()).az); return Math.sin(a) * fcx + Math.cos(a) * fcz; }));
        var vc = buffers.getBuffer(MurimRenderTypes.flowSolid());
        int cols = tier >= 2 ? 20 : 30, rows = tier >= 2 ? 6 : 9;
        for (int k : order) {
            Sheet s = sheet(k, P.clock());
            if (sheetBorn[k] < 0) sheetBorn[k] = P.clock();
            double grow = FlowSpaceMath.smooth(0, FlowLook.SHEET_GROW, P.clock() - sheetBorn[k]) * (s.front ? lowGrow : 1);
            double t = sheetTear[k];
            double alive = t > 90 ? 1 : (t < FlowLook.TEAR_TIME ? 1 - FlowSpaceMath.smooth(0, FlowLook.TEAR_TIME, t) : FlowSpaceMath.smooth(FlowLook.TEAR_TIME + .5, FlowLook.TEAR_TIME + .5 + FlowLook.SHEET_GROW, t));
            double life = 1;
            if (P.ending() == 0) life = .35 + .65 * (1 - FlowSpaceMath.smooth(FlowSpaceMath.liftAt() + 2.4, FlowSpaceMath.liftAt() + 3.8, P.te())) * (1 - .0);
            if (P.ending() == 1) life = 1 - FlowSpaceMath.smooth(.25, 1, FlowSpaceMath.thrown(P.te()));
            if (P.ending() == 2) life = 1 - FlowSpaceMath.smooth(0, FlowLook.END_OTHER - .2, P.te());
            double g = grow * alive * life * sheetEnv();
            double span = s.span, r0 = s.r0, hh = s.h * grow;
            if (P.ending() == 0) {
                // the burst blows the curtains outward and upward while the figure hangs in the air
                double bl = FlowSpaceMath.smooth(FlowSpaceMath.burstAt(), FlowSpaceMath.burstAt() + 1.2, P.te());
                r0 *= 1 + .7 * bl; hh *= 1 + .35 * bl; span *= 1 + .25 * bl;
            }
            if (g < .01) continue;
            double mid = Math.toRadians(s.az);
            double dist = Math.hypot(P.centre().x + Math.sin(mid) * s.r0 - cam.x, P.centre().z + Math.cos(mid) * s.r0 - cam.z);
            float nearK = (float) FlowSpaceMath.smooth(FlowLook.SHEET_NEAR_FROM, FlowLook.SHEET_NEAR_TO, dist);
            double ca = FlowLook.SHEET_ALPHA * g * nearK * gd(FlowLook.CALM_CURTAIN_ALPHA, 1);
            curtain(vc, pose, s.az, span, r0, hh, s.seed, s.tongues, s.lean, ca, c, false, cols, rows);
            // the calm fire is denser: a second, smaller curtain with tongues of its own stands inside the first
            if (grade >= 0) curtain(vc, pose, s.az + 9, span * .85, r0 * .84, hh * .82, s.seed + 5.5, Math.max(3, s.tongues - 1), s.lean * .7, ca * .7, c, false, cols, rows);
        }
        buffers.endBatch(MurimRenderTypes.flowSolid());
    }

    // ------------------------------------------------------------------ the vortex

    private static void put(VertexConsumer vc, PoseStack.Pose pose, double x, double y, double z, float[] c, float a) {
        vc.addVertex(pose, (float) x, (float) y, (float) z).setColor(Math.round(Math.min(1, c[0]) * 255), Math.round(Math.min(1, c[1]) * 255),
                Math.round(Math.min(1, c[2]) * 255), Math.round(Math.max(0, Math.min(1, a)) * 255)).setUv(0, 0).setUv1(0, 0).setUv2(0, 0).setNormal(0, 0, 1);
    }

    /** The cross profile of an arm (-1 the trailing soft edge .. 1 the leading crisp edge): alpha, white share, deep-tone share. */
    private static final double[] CROSS_X = {-1, -.7, -.3, .2, .62, .84, .96, 1.0};
    private static final float[] CROSS_A = {0, .35F, .6F, .8F, .95F, 1F, .95F, 0};
    private static final float[] CROSS_W = {0, 0, 0, .05F, .15F, .8F, .9F, .9F};
    private static final float[] CROSS_DEEP = {1F, .7F, .35F, 0, 0, 0, 0, 0};

    private static double nearFade(double x, double y, double z, Vec3 cam) {
        double d = Math.sqrt((x - cam.x) * (x - cam.x) + (y - cam.y) * (y - cam.y) + (z - cam.z) * (z - cam.z));
        return FlowSpaceMath.smooth(FlowLook.NEAR_FADE_FROM, FlowLook.NEAR_FADE_TO, d);
    }

    private static double floorFade(double yAboveFloor) { return FlowSpaceMath.smooth(FlowLook.FLOOR_FADE_FROM, FlowLook.FLOOR_FADE_TO, yAboveFloor); }

    private static double rimRadius() {
        double r = vortexRim(P.progress()) * FlowSpaceMath.shudder(stallT);
        if (P.ending() == 0) r = FlowSpaceMath.lerp(r, FlowLook.VORTEX_RIN + .1, FlowSpaceMath.contraction(P.te()));
        if (P.ending() == 1) r *= 1 + (FlowLook.BURST_RADIUS_GAIN - 1) * FlowSpaceMath.thrown(P.te());
        return r;
    }

    private static double armEnvelope() {
        double e = env();
        if (P.ending() == 0) e *= 1 - FlowSpaceMath.smooth(FlowLook.FIN_CONTRACT - .25, FlowLook.FIN_CONTRACT + .25, P.te());
        if (P.ending() == 1) e *= 1 - FlowSpaceMath.smooth(.35, 1, FlowSpaceMath.thrown(P.te()));
        if (P.ending() == 2) e *= 1 - FlowSpaceMath.smooth(0, FlowLook.END_OTHER - .2, P.te());
        return e;
    }

    /** A soft round disc (gaussian-ish fall-off) in the plane of {@code e1, e2}: centre colour and alpha to an edge colour at alpha 0. */
    private static void softDisc(VertexConsumer vc, PoseStack.Pose pose, Vec3 c, Vec3 e1, Vec3 e2, double r, float[] inner, float[] outer, float alpha, int segs) {
        if (alpha < .004F || r <= 0) return;
        double[] rad = {0, .25, .5, .75, 1};
        float[] pa = {1F, .78F, .4F, .12F, 0F};
        for (int k = 0; k < 4; k++) {
            for (int s = 0; s < segs; s++) {
                double a0 = 2 * Math.PI * s / segs, a1 = 2 * Math.PI * (s + 1) / segs;
                Vec3 d0 = e1.scale(Math.cos(a0)).add(e2.scale(Math.sin(a0))), d1 = e1.scale(Math.cos(a1)).add(e2.scale(Math.sin(a1)));
                float[] c0 = mix(inner, outer, rad[k]), c1 = mix(inner, outer, rad[k + 1]);
                Vec3 p0 = c.add(d0.scale(r * rad[k])), p1 = c.add(d0.scale(r * rad[k + 1])), p2 = c.add(d1.scale(r * rad[k + 1])), p3 = c.add(d1.scale(r * rad[k]));
                put(vc, pose, p0.x, p0.y, p0.z, c0, alpha * pa[k]);
                put(vc, pose, p1.x, p1.y, p1.z, c1, alpha * pa[k + 1]);
                put(vc, pose, p2.x, p2.y, p2.z, c1, alpha * pa[k + 1]);
                put(vc, pose, p3.x, p3.y, p3.z, c0, alpha * pa[k]);
            }
        }
    }

    /** The soft halo of the pill colour behind torso and head (radius 0.9 -> 1.4 block): in the scene's depth, pushed back from the camera so the model stands in front of it. */
    private static void drawHalo(PoseStack.Pose pose, Vec3 cam, MultiBufferSource.BufferSource buffers) {
        double env = env();
        if (P.ending() == 0) env *= 1 - FlowSpaceMath.smooth(FlowLook.FIN_CONTRACT - .3, FlowLook.FIN_CONTRACT + .6, P.te());
        if (P.ending() == 1) env *= 1 - FlowSpaceMath.smooth(.3, 1.2, P.te());
        if (P.ending() == 2) env *= 1 - FlowSpaceMath.smooth(0, FlowLook.END_OTHER - .2, P.te());
        if (env < .01) return;
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        var left = camera.getLeftVector();
        var look = camera.getLookVector();
        var up = camera.getUpVector();
        Vec3 cr = new Vec3(-left.x(), -left.y(), -left.z()), cu = new Vec3(up.x(), up.y(), up.z()), f = new Vec3(look.x(), look.y(), look.z());
        float[] c = tone(P.pill(), P.pressure01());
        double r = FlowSpaceMath.lerp(FlowLook.HALO_R0, FlowLook.HALO_R1, FlowSpaceMath.smooth(0, 1, P.progress()));
        Vec3 at = new Vec3(P.centre().x, P.floorY() + FlowLook.HALO_Y, P.centre().z);
        Vec3 away = new Vec3(f.x, 0, f.z).normalize();
        at = at.add(away.scale(FlowLook.HALO_BACK));
        var vc = buffers.getBuffer(MurimRenderTypes.flowGlow());
        softDisc(vc, pose, at, cr, cu, r, mix(c, WHITE, .12), deep(c, .6), (float) (FlowLook.HALO_ALPHA * env * (.7 + .3 * FlowSpaceMath.smooth(0, 1, P.progress()))), 36);
        buffers.endBatch(MurimRenderTypes.flowGlow());
    }

    private static void drawArms(PoseStack.Pose pose, Vec3 cam, MultiBufferSource.BufferSource buffers, int tier) {
        double env = armEnvelope();
        if (env < .01) return;
        float[] c = tone(P.pill(), P.pressure01());
        if (P.ending() == 1) c = mix(c, new float[] {.9F, .18F, .16F}, FlowSpaceMath.smooth(0, FlowLook.BURST_BALL + .6, P.te()));
        float[] deepC = deep(c, .42), hot = mix(c, WHITE, .75);
        double rim = rimRadius();
        double contract = P.ending() == 0 ? FlowSpaceMath.contraction(P.te()) : 0, thrown = P.ending() == 1 ? FlowSpaceMath.thrown(P.te()) : 0;
        double wScale = (.85 + .15 * FlowSpaceMath.smooth(0, 1, P.progress())) * (1 - .6 * contract) * (1 + .5 * thrown);
        double aBase = FlowSpaceMath.armAlpha(P.progress()) * (1 + .6 * contract) * gd(FlowLook.CALM_ARM_ALPHA, 1);
        int k = FlowLook.ARM_SAMPLES[Math.min(tier, 3)];
        var type = MurimRenderTypes.flowGlow();
        VertexConsumer vc = buffers.getBuffer(type);
        // the white-hot core on the floor under the lap: the vortex converges on it
        {
            double kk = FlowSpaceMath.smooth(0, 1, P.progress());
            double cr = FlowLook.CORE_R0 + (FlowLook.CORE_R1 - FlowLook.CORE_R0) * kk;
            Vec3 cc = new Vec3(P.centre().x, P.floorY() + .04, P.centre().z);
            softDisc(vc, pose, cc, new Vec3(1, 0, 0), new Vec3(0, 0, 1), cr, mix(c, WHITE, .85), c, (float) (FlowLook.CORE_ALPHA * env), 28);
        }
        // a soft pill-colour light on the floor over the whole vortex (the floor between the arms is lit, never darker than outside)
        {
            Vec3 cc = new Vec3(P.centre().x, P.floorY() + .035, P.centre().z);
            softDisc(vc, pose, cc, new Vec3(1, 0, 0), new Vec3(0, 0, 1), Math.max(1.2, rim * FlowLook.WASH_SHARE), c, c, (float) (FlowLook.WASH_ALPHA * env * FlowSpaceMath.smooth(0, 1, P.intro())), 36);
        }
        double[] pt = new double[5];
        // the arms unroll from the fire ring on the floor during the intro: each shows only the part it has grown so far
        double unroll = P.ending() < 0 ? FlowSpaceMath.smooth(.2, .95, P.intro()) : 1;
        double[][] pos = new double[k][3], sd = new double[k][3];
        double[] al = new double[k], wd = new double[k], rad = new double[k];
        for (int i = 0; i < armsActive; i++) {
            double grow = armGrow[i];
            if (grow < .01) continue;
            var a = FlowSpaceMath.arm(i);
            double flare = armFlare[i] < FlowLook.FORK_FLARE ? Math.sin(Math.PI * armFlare[i] / FlowLook.FORK_FLARE) : 0;
            double br = armBright[i] < 5 ? FlowSpaceMath.armBrighten(armBright[i]) : 0;
            double lam = FlowLook.FLOW_WAVELENGTH * (.8 + .5 * ((i * .37) % 1)), arcLen = 0;
            double head = -1;
            for (double[] rp : ripples) if ((int) rp[0] == i) head = FlowSpaceMath.rippleHead(rp[1]);
            for (int j = 0; j < k; j++) {
                double u = a.u0() + (a.u1() - a.u0()) * unroll * j / (k - 1);
                FlowSpaceMath.armPoint(a, u, rot, rim, pt);
                pos[j][0] = P.centre().x + pt[0]; pos[j][1] = P.floorY() + pt[1]; pos[j][2] = P.centre().z + pt[2];
                rad[j] = pt[3];
                if (j > 0) arcLen += Math.sqrt(Math.pow(pos[j][0] - pos[j - 1][0], 2) + Math.pow(pos[j][2] - pos[j - 1][2], 2));
                double w = FlowSpaceMath.armWidth(a, u, wScale * gd(FlowLook.CALM_WIDTH, 1) * (1 + .3 * flare + .25 * br)) * grow;
                wd[j] = w;
                double flow = 1 - FlowLook.FLOW_DEPTH + FlowLook.FLOW_DEPTH * (.5 + .5 * Math.sin(2 * Math.PI * (arcLen / lam + P.clock() * FlowLook.FLOW_SPEED / lam + i * .31)));
                double rp = head < 0 ? 0 : FlowLook.RIPPLE_GAIN * Math.exp(-Math.pow((u - head) / FlowLook.RIPPLE_SIGMA, 2));
                al[j] = aBase * env * grow * flow * (1 + rp + .35 * br + .3 * flare) * FlowSpaceMath.smooth(FlowLook.VORTEX_RIN, FlowLook.VORTEX_RIN + FlowLook.ARM_BODY_FADE, rad[j]) * (unroll < .999 ? FlowSpaceMath.smooth(1, .8, j / (double) (k - 1)) : 1);
            }
            // the ribbon lies flat on the floor: its width is the horizontal normal of the arm's own line
            for (int j = 0; j < k; j++) {
                int j0 = Math.max(0, j - 1), j1 = Math.min(k - 1, j + 1);
                double tx = pos[j1][0] - pos[j0][0], tz = pos[j1][2] - pos[j0][2], tl = Math.max(1e-9, Math.hypot(tx, tz));
                sd[j][0] = -tz / tl * wd[j] / 2; sd[j][1] = 0; sd[j][2] = tx / tl * wd[j] / 2;
            }
            double edgeGain = FlowLook.ARM_EDGE_ALPHA / .95;
            for (int j = 0; j < k - 1; j++) {
                if (wd[j] < 1e-4 && wd[j + 1] < 1e-4) continue;
                for (int cI = 0; cI < CROSS_X.length - 1; cI++) {
                    int[] jj = {j, j + 1, j + 1, j};
                    int[] cc = {cI, cI, cI + 1, cI + 1};
                    for (int q = 0; q < 4; q++) {
                        int jq = jj[q], cq = cc[q];
                        double px = pos[jq][0] + sd[jq][0] * CROSS_X[cq], py = pos[jq][1], pz = pos[jq][2] + sd[jq][2] * CROSS_X[cq];
                        double alpha = al[jq] * CROSS_A[cq] * (cq >= 5 ? edgeGain : 1) * floorFade(py - P.floorY()) * nearFade(px, py, pz, cam);
                        float wh = CROSS_W[cq] + (float) (.25 * br);
                        float[] col = mix(mix(c, deepC, CROSS_DEEP[cq]), hot, Math.min(1, wh));
                        put(vc, pose, px, py, pz, col, (float) Math.min(1, alpha));
                    }
                }
            }
        }
        buffers.endBatch(type);
        drawDots(pose, cam, buffers, tier, c, rim, wScale, env);
    }

    /** A round dot of one of three sizes: a bright core and a soft edge (12 segments, two rings). */
    private static void softDot(VertexConsumer vc, PoseStack.Pose pose, Vec3 p, Vec3 ex, Vec3 ey, float[] core, float[] col, float al) {
        int n = 12;
        Vec3[] in = new Vec3[n + 1], out = new Vec3[n + 1];
        for (int q = 0; q <= n; q++) {
            double an = q * 2 * Math.PI / n;
            Vec3 d = ex.scale(Math.cos(an)).add(ey.scale(Math.sin(an)));
            in[q] = p.add(d.scale(.55));
            out[q] = p.add(d);
        }
        for (int q = 0; q < n; q++) {
            put(vc, pose, p.x, p.y, p.z, core, al); put(vc, pose, in[q].x, in[q].y, in[q].z, core, al * .85F); put(vc, pose, in[q + 1].x, in[q + 1].y, in[q + 1].z, core, al * .85F); put(vc, pose, p.x, p.y, p.z, core, al);
            put(vc, pose, in[q].x, in[q].y, in[q].z, core, al * .85F); put(vc, pose, out[q].x, out[q].y, out[q].z, col, 0); put(vc, pose, out[q + 1].x, out[q + 1].y, out[q + 1].z, col, 0); put(vc, pose, in[q + 1].x, in[q + 1].y, in[q + 1].z, core, al * .85F);
        }
    }

    /** Dots of three sizes (1 : 2 : 4) riding the arms inward and shrinking into the body (stateless: a dot's place is a function of the clock). */
    private static void drawDots(PoseStack.Pose pose, Vec3 cam, MultiBufferSource.BufferSource buffers, int tier, float[] c, double rim, double wScale, double env) {
        int n = (int) (FlowLook.DOT_COUNT[Math.min(tier, 3)] * gd(FlowLook.CALM_DOTS, 1));
        double speedGain = 1 + (P.ending() == 0 ? 3 * FlowSpaceMath.contraction(P.te()) : 0);
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        var left = camera.getLeftVector();
        Vec3 cr = new Vec3(-left.x(), -left.y(), -left.z()), cu = new Vec3(camera.getUpVector());
        var type = MurimRenderTypes.flowGlow();
        VertexConsumer vc = buffers.getBuffer(type);
        double[] pt = new double[5], pt2 = new double[5];
        for (int d = 0; d < n; d++) {
            int arm = d % armsActive;
            if (armGrow[arm] < .05) continue;
            var a = FlowSpaceMath.arm(arm);
            int cls = d % 7 < 4 ? 0 : (d % 7 < 6 ? 1 : 2);
            double ph = (d * .61803 + .17) % 1, sp = FlowLook.DOT_SPEED_MIN + (FlowLook.DOT_SPEED_MAX - FlowLook.DOT_SPEED_MIN) * ((d * .3183) % 1);
            double x = 1 - ((ph + P.clock() * sp * speedGain) % 1);
            double u = a.u0() + (a.u1() - a.u0()) * x;
            FlowSpaceMath.armPoint(a, u, rot, rim, pt);
            FlowSpaceMath.armPoint(a, Math.min(1, u + .02), rot, rim, pt2);
            double tx = pt2[0] - pt[0], tz = pt2[2] - pt[2], tl = Math.max(1e-9, Math.hypot(tx, tz));
            double lat = (((d * .7548) % 1) - .5) * 1.5, w = FlowSpaceMath.armWidth(a, u, wScale * gd(FlowLook.CALM_WIDTH, 1));
            double px = P.centre().x + pt[0] - tz / tl * lat * w / 2, py = P.floorY() + pt[1] + .05 + .12 * ((d * .41) % 1), pz = P.centre().z + pt[2] + tx / tl * lat * w / 2;
            double env2 = FlowSpaceMath.smooth(0, .12, x) * (1 - FlowSpaceMath.smooth(.9, 1, x)) * armGrow[arm] * FlowSpaceMath.smooth(FlowLook.VORTEX_RIN, FlowLook.VORTEX_RIN + FlowLook.ARM_BODY_FADE, pt[3]);
            double al = FlowLook.DOT_ALPHA * env * env2 * nearFade(px, py, pz, cam);
            if (al < .02) continue;
            double s = FlowLook.DOT_SIZE[cls] * (.35 + .65 * u);
            float[] col = mix(c, WHITE, cls == 0 ? .35 : (cls == 1 ? .2 : .08));
            float[] core = mix(col, WHITE, .55);
            softDot(vc, pose, new Vec3(px, py, pz), cr.scale(s), cu.scale(s), core, col, (float) al);
        }
        buffers.endBatch(type);
    }

    // ------------------------------------------------------------------ embers, chips, dust

    private static void drawEmbers(PoseStack.Pose pose, MultiBufferSource.BufferSource buffers, double env) {
        if (embers == null || env < .01) return;
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        var left = camera.getLeftVector();
        Vec3 cr = new Vec3(-left.x(), -left.y(), -left.z()), cu = new Vec3(camera.getUpVector());
        var type = MurimRenderTypes.flowGlow();
        VertexConsumer vc = buffers.getBuffer(type);
        for (var m : embers.motes) {
            double e = m.envelope() * env;
            if (e <= .01) continue;
            double px = P.centre().x + m.x, py = P.floorY() + m.y, pz = P.centre().z + m.z;
            float[] col = {((m.rgb >> 16) & 255) / 255F, ((m.rgb >> 8) & 255) / 255F, (m.rgb & 255) / 255F};
            float[] core = mix(col, WHITE, m.bright * .7);
            double s = m.size * (m.spark ? .9 : 1.0);
            Vec3 p = new Vec3(px, py, pz);
            if (m.spark) {
                Vec3 v = new Vec3(m.vx, m.vy, m.vz);
                double sx = v.dot(cr), sy = v.dot(cu), sl = Math.hypot(sx, sy);
                if (sl < 1e-6) { sx = 0; sy = 1; sl = 1; }
                Vec3 ax = cr.scale(sx / sl).add(cu.scale(sy / sl)), sd = cr.scale(-sy / sl).add(cu.scale(sx / sl));
                double len = Math.min(.3, .06 + .05 * sl), w = Math.max(s * 1.4, .02);
                Vec3 tail = p.subtract(ax.scale(len)), hl = p.add(sd.scale(w)), hr = p.subtract(sd.scale(w));
                put(vc, pose, hl.x, hl.y, hl.z, col, 0); put(vc, pose, p.x, p.y, p.z, core, (float) e); put(vc, pose, tail.x, tail.y, tail.z, col, 0); put(vc, pose, tail.x, tail.y, tail.z, col, 0);
                put(vc, pose, p.x, p.y, p.z, core, (float) e); put(vc, pose, hr.x, hr.y, hr.z, col, 0); put(vc, pose, tail.x, tail.y, tail.z, col, 0); put(vc, pose, tail.x, tail.y, tail.z, col, 0);
            } else {
                Vec3 ex = cr.scale(s), ey = cu.scale(s);
                Vec3[] ring = new Vec3[7];
                for (int q = 0; q <= 6; q++) { double an = q * Math.PI / 3 + .4; ring[q] = p.add(ex.scale(Math.cos(an))).add(ey.scale(Math.sin(an))); }
                for (int q = 0; q < 6; q++) {
                    put(vc, pose, p.x, p.y, p.z, core, (float) (e * .8));
                    put(vc, pose, ring[q].x, ring[q].y, ring[q].z, col, 0);
                    put(vc, pose, ring[q + 1].x, ring[q + 1].y, ring[q + 1].z, col, 0);
                    put(vc, pose, ring[q + 1].x, ring[q + 1].y, ring[q + 1].z, col, 0);
                }
            }
        }
        buffers.endBatch(type);
    }

    private static void drawDustAndChips(PoseStack.Pose pose, MultiBufferSource.BufferSource buffers) {
        if (!FxDust.available()) return;
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        var left = camera.getLeftVector();
        Vec3 cr = new Vec3(-left.x(), -left.y(), -left.z()), cu = new Vec3(camera.getUpVector());
        FxDust.Frame fr = new FxDust.Frame(new Vec3(P.centre().x, P.floorY(), P.centre().z), new Vec3(1, 0, 0), new Vec3(0, 0, 1), cr, cu);
        List<FxDustSim.Puff> dust = new ArrayList<>(), red = new ArrayList<>(), ash = new ArrayList<>();
        for (double[] ch : chips) {
            double k = ch[6] / ch[7];
            double alpha = Math.min(1, ch[6] / .2) * (1 - FlowSpaceMath.smooth(.7, 1, k)) * P.fade();
            var puff = new FxDustSim.Puff(ch[0], ch[1], ch[2], ch[3], ch[4], ch[5], ch[8], ch[9], Math.clamp((k - .5) / .5, 0, 1), ch[9] * 6.28 + ch[6] * 2, FxDustSim.CHIP, 1.3, 0, alpha, 1, Double.NaN, 1);
            (ch[10] > .5 ? ash : red).add(puff);
        }
        // the puffs are in the sim frame (x right, y up, z near); the ring puffs were built with the centre offset already removed
        FxDust.draw(FxDust.WAKE_NIGHT, dust, fr, pose, buffers, P.clock());
        FxDust.draw(RED_CHIP, red, fr, pose, buffers, P.clock());
        FxDust.draw(ASH, ash, fr, pose, buffers, P.clock());
    }

    // ------------------------------------------------------------------ intro (the pill dissolves into the body) and the essence of the finale

    private static Vec3[] camVecs() {
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        var left = camera.getLeftVector();
        var up = camera.getUpVector();
        return new Vec3[] {new Vec3(-left.x(), -left.y(), -left.z()), new Vec3(up.x(), up.y(), up.z())};
    }

    /** Essence of the finale: motes and short shards streaming out of the black core, then settling (or rising round the lifting figure). */
    private static void drawMotes(PoseStack.Pose pose, MultiBufferSource.BufferSource buffers) {
        if (motes.isEmpty()) return;
        Vec3[] cv = camVecs();
        Vec3 cr = cv[0], cu = cv[1];
        float[] c = tone(P.pill(), 0);
        var vc = buffers.getBuffer(MurimRenderTypes.flowGlow());
        for (double[] m : motes) {
            double k = m[6] / m[7], e = FlowSpaceMath.smooth(0, .08, m[6]) * (1 - FlowSpaceMath.smooth(.55, 1, k)) * .95;
            if (e < .02) continue;
            Vec3 p = new Vec3(m[0], m[1], m[2]);
            float[] col = mix(c, WHITE, .35 + .3 * (1 - k)), core = mix(col, WHITE, .6);
            double speed = Math.sqrt(m[3] * m[3] + m[4] * m[4] + m[5] * m[5]);
            if (m[9] >= 2) {
                // a pale petal-like flake, slowly turning
                double an = m[9] * 9 + m[6] * 1.3, sx = m[8] * 1.9, sy = m[8] * .7;
                Vec3 e1 = cr.scale(Math.cos(an) * sx).add(cu.scale(Math.sin(an) * sx)), e2 = cr.scale(-Math.sin(an) * sy).add(cu.scale(Math.cos(an) * sy));
                softDisc(vc, pose, p, e1, e2, 1, mix(col, WHITE, .5), col, (float) (e * .75), 12);
            } else if (speed > 1.4) {
                // a shard: a short diamond along the way it flies
                Vec3 v = new Vec3(m[3], m[4], m[5]);
                double sx = v.dot(cr), sy = v.dot(cu), sl = Math.hypot(sx, sy);
                if (sl < 1e-6) { sx = 0; sy = 1; sl = 1; }
                Vec3 ax = cr.scale(sx / sl).add(cu.scale(sy / sl)), sd = cr.scale(-sy / sl).add(cu.scale(sx / sl));
                double len = Math.min(.4, .05 + .05 * speed) , w = Math.max(m[8] * 1.2, .016);
                Vec3 tail = p.subtract(ax.scale(len)), hl = p.add(sd.scale(w)), hr = p.subtract(sd.scale(w));
                put(vc, pose, hl.x, hl.y, hl.z, col, (float) (e * .4)); put(vc, pose, p.x, p.y, p.z, core, (float) e); put(vc, pose, hr.x, hr.y, hr.z, col, (float) (e * .4)); put(vc, pose, tail.x, tail.y, tail.z, col, 0);
            } else {
                softDot(vc, pose, p, cr.scale(m[8]), cu.scale(m[8]), core, col, (float) e);
            }
        }
        buffers.endBatch(MurimRenderTypes.flowGlow());
    }

    // ------------------------------------------------------------------ finale rays and the pale brightening

    private static double easeOut(double k) { k = FlowSpaceMath.clamp01(k); return 1 - (1 - k) * (1 - k); }

    // ------------------------------------------------------------------ intro: the pill melts INSIDE (refs 1, 1.5, 2)

    /** The warm light born in the belly under the hands: it grows over INTRO_DISSOLVE s and spreads up the torso as a soft inner light. Nothing flies through the air. */
    private static void drawIntro(PoseStack.Pose pose, Vec3 cam, MultiBufferSource.BufferSource buffers) {
        if (P.ending() >= 0 || P.core() == null) return;
        double t = P.clock() - FlowLook.INTRO_LEAD, endT = FlowLook.INTRO_DISSOLVE + FlowLook.INTRO_FLAME;
        if (t < 0 || t > endT + 1.0) return;
        Vec3[] cv = camVecs();
        Vec3 cr = cv[0], cu = cv[1];
        float[] c = tone(P.pill(), 0), core = mix(c, WHITE, .7), warm = mix(c, WHITE, .2);
        Vec3 toCam = cam.subtract(P.core()).normalize();
        Vec3 d = P.core().add(0, .12, 0).add(toCam.scale(.12));
        double g = FlowSpaceMath.smooth(0, FlowLook.INTRO_DISSOLVE, t) * (1 - FlowSpaceMath.smooth(endT, endT + 1.0, t));
        var vc = buffers.getBuffer(MurimRenderTypes.flowGlow());
        softDisc(vc, pose, d, cr, cu, FlowLook.BELLY_R * (.35 + .65 * g), core, warm, (float) (.8 * g), 28);
        // it climbs: a soft glow along the torso whose top edge rises from the belly to the chest
        double climb = FlowSpaceMath.smooth(.25, FlowLook.INTRO_DISSOLVE + .5, t);
        Vec3 at = d.add(0, FlowLook.BELLY_CLIMB * climb * .8, 0);
        softDisc(vc, pose, at, cr.scale(.2 + .05 * climb), cu.scale(.1 + .4 * climb), 1, warm, c, (float) (.5 * g), 24);
        buffers.endBatch(MurimRenderTypes.flowGlow());
    }

    // ------------------------------------------------------------------ dark flakes (refs «cultivating it», 3): small near-black chips drifting up through the aura

    private static void drawFlakes(PoseStack.Pose pose, Vec3 cam, MultiBufferSource.BufferSource buffers, int tier) {
        double env;
        if (P.ending() < 0) env = FlowSpaceMath.smooth(.4, 1.4, P.clock() - FlowLook.INTRO_LEAD);
        else env = P.fade() * (P.ending() == 0 ? 1 : (1 - FlowSpaceMath.smooth(0, FlowLook.END_OTHER, P.te())));
        if (env < .02) return;
        Vec3[] cv = camVecs();
        Vec3 cr = cv[0], cu = cv[1];
        float[] c = tone(P.pill(), P.pressure01()), dark = deep(c, .12);
        int n = (int) (FlowLook.FLAKES * gd(FlowLook.CALM_FLAKES, 1) * (tier >= 3 ? .35 : (tier >= 2 ? .55 : (tier >= 1 ? .8 : 1))));
        var vc = buffers.getBuffer(MurimRenderTypes.flowSolid());
        double clock = P.clock();
        for (int i = 0; i < n; i++) {
            double h1 = hash(i * 1.37), h2 = hash(i * 2.71), h3 = hash(i * 3.13), h4 = hash(i * 4.19), h5 = hash(i * 5.23), h6 = hash(i * 6.29), h7 = hash(i * 7.31), h8 = hash(i * 8.37);
            double life = FlowLook.FLAKE_LIFE * (.7 + .6 * h1), u = (clock / life + h2) % 1;
            double rad = .35 + 1.5 * h4 * (.55 + .45 * u), ang = h3 * 6.2832 + clock * (.1 + .1 * h5) * (h6 < .5 ? 1 : -1) + u * 1.4;
            double lift = P.ending() == 0 ? ClientPillState.lift(0F) : 0;
            double y = P.floorY() + .1 + u * 2.6 * (.7 + .6 * h7) + lift * .8 * u;
            double size = (FlowLook.FLAKE_MIN + (FlowLook.FLAKE_MAX - FlowLook.FLAKE_MIN) * h8) * gd(FlowLook.CALM_FLAKE_SIZE, 1);
            double al = .92 * env * FlowSpaceMath.smooth(0, .12, u) * (1 - FlowSpaceMath.smooth(.78, 1, u));
            if (al < .03) continue;
            Vec3 p = new Vec3(P.centre().x + Math.sin(ang) * rad, y, P.centre().z + Math.cos(ang) * rad);
            if (nearFade(p.x, p.y, p.z, cam) < .5) continue;
            double rot = clock * (.6 + h5) * (h6 < .5 ? 1 : -1) + h8 * 6, squash = .45 + .55 * Math.abs(Math.cos(clock * (.5 + h1) + h2 * 6));
            // an irregular soft flake: seven corners, a dark heart that fades to a soft edge (never a hard square)
            int nv = 7;
            Vec3[] ring = new Vec3[nv + 1];
            for (int j = 0; j < nv; j++) {
                double an = rot + j * 6.2832 / nv, rr = size * (.5 + .8 * hash(i * 11.0 + j));
                ring[j] = p.add(cr.scale(Math.cos(an) * rr * squash)).add(cu.scale(Math.sin(an) * rr));
            }
            ring[nv] = ring[0];
            for (int j = 0; j < nv; j++) {
                put(vc, pose, p.x, p.y, p.z, dark, (float) al);
                put(vc, pose, ring[j].x, ring[j].y, ring[j].z, dark, (float) (al * .3));
                put(vc, pose, ring[j + 1].x, ring[j + 1].y, ring[j + 1].z, dark, (float) (al * .3));
                put(vc, pose, p.x, p.y, p.z, dark, (float) al);
            }
        }
        buffers.endBatch(MurimRenderTypes.flowSolid());
    }

    // ------------------------------------------------------------------ the burst in the air (ref 6): broad soft beams, a huge bloom, floating motes and petals, a pool of light on the floor

    private static void drawRays(PoseStack.Pose pose, Vec3 cam, MultiBufferSource.BufferSource buffers, int tier) {
        if (rays.isEmpty() || P.core() == null) return;
        double t = P.te() - FlowSpaceMath.burstAt();
        if (t < 0) return;
        float[] c = tone(P.pill(), P.pressure01()), pale = mix(c, WHITE, .55), hot = mix(c, WHITE, .9);
        var vc = buffers.getBuffer(MurimRenderTypes.flowGlow());
        int stride = tier >= 2 ? 2 : 1;
        double grow = .45, hold = .8, fade = 1.0, life = grow + hold + fade;
        Vec3 toCam = cam.subtract(P.core()).normalize();
        for (int i = 0; i < rays.size(); i += stride) {
            double[] r = rays.get(i);
            double a = t - r[6], len = r[3];
            if (a < 0 || a > life) continue;
            boolean thin = r[8] > .5;
            double head = len * easeOut(a / grow);
            double al = FlowLook.RAY_ALPHA * (thin ? .8 : .5) * FlowSpaceMath.smooth(0, .12, a) * (1 - FlowSpaceMath.smooth(grow + hold, life, a));
            Vec3 dir = new Vec3(r[0], r[1], r[2]);
            al *= 1 - FlowSpaceMath.smooth(.5, .8, dir.dot(toCam));
            if (al < .01 || head < .1) continue;
            Vec3 base = P.core().add(dir.scale(FlowLook.RAY_START));
            Vec3 mid = base.add(dir.scale(head / 2));
            Vec3 side = dir.cross(cam.subtract(mid).normalize());
            if (side.lengthSqr() < 1e-8) continue;
            side = side.normalize();
            int n = 4;
            for (int q = 0; q < n; q++) {
                double x0 = q / (double) n, x1 = (q + 1) / (double) n;
                // narrow at the dantian, widest at the far end; soft edges (alpha 0 at both sides), pale at the far end
                double w0 = r[4] * (.08 + .92 * Math.pow(x0, .8)) / 2, w1 = r[4] * (.08 + .92 * Math.pow(x1, .8)) / 2;
                Vec3 p0 = base.add(dir.scale(head * x0)), p1 = base.add(dir.scale(head * x1));
                float a0 = (float) (al * (1 - .55 * x0)), a1 = (float) (al * (1 - .55 * x1));
                float[] c0 = mix(hot, pale, x0), c1 = mix(hot, pale, x1);
                put(vc, pose, p0.x + side.x * w0, p0.y + side.y * w0, p0.z + side.z * w0, c0, 0); put(vc, pose, p0.x, p0.y, p0.z, c0, a0);
                put(vc, pose, p1.x, p1.y, p1.z, c1, a1); put(vc, pose, p1.x + side.x * w1, p1.y + side.y * w1, p1.z + side.z * w1, c1, 0);
                put(vc, pose, p0.x, p0.y, p0.z, c0, a0); put(vc, pose, p0.x - side.x * w0, p0.y - side.y * w0, p0.z - side.z * w0, c0, 0);
                put(vc, pose, p1.x - side.x * w1, p1.y - side.y * w1, p1.z - side.z * w1, c1, 0); put(vc, pose, p1.x, p1.y, p1.z, c1, a1);
            }
        }
        buffers.endBatch(MurimRenderTypes.flowGlow());
    }

    /** The middle pill's pulse: one soft round flash at the belly, gone in 0.9 s. */
    private static void drawPulse(PoseStack.Pose pose, Vec3 cam, MultiBufferSource.BufferSource buffers) {
        if (grade != 1 || P.ending() != 2 || P.core() == null) return;
        double t = P.te(), k = FlowSpaceMath.smooth(0, .15, t) * (1 - FlowSpaceMath.smooth(.15, .95, t));
        if (k < .01) return;
        Vec3[] cv = camVecs();
        float[] c = tone(P.pill(), 0);
        var vc = buffers.getBuffer(MurimRenderTypes.flowGlow());
        Vec3 at = P.core().add(0, .14, 0).add(cam.subtract(P.core()).normalize().scale(.12));
        softDisc(vc, pose, at, cv[0], cv[1], FlowLook.CALM_PULSE_R * (.7 + .3 * k), mix(c, WHITE, .8), c, (float) (.7 * k), 28);
        buffers.endBatch(MurimRenderTypes.flowGlow());
    }

    /** Large faint lens arcs round the burst and the wide pool of light on the floor under the hanging figure (radius 3 -> 6 blocks), no column. */
    private static void drawBurstLight(PoseStack.Pose pose, Vec3 cam, MultiBufferSource.BufferSource buffers) {
        if (P.ending() != 0 || P.core() == null) return;
        double t = P.te() - FlowSpaceMath.burstAt();
        float lift = ClientPillState.lift(0F);
        double lk = FlowSpaceMath.smooth(0, .3, lift);
        float[] c = tone(P.pill(), 0), pale = mix(c, WHITE, .6);
        var vc = buffers.getBuffer(MurimRenderTypes.flowGlow());
        double pulse = t < 0 ? 0 : FlowSpaceMath.smooth(0, .3, t) * (1 - FlowSpaceMath.smooth(1.5, 3.5, t));
        double r = 2.2 + .9 * lift + 3.0 * (t < 0 ? 0 : FlowSpaceMath.smooth(0, 2.2, t));
        float a = (float) (.45 * lk * (1 + .6 * pulse));
        if (a > .01) softDisc(vc, pose, new Vec3(P.centre().x, P.floorY() + .045, P.centre().z), new Vec3(1, 0, 0), new Vec3(0, 0, 1), r, pale, c, a, 40);
        if (t > 0 && t < 3.5) {
            Vec3[] cv = camVecs();
            Vec3 cr = cv[0], cu = cv[1], f = cu.cross(cr).normalize();
            double env = FlowSpaceMath.smooth(0, .3, t) * (1 - FlowSpaceMath.smooth(1.5, 3.5, t));
            for (int i = 0; i < 3; i++) lensArc(vc, pose, P.core(), cr, cu, f, 2.2 + 1.1 * i, P.clock() * (.12 + .05 * i) + i * 2.0, 4.2, .5 + .3 * i, .035, pale, (float) (.2 * env));
            // the bloom: a huge white flash at the chest (1.5 block), the figure stays visible in front of it
            double k = FlowSpaceMath.smooth(0, .1, t) * (1 - FlowSpaceMath.smooth(.1, FlowLook.DISC_TIME, t));
            Vec3 away = new Vec3(f.x, 0, f.z).lengthSqr() < 1e-8 ? new Vec3(0, 0, -1) : new Vec3(f.x, 0, f.z).normalize();
            if (k > .01) softDisc(vc, pose, P.core().add(away.scale(.2)), cr, cu, FlowLook.DISC_R, WHITE, mix(c, WHITE, .3), (float) (.95 * k), 36);
        }
        buffers.endBatch(MurimRenderTypes.flowGlow());
    }

    private static void lensArc(VertexConsumer vc, PoseStack.Pose pose, Vec3 c, Vec3 e1, Vec3 e2, Vec3 n, double r, double a0, double sweep, double tilt, double thick, float[] rgb, float alpha) {
        if (alpha <= .004F) return;
        int segs = 48;
        Vec3 e2t = e2.scale(Math.cos(tilt)).add(n.scale(Math.sin(tilt)));
        for (int i = 0; i < segs; i++) {
            double aa = a0 + sweep * i / segs, ab = a0 + sweep * (i + 1) / segs;
            float pa = (float) Math.sin(Math.PI * (i + .5) / segs), pb = (float) Math.sin(Math.PI * (i + 1.5) / segs);
            Vec3 da = e1.scale(Math.cos(aa)).add(e2t.scale(Math.sin(aa))), db = e1.scale(Math.cos(ab)).add(e2t.scale(Math.sin(ab)));
            double[] rr = {r - thick, r, r + thick};
            float[] ca = {0F, 1F, 0F};
            for (int q = 0; q < 2; q++) {
                put(vc, pose, c.x + da.x * rr[q], c.y + da.y * rr[q], c.z + da.z * rr[q], rgb, alpha * pa * ca[q]);
                put(vc, pose, c.x + da.x * rr[q + 1], c.y + da.y * rr[q + 1], c.z + da.z * rr[q + 1], rgb, alpha * pa * ca[q + 1]);
                put(vc, pose, c.x + db.x * rr[q + 1], c.y + db.y * rr[q + 1], c.z + db.z * rr[q + 1], rgb, alpha * pb * ca[q + 1]);
                put(vc, pose, c.x + db.x * rr[q], c.y + db.y * rr[q], c.z + db.z * rr[q], rgb, alpha * pb * ca[q]);
            }
        }
    }

    /** The burst's dust as a low soft band in the pill's pale tone (a flat ring on the floor with a short soft wall), expanding to 5 blocks: no sprites. */
    private static void drawBands(PoseStack.Pose pose, MultiBufferSource.BufferSource buffers) {
        if (rings.isEmpty()) return;
        float[] pale = mix(tone(P.pill(), 0), WHITE, .5);
        var vc = buffers.getBuffer(MurimRenderTypes.flowGlow());
        int segs = 56;
        double y0 = P.floorY() + .03;
        for (double[] r : rings) {
            double k = r[8] / r[4], e = 1 - Math.pow(1 - Math.min(1, k), 2.2);
            double rad = r[2] + (r[3] - r[2]) * e;
            float a = (float) (FlowLook.BAND_ALPHA * Math.min(1, r[8] / .2) * (1 - FlowSpaceMath.smooth(.45, 1, k)));
            if (a < .01) continue;
            double w = FlowLook.BAND_W * (.6 + .6 * e);
            for (int i = 0; i < segs; i++) {
                double a0 = 2 * Math.PI * i / segs, a1 = 2 * Math.PI * (i + 1) / segs;
                double s0 = Math.sin(a0), c0 = Math.cos(a0), s1 = Math.sin(a1), c1 = Math.cos(a1);
                double[] rr = {rad - w, rad, rad + w};
                float[] pa = {0F, a, 0F};
                for (int q = 0; q < 2; q++) {
                    put(vc, pose, P.centre().x + s0 * rr[q], y0, P.centre().z + c0 * rr[q], pale, pa[q]);
                    put(vc, pose, P.centre().x + s0 * rr[q + 1], y0, P.centre().z + c0 * rr[q + 1], pale, pa[q + 1]);
                    put(vc, pose, P.centre().x + s1 * rr[q + 1], y0, P.centre().z + c1 * rr[q + 1], pale, pa[q + 1]);
                    put(vc, pose, P.centre().x + s1 * rr[q], y0, P.centre().z + c1 * rr[q], pale, pa[q]);
                }
                put(vc, pose, P.centre().x + s0 * rad, y0, P.centre().z + c0 * rad, pale, a * .8F);
                put(vc, pose, P.centre().x + s1 * rad, y0, P.centre().z + c1 * rad, pale, a * .8F);
                put(vc, pose, P.centre().x + s1 * rad, y0 + FlowLook.BAND_H, P.centre().z + c1 * rad, pale, 0);
                put(vc, pose, P.centre().x + s0 * rad, y0 + FlowLook.BAND_H, P.centre().z + c0 * rad, pale, 0);
            }
        }
        buffers.endBatch(MurimRenderTypes.flowGlow());
    }

    /** The bright disc at the dantian (1.2 block, 0.3 s) and the frame brightening in the pill tone (peak 35 %, 0.3 s). */
    private static void drawBrightening(PoseStack.Pose pose, Vec3 cam, MultiBufferSource.BufferSource buffers) {
        double t = P.te() - FlowSpaceMath.burstAt();
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        var l = camera.getLookVector();
        var left = camera.getLeftVector();
        var up = camera.getUpVector();
        Vec3 f = new Vec3(l.x(), l.y(), l.z()), lf = new Vec3(left.x(), left.y(), left.z()), u = new Vec3(up.x(), up.y(), up.z());
        var type = MurimRenderTypes.flowGlow();
        VertexConsumer vc = buffers.getBuffer(type);
        double b = P.ending() == 0 ? FlowSpaceMath.brightening(P.te()) : 0;
        if (b > .003 && P.core() != null) {
            // the frame goes pale in the pill tone BEHIND the figure (a big soft disc and a pale floor), so the body stays in front of it
            float[] tint = mix(tone(P.pill(), 0), WHITE, .2);
            float a = (float) (FlowLook.BRIGHT_ALPHA * b);
            Vec3 away = new Vec3(f.x, 0, f.z).normalize();
            softDisc(vc, pose, P.core().add(away.scale(1.2)), lf.scale(-1), u, FlowLook.BRIGHT_R, tint, tint, a, 36);
            softDisc(vc, pose, new Vec3(P.centre().x, P.floorY() + .045, P.centre().z), new Vec3(1, 0, 0), new Vec3(0, 0, 1), FlowLook.BRIGHT_R * .7, tint, tint, a * .6F, 36);
        }
        buffers.endBatch(type);
    }

    // ------------------------------------------------------------------ the frame

    /** Called once a frame before the draw: steps the state, spawns the events of the ending. */
    static void update(Params p, FxQuality quality) {
        P = p;
        double dt = p.dt();
        double spin = FlowSpaceMath.spin(p.progress()) * gd(FlowLook.CALM_SPIN, 1) * FlowSpaceMath.stall(stallT);
        if (grade >= 0) armsActive = FlowLook.CALM_ARMS[grade];
        if (p.ending() == 0) spin *= 1 + 4 * FlowSpaceMath.contraction(p.te());
        if (p.ending() == 1) spin *= 1 + 2 * FlowSpaceMath.thrown(p.te());
        rot += spin * dt;
        stallT += dt;
        for (int i = 0; i < FlowLook.ARMS_MAX; i++) {
            armGrow[i] += ((i < armsActive && (p.ending() >= 0 || p.intro() > .35) ? 1 : 0) - armGrow[i]) * Math.min(1, dt / (FlowLook.ARM_GROW * .5));
            armFlare[i] += dt;
            armBright[i] += dt;
        }
        for (int i = 0; i < sheetTear.length; i++) sheetTear[i] += dt;
        for (int i = ripples.size() - 1; i >= 0; i--) { ripples.get(i)[1] += dt; if (ripples.get(i)[1] > FlowLook.NODE_RIPPLE + .4) ripples.remove(i); }
        for (int i = rings.size() - 1; i >= 0; i--) { double[] r = rings.get(i); r[8] += dt; if (r[8] > r[4]) rings.remove(i); }
        for (int i = chips.size() - 1; i >= 0; i--) {
            double[] c = chips.get(i);
            c[6] += dt;
            double drag = Math.exp(-1.1 * dt);
            c[3] *= drag; c[5] *= drag;
            c[4] = (c[4] - (c[10] > .5 ? .5 : 6.5) * dt) * Math.exp(-.4 * dt);
            c[0] += c[3] * dt; c[1] += c[4] * dt; c[2] += c[5] * dt;
            if (c[1] < .04) { c[1] = .04; c[4] = 0; c[3] *= .9; c[5] *= .9; }
            if (c[6] > c[7]) chips.remove(i);
        }
        for (int i = motes.size() - 1; i >= 0; i--) {
            double[] m = motes.get(i);
            m[6] += dt;
            double drag = Math.exp(-1.5 * dt);
            m[3] *= drag; m[4] = m[4] * drag + .12 * dt; m[5] *= drag;
            m[0] += m[3] * dt; m[1] += m[4] * dt; m[2] += m[5] * dt;
            if (m[6] > m[7]) motes.remove(i);
        }
        if (embers == null) {
            float[] c = tone(p.pill(), 0);
            int[] pal = {rgb(deep(c, .75)), rgb(c), rgb(mix(c, WHITE, .5)), rgb(mix(c, WHITE, .25))};
            embers = new FxCultivationMotes(41, pal, new double[] {.2, .4, .3, .1}, new int[] {rgb(mix(c, WHITE, .6)), rgb(mix(c, WHITE, .8))}, FlowLook.EMBER_RATE, FlowLook.EMBER_SPARKS);
            embers.sizeScale = .6;
        }
        double growth = .3 + .7 * p.progress();
        if (p.ending() == 0) growth = p.te() < FlowSpaceMath.burstAt() ? 1 : .15 * (1 - FlowSpaceMath.smooth(FlowSpaceMath.burstAt(), FlowLook.END_DONE, p.te()));
        if (p.ending() == 1) growth = 1.3 * (1 - FlowSpaceMath.smooth(0, 1.6, p.te()));
        if (p.ending() == 2) growth = .3 * (1 - FlowSpaceMath.smooth(0, FlowLook.END_OTHER, p.te()));
        embers.step(dt, growth * gd(FlowLook.CALM_EMBERS, 1) * (p.ending() < 0 ? p.intro() : p.fade()), quality.particleScale());
        // events of the ending
        double te = p.te();
        // the middle pill's small pulse at the end of the run: a soft round of motes (pink petals only for plum pills) goes out of the belly
        if (grade == 1 && te >= 0 && !pulseSpawned && p.core() != null) {
            pulseSpawned = true;
            Vec3 c0 = p.core();
            for (int i = 0; i < 70; i++) {
                double z = (RNG.nextDouble() - .5) * 1.2, rr = Math.sqrt(1 - z * z), an = RNG.nextDouble() * 6.2832, sp = .4 + 1.0 * RNG.nextDouble();
                motes.add(new double[] {c0.x, c0.y, c0.z, Math.cos(an) * rr * sp, z * sp, Math.sin(an) * rr * sp, 0, 2.4 * (.6 + .5 * RNG.nextDouble()), .02 + .035 * RNG.nextDouble(), RNG.nextDouble()});
            }
            if (PillScene.plum()) for (int i = 0; i < 40; i++) {
                double an = RNG.nextDouble() * 6.2832, sp = .3 + .9 * RNG.nextDouble();
                motes.add(new double[] {c0.x, c0.y, c0.z, Math.cos(an) * sp, .1 + .5 * RNG.nextDouble(), Math.sin(an) * sp, 0, 2.8 * (.7 + .6 * RNG.nextDouble()), .05 + .05 * RNG.nextDouble(), 2 + RNG.nextDouble()});
            }
        }
        if (te >= 0) {
            if (p.ending() == 0 && te >= FlowSpaceMath.burstAt() && !burstSpawned) {
                burstSpawned = true;
                for (int i = 0; i < FlowLook.BEAMS + FlowLook.STREAKS; i++) {
                    boolean thin = i >= FlowLook.BEAMS;
                    double z = 1 - 2 * (i + .5) / (FlowLook.BEAMS + FlowLook.STREAKS), rr = Math.sqrt(1 - z * z), an = i * 2.399963 + RNG.nextDouble() * .3;
                    double len = thin ? 7 + 3 * RNG.nextDouble() : FlowLook.RAY_LEN_MIN + (FlowLook.RAY_LEN_MAX - FlowLook.RAY_LEN_MIN) * RNG.nextDouble();
                    double w = thin ? .07 : FlowLook.RAY_W_MIN + (FlowLook.RAY_W_MAX - FlowLook.RAY_W_MIN) * RNG.nextDouble();
                    rays.add(new double[] {Math.cos(an) * rr, z, Math.sin(an) * rr, len, w, 0, RNG.nextDouble() * .25, RNG.nextDouble(), thin ? 1 : 0});
                }
                Vec3 c0 = p.core();
                for (int i = 0; i < FlowLook.ESSENCE; i++) {
                    double z = 1 - 2 * RNG.nextDouble(), rr = Math.sqrt(1 - z * z), an = RNG.nextDouble() * 6.2832, sp = FlowLook.ESSENCE_SPEED_MIN + (FlowLook.ESSENCE_SPEED_MAX - FlowLook.ESSENCE_SPEED_MIN) * Math.pow(RNG.nextDouble(), 1.3);
                    motes.add(new double[] {c0.x, c0.y, c0.z, Math.cos(an) * rr * sp, z * sp, Math.sin(an) * rr * sp, 0, FlowLook.ESSENCE_LIFE * (.6 + .5 * RNG.nextDouble()), .025 + .05 * RNG.nextDouble() * RNG.nextDouble() * 2, RNG.nextDouble()});
                }
                for (int i = 0; i < FlowLook.PETALS; i++) {
                    double z = 1 - 2 * RNG.nextDouble(), rr = Math.sqrt(1 - z * z), an = RNG.nextDouble() * 6.2832, sp = .3 + 1.8 * RNG.nextDouble();
                    motes.add(new double[] {c0.x, c0.y, c0.z, Math.cos(an) * rr * sp, z * sp, Math.sin(an) * rr * sp, 0, FlowLook.ESSENCE_LIFE * (.8 + .6 * RNG.nextDouble()), .05 + .07 * RNG.nextDouble(), 2 + RNG.nextDouble()});
                }
            }
            // the body lifts after the burst: essence settles round the rising figure
            if (p.ending() == 0 && te >= FlowSpaceMath.burstAt() + .3 && ClientPillState.finaleLift() && ClientPillState.finaleLiftHeight() >= FlowLook.LIFT_VISIBLE && p.core() != null) {
                int n = (int) Math.floor(dt * FlowLook.LIFT_MOTE_RATE + liftCarry);
                liftCarry = dt * FlowLook.LIFT_MOTE_RATE + liftCarry - n;
                for (int i = 0; i < n; i++) {
                    double an = RNG.nextDouble() * 6.2832, rr = .3 + RNG.nextDouble() * .8;
                    motes.add(new double[] {p.core().x + Math.sin(an) * rr, p.core().y - .6 + RNG.nextDouble() * 2.2, p.core().z + Math.cos(an) * rr, (RNG.nextDouble() - .5) * .3, .05 + .2 * RNG.nextDouble(), (RNG.nextDouble() - .5) * .3,
                            0, 1.6 + RNG.nextDouble() * .9, FlowLook.LIFT_MOTE_SIZE * (.5 + RNG.nextDouble()), RNG.nextDouble()});
                }
            }
            if (p.ending() == 1 && te >= FlowLook.BURST_BALL && !throwSpawned) {
                throwSpawned = true;
                for (int i = 0; i < FlowLook.BURST_CHIPS; i++) {
                    double an = RNG.nextDouble() * 6.28, rr = .8 + RNG.nextDouble() * 1.6, sp = 3 + RNG.nextDouble() * 6;
                    chips.add(new double[] {Math.sin(an) * rr, .4 + RNG.nextDouble() * 1.6, Math.cos(an) * rr, Math.sin(an) * sp, 1 + RNG.nextDouble() * 4, Math.cos(an) * sp,
                            0, 1.6 + RNG.nextDouble() * 1.4, .04 + RNG.nextDouble() * .06, RNG.nextDouble(), 0});
                }
            }
            if (p.ending() == 1 && te >= FlowLook.BURST_BALL + 1.0 && !ashSpawned) {
                ashSpawned = true;
                for (int i = 0; i < FlowLook.ASH_FLAKES; i++) {
                    double an = RNG.nextDouble() * 6.28, rr = RNG.nextDouble() * 4.5;
                    chips.add(new double[] {Math.sin(an) * rr, 1 + RNG.nextDouble() * 2, Math.cos(an) * rr, (RNG.nextDouble() - .5) * .3, -.15 - .2 * RNG.nextDouble(), (RNG.nextDouble() - .5) * .3,
                            0, 2.2 + RNG.nextDouble() * 1.4, .03 + .04 * RNG.nextDouble(), RNG.nextDouble(), 1});
                }
            }
        }
        lastTe = te;
    }

    /** Draws the space in the scene's pass (the scene's depth): far to near. */
    static void draw(PoseStack.Pose pose, Vec3 cam, MultiBufferSource.BufferSource buffers, int tier) {
        if (P == null || P.fade() <= .003 && P.intro() <= .003 && P.clock() > FlowLook.INTRO_LEAD + FlowLook.INTRO_DISSOLVE + FlowLook.INTRO_TRAVEL + .4 && P.te() < 0) return;
        if (FlowFireStand.ON) {
            // the aura stand: only the aura and the floor vortex
            FlowAura.draw(pose, cam, buffers, tier);
            drawArms(pose, cam, buffers, tier);
            return;
        }
        boolean aura = FlowAura.game();
        if (!aura) {
            if (grade >= 0 && FlowFireSheets.available()) FlowFireSheets.draw(P, pose, cam, buffers, tier); else drawSheets(pose, cam, buffers, tier);
        }
        boolean finale = aura && P.ending() >= 0 && P.ending() <= 1;
        if (!aura) drawHalo(pose, cam, buffers);
        if (!finale) drawDustAndChips(pose, buffers);
        drawArms(pose, cam, buffers, tier);
        if (aura) {
            FlowAura.draw(pose, cam, buffers, tier);   // the engine's aura, after the floor vortex; the old fire, halo, embers and intro are not drawn
            // the finale on the burst / lift layers: the old thin rays, the confetti motes, the dust ring and the bloom are not drawn then
            if (P.ending() < 0) FlowBurstLift.warmGame(pose, cam, buffers, P.clock(), Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false));
            if (finale) FlowBurstLift.drawGame(pose, cam, buffers, P.ending(), P.te(), rgb(P.pill()), Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false));
        } else { drawEmbers(pose, buffers, env()); drawIntro(pose, cam, buffers); }
        if (!finale) {
            drawMotes(pose, buffers);
            drawBands(pose, buffers);
            drawPulse(pose, cam, buffers);
            drawBurstLight(pose, cam, buffers);
        }
        if (!aura) drawFlakes(pose, cam, buffers, tier);
        if (!finale) {
            drawRays(pose, cam, buffers, tier);
            drawBrightening(pose, cam, buffers);
        }
    }

    /** The share (0..1) of the effect still alive, for the camera and the lingering (seconds after the end are the director's). */
    static double lastTe() { return lastTe; }

    /** The brightest arm's outer point (world) for a local floor light and its strength; null when none. */
    static Vec3 armLight() {
        if (P == null) return null;
        int best = -1; double bv = .12;
        for (int i = 0; i < armsActive; i++) {
            double v = (armBright[i] < 5 ? FlowSpaceMath.armBrighten(armBright[i]) : 0) + (armFlare[i] < FlowLook.FORK_FLARE ? Math.sin(Math.PI * armFlare[i] / FlowLook.FORK_FLARE) : 0);
            if (v > bv) { bv = v; best = i; }
        }
        if (best < 0) return null;
        double[] pt = new double[5];
        FlowSpaceMath.armPoint(FlowSpaceMath.arm(best), .7, rot, rimRadius(), pt);
        return new Vec3(P.centre().x + pt[0], P.floorY() + .35, P.centre().z + pt[2]);
    }
}
