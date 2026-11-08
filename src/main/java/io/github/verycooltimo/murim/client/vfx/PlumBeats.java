package io.github.verycooltimo.murim.client.vfx;

/** Pure presentation time, in ticks since the form's visual start. No combat timing lives here. */
final class PlumBeats {
    record Beat(int formed, int strike, int end) {
        double gather(double tick) { return smooth(tick / formed); }
        double attack(double tick) { return smooth((tick - strike) / 8); }
        boolean holding(double tick) { return tick >= formed && tick < strike; }
    }
    static final Beat SEA = new Beat(20, 40, 90);
    // River visuals start at T60; the authoritative release remains T100.
    static final Beat RIVER = new Beat(20, 40, 76);
    // Rain starts at MARK T66; cohorts still contact at T144/147/150.
    static final Beat RAIN = new Beat(46, 78, 134);
    static final Beat BLOOM = new Beat(28, 52, 100);
    static final Beat SCATTER = new Beat(20, 40, 108);

    static double smooth(double value) {
        double u = Math.max(0, Math.min(1, value));
        return u * u * (3 - 2 * u);
    }
    static double nearFade(double distance) { return smooth((distance - 2) / 1.5); }
    private PlumBeats() { }
}
