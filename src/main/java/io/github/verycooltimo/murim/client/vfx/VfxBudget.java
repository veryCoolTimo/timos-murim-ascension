package io.github.verycooltimo.murim.client.vfx;

/**
 * One ceiling on the particles that the mod's own simulations keep alive at once. Each effect still has its own local cap;
 * this is the limit over all of them, so that many auras and a breakthrough together cannot add up without bound.
 */
public final class VfxBudget {
    /** Live particles over all owners. */
    public static final int TOTAL = 2400;

    private static int rain;
    private static int aura;
    private static int breakthrough;

    private VfxBudget() {
    }

    public static void setAura(int live) {
        aura = live;
    }

    public static void setBreakthrough(int live) {
        breakthrough = live;
    }

    public static void addAura(int delta) {
        aura += delta;
    }

    static void setRain(int live) { rain = live; }
    static int rainRoom() { return Math.max(0, TOTAL - aura - breakthrough); }

    public static int live() {
        return aura + breakthrough + rain;
    }

    public static boolean hasRoom() {
        return live() < TOTAL;
    }

    static void reset() {
        rain = 0;
        aura = 0;
        breakthrough = 0;
    }
}
