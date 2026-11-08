package io.github.verycooltimo.murim.client.vfx.bt;

import io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone;
import io.github.verycooltimo.murim.client.vfx.MeditationVfxRenderer;
import io.github.verycooltimo.murim.client.vfx.MurimRenderTypes;
import net.minecraft.world.phys.Vec3;
import static io.github.verycooltimo.murim.client.vfx.bt.BtMath.*;

/** Third Rate default branch routing + finales A and B, lab-bt_third.js. */
final class BtRank3 {
    private final BtFireSim fire=new BtFireSim();
    void render(BtCtx c) {
        double t=c.labSeconds(),e=t-10.8,chan=smooth(1.2,3.6,t),after=1-smooth(11.6,13.6,t),river=smooth(8.5,10.6,t);
        fire.advance(t);flames(c,fire);
        BtChannels.draw(c,chan*after);
        Vec3 core=c.body().surface(c.body().bone(Bone.DANTIAN));
        double gather=smooth(10.1,10.75,t)*(1-smooth(10.8,10.85,t));
        double level=.3*smooth(.4,1.2,t)+.4*chan+.4*river,tight=1-.45*gather,fade=1-smooth(11.3,15,t);
        BtDraw.dot(c,MurimRenderTypes.mote(),core,c.body().px(90*tight),level*.7*fade,0x77e9ff);
        BtDraw.dot(c,MurimRenderTypes.mote(),core,c.body().px(16*tight),level*1.3*fade,0xffffff);
        star(c,core,Math.max(43.2,26+18*river)*tight*(1+.15*Math.sin(t*5.2)),level*fade,.1*Math.sin(t*.7));
        light(c,chan,after,river);pillar(c,e);finale(c,e);flecks(c,e);
        MeditationVfxRenderer.labPurificationDetails(c);
    }
    private static void star(BtCtx c,Vec3 at,double radius,double alpha,double rotation) {
        BtDraw.dot(c,MurimRenderTypes.mote(),at,c.body().px(radius*.9),alpha*.7,0x77e9ff);
        for(int set=0;set<2;set++)for(int i=0;i<4;i++) {
            double a=rotation+i*Math.PI/2+(set==1?Math.PI/4:0),len=radius*(set==1?.38:1),width=radius*(set==1?.06:.09);
            Vec3 dir=c.body().right().scale(Math.cos(a)).add(0,Math.sin(a),0),side=c.body().right().scale(-Math.sin(a)).add(0,Math.cos(a),0);
            BtDraw.strip(c,new Vec3[]{at,at.add(dir.scale(c.body().px(len)))},new double[]{c.body().px(width),0},new double[]{Math.min(1,alpha),0},0xf0ffff,MurimRenderTypes.band());
        }
    }
    /** lab-bt_third.js bt3Fire: the simulation as one fire field (author 31.10: strips «совсем не так как в 2д лабе»). */
    static void flames(BtCtx c,BtFireSim fire) {
        BtFireField.render(c,fire,BtFireField.BLUE);
    }

    private static void light(BtCtx c,double chan,double after,double river) {
        double t=c.labSeconds();
        for(int ch=0;ch<5;ch++) {
            Vec3[] a=BtChannels.anchors(c.body(),ch);
            for(int j=0;j<a.length;j+=Math.max(1,a.length/3))BtDraw.dot(c,MurimRenderTypes.btBodyGlow(),c.body().surface(a[j]),c.body().px(ch==0?120:60),(.08+.16*river)*chan*after,0x77e9ff);
        }
        var l=BtFireSim.level(t);double rim=.55*Math.min(1,l.energy()*1.4)*(1+.4*l.flare());
        // Canvas silhouette clipping becomes bone-surface light on the robe, hands and knees. No light on the face.
        for(Bone bone:new Bone[]{Bone.LEFT_SHOULDER,Bone.RIGHT_SHOULDER,Bone.LEFT_KNEE,Bone.RIGHT_KNEE,Bone.LEFT_HAND,Bone.RIGHT_HAND})
            BtDraw.dot(c,MurimRenderTypes.btBodyGlow(),c.body().surface(c.body().bone(bone)),c.body().px(35),rim*.8,0x5aaaff);
        BtDraw.floor(c,c.body().plate(400,594,0),c.body().px(330),.18*Math.min(1,l.energy()),0x2f8dff,MurimRenderTypes.mote());
        double k=.25*smooth(10.8,11,t)*(1-smooth(11.8,15.5,t));
        BtDraw.dot(c,MurimRenderTypes.btBodyGlow(),c.body().surface(c.body().bone(Bone.CHEST)),c.body().px(200),k,0x77e9ff);
    }
    private static void pillar(BtCtx c,double e) {
        if(e<0)return;
        double t=c.labSeconds(),rise=smooth(0,.22,e),k=1-smooth(1.8,3.1,e),fl=.85+.15*Math.sin(t*41);
        Vec3 d=c.body().bone(Bone.DANTIAN),head=c.body().bone(Bone.HEAD).add(0,.24,0),top=d.add(0,rise*(head.y-d.y+2.2),0);
        Vec3 backAxis=d.subtract(c.body().near().scale(.21));
        double[] widths={.38,.20,.09},alphas={.18,.32,.5};int[] colours={0x357cdc,0x77e9ff,0xf0ffff};
        for(int pass=0;pass<3;pass++) {
            BtDraw.softLine(c,backAxis, new Vec3(backAxis.x,top.y,backAxis.z),widths[pass],alphas[pass]*k,colours[pass]);
            Vec3 base=c.body().surface(d);
            double collar=c.body().bone(Bone.CHEST).y+.12;
            Vec3 end=new Vec3(base.x,Math.min(top.y,collar),base.z);
            if(end.y>base.y)BtDraw.softLine(c,base,end,widths[pass],alphas[pass]*k*fl,colours[pass]);
        }
        if(rise<1){BtDraw.dot(c,MurimRenderTypes.mote(),top,c.body().px(40),smooth(0,.02,e),0x77e9ff);BtDraw.dot(c,MurimRenderTypes.mote(),top,c.body().px(12),1.4*smooth(0,.02,e),0xffffff);}
    }
    private static void finale(BtCtx c,double elapsed) {
        double e=elapsed-.12;if(e<0)return;
        if(e<1.8) {
            double r=430*(1-Math.exp(-e*2.4)),a=1-smooth(.5,1.8,e);
            BtDraw.ring(c,r,9*(1-e/2.4),a,0x77e9ff,5);BtDraw.ring(c,r,1.6*(1-e/2.4),.8*a,0xf0ffff,5);
            BtDraw.floor(c,c.body().plate(400,594,0),c.body().px(r*.95),.14*a,0x77e9ff,MurimRenderTypes.mote());
        }
        for(int i=0;i<3;i++) {
            double q=e-i*.08;if(q<0||q>1.1)continue;
            double r=(160+40*i)*(1+q*.4),st=i*2.1+q*2.2,sw=1.2+.3*i,a=1-q/1.1;
            Vec3[] pts=new Vec3[25];double[] w=new double[25],al=new double[25];
            for(int j=0;j<=24;j++){double f=j/24.0,an=st+sw*f;pts[j]=c.body().plate(400+Math.cos(an)*r,594+Math.sin(an)*r*.22,10);w[j]=c.body().px(4*a*Math.pow(Math.max(0,Math.sin(Math.PI*f)),.8));al[j]=.5*a;}
            BtDraw.strip(c,pts,w,al,0x77e9ff,MurimRenderTypes.ribbon());for(int j=0;j<25;j++){w[j]*=.325;al[j]*=1.8;}BtDraw.strip(c,pts,w,al,0xf0ffff,MurimRenderTypes.ribbon());
        }
        for(int i=0;i<80*BtScenes.quality(c.mc());i++) {
            int seed=i*7+5200;double q=e-.12*random(seed+13);if(q<0||q>.75)continue;
            double an=random(seed+26)*6.283,r0=170+110*random(seed+39)+q*520,len=140+260*random(seed+52),a=1-q/.75;
            Vec3 axis=c.body().surface(c.body().bone(Bone.DANTIAN)).add(0,c.body().px(20),0),dir=c.body().right().scale(Math.cos(an)).add(0,-Math.sin(an),0);
            BtDraw.strip(c,new Vec3[]{axis.add(dir.scale(c.body().px(r0))),axis.add(dir.scale(c.body().px(r0+len*.5))),axis.add(dir.scale(c.body().px(r0+len)))},new double[]{0,c.body().px((1+3*random(seed+65))*a),0},new double[]{0,.75*a,0},random(seed+78)<.4?0x77e9ff:0xf0ffff,MurimRenderTypes.band());
        }
    }
    private static void flecks(BtCtx c,double e) {
        if(e<0||e>1.3)return;
        for(int i=0;i<46*BtScenes.quality(c.mc());i++) {
            int seed=i*37+1900;double an=random(seed+11)*6.283,sp=160+300*random(seed+22),life=.6+.6*random(seed+66);if(e>life)continue;
            double x=(random(seed+33)-.5)*200+Math.cos(an)*sp*e,y=200+260*random(seed+44)+(Math.sin(an)*sp-60)*e+200*e*e,sz=1.5+2.5*random(seed+55),a=1-e/life,burn=smooth(.15,.55,e/life);
            Vec3 at=c.body().plate(400+x,594,585-y).add(c.body().near().scale(.18));
            BtDraw.dot(c,MurimRenderTypes.impurity(),at,c.body().px(sz),.85*a*(1-burn)*smooth(0,.025,e),0x1a0e08);
            BtDraw.dot(c,MurimRenderTypes.mote(),at,c.body().px(sz*3),a*burn*.8,0x2f8dff);BtDraw.dot(c,MurimRenderTypes.mote(),at,c.body().px(sz*.6*(1-.5*burn)),a*burn,0x77e9ff);
        }
    }
}
