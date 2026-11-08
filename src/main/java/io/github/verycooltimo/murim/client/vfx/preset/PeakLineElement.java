package io.github.verycooltimo.murim.client.vfx.preset;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static java.lang.Math.*;
import static io.github.verycooltimo.murim.client.vfx.preset.EssenceMath.*;
import static io.github.verycooltimo.murim.client.vfx.preset.EssenceMath.clamp;
import static io.github.verycooltimo.murim.client.vfx.preset.EssencePreset.*;

/** Port of lab-bt_peak.js btpRibbons/btpRibbonAt/btpOrbAt/btpOrbTrail.
 * Pure lab coordinates; the renderer supplies the player frame, never scene state.
 */
public final class PeakLineElement {
    private static final double[] W={.2,.45,.65,.8,.92,1,1,.95,.8};
    private static final float[] PHI=new float[8001];
    static { double a=0;for(int i=0;i<=8000;i++){PHI[i]=(float)a;a+=rate(i*.005)*.005;} }
    public record Head(double x,double y,double depth) { }
    public record Ribbon(Layer layer,List<Double> depth) { }
    private final boolean thin;
    private final double[] row;
    private final int index;
    private final String palette;
    private boolean full;
    private final Layer fist;
    public PeakLineElement(Layer fist,boolean thin) { this(fist,thin?ROWS[3]:ROWS[0],thin?5:2,thin?"rose":"pink");this.full=false; }
    PeakLineElement(Layer fist,double[] row,int index,String palette) {
        this.fist=fist;this.row=row;this.index=index;this.palette=palette;this.thin=row[0]<.15;this.full=true;
    }
    // width, start, yc, R, e, tilt, w, theta, span, release offset, hue0, hue1, twin amplitude, twin phase
    static final double[][] ROWS={
      {.32,13.8,300,300,.26,.35,1,3.44,2.5,.21,.9,1.06,12,.3},
      {.28,14.2,480,330,.22,-.5,.85,3.64,2.4,.19,.04,.15,12,1.6},
      {.2,14.6,280,255,.3,-.28,1.2,3.29,2.2,.13,.5,.64,0,0},
      {.11,15.4,360,215,.33,.3,1.7,3.24,1.8,.07,.86,.98,0,0},
      {.2,16.4,560,345,.18,.55,.75,3.74,2.5,.15,.28,.45,10,.7},
      {.18,17.2,460,375,.2,-.25,.65,3.64,2.2,.14,.55,.66,0,0},
      {.13,18,390,275,.3,.6,1.35,3.24,2,.08,.72,.86,0,0},
      {.12,18.8,340,315,.24,-.35,1.1,3.39,2.2,.1,.44,.56,0,0},
      {.22,19.6,430,290,.28,-.6,.95,3.54,2.4,.17,.98,1.1,10,2.1},
      {.12,20.6,300,230,.32,.5,1.6,3.29,1.8,.06,.18,.3,0,0},
      {.26,21.8,520,380,.2,.3,.6,3.74,2.3,.2,.06,.14,0,0},
      {.16,23,400,245,.3,-.45,1.5,3.34,1.9,.11,.62,.76,0,0}};
    /** Lab plate floor, independent of the animated player's dantian height. */
    public static double blocksX(double normalizedX) {return normalizedX*350/360;}
    public static double blocksY(double normalizedY) {return (45+normalizedY*350)/360;}
    public boolean hasTwin(){return row[12]>0;}
    public double orbitRadius() { return row[3]; }
    public double start() { return row[1]; }
    private static double ramp(double t){return smooth(19,25.2,t);}
    private static double press(double t){return smooth(24.1,25.38,t);}
    private static double rate(double t){return 1+2.2*pow(ramp(t),2)+3.3*press(t);}
    private static double phi(double t){double f=clamp(t/.005,0,7999.999);int i=(int)floor(f);return mix(PHI[i],PHI[i+1],f-i);}
    private double release(){return 25.5+row[9];}
    private Head head(double t) {
        double t0=start(),yc=row[2],r=row[3],e=row[4],tilt=row[5],w=row[6],th0=row[7];
        double E=1-pow(1-clamp((t-t0)/1.4,0,1),3),ramp=ramp(t),th=th0+w*(phi(t)-phi(t0));
        double R=r*(.5+.5*E)*(1-.08*press(t));
        yc=(t<t0?-260-400*(t0-t):-260+(yc+260)*E)+18*sin(.6*t+th0)*(1+ramp);
        double tl=tilt*(1+.3*ramp)+.06*sin(.9*t+th0)*(1+1.5*ramp),X=R*cos(th),Y=R*e*sin(th);
        return new Head(400+X*cos(tl)-Y*sin(tl),yc+X*sin(tl)+Y*cos(tl),sin(th));
    }
    public Head at(double t) {
        if(t<=release())return head(t);
        Head h=head(release()),h0=head(release()-.01);double q=t-release(),ox=h.x()-400,oy=h.y()-330,ol=hypot(ox,oy);if(ol==0)ol=1;
        return new Head(h.x()+(h.x()-h0.x())*100*q+450*ox/ol*q*q,
                h.y()+(h.y()-h0.y())*100*q+(450*oy/ol-350)*q*q,h.depth());
    }
    public List<Head> trail(double t) {
        double span=row[8],w=row[6],T=min(span/w,1.2*span/(w*rate(min(t,release())))),q=max(0,t-release()),e=900*q+2400*q*q;
        List<Head> out=new ArrayList<>();
        for(int k=0;k<16;k++) {Head h=at(t-T*(1-k/15.0));
            // Bench correction: fling the whole tail toward the upper right of the lab plate.
            out.add(new Head(h.x()+e*.9,h.y()-e*.65-500*q*q,h.depth()));}
        return out;
    }
    public List<Ribbon> ribbons(double t) {
        List<Ribbon> out=new ArrayList<>();
        for(int twin=0;twin<(row[12]>0?2:1);twin++) {
            List<Head> trail=trail(t-(twin==1?.15:0));
            Layer base=merged(twin==1);double local=t-base.p("start");if(local<0||local>base.p("dur"))continue;
            List<Point> path=new ArrayList<>();List<Double> depth=new ArrayList<>();
            for(int i=0;i<16;i++) {
                Head h=trail.get(i);double x=h.x(),y=h.y();
                if(twin==1){Head a=trail.get(max(0,i-1)),b=trail.get(min(15,i+1));double dx=b.x()-a.x(),dy=b.y()-a.y(),l=hypot(dx,dy);if(l==0)l=1;
                    double s=i/15.0,o=row[12]*sin(1.7*PI*s+row[13])*(.35+.65*sin(PI*s));x-=dy/l*o;y+=dx/l*o;}
                path.add(new Point((x-400)/350,(540-y)/350));depth.add(h.depth());
            }
            double pop=release()+.14,fade=1-smooth(0,1,(t-pop)/.35),flare=t<pop?0:2.6*exp(-(t-pop)/.06);
            double gain=fade*(1+flare)*(1+.45*ramp(t)+.2*press(t));
            Map<String,Double> p=new HashMap<>(base.params());for(String key:List.of("bodyAmt","coreAmt","haloAmt"))p.put(key,base.p(key)*gain);
            List<Double> wp=new ArrayList<>();double ph=(index+(twin==1?40:0))*1.7;
            for(int j=0;j<=23;j++){double s=j/23.0,f=s*8;int i=min(7,(int)floor(f));double d=depth.get((int)round(s*15));
                wp.add((twin==1?(j==0||j==23?0:pow(sin(PI*s),.45)):1)*mix(W[i],W[i+1],f-i)*(.5+.5*abs(cos(PI*(s-1.1*t)+ph)))*(.72+.33*d)*.8*(1-.25*press(t)));}
            out.add(new Ribbon(new Layer(Map.copyOf(p),base.colors(),List.copyOf(path),List.copyOf(wp),List.of(),List.of(),base.seed()),List.copyOf(depth)));
        }
        return List.copyOf(out);
    }
    private Layer mergedMain,mergedTwin;
    /** Merge order: fist -> look -> norm defaults -> row -> twin overrides. Built once: it depends on the row only. */
    public Layer merged(boolean twin) {
        if(twin){if(mergedTwin==null)mergedTwin=build(true);return mergedTwin;}
        if(mergedMain==null)mergedMain=build(false);return mergedMain;
    }
    private Layer build(boolean twin) {
        Map<String,Double> p=new HashMap<>(fist.params());
        put(p,"glitAmt",0,"threads",0,"fiberAmt",0,"veinAmt",0,"pleat",.25,"foldGlow",.4,"fan",.35,"wisps",2,"oct",3,"contrast",1.15,"nU",6,"nV",2.2,"accAmt",.08,
                "coreAmt",.85,"coreW",.085,"bodyAmt",.42,"centerDim",.15,"rimAmt",.1,"density",.2,"halo",3.2,"haloAmt",.55,"hazeTex",.1,"crisp",.6,"ragged",.35,
                "c2Amt",1,"c2Across",.8,"c2Along",.6,"c2Noise",.1,"pCount",140,"pSize",1.5,"frayHead",.45,"frayTail",.35);
        if(thin)put(p,"coreAmt",1,"coreW",.12,"bodyAmt",.4,"haloAmt",.45,"ragged",.25,"pleat",0,"pCount",50);
        else if(row[0]>=.26)put(p,"ragged",.38,"bodyAmt",.5,"coreAmt",.65,"haloAmt",.6,"pCount",90);
        PresetDefaults.PARAMS.forEach(p::putIfAbsent);
        int index=this.index+(twin?40:0);double start=start()+(twin?.15:0),end=release()+.14+.35;
        put(p,"paletteMode",5,"arcLine",0,"behind",0,"depth",0,"noiseSeed",20+index*3,"start",start,"dur",end-start,"inDur",.05,"easeIn",1.8,
                "outDur",.35,"outMode",2,"dir",0,"width",row[0]*(twin?.5:1),"head",.22,"tail",.18,"tintStart",0);
        put(p,"easeIn",1,"pCount",0,"paletteMode",5,"paletteStart",row[10]+(twin?.08:0),"paletteEnd",row[11]+(twin?.08:0));
        if(twin&&!full)put(p,"haloAmt",.15,"wisps",0);
        if(twin)put(p,"coreAmt",.45,"twin",1,"goldTint",this.index==2?.9:0);
        String name=twin?(index==42?"gold":index==46?"teal":"pink"):palette;
        String[] c=COLORS.get(name);
        if(twin&&this.index==2)c=new String[]{"#fff6d8","#ffc45a","#ffc45a","#e0401a"};
        Map<String,String> colors=Map.of("core",c[0],"body",c[1],"col2",c[2],"edge",c[3],"veinCol",c[0],"spineCol",c[0],"smokeCol",c[3]);
        return new Layer(Map.copyOf(p),colors,fist.path(),List.of(),List.of(),List.of(),20+index*3);
    }
    static final Map<String,String[]> COLORS=Map.ofEntries(
      Map.entry("pink",new String[]{"#ffe8f8","#e0369c","#ff8a30","#8a3cff"}),
      Map.entry("gold",new String[]{"#fff6d8","#ffa020","#ff3d7a","#e0401a"}),
      Map.entry("cold",new String[]{"#e8f8ff","#3a66ff","#20d8ff","#9a4dff"}),
      Map.entry("rose",new String[]{"#ffe8f0","#ff3d8a","#ff9ac8","#c0306a"}),
      Map.entry("green",new String[]{"#e6ffee","#18c870","#ffd83a","#12a8c0"}),
      Map.entry("sky",new String[]{"#e8f6ff","#2a9cff","#7fd4ff","#2a5ab8"}),
      Map.entry("violet",new String[]{"#f3e8ff","#8a4dff","#ff5ab0","#3a66ff"}),
      Map.entry("teal",new String[]{"#e0fffa","#14d6b8","#4a8cff","#1a5f8a"}),
      Map.entry("warm",new String[]{"#fff4d8","#ff4f3a","#ffc02e","#e8307a"}),
      Map.entry("lime",new String[]{"#f4ffe0","#9adf20","#e8ff6a","#3a9a20"}),
      Map.entry("amber",new String[]{"#fff4d8","#ff9a10","#ffd040","#c05010"}),
      Map.entry("indigo",new String[]{"#eceaff","#4a3cff","#9a8cff","#2a1a9a"}));
    private static void put(Map<String,Double> p,Object... values){for(int i=0;i<values.length;i+=2)p.put((String)values[i],((Number)values[i+1]).doubleValue());}
}
