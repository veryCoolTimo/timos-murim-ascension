package io.github.verycooltimo.murim.client.vfx.preset;

import static java.lang.Math.PI;
import static java.lang.Math.min;
import static java.lang.Math.max;
import static java.lang.Math.abs;
import static java.lang.Math.sin;
import static java.lang.Math.cos;
import static java.lang.Math.exp;
import static java.lang.Math.pow;
import static java.lang.Math.floor;
import static java.lang.Math.round;
import static java.lang.Math.hypot;
import static java.lang.Math.acos;
import static java.lang.Math.signum;
import static io.github.verycooltimo.murim.client.vfx.preset.EssenceMath.*;
import io.github.verycooltimo.murim.client.vfx.preset.EssencePreset.Layer;

/** CPU translation of lab.js FS material branches. Derivative AA uses mesh footprint;
 * per-vertex tone mapping replaces the lab's offscreen HDR composite (see notes).
 */
final class EssenceMaterial {
    record RGB(double r,double g,double b) {
        RGB mul(double k) { return new RGB(r*k,g*k,b*k); }
        RGB add(RGB c) { return new RGB(r+c.r,g+c.g,b+c.b); }
        RGB blend(RGB c,double k) { return new RGB(mix(r,c.r,k),mix(g,c.g,k),mix(b,c.b,k)); }
        static RGB hex(String c) { int v=Integer.parseInt(c.substring(1),16); return new RGB((v>>16)/255.0,((v>>8)&255)/255.0,(v&255)/255.0); }
    }
    static final RGB BLACK=new RGB(0,0,0);
    private final Layer l;
    private final RGB body,core,edge,col2,vein,spine,smoke;
    // Layer params read once per material: a map lookup per shade call was a large share of the Peak ribbons cost (31.10).
    private final double P_accAmt,P_accBri,P_accCon,P_accEvo,P_accFlow,P_accScale,P_bodyAmt,P_bright,P_c2Across,P_c2Along,P_c2Amt,P_c2Con,P_c2Noise,P_c2Scale,P_centerDim,P_contrast,P_coreAmt,P_coreOffset,P_coreTint,P_coreW,P_crisp,P_dir,P_evo,P_fan,P_fiberAmt,P_fiberSharp,P_fiberWarp,P_fibers,P_flashAmt,P_flashScale,P_flow,P_foldGlow,P_frayHead,P_frayLen,P_frayTail,P_glitAmt,P_glitCol,P_glitDens,P_glitSize,P_glitTw,P_goldTint,P_haloAmt,P_haloTint,P_hazeTex,P_head,P_hot,P_lumaCon,P_lumaSoft,P_nU,P_nV,P_oct,P_paletteEnd,P_paletteMode,P_paletteStart,P_pleat,P_ragged,P_rimAmt,P_sideFade,P_smokeEvo,P_smokeRise,P_smokeScale,P_smokeSpread,P_smokeThr,P_smokeWarp,P_tail,P_tintStart,P_veinAmt,P_veinScale,P_veinSharp,P_veinWarp,P_width;
    EssenceMaterial(Layer l) {
        this.l=l;body=color("body");core=color("core");edge=color("edge");col2=color("col2");
        vein=color("veinCol");spine=color("spineCol");smoke=color("smokeCol");
        P_accAmt=p("accAmt");P_accBri=p("accBri");P_accCon=p("accCon");P_accEvo=p("accEvo");P_accFlow=p("accFlow");P_accScale=p("accScale");P_bodyAmt=p("bodyAmt");P_bright=p("bright");P_c2Across=p("c2Across");P_c2Along=p("c2Along");P_c2Amt=p("c2Amt");P_c2Con=p("c2Con");P_c2Noise=p("c2Noise");P_c2Scale=p("c2Scale");P_centerDim=p("centerDim");P_contrast=p("contrast");P_coreAmt=p("coreAmt");P_coreOffset=p("coreOffset");P_coreTint=p("coreTint");P_coreW=p("coreW");P_crisp=p("crisp");P_dir=p("dir");P_evo=p("evo");P_fan=p("fan");P_fiberAmt=p("fiberAmt");P_fiberSharp=p("fiberSharp");P_fiberWarp=p("fiberWarp");P_fibers=p("fibers");P_flashAmt=p("flashAmt");P_flashScale=p("flashScale");P_flow=p("flow");P_foldGlow=p("foldGlow");P_frayHead=p("frayHead");P_frayLen=p("frayLen");P_frayTail=p("frayTail");P_glitAmt=p("glitAmt");P_glitCol=p("glitCol");P_glitDens=p("glitDens");P_glitSize=p("glitSize");P_glitTw=p("glitTw");P_goldTint=p("goldTint");P_haloAmt=p("haloAmt");P_haloTint=p("haloTint");P_hazeTex=p("hazeTex");P_head=p("head");P_hot=p("hot");P_lumaCon=p("lumaCon");P_lumaSoft=p("lumaSoft");P_nU=p("nU");P_nV=p("nV");P_oct=p("oct");P_paletteEnd=p("paletteEnd");P_paletteMode=p("paletteMode");P_paletteStart=p("paletteStart");P_pleat=p("pleat");P_ragged=p("ragged");P_rimAmt=p("rimAmt");P_sideFade=p("sideFade");P_smokeEvo=p("smokeEvo");P_smokeRise=p("smokeRise");P_smokeScale=p("smokeScale");P_smokeSpread=p("smokeSpread");P_smokeThr=p("smokeThr");P_smokeWarp=p("smokeWarp");P_tail=p("tail");P_tintStart=p("tintStart");P_veinAmt=p("veinAmt");P_veinScale=p("veinScale");P_veinSharp=p("veinSharp");P_veinWarp=p("veinWarp");P_width=p("width");
    }
    private RGB color(String name) { return RGB.hex(l.colors().get(name)); }
    private double p(String key) { return l.p(key); }
    /** lab-bt_peak.js btpHue / paletteMode 5: hue wraps only after interpolation. */
    static RGB hueRun(double start,double end,double u) {
        double h=mix(start,end,u);h-=floor(h);
        return new RGB(wheel(h+1),wheel(h+2.0/3),wheel(h+1.0/3));
    }
    private static double wheel(double h) { return clamp(abs((h-floor(h))*6-3)-1,0,1); }
    RGB shade(double u,double v,double facing,double side,double t,Trim tr,int mode,
              double gain,double seed,double tint,int columns) {
        double ut=P_dir>.5?1-u:u;
        if(ut<tr.a() || ut>tr.b() || tr.b()<=tr.a())return BLACK;
        double tv=(ut-tr.a())/max(tr.b()-tr.a(),1e-4);
        double tail=mode==4?max(P_tail,.3):P_tail, head=mode==4?max(P_head,.3):P_head;
        double prof=pow(smooth(0,tail,tv),.8)*pow(smooth(0,head,1-tv),.6);
        if(mode==1)prof=min(prof,pow(smooth(0,max(tail,.3),tv)*smooth(0,max(head,.28),1-tv),.85));
        double av=abs(v)/max(prof,.001);if(av>1)return BLACK;
        double ex=t*P_evo*.31,ey=-t*P_evo*.23;
        double n0=fbm(u*P_nU-t*P_flow+seed+ex,v*P_nV+seed+ey,P_oct);
        double n=clamp((n0-.5)*P_contrast*(1+tr.luma()*P_lumaCon)+.5+P_bright,0,1);
        double thr=tr.luma()>0?-.15+tr.luma()*1.2:-1, m=smooth(thr,thr+P_lumaSoft,n0);
        double lim=P_ragged==0?1:1-P_ragged*(1-fbm(u*P_nU*2.3-t*P_flow*1.2+ex+11,v*3+ey+11,3))*.9;
        double soft=mix(.3,.05,P_crisp),edgeA=1-smooth(lim-soft,lim,av);
        if(m*edgeA<=0)return BLACK;
        double cv=v/max(prof,.001),sideFade=mode==2||mode==4?1:mix(1,smooth(-1,.9,cv*signum(P_sideFade)),abs(P_sideFade));
        double c2=fbm(u*P_c2Scale*2-t*P_flow*.3+ex*.3+seed+23,cv*1.1+ey*.3+seed+23,P_oct);
        double gradient=mix(.5,smooth(-.85,.85,cv*side),P_c2Across)+(smooth(.12,.88,u)-.5)*P_c2Along;
        double k2=clamp((gradient+(c2-.5)*P_c2Noise*2-.5)*P_c2Con+.5,0,1)*P_c2Amt;
        k2=clamp(k2+tint+P_tintStart*(1-smooth(.07,.24,u)),0,1);
        RGB bodyC=body.blend(col2,k2),coreC=core.blend(core.blend(spine,smooth(.15,.85,u+(c2-.5)*.4)),P_coreTint);
        double paletteMode=P_paletteMode,pu=mix(P_paletteStart,P_paletteEnd,u);
        RGB palette=bodyC;
        if(paletteMode>4.5 && paletteMode<5.5) {
            palette=hueRun(P_paletteStart,P_paletteEnd,u);
            // Isolated line twin: retain the +.08 hue run under its authored gold body.
            palette=palette.blend(body,P_goldTint);
            bodyC=palette.blend(col2,k2*.18);
            coreC=palette.blend(new RGB(1,1,1),.65);
        } else if(paletteMode>.5 && paletteMode<1.5) {
            palette=new RGB(154/255.0,107/255.0,101/255.0).blend(new RGB(78/255.0,131/255.0,162/255.0).mul(.7),smooth(.05,.30,pu));
            palette=palette.blend(new RGB(168/255.0,212/255.0,223/255.0).mul(.8),smooth(.32,.55,pu));
            palette=palette.blend(new RGB(123/255.0,155/255.0,166/255.0).mul(.7),smooth(.60,.77,pu));
            double peach=smooth(.62,.78,pu)*(1-smooth(.89,.98,pu)),selvedge=smooth(.12,.85,cv)*(.3+.7*peach);
            bodyC=palette.blend(new RGB(.65,.36,.32),selvedge);
            coreC=new RGB(154/255.0,107/255.0,101/255.0).blend(new RGB(213/255.0,237/255.0,241/255.0),smooth(.1,.34,pu)).blend(new RGB(.87,.62,.55),peach*selvedge);
        } else if(paletteMode>2.5) {
            double join=paletteMode<3.5?.43:.34,warm=smooth(join-.12,join+.18,pu);
            palette=new RGB(181/255.0,104/255.0,140/255.0).blend(new RGB(.82,.065,.025),warm);
            bodyC=palette.blend(new RGB(.93,.58,.24),warm*max(0,cv)*.45);
            coreC=new RGB(215/255.0,172/255.0,192/255.0).blend(new RGB(247/255.0,213/255.0,185/255.0).blend(new RGB(228/255.0,194/255.0,148/255.0),smooth(.72,.96,pu)),warm).mul(1+warm*1.35);
            if(paletteMode<3.5) {
                double gold=smooth(.43,.58,pu);
                bodyC=new RGB(186/255.0,89/255.0,124/255.0).mul(.7).blend(new RGB(.72,.055,.018),gold);
                coreC=new RGB(234/255.0,194/255.0,205/255.0).blend(new RGB(245/255.0,212/255.0,171/255.0).mul(2.35),gold);
            } else {
                bodyC=new RGB(.49,.32,.43).blend(bodyC,smooth(.12,.52,pu));
                coreC=new RGB(.67,.50,.62).blend(coreC,smooth(.22,.58,pu));
            }
        } else if(paletteMode>1.5) {
            double warm=smooth(.43,.68,c2+.17*sin(u*17));
            palette=new RGB(1,.66,.10).blend(new RGB(1,.19,.48),smooth(.25,.75,u));
            bodyC=bodyC.blend(palette,warm*.38);
            coreC=new RGB(.55,.86,1).blend(new RGB(1,.48,.72),smooth(-.2,.55,cv)).blend(new RGB(.96,.98,1),exp(-cv*cv*30)*.85);
        }
        RGB materialCore=paletteMode>.5?coreC:core;
        if(mode==11) {
            RGB beam=new RGB(.45,.78,1).blend(new RGB(1,.60,.75),smooth(.15,.65,cv));
            beam=beam.blend(new RGB(.92,.97,1),exp(-pow((cv+.08)/.52,2))*.95);
            return beam.mul(exp(-pow(cv/(.43+.09*sin(u*23)),2))*P_coreAmt).add(new RGB(.045,.19,.85).mul(exp(-pow(cv/.78,2))*P_bodyAmt))
                    .mul(edgeA*m*gain*(.86+.14*noise2(u*45,t*.3)));
        }
        if(mode==10) {
            double x=(v+1)*.5,brightFold=exp(-pow((x-.22-.10*sin(u*19+seed*.1))/.095,2));
            double fine=pow(max(0,1-facing),2),rim=exp(-pow((x-.94)/.025,2));
            return bodyC.blend(col2,smooth(.3,1,x)*P_c2Amt).mul((.25+.65*fine)*(1-av*.55))
                    .add(materialCore.mul(brightFold*max(P_coreAmt,.3)*.3+fine*.4))
                    .add(materialCore.blend(col2,.4).mul(rim*.25)).mul(edgeA*m*gain);
        }
        if(mode==6) {
            double crease=exp(-pow((v+.24)/(.16+max(2.0/columns,.025)),2));
            RGB crest=materialCore.blend(col2,k2*.35);
            if(paletteMode>.5)crest=crest.blend(coreC,paletteMode<1.5?1:.8);
            if(paletteMode>1.5 && paletteMode<2.5)crest=new RGB(.95,.67,.22);
            return crest.mul(crease).add(bodyC.mul(pow(max(0,1-av),.7)*.20)).mul(edgeA*m*gain);
        }
        if(mode==1) {
            double hz=smooth(.3,.8,fbm(u*3-t*P_flow*.3+seed+5,v*1.2+seed+5,4));
            double h=exp(-av*av*7)*(1-smooth(.65,1,av))*P_haloAmt*(.4+.6*n)*mix(1,hz*1.8,P_hazeTex);
            RGB c=body.blend(bodyC,paletteMode>.5&&paletteMode<1.5?.8:.35).blend(edge,max(P_haloTint,P_hot*.95)*(paletteMode>.5?.35:1));
            if(paletteMode>2.5 && paletteMode<4.5)c=new RGB(.62,.16,.31).blend(new RGB(.92,.018,.009),smooth(.35,.66,u));
            if(paletteMode>4.5 && paletteMode<5.5)c=palette;
            return c.mul(h*m*sideFade*gain*pow(prof,.45));
        }
        if(mode==2) { double c=exp(-av*av*3);return body.blend(materialCore,c).mul(c*P_coreAmt*(.4+.6*n)*m*gain); }
        if(mode==4) {
            var xy=path(l,u,t).position();
            double x=(400.0/350+xy.x())*P_smokeScale+seed;
            double y=(540.0/350-xy.y()+v*P_width*.5*P_smokeSpread)*P_smokeScale+t*P_smokeRise+seed;
            double sx=t*P_smokeEvo*.37,sy=-t*P_smokeEvo*.21;
            double dx=(fbm(x*.7+sx+1.7,y*.7+sy+1.7,4)-.5)*P_smokeWarp*2;
            double dy=(fbm(x*.7-sx+9.2,y*.7-sy+9.2,4)-.5)*P_smokeWarp*2;
            double sn=fbm(x+dx+sx,y+dy+sy,5);
            return smoke.mul(mix(.45,1,sn)*smooth(P_smokeThr,P_smokeThr+.22,sn)*exp(-av*av*1.6)*(1-smooth(.45,1,av))*m*gain);
        }
        double w2=cv+P_fiberWarp*(.22*sin(u*31+cv*4)+.08*sin(u*77+cv*9));
        double fanWave=sin(u*17+seed*.23+sin(u*9)*1.2),ridge=pow(max(0,1-facing),1.5)*P_pleat;
        double phase=w2*P_fibers*.5,d=abs(fract(phase+.5)-.5),aa=max(P_fibers/columns,.002),fw=1/max(P_fiberSharp,1);
        double fib=P_fiberAmt==0?0:(1-smooth(fw,fw+aa,d))*fw/(fw+aa)*pow(.5+.5*sin(u*33+w2*13),2);
        double coreV=cv-P_coreOffset-P_ragged*.3*sin(u*19+seed)-P_ragged*.1*sin(u*47-t*.4)-P_fan*.30*fanWave;
        double c=exp(-coreV*coreV/max(P_coreW*P_coreW,1e-4));
        c*=mix(1,.10+2*exp(-pow((u-.34)/.10,2))+.4*exp(-pow((u-.72)/.12,2)),P_hot);
        double rim=exp(-pow((av-(lim-soft*.6))/mix(.12,.035,P_crisp),2))*(side>0?1:.45);
        double mid=mix(1-P_centerDim,1,smooth(0,.85,av));
        double fold=1+P_foldGlow*.25*(1-facing),material=mix(1,.25+.75*smooth(.22,.65,n0),min(P_pleat*.45,.7));
        double glit=0;
        if(P_glitAmt!=0){double gx=(u-t*P_flow/max(P_nU,.1))*P_glitDens*100,gy=(v*.5+.5)*P_glitDens*12;
        double gh=hash2(floor(gx)+seed,floor(gy)+seed);
        double gd=hypot(fract(gx)-hash2(floor(gx)+3.1,floor(gy)+3.1),fract(gy)-hash2(floor(gx)+7.7,floor(gy)+7.7));
        glit=(gh<=.45?1:0)*(1-smooth(P_glitSize*.5,P_glitSize,gd))*(.5+.5*sin(t*P_glitTw+gh*62.8));}
        double fz=max((1-smooth(0,P_frayLen,tv))*P_frayTail,(1-smooth(0,P_frayLen,1-tv))*P_frayHead);
        double strand=pow(1-abs(2*fract(w2*max(P_fibers,4)*.25+.5)-1),3);
        double veins=0;
        if(P_veinAmt!=0){double vx=u*P_veinScale*2-t*P_flow*.5+seed*2.3+ex,vy=v*P_veinScale*1.1+seed*2.3+ey;
        double dx=(fbm(vx*.8+4.1,vy*.8+4.1,3)-.5)*P_veinWarp*2;
        double dy=(fbm(vx*.8+8.3,vy*.8+8.3,3)-.5)*P_veinWarp*2;vx+=dx;vy+=dy;
        double vp=w2*(3+P_veinScale*1.5)+(fbm(vx,vy,4)-.5)*P_veinWarp*3+.14*sin(u*95+w2*7+t*.3);
        double vd=abs(fract(vp+.5)-.5),vaa=max((3+P_veinScale*1.5)*2/columns,.001),vw=1/max(P_veinSharp,2);
        veins=(1-smooth(vw,vw+vaa,vd))*vw/(vw+vaa*.5)*smooth(.35,.68,fbm(vx*1.7+9,vy*1.7+9,3));}
        RGB foldColor=bodyC.blend(materialCore,.65).blend(new RGB(1,.34,.04).blend(materialCore,smooth(.02,.35,ridge)),P_hot);
        RGB col=foldColor.mul(ridge*(1+P_foldGlow*2)).add(bodyC.mul(ridge*.15+P_bodyAmt*(.55+.45*n)*mid))
                .add(vein.mul(veins*P_veinAmt)).add(edge.blend(col2,k2*.5).mul(P_rimAmt*rim))
                .add(bodyC.blend(materialCore,.65).mul(P_fiberAmt*fib*(.5+.5*mid)))
                .add(materialCore.blend(col2,P_glitCol).mul(glit*P_glitAmt*(.5+.5*n)))
                .add(coreC.blend(materialCore,c*c*.65).mul(P_coreAmt*c*(.5+.5*n)));
        if(paletteMode>.5 && paletteMode<1.5) {
            double mx=max(1e-4,max(palette.r,max(palette.g,palette.b)));
            col=new RGB(col.r*mix(1,palette.r/mx,.12),col.g*mix(1,palette.g/mx,.12),col.b*mix(1,palette.b/mx,.12));
        }
        double accent=P_accAmt==0?1:mix(1-P_accAmt,1,clamp((fbm(u*P_accScale*2-t*P_accFlow+seed*3.1,v*.6+t*P_accEvo,3)-.5)*P_accCon+.5+P_accBri,0,1));
        double flash=P_flashAmt==0?1:mix(1,smooth(.35,.75,fbm(u*P_flashScale*3-t*P_flow*.4+seed*1.3,.5+seed*1.3,3))*2,P_flashAmt);
        return col.mul(fold*material*edgeA*m*accent*mix(1,strand,fz)*sideFade*flash*gain);
    }
}
