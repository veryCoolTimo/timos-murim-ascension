package io.github.verycooltimo.murim.client.flow;

/**
 * Pure maths of the space layers of the high pill game (vortex arms, finale and burst timeline, camera keys, event envelopes). No Minecraft classes:
 * unit-tested (FlowSpaceMathTest). All numbers come from {@link FlowLook}. Frame: x right, y up from the floor, z towards the front camera, blocks.
 */
final class FlowSpaceMath {
    private FlowSpaceMath() { }

    static double clamp01(double x) { return x < 0 ? 0 : (x > 1 ? 1 : x); }

    static double smooth(double e0, double e1, double x) {
        double k = clamp01((x - e0) / (e1 - e0));
        return k * k * (3 - 2 * k);
    }

    static double lerp(double a, double b, double k) { return a + (b - a) * k; }

    // ------------------------------------------------------------------ run-long curves

    /** Rim radius of the vortex (blocks) for the drawn progress 0..1. */
    static double vortexRadius(double progress) { return lerp(FlowLook.VORTEX_R0, FlowLook.VORTEX_R1, smooth(0, 1, progress)); }

    /** Rotation speed in degrees per second. */
    static double spin(double progress) { return lerp(FlowLook.SPIN_START, FlowLook.SPIN_END, clamp01(progress)); }

    static double armAlpha(double progress) { return lerp(FlowLook.ARM_ALPHA_START, FlowLook.ARM_ALPHA_END, clamp01(progress)); }

    /** Height of a sheet (blocks) from the progress and the pressure share. */
    static double sheetHeight(double progress, double pressure01) {
        return sheetHeight(progress, pressure01, .55);
    }

    /** The share {@code wp} of the height follows the progress (or the fire level of the scroll game), the rest the pressure (heat). */
    static double sheetHeight(double progress, double pressure01, double wp) {
        return lerp(FlowLook.SHEET_H0, FlowLook.SHEET_H1, clamp01(wp * smooth(0, 1, progress) + (1 - wp) * pressure01));
    }

    /** How many sheets stand at a progress. */
    static int sheetCount(double progress) {
        return Math.min(FlowLook.SHEETS_MAX, FlowLook.SHEETS_START + (int) Math.floor(clamp01(progress) * (FlowLook.SHEETS_MAX - FlowLook.SHEETS_START + .999)));
    }

    // ------------------------------------------------------------------ arms

    /** One arm: its own start angle, winding, rim width, ends along the arm (u: 0 the core, 1 the rim), speed multiplier and curl exponent. */
    record Arm(double phi, double sweep, double rimWidth, double u0, double u1, double spin, double curl) { }

    private static double frac(double x) { return x - Math.floor(x); }

    /** Arm {@code i} (0-based, deterministic): every arm differs in length, width, curl and speed. */
    static Arm arm(int i) {
        double g = frac(i * .6180339887 + .13), h = frac(i * .7548776662 + .41), k = frac(i * .5698402909 + .77);
        double phi = (i * 360D / FlowLook.ARMS_MAX) + (g - .5) * 26;
        return new Arm(phi, 150 + 120 * h, lerp(FlowLook.ARM_WIDTH_MIN, FlowLook.ARM_WIDTH_MAX, k), .02 + .1 * g, .78 + .22 * h, .8 + .4 * k, .85 + .5 * frac(g + h));
    }

    /** Position of an arm's centre line at u, for the current rotation angle (degrees) and rim radius; {@code out = {x, y, z, radius, angle}}. */
    static void armPoint(Arm a, double u, double rotDeg, double rim, double[] out) {
        double r = FlowLook.VORTEX_RIN + (rim - FlowLook.VORTEX_RIN) * Math.pow(u, 1.1);
        double ang = Math.toRadians(a.phi() + rotDeg * a.spin() + a.sweep() * Math.pow(1 - u, a.curl()));
        out[0] = Math.sin(ang) * r;
        out[2] = Math.cos(ang) * r;
        out[1] = FlowLook.VORTEX_Y0 + FlowLook.VORTEX_RISE * clamp01((r - FlowLook.VORTEX_RIN) / Math.max(.1, rim - FlowLook.VORTEX_RIN));
        out[3] = r;
        out[4] = ang;
    }

    /** Width of an arm at u (blocks, full width): narrow at the core, rim width at the rim, tapering to nothing at both ends. */
    static double armWidth(Arm a, double u, double widthScale) {
        double span = Math.max(1e-6, a.u1() - a.u0());
        double x = clamp01((u - a.u0()) / span);
        double taper = smooth(0, .12, x) * (1 - smooth(.86, 1, x));
        return a.rimWidth() * widthScale * (FlowLook.ARM_CORE_WIDTH + (1 - FlowLook.ARM_CORE_WIDTH) * Math.pow(u, .9)) * taper;
    }

    // ------------------------------------------------------------------ events

    /** Share of the rotation that is left during a mistake, {@code t} seconds after it: continuous, 1 before, the stall, back to 1. */
    static double stall(double t) {
        if (t <= 0) return 1;
        double p = smooth(0, .3, t) * (1 - smooth(FlowLook.STALL_TIME * .5, FlowLook.STALL_TIME, t));
        return 1 - (1 - FlowLook.STALL_SHARE) * p;
    }

    /** Radius multiplier of the one damped shudder after a mistake: 1 at t = 0, a decaying wave of FlowLook.SHUDDER_HZ (below 2 Hz). */
    static double shudder(double t) {
        if (t <= 0) return 1;
        return 1 - FlowLook.SHUDDER_DEPTH * Math.exp(-FlowLook.SHUDDER_DAMP * t) * Math.sin(2 * Math.PI * FlowLook.SHUDDER_HZ * t);
    }

    /** Envelope of a ripple head along an arm (u from 0 to 1 over FlowLook.NODE_RIPPLE seconds): position of the head. */
    static double rippleHead(double age) { return smooth(0, FlowLook.NODE_RIPPLE, age); }

    /** Brightening of the whole arm after a node: 1 at the peak, in over 0.15 s, out by FlowLook.ARM_BRIGHTEN. */
    static double armBrighten(double age) {
        if (age < 0) return 0;
        return smooth(0, .15, age) * (1 - smooth(.15, FlowLook.ARM_BRIGHTEN + .15, age));
    }

    // ------------------------------------------------------------------ finale and burst timeline (seconds after the end of the stream)

    /** Contraction of the vortex in a clean finale: 0 before, 1 after FlowLook.FIN_CONTRACT. */
    static double contraction(double te) { return smooth(0, FlowLook.FIN_CONTRACT, te); }

    /** The moment the radial burst of the clean finale starts. */
    static double burstAt() { return FlowLook.FIN_CONTRACT + FlowLook.FIN_BURST_AFTER; }

    /** The moment the body's lift starts (seconds after the end): the core has grown. */
    static double liftAt() { return FlowLook.FIN_CONTRACT + FlowLook.FIN_BALL; }

    /** Growth of the black ball (0..1) in the clean finale (it forms during FIN_BALL) and in the backlash (BURST_BALL). */
    static double ball(double te, boolean burst) {
        double t0 = burst ? 0 : FlowLook.FIN_CONTRACT, len = burst ? FlowLook.BURST_BALL : FlowLook.FIN_BALL;
        return smooth(t0, t0 + len, te);
    }

    /** Thrown outward share (0..1) of arms and sheets in the backlash. */
    static double thrown(double te) { return smooth(FlowLook.BURST_BALL, FlowLook.BURST_BALL + FlowLook.BURST_THROW, te); }

    /** The pale brightening of the finale (alpha multiplier 0..1): up over BRIGHT_UP, down to FlowLook.BRIGHT_TIME; 0 outside. */
    static double brightening(double te) {
        double t = te - burstAt();
        if (t <= 0 || t >= FlowLook.BRIGHT_TIME) return 0;
        return smooth(0, FlowLook.BRIGHT_UP, t) * (1 - smooth(FlowLook.BRIGHT_UP, FlowLook.BRIGHT_TIME, t));
    }

    /** When (seconds after the end) the effect is over for each ending: 0 clean, 1 backlash, 2 other. */
    static double effectEnd(int ending) { return ending == 0 ? FlowLook.END_DONE : (ending == 1 ? FlowLook.END_BURST : FlowLook.END_OTHER); }

    /** Fade of a channel sample for the "from the hands inward" fade: {@code hand} 0 at a palm .. 1 farthest, {@code f} the fade clock 0..1. */
    static double handFade(double f, double hand) {
        double a = hand * .6;
        return 1 - smooth(a, a + .4, f);
    }

    // ------------------------------------------------------------------ camera keys

    /** Distance of the front shot (blocks): the slow push-in during the run, the pull-back over FlowLook.CAM_PULL_TIME of the finale contraction. */
    static double cameraDistance(double progress, int ending, double te) {
        double run = lerp(FlowLook.CAM_START, FlowLook.CAM_END, smooth(0, 1, progress));
        if (ending == 0 && te > 0) return lerp(run, FlowLook.CAM_PULL, smooth(.4, .4 + FlowLook.CAM_PULL_TIME, te));
        return run;
    }

    static double cameraPitch(int ending, double te) {
        if (ending == 0 && te > 0) return lerp(FlowLook.CAM_PITCH_RUN, FlowLook.CAM_PITCH_PULL, smooth(.4, .4 + FlowLook.CAM_PULL_TIME, te));
        return FlowLook.CAM_PITCH_RUN;
    }

    /** The one damped nudge at the burst (blocks of focus height), {@code t} seconds after the black ball: 0 at t = 0. */
    static double cameraNudge(double t) {
        if (t <= 0) return 0;
        return FlowLook.CAM_NUDGE * Math.exp(-5 * t) * Math.sin(2 * Math.PI * 1.4 * t) * 2;
    }
}
