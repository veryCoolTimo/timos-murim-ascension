package io.github.verycooltimo.murim.client.vfx.bt;

import java.util.List;

/** Shared First Rate tongue-strip fire path; deliberately contains no other scene layers. */
public final class BtFireElement {
    private final BtFireSim fire=new BtFireSim(BtFireSim.Mode.FIRST);
    public void render(BtCtx c) { fire.advance(c.labSeconds());if(BtScenes.quality(c.mc())>0)BtFireField.render(c,fire,BtFireField.BLUE); }
    /** Bench inputs are explicit: inherited MINIMAL particles must not suppress the selected mesh. */
    public void renderBench(BtCtx c) {
        fire.advance(c.labSeconds());
        BtFireField.render(c,fire,BtFireField.BLUE);
    }
    /** Legacy round-8 geometry probe; runtime rendering uses the live lab simulation. */

    public static List<BtFireGeometry.Flame> benchGeometry(double seconds,double knees,double crown) {
        return BtFireGeometry.at(true,seconds,1,knees,crown);
    }
}
