package io.github.verycooltimo.murim.client.vfx.preset;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.client.vfx.VfxDraw;
import net.minecraft.world.phys.Vec3;
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
import static io.github.verycooltimo.murim.client.vfx.preset.EssencePreset.*;
import static io.github.verycooltimo.murim.client.vfx.preset.EssenceMaterial.*;

/** Camera-facing cross sections on a player-oriented 3D plane.
 * API: VfxDraw.vertex, CoreGlow, WhirlVfx.onRender; all buffers are owned by PresetRuntime.
 */
final class PresetMesh {
    private final int ROWS, COLS;
    private final java.util.function.DoubleFunction<Vec3> centreline;
    private final Layer l;
    private final EssenceMaterial material;
    private final Vec3 origin,right,forward,camera;
    private final double height,time,exposure;
    private final VertexConsumer buffer;
    private final PoseStack.Pose pose;
    private final boolean dark;
    private final Sample[] samples;
    private final Vec3[] centers,normals,tangents;
    private final double[] bendLimit;
    private record Vertex(Vec3 pos, RGB rgb, double alpha) { }
    @FunctionalInterface private interface Surface { Vertex at(int row,int col); }
    PresetMesh(Layer layer,Vec3 origin,Vec3 right,Vec3 forward,Vec3 camera,double height,double time,
               double exposure,VertexConsumer buffer,PoseStack.Pose pose,boolean dark) {
        this(layer,origin,right,forward,camera,height,time,exposure,buffer,pose,dark,null);
    }
    PresetMesh(Layer layer,Vec3 origin,Vec3 right,Vec3 forward,Vec3 camera,double height,double time,
               double exposure,VertexConsumer buffer,PoseStack.Pose pose,boolean dark,
               java.util.function.DoubleFunction<Vec3> centreline) {
        this.centreline=centreline;ROWS=centreline==null?280:64;COLS=centreline==null?64:16;
        samples=new Sample[ROWS+1];centers=new Vec3[ROWS+1];normals=new Vec3[ROWS+1];tangents=new Vec3[ROWS+1];bendLimit=new double[ROWS+1];
        this.l=layer;this.material=new EssenceMaterial(layer);this.origin=origin;this.right=right;
        this.forward=forward;this.camera=camera;this.height=height;this.time=time;this.exposure=exposure;
        this.buffer=buffer;this.pose=pose;this.dark=dark;
        for(int i=0;i<=ROWS;i++) { samples[i]=path(l,i/(double)ROWS,time);centers[i]=center(i/(double)ROWS); }
        for(int i=0;i<=ROWS;i++) {
            Vec3 d=centers[min(ROWS,i+1)].subtract(centers[max(0,i-1)]).normalize();tangents[i]=d;
            normals[i]=cameraNormal(d,centers[i],plane(samples[i].normal()));
        }
        double[] raw=new double[ROWS+1];
        for(int i=0;i<=ROWS;i++) {
            int a=max(0,i-2),b=min(ROWS,i+2);
            double angle=acos(clamp(normals[a].dot(normals[b]),-1,1));
            raw[i]=angle>1e-4?.9*centers[a].distanceTo(centers[b])/height/angle:Double.POSITIVE_INFINITY;
        }
        for(int i=0;i<=ROWS;i++) { double m=Double.POSITIVE_INFINITY;
            for(int j=max(0,i-8);j<=min(ROWS,i+8);j++)m=min(m,raw[j]);bendLimit[i]=m;
        }
    }
    private Vec3 center(double u) {
        if(centreline==null)return world(path(l,u,time).position());
        Point raw=curve(l.path(),u), displaced=path(l,u,time).position();
        return centreline.apply(u).add(plane(new Point(displaced.x()-raw.x(),displaced.y()-raw.y())).scale(height));
    }
    private double p(String k) { return l.p(k); }
    private Vec3 plane(Point p) { return right.scale(p.x()).add(0,p.y(),0); }
    private Vec3 world(Point p) {
        // behind is real geometry, not the laboratory's reference-image mask.
        return origin.add(plane(p).scale(height)).add(forward.scale((p("behind")>.5?-.22:.22)+p("depth")*.003));
    }
    private Vec3 cameraNormal(Vec3 tangent,Vec3 center,Vec3 fallback) {
        Vec3 n=tangent.cross(camera.subtract(center));
        if(n.lengthSqr()<1e-10)n=fallback;
        n=n.normalize();
        // Keep material v oriented consistently when viewing the back of the plane.
        return n.dot(fallback)<0?n.scale(-1):n;
    }
    private Vec3 at(double u,double offset) {
        Sample s=path(l,u,time);Vec3 c=center(u);
        Vec3 tangent=centreline==null?plane(s.tangent()):center(min(1,u+.0001)).subtract(center(max(0,u-.0001))).normalize();
        return c.add(cameraNormal(tangent,c,plane(s.normal())).scale(offset*height));
    }
    private Vertex vertex(Vec3 pos,double u,double v,double facing,double side,Trim tr,int mode,double gain,double seed,double tint,int cols) {
        if(dark) {
            double ut=p("dir")>.5?1-u:u;
            if(ut<tr.a()||ut>tr.b()||tr.b()<=tr.a())return new Vertex(pos,BLACK,0);
            double tv=(ut-tr.a())/max(tr.b()-tr.a(),1e-4),prof=pow(smooth(0,p("tail"),tv),.8)*pow(smooth(0,p("head"),1-tv),.6);
            double av=abs(v)/max(prof,.001),n=fbm(u*p("nU")-time*p("flow")+seed,v*p("nV")+seed,p("oct"));
            double m=smooth(tr.luma()>0?-.15+tr.luma()*1.2:-1,(tr.luma()>0?-.15+tr.luma()*1.2:-1)+p("lumaSoft"),n);
            double density=(p("darkOnly")>0?pow(max(0,1-av*av),.8)*(.45+.55*n):exp(-av*av*3))*p("shadow")*m*(1-smooth(.7,1,av));
            return new Vertex(pos,new RGB(.08,.04,.07),1-exp(-density));
        }
        RGB rgb=material.shade(u,v,facing,side,time,tr,mode==0&&p("arcLine")>0?11:mode,gain,seed+l.seed()*7.31,tint,cols);
        return new Vertex(pos,rgb,1);
    }
    void draw() {
        Trim tr=trim(l,time,0);
        if(tr.b()<=tr.a())return;
        if(!dark && p("darkOnly")>0)return;
        if(dark && p("shadow")<=0)return;
        if(!dark) strip(tr,1,0,p("halo"),1,3.7,0);
        for(int sh=(int)round(p("sheets"))-1;sh>=0;sh--) {
            double ws=sh==0?1:.5+.4*hash(sh,31,l.seed());
            double gain=sh==0?1:p("sheetAlpha")*(.6+.4*hash(sh,32,l.seed()));
            strip(tr,0,sh,ws,gain,3.7+sh*5.1,(sh==0?.15:hash(sh,33,l.seed()))*p("sheetTint"));
        }
        for(int i=0;i<l.veils().size();i++) veil(l.veils().get(i),i,tr);
        if(dark)return;
        for(int i=0;i<l.folds().size();i++) fold(l.folds().get(i),i,tr);
        threads();spine();wisps(tr);
        if(p("smokeAmt")>0)strip(tr,4,0,p("smokeSpread"),p("smokeAmt"),6.1,0);
    }
    private void strip(Trim tr,int mode,int sh,double ws,double gain,double seed,double tint) {
        // Scene ribbons (centreline): the soft halo/smoke sheets need few columns, the body keeps its core (perf 31.10).
        int cols=centreline!=null?(mode==1||mode==4?8:12):mode==0?COLS:24;
        double base=p("width")*.5,curl=p("curl"),sheetSpread=p("sheetSpread"),sheetDiverge=p("sheetDiverge");
        double pleat=p("pleat"),fibers=p("fibers"),ragged=p("ragged"),hot=p("hot"),fan=p("fan");
        // Everything that depends on the row only is computed once per row, not once per column (perf 31.10).
        Twist[] rowTwist=new Twist[ROWS+1];double[] rowHalf=new double[ROWS+1],rowOff=new double[ROWS+1],rowSpeed=new double[ROWS+1];
        Vec3[] rowCenter=new Vec3[ROWS+1],rowNormal=new Vec3[ROWS+1];
        for(int i=0;i<=ROWS;i++) {
            double u=i/(double)ROWS;Twist tw=twist(l,u,time,sh);double hw,off=0;
            Vec3 center=centers[i],normal=normals[i];
            if(mode==1) {
                hw=min(base*ws*(.25+.75*profile(l,u)),bendLimit[i]);
                Point xy=curve(l.path(),u),d=normalized(derivative(l.path(),u));center=centreline==null?world(xy):centreline.apply(u);
                normal=cameraNormal(plane(d),center,plane(new Point(d.y(),-d.x())));
            } else if(mode==4)hw=min(base*ws,bendLimit[i]);
            else {
                hw=halfWidth(l,u,time,sh)*ws;
                off=curl*base*ws*tw.sine();
                if(sh>0)off+=(noise(u*2.5+sh*9.1+time*.4,l.seed())-.5)*2*sheetSpread*base
                        +(hash(sh,34,l.seed())-.5)*2*sheetDiverge*base*3*pow(abs(u-.45)*1.8,2);
            }
            rowTwist[i]=tw;rowHalf[i]=hw;rowOff[i]=off;rowCenter[i]=center;rowNormal[i]=normal;
            rowSpeed[i]=centers[min(ROWS,i+1)].distanceTo(centers[max(0,i-1)])/height*ROWS*.5;
        }
        final int fcols=cols;
        grid(ROWS,cols,(i,j)->{
            double u=i/(double)ROWS,v=j/(double)fcols*2-1;
            Twist tw=rowTwist[i];double hw=rowHalf[i],off=rowOff[i],along=0,lift=0,facing=tw.facing();
            Vec3 center=rowCenter[i],normal=rowNormal[i];
            double across=hw*v+off;
            if(mode==0 && pleat>.1) {
                double frequency=7+fibers*.38;
                double phase=frequency*(v+.24*sin(u*17+v*2.5+sh*1.3)+.15*sin(u*37+v*5.4+sh*3.1)
                        +.065*sin(u*83+v*9.7)+.10*sin(v*9+sh))+time*.15;
                double amp=pleat*(.35+.65*pow(sin(u*11+1.5+sh*.7),2)),env=1-v*v;
                double warp=(.7*sin(phase)+.26*sin(phase*2.37+u*11))/frequency;
                double phaseD=frequency*(1+.60*cos(u*17+v*2.5+sh*1.3)+.81*cos(u*37+v*5.4+sh*3.1)
                        +.6305*cos(u*83+v*9.7)+.90*cos(v*9+sh));
                double derivative=phaseD*(.7*cos(phase)+.6162*cos(phase*2.37+u*11))/frequency;
                double dx=1+amp*(env*derivative-2*v*warp),dz=amp*(.8*sin(phase)+.4*sin(phase*2.37+u*11));
                double speed=rowSpeed[i];
                double ap=u*43+v*7+sh*1.7,compression=abs(1+hw*amp*.25*43*cos(ap)*env/max(speed,1.0/350));
                facing=min(1,min(abs(dx)/max(.1,hypot(dx,dz)),compression));
                across=hw*(v+amp*env*warp+ragged*.3*sin(u*83+v*7)*pow(abs(v),3))+off;
                along=hw*amp*.25*sin(ap)*env;
                lift=hw*hot*fan*2.1*exp(-pow((u-.34)/.14,2))*pow(max(0,v),1.3);
            }
            Vec3 pos=center.add(normal.scale(across*height)).add(tangents[i].scale(along*height)).add(right.scale(lift*.12*height)).add(0,lift*height,0);
            return vertex(pos,u,v,facing,tw.side(),tr,mode,gain,seed,tint,fcols);
        });
    }
    private void veil(Veil veil,int index,Trim tr) {
        grid(180,28,(i,j)->{
            double z=i/180.0,f=j/28.0,v=2*f-1;Point a=curve(veil.a(),z),b=curve(veil.b(),z);
            Vec3 pa=at(clamp(a.x(),0,1),a.y()*p("width")*.5),pb=at(clamp(b.x(),0,1),b.y()*p("width")*.5);
            double phase=f*15+sin(z*17)*2+sin(z*39+f*3)*.7,ripple=sin(phase)*sin(PI*f)*1.2/350;
            Vec3 pos=pa.lerp(pb,f).add(plane(path(l,clamp(a.x(),0,1),time).tangent()).scale(ripple*height));
            return vertex(pos,mix(a.x(),b.x(),f),v,.45+.55*abs(cos(phase)),1,tr,10,veil.gain(),3.7+index,0,28);
        });
    }
    private void fold(Fold fold,int index,Trim tr) {
        if(p("foldDetail")<=0)return;
        for(int filament=0;filament<4;filament++) {
            final int f=filament;
            Vec3[] points=new Vec3[161];double[] widths=new double[161],us=new double[161];
            for(int j=0;j<=160;j++) {
                double z=j/160.0;Point uv=curve(fold.path(),z);double u=clamp(uv.x(),0,1);
                double spread=f==0?0:(f-2)*(.7+.7*sin(z*19+index))/350;
                points[j]=at(u,p("width")*.5*uv.y()+spread);us[j]=u;
                widths[j]=(f==0?fold.width():.18+.12*hash(index,f,l.seed()))/350*pow(max(0,sin(PI*z)),.55)*(.5+.5*pow(sin(z*8+index),2));
            }
            poly(points,widths,us,tr,6,fold.gain()*p("foldDetail")*(f==0?1:.38),3.7+index*.1);
        }
    }
    private void threads() {
        for(int k=0;k<(int)round(p("threads"));k++) {
            double h1=hash(k,1,l.seed()),h2=hash(k,2,l.seed()),h3=hash(k,3,l.seed()),h4=hash(k,4,l.seed());
            Trim tr=trim(l,time,h4*p("delay"));
            boolean toHead=h3>.45;double u0=toHead?.5+.35*h2:.15+.3*h2,sign=h1>.5?1:-1;
            Vec3[] points=new Vec3[ROWS+1];double[] widths=new double[ROWS+1],us=new double[ROWS+1];
            for(int i=0;i<=ROWS;i++) {
                double u=i/(double)ROWS,e=toHead?max(0,(u-u0)/(1-u0)):max(0,(u0-u)/u0);
                double off=(h1*2-1)*.55*halfWidth(l,u,time,0)+sign*p("threadSpread")*.45*e*e
                        +(noise(u*9+time*1.3+k*5,l.seed())-.5)*.03*e;
                points[i]=at(u,off);widths[i]=p("threadW")/350;us[i]=u;
            }
            poly(points,widths,us,tr,2,1,3.7);
        }
    }
    private void spine() {
        if(p("spine")<=0)return;
        Trim tr=trim(l,time,-.04);Vec3[] points=new Vec3[ROWS+1];double[] widths=new double[ROWS+1],us=new double[ROWS+1];
        for(int i=0;i<=ROWS;i++) {
            double u=i/(double)ROWS,pu=mix(-p("spineExt"),1+p("spineExt"),u);
            points[i]=at(pu,p("spineOff")*p("width")*.5+(noise(u*5+time*.6+40,l.seed())-.5)*.02);
            widths[i]=p("spineW")/350;us[i]=u;
        }
        // Same material, but the spine uses its own authored colours.
        var params=new java.util.HashMap<>(l.params());params.put("sideFade",0.0);
        var colors=new java.util.HashMap<>(l.colors());colors.put("body",colors.get("spineCol"));colors.put("core",colors.get("spineCol"));
        EssenceMaterial spineMaterial=new EssenceMaterial(new Layer(params,colors,l.path(),l.widthProfile(),l.folds(),l.veils(),l.seed()));
        polyMaterial=spineMaterial;
        if(p("spineGlow")>0) {
            double[] wide=widths.clone();for(int i=0;i<wide.length;i++)wide[i]*=7;
            poly(points,wide,us,tr,2,p("spineGlow")*.35,3.7);
        }
        poly(points,widths,us,tr,2,1.25,3.7);polyMaterial=null;
    }
    private EssenceMaterial polyMaterial;
    private void wisps(Trim parent) {
        for(int j=0;j<(int)round(p("wisps"));j++) {
            double r=hash(j,91,l.seed()),r2=hash(j,92,l.seed()),center=.12+.76*hash(j,93,l.seed());
            double ut=p("dir")>0?1-center:center;if(ut<parent.a()||ut>parent.b())continue;
            double span=.10+.15*r,sign=r2>.5?1:-1,hw=p("width")*.5;
            Vec3[] points=new Vec3[73];double[] widths=new double[73],us=new double[73];
            for(int i=0;i<=72;i++) {
                double z=i/72.0,u=clamp(center+(z-.5)*span,0,1),pulse=sin(PI*z);
                points[i]=at(u,hw*sign*(.15+.75*r2)*pulse*pulse).add(plane(path(l,u,time).tangent()).scale(hw*(.15+.4*r)*sin(2*PI*z)*pulse*height));
                widths[i]=(.12+.6*r*r)*pulse*(.65+.35*sin(z*12+r*6))/350;us[i]=z;
            }
            poly(points,widths,us,new Trim(0,1,parent.luma(),parent.out()),2,.3+.4*r,3.7);
        }
    }
    private void poly(Vec3[] points,double[] widths,double[] us,Trim tr,int mode,double gain,double seed) {
        grid(points.length-1,12,(i,j)->{
            Vec3 tangent=points[min(points.length-1,i+1)].subtract(points[max(0,i-1)]).normalize();
            Vec3 n=cameraNormal(tangent,points[i],right);double v=j/12.0*2-1;
            Vec3 pos=points[i].add(n.scale(widths[i]*height*v));
            if(polyMaterial!=null)return new Vertex(pos,polyMaterial.shade(us[i],v,1,1,time,tr,mode,gain,seed+l.seed()*7.31,0,12),1);
            return vertex(pos,us[i],v,1,1,tr,mode,gain,seed,0,12);
        });
    }
    private void grid(int rows,int cols,Surface surface) {
        if(centreline!=null) {
            final Surface original=surface;final int oldRows=rows,oldCols=cols;
            rows=min(rows,oldRows==64?44:16);cols=min(cols,oldRows==64?16:4);final int nr=rows,nc=cols;
            surface=(i,j)->original.at((int)round(i*(double)oldRows/nr),(int)round(j*(double)oldCols/nc));
        }
        // Two reusable rows: evaluate each material sample once, not once per adjacent quad.
        Vertex[] previous=new Vertex[cols+1],current=new Vertex[cols+1];
        for(int j=0;j<=cols;j++)previous[j]=surface.at(0,j);
        for(int i=1;i<=rows;i++) {
            for(int j=0;j<=cols;j++)current[j]=surface.at(i,j);
            for(int j=0;j<cols;j++) { emit(previous[j]);emit(current[j]);emit(current[j+1]);emit(previous[j+1]); }
            Vertex[] swap=previous;previous=current;current=swap;
        }
    }
    private void emit(Vertex v) {
        RGB c=v.rgb();double alpha=v.alpha();
        if(!dark) {
            double mx=max(c.r(),max(c.g(),c.b()));
            double scale=mx<=0?0:(1-exp(-mx*exposure))/mx;
            c=c.mul(scale);
            // EssenceMaterial returns radiance with coverage already multiplied into RGB.
            // Alpha-over needs straight colour; retain that coverage instead of painting opaque black edges.
            alpha=max(c.r(),max(c.g(),c.b()));
            if(alpha>0)c=c.mul(1/alpha);
        }
        // Procedural body shader consumes vertex colour/coverage only; UV retained for NEW_ENTITY layout.
        VfxDraw.vertex(buffer,pose,v.pos(),camera.subtract(v.pos()).normalize(),.5F,.5F,(float)alpha,(float)c.r(),(float)c.g(),(float)c.b());
    }
    int particles(VertexConsumer mote,int stride,int budget) {
        if(p("darkOnly")>0 || budget<=0)return 0;
        int drawn=0;
        for(int pass=0;pass<2;pass++) {
            boolean burst=pass==1;if(burst&&p("outMode")==1)continue;
            int n=(int)round(p(burst?"pOut":"pCount"));
            for(int i=0;i<n;i+=stride) {
                int salt=burst?20:10;
                double[] h=new double[5];for(int j=0;j<5;j++)h[j]=hash(i,salt+j+1,l.seed());
                double b=burst?p("dur")-p("outDur")+hash(i,20,l.seed())*p("outDur")*.9:(i+hash(i,10,l.seed()))/max(n,1)*p("dur")*.95;
                double life=burst?.35+.5*hash(i,26,l.seed()):p("pLife")*(.5+hash(i,16,l.seed())),age=time-b;
                Trim tr=trim(l,b,0);if(age<0||age>life||tr.b()<=tr.a())continue;
                double ut=mix(tr.a(),tr.b(),hash(i,salt+7,l.seed())),u=p("dir")>0?1-ut:ut;
                if(p("pCluster")>0&&noise(u*7+13+h[4]*.3,l.seed())<p("pCluster")*.85)continue;
                Sample pt=path(l,u,b);Twist tw=twist(l,u,b,0);
                double hw=p("width")*.5*(p("edgeMin")+(1-p("edgeMin"))*tw.facing());
                if(!l.widthProfile().isEmpty()) {
                    double z=u*(l.widthProfile().size()-1);int index=min(l.widthProfile().size()-2,(int)floor(z));
                    hw*=mix(l.widthProfile().get(index),l.widthProfile().get(index+1),z-index);
                }
                double v=h[0]*2-1;v=signum(v)*pow(abs(v),1-p("pEdge")*.85)*(1+p("pSpread")*h[3]*h[3]);
                double sp=p("pSpeed"),along=(p("dir")>0?-1:1)*signum(p("flow")==0?1:p("flow"));
                double vx=pt.tangent().x()*sp*(.15+.6*h[1])*along+pt.normal().x()*(h[2]-.5)*sp*(burst?1.4:.5);
                double vy=pt.tangent().y()*sp*(.15+.6*h[1])*along+pt.normal().y()*(h[2]-.5)*sp*(burst?1.4:.5)+sp*.12;
                double drag=age*(1-.4*age/life);
                Point point=new Point(pt.position().x()+pt.normal().x()*hw*v+vx*drag,pt.position().y()+pt.normal().y()*hw*v+vy*drag);
                double a=min(1,age*12)*(1-age/life)*clamp((p("dur")-time)/.18,0,1);
                double size=.5*p("pSize")*(.6+.8*h[3])*(hash(h[1]*997,5,l.seed())<p("pBig")*.2?3:1)/350*height;
                RGB c=RGB.hex(l.colors().get("body")).blend(RGB.hex(l.colors().get("core")),pow(h[4],3))
                        .blend(RGB.hex(l.colors().get("col2")),smooth(.82,.98,h[4]));
                VfxDraw.billboard(mote,pose,world(point),camera,size,(float)a,(float)c.r(),(float)c.g(),(float)c.b());
                if(++drawn>=budget)return drawn;
            }
        }
        return drawn;
    }
}
