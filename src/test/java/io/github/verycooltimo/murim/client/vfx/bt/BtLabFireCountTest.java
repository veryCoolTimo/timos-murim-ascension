package io.github.verycooltimo.murim.client.vfx.bt;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Count-only reference transcription of lab-bt_third.js / lab-bt_rank1.js.
 * No production sim, level, contour or random helpers: positions do not affect lifetime or random consumption.
 */
class BtLabFireCountTest {
    @Test void bothSeedsMatchTheLabAtRequestedTimes() {
        for(boolean first:new boolean[]{true,false}) {
            var actual=new BtFireSim(first?BtFireSim.Mode.FIRST:BtFireSim.Mode.THIRD);
            var lab=new Lab(first);
            for(int seconds:new int[]{1,4,8,11}) {
                actual.advance(seconds);lab.advance(seconds*20);
                assertEquals(lab.parts.size(),actual.particles().size(),Math.max(.0,lab.parts.size()*.05),
                        "lab count, first="+first+", seconds="+seconds);
            }
        }
    }
    private static final class Life {
        double age;final int life;
        Life(int life){this.life=life;}
    }
    private static final class Lab {
        final boolean first;int seed,tick;double debt;
        final int[] spots=new int[14];final List<Life> parts=new ArrayList<>();
        Lab(boolean first){this.first=first;seed=first?577:911;}
        double r(){seed=seed*1664525+1013904223;return Integer.toUnsignedLong(seed)/4294967296.0;}
        void advance(int target) {
            while(tick<target) {
                double t=++tick/20.0,control=first?s(8.6,11.6,t):0;
                double gust=s(2.8,4.4,t)*(1-s(8.8,10.4,t))*s(.4,.75,noise(t*1.2,17));
                double tear=s(4.8,6.4,t)*(1-s(8.6,9.6,t));
                double gather=first?(t>=15.2?0:s(14.4,15.15,t)):s(10.1,10.75,t)*(1-s(10.8,10.85,t));
                double e=t-10.8,flare=e>0?(1-Math.exp(-e*14))*Math.exp(-e*1.5):0;
                double energy=first?.95*s(1,3.6,t)*(1+.45*gust)*(1-control)
                        :e>0?.55*(1-s(13.4,15.6,t))+.7*flare:.62*s(6.4,10,t)*(1-.95*gather);
                for(int i=0;i<spots.length;i++)if(--spots[i]<=0) {
                    double angle=r()<.7?(r()<.5?0:Math.PI)+(r()-.5)*1.5:r()*6.283;
                    if(Math.sin(angle)>.3&&r()<.6)angle+=Math.PI;
                    double band=r();r(); // f: all three branches consume one random draw.
                    spots[i]=6+(int)(r()*11);
                }
                debt+=30*energy+(!first&&tick==216?170:0);
                while(debt>=1) {
                    debt--;
                    if(!first&&tick==216){r();r();r();}else r(); // spot or burst angle/fraction
                    r();r();r(); // angle jitter, row jitter, radial jitter
                    boolean fly=first&&r()<tear*(.05+.3*gust);
                    r();r();boolean longer=r()<(first?.1:.12); // up, out, long
                    r();if(!fly)r(); // size and occasional larger tongue
                    int life=fly?12+(int)(r()*7):longer?22+(int)(r()*10):11+(int)(r()*11);
                    r(); // particle seed
                    parts.add(new Life(life));
                }
                for(var p:parts)p.age+=1+3*gather+.5*control;
                parts.removeIf(p->p.age>=p.life);
            }
        }
    }
    private static double s(double a,double b,double t){double u=Math.max(0,Math.min(1,(t-a)/(b-a)));return u*u*(3-2*u);}
    private static double hash(double n,double seed){double x=Math.sin(n*127.1+seed*31.7)*43758.5453;return x-Math.floor(x);}
    private static double noise(double t,double seed){double i=Math.floor(t),u=s(0,1,t-i);return hash(i,seed)*(1-u)+hash(i+1,seed)*u;}
}
