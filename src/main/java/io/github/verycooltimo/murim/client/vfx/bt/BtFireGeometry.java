package io.github.verycooltimo.murim.client.vfx.bt;

import java.util.ArrayList;
import java.util.List;
import static io.github.verycooltimo.murim.client.vfx.bt.BtMath.*;

/** Pure bench geometry inputs ported unchanged from BtRank3.flames; scene implementation is untouched. */
public final class BtFireGeometry {
    public record Flame(double x,double depth,double baseHeight,double height,double halfWidth,double alpha,double seed) {}
    public static List<Flame> at(boolean first,double t,double quality,double knees,double crown) {
        double energy=first?BtRank1Math.level(t).energy():BtFireSim.level(t).energy();
        double fade=BtFireEnvelope.recession(first,t),gain=smooth(0,.35,energy)*fade;
        if(gain<=.001||quality<=0)return List.of();
        double radius=BtFireEnvelope.radius(knees);
        int count=Math.max(1,(int)Math.round(BtFireEnvelope.count(first)*quality));
        List<Flame> out=new ArrayList<>();
        for(int i=0;i<count;i++) {
            double seed=random(i*71+911),angle=i*Math.PI*2/count;
            boolean front=Math.sin(angle)>.3;
            double pulse=.88+.12*Math.sin(t*4+seed*12),half=BtFireEnvelope.halfWidth(first,seed);
            double x=Math.cos(angle)*Math.max(0,radius-half*1.3-.03);
            double baseHeight=front?.025:.04+.25*random(i*71+912);
            double height=BtFireEnvelope.height(front,seed,pulse,crown,baseHeight);
            if(first)height*=fade;
            double ember=first&&i>2?1-smooth(9,10.5,t):1;
            out.add(new Flame(x,front?.19:-.28,baseHeight,height,half*gain*ember,
                    gain*ember*(front?.25:.72),seed));
        }
        return List.copyOf(out);
    }
    private BtFireGeometry() {}
}
