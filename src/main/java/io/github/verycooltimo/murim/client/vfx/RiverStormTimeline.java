package io.github.verycooltimo.murim.client.vfx;

/** Pure burst samples in node-local attack coordinates; shared by rendering and regression tests.
 * A slot remains a slot: flower slots submit six petals, all other slots submit one. */
final class RiverStormTimeline {
    static final int APEX_SLOTS = 2200;
    record Point(double along, double side, double up, double alpha) { }
    static double seed(int i, int salt) {
        double n = Math.sin(i * 127.1 + salt * 311.7) * 43758.5453;
        return n - Math.floor(n);
    }
    static Point point(int i, double age, double scale) {
        double phase = i * 2.399963;
        double travel = 10 + 12 * (1 - Math.exp(-Math.max(0, age) / 10));
        boolean axial = i % 10 < 7;
        double along = axial ? -.2 + seed(i, 2) * 1.4 : seed(i, 2) * .4;
        double radius = axial ? .16 : .65;
        double alpha = 1 - PlumBeats.smooth((age - 25 - seed(i, 19) * 15) / 10);
        return new Point(along * travel * scale, Math.cos(phase) * radius * travel * scale,
                Math.sin(phase) * radius * travel * scale
                        - Math.pow(Math.max(0, age - 10), 2) * .003, alpha);
    }
    static double halfWidth(double width) { return Math.max(.04, width); }
    /** T100 release (storm age 40); every cohort reaches contact at the server flight time. */
    static double streamProgress(int slot, double tick, double flight) {
        double delay = seed(slot, 1) * Math.min(2, flight * .4);
        return PlumBeats.smooth((tick - 40 - delay) / Math.max(1, flight - delay));
    }
    static int petals(int slot) { return slot % 32 == 0 ? 6 : 1; }
    static boolean expired(int age, int ending, int burst) {
        return ending >= 0 && age - ending > 41 && (burst < 0 || age - burst > 50);
    }
    private RiverStormTimeline() { }
}
