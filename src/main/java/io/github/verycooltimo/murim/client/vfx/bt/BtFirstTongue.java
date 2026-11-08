package io.github.verycooltimo.murim.client.vfx.bt;

/** Lab silhouette (lab.js renderBr1 fire at 4.5–9 s): an uneven crown of five behind the head, the main mass beside the
 * shoulders and elbows, low wide tongues outside the knees, and only a few low wisps in front. In blocks. */
public record BtFirstTongue(double x,double base,double length,double halfWidth,double depth,boolean front,double seed) {
    public static final int COUNT=22;
    public static BtFirstTongue lane(int lane) {
        double seed=BtMath.random(lane*71+911);
        if(lane<5) {
            double[] xs={-.21,-.09,.02,.13,.24},len={.48,.70,.60,.74,.42};
            return new BtFirstTongue(xs[lane]+.02*(seed-.5),.98+.06*seed,len[lane]+.08*seed,.08+.04*seed,-.26,false,seed);
        }
        if(lane<13) {
            int local=lane-5,ring=local/2;double side=local%2==0?-1:1;
            double[] xs={.38,.47,.56,.64},base={.52,.40,.30,.22},len={.80,.88,.74,.62};
            return new BtFirstTongue(side*(xs[ring]+.02*seed),base[ring]+.04*seed,len[ring]+.10*seed,.085+.045*seed,-.16,false,seed);
        }
        if(lane<19) {
            int local=lane-13,ring=local/2;double side=local%2==0?-1:1;
            double[] xs={.54,.62,.70},len={.62,.52,.42};
            return new BtFirstTongue(side*(xs[ring]+.01*seed),.02+.03*seed,len[ring]+.10*seed,.08+.045*seed,.04,false,seed);
        }
        double[] xs={-.68,.66,-.61};
        return new BtFirstTongue(xs[lane-19],.02,.20+.06*seed,.075+.02*seed,.24,true,seed);
    }
    /** Full physical width; body never tapers before the final third. */
    public static double width(double half,double u,double phase) {
        // BtFirstTongueTest: the .055 side tongue must stay >= .08 blocks wide.
        // Base profile >= .9 and modulation >= .84 give a minimum of .08316.
        return 2*half*BtSpline.flameWidth(u)*(.92+.08*Math.sin(u*9+phase));
    }
}
