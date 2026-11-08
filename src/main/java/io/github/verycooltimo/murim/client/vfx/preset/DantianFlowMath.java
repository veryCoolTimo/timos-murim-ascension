package io.github.verycooltimo.murim.client.vfx.preset;

/** Pure motion from docs/design/dantian-flow-lab-r2.js; seconds unless named ticks. */
public final class DantianFlowMath {
    public static final int SEED_TICKS = 176;
    public static double clamp(double x) { return Math.max(0, Math.min(1, x)); }
    public static double radius(double ring, double scale) { return (.03 + .45 * ring) * scale; }
    public static double smoothRadius(double old, double target, double seconds) {
        return target + (old - target) * Math.exp(-seconds / .25);
    }
    public static double speed(int beat, double radius) {
        return beat == 2 ? Math.min(3.2, 1.15 * Math.sqrt(.4 / Math.max(.001, radius))) : 1;
    }
    public static double strain(double strain, boolean inBand) { return clamp(Math.max(strain, inBand ? 0 : .65)); }
    public static double push(double strain, double along) { return .30 * clamp(strain) * clamp(along) * clamp(along); }
    /** Leading half only; shared by displacement, replacement tint and frayed edges. */
    public static double missTint(double strain, double along) {
        return clamp(strain) * clamp((along - .5) / .08);
    }
    public static double recoverStrain(double old, double target, double seconds) {
        return target + (old - target) * Math.exp(-seconds / (target < old ? .5 : .15));
    }
    public static double spiralAngle(double along) { return (clamp(along) - 1) * 3.5 * Math.PI; }
    public static double spiralRadius(double radius, double along) { return radius * (1 - .65 * clamp(along)); }
    public static double ribbonWidth(double radius) { return Math.max(.015, .22 * radius / .45); }
    public static double nextCadence(int beat, boolean inBand, double elapsed, double seconds) {
        return beat == 2 && inBand ? elapsed + seconds : 0;
    }
    public static boolean peel(int beat, boolean inBand, double accumulated) { return beat == 2 && inBand && accumulated >= 1.5; }
    public static double pulse(double ticks) { return 1 + .35 * Math.exp(-Math.pow((ticks - 20) / 5.6, 2)); }
    public static double coil(double ticks) { return 1 - clamp(ticks / 8); }
    public static double ember(double ticks) { return ticks >= 0 && ticks < SEED_TICKS ? 1 : 0; }
    // Author review 1.11: all times below are seconds.
    public static double startReach(double seconds) { return 1 - clamp(seconds / 6); }
    public static double unfurl(double seconds) { return clamp((seconds - 6) / 1.2); }
    public static double wave(double theta, double seconds, double scale) {
        return .06 * scale * Math.sin(3 * theta - 2.4 * seconds);
    }
    public static double floorRadius(double value, int beat, double seconds, double scale) {
        return Math.max(value, (beat == 1 ? .34 : beat == 2 ? .26 * (1 - clamp((seconds - 8) / 2)) : 0) * scale);
    }
    public static double impact(double seconds) { return seconds < 0 ? 0 : 1 - clamp(seconds / .4); }
    public static double darkness(double seconds) {
        return .65 * clamp((seconds - .25) / .25) * DantianVeins.alpha(seconds);
    }
    public static final double SEGMENT_SECONDS = .35;
    public static final double FLASH_SECONDS = .15, CORE_START = FLASH_SECONDS;
    public static final double SWELL_SECONDS = .3, BURST_START = CORE_START + SWELL_SECONDS;
    public static final double EXPLOSION_SECONDS = .4, MERIDIAN_START = BURST_START + EXPLOSION_SECONDS;
    public static final double BURST_SECONDS = .7, TITLE_START = MERIDIAN_START + 10 * SEGMENT_SECONDS;
    public static final double STAMP_SECONDS = .12, TEXT_FADE_SECONDS = .25;
    public static final double TITLE_FULL = TITLE_START + STAMP_SECONDS + TEXT_FADE_SECONDS;
    public static final double RETRACT_START = TITLE_FULL + 2.5;
    public static final double RETRACT_SECONDS = 1.0;
    public static double release(double seconds) {
        // Compare the endpoint before subtracting: cancellation can otherwise leave a residual alpha.
        if (seconds >= RETRACT_START + RETRACT_SECONDS) return 1;
        return clamp((seconds - RETRACT_START) / RETRACT_SECONDS);
    }
    public static double coreGlow(double seconds) {
        return .85 + .15 * Math.cos(2 * Math.PI * (seconds - 1) / 1.2);
    }
    /** Signed camera-facing depth from the body plane; the main pass always keeps depth testing. */
    public static boolean flowOccluded(double depth) { return depth < 0; }
    public static double edgeRim(double depth, double edgeDistance, double scale) {
        return flowOccluded(depth) ? .15 * (1 - clamp(Math.abs(edgeDistance) / (.04 * scale))) : 0;
    }
    public static double flash(double seconds) {
        return seconds < 0 ? 0 : 1-clamp(seconds/FLASH_SECONDS);
    }
    public static double sharedFade(double seconds) { return 1-release(seconds); }
    public static double stampScale(double seconds) {
        double u=clamp((seconds-TITLE_START)/STAMP_SECONDS);
        return 1.6-.6*u*u;
    }
    public static double stampAlpha(double seconds) {
        return clamp((seconds-TITLE_START)/.04)*sharedFade(seconds);
    }
    public static double title(double seconds) {
        return clamp((seconds-TITLE_START-STAMP_SECONDS)/TEXT_FADE_SECONDS)*sharedFade(seconds);
    }
    public static double titleRise(double seconds) { return 0; }
    /** Uniform screen field: no world-plane clipping or silhouette boundary. */
    public static double shadeAlpha(double seconds,double x,double y) { double u=clamp(seconds/.2);
        return .75*u*u*(3-2*u)*sharedFade(seconds); }
    /** Torso, head, both arms, both legs; 0.35 seconds per measured bone segment. */
    private static int segmentOrder(int channel,int segment) {
        return (channel == 0 ? 0 : channel < 3 ? 4 : 7) + segment;
    }
    public static double meridian(double seconds, int channel, int segment) {
        return clamp((seconds - MERIDIAN_START) / SEGMENT_SECONDS - segmentOrder(channel,segment));
    }
    public static double meridianRelease(double seconds,int channel,int segment) {
        return clamp((1-release(seconds))*10-segmentOrder(channel,segment));
    }
    public static double readableSpiral(double radius,double along,double seconds,double scale) {
        double floor=floorRadius(0,2,seconds,scale);
        return floorRadius(spiralRadius(radius,along),2,seconds,scale)+.06*scale*(1-clamp(along))*(floor/(.26*scale));
    }
    private DantianFlowMath() { }
}
