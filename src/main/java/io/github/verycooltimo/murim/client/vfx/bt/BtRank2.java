package io.github.verycooltimo.murim.client.vfx.bt;

import com.mojang.math.Axis;
import io.github.verycooltimo.murim.client.vfx.*;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import static io.github.verycooltimo.murim.client.vfx.bt.BtMath.*;

/** Earth answers: lab br2Draw, Wave, Haze, LightOn and Pulse. No veins or outline. */
final class BtRank2 {
    private final List<BtRank2Math.Item> items=BtRank2Math.make();
    private final BlockState floor;
    private final java.util.Map<BtRank2Math.Item,Vec3> landed=new java.util.HashMap<>();
    BtRank2(BlockState floor) { this.floor=floor; }

    /** Resolve once, before rendering. Full collision cubes also exclude thin carpets and oversized door models.
     * API: BreakthroughParticles.floor, SectWalking.isCollisionShapeFullBlock, SectLayoutBoxes.hasBlockEntity. */
    static BlockState floor(net.minecraft.client.Minecraft mc) {
        BlockPos at=mc.player.blockPosition().below();
        BlockState s=mc.level.getBlockState(at);
        for(int i=0;i<2&&(s.isAir()||!s.isCollisionShapeFullBlock(mc.level,at));i++) {at=at.below();s=mc.level.getBlockState(at);}
        if(s.isAir()||s.hasBlockEntity()||!s.getFluidState().isEmpty()||s.getLightEmission()>0||!s.isCollisionShapeFullBlock(mc.level,at))
            return Blocks.COBBLESTONE.defaultBlockState();
        // [НЕПРОВЕРЕНО: BlockState#getRenderShape in 1.21.1; verify with compileJava on tester machine.]
        return s.getRenderShape()==net.minecraft.world.level.block.RenderShape.MODEL?s:Blocks.COBBLESTONE.defaultBlockState();
    }
    record Aura(double level,double gather,double flash,double elapsed) {}
    static Aura aura(double t) {
        double e=t-15.2,gather=smooth(14.6,14.85,t)*(e<0?1:0),flash=e>=0?Math.exp(-e*9):0;
        double level=e<0?smooth(1.5,5,t)*(.5+.5*BtRank2Math.spinRate(t)/2.9)*(1+.14*Math.sin(BtRank2Math.spin(t)*2)*(1-gather))+.35*gather:.16+.2*Math.exp(-e*1.6)+flash;
        return new Aura(level,gather,flash,e);
    }
    void render(BtCtx c) {
        double t=c.labSeconds();Aura a=aura(t);
        haze(c,a);light(c,a);pulse(c,a.elapsed);wave(c,a.elapsed);
        int speck=0,puff=0;
        for(var o:items) {
            if(o.kind()==BtRank2Math.Kind.SPECK&&speck++>=150*BtScenes.quality(c.mc()))continue;
            if(o.kind()==BtRank2Math.Kind.PUFF&&puff++>=110*BtScenes.quality(c.mc()))continue;
            var p=BtRank2Math.position(o,t);Vec3 at=c.body().plate(p.x(),p.floorY(),p.height());
            if(!c.body().faceClear(at))continue;
            double lit=.75+.5*smooth(380,700,p.x());
            if(p.height()>=2&&o.kind()!=BtRank2Math.Kind.SPECK) {
                // Canvas soft ellipses become horizontal alpha-blended mote quads with depth testing.
                BtDraw.floor(c,c.body().plate(p.x(),p.floorY(),0),c.body().px(o.size()*1.1),.28*Math.max(0,1-p.height()/280)*Math.min(1,o.size()/8),0x08090e,MurimRenderTypes.impurity());
            }
            switch(o.kind()) {
                case SPECK -> BtDraw.dot(c,MurimRenderTypes.solid(),at,.006,Math.min(.45,.35*lit*Math.min(1,p.height()/12+.2))*(1-smooth(19,20,t))*smooth(0,.4,t),0xc4ccec);
                case PUFF -> {
                    // impurity() is the existing alpha-blended soft mote, not the 4x4 dust atlas or additive mist.
                    if(p.height()<=1&&t>=15.2)continue;
                    int tail=Math.min(6,(int)(BtRank2Math.spinRate(t)*3));
                    for(int k=tail;k>=0;k--) {
                        var q=BtRank2Math.position(o,t-k*.04);double sc=(1-k*.13)*p.grow(),al=(.2-.025*k)*lit*Math.min(1,p.height()/20+.25)*smooth(0,.4,t);
                        for(int m=0;m<3;m++) {
                            double ox=(random(o.phase()*99+m)-.5)*o.size()*1.2,oy=(random(o.phase()*77+m)-.5)*o.size()*.7;
                            BtDraw.dot(c,MurimRenderTypes.impurity(),c.body().plate(q.x()+ox*sc,q.floorY(),q.height()-oy*sc),Math.min(.06,Math.max(.025,c.body().px(o.size()*(.55+.3*m/2)*sc))),al,0x969cb8);
                        }
                    }
                }
                case PEBBLE -> pebble(c,o,p,at);
                case LEAF -> leaf(c,o,p,at);
            }
        }
    }
    private void pebble(BtCtx c,BtRank2Math.Item o,BtRank2Math.Position p,Vec3 at) {
        double t=c.labSeconds(),fade=1-smooth(19.5,20,t),grow=smooth(o.ts()-1.6,o.ts(),t);
        double size=Math.min(p.front()&&t>15.2?.16:.12,Math.max(.04,Math.min(o.size()>20?.12:.08,c.body().px(o.size()*1.1)*1.35))*p.grow())*grow*fade*.8;
        if(size<=.00001)return;
        // Freeze the floor anchor after the bounce; posed bones must not drag landed models.
        if(t>=p.landing()+.16) {
            Vec3 settled=landed.get(o);
            if(settled==null){settled=at;landed.put(o,settled);}
            at=settled;
        }
        double rot=o.spin()*(Math.min(t,14.6)-o.ts())*(t>=15.2||p.height()>1?1:0)+o.phase()+(t>15.2?o.spin()*2*(Math.min(t,p.landing())-15.2):0);
        var ps=c.stack();ps.pushPose();
        try {
            ps.translate(at.x,at.y+size*.5,at.z);
            ps.mulPose(Axis.YP.rotationDegrees((float)Math.toDegrees(rot)));
            ps.mulPose(Axis.XP.rotationDegrees((float)(23*Math.sin(rot+o.phase()))));
            ps.scale((float)size,(float)size,(float)size);ps.translate(-.5,-.5,-.5);
            // API requested by BRIEF-1; renderModel signature is evidenced in GateDoorRenderer.
            // [НЕПРОВЕРЕНО: Minecraft#getBlockRenderer accessor; compileJava must verify on the tester machine.]
            var blocks=c.mc().getBlockRenderer();
            int light=LevelRenderer.getLightColor(c.mc().level,BlockPos.containing(at.x,at.y+.1,at.z));
            // Use the floor's actual packed light: no artificial block/sky minimum at night.
            // Same real baked block model/texture path as GateDoorRenderer.renderModel, including ModelData.
            // renderSingleBlock overload is absent from local API evidence; use its verified model-rendering equivalent.
            blocks.getModelRenderer().renderModel(ps.last(),c.buffers().getBuffer(Sheets.cutoutBlockSheet()),floor,blocks.getBlockModel(floor),
                    .85f,.85f,.85f,light,OverlayTexture.NO_OVERLAY,ModelData.EMPTY,null);
        } finally { ps.popPose(); }
        // Opaque vanilla models cannot alpha fade: shrink them smoothly over the final 0.5 s instead.
    }
    private static void leaf(BtCtx c,BtRank2Math.Item o,BtRank2Math.Position p,Vec3 at) {
        double t=c.labSeconds();if(p.height()<=0&&t>16.7)return;
        double rot=o.spin()*t+o.phase(),flip=Math.cos(t*2.2+o.phase()),s=Math.max(.035,c.body().px(o.size()*p.grow()))*smooth(o.ts()-1,o.ts(),t);
        Vec3 right=c.body().right(),up=new Vec3(0,1,0),a=right.scale(Math.cos(rot)).add(up.scale(Math.sin(rot))).scale(s);
        Vec3 b=right.scale(-Math.sin(rot)).add(up.scale(Math.cos(rot))).scale(s*.275*(.35+.65*Math.abs(flip)));
        int rgb=flip>0?0x466034:0x3a512c;var vc=c.buffers().getBuffer(MurimRenderTypes.impurity());
        Vec3[] q={at.subtract(a),at.add(b),at.add(a),at.subtract(b)};
        for(var v:q)VfxDraw.vertex(vc,c.pose(),v,c.body().near(),.5f,.5f,1,((rgb>>16)&255)/255f,((rgb>>8)&255)/255f,(rgb&255)/255f);
        BtDraw.line(c,at.subtract(a.scale(.9)),at.add(a.scale(.9)),c.body().px(.5),.6,0x91ad6b);
    }
    private static void light(BtCtx c,Aura a) {
        double l=Math.min(1.4,a.level),fade=a.elapsed>=0?Math.exp(-Math.max(0,a.elapsed-.05)*6):1;
        Vec3 core=c.body().surface(c.body().bone(BoneAnchorLayer.Bone.DANTIAN));
        BtDraw.dot(c,MurimRenderTypes.btBodyGlow(),c.body().surface(c.body().axis()).add(0,-c.body().px(120),0),c.body().px(230),.22*l*fade,0xb8742e);
        BtDraw.dot(c,MurimRenderTypes.btBodyGlow(),core.add(0,-c.body().px(40),0),c.body().px(120),(.16*Math.min(1,a.level)+.34*a.gather)*fade,0xffc468);
        double ember=(a.elapsed>=0?0:1)*Math.min(1.3,.5*a.level+.9*a.gather+.6*a.flash)*(1+.12*Math.sin(c.labSeconds()*4.2))*fade;
        for(var bone:new BoneAnchorLayer.Bone[]{BoneAnchorLayer.Bone.LEFT_KNEE,BoneAnchorLayer.Bone.RIGHT_KNEE,BoneAnchorLayer.Bone.LEFT_HAND,BoneAnchorLayer.Bone.RIGHT_HAND}) {
            Vec3 at=c.body().surface(c.body().bone(bone));
            BtDraw.dot(c,MurimRenderTypes.mote(),at,.25,.10*ember,0xb8742e);
            BtDraw.dot(c,MurimRenderTypes.btBodyGlow(),at,.18,.14*l*fade,0xffc468);
        }
        BtDraw.floor(c,c.body().plate(400,594,0),c.body().px(260),.24*Math.min(1,a.level)*(1-smooth(15.2,15.5,c.labSeconds())),0xffc468,MurimRenderTypes.mote());
    }
    private static void haze(BtCtx c,Aura a) {
        if(a.elapsed>=0||a.level<=.01)return;
        for(int i=0;i<30;i++) {
            double th=(i+.6*random(i*23+17001))/30*6.283,rad=(150+80*random(i*23+17002))*(1-.35*a.gather);
            boolean front=Math.sin(th)>0;double h=(front?.26:1)*(200+170*random(i*23+17003))*(.6+.4*Math.min(1,a.level))*(1+.15*a.gather);
            double width=22+26*random(i*23+17004),ph=random(i*23+17005)*6.283,sp=.8+.6*random(i*23+17006);
            // Soft overlapping blobs make broad floor-rooted columns, never upright ribbon streaks.
            for(int j=0;j<=12;j++) {
                double f=j/12.0,y=h*f;
                Vec3 at=c.body().plate(400+Math.cos(th)*rad+Math.sin(c.labSeconds()*sp+ph+f*3)*9*f,
                        594+Math.sin(th)*rad*.22,y);
                double size=c.body().px(width*(2.4-.8*f));
                double alpha=Math.min(1,a.level)*(.055+.025*a.gather)*(front?.45:1)
                        *smooth(0,.15,f)*(1-smooth(.65,1,f));
                BtDraw.dot(c,MurimRenderTypes.mote(),at,size,alpha,0xb8742e);
                BtDraw.dot(c,MurimRenderTypes.mote(),at,size*.6,alpha*.65,0xffc468);
            }
        }
    }
    private static void pulse(BtCtx c,double e) {
        if(e<0||e>.7)return;
        double r=.18+1.72*(1-Math.pow(1-Math.min(1,e/.45),2));
        double a=smooth(0,.1,e)*(1-smooth(.25,.7,e));
        BtDraw.softAmberAnnulus(c,r,.1,a*.65);
        BtDraw.floor(c,c.body().plate(400,594,0),r,.3*a,0xffc468,MurimRenderTypes.mote());
    }
    private static void wave(BtCtx c,double e) {
        if(e<0||e>1.6)return;
        double grow=1-Math.exp(-e/.2),fade=Math.exp(-e*2.2)*smooth(0,.03,e);
        for(int set=0;set<2;set++)for(int i=0;i<(set==0?130:40)*BtScenes.quality(c.mc());i++) {
            int seed=i*29+(set==0?16000:16500);double th=(i+random(seed+1)*.8)/(set==0?130:40)*6.283,rad=(205+(set==0?460:-35)*grow)*(.94+.12*random(seed+2));
            Vec3 at=c.body().plate(400+Math.cos(th)*rad,594+Math.sin(th)*rad*.22,(3+12*random(seed+3))*Math.min(1,e*4));
            double al=.3*(set==0?1:.4)*fade*(.6+.4*random(seed+4)),sz=(6+8*random(seed+5))*(1+e*1.2);
            for(int m=0;m<2;m++)BtDraw.dot(c,MurimRenderTypes.impurity(),at.add(c.body().right().scale(c.body().px((random(seed+6+m)-.5)*sz))).add(0,c.body().px((random(seed+8+m)-.5)*sz*.4),0),c.body().px(sz*(.6+.25*m)),al,0x9ca0ba);
        }
    }
}
