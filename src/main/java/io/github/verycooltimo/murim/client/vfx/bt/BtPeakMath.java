package io.github.verycooltimo.murim.client.vfx.bt;

import java.util.List;
import static io.github.verycooltimo.murim.client.vfx.bt.BtMath.*;

/** Literal btpOrbHead/At/Trail, spin integral, hue and jolt timing. All positions stay in lab pixels. */
public final class BtPeakMath {
    public static final double BLOW=25.5;
    public record Point(double x,double y,double depth) {}
    public record Essence(String palette,double width,double t0,double yc,double radius,double flatness,double tilt,double speed,double angle,double span,double pop,double hue0,double hue1,double twinAmplitude,double twinPhase) {
        public double release(){return BLOW+pop;}
    }
    public static final List<Essence> ESSENCES=List.of(
        new Essence("pink",.32,13.8,300,300,.26,.35,1,3.44,2.5,.21,.9,1.06,12,.3),
        new Essence("gold",.28,14.2,480,330,.22,-.5,.85,3.64,2.4,.19,.04,.15,12,1.6),
        new Essence("cold",.2,14.6,280,255,.3,-.28,1.2,3.29,2.2,.13,.5,.64,0,0),
        new Essence("rose",.11,15.4,360,215,.33,.3,1.7,3.24,1.8,.07,.86,.98,0,0),
        new Essence("green",.2,16.4,560,345,.18,.55,.75,3.74,2.5,.15,.28,.45,10,.7),
        new Essence("sky",.18,17.2,460,375,.2,-.25,.65,3.64,2.2,.14,.55,.66,0,0),
        new Essence("violet",.13,18.0,390,275,.3,.6,1.35,3.24,2,.08,.72,.86,0,0),
        new Essence("teal",.12,18.8,340,315,.24,-.35,1.1,3.39,2.2,.1,.44,.56,0,0),
        new Essence("warm",.22,19.6,430,290,.28,-.6,.95,3.54,2.4,.17,.98,1.1,10,2.1),
        new Essence("lime",.12,20.6,300,230,.32,.5,1.6,3.29,1.8,.06,.18,.3,0,0),
        new Essence("amber",.26,21.8,520,380,.2,.3,.6,3.74,2.3,.2,.06,.14,0,0),
        new Essence("indigo",.16,23.0,400,245,.3,-.45,1.5,3.34,1.9,.11,.62,.76,0,0));
    private static final double[] PHI=new double[8001];
    static {for(int i=1;i<PHI.length;i++)PHI[i]=PHI[i-1]+spinRate((i-1)*.005)*.005;}
    public static double ramp(double t){return smooth(19,25.2,t);}
    public static double press(double t){return smooth(BLOW-1.4,BLOW-.12,t);}
    public static double spinRate(double t){return 1+2.2*Math.pow(ramp(t),2)+3.3*press(t);}
    public static double phi(double t){double f=Math.max(0,Math.min(7999.999,t/.005));int i=(int)f;return PHI[i]+(PHI[i+1]-PHI[i])*(f-i);}
    public static double haloScale(double t){double s=BLOW+.06,k=(.62+.38*smooth(12.6,14.8,t))*(1-.45*press(t));return t>s?k+(.98-k)*(1-Math.exp(-(t-s)/.1)):k;}
    public static Point head(Essence o,double t) {
        double e=1-Math.pow(1-clamp((t-o.t0)/1.4),3),ramp=ramp(t),th=o.angle+o.speed*(phi(t)-phi(o.t0));
        double r=o.radius*(.5+.5*e)*(1-.08*press(t));
        double yc=(t<o.t0?-260-400*(o.t0-t):-260+(o.yc+260)*e)+18*Math.sin(.6*t+o.angle)*(1+ramp);
        double tilt=o.tilt*(1+.3*ramp)+.06*Math.sin(.9*t+o.angle)*(1+1.5*ramp),x=r*Math.cos(th),y=r*o.flatness*Math.sin(th);
        return new Point(400+x*Math.cos(tilt)-y*Math.sin(tilt),yc+x*Math.sin(tilt)+y*Math.cos(tilt),Math.sin(th));
    }
    public static Point at(Essence o,double t) {
        if(t<=o.release())return head(o,t);
        Point h=head(o,o.release()),h0=head(o,o.release()-.01);double q=t-o.release(),ox=h.x-400,oy=h.y-330,l=Math.max(1e-12,Math.hypot(ox,oy));
        return new Point(h.x+(h.x-h0.x)*100*q+450*ox/l*q*q,h.y+(h.y-h0.y)*100*q+(450*oy/l-350)*q*q,h.depth);
    }
    public static Point[] trail(Essence o,double t,int n) {
        double duration=Math.min(o.span/o.speed,1.2*o.span/(o.speed*spinRate(Math.min(t,o.release()))));
        Point[] p=new Point[n];double q=Math.max(0,t-o.release()),e=900*q+2400*q*q;
        for(int k=0;k<n;k++){Point h=at(o,t-duration*(1-k/(double)(n-1)));double dx=h.x-400,dy=h.y-330,l=Math.max(1e-12,Math.hypot(dx,dy));p[k]=new Point(h.x+dx/l*e,h.y+dy/l*e-500*q*q,h.depth);}
        return p;
    }
    private static final double[] ORBW={.2,.45,.65,.8,.92,1,1,.95,.8};
    public static double width(double s,double t,double phase,double depth){double f=clamp(s)*8;int i=Math.min(7,(int)f);return (ORBW[i]*(1-(f-i))+ORBW[i+1]*(f-i))*(.5+.5*Math.abs(Math.cos(Math.PI*(s-1.1*t)+phase)))*(.72+.33*depth)*.8*(1-.25*press(t));}
    public static int hue(double h){h=h-Math.floor(h);int rgb=0;for(double o:new double[]{1,2.0/3,1.0/3})rgb=(rgb<<8)|(int)Math.round(255*clamp(Math.abs(((h+o)%1)*6-3)-1));return rgb;}
    public static final double[][] JOLT1={{4.4,.12},{5,.25},{5.7,.38},{6.3,.5},{6.9,.62},{7.6,.74},{8.3,.86},{9,.95},{9.6,1}};
    public static final double[][] JOLT2={{20.4,.18},{20.9,.34},{21.4,.5},{22,.64},{22.5,.76},{23.1,.88},{23.8,1}};
    /** Time at which the front reaches fraction f (lab btpJolt). */
    public static double jolt(double[][] front,double f){double p=0;for(double[] row:front){if(f<=row[1])return row[0]+.07*(f-p)/Math.max(1e-6,row[1]-p);p=row[1];}return front[front.length-1][0];}
    public static double front(double[][] jolts,double t){double previous=0;for(double[] row:jolts){if(t<row[0])return previous;if(t<row[0]+.07)return previous+(row[1]-previous)*(t-row[0])/.07;previous=row[1];}return previous;}
    /** BTP_RC second colours, including the explicitly named twin palettes in table D. */
    public static int secondColour(String palette,boolean twin) {
        if(twin)palette=switch(palette){case "pink"->"gold";case "gold","warm"->"pink";case "green"->"teal";default->palette;};
        return switch(palette) {
            case "warm"->0xffc02e;case "gold"->0xff3d7a;case "pink"->0xff8a30;
            case "violet"->0xff5ab0;case "cold"->0x20d8ff;case "green"->0xffd83a;
            case "teal"->0x4a8cff;case "rose"->0xff9ac8;case "sky"->0x7fd4ff;
            case "lime"->0xe8ff6a;case "amber"->0xffd040;case "indigo"->0x9a8cff;
            default->throw new IllegalArgumentException("unknown essence palette: "+palette);
        };
    }
    /** Literal ESS / THICK / THIN material values in lab-bt_peak.js, not an opacity budget. */
    public record Material(double body,double core,double coreWidth,double halo,double ragged) {}
    public static Material material(double width) {
        return width>=.26?new Material(.50,.65,.085,.60,.38)
                :width<=.13?new Material(.40,1,.12,.45,.25):new Material(.42,.85,.085,.55,.35);
    }
    public static double bodyAlpha(Material m,double u,double s,double phase) {
        double edge=1-m.ragged()*(.5+.5*Math.sin(s*31+phase+u*3));
        return m.body()*(1-smooth(0,edge,Math.abs(u)));
    }
    public static double coreAlpha(Material m,double u) {return m.core()*Math.exp(-Math.pow(u/m.coreWidth(),2));}
    public static double glowAlpha(Material m,double u) {return m.halo()*.3*(1-smooth(0,3.2,Math.abs(u)));}
    /** Compress all RGB channels together; never clamp them independently and whiten a highlight. */
    public static double[] compress(double r,double g,double b) {
        double scale=Math.max(1,Math.max(r,Math.max(g,b)));
        return new double[]{r/scale,g/scale,b/scale};
    }

    private BtPeakMath() {}
}
