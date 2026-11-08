package io.github.verycooltimo.murim.client.vfx.bt;

import static io.github.verycooltimo.murim.client.vfx.bt.BtMath.*;

/** Physical render limits from round-8 feedback and lab-bt_third.js / AuraRenderer#front. */
public final class BtFireEnvelope {
    private BtFireEnvelope() {}
    public static int count(boolean first) { return first?16:18; }
    public static double radius(double kneeSpan) { return Math.min(.95,Math.max(0,kneeSpan)*.8); }
    public static double halfWidth(boolean first,double seed) { return first?.05+.07*seed:.06+.08*seed; }
    public static double recession(boolean first,double t) { return first?1-smooth(9,11,t):1-smooth(13.4,15.6,t); }
    public static double height(boolean front,double seed,double pulse,double crown,double base) {
        return Math.max(0,Math.min(front?.22+.16*seed:Math.max(.45,(.45+.65*seed)*pulse),1.3*crown-base));
    }
}
