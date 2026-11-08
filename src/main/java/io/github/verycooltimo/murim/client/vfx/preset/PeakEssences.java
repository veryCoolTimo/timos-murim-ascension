package io.github.verycooltimo.murim.client.vfx.preset;

import java.util.*;
import io.github.verycooltimo.murim.client.vfx.bt.BtCtx;
import static java.lang.Math.*;
import static io.github.verycooltimo.murim.client.vfx.preset.EssenceMath.*;
import static io.github.verycooltimo.murim.client.vfx.preset.EssenceMath.clamp;
import static io.github.verycooltimo.murim.client.vfx.preset.EssencePreset.*;

/** Full lab D table: two cloth arcs, twelve heads and four delayed twins. */
public final class PeakEssences {
    private final List<PeakLineElement> elements=new ArrayList<>();
    private final Layer[] cloth=new Layer[2];
    private static final String[] PALETTES={"pink","gold","cold","rose","green","sky","violet","teal","warm","lime","amber","indigo"};
    private static final double[][] ARCS={{200,385,1,2.2},{112,178,.975,-2.6}};
    public PeakEssences(Layer fist,Layer main) {
        for(int i=0;i<12;i++)elements.add(new PeakLineElement(fist,PeakLineElement.ROWS[i],i+2,PALETTES[i]));
        for(int i=0;i<2;i++) {
            Map<String,Double> p=new HashMap<>(main.params());
            put(p,"c2Amt",.7,"c2Across",.7,"c2Along",.25,"pCount",600,"coreAmt",.5,"coreW",.09,"bodyAmt",.62,"centerDim",.45,"rimAmt",.25,"wisps",5,"threads",0,"foldDetail",.35);
            PresetDefaults.PARAMS.forEach(p::putIfAbsent);
            double start=i==0?12.6:14.3;
            put(p,"paletteMode",0,"arcLine",0,"behind",0,"depth",0,"noiseSeed",20+i*3,"start",start,"dur",25.96-start,"inDur",i==0?2.4:1.6,"easeIn",1.8,"outDur",.4,"outMode",2,"dir",0,"width",i==0?.11:.06,"head",.22,"tail",.18,"tintStart",0);
            put(p,"pCount",i==0?110:60,"bodyAmt",i==0?.13:.09,"coreAmt",i==0?.07:.06,"glitAmt",0,"fiberAmt",0,"haloAmt",.12,"wisps",0,"foldDetail",.2);
            if(i==0)p.put("c2Amt",.6);
            Map<String,String> colors=Map.of("core","#d8f4ff","body","#4b9fe8","col2","#80dfff","edge","#2a5ab8","veinCol","#d8f4ff","spineCol","#d8f4ff","smokeCol","#2a5ab8");
            double[] w=i==0?new double[]{.05,.45,.8,.3,.08,.5,.9,1,.9,.5,.75,.9,.45,.05}:new double[]{.05,.6,1,.7,.3,.05};
            List<Double> profile=new ArrayList<>();for(double v:w)profile.add(v);
            cloth[i]=new Layer(Map.copyOf(p),colors,main.path(),List.copyOf(profile),main.folds(),List.of(),20+i*3);
        }
    }
    public List<Layer> merged() {
        List<Layer> out=new ArrayList<>(18);out.addAll(Arrays.asList(cloth));
        for(var e:elements){out.add(e.merged(false));if(e.hasTwin())out.add(e.merged(true));}
        return List.copyOf(out);
    }
    // Per-tick mesh cache (perf 31.10): the ribbons were the whole CPU cost of the Peak (stand: 109 ms a frame at 24–25 s).
    // Each client tick builds ONE new mesh, for the start of the next tick; frames in between blend the two, so there is no lag.
    private final RecordedMesh atStart=new RecordedMesh(),atEnd=new RecordedMesh();
    private RecordedMesh start=atStart,end=atEnd;
    private long tick=Long.MIN_VALUE;
    public void render(BtCtx c) {
        double t=c.labSeconds();
        long now=(long)Math.floor(t*20);
        long mark=io.github.verycooltimo.murim.client.vfx.bt.BtPerf.now();
        if(now!=tick) {
            if(now==tick+1){RecordedMesh swap=start;start=end;end=swap;}
            else{start.clear();build(at(c,now/20.0),start);}
            end.clear();build(at(c,(now+1)/20.0),end);
            tick=now;
        }
        mark=io.github.verycooltimo.murim.client.vfx.bt.BtPerf.lap(io.github.verycooltimo.murim.client.vfx.bt.BtPerf.BUILD,mark);
        var type=io.github.verycooltimo.murim.client.vfx.MurimRenderTypes.presetBody();
        RecordedMesh.replay(start.vertices()>0?start:null,end,t*20-now,c.buffers().getBuffer(type),c.pose());
        io.github.verycooltimo.murim.client.vfx.bt.BtPerf.lap(io.github.verycooltimo.murim.client.vfx.bt.BtPerf.REPLAY,mark);
        // One preset draw call for all eighteen ribbons, still before the scene's later layers (as the per-ribbon flush was).
        c.buffers().endBatch(type);
        // and once more into the glow target (author 31.10: «не хватает глоу»)
        if(io.github.verycooltimo.murim.client.vfx.bt.BtGlow.active()) {
            var glow=io.github.verycooltimo.murim.client.vfx.bt.BtGlow.preset();
            RecordedMesh.replay(start.vertices()>0?start:null,end,t*20-now,c.buffers().getBuffer(glow),c.pose());
            c.buffers().endBatch(glow);
        }
        for(var e:elements) {
            double pop=e.merged(false).p("start")+e.merged(false).p("dur")-.35;
            if(t>=pop&&t<pop+.9)pop(c,e,pop);
        }
    }
    private static BtCtx at(BtCtx c,double t) { return new BtCtx(c.mc(),c.body(),c.camera(),c.buffers(),c.stack(),t,c.partial()); }
    /** All ribbons at the context's time into a recorder. */
    private void build(BtCtx c,RecordedMesh into) {
        double t=c.labSeconds();
        for(int i=0;i<2;i++) {
            Layer base=cloth[i];if(t<base.p("start")||t>25.96)continue;
            double[] h=ARCS[i];List<Point> path=new ArrayList<>(25);List<Double> depth=new ArrayList<>(25);
            double scale=(.62+.38*smooth(12.6,14.8,t))*(1-.45*smooth(24.1,25.38,t));
            if(t>25.56)scale+=(.98-scale)*(1-exp(-(t-25.56)/.1));
            for(int j=0;j<=24;j++) {
                double th=toRadians(h[0]+(h[1]-h[0])*j/24+h[3]*(t-13));
                double r=285*scale*(1+.03*sin(2*th+1.3*t)+.018*sin(5*th-.9*t+1)+.012*sin(9*th+2.1*t))*h[2];
                path.add(new Point(cos(th)*r/350,(540-328-sin(th)*r)/350));depth.add(-.3);
            }
            double fade=1-smooth(25.56,25.96,t),flare=t<25.56?0:2.6*exp(-(t-25.56)/.06);
            double gain=(1-.5*smooth(20.5,23,t)+.5*smooth(23.9,25.3,t))*fade*(1+flare);
            Map<String,Double> p=new HashMap<>(base.params());for(String key:List.of("bodyAmt","coreAmt","haloAmt"))p.put(key,base.p(key)*gain);
            PeakLineRenderer.ribbon(c,new PeakLineElement.Ribbon(new Layer(p,base.colors(),path,base.widthProfile(),base.folds(),base.veils(),base.seed()),depth),285,false,into);
        }
        for(var e:elements)PeakLineRenderer.render(c,e,false,into);
    }
    private static void pop(BtCtx c,PeakLineElement e,double pop) {
        var ribbon=e.ribbons(pop).getFirst();var points=ribbon.layer().path();
        Point at=points.getFirst();double far=-1;
        for(var p:points){double d=hypot(p.x(),p.y()-(540-200)/350.0);if(d>far){far=d;at=p;}}
        double q=c.labSeconds()-pop;
        int color=io.github.verycooltimo.murim.client.vfx.bt.BtPeakMath.hue(e.merged(false).p("paletteStart"));
        glow(c,at,58,.95*exp(-q/.1),color);glow(c,at,18,exp(-q/.07),0xffffff);
        int count=e.merged(false).p("width")<.15?36:64;
        for(int j=0;j<count;j++) {
            double a=j*2.39996323+e.start(),d=(60+140*fract(sin(j*47+e.start())*43758.5))*(1-exp(-q/.25));
            Point p=points.get(j%points.size());
            Point spark=new Point(p.x()+cos(a)*d/350,p.y()+sin(a)*d/350);
            if(hypot(spark.x(),spark.y()-(540-200)/350.0)<.2)continue;
            glow(c,spark,1.1+2*fract(sin(j*31.7)*43758.5),pow(1-q/.9,1.5),color);
        }
    }
    private static void glow(BtCtx c,Point p,double size,double alpha,int rgb) {
        double yaw=toRadians(c.mc().player.getYRot()),scale=350.0/360;
        var at=new net.minecraft.world.phys.Vec3(c.body().axis().x+cos(yaw)*p.x()*scale,
            c.body().ground()+PeakLineElement.blocksY(p.y()),c.body().axis().z+sin(yaw)*p.x()*scale);
        if(!c.body().faceClear(at))return;
        io.github.verycooltimo.murim.client.vfx.VfxDraw.billboard(c.buffers().getBuffer(io.github.verycooltimo.murim.client.vfx.MurimRenderTypes.mote()),
            c.pose(),at,c.camera(),c.body().px(size),(float)alpha,(rgb>>16&255)/255F,(rgb>>8&255)/255F,(rgb&255)/255F);
    }
    private static void put(Map<String,Double> p,Object... v){for(int i=0;i<v.length;i+=2)p.put((String)v[i],((Number)v[i+1]).doubleValue());}
}
