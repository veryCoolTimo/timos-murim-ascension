package io.github.verycooltimo.murim.client.flow;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.client.vfx.MurimRenderTypes;
import io.github.verycooltimo.murim.client.vfx.fx.FxCultivationMotes;
import io.github.verycooltimo.murim.client.vfx.fx.FxCultivationScene;
import io.github.verycooltimo.murim.client.vfx.fx.FxEssence;
import io.github.verycooltimo.murim.client.vfx.fx.FxFlame;
import io.github.verycooltimo.murim.client.vfx.fx.FxFlameLook;
import io.github.verycooltimo.murim.client.vfx.fx.FxPerf;
import io.github.verycooltimo.murim.client.vfx.fx.FxQuality;
import io.github.verycooltimo.murim.client.vfx.fx.FxTune;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;

/**
 * The high pill's absorption picture on the effect engine, two phases (refs vfx-essence/pill-sequence 1, 1.5, 2, 3, 3.5 and pills-ar/melting):
 * <ol>
 *   <li><b>MELT</b> (about 2.5 s): the pill's light is born INSIDE the belly, a soft halo and a narrow lozenge of light climb the torso, the body lamp
 *       ({@link FlowBodyRim} lamp) lights the clothes from the belly outward and never the head, the first two thin wisps of essence leave the shoulders.</li>
 *   <li><b>RISING AURA</b> (level 0..1): essence as CLOTH, broad soft sheets ({@link FxFlame} cloth mode, {@link FxEssence.Strip}) that rise and wrap round and
 *       behind the seated figure from knee height to 2-3 body heights, a pale glow behind and under the body, a few light motes. No dust, no flakes.</li>
 * </ol>
 * Colour is the pill's: two tones of it and a pale core low down. All numbers are {@code FxTune.f("flow.aura.<key>", default)}.
 *
 * <p>Director entry (matches {@code FlowScore}): once a frame {@link #tick} with the clock, step, pill colour and the belly (the director's
 * {@code body.o()} and {@code body.out()}), after {@code FlowAura.setLevel(FlowScore.fire(fallback))} (0.4 s ease inside) and {@link #drop} for a mistake when
 * the score does not already dip; {@link #startMelt()} when the pill is swallowed; {@link #draw} in the scene's pass after the floor vortex.
 * Stand: {@code MURIM_CAPTURE_FLOW=aurastand} ({@link FlowFireStand}) loops the melt and a level script (13.5 s) under a fixed camera.
 */
final class FlowAura {
    private FlowAura() { }

    /** Length of the melt in seconds. */
    static double meltLen() { return f("melt.len", 2.5F); }

    private static float f(String key, float def) { return FxTune.f("flow.aura." + key, def); }

    // ------------------------------------------------------------------ state

    private static final FxQuality QUALITY = new FxQuality();
    private static FxCultivationMotes MOTES = new FxCultivationMotes(1116);
    private static double meltT = 99, level, target, dropAge = 99, dropDepth, gate = 1, clock, lastClock = -1;
    private static int rgb = 0xC81E28, lookRgb = -1, scriptStep;
    private static Vec3 belly = Vec3.ZERO, out = new Vec3(0, 0, 1);
    private static double floorY;
    private static FxFlameLook[] looks;
    private static float[] pill = {.8F, .1F, .1F};

    private static double smooth(double a, double b, double x) {
        double k = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return k * k * (3 - 2 * k);
    }

    /** The pill is swallowed: the melt starts now. */
    static void startMelt() { meltT = 0; level = 0; target = 0; dropAge = 99; }

    /** The fire level 0..1; eased over about 0.4 s. */
    static void setLevel(double v) { target = Math.max(0, Math.min(1, v)); }

    /** A mistake: the level falls by {@code depth} over 0.4 s and climbs back over 1.6 s (the same shape as {@code FlowScore.fire}). */
    static void drop(double depth) { dropAge = 0; dropDepth = depth; }

    static double level() { return level; }

    private static double pull;

    /** 0..1 how far the aura is pulled into the dantian (the finale's anticipation: lower, narrower, thinner); 0 in the run. */
    static void setPull(double p) { pull = Math.max(0, Math.min(1, p)); }

    /** True while the aura is nearly gone (nothing to draw). */
    static boolean off() { return gate <= .004 && meltT > meltLen() + 1; }

    /** True while the scroll game runs on the aura (the old fire sheets, halo, embers, intro and dark flakes are not drawn then). */
    static boolean game() { return FlowScrollBoard.scrollMode() && FlowScore.active() && FxTune.f("flow.aura.game", 1F) > .5F && !"0".equals(System.getenv("MURIM_FLOW_AURA")); }

    /** The aura's overall strength 0..1: the director fades it out when the game ends. */
    static void setGate(double g) { gate = Math.max(0, Math.min(1, g)); }

    /** The game's own lamp state back (the finale's lamp lights the head and is pale again). */
    static void releaseLamp() {
        FlowBodyRim.lampNoHead = false; FlowBodyRim.lampBand = .45F; FlowBodyRim.lampPale = .55F; FlowBodyRim.dimK = 0;
    }

    // ------------------------------------------------------------------ tick

    /**
     * One frame of state.
     * @param time  the clock in seconds (the stand: game time)
     * @param dt    the step
     * @param pillRgb the pill colour 0xRRGGBB
     * @param o     the bottom centre of the torso's front face ({@code FlowMeridian.Body#o})
     * @param fwd   the torso's outward unit vector
     * @param floor the floor's height under the figure
     * @param pressure01 0..1 heat of the game
     */
    static void tick(double time, double dt, int pillRgb, Vec3 o, Vec3 fwd, double floor, double pressure01) {
        clock = time; belly = o; out = fwd; floorY = floor; rgb = pillRgb;
        pill = new float[] {((pillRgb >> 16) & 255) / 255F, ((pillRgb >> 8) & 255) / 255F, (pillRgb & 255) / 255F};
        if (FlowFireStand.ON) script(time);
        meltT += dt;
        dropAge += dt;
        double dip = 0, in = .4, back = 1.6;
        if (dropAge < in + back) dip = dropDepth * (dropAge < in ? smooth(0, 1, dropAge / in) : 1 - smooth(0, 1, (dropAge - in) / back));
        double want = Math.max(0, Math.min(1, target - dip));
        level += (want - level) * (1 - Math.exp(-dt / f("ease", .13F)));    // about 0.4 s to settle
        QUALITY.update(FxPerf.lastFrameMs(), dt);
        MOTES.sizeScale = f("mote.size", .8F);
        MOTES.wind = f("mote.wind", .1F);
        MOTES.step(dt, gate * level * smooth(meltT, meltT + 1, meltLen() * .9), QUALITY.particleScale());
        pressure = pressure01;
        lamp();
    }

    private static double pressure;

    /** The stand's script: melt, then 0.2 -> 0.5 -> 0.8 -> 1.0, one drop, a fade out; one loop of {@code stand_loop} seconds. */
    private static void script(double time) {
        double loop = f("stand_loop", 13.5F), ts = time % loop;
        if (lastClock >= 0 && ts < lastClock) { startMelt(); scriptStep = 0; MOTES.motes.clear(); }
        lastClock = ts;
        meltT = ts;
        double[] at = {f("stand_l1", 2.7F), f("stand_l2", 4.9F), f("stand_l3", 7.0F), f("stand_l4", 9.0F)};
        double[] lv = {.2, .5, .8, 1};
        for (int i = scriptStep; i < 4; i++) if (ts >= at[i]) { setLevel(lv[i]); scriptStep = i + 1; }
        if (scriptStep == 4 && ts >= f("stand_drop", 10.9F)) { drop(f("stand_drop_depth", .45F)); scriptStep = 5; }
        gate = 1 - smooth(loop - 1.3, loop - .2, ts);
    }

    /** The body as a lamp: light spreads from the belly outward through the clothes, never over the head. */
    private static void lamp() {
        double grow = smooth(f("lamp.t0", .4F), f("lamp.t1", 2.4F), meltT);
        FlowBodyRim.lampNoHead = true;
        FlowBodyRim.lampBand = f("lamp.band", .9F);
        FlowBodyRim.dimK = (float) (f("dim", game() ? .66F : .55F) * smooth(0, 1.0, meltT) * gate);
        FlowBodyRim.lampPale = f("lamp.pale", .06F);
        FlowBodyRim.lampAt = belly.add(0, f("belly.up", .27F), 0);
        float[] lt = lite(pill, f("lamp.lite", .2F));
        // in the game the lamp is shifted a little toward orange: a gold light on a blue-white shirt turns it green-yellow
        if (game()) lt = new float[] {lt[0], lt[1] * f("lamp.g", .66F), lt[2] * f("lamp.b", .42F)};
        FlowBodyRim.lampTone = lt;
        double hold = f("lamp.hold", .45F) + (1 - f("lamp.hold", .45F)) * 0;
        double k = f("lamp.gain", .8F) * smooth(0, 1.2, meltT) * (grow * (1 - .5 * smooth(.2, .8, level)) + 0) * gate;
        FlowBodyRim.lampK = (float) Math.max(k, f("lamp.hold", .5F) * grow * gate * (.5 + .5 * level));
        FlowBodyRim.lampR = (float) (f("lamp.reach0", .3F) + (f("lamp.reach1", 1.15F) - f("lamp.reach0", .3F)) * grow);
    }

    /** 0 while the melt plays (the stand's camera is close), then 1 as it eases out to the aura framing. */
    static double camBlend() { return smooth(meltLen() - .1, meltLen() + 1.1, meltT); }

    private static float[] lite(float[] c, double q) {
        float m = Math.max(c[0], Math.max(c[1], c[2])), s = m <= 0 ? 1 : (float) ((1 + q) / Math.max(m, .35F));
        return new float[] {Math.min(1, c[0] * s), Math.min(1, c[1] * s), Math.min(1, c[2] * s)};
    }

    private static int pack(float[] c, double lift) {
        int r = Math.round((float) (Math.min(1, c[0] + (1 - c[0]) * lift) * 255)), g = Math.round((float) (Math.min(1, c[1] + (1 - c[1]) * lift) * 255)), b = Math.round((float) (Math.min(1, c[2] + (1 - c[2]) * lift) * 255));
        return r << 16 | g << 8 | b;
    }

    // ------------------------------------------------------------------ cloth looks (the pill's two tones and a pale core)

    private static void buildLooks() {
        if (lookRgb == rgb && looks != null) return;
        lookRgb = rgb;
        float m = Math.max(pill[0], Math.max(pill[1], Math.max(pill[2], .01F))), cap = Math.min(m, f("tone.cap", .72F));
        // in the game a gold pill reads as deep amber, not pale yellow: the green and the blue are pulled down a little
        float gk = game() ? f("amber.g", .86F) : 1F, bk = game() ? f("amber.b", .6F) : 1F;
        float[] body = {pill[0] / m * cap, pill[1] / m * cap * gk, pill[2] / m * cap * bk};
        int deep = pack(new float[] {body[0] * f("tone.deep", .2F), body[1] * f("tone.deep", .2F), body[2] * f("tone.deep", .2F)}, 0);
        int mid = pack(body, 0), pale = pack(body, f("tone.pale", .1F));
        int[] cols = {deep, mid, mid, pale, pale};
        MOTES = new FxCultivationMotes(1116, new int[] {pack(body, .22F), mid, pack(body, .5F)}, new double[] {.4, .4, .2}, new int[] {pack(body, .5F)}, f("mote.rate", 14F), 0);
        int second = pack(new float[] {body[0] * f("tone.second", .62F), body[1] * f("tone.second", .62F), body[2] * f("tone.second", .62F)}, 0);
        int[] flat = {deep, second, mid, pale, pale};
        boolean soft = !"flat".equals(System.getenv("MURIM_AURA_STYLE")) && f("style", 1F) > .5F;   // MURIM_AURA_STYLE=flat: the cel-shaded flat essence instead of the cloth (dev compare; FxTune stops the stand's capture)
        // the essence's own cel-shaded flame (flat tones, torn holes and tongues, pale accents), tuned like the accepted aura's sheets: a tall slow one behind,
        // finer ones at the sides, small low ones in front
        looks = soft ? new FxFlameLook[] {
                cloth(.11F, .45F, .55F, .28F, -.3F, -.15F, .2F, .15F, cols, f("a.deep", .8F), f("a.body", .78F)),
                cloth(.15F, .55F, .65F, .24F, -.28F, -.12F, .2F, .15F, cols, f("a.deep", .8F), f("a.body", .74F)),
                cloth(.1F, .4F, .45F, .38F, -.25F, -.05F, .25F, .12F, cols, f("a.deep", .8F), f("a.body", .7F))}
                : new FxFlameLook[] {
                flat(.18F, .6F, .45F, .45F, .0F, .07F, flat), flat(.22F, .7F, .5F, .5F, -.02F, .06F, flat), flat(.2F, .7F, .5F, .5F, -.04F, .04F, flat)};
    }

    private static FxFlameLook flat(float tiles, float rise, float warp, float squash, float low, float high, int[] cols) {
        float p = (float) (1 + pressure * f("p.rise", .5F)), a = f("a.flat", .92F);
        return new FxFlameLook(tiles, rise * p, warp, squash, low, high, 1F, 1F, f("dark", .24F), f("light", .22F), f("accent", .15F), f("second", .12F), cols,
                new float[] {a, a * .95F, a * .95F, a * .95F, a}, new float[] {1F, .95F, .85F, .6F}, new float[] {2F, 0F, 0F, f("foot", .5F)});
    }

    private static FxFlameLook cloth(float tiles, float rise, float warp, float squash, float low, float high, float fadeFrom, float pale, int[] cols, float aDeep, float aBody) {
        float p = (float) (1 + pressure * f("p.rise", .5F));
        return new FxFlameLook(tiles, rise * p, warp, squash, low, high, 1F, 0F, .2F, .15F, 0F, 0F, cols,
                new float[] {aDeep, aBody, aBody, aBody, aBody}, new float[] {1F, 1F, .95F, .8F}, new float[] {1F, f("feather", .16F), fadeFrom, pale + f("core", .06F)});
    }

    // ------------------------------------------------------------------ the sheets

    /**
     * One cloth: {@code rel} is the middle azimuth in degrees from the camera's side (0 = towards the camera, 180 = straight behind), {@code span} its arc,
     * {@code r0} the radius at its foot, {@code flare} the opening per block of height, {@code y0} the foot's height above the floor, {@code hLo}/{@code hHi} its
     * height at level 0 and 1, {@code lean} how far the top is turned round the figure (degrees), {@code at} the level where it starts to grow, {@code look} the look.
     */
    private record Sheet(double rel, double span, double r0, double flare, double y0, double hLo, double hHi, double lean, double at, int look, float alpha, double seed, double ph) { }

    private static final Sheet[] SHEETS = {
            // the mass is BEHIND and above the figure and envelops it; the wind blows from one side, so every current leans the same way (positive lean)
            new Sheet(180, 200, 2.2, .5, .5, 2.0, 4.6, 30, .70, 0, .55F, 7, 1.1),       // the far big one, only when the fire is high
            new Sheet(178, 140, 1.7, .5, .3, 1.8, 4.3, 35, .45, 0, .72F, 3, 0),
            new Sheet(180, 120, 1.05, .4, .12, 1.5, 3.4, 42, .00, 0, .9F, 31, .7),      // directly behind the back and head, from the first step
            new Sheet(158, 90, 1.25, .42, .12, 1.3, 3.8, 55, .05, 1, .85F, 5, 2.1),
            new Sheet(204, 90, 1.2, .42, .12, 1.3, 3.5, 48, .20, 1, .85F, 9, 3.3),
            new Sheet(98, 70, 1.1, .3, .1, .8, 2.0, 60, .15, 2, .72F, 11, 4.2),          // the sides: lower, curling inward (towards the back)
            new Sheet(-100, 70, 1.15, .3, .1, .8, 2.1, -50, .30, 2, .72F, 13, 5.6),
            new Sheet(24, 72, .95, .15, .08, .5, 1.0, 110, .10, 2, .42F, 17, 6.4),        // low diagonal currents across the knees and the lap
            new Sheet(-26, 70, 1.05, .15, .1, .45, .95, 115, .20, 2, .4F, 19, 7.7),
            new Sheet(2, 60, .88, .12, .08, .4, .85, 120, .35, 1, .36F, 23, 8.3)};

    private static Vec3 axis() { return belly.subtract(out.scale(f("axis.back", .17F))); }

    /** Builds a sheet's strip in world points; {@code null} while it is not grown. */
    private static FxEssence.Strip strip(Sheet s, Vec3 feet, double camA, double lv, double t, int n) {
        double grow = smooth(s.at(), s.at() + f("grow", .3F), lv) * gate;
        if (grow <= .004) return null;
        double pl = pull;
        double h = (s.hLo() + (s.hHi() - s.hLo()) * lv) * (1 - .6 * pl);
        double wander = Math.toRadians(f("wander", 5F)) * Math.sin(.21 * t + s.ph()), lean = Math.toRadians(s.lean()) * (game() ? f("lean.k", .45F) : 1) * (h / 3.5) * (1 + .12 * Math.sin(.29 * t + s.ph() * 1.7));
        double mid = camA + Math.toRadians(s.rel()) + wander, span = Math.toRadians(s.span());
        double y0 = s.y0() + (belly.y - feet.y - s.y0()) * .55 * pl, y1 = y0 + h, r0 = s.r0() * (1 - .9 * pl);
        Vec3[] top = new Vec3[n + 1], bottom = new Vec3[n + 1];
        for (int j = 0; j <= n; j++) {
            double a = mid - span / 2 + span * j / n, rb = r0, rt = r0 + s.flare() * (1 - pl) * h, at = a + lean;
            bottom[j] = feet.add(Math.sin(a) * rb, y0, Math.cos(a) * rb);
            top[j] = feet.add(Math.sin(at) * rt, y1, Math.cos(at) * rt);
        }
        double[] cam = {Math.sin(camA), Math.cos(camA)};
        Integer[] idx = new Integer[n];
        double[] d = new double[n];
        for (int j = 0; j < n; j++) {
            idx[j] = j;
            Vec3 m = top[j].add(top[j + 1]).add(bottom[j]).add(bottom[j + 1]).scale(.25).subtract(feet);
            d[j] = m.x * cam[0] + m.z * cam[1];
        }
        java.util.Arrays.sort(idx, Comparator.comparingDouble(j -> d[j]));
        int[] order = new int[n];
        for (int j = 0; j < n; j++) order[j] = idx[j];
        double rm = s.r0() + s.flare() * h * .5, hw = Math.max(.1, rm * span / 2);
        return new FxEssence.Strip(top, bottom, order, hw, h / 2, s.seed(), 1.0, (float) (s.alpha() * grow * (1 - .4 * pl) * (1 - f("p.alpha", .12F) + f("p.alpha", .12F) * pressure) * f("alpha", 1F)));
    }

    private record Drawn(FxEssence.Strip strip, FxFlameLook look, double depth) { }

    // ------------------------------------------------------------------ draw

    static void draw(PoseStack.Pose pose, Vec3 cam, MultiBufferSource.BufferSource buffers, int tier) {
        if (gate <= .004 && meltT > meltLen() + 1) return;
        if (!FxFlame.available()) return;
        buildLooks();
        Vec3 ax = axis(), feet = new Vec3(ax.x, floorY, ax.z);
        double camA = Math.atan2(cam.x - feet.x, cam.z - feet.z);
        if (game()) {
            // the game camera is shifted sideways for the scroll: the aura stands behind the figure along the camera's VIEW direction, not along the line to the camera's position
            double[] rest = io.github.verycooltimo.murim.client.camera.FlowCamera.restPose();
            if (rest != null) camA = Math.PI - Math.toRadians(rest[3]);
        }
        double cx = Math.sin(camA), cz = Math.cos(camA), t = clock;
        int tq = Math.max(QUALITY.tier(), Math.min(3, tier));
        glow(pose, buffers, feet, cx, cz, t, tq);
        List<Drawn> list = new ArrayList<>();
        int n = tq >= 2 ? 6 : 10;
        for (int i = 0; i < SHEETS.length; i++) {
            Sheet s = SHEETS[i];
            if (tq >= 3 && s.hHi() < 1) continue;
            FxEssence.Strip st = strip(s, feet, camA, level, t, n);
            if (st == null) continue;
            FxFlameLook lk = looks[s.look()].fitted(st.halfWidth());
            double mid = Math.toRadians(s.rel());
            list.add(new Drawn(st, lk, Math.cos(mid) * (s.r0())));       // positive = nearer the camera
        }
        wisps(list, feet, cx, cz, t);
        list.sort(Comparator.comparingDouble(Drawn::depth));
        for (Drawn d : list) FxFlame.draw(d.look(), d.strip(), pose, buffers, t);
        motes(pose, buffers, feet);
    }

    // ------------------------------------------------------------------ the melt's wisps: two thin cloths leave the shoulders

    private static void wisps(List<Drawn> list, Vec3 feet, double cx, double cz, double t) {
        double born = smooth(f("wisp.t0", 1.2F), f("wisp.t1", 2.4F), meltT) * (1 - smooth(f("wisp.off0", .2F), f("wisp.off1", .6F), level)) * gate;
        if (born <= .01) return;
        Vec3 side = new Vec3(-out.z, 0, out.x).normalize(), w = new Vec3(cz, 0, -cx);    // the shoulders' line; the quad's width faces the camera
        double sh = belly.y + f("wisp.shoulder", .58F), rise = f("wisp.rise", .75F) * (.5 + .5 * born), hw = f("wisp.w", .2F);
        for (int k = -1; k <= 1; k += 2) {
            Vec3 foot = feet.add(side.scale(k * .36)).add(0, sh - feet.y, 0);
            double bend = k * (.34 + .1 * Math.sin(.4 * t + k));
            Vec3[] top = new Vec3[5], bottom = new Vec3[5];
            for (int j = 0; j <= 4; j++) {
                double u = -1 + 2.0 * j / 4;
                bottom[j] = foot.add(w.scale(u * hw * .8));
                top[j] = foot.add(side.scale(bend)).add(0, rise, 0).add(w.scale(u * hw * 1.4));
            }
            FxEssence.Strip st = new FxEssence.Strip(top, bottom, new int[] {0, 1, 2, 3}, hw, rise / 2, 31 + k * 7, 1.0, (float) (born * f("wisp.a", .8F)));
            list.add(new Drawn(st, looks[2].fitted(hw), .01));
        }
    }

    // ------------------------------------------------------------------ light: the melt's belly light, then the glow behind and under the body

    private static void glow(PoseStack.Pose pose, MultiBufferSource.BufferSource buffers, Vec3 feet, double cx, double cz, double t, int tq) {
        List<FxCultivationScene.Light> lights = new ArrayList<>();
        double by = belly.y - floorY + f("belly.up", .27F), lv = level;
        // MELT: the pill's light inside the belly (a hot point, a pool), a narrow lozenge that climbs the torso. In front of the torso's face for the camera.
        double seed = smooth(0, .7, meltT), pool = smooth(.25, 1.9, meltT), climb = smooth(f("climb.t0", .9F), f("climb.t1", 2.5F), meltT);
        double melt = (1 - .75 * smooth(.1, .7, lv)) * gate;
        double bz = f("belly.z", .24F);
        float[] hot = lite(pill, .55F);
        lights.add(new FxCultivationScene.Light("pool", 0, by, bz, f("pool.r", .6F) * (.35 + .65 * pool), f("pool.r", .6F) * (.35 + .65 * pool) * .9, pack(pill, .1), f("pool.a", .8F) * pool * melt));
        lights.add(new FxCultivationScene.Light("core", 0, by, bz + .02, f("core.r", .15F) * (.5 + .5 * pool), f("core.r", .15F) * (.5 + .5 * pool), pack(hot, .25), f("core.a", .9F) * seed * melt));
        double hy = .07 + f("loz.h", .5F) * climb;
        double cut = Math.min(hy, f("top", .62F) - (by - (belly.y - floorY)) - hy * .8 + hy * .8);
        lights.add(new FxCultivationScene.Light("lozenge", 0, by + hy * .8, bz, f("loz.w", .15F) * (.8 + .4 * climb), Math.max(.05, Math.min(hy, cut + .15)), pack(pill, .15), f("loz.a", .7F) * smooth(.5, 1.6, meltT) * melt));
        // AURA: the concentrated glow behind and under the body (falls off to nothing), the pool low in front of the knees, a high air light
        double a = gate * smooth(meltT, meltT + 1, meltLen() * .8) * (.3 + .7 * lv) * f("glow", 1F);
        float[] deep = pill;
        lights.add(new FxCultivationScene.Light("wash", 0, 1.0, 0, 2.4, 1.6, pack(deep, 0), .3 * a));
        lights.add(new FxCultivationScene.Light("back", 0, .9 + .5 * lv, -.9, 1.1 + .6 * lv, 1.2 + 1.0 * lv, pack(deep, 0), .6 * a));
        lights.add(new FxCultivationScene.Light("core_back", 0, .8, -.4, .75, .85, pack(deep, .2), .4 * a));
        lights.add(new FxCultivationScene.Light("under", 0, .32, -.05, 1.3, .55, pack(deep, .1), .55 * a));
        lights.add(new FxCultivationScene.Light("knees", 0, .26, .75, 1.0, .42, pack(deep, .1), .45 * a));
        if (tq < 2) lights.add(new FxCultivationScene.Light("air", 0, 2.0 + lv, -.6, 1.5, 1.6, pack(deep, 0), .22 * a * lv));
        var type = MurimRenderTypes.presetLight();
        VertexConsumer vc = buffers.getBuffer(type);
        int grid = QUALITY.grid();
        for (var l : lights) {
            if (l.alpha() <= .004) continue;
            int r = (l.rgb() >> 16) & 255, g = (l.rgb() >> 8) & 255, b = l.rgb() & 255;
            for (float[] v : FxCultivationScene.mesh(l, cx, cz, grid)) {
                vc.addVertex(pose, (float) (feet.x + v[0]), (float) (feet.y + v[1]), (float) (feet.z + v[2])).setColor(r, g, b, Math.clamp(Math.round(v[3] * 255), 0, 255))
                        .setUv(0, 0).setUv1(0, 0).setUv2(0, 0).setNormal(0, 0, 1);
            }
        }
        buffers.endBatch(type);
        FxPerf.draw();
    }

    // ------------------------------------------------------------------ a few soft light motes (the engine's FxCultivationMotes)

    private static void motes(PoseStack.Pose pose, MultiBufferSource.BufferSource buffers, Vec3 feet) {
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        var left = camera.getLeftVector();
        Vec3 cr = new Vec3(-left.x(), -left.y(), -left.z()), cu = new Vec3(camera.getUpVector());
        var type = MurimRenderTypes.presetLight();
        VertexConsumer vc = buffers.getBuffer(type);
        for (var m : MOTES.motes) {
            double e = m.envelope() * f("mote.a", .8F);
            if (e <= .01 || m.spark) continue;
            Vec3 p = feet.add(m.x, m.y, m.z);
            int r = (m.rgb >> 16) & 255, g = (m.rgb >> 8) & 255, b = m.rgb & 255;
            int cr2 = r + Math.round((255 - r) * m.bright * .6F), cg2 = g + Math.round((255 - g) * m.bright * .6F), cb2 = b + Math.round((255 - b) * m.bright * .6F);
            int a = Math.clamp(Math.round((.5F + .4F * m.bright) * e * 255), 0, 255);
            double s = m.size;
            Vec3 ex = cr.scale(s), ey = cu.scale(s);
            Vec3[] ring = new Vec3[9];
            for (int q = 0; q <= 8; q++) { double an = q * Math.PI / 4 + .39; ring[q] = p.add(ex.scale(Math.cos(an))).add(ey.scale(Math.sin(an))); }
            for (int q = 0; q < 8; q++) {
                put(vc, pose, p, cr2, cg2, cb2, a); put(vc, pose, ring[q], r, g, b, 0); put(vc, pose, ring[q + 1], r, g, b, 0); put(vc, pose, ring[q + 1], r, g, b, 0);
            }
        }
        buffers.endBatch(type);
    }

    private static void put(VertexConsumer vc, PoseStack.Pose pose, Vec3 p, int r, int g, int b, int a) {
        vc.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a).setUv(0, 0).setUv1(0, 0).setUv2(0, 0).setNormal(0, 0, 1);
    }
}
