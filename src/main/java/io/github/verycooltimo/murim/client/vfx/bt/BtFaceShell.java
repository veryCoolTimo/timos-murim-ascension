package io.github.verycooltimo.murim.client.vfx.bt;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone;
import io.github.verycooltimo.murim.client.vfx.MurimRenderTypes;
import io.github.verycooltimo.murim.client.vfx.VfxDraw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * The Peak's face shell, the lab's as it is (lab-bt_peak.js btpShell, btpShellGlow, btpBlaze, btpSeep, btpPeels, btpBurst,
 * btpBurstDust, btpCrumbs on the fracture of {@link BtFaceFrac}). Author 31.10 on the previous port (a texture mosaic over the face):
 * «в лабе трещины рандомные, аккуратные кусочки падают красиво и горят, а тут мозаика просто накладывается».
 * <ul>
 *   <li>Three layers over the head's front face (BoneAnchorLayer FACE_* corners), painted as the lab paints its canvases:
 *       the ink (dark lines of three weights, dark lips, dark rims and depths of the holes, the old shell darkening; lit by the world),
 *       the light (lenses of cold light, holes full of light; unlit, additive) and the glow (the lab's glow canvas blurred at three
 *       radii, the blaze at the blow; additive, on a quad twice the face so it spills onto the hair and the air). Round 1 drew the
 *       light lit by the world (white turned grey at night: «мозаика») and its glow as dots that the face guard dropped.</li>
 *   <li>The plates that come off are real pieces: the skin itself (the player's own face texture) lifts, turns over showing its
 *       dark broken side, and falls; each catches the light and its hole pulses. At the blow the whole shell comes off in a cascade
 *       outwards from the first crack, each piece falling the same way and turning into sparks as it fades.</li>
 *   <li>No light over the eyes (author: no light from the eyes; the lab clears its light layer above the cheek, btpSeep).</li>
 * </ul>
 * API: NativeImage#setPixelRGBA takes ABGR; DynamicTexture#upload; RenderType#entityTranslucent(ResourceLocation) (lit, alpha);
 * RenderType#eyes(ResourceLocation) (rendertype_eyes: unlit, additive ONE/ONE, so the light texture holds premultiplied colour);
 * AbstractClientPlayer#getSkin().texture() (the head front is pixels 8..16 x 8..16 of the 64×64 skin).
 */
final class BtFaceShell {
    private static final int N=128,GN=64;
    /** The glow quad reaches this far past the face on every side, in face widths. */
    private static final double GM=.5;
    private static final ResourceLocation INK_TEX=ResourceLocation.fromNamespaceAndPath("murim","dynamic/bt_face_ink"),
            LIGHT_TEX=ResourceLocation.fromNamespaceAndPath("murim","dynamic/bt_face_light"),
            GLOW_TEX=ResourceLocation.fromNamespaceAndPath("murim","dynamic/bt_face_glow");
    private static DynamicTexture inkTexture,lightTexture,glowTexture;
    /** Where the lab's face rect sits on the block face (u from the player's right, v from the brow): the first crack on the cheek
     * beside the nose, one row under the eye row (round 1 had it on the eye row and its holes covered the eye). */
    static final double U0=.03,V0=.28;
    private static final double BLOW=BtPeakMath.BLOW;
    private static BtFaceFrac frac;
    private static final Layer INK=new Layer(N,0),LIGHT=new Layer(N,0),GLOW=new Layer(GN,GM),BLUR=new Layer(GN,GM),SUM=new Layer(GN,GM);

    static double faceU(double x) { return (x-BtFaceFrac.RX)/BtFaceFrac.RW+U0; }
    static double faceV(double y) { return (y-BtFaceFrac.RY)/BtFaceFrac.RH+V0; }

    void render(BtCtx c) {
        double t=c.labSeconds();
        if(t<4.4||t>BLOW+2.8)return;
        Vec3 rt=c.body().bones().get(Bone.FACE_RIGHT_TOP),lt=c.body().bones().get(Bone.FACE_LEFT_TOP),rb=c.body().bones().get(Bone.FACE_RIGHT_BOTTOM);
        if(rt==null||lt==null||rb==null)return;
        if(frac==null)frac=new BtFaceFrac();
        Vec3 uAxis=lt.subtract(rt),vAxis=rb.subtract(rt),normal=vAxis.cross(uAxis).normalize();
        Face f=new Face(rt,uAxis,vAxis,normal);
        if(inkTexture==null) {
            inkTexture=new DynamicTexture(N,N,true);lightTexture=new DynamicTexture(N,N,true);glowTexture=new DynamicTexture(GN,GN,true);
            var tm=Minecraft.getInstance().getTextureManager();
            tm.register(INK_TEX,inkTexture);tm.register(LIGHT_TEX,lightTexture);tm.register(GLOW_TEX,glowTexture);
        }
        if(t<BLOW) {
            paintShell(t);
            INK.writeAlpha(inkTexture.getPixels());inkTexture.upload();
            LIGHT.writeAdditive(lightTexture.getPixels(),true,1);lightTexture.upload();
            quad(c,f,RenderType.entityTranslucent(INK_TEX),0,.004);
            quad(c,f,RenderType.eyes(LIGHT_TEX),0,.005);
        }
        if(paintGlow(t)) {
            // the eyes stay dim in the glow and the blaze, not black: a fully cleared row read as a dark visor across the lit face
            SUM.writeAdditive(glowTexture.getPixels(),false,.6);
            // Linear filtering: a 64-texel glow under nearest filtering showed its texels in a close-up. DynamicTexture#upload
            // uploads with blur=false, so upload here with blur=true, clamp=true.
            // API: com.mojang.blaze3d.platform.NativeImage#upload(level,x,y,skipPixels,skipRows,width,height,blur,clamp,mipmap,autoClose)
            glowTexture.bind();glowTexture.getPixels().upload(0,0,0,0,0,GN,GN,true,true,false,false);
            quad(c,f,RenderType.eyes(GLOW_TEX),GM,.006);
        }
        seep(c,t,f);
        pieces(c,t,f);
        crumbs(c,t,f);
    }

    /** The face square widened by {@code m} face widths on every side, {@code out} blocks off the face. */
    private static void quad(BtCtx c,Face f,RenderType type,double m,double out) {
        var buffer=c.buffers().getBuffer(type);
        Vec3 lift=f.normal().scale(out);
        Vec3 a=f.corner(-m,-m).add(lift),b=f.corner(-m,1+m).add(lift),d=f.corner(1+m,1+m).add(lift),e=f.corner(1+m,-m).add(lift);
        VfxDraw.vertex(buffer,c.pose(),a,f.normal(),0,0,1,1,1,1);
        VfxDraw.vertex(buffer,c.pose(),b,f.normal(),0,1,1,1,1,1);
        VfxDraw.vertex(buffer,c.pose(),d,f.normal(),1,1,1,1,1,1);
        VfxDraw.vertex(buffer,c.pose(),e,f.normal(),1,0,1,1,1,1);
    }

    /** The face's frame: lab px to the world. */
    private record Face(Vec3 origin,Vec3 u,Vec3 v,Vec3 normal) {
        Vec3 at(double x,double y,double out) { return origin.add(u.scale(faceU(x))).add(v.scale(faceV(y))).add(normal.scale(out)); }
        Vec3 corner(double fu,double fv) { return origin.add(u.scale(fu)).add(v.scale(fv)); }
        /** Blocks per lab px across the face. */
        double px() { return u.length()/BtFaceFrac.RW; }
    }

    /**
     * A canvas over the face square widened by {@code m} face widths on every side; premultiplied RGBA, painted source-over as the
     * lab's 2D canvases are.
     */
    private static final class Layer {
        final int n;final double m;
        final float[] r,g,b,a;
        Layer(int n,double m) { this.n=n;this.m=m;r=new float[n*n];g=new float[n*n];b=new float[n*n];a=new float[n*n]; }
        void clear() { java.util.Arrays.fill(r,0);java.util.Arrays.fill(g,0);java.util.Arrays.fill(b,0);java.util.Arrays.fill(a,0); }
        double tx(double x) { return (faceU(x)+m)/(1+2*m)*n; }
        double ty(double y) { return (faceV(y)+m)/(1+2*m)*n; }
        double x(int i) { return BtFaceFrac.RX+((i+.5)/n*(1+2*m)-m-U0)*BtFaceFrac.RW; }
        double y(int j) { return BtFaceFrac.RY+((j+.5)/n*(1+2*m)-m-V0)*BtFaceFrac.RH; }
        /** Source-over in premultiplied form. */
        void over(int k,float cr,float cg,float cb,float ca) {
            if(ca<=0)return;ca=Math.min(1,ca);
            r[k]=cr*ca+r[k]*(1-ca);g[k]=cg*ca+g[k]*(1-ca);b[k]=cb*ca+b[k]*(1-ca);a[k]=ca+a[k]*(1-ca);
        }
        /** For an alpha-blended type: straight colour, alpha on skin only. */
        void writeAlpha(NativeImage img) {
            for(int j=0;j<n;j++)for(int i=0;i<n;i++) {
                int k=j*n+i;if(a[k]<=0){img.setPixelRGBA(i,j,0);continue;}
                double s=BtFaceFrac.onSkin(x(i),y(j)),ia=1/Math.max(1e-6,a[k]);
                img.setPixelRGBA(i,j,abgr(a[k]*s,r[k]*ia,g[k]*ia,b[k]*ia));
            }
        }
        /** For an additive type: premultiplied colour; {@code skin} keeps it on the skin; {@code eyes} of it cleared over the eyes. */
        void writeAdditive(NativeImage img,boolean skin,double eyes) {
            for(int j=0;j<n;j++) {
                double eye=1-eyes*eyeClear(y(j));
                for(int i=0;i<n;i++) {
                    int k=j*n+i;double s=eye*(skin?BtFaceFrac.onSkin(x(i),y(j)):1);
                    if(s<=0||(r[k]<=0&&g[k]<=0&&b[k]<=0)){img.setPixelRGBA(i,j,0);continue;}
                    img.setPixelRGBA(i,j,abgr(1,r[k]*s,g[k]*s,b[k]*s));
                }
            }
        }
    }

    /**
     * No light from the eyes: the lab clears its light layer above the cheek (btpSeep), where its hair hides the eyes and the brow.
     * The block face shows its brow, so only the eye row is cleared (round 2 cleared everything above it and the blaze lit only the
     * lower face — codex 31.10).
     */
    private static double eyeClear(double y) {
        double v=faceV(y);
        return BtMath.smooth(.44,.5,v)*(1-BtMath.smooth(.625,.68,v));
    }

    // ---------------------------------------------------------------- the shell (btpShell)
    private static double flick(double t,double k) { return .85+.15*Math.sin(t*9.3+k*2.1)*Math.sin(t*5.1+k); }

    private static void paintShell(double t) {
        INK.clear();LIGHT.clear();
        BtFaceFrac F=frac;
        // The old shell darkens where the cracks have run, so the light from the openings reads against it.
        double rr=4+20*BtMath.smooth(4.4,9.6,t)+30*BtMath.smooth(20.4,23.8,t)+40*BtMath.smooth(BLOW-.5,BLOW-.1,t),al=.3+.14*BtMath.smooth(20.4,23.8,t);
        for(int j=0;j<N;j++)for(int i=0;i<N;i++) {
            double d=Math.hypot(INK.x(i)-BtFaceFrac.OX,INK.y(j)-BtFaceFrac.OY)/rr;if(d>=1)continue;
            double a=d<.75?al*(1-.2*d/.75):al*.8*(1-(d-.75)/.25);
            INK.over(j*N+i,36/255F,32/255F,48/255F,(float)a);
        }
        // Seams: dark lines in three weights (the lab strokes each twice: a soft brown rim, then the ink).
        double ink=N/BtFaceFrac.RW;
        for(var s:F.segs) {
            double[] P=s.pts();int n=P.length/3;
            // The lab's web is dense round the wound; on the bigger block face the long cracks far from it read as black borders of
            // a mosaic (codex 31.10), so they thin and lighten with the distance from the first crack.
            double far=BtMath.smooth(BtFaceFrac.R1,BtFaceFrac.R2+8,Math.hypot(P[(n/2)*3]-BtFaceFrac.OX,P[(n/2)*3+1]-BtFaceFrac.OY));
            double w=s.w()*(1-.35*far);float da=(float)(1-.45*far);
            int start=-1;
            for(int k=0;k<=n;k++) {
                boolean on=k<n&&P[k*3+2]<=t;
                if(on&&start<0)start=k;
                if(!on&&start>=0) {
                    if(k-start>1){line(INK,P,start,k,w*1.45*ink,51/255F,42/255F,43/255F,.6F*da);line(INK,P,start,k,w*ink,18/255F,21/255F,27/255F,da);}
                    start=-1;
                }
            }
        }
        // An open seam is a lens of light between dark lips, widest in the middle of the crack; only a short piece of it opens.
        double st2=BtMath.smooth(BtFaceFrac.T2,BtFaceFrac.T2+3,t);
        float lr=(float)(st2>.5?86/255.:215/255.),lg=(float)(st2>.5?222/255.:247/255.),lb=1;
        float cr=(float)(st2>.5?240/255.:1),cg=(float)(st2>.5?253/255.:1);
        for(var s:F.segs) {
            double o=BtMath.clamp((t-s.open())/s.od());if(o<=0)continue;
            double[] P=s.pts();int n=P.length/3;
            lens(INK,s,P,n,t,o,1,.42,18/255F,21/255F,27/255F,1F);
            lens(LIGHT,s,P,n,t,o,1,0,lr,lg,lb,(float)flick(t,1));
            lens(LIGHT,s,P,n,t,o,.42,0,cr,cg,1F,(float)flick(t,4));
        }
        // Holes where plates came off: light from the depth — paler at first, saturated later; a dark rim and a dark inner wall.
        for(var p:F.peels) {
            double e=t-p.t();if(e<.12)continue;
            double a=Math.min(1,(e-.12)/.45)*flick(t,p.t());if(a<=0)continue;
            hole(p,a,p.stage()==1);
        }
    }

    /** A round-capped polyline P[from..to) of width w texels, anti-aliased by coverage of the distance to it. */
    private static void line(Layer L,double[] P,int from,int to,double w,float r,float g,float b,float a) {
        double minX=1e9,minY=1e9,maxX=-1e9,maxY=-1e9;
        for(int k=from;k<to;k++){double x=L.tx(P[k*3]),y=L.ty(P[k*3+1]);minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);}
        double hw=Math.max(.35,w/2);int n=L.n;
        int i0=Math.max(0,(int)Math.floor(minX-hw-1)),i1=Math.min(n-1,(int)Math.ceil(maxX+hw+1)),j0=Math.max(0,(int)Math.floor(minY-hw-1)),j1=Math.min(n-1,(int)Math.ceil(maxY+hw+1));
        for(int j=j0;j<=j1;j++)for(int i=i0;i<=i1;i++) {
            double px=i+.5,py=j+.5,best=1e9;
            for(int k=from;k<to-1;k++)best=Math.min(best,dist(px,py,L.tx(P[k*3]),L.ty(P[k*3+1]),L.tx(P[k*3+3]),L.ty(P[k*3+4])));
            double cov=BtMath.clamp(hw-best+.5);
            // thin lines fade by coverage rather than vanish
            if(w<1)cov*=Math.max(.35,w);
            if(cov>0)L.over(j*n+i,r,g,b,(float)(a*cov));
        }
    }
    private static double dist(double px,double py,double ax,double ay,double bx,double by) {
        double dx=bx-ax,dy=by-ay,l=dx*dx+dy*dy,h=l<1e-12?0:BtMath.clamp(((px-ax)*dx+(py-ay)*dy)/l);
        return Math.hypot(px-ax-dx*h,py-ay-dy*h);
    }

    /** One lens: the open run of a seam, as wide as {@code (ow * o * k + add)} in its middle, filled with a colour. */
    private static void lens(Layer L,BtFaceFrac.Seg s,double[] P,int n,double t,double o,double k,double add,float r,float g,float b,float a) {
        double[][] poly=lensPoly(s,P,n,t,o,k,add);
        if(poly!=null)fill(L,poly,(x,y)->new float[]{r,g,b,a});
    }
    /** The lab fills the run of the crack that the front has reached. */
    private static double[][] lensPoly(BtFaceFrac.Seg s,double[] P,int n,double t,double o,double k,double add) {
        int last=-1;for(int j=0;j<n;j++)if(P[j*3+2]<=t)last=j;
        if(last<1)return null;
        double[][] poly=new double[(last+1)*2][];
        for(int j=0;j<=last;j++) {
            double q=BtMath.clamp((j/(double)(n-1)-s.oc()+s.oh())/(2*s.oh())),w=(s.ow()*o*k+add)*Math.pow(Math.sin(Math.PI*q),.6)/2;
            poly[j]=new double[]{P[j*3]+s.nx()*w,P[j*3+1]+s.ny()*w};
            poly[poly.length-1-j]=new double[]{P[j*3]-s.nx()*w,P[j*3+1]-s.ny()*w};
        }
        return poly;
    }

    private interface Shade { float[] at(double x,double y); }

    /** Fills a polygon (lab px) with 2×2 supersampling; {@code shade} gives the colour and alpha at a point. */
    private static void fill(Layer L,double[][] poly,Shade shade) {
        double minX=1e9,minY=1e9,maxX=-1e9,maxY=-1e9;int n=L.n;
        for(double[] p:poly){minX=Math.min(minX,L.tx(p[0]));maxX=Math.max(maxX,L.tx(p[0]));minY=Math.min(minY,L.ty(p[1]));maxY=Math.max(maxY,L.ty(p[1]));}
        int i0=Math.max(0,(int)Math.floor(minX)),i1=Math.min(n-1,(int)Math.ceil(maxX)),j0=Math.max(0,(int)Math.floor(minY)),j1=Math.min(n-1,(int)Math.ceil(maxY));
        double[] xs=new double[poly.length],ys=new double[poly.length];
        for(int k=0;k<poly.length;k++){xs[k]=L.tx(poly[k][0]);ys[k]=L.ty(poly[k][1]);}
        for(int j=j0;j<=j1;j++)for(int i=i0;i<=i1;i++) {
            int in=0;
            for(int sy=0;sy<2;sy++)for(int sx=0;sx<2;sx++)if(inside(xs,ys,i+.25+.5*sx,j+.25+.5*sy))in++;
            if(in==0)continue;
            float[] c=shade.at(L.x(i),L.y(j));
            L.over(j*n+i,c[0],c[1],c[2],c[3]*in/4F);
        }
    }
    private static boolean inside(double[] xs,double[] ys,double x,double y) {
        boolean in=false;
        for(int a=0,b=xs.length-1;a<xs.length;b=a++)if((ys[a]>y)!=(ys[b]>y)&&x<(xs[b]-xs[a])*(y-ys[a])/(ys[b]-ys[a])+xs[a])in=!in;
        return in;
    }
    private static double[][] scaled(double[][] P,double cx,double cy,double k) {
        double[][] q=new double[P.length][];
        for(int i=0;i<P.length;i++)q[i]=new double[]{cx+(P[i][0]-cx)*k,cy+(P[i][1]-cy)*k};
        return q;
    }
    private static double[] ring(double[][] P) {
        double[] ring=new double[(P.length+1)*3];
        for(int k=0;k<=P.length;k++){double[] q=P[k%P.length];ring[k*3]=q[0];ring[k*3+1]=q[1];}
        return ring;
    }

    /**
     * A hole full of light: the dark rim and the dark depth in the ink; in the light, the radial light from white to cold blue, a white
     * core and the dark inner wall (the shell has depth at the edge), as the lab paints them.
     */
    private static void hole(BtFaceFrac.Plate p,double a,boolean s1) {
        double[][] P=p.outline();
        double ink=N/BtFaceFrac.RW;
        fill(INK,P,(x,y)->new float[]{18/255F,21/255F,27/255F,(float)a});
        line(INK,ring(P),0,P.length+1,.9*ink,10/255F,10/255F,14/255F,(float)Math.min(1,a*1.5));
        float[] c0=s1?new float[]{1,1,1}:new float[]{240/255F,253/255F,1},c1=s1?new float[]{215/255F,247/255F,1}:new float[]{86/255F,222/255F,1},
                c2=s1?new float[]{113/255F,156/255F,216/255F}:new float[]{0,143/255F,213/255F};
        fill(LIGHT,scaled(P,p.cx(),p.cy(),1-.55/p.r()),(x,y)->{
            double d=Math.hypot(x-p.cx(),y-p.cy())/(p.r()*1.15);
            float[] c=d<.5?mixC(c0,c1,d/.5):mixC(c1,c2,Math.min(1,(d-.5)/.5));
            return new float[]{c[0],c[1],c[2],(float)a};
        });
        fill(LIGHT,scaled(P,p.cx(),p.cy(),.5),(x,y)->new float[]{1,1,1,(float)(.9*a)});
        // the inner wall: the lab strokes 1.3 px clipped to the hole, so half of it shows inside the rim
        line(LIGHT,ring(scaled(P,p.cx(),p.cy(),1-.33/p.r())),0,P.length+1,.65*ink,12/255F,12/255F,18/255F,(float)(.75*a));
    }
    private static float[] mixC(float[] a,float[] b,double k) { return new float[]{(float)(a[0]+(b[0]-a[0])*k),(float)(a[1]+(b[1]-a[1])*k),(float)(a[2]+(b[2]-a[2])*k)}; }
    private static int abgr(double a,double r,double g,double b) {
        int Aa=(int)Math.round(BtMath.clamp(a)*255),Rr=(int)Math.round(BtMath.clamp(r)*255),Gg=(int)Math.round(BtMath.clamp(g)*255),Bb=(int)Math.round(BtMath.clamp(b)*255);
        return Aa<<24|Bb<<16|Gg<<8|Rr;
    }

    // ---------------------------------------------------------------- the glow (btpShellGlow, btpBlaze)
    /**
     * The lab's glow canvas — the open seams widened (lens 1.5, +.6) and the holes, in cold light, on the skin only — blurred at three
     * radii and screened over the face, stronger and bluer in the second cracking; soft glows over the openings; each jolt flashes the
     * light in the cracks. At the blow his face blazes: the skin floods with light, blurred twice, then fades (btpBlaze).
     * Returns false when there is nothing to draw.
     */
    private static boolean paintGlow(double t) {
        double e=t-BLOW,blaze=e<-.5||e>2.4?0:e<0?.3*BtMath.smooth(-.5,0,e):e<.05?.3+.55*e/.05:.85*Math.exp(-(e-.05)/.45);
        boolean shell=t<BLOW;
        if(!shell&&blaze<=.004)return false;
        SUM.clear();
        double st2=BtMath.smooth(BtFaceFrac.T2,BtFaceFrac.T2+3,t),lpx=GN/(BtFaceFrac.RW*(1+2*GM));
        if(shell) {
            GLOW.clear();
            BtFaceFrac F=frac;
            float gr=(float)(st2>.5?50/255.:145/255.),gg=(float)(st2>.5?206/255.:203/255.),gb=(float)(st2>.5?1:246/255.);
            for(var s:F.segs) {
                double o=BtMath.clamp((t-s.open())/s.od());if(o<=0)continue;
                double[] P=s.pts();
                double[][] poly=lensPoly(s,P,P.length/3,t,o,1.5,.6);
                if(poly!=null)fill(GLOW,poly,(x,y)->new float[]{gr,gg,gb,1});
            }
            for(var p:F.peels) {
                double h=t-p.t();if(h<.12)continue;
                float a=(float)(Math.min(1,(h-.12)/.45)*flick(t,p.t()));if(a<=0)continue;
                boolean s1=p.stage()==1;
                fill(GLOW,p.outline(),(x,y)->new float[]{s1?145/255F:50/255F,s1?203/255F:206/255F,s1?246/255F:1,a});
            }
            skin(GLOW);
            blurInto(GLOW,1.5*lpx,.95);
            blurInto(GLOW,4*lpx,.7+.3*st2);
            if(st2>0)blurInto(GLOW,10*lpx,.6*st2);
            if(st2>0)spot(408,216,28,20,50/255F,202/255F,1,.32*st2);
            double st1=BtMath.smooth(5.8,9.5,t)*(1-st2);
            if(st1>0)spot(404,208,22,18,150/255F,215/255F,1,.26*st1);
            for(int k=0;k<2;k++)for(double[] row:k==0?BtPeakMath.JOLT1:BtPeakMath.JOLT2) {
                double q=t-row[0];if(q<0||q>.6)continue;double a=Math.exp(-q/.12);
                if(k==1)spot(405,210,36,28,64/255F,202/255F,1,.5*a);else spot(405,210,28,22,190/255F,235/255F,1,.45*a);
                spot(405,210,10,9,1,1,1,.5*a);
            }
        }
        if(blaze>.004) {
            GLOW.clear();
            // the lab's mask is the oval of its face; the block face is square, so its corners fall off
            for(int j=0;j<GN;j++)for(int i=0;i<GN;i++) {
                double x=GLOW.x(i),y=GLOW.y(j),s=BtFaceFrac.onSkin(x,y)*(1-BtMath.smooth(.2,.56,Math.hypot(faceU(x)-.5,(faceV(y)-.62)*1.15)));
                if(s>0)GLOW.over(j*GN+i,205/255F,242/255F,1,(float)s);
            }
            // the sharp pass at half: at full it drew the square face as a lamp with straight edges (codex 31.10)
            blurInto(GLOW,2*lpx,.5*blaze);
            blurInto(GLOW,12*lpx,blaze);
            spot(402,212,48,56,140/255F,220/255F,1,.55*blaze);
        }
        return true;
    }

    /** Keeps a layer on the skin of the face square (the lab's destination-in with its face mask). */
    private static void skin(Layer L) {
        for(int j=0;j<L.n;j++)for(int i=0;i<L.n;i++) {
            int k=j*L.n+i;if(L.a[k]<=0)continue;
            float s=(float)BtFaceFrac.onSkin(L.x(i),L.y(j));
            L.r[k]*=s;L.g[k]*=s;L.b[k]*=s;L.a[k]*=s;
        }
    }

    /** Adds the layer blurred by a gaussian of {@code sigma} texels, times {@code alpha}, to the sum (canvas blur + screen). */
    private static void blurInto(Layer src,double sigma,double alpha) {
        if(alpha<=0)return;
        int n=src.n,rad=Math.max(1,(int)Math.ceil(sigma*2.6));
        double[] w=new double[rad*2+1];double ws=0;
        for(int k=-rad;k<=rad;k++){w[k+rad]=Math.exp(-k*k/(2*sigma*sigma));ws+=w[k+rad];}
        for(int k=0;k<w.length;k++)w[k]/=ws;
        float[][] ch={src.r,src.g,src.b},out={SUM.r,SUM.g,SUM.b};
        float[] tmp=BLUR.r;
        for(int c=0;c<3;c++) {
            float[] in=ch[c];
            for(int j=0;j<n;j++)for(int i=0;i<n;i++) {
                double s=0;
                for(int k=-rad;k<=rad;k++){int x=i+k;if(x>=0&&x<n)s+=in[j*n+x]*w[k+rad];}
                tmp[j*n+i]=(float)s;
            }
            for(int j=0;j<n;j++)for(int i=0;i<n;i++) {
                double s=0;
                for(int k=-rad;k<=rad;k++){int y=j+k;if(y>=0&&y<n)s+=tmp[y*n+i]*w[k+rad];}
                out[c][j*n+i]=(float)Math.min(1.5,out[c][j*n+i]+s*alpha);
            }
        }
    }

    /** bloodGlow: an elliptic radial glow (alpha a at the centre, half at .36, none at the edge), added to the sum. */
    private static void spot(double cx,double cy,double rx,double ry,float r,float g,float b,double a) {
        if(a<=0)return;
        Layer L=SUM;int n=L.n;
        double x0=L.tx(cx-rx),x1=L.tx(cx+rx),y0=L.ty(cy-ry),y1=L.ty(cy+ry);
        for(int j=Math.max(0,(int)Math.floor(y0));j<=Math.min(n-1,(int)Math.ceil(y1));j++)for(int i=Math.max(0,(int)Math.floor(x0));i<=Math.min(n-1,(int)Math.ceil(x1));i++) {
            double d=Math.hypot((L.x(i)-cx)/rx,(L.y(j)-cy)/ry);if(d>=1)continue;
            double k=d<.36?1-.5*d/.36:.5*(1-(d-.36)/.64),q=Math.min(1,a)*k;
            int m=j*n+i;
            L.r[m]=(float)Math.min(1.5,L.r[m]+r*q);L.g[m]=(float)Math.min(1.5,L.g[m]+g*q);L.b[m]=(float)Math.min(1.5,L.b[m]+b*q);
        }
    }

    /** A few motes of light seep off the cracked cheek; each plate that comes off leaves a pulse of light in its hole. */
    private static void seep(BtCtx c,double t,Face f) {
        double px=f.px();
        double k=BtMath.smooth(5.5,7.5,t)*(1-BtMath.smooth(12.5,14,t))+BtMath.smooth(20.6,22,t)*(1-BtMath.smooth(25.4,26,t));
        if(k>0)for(int i=0;i<14;i++) {
            int s=i*71+8100;double life=1.6+1.2*BtFaceFrac.random(s+1),ph=((t/life+BtFaceFrac.random(s+2))%1+1)%1;
            double x=BtFaceFrac.OX+4+(BtFaceFrac.random(s+3)-.3)*8+30*ph*(.6+.4*BtFaceFrac.random(s+5)),y=BtFaceFrac.OY+6+(BtFaceFrac.random(s+4)-.5)*8+10*ph*(BtFaceFrac.random(s+7)-.3);
            dot(c,f.at(x,y,.03),(.7+.7*BtFaceFrac.random(s+6))*2.4*px,.75*k*Math.sin(Math.PI*ph),0x96e1ff);
        }
        for(var p:frac.peels) {
            double e=t-p.t();if(e<0||e>.9)continue;
            dot(c,f.at(p.cx(),p.cy(),.02),(8+14*e)*px,.75*(1-e/.9),0x64dfff);
        }
    }

    // ---------------------------------------------------------------- the pieces (btpPeels, btpBurst, btpBurstDust)
    /**
     * The plates that come off: the skin itself lifts off the face, turns over and falls, its back dark; it catches the light. At the
     * blow every plate left comes off the same way in a cascade outwards from the first crack, and turns into sparks as it fades.
     * The lab's motion is in the picture plane; here the piece also leaves the face along its normal, only as far as it must not to cut
     * into the head (round 1 sent the burst a quarter block towards the camera and it read as a mosaic flying apart).
     */
    private static void pieces(BtCtx c,double t,Face f) {
        ResourceLocation skin=c.mc().player instanceof AbstractClientPlayer p?p.getSkin().texture():null;
        if(skin==null)return;
        // The skin pieces share the buffer source's one dynamic buffer: asking for another render type in between (a glow dot) ends
        // that batch and the next vertex throws «Not building!». Pieces first, their light after.
        VertexConsumer vc=c.buffers().getBuffer(RenderType.entityTranslucent(skin));
        java.util.List<Runnable> light=new java.util.ArrayList<>();
        double px=f.px();
        for(var p:frac.peels) {
            double e=t-p.t();if(e<0||e>2.6)continue;
            double lift=Math.min(1,e/.5),q=Math.max(0,e-.5);
            double x=p.cx()+p.outX()*(.7*lift+6*q)+p.vx()*q,y=p.cy()+p.outY()*(.7*lift+6*q)+2*q+48*q*q;
            double spin=p.spin()*q+.06*lift*Math.signum(p.spin()),flip=p.flip()*q,a=1-BtMath.smooth(1.8,2.6,e);
            double k=BtMath.smooth(8,13.6,p.t());
            float[] tint={(float)((150+40*k)/255),(float)((160+40*k)/255),(float)((205+20*k)/255)};
            piece(c,vc,f,p,x,y,.012+.02*lift+.05*q,spin,flip,a,tint,q,BtMath.smooth(1.2,2.2,e),light);
            // the falling plate catches the light
            if(q>0){Vec3 at=f.at(x,y,.03+.05*q);double la=.8*(1-BtMath.smooth(1.4,2.2,e))*(.6+.4*Math.sin(e*14));light.add(()->dot(c,at,5*px*2.2,la,0xe6faff));}
        }
        double e=t-BLOW;
        if(e<0||e>2.8){light.forEach(Runnable::run);return;}
        float[] tint={190/255F,200/255F,225/255F};
        for(var p:frac.burst) {
            double q=e-p.t();if(q<0)continue;
            double lift=Math.min(1,q/.2),fq=Math.max(0,q-.2);
            double x=p.cx()+p.outX()*(1.2*lift+16*fq)+p.sw()*.12*fq,y=p.cy()+p.outY()*(1.2*lift+16*fq)+10*fq+230*fq*fq;
            double a=1-BtMath.smooth(.55,1.05,q),out=.012+.03*lift+.06*fq;
            if(a>0)piece(c,vc,f,p,x,y,out,p.spin()*.3*fq+.05*lift*Math.signum(p.spin()),p.flip()*.45*fq,a,tint,fq,BtMath.smooth(.25,.75,q),light);
            // as each piece fades it turns into a few sparks of cold light that rise a little and go out
            if(q>=.35&&q<=1.6) {
                double sa=BtMath.smooth(.4,.8,q)*(1-BtMath.smooth(.95,1.6,q)),s=q-.4;
                for(int k=0;k<4;k++) {
                    double seed=k*13+p.cx()*7.1+p.cy()*3.3;
                    double sx=x+(BtFaceFrac.random(seed+3)-.5)*8+(BtFaceFrac.random(seed+4)-.5)*20*s,sy=y+(BtFaceFrac.random(seed+5)-.5)*6-40*s;
                    Vec3 at=f.at(sx,sy,out+.01);double size=(.7+1.2*BtFaceFrac.random(seed+6))*2.2*px,la=sa*(.5+.5*BtFaceFrac.random(seed+2));
                    int rgb=BtFaceFrac.random(seed+1)<.5?0xffffff:0xbeecff;
                    light.add(()->dot(c,at,size,la,rgb));
                }
            }
        }
        light.forEach(Runnable::run);
    }

    /**
     * One piece at (x, y) lab px, {@code out} blocks off the face, turned by {@code spin} in the face plane and over by {@code flip}
     * about its horizontal axis. Front: the player's own skin there, tinted; back: the dark broken side, a hair behind.
     * As it falls it crumbles ({@code crumble} 0..1): three to five chunks of it drift apart and shrink, their broken edges burning
     * with cold light (codex 31.10: whole dark plates hanging in front of the chest read as solid debris, not a shell burning away).
     */
    private static void piece(BtCtx c,VertexConsumer vc,Face f,BtFaceFrac.Plate p,double x,double y,double out,double spin,double flip,
                              double a,float[] tint,double fall,double crumble,java.util.List<Runnable> light) {
        double px=f.px(),cs=Math.cos(spin),sn=Math.sin(spin),cf=Math.cos(flip),sf=Math.sin(flip);
        Vec3 un=f.u().normalize(),vn=f.v().normalize(),centre=f.at(x,y,out);
        double dk=cf<0?.72:(1-cf)*.5+.18*Math.min(1,fall);
        double[][] P=p.outline();int n=P.length;
        Vec3[] w=new Vec3[n];float[] su=new float[n],sv=new float[n];
        for(int k=0;k<n;k++) {
            double dx=(P[k][0]-p.cx())*px,dy=(P[k][1]-p.cy())*px;
            double rx=dx*cs-dy*sn,ry=dx*sn+dy*cs;
            // turn over about the piece's horizontal axis: the v offset tips out of the face plane
            w[k]=centre.add(un.scale(rx)).add(vn.scale(ry*cf)).add(f.normal().scale(-ry*sf));
            su[k]=(float)((8+8*BtMath.clamp(faceU(P[k][0])))/64);sv[k]=(float)((8+8*BtMath.clamp(faceV(P[k][1])))/64);
        }
        Vec3 nrm=f.normal().scale(cf).add(vn.scale(sf));
        float sr=(float)(tint[0]*(1-dk*.85)),sg=(float)(tint[1]*(1-dk*.85)),sb=(float)(tint[2]*(1-dk*.85));
        float cu=(float)((8+8*BtMath.clamp(faceU(p.cx())))/64),cv=(float)((8+8*BtMath.clamp(faceV(p.cy())))/64);
        Vec3 back=nrm.scale(-.0035);
        float dr=33/255F,dg=30/255F,db=37/255F;
        double seed=p.cx()*5.3+p.cy()*2.9;
        int chunks=crumble<=0?1:3+(int)(BtFaceFrac.random(seed)*2.99);
        for(int ch=0;ch<chunks;ch++) {
            int from=ch*n/chunks,to=(ch+1)*n/chunks;
            // the chunk: the centre and its arc of the outline; it drifts out from the centre, sinks a little faster and shrinks
            Vec3 arc=Vec3.ZERO;for(int k=from;k<=to;k++)arc=arc.add(w[k%n]);
            arc=arc.scale(1.0/(to-from+1));
            Vec3 mid=centre.add(arc).add(arc).scale(1/3.0),dir=arc.subtract(centre);
            double r1=BtFaceFrac.random(seed+ch*3+1),r2=BtFaceFrac.random(seed+ch*3+2),k2=crumble*crumble;
            Vec3 shift=dir.scale(crumble*(.8+1.2*r1)).add(vn.scale(k2*px*(4+8*r2))).add(f.normal().scale(.004*crumble));
            double shrink=1-.45*crumble;
            java.util.function.UnaryOperator<Vec3> at=q->mid.add(q.subtract(mid).scale(shrink)).add(shift);
            Vec3 cc=at.apply(centre);
            // a fan from the centre; entityTranslucent draws both sides
            for(int k=from;k<to;k++) {
                int m=(k+1)%n;Vec3 wk=at.apply(w[k%n]),wm=at.apply(w[m]);
                VfxDraw.vertex(vc,c.pose(),cc.add(back),nrm,cu,cv,(float)a,dr,dg,db);
                VfxDraw.vertex(vc,c.pose(),wk.add(back),nrm,su[k%n],sv[k%n],(float)a,dr,dg,db);
                VfxDraw.vertex(vc,c.pose(),wm.add(back),nrm,su[m],sv[m],(float)a,dr,dg,db);
                VfxDraw.vertex(vc,c.pose(),wm.add(back),nrm,su[m],sv[m],(float)a,dr,dg,db);
                VfxDraw.vertex(vc,c.pose(),cc,nrm,cu,cv,(float)a,sr,sg,sb);
                VfxDraw.vertex(vc,c.pose(),wk,nrm,su[k%n],sv[k%n],(float)a,sr,sg,sb);
                VfxDraw.vertex(vc,c.pose(),wm,nrm,su[m],sv[m],(float)a,sr,sg,sb);
                VfxDraw.vertex(vc,c.pose(),wm,nrm,su[m],sv[m],(float)a,sr,sg,sb);
            }
            // its broken edge burns: a few embers of cold light along the arc — a flash as it tears off, then brighter as it crumbles
            double burn=Math.max(crumble,.7*(1-BtMath.smooth(0,.35,fall)));
            if(burn>.05)for(int e=0;e<2;e++) {
                int k=from+(to-from)*(e+1)/3;
                Vec3 ember=at.apply(w[k%n]).add(nrm.scale(.006));
                double ea=.8*a*burn*(.55+.45*Math.sin(fall*17+e*2.1+ch)),size=(1.1+.9*BtFaceFrac.random(seed+ch*7+e))*2.2*px;
                int rgb=(e+ch)%2==0?0xffffff:0xbeecff;
                light.add(()->dot(c,ember,size,ea,rgb));
            }
        }
    }

    /** A few small dark angular flakes hang off the face and sink (btpCrumbs). */
    private static void crumbs(BtCtx c,double t,Face f) {
        double k=BtMath.smooth(6,7,t)*(1-BtMath.smooth(12,13,t))+BtMath.smooth(21,22,t)*(1-BtMath.smooth(26,27,t));
        if(k<=0)return;
        double px=f.px();
        var vc=c.buffers().getBuffer(MurimRenderTypes.impurity());
        Vec3 un=f.u().normalize(),vn=f.v().normalize();
        double[][] q={{-1,-.35},{.85,-.7},{.5,.65},{-.55,.45}};
        for(int i=0;i<34;i++) {
            int s=i*17+8800;int st=i<12?1:2;double t0=st==1?6.4+BtFaceFrac.random(s+1)*5:21.2+BtFaceFrac.random(s+1)*4.4,e=t-t0;
            if(e<0||e>2)continue;
            double an=Math.PI*(-.25+1.2*BtFaceFrac.random(s+2)),rr=st==1?2+7*BtFaceFrac.random(s+3):3+15*BtFaceFrac.random(s+3);
            double x0=BtFaceFrac.OX+Math.cos(an)*rr,y0=BtFaceFrac.OY+Math.sin(an)*rr*.9;
            double x=x0+(BtFaceFrac.random(s+4)-.3)*8*e+3*e,y=y0-1.5*e+12*e*e,size=(.9+1.5*BtFaceFrac.random(s+5))*px,a=Math.min(1,e*5)*(1-e/2)*k;
            double rot=BtFaceFrac.random(s+6)*6+e*(BtFaceFrac.random(s+7)-.5)*7,cr=Math.cos(rot),sr=Math.sin(rot);
            int rgb=BtFaceFrac.random(s+8)<.5?0x4e4241:0x171820;
            Vec3 at=f.at(x,y,.02+.04*e);
            for(double[] d:q) {
                double dx=(d[0]*cr-d[1]*sr)*size,dy=(d[0]*sr+d[1]*cr)*size;
                VfxDraw.vertex(vc,c.pose(),at.add(un.scale(dx)).add(vn.scale(dy)),f.normal(),.5F,.5F,(float)a,(rgb>>16&255)/255F,(rgb>>8&255)/255F,(rgb&255)/255F);
            }
        }
    }

    /** The face's own light: not subject to the guard that keeps the essences' light off his face (BtDraw.dot). */
    private static void dot(BtCtx c,Vec3 at,double size,double alpha,int rgb) {
        if(alpha<=.001||size<=0)return;
        VfxDraw.billboard(c.buffers().getBuffer(MurimRenderTypes.mote()),c.pose(),at,c.camera(),size,(float)alpha,((rgb>>16)&255)/255f,((rgb>>8)&255)/255f,(rgb&255)/255f);
    }
}
