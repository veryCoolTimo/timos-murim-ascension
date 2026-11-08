package io.github.verycooltimo.murim.client.vfx.bt;

import io.github.verycooltimo.murim.client.CameraShakeHandler;
import io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone;
import io.github.verycooltimo.murim.client.vfx.MurimRenderTypes;
import io.github.verycooltimo.murim.client.vfx.VfxDraw;
import net.minecraft.world.phys.Vec3;
import static io.github.verycooltimo.murim.client.vfx.bt.BtMath.*;

/** First Rate: fire -> control -> violet shell -> incoming qi -> pressure dome -> held thin aura. */
final class BtRank1 {
    private final BtFireElement fire=new BtFireElement();
    private final java.util.List<BtRank1Math.Stream> streams=BtRank1Math.streams();
    private boolean shook;
    void render(BtCtx c) {
        double t=c.labSeconds(),e=t-15.2;var l=BtRank1Math.level(t);
        fire.render(c);
        if(e>=.25&&!shook){CameraShakeHandler.request(.85F,20);shook=true;}
        int colour=mix(0x78cdff,0xb278ff,l.violet());
        Vec3 palms=c.body().bone(Bone.LEFT_HAND).scale(.5).add(c.body().bone(Bone.RIGHT_HAND).scale(.5));
        Vec3 core=palms.add(c.body().near().scale(.12*(1-smooth(0,.2,e))));
        double radius=(9+14*l.ball())*(1-.36*l.dense())*(1+.05*Math.sin(t*6.1)*(1-l.gather()));
        radius=Math.min(.1,c.body().px(radius))*smooth(5.4,8,t);
        double k=Math.min(1,l.ball()*(.8+.4*l.dense()+.3*l.gather()));
        BtDraw.dot(c,MurimRenderTypes.mote(),core,radius*3.4,.3*k,colour);
        BtDraw.dot(c,MurimRenderTypes.mote(),core,radius,k,colour);
        BtDraw.dot(c,MurimRenderTypes.mote(),core,radius*.45,k,mix(0xffffff,0xc9a8ff,l.violet()));
        for(Bone bone:new Bone[]{Bone.LEFT_KNEE,Bone.RIGHT_KNEE,Bone.LEFT_HAND,Bone.RIGHT_HAND,Bone.CHEST})
            BtDraw.dot(c,MurimRenderTypes.btBodyGlow(),c.body().surface(c.body().bone(bone)), .18,Math.min(.3,.12*l.energy()+.18*l.ball()),colour);
        BtDraw.floor(c,c.body().plate(400,594,0),c.body().px(340),.26*Math.min(1,l.energy()*1.2)+.1*l.shell()+.12*l.aura(),colour,MurimRenderTypes.mote());
        if(l.shell()>0) {
            double breath=Math.sin((t-9)*Math.PI*2/2.8)*(1-l.gather());
            double gain=.4*l.shell()*(.9+.1*breath)*(1-.55*l.gather());
            contour(c,.008,.02,gain,mix(0xbdebff,0xba7ae8,l.violet()),true);
        }
        inflow(c);
        blast(c,e);
        if(l.aura()>0) {
            double breath=.5+.5*Math.sin(e*Math.PI*2/2.6),fl=1-smooth(0,.5,e);
            contour(c,.032,.06,.38*l.aura()*(.8+.2*breath),0xb278ff,false);
            contour(c,.02,.012,(.75+.15*breath)*l.aura(),mix(0xb278ff,0xffffff,fl),false);
        }
    }
    static int mix(int a,int b,double f) {int rgb=0;for(int shift:new int[]{16,8,0})rgb|=((int)(((a>>shift)&255)*(1-f)+((b>>shift)&255)*f))<<shift;return rgb;}
    /** Offset ribbons stay outside the silhouette, including round the head. No filled light over the face. */
    private static void contour(BtCtx c,double offset,double width,double gain,int rgb,boolean shell) {
        var shape=c.body().silhouette();
        // Keep the crown attached to the head rather than enlarging it into a separate halo loop.
        double neck=c.body().bone(Bone.HEAD).y-c.body().ground()-.16;
        var inner=shape.outline(offset);var outer=shape.outline(offset+width);
        var headInner=shape.outline(.008);var headOuter=shape.outline(.028);
        var vc=c.buffers().getBuffer(MurimRenderTypes.ribbon());
        for(int i=0;i<inner.size()-1;i++) {
            var a=inner.get(i);var z=inner.get(i+1);var ao=outer.get(i);var zo=outer.get(i+1);
            double fa=smooth(neck,neck+.12,a.y()),fz=smooth(neck,neck+.12,z.y());
            a=a.mix(headInner.get(i),fa);z=z.mix(headInner.get(i+1),fz);
            ao=ao.mix(headOuter.get(i),fa);zo=zo.mix(headOuter.get(i+1),fz);
            Vec3[] q={c.body().silhouettePoint(a,.32),c.body().silhouettePoint(z,.32),c.body().silhouettePoint(zo,.32),c.body().silhouettePoint(ao,.32)};
            double floor=1; // Include the crossed-leg/lap edge of the closed outline.
            for(int j=0;j<4;j++)VfxDraw.vertex(vc,c.pose(),q[j],c.body().near(),j==1||j==2?1:0,j>=2?1:0,(float)(gain*floor*(j>=2?.65:1)),(rgb>>16&255)/255F,(rgb>>8&255)/255F,(rgb&255)/255F);
        }
    }
    private void inflow(BtCtx c) {
        double tk=c.labSeconds()*20;
        for(var s:streams) {
            if(tk<s.t0()||tk>s.t1()+3)continue;
            Vec3[] p=new Vec3[5];double[] a=new double[5];
            for(int j=0;j<5;j++) {
                double q=clamp((tk-(4-j)-s.t0())/(s.t1()-s.t0()));var at=BtRank1Math.bezier(s,Math.pow(q,1.6));
                // Incoming qi is on the far plane and disappears into the depth-tested body.
                Vec3 endpoint=c.body().plate(s.end().x(),594,585-s.end().y());
                Vec3 hull=c.body().skinPoint(s.end().x(),s.end().y());
                p[j]=c.body().plate(at.x(),594,585-at.y()).add(hull.subtract(endpoint).scale(q*q)).subtract(c.body().near().scale(.12));
                a[j]=1-smooth(s.t1(),s.t1()+3,tk);
            }
            if(s.seed()<BtScenes.quality(c.mc()))BtDraw.flame(c,p,c.body().px(s.size()*360*.4),a[0],s.seed(),1);
            double hit=(tk-s.t1())/2.4;
            if(s.seed()<BtScenes.quality(c.mc())&&hit>=0&&hit<=1){Vec3 at=c.body().skinPoint(s.end().x(),s.end().y());BtDraw.dot(c,MurimRenderTypes.mote(),at,c.body().px(18+16*hit),.55*(1-hit),0xcdb4ff);BtDraw.dot(c,MurimRenderTypes.mote(),at,c.body().px(7),.8*(1-hit),0xffffff);}
        }
    }
    private static void blast(BtCtx c,double e) {
        if(e<0||e>1.2)return;
        double r=90+1050*(1-Math.exp(-e*4.6)),a=1-smooth(.1,.5,e);
        // Far camera plane: compensate its greater camera distance so its projected size matches the lab dome.
        Vec3 centre=c.body().axis().add(0,c.body().px(40),0).subtract(c.body().near().scale(.4));
        double perspective=c.camera().distanceTo(centre)/c.camera().distanceTo(c.body().axis());
        BtDraw.dot(c,MurimRenderTypes.mote(),centre,c.body().px(r)*perspective,.16*a,0xaa82ff);
        // Lab floor release at 15.30 s: world-horizontal, growing from a one-block radius.
        double ringTime=e-.1;
        if(ringTime>=0) {
            double ringGain=smooth(0,.05,ringTime)*(1-smooth(.12,.38,ringTime));
            BtDraw.annulus(c,1+2*ringTime,.025,.4*ringGain,0xb278ff);
            BtDraw.annulus(c,1+2*ringTime,.008,.5*ringGain,0xf4eeff);
        }
        for(int i=0;i<44*BtScenes.quality(c.mc());i++) {
            double th=(i+random(i*41+9501))/44*Math.PI*2,q=e-.04*random(i*41+9502);if(q<0)continue;
            double rr=(.82+.18*random(i*41+9503))*.92*(90+1050*(1-Math.exp(-q*4.6))),fade=Math.max(0,1-q/(.5+.4*random(i*41+9505)));
            Vec3 at=c.body().plate(400+Math.cos(th)*rr,594+Math.sin(th)*rr*.22,(20+70*random(i*41+9504))*Math.min(1,q*3));
            BtDraw.dot(c,MurimRenderTypes.mote(),at,c.body().px(6),fade*.5,0xbea0ff);BtDraw.dot(c,MurimRenderTypes.mote(),at,c.body().px(2),fade,0xfaf6ff);
        }
    }
}
