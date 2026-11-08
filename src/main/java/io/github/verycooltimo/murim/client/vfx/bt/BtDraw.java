package io.github.verycooltimo.murim.client.vfx.bt;

import io.github.verycooltimo.murim.client.vfx.*;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;

/** Reuses VfxDraw vertex layout; layered ribbons replace Canvas blur with a crisp wider halo. */
final class BtDraw {
    private BtDraw() {}
    static void dot(BtCtx c,RenderType type,Vec3 at,double size,double alpha,int rgb) {
        if(size<=0||alpha<=.001||!c.body().faceClear(at))return;
        // Bound the whole quad away from the camera-side face, not just its centre.
        Vec3 q=at.subtract(c.body().axis());
        if(q.dot(c.body().near())>-.02 && Math.abs(q.dot(c.body().right()))<size+.22) {
            double crown=c.body().bone(BoneAnchorLayer.Bone.HEAD).y+.24;
            if(at.y<crown)size=Math.min(size,Math.max(0,c.body().ground()+.8-at.y));
            else size=Math.min(size,Math.max(0,at.y-crown));
        }
        if(size<=0)return;
        VfxDraw.billboard(c.buffers().getBuffer(type),c.pose(),at,c.camera(),size,(float)alpha,((rgb>>16)&255)/255f,((rgb>>8)&255)/255f,(rgb&255)/255f);
    }
    static void strip(BtCtx c,Vec3[] p,double[] w,double[] a,int colour,RenderType type) {
        var vc=c.buffers().getBuffer(type);
        // Shared side at every joint (RibbonMesher), without its .012 block minimum: lab tips taper to zero.
        Vec3[] sides=new Vec3[p.length];
        for(int i=0;i<p.length;i++) {
            Vec3 tangent=p[Math.min(p.length-1,i+1)].subtract(p[Math.max(0,i-1)]);
            sides[i]=tangent.cross(c.camera().subtract(p[i])).normalize();
            if(i>0&&sides[i].dot(sides[i-1])<0)sides[i]=sides[i].scale(-1);
        }
        for(int i=0;i<p.length-1;i++) {
            if(!c.body().faceClear(p[i])||!c.body().faceClear(p[i+1]))continue;
            Vec3 normal=c.camera().subtract(p[i]).normalize();
            Vec3[] q={p[i].subtract(sides[i].scale(w[i])),p[i+1].subtract(sides[i+1].scale(w[i+1])),p[i+1].add(sides[i+1].scale(w[i+1])),p[i].add(sides[i].scale(w[i]))};
            boolean clear=true;for(Vec3 v:q)if(!c.body().faceClear(v))clear=false;if(!clear)continue;
            for(int j=0;j<4;j++)VfxDraw.vertex(vc,c.pose(),q[j],normal,j==1||j==2?1:0,j>=2?1:0,(float)a[j==1||j==2?i+1:i],((colour>>16)&255)/255f,((colour>>8)&255)/255f,(colour&255)/255f);
        }
    }
    static Vec3 splinePoint(Vec3[] p,double segment) {
        int i=Math.min(p.length-2,(int)segment);double t=segment-i;
        Vec3 a=p[Math.max(0,i-1)],b=p[i],z=p[i+1],d=p[Math.min(p.length-1,i+2)];
        return new Vec3(BtSpline.sample(a.x,b.x,z.x,d.x,t),BtSpline.sample(a.y,b.y,z.y,d.y,t),BtSpline.sample(a.z,b.z,z.z,d.z,t));
    }
    /** One UV cell along the entire trail, shared per-node sides with sign continuity (RibbonMesher). */
    static void flame(BtCtx c,Vec3[] trail,double halfWidth,double alpha,double seed,int row) {
        if(trail.length<2||alpha<=.001)return;
        if(row==0){auraFlame(c,trail,halfWidth,alpha,seed);return;}
        int n=(trail.length-1)*6+1,col=Math.min(3,(int)(seed*4));
        Vec3[] p=new Vec3[n],side=new Vec3[n];double[] w=new double[n];
        for(int j=0;j<n;j++) {double u=j/(double)(n-1);p[j]=splinePoint(trail,u*(trail.length-1));w[j]=halfWidth*BtSpline.flameWidth(u);}
        for(int j=0;j<n;j++) {
            Vec3 tangent=p[Math.min(n-1,j+1)].subtract(p[Math.max(0,j-1)]);
            Vec3 v=tangent.cross(c.camera().subtract(p[j]));
            side[j]=v.lengthSqr()<1e-12?(j>0?side[j-1]:c.body().right()):v.normalize();
            if(j>0&&side[j].dot(side[j-1])<0)side[j]=side[j].scale(-1);
        }
        var vc=c.buffers().getBuffer(MurimRenderTypes.btFlame());
        for(int j=0;j<n-1;j++) {
            Vec3[] q={p[j].subtract(side[j].scale(w[j])),p[j+1].subtract(side[j+1].scale(w[j+1])),p[j+1].add(side[j+1].scale(w[j+1])),p[j].add(side[j].scale(w[j]))};
            boolean clear=true;for(Vec3 v:q)if(!c.body().faceClear(v))clear=false;if(!clear)continue;
            for(int k=0;k<4;k++) {
                double u=(col+(k>=2?1:0))/4.0,v=(row+1-(j+(k==1||k==2?1:0))/(double)(n-1))/2.0;
                // Half texel inset prevents neighbouring atlas cells bleeding under filtering.
                u=Math.max((col*128+.5)/512,Math.min(((col+1)*128-.5)/512,u));
                v=Math.max((row*256+.5)/512,Math.min(((row+1)*256-.5)/512,v));
                VfxDraw.vertex(vc,c.pose(),q[k],c.camera().subtract(p[j]).normalize(),(float)u,(float)v,(float)alpha,1,1,1);
            }
        }
    }

    static void firstFlame(BtCtx c,Vec3[] trail,double halfWidth,double alpha,double seed) {
        if(trail.length<2||alpha<=.001)return;
        // Source: approved aura-ref layers; lab bt3Fire trails retain their simulation.
        // Normal alpha carries dense blue mass, with transparent cross-section edges.
        blueFlame(c,trail,halfWidth,Math.min(1,alpha*1.15),seed,true);
        AuraRenderer.flowStrip(c.buffers().getBuffer(MurimRenderTypes.ribbon()),c.pose(),c.camera(),
                trail,halfWidth*1.25,(float)(alpha*.12),new VfxColour(22/255F,77/255F,180/255F),new VfxColour(37/255F,142/255F,232/255F),false,0,c.body()::faceClear);
        // Crisp brush marks belong only to selected crests, never every tongue's body.
        if(seed>.9) {
            Vec3[] crest=java.util.Arrays.copyOfRange(trail,Math.max(0,trail.length/2-1),trail.length);
            AuraRenderer.flowStrip(c.buffers().getBuffer(MurimRenderTypes.auraStrokes()),c.pose(),c.camera(),
                    crest,halfWidth*.12,(float)(alpha*.6),new VfxColour(184/255F,243/255F,1),new VfxColour(101/255F,216/255F,250/255F),true,
                    Math.min(7,(int)(seed*8)),c.body()::faceClear);
        }
    }

    /** Same particle-trail taper as AuraRenderer, with its glow/core and pressure brush atlas. */
    static void auraFlame(BtCtx c,Vec3[] trail,double width,double alpha,double seed) {
        var glow=c.buffers().getBuffer(MurimRenderTypes.ribbon());
        var clear=(java.util.function.Predicate<Vec3>)c.body()::faceClear;
        AuraRenderer.flowStrip(glow,c.pose(),c.camera(),trail,width*1.6,(float)(alpha*.5),
                FIRE_BODY,FIRE_EDGE,false,0,clear);
        AuraRenderer.flowStrip(glow,c.pose(),c.camera(),trail,width*.55,(float)(alpha*.55),
                FIRE_CORE,FIRE_MID,false,0,clear);
        var brush=c.buffers().getBuffer(MurimRenderTypes.auraStrokes());
        int cell=Math.min(7,(int)(seed*8));
        AuraRenderer.flowStrip(brush,c.pose(),c.camera(),trail,width*.72,(float)(alpha*.65),
                FIRE_EDGE,FIRE_EDGE,true,cell,clear);
        AuraRenderer.flowStrip(brush,c.pose(),c.camera(),trail,width*.2,(float)(alpha*.8),
                FIRE_CORE,FIRE_MID,true,cell,clear);
    }
    private static final VfxColour FIRE_EDGE=new VfxColour(10/255F,29/255F,110/255F),
            FIRE_BODY=new VfxColour(34/255F,118/255F,1),FIRE_MID=new VfxColour(120/255F,217/255F,1),
            FIRE_CORE=new VfxColour(232/255F,253/255F,1);

    // Source: lab-bt_third.js bt3Fire shader hash/noise/fbm (shared world field, no per-card phase).
    private static double flameHash(double x,double y) {
        double h=Math.sin(x*127.1+y*311.7+5)*43758.5453;return h-Math.floor(h);
    }
    private static double flameNoise(double x,double y) {
        double ix=Math.floor(x),iy=Math.floor(y),u=x-ix,v=y-iy;
        u=u*u*(3-2*u);v=v*v*(3-2*v);
        return (flameHash(ix,iy)*(1-u)+flameHash(ix+1,iy)*u)*(1-v)
                +(flameHash(ix,iy+1)*(1-u)+flameHash(ix+1,iy+1)*u)*v;
    }
    private static double flameFbm(double x,double y) {
        return .57*flameNoise(x,y)+.28*flameNoise(x*2.03,y*2.03)+.15*flameNoise(x*4.11,y*4.11);
    }
    /** Broad smooth S-edges and a continuous blue cross-gradient, without folded atlas quads. */
    private static void blueFlame(BtCtx c,Vec3[] trail,double halfWidth,double alpha,double seed) {
        blueFlame(c,trail,halfWidth,alpha,seed,false);
    }
    private static void blueFlame(BtCtx c,Vec3[] trail,double halfWidth,double alpha,double seed,boolean first) {
        int n=first?49:17;
        Vec3[] p=new Vec3[n],side=new Vec3[n];double[] w=new double[n];
        // Source: lab-bt_third.js width(u,W), flame noise in plate coordinates shared by all tongues.
        for(int j=0;j<n;j++) {
            double u=j/(double)(n-1);
            p[j]=splinePoint(trail,u*(trail.length-1));
            Vec3 tangent=splinePoint(trail,Math.min(1,u+.001)*(trail.length-1))
                    .subtract(splinePoint(trail,Math.max(0,u-.001)*(trail.length-1)));
            side[j]=tangent.cross(c.camera().subtract(p[j])).normalize();
            if(j>0&&side[j].dot(side[j-1])<0)side[j]=side[j].scale(-1);
            double x=400+p[j].subtract(c.body().axis()).dot(c.body().right())*360;
            double y=585-(p[j].y-c.body().ground())*360,clock=c.labSeconds()*1.5;
            double warp=(flameNoise(x*.04,y*.033+clock*2.3)-.5)*14
                    +(flameNoise(x*.09+clock*.7,y*.06+clock*3.3)-.5)*7;
            double detail=flameFbm(x*.05+warp*.035,y*.026+clock*1.9);
            if(!first)p[j]=p[j].add(c.body().right().scale(-warp/360));
            // A short rounded foot opens into the wavy body; the tip stays sharp.
            double base=first?(u<.18?Math.sqrt(Math.max(0,1-Math.pow((u-.18)/.22,2))):1):Math.min(1,.25+u*4);
            w[j]=first?BtFirstTongue.width(halfWidth,u,seed*6-c.labSeconds()*5)/2
                    :(halfWidth*base*Math.pow(1-u,.8)+1.0/360)*(.85+.3*detail);
            if(j==n-1)w[j]=0;
        }
        double[] stops={-1,-.88,-.68,-.38,-.09,0,.09,.38,.68,.88,1};
        int[] colours={0x0a1d6e,0x0a1d6e,0x2276ff,0x78d9ff,0x78d9ff,0xe8fdff,0x78d9ff,0x78d9ff,0x2276ff,0x0a1d6e,0x0a1d6e};
        // Only the hottest First Rate tongues have a pale centre, never a pale edge.
        boolean hot=!first;
        if(first)colours=new int[]{0x164db4,0x164db4,0x258ee8,0x258ee8,0x65d8fa,0xb8f3ff,0x65d8fa,0x258ee8,0x258ee8,0x164db4,0x164db4};
        // Alpha-over carries the dark rim and blue body; only the narrow hot centre adds light.
        for(int pass=0;pass<2;pass++) {
            if(pass==1&&!hot)continue;
            var vc=c.buffers().getBuffer(pass==0?MurimRenderTypes.btEssenceBody():MurimRenderTypes.ribbon());
            for(int j=0;j<n-1;j++)for(int row=pass==0?0:1;row<(pass==0?stops.length-1:stops.length-2);row++) {
                Vec3[] q=new Vec3[4];
                for(int k=0;k<4;k++){int at=j+(k==1||k==2?1:0),r=row+(k>=2?1:0);q[k]=p[at].add(side[at].scale(w[at]*stops[r]));}
                boolean clear=true;for(var at:q)if(!c.body().faceClear(at))clear=false;if(!clear)continue;
                for(int k=0;k<4;k++) {
                    int r=row+(k>=2?1:0),rgb=colours[r];
                    double coverage=pass==0?(Math.abs(stops[r])==1?0:Math.abs(stops[r])>=.76?.85:1):(Math.abs(stops[r])>=.38?0:.24);
                    VfxDraw.vertex(vc,c.pose(),q[k],c.body().near(),.5F,.5F,(float)(alpha*coverage),
                            (rgb>>16&255)/255F,(rgb>>8&255)/255F,(rgb&255)/255F);
                }
            }
        }
    }

    /** BRIEF-4: one textured annular band, with a soft edge and saturated blue centre. */
    static void softHalo(BtCtx c,Vec3 centre,Vec3 a,Vec3 b,double halfWidth,double alpha) {
        var vc=c.buffers().getBuffer(MurimRenderTypes.ribbon());
        double[] offsets={-.165,-.09,-halfWidth,0,halfWidth};
        double[] opacity={0,.10,.18,.35,0};
        for(int band=0;band<offsets.length-1;band++) {
            Vec3[] q={a.add(a.subtract(centre).normalize().scale(offsets[band])),
                    b.add(b.subtract(centre).normalize().scale(offsets[band])),
                    b.add(b.subtract(centre).normalize().scale(offsets[band+1])),
                    a.add(a.subtract(centre).normalize().scale(offsets[band+1]))};
            boolean clear=true;for(var at:q)if(!c.body().faceClear(at))clear=false;if(!clear)continue;
            for(int j=0;j<4;j++)VfxDraw.vertex(vc,c.pose(),q[j],c.body().near(),.5F,.5F,
                    (float)(alpha*opacity[band+(j>=2?1:0)]),181/255F,238/255F,1);
        }
    }

    static void line(BtCtx c,Vec3 a,Vec3 b,double width,double alpha,int rgb) {
        Vec3[] p=new Vec3[25];double[] w=new double[25],al=new double[25];
        for(int i=0;i<=24;i++){double f=i/24.0;p[i]=a.scale(1-f).add(b.scale(f));w[i]=width;al[i]=alpha;}
        strip(c,p,w,al,rgb,MurimRenderTypes.ribbon());
    }

    /** Vertex-sampled smooth cross-section; uses the centre texel so the texture cannot add a hard rim. */
    static void softLine(BtCtx c,Vec3 a,Vec3 b,double width,double alpha,int rgb) {
        softStrip(c,new Vec3[]{a,b},new double[]{width,width},new double[]{alpha,alpha},rgb);
    }
    static void softStrip(BtCtx c,Vec3[] p,double[] w,double[] alpha,int rgb) {
        var vc=c.buffers().getBuffer(MurimRenderTypes.ribbon());
        Vec3 previous=null;
        Vec3[] side=new Vec3[p.length];
        for(int j=0;j<p.length;j++) {
            Vec3 tangent=p[Math.min(p.length-1,j+1)].subtract(p[Math.max(0,j-1)]);
            Vec3 v=tangent.cross(c.camera().subtract(p[j])).normalize();
            if(previous!=null&&v.dot(previous)<0)v=v.scale(-1);
            side[j]=v;previous=v;
        }
        for(int j=0;j<p.length-1;j++)for(int row=0;row<16;row++) {
            Vec3[] q=new Vec3[4];double[] aa=new double[4];
            for(int k=0;k<4;k++) {
                int n=j+(k==1||k==2?1:0);
                double u=-1+2*(row+(k>=2?1:0))/16.0;
                q[k]=p[n].add(side[n].scale(w[n]*u));
                aa[k]=alpha[n]*(1-BtMath.smooth(0,1,Math.abs(u)));
            }
            boolean clear=true;for(var at:q)if(!c.body().faceClear(at))clear=false;if(!clear)continue;
            for(int k=0;k<4;k++)VfxDraw.vertex(vc,c.pose(),q[k],c.body().near(),.5F,.5F,(float)aa[k],(rgb>>16&255)/255F,(rgb>>8&255)/255F,(rgb&255)/255F);
        }
    }

    static void floor(BtCtx c,Vec3 at,double radius,double alpha,int rgb,RenderType type) {
        var vc=c.buffers().getBuffer(type);Vec3 up=new Vec3(0,1,0);
        Vec3[] q={at.add(-radius,0,-radius),at.add(radius,0,-radius),at.add(radius,0,radius),at.add(-radius,0,radius)};
        for(int j=0;j<4;j++)VfxDraw.vertex(vc,c.pose(),q[j],up,j==1||j==2?1:0,j>=2?1:0,(float)alpha,((rgb>>16)&255)/255f,((rgb>>8)&255)/255f,(rgb&255)/255f);
    }
    /** Continuous floor band with radial width; no noise gaps or camera-facing hairline. */
    static void annulus(BtCtx c,double radius,double halfWidth,double alpha,int rgb) {
        Vec3 centre=c.body().plate(400,594,0),up=new Vec3(0,1,0);
        var vc=c.buffers().getBuffer(MurimRenderTypes.ribbon());
        double inner=Math.max(0,radius-halfWidth),outer=radius+halfWidth;
        for(int i=0;i<128;i++) {
            double th=i*Math.PI*2/128,next=(i+1)*Math.PI*2/128;
            Vec3 a=new Vec3(Math.cos(th),0,Math.sin(th)),b=new Vec3(Math.cos(next),0,Math.sin(next));
            Vec3[] q={centre.add(a.scale(inner)),centre.add(b.scale(inner)),centre.add(b.scale(outer)),centre.add(a.scale(outer))};
            for(int j=0;j<4;j++)VfxDraw.vertex(vc,c.pose(),q[j],up,j==1||j==2?1:0,j>=2?1:0,(float)alpha,(rgb>>16&255)/255F,(rgb>>8&255)/255F,(rgb&255)/255F);
        }
    }
    /** Round-5 lab pulse: 0.2 block across, continuous amber/ochre gradient with transparent edges. */
    static void softAmberAnnulus(BtCtx c,double radius,double halfWidth,double alpha) {
        Vec3 centre=c.body().plate(400,594,0),up=new Vec3(0,1,0);
        var vc=c.buffers().getBuffer(MurimRenderTypes.btEssenceBody());
        for(int row=0;row<16;row++)for(int i=0;i<128;i++) {
            double th=i*Math.PI*2/128,next=(i+1)*Math.PI*2/128;
            for(int j=0;j<4;j++) {
                double f=(row+(j>=2?1:0))/16.0,d=Math.abs(2*f-1);
                double r=Math.max(0,radius+(2*f-1)*halfWidth),angle=j==1||j==2?next:th;
                int rgb=BtRank1.mix(0xffc468,0xb8742e,d);
                Vec3 at=centre.add(Math.cos(angle)*r,0,Math.sin(angle)*r);
                VfxDraw.vertex(vc,c.pose(),at,up,.5F,.5F,(float)(alpha*(1-BtMath.smooth(.4,1,d))),
                        (rgb>>16&255)/255F,(rgb>>8&255)/255F,(rgb&255)/255F);
            }
        }
    }
    static void ring(BtCtx c,double radius,double width,double alpha,int rgb,double seed) {
        for(int i=0;i<96;i++) {
            double th=i*Math.PI*2/96,next=(i+1)*Math.PI*2/96,n=Math.max(0,BtMath.noise(i/96.0*14,seed)-.22)*1.6;
            Vec3 a=c.body().plate(400+Math.cos(th)*radius,594+Math.sin(th)*radius*.22,0),b=c.body().plate(400+Math.cos(next)*radius,594+Math.sin(next)*radius*.22,0);
            Vec3 ra=a.subtract(new Vec3(c.body().axis().x,a.y,c.body().axis().z)).normalize().scale(c.body().px(width*n));
            Vec3 rb=b.subtract(new Vec3(c.body().axis().x,b.y,c.body().axis().z)).normalize().scale(c.body().px(width*n));
            var vc=c.buffers().getBuffer(MurimRenderTypes.ribbon());Vec3[] q={a.subtract(ra),b.subtract(rb),b.add(rb),a.add(ra)};
            for(int j=0;j<4;j++)VfxDraw.vertex(vc,c.pose(),q[j],new Vec3(0,1,0),j==1||j==2?1:0,j>=2?1:0,(float)alpha,((rgb>>16)&255)/255f,((rgb>>8)&255)/255f,(rgb&255)/255f);
        }
    }
}
