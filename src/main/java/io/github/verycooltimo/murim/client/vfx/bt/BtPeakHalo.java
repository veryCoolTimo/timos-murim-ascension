package io.github.verycooltimo.murim.client.vfx.bt;

import io.github.verycooltimo.murim.client.vfx.MurimRenderTypes;
import io.github.verycooltimo.murim.client.vfx.VfxDraw;
import net.minecraft.world.phys.Vec3;
import static io.github.verycooltimo.murim.client.vfx.bt.BtMath.*;

/** Source: lab-bt_peak.js btpHalo (263–296). Soft screen-space shell, no face light. */
final class BtPeakHalo {
    /**
     * The lab's sphere (285 plate px) encloses the painted hero with room to spare; round the block body, wider and taller on screen,
     * the same radius hugged the silhouette and the rim hid behind the arms and the ribbons (31.10 film: «a weak arc»). 1.3 times the
     * lab's radius, as the fire's spread.
     */
    static final double SIZE=.815*1.3;
    static double radius(double t,double th) {
        return SIZE*BtPeakMath.haloScale(t)*(1+.03*Math.sin(2*th+1.3*t)
                +.018*Math.sin(5*th-.9*t+1)+.012*Math.sin(9*th+2.1*t));
    }
    static double height() {return .72;}
    static void render(BtCtx c,double gain) {
        shell(c,gain,c.buffers().getBuffer(MurimRenderTypes.ribbon()));
        // its glow (author 31.10: «не хватает глоу»): the same bands into the glow target
        if(BtGlow.active()) {
            c.buffers().endBatch(MurimRenderTypes.ribbon());
            var glow=BtGlow.textured(MurimRenderTypes.ribbonTexture());
            shell(c,gain,c.buffers().getBuffer(glow));
            c.buffers().endBatch(glow);
        }
        motes(c,gain);
    }
    private static void shell(BtCtx c,double gain,com.mojang.blaze3d.vertex.VertexConsumer vc) {
        double t=c.labSeconds(),r=SIZE*BtPeakMath.haloScale(t);
        Vec3 centre=new Vec3(c.body().axis().x,c.body().ground()+height(),c.body().axis().z)
                .subtract(c.body().near().scale(.32));
        Vec3 right=c.body().right(),up=new Vec3(0,1.43/1.63,0);
        double hot=-.8+.3*Math.sin(t*.37);
        for(int j=0;j<120;j++) {
            double th=j*Math.PI*2/120,dist=Math.atan2(Math.sin(th-hot),Math.cos(th-hot));
            double patch=Math.min(1,.4+.45*noise(th*1.6+t*.25,7)+.35*Math.exp(-dist*dist/.5));
            double dark=Math.atan2(Math.sin(th-Math.PI*1.05),Math.cos(th-Math.PI*1.05));
            double lum=patch*(1-.55*Math.exp(-dark*dark/.25))*(1-smooth(.3,.85,Math.sin(th)));
            // 46px shell + 22px blur drawn at .9 in the lab: about .19 block of feathered band at .9 (codex 31.10 next to the lab: the
            // port's shell read as a weak arc, the lab's as a clearly enclosed bubble with a bright rim).
            band(vc,c,centre,right,up,th,Math.PI*2/120,radius(t,th)*.97,.19,gain*lum*.9,0x68c5f4);
            // One continuous feathered interior, lighter towards the edge, rather than eight visible annuli.
            band(vc,c,centre,right,up,th,Math.PI*2/120,r*.72,r*.27,
                    gain*.15*lum,0x96d7ff);
        }
        double rim=-1.9+.12*Math.sin(t*.3);
        arc(vc,c,centre,right,up,t,.98,rim,1.8,40.0/720,.6*gain,0xc8f0ff);
        arc(vc,c,centre,right,up,t,.98,rim+.12,1.5,16.0/720,.7*gain,0xebfaff);
    }
    private static void motes(BtCtx c,double gain) {
        double t=c.labSeconds(),r=SIZE*BtPeakMath.haloScale(t);
        Vec3 centre=new Vec3(c.body().axis().x,c.body().ground()+height(),c.body().axis().z)
                .subtract(c.body().near().scale(.32));
        Vec3 right=c.body().right(),up=new Vec3(0,1.43/1.63,0);
        for(int i=0;i<9;i++) {
            int seed=i*31+5500;double an=random(seed+1)*Math.PI*2+t*.05*(random(seed+2)-.5),rr=r*(.45+.4*random(seed+3));
            if(Math.sin(an)*rr>r*.55)continue;
            Vec3 at=centre.add(right.scale(Math.cos(an)*rr)).add(up.scale(-Math.sin(an)*rr*.95));
            if(c.body().faceClear(at))BtDraw.dot(c,MurimRenderTypes.mote(),at,r*(.25+.2*random(seed+4)),gain*(.05+.06*random(seed+6)),random(seed+5)<.5?0x4b9fe8:0x80dfff);
        }
    }
    private static void arc(com.mojang.blaze3d.vertex.VertexConsumer vc,BtCtx c,Vec3 centre,Vec3 right,Vec3 up,double t,double scale,double start,double span,double width,double alpha,int rgb) {
        for(int j=0;j<48;j++) {
            double th=start+span*j/48;
            if(Math.sin(th)>.7)continue;
            band(vc,c,centre,right,up,th,span/48,radius(t,th)*scale,width,
                    alpha*Math.pow(Math.sin(Math.PI*(j+.5)/48),.5),rgb);
        }
    }
    /** Gaussian cross-section approximates the lab's two blurred canvas bands. */
    private static void band(com.mojang.blaze3d.vertex.VertexConsumer vc,BtCtx c,Vec3 centre,Vec3 right,Vec3 up,double th,double step,double r,double width,double alpha,int rgb) {
        for(int row=0;row<8;row++) {
            Vec3[] points=new Vec3[4];double[] al=new double[4];
            for(int k=0;k<4;k++) {
                double u=-1+(row+(k>=2?1:0))/4.0,angle=th+(k==1||k==2?step:0),rr=r+u*width;
                points[k]=centre.add(right.scale(Math.cos(angle)*rr)).add(up.scale(-Math.sin(angle)*rr));
                al[k]=alpha*(Math.exp(-u*u*4)-Math.exp(-4));
            }
            boolean clear=true;for(Vec3 p:points)if(!c.body().faceClear(p))clear=false;
            if(!clear)continue;
            for(int k=0;k<4;k++)VfxDraw.vertex(vc,c.pose(),points[k],c.body().near(),.5F,.5F,(float)al[k],
                    (rgb>>16&255)/255F,(rgb>>8&255)/255F,(rgb&255)/255F);
        }
    }
    private BtPeakHalo() {}
}
