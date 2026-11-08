package io.github.verycooltimo.murim.client.vfx.bt;

import static io.github.verycooltimo.murim.client.vfx.bt.BtMath.smooth;

/** The First Rate fire, shared by the scene and the isolated fire bench: the lab simulation drawn as the lab's field ({@link BtFireField}). */
public final class BtBenchFire {
    public static double recession(double t) { return 1-smooth(9.5,10.5,t); }
    public static int emberCount(double t) { return t>=10.5&&t<11.5?5:0; }
    /** Author 31.10: the hand-placed lanes looked nothing like the lab; the simulation itself is drawn as the lab's fire field. */
    static void render(BtCtx c,BtFireSim sim) {
        BtFireField.render(c,sim,BtFireField.BLUE);
    }
    private BtBenchFire() {}
}
