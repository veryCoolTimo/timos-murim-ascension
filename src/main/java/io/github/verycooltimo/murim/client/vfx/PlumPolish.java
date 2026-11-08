package io.github.verycooltimo.murim.client.vfx;

/** 7 November art-review geometry, in blocks. No star conversion or combat clocks. */
final class PlumPolish {
    static int pigment(int slot, boolean scatter) {
        int n = Math.floorMod(slot, 20);
        return n < 13 ? 0 : n < 18 ? (scatter ? 2 : 1) : (scatter ? 1 : 2);
    }
    static int seaSlots() { return 1000; }
    static double seaHeight(int slot, double phase) {
        return new double[]{.2, .7, 1.3}[Math.floorMod(slot, 3)]
                + (.15 + .15 * Math.sin(phase));
    }
    // Integer thousandths keep the maximum exactly .22, without additive rounding overshoot.
    static double crestSpeed(int crest) { return (80 + 14 * Math.floorMod(crest * 7, 11)) / 1000.; }
    static double crestRadius(int crest, double tick) {
        return 1 + ((tick * crestSpeed(crest) + crest * 1.63) % 8);
    }
    static int strikeDelay(int clone) { return Math.floorMod(clone, 2); }
    static boolean headClear(double distanceSquared, double radius) {
        return distanceSquared >= (.6 + radius) * (.6 + radius);
    }
    private PlumPolish() {}
}
