package io.github.verycooltimo.murim.client.vfx.bt;

import io.github.verycooltimo.murim.client.CameraShakeHandler;
import io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone;
import io.github.verycooltimo.murim.client.vfx.MurimRenderTypes;
import io.github.verycooltimo.murim.client.vfx.VfxDraw;
import net.minecraft.world.phys.Vec3;
import static io.github.verycooltimo.murim.client.vfx.bt.BtMath.*;

/** Peak's single gather/compress/release flow. Moving heads leave continuous depth-tested trails. */
final class BtPeak {
    private boolean shook;
    private io.github.verycooltimo.murim.client.vfx.preset.PeakEssences ribbons;
    private final BtFaceShell face=new BtFaceShell();
    BtPeak() {
        if(!io.github.verycooltimo.murim.client.vfx.preset.PresetLibrary.loaded()) {
            try {io.github.verycooltimo.murim.client.vfx.preset.PresetLibrary.reload();}
            catch(java.io.IOException ex){io.github.verycooltimo.murim.MurimMod.LOGGER.error("Cannot load Peak ribbons",ex);}
        }
    }
    void render(BtCtx c) {
        double t=c.labSeconds(),e=t-25.5;
        if(e>=0&&!shook){CameraShakeHandler.request(.95F,20);shook=true;}
        long mark=BtPerf.now();
        // the glow of the halo and the ribbons (author 31.10: «не хватает глоу»), while they are there
        if(t>12.4&&t<26.2)BtGlow.prepare(c.mc(),1F);
        halo(c);mark=BtPerf.lap(BtPerf.HALO,mark);
        // Lab: only the face cracks (body-wide cracks were rejected 29–30.10); the face shell draws its own seep and crumbs.
        face.render(c);mark=BtPerf.lap(BtPerf.SHELL,mark);
        whirl(c);mark=BtPerf.lap(BtPerf.WHIRL,mark);
        if(ribbons==null) {
            var f=io.github.verycooltimo.murim.client.vfx.preset.PresetLibrary.get("fist");
            var m=io.github.verycooltimo.murim.client.vfx.preset.PresetLibrary.get("main");
            if(f!=null&&m!=null)ribbons=new io.github.verycooltimo.murim.client.vfx.preset.PeakEssences(f.layers().getFirst(),m.layers().getFirst());
        }
        if(ribbons!=null)ribbons.render(c);
        mark=BtPerf.lap(BtPerf.RIBBONS,mark);
        base(c);mark=BtPerf.lap(BtPerf.BASE,mark);
        ambient(c);mark=BtPerf.lap(BtPerf.AMBIENT,mark);
        blow(c,e);mark=BtPerf.lap(BtPerf.BLOW,mark);
        afterImpact(c,e);mark=BtPerf.lap(BtPerf.AFTER,mark);
        calm(c);BtPerf.lap(BtPerf.CALM,mark);
    }
    private static void halo(BtCtx c) {
        double t=c.labSeconds(),q=t-25.56,a=smooth(12.6,14.8,t)
                *(1-.5*smooth(20.5,23,t)+.5*smooth(23.9,25.3,t))
                *(q<0?1:(1+1.2*Math.exp(-q/.08))*(1-smooth(25.56,25.96,t)));
        if(a<=0)return;
        BtPeakHalo.render(c,a);
    }
    private static void whirl(BtCtx c) {
        double t=c.labSeconds(),gain=smooth(14.5,17,t)*(1-smooth(25.5,25.9,t));
        if(gain<=0)return;
        for(int i=0;i<16*BtScenes.quality(c.mc());i++) {
            Vec3[] p=new Vec3[25];double[] w=new double[25],a=new double[25];
            for(int j=0;j<25;j++){double f=j/24.0,th=BtPeakMath.phi(t)*2+i*2.4+f*1.6,r=185+60*random(i*17+1)+15*Math.sin(th*3+t);p[j]=c.body().plate(400+Math.cos(th)*r,594+Math.sin(th)*r*.22,12+22*Math.sin(f*Math.PI));w[j]=c.body().px(9*Math.sin(f*Math.PI));a[j]=.2*gain;}
            BtDraw.strip(c,p,w,a,BtPeakMath.hue(i/16.0+t*.03),MurimRenderTypes.ribbon());
        }
    }
    /** codex 31.10 next to the lab: the lab's face glows cyan more and more towards the blow and is the brightest point at it; the
     * port's cracks gave the face little light. A soft cyan glow over the face from 21.5 s, strongest at the blow, gone by 26.2 s. */
    private static void base(BtCtx c) {
        double t=c.labSeconds(),e=t-25.5,k=smooth(16.5,19.5,t)*(1-smooth(26.5,28.5,t))+(e>0?Math.exp(-e*1.2)*.6:0);
        BtDraw.floor(c,c.body().plate(292,594,0),c.body().px(125),.3*k,0xf2f89b,MurimRenderTypes.mote());
        BtDraw.floor(c,c.body().plate(525,594,0),c.body().px(135),.28*k,0x3af39a,MurimRenderTypes.mote());
    }
    /** btpInner, Wind, Orbs: bone-clipped robe light, dark rising grit and softly rimmed coloured loops. */
    private static void ambient(BtCtx c) {
        double t=c.labSeconds(),e=t-25.5;
        double inner=(.4*smooth(14.6,17,t)+.6*smooth(17,25.5,t))*(1-.85*smooth(26.5,30,t));
        for(int patch=0;patch<3;patch++) {
            Vec3 at=c.body().surface(c.body().bone(patch==0?Bone.CHEST:Bone.DANTIAN)).add(0,patch==0?-.12:patch==2?-.1:0,0);
            BtDraw.dot(c,MurimRenderTypes.btBodyGlow(),at,.18+.06*patch,Math.min(.23,.12*inner+.11*BtPeakMath.press(t))*(1-smooth(31,34,t)),0x96dcff);
        }
        double wind=(.45*smooth(15.5,18,t)+.6*smooth(21,25.3,t)+(e>0?Math.exp(-e*1.3):0))*(1-smooth(27.5,30.5,t));
        for(int i=0;i<40*BtScenes.quality(c.mc());i++) {
            int seed=i*23+9100;double ph=(t*(.25+.25*random(seed+1))+random(seed+2))%1;
            BtDraw.dot(c,MurimRenderTypes.impurity(),c.body().plate(200+400*random(seed+3)+20*Math.sin(t+i),594,585-(600-ph*260)),c.body().px(1.6),.6*wind*(1-ph),0x1e1a22);
        }
        double orbs=(smooth(17,20,t)*(1-smooth(28,31,t))+(e>0?.6*Math.exp(-e*.8):0))*(1-smooth(31,34,t));
        if(t>=17)for(int i=0;i<22*BtScenes.quality(c.mc());i++) {
            int seed=i*61+5100;double life=4+3*random(seed+1),ph=((t-17)/life+random(seed+2))%1,side=random(seed+3)<.5?-1:1;
            double x=400+side*(140+180*random(seed+4))+18*Math.sin(t*.7+i),y=600-460*ph-30*random(seed+5),r=3+9*Math.pow(random(seed+6),2),a=Math.sin(Math.PI*ph)*orbs*(.35+.4*random(seed+7));
            Vec3 at=c.body().plate(x,594,585-y).subtract(c.body().near().scale(.1));
            BtDraw.dot(c,MurimRenderTypes.mote(),at,c.body().px(r),a*.45,BtPeakMath.hue(i*.2));
            Vec3[] p=new Vec3[25];double[] w=new double[25],al=new double[25];
            for(int j=0;j<25;j++){double th=j*Math.PI*2/24;p[j]=at.add(c.body().right().scale(c.body().px(r*.86*Math.cos(th)))).add(0,c.body().px(r*.86*Math.sin(th)),0);w[j]=c.body().px(r*.1);al[j]=a*.4;}
            BtDraw.strip(c,p,w,al,0xffffff,MurimRenderTypes.ribbon());
        }
    }
    private static void blow(BtCtx c,double e) {
        if(e<0||e>=.75)return;
        double core=e<.08?e/.08:Math.exp(-(e-.08)*1.5);
        Vec3 cheek=c.body().bone(Bone.HEAD).subtract(c.body().near().scale(.3));
        if(e<.8)for(int j=0;j<72;j++) {
            double q=e/.8,th=j/72.0*Math.PI*2,rr=285*(1+.5*(1-Math.pow(1-q,2))),noise=smooth(.35,.6,BtMath.noise(th*2.2+3,77));
            Vec3 at=c.body().plate(400+Math.cos(th)*rr,594,585-330-Math.sin(th)*rr*.92).subtract(c.body().near().scale(.2));
            BtDraw.dot(c,MurimRenderTypes.mote(),at,c.body().px(26*(1-q)+6),.3*Math.pow(1-q,1.6)*noise,BtPeakMath.hue(j/72.0));
        }
        if(e<.75) {
            Vec3 head=c.body().bone(Bone.HEAD).subtract(c.body().near().scale(.1));
            double up=e<.04?e/.04:Math.exp(-(e-.04)/.3);
            for(int pass=0;pass<3;pass++) {
                Vec3[] p={head,head.add(0,c.body().px(150),0),head.add(0,c.body().px(264),0)};
                double half=c.body().px(new double[]{105,45,15}[pass]);
                BtDraw.strip(c,p,new double[]{half,half*.84,half*.6},new double[]{up*new double[]{.55,.75,.95}[pass],up*.35,0},new int[]{0x5aaaff,0x96d7ff,0xe1f7ff}[pass],MurimRenderTypes.ribbon());
            }
            BtDraw.ring(c,100+900*(1-Math.exp(-e*4)),20,.5*Math.exp(-e*5),0x80dfff,99);
        }
        double th=.55+.5*(1-Math.exp(-e*1.6));
        for(int i=0;i<8;i++) {
            double d=new double[]{95,165,240,310,395,470,-110,-200}[i],rad=new double[]{16,9,30,13,44,22,12,26}[i];
            Vec3 at=cheek.add(c.body().right().scale(c.body().px(Math.cos(th)*d))).add(0,-c.body().px(Math.sin(th)*d),0);
            BtDraw.dot(c,MurimRenderTypes.mote(),at,c.body().px(rad),.3*Math.exp(-Math.max(0,e-.06)*1.2),BtPeakMath.hue(i*.14));
        }
    }
    /** btpHalo/btpBlow/btpSparks: continuous recovery under the outgoing essence trails. */
    private static void afterImpact(BtCtx c,double e) {
        if(e<0||e>3)return;
        double gain=smooth(0,.12,e)*Math.exp(-Math.max(0,e-.12)*2)
                +.32*smooth(.3,.75,e)*(1-smooth(1.5,3,e));
        BtPeakHalo.render(c,gain*.85);
        // Soft cyan-white mass behind the whole seated body; no billboard over the face.
        Vec3 centre=new Vec3(c.body().axis().x,c.body().ground()+.72,c.body().axis().z)
                .subtract(c.body().near().scale(.28));
        VfxDraw.billboard(c.buffers().getBuffer(MurimRenderTypes.mote()),c.pose(),centre,c.camera(),
                .86,(float)(gain*.22),.65F,.88F,1);
        for(int i=0;i<32*BtScenes.quality(c.mc());i++) {
            int seed=8300+i*47;
            double x=(random(seed)-.5)*2.1,y=.04+random(seed+1)*1.5;
            double alpha=gain*(.25+.4*random(seed+2));
            Vec3 at=new Vec3(c.body().axis().x,c.body().ground()+y+e*(.12+.16*random(seed+3)),c.body().axis().z)
                    .add(c.body().right().scale(x)).subtract(c.body().near().scale(.15));
            BtDraw.dot(c,MurimRenderTypes.mote(),at,.008+.01*random(seed+4),alpha,0xc8efff);
            if(i%3==0) {
                Vec3[] line={at.add(0,-.22,0),at,at.add(0,.3,0)};
                BtDraw.strip(c,line,new double[]{0,.004,0},new double[]{0,alpha*.6,0},0xa8eaff,MurimRenderTypes.ribbon());
            }
        }
        for(int side:new int[]{-1,1}) {
            Vec3 knee=c.body().bone(side<0?Bone.LEFT_KNEE:Bone.RIGHT_KNEE);
            BtDraw.floor(c,new Vec3(knee.x,c.body().ground()+.015,knee.z),.33,gain*.28,
                    side<0?0xc9edb3:0x65dcb8,MurimRenderTypes.mote());
        }
    }
    private static void calm(BtCtx c) {
        double t=c.labSeconds(),k=smooth(27.5,29.5,t)*(1-smooth(31,34,t));if(k<=0)return;
        for(int i=0;i<7*BtScenes.quality(c.mc());i++) {
            double born=27.6+random(i*41+7301)*4,e=t-born;if(e<0)continue;
            double x=random(i*41+7302)<.5?214+40*random(i*41+7307):540+40*random(i*41+7307),y=90+280*random(i*41+7303)+9*e;
            BtDraw.dot(c,MurimRenderTypes.impurity(),c.body().plate(x+10*Math.sin(e*.9+i),594,585-y),c.body().px(1.5),Math.min(1,e)*.7*k,0xf2f5ee);
        }
    }
}
