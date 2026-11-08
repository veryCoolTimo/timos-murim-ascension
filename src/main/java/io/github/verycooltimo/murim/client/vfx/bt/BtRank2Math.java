package io.github.verycooltimo.murim.client.vfx.bt;

import java.util.ArrayList;
import java.util.List;
import static io.github.verycooltimo.murim.client.vfx.bt.BtMath.*;

/** Literal br2Make / br2Spin / br2Pull / br2Pos, lab-bt_rank2.js. All distances remain plate pixels. */
public final class BtRank2Math {
    public static final double FREEZE=14.6, BLOW=15.2;
    public enum Kind { SPECK, PUFF, PEBBLE, LEAF }
    public record Item(Kind kind,double ts,double rise,double h,double size,double angle,double radius,double phase,double dr,double spin) {}
    public record Position(double x,double floorY,double height,double angle,double landing,double grow) {
        public boolean front() { return Math.sin(angle)>0; }
    }
    private BtRank2Math() {}
    public static List<Item> make() {
        List<Item> out=new ArrayList<>();
        int[] counts={150,110,88,14};
        for (int kind=0;kind<4;kind++) for(int i=0;i<counts[kind];i++) {
            int seed=12000+kind*1000+i*41;
            double ts,rise,h,size,spin=0; boolean big=r(seed,13)<.1;
            switch(kind) {
                case 0 -> {ts=.9+3.2*r(seed,5);rise=2.5+2*r(seed,6);h=70+70*r(seed,8);size=1.6+1.8*r(seed,9);}
                case 1 -> {ts=1.6+3.6*r(seed,5);rise=3+2*r(seed,6);h=75+45*r(seed,8);size=7+7*r(seed,9);}
                case 2 -> {ts=3.2+4.8*r(seed,5);rise=2.6+2*r(seed,6);h=big?78+12*r(seed,8):80+45*r(seed,8);size=big?20+10*r(seed,9):6+14*r(seed,9)*r(seed,9);spin=(r(seed,12)-.5)*(big?1.4:3.4);}
                default -> {ts=3+4*r(seed,5);rise=3+2*r(seed,6);h=95+45*r(seed,8);size=12+6*r(seed,9);spin=(r(seed,12)-.5)*4;}
            }
            out.add(new Item(Kind.values()[kind],ts,rise,h,size,r(seed,1)*6.283,288+108*Math.pow(r(seed,2),2),r(seed,3)*6.283,r(seed,4),spin));
        }
        return List.copyOf(out);
    }
    private static double r(int seed,int k) { return random(seed+k*7); }
    public static double spin(double t) {
        double tt=Math.min(t,FREEZE),a=0,prevT=5,prevV=0;
        double[][] seg={{9,.4},{12.4,1.1},{14.6,2.9}};
        for(double[] s:seg) { if(tt<=prevT) break; double e=Math.min(tt,s[0]),v=prevV+(s[1]-prevV)*(e-prevT)/(s[0]-prevT);a+=(prevV+v)/2*(e-prevT);prevT=s[0];prevV=s[1]; }
        return a;
    }
    public static double spinRate(double t) {
        if(t<5||t>=FREEZE)return 0;
        double prevT=5,prevV=0;
        for(double[] s:new double[][]{{9,.4},{12.4,1.1},{14.6,2.9}}) {if(t<=s[0])return prevV+(s[1]-prevV)*(t-prevT)/(s[0]-prevT);prevT=s[0];prevV=s[1];}
        return 2.9;
    }
    public static double pull(double t) { return 1-.28*smooth(12.4,14.6,Math.min(t,FREEZE)); }
    /** One plane, y = 252 - .36*z in plate pixels; radius 288..396 gives .30..1.10 blocks. */
    public static double orbitHeight(double radius,double angle) {
        return 252-.36*radius*Math.sin(angle);
    }
    public static double landingTime(double height,double up,double gravity) { return (up+Math.sqrt(up*up+2*gravity*height))/gravity; }
    public static Position position(Item o,double t) {
        double tt=Math.min(t,FREEZE),lift=smooth(o.ts,o.ts+o.rise,tt);
        // Plate Y grows downwards; reverse its angular increment in the right-handed world X/Z plane
        // so the specified orbit is counter-clockwise viewed from above. All rates/magnitudes remain the lab's.
        double th=o.angle-spin(t)*(1+.3*(1-(o.radius-288)/108));
        double rad0=o.radius*pull(t),rad=rad0,land=Double.NaN;
        double h=lift*orbitHeight(rad0,th);
        if(t>=BLOW&&lift>.02) {
            double e=t-BLOW,drag=switch(o.kind){case LEAF->.4;case PUFF->.7;case SPECK->.85;default->1;};
            double v=1.25*(950+750*o.dr)*drag,tau=o.kind==Kind.PUFF?.3:o.kind==Kind.LEAF?.7:.55,up=(110+170*o.dr)*drag,g=o.kind==Kind.LEAF?520:2600;
            double tl=landingTime(h,up,g);land=BLOW+tl;
            // A landed stone has a fixed floor position, rather than continuing to slide asymptotically.
            double travel=o.kind==Kind.PEBBLE?Math.min(e,tl):e;
            rad=rad0+v*tau*(1-Math.exp(-travel/tau));
            if(e<tl)h=Math.max(0,h+up*e-.5*g*e*e);else {double b=e-tl;h=o.kind==Kind.PEBBLE&&b<.16?Math.sin(Math.PI*b/.16)*Math.min(8,o.size*.4):0;}
        }
        double tr=o.kind==Kind.PEBBLE&&lift<.05&&t>o.ts-1.6&&t<BLOW?smooth(o.ts-1.6,o.ts,t)*1.2:0;
        double hum=o.kind==Kind.PEBBLE&&t>=FREEZE&&t<BLOW&&h>2?1:0;
        double wob=o.kind==Kind.LEAF&&h>0?Math.sin(t*3+o.phase)*14:0;
        double grow=Math.min(2,1+Math.max(0,Math.sin(th))*Math.max(0,rad-rad0)/650);
        return new Position(400+Math.cos(th)*rad+wob+Math.sin(t*61+o.phase)*tr+hum*Math.sin(t*90+o.phase)*1.3,
                594+Math.sin(th)*rad*.22,h*grow,th,land,grow);
    }
}
