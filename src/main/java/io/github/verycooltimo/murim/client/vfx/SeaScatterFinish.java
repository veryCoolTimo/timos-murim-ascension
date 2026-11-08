package io.github.verycooltimo.murim.client.vfx;

/** Pure material limits shared only by Sea and Scatter. Sizes are half widths. */
final class SeaScatterFinish {
    static int cell(int slot) { return Math.floorMod(slot, 3) == 0 ? 4 + Math.floorMod(slot, 4) : Math.floorMod(slot, 4); }
    static int landedLife(int slot) { return 60 + Math.floorMod(slot * 37, 101); }
    static int nearCount(int tier) { return tier < 3 ? 0 : 3 + Math.min(3, (tier - 3) * 3 / 5); }
    static double nearSize(int slot) { return .09 + .11 * Math.floorMod(slot * 17, 31) / 30.0; }
    static double depth(double distance) { return distance > 14 ? .7 : distance > 7 ? .85 : 1; }
    static float envelope(double age, double life) {
        return (float) Math.max(0, Math.min(1, Math.min(age / 3, (life - age) / 8)));
    }
    /** Painted storm + 48 contact cohorts + residue + floor/crest accents; full quality.
     * Existing clone meshes, dust and legacy cuts are outside this material ceiling. */
    static int finishQuads(int tier, boolean sea) {
        if (tier < 3) return 0;
        int t = Math.min(8, tier);
        double density = sea ? new double[]{0, .1, .2, .3, .5, .65, .8, 1, 1.25}[t] / 1.25
                : new double[]{0, .12, .22, .35, .5, .65, .82, 1, 1.2}[t] / 1.2;
        int slots = (int) ((sea ? PlumPolish.seaSlots() : 480) * density);
        return slots * 2 + ((slots + 31) / 32) * 10 + (sea ? ((slots + 12) / 13) * 2 : 0)
                + 48 * 2 * (int) (56 * density) + 128 + 120 + (sea ? nearCount(t) * 2 + 12 : 0);
    }
    private SeaScatterFinish() {}
}
