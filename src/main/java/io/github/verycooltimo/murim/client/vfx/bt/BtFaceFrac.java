package io.github.verycooltimo.murim.client.vfx.bt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The lab's fracture of the face, ported 1:1 (lab-bt_peak.js btpFrac): the face is cut into irregular convex plates by straight cuts,
 * the largest plate for its place first, smallest by the left cheekbone where the cracking starts; every cut is a crack, bent by one
 * piecewise-linear field so lines kink like broken porcelain and crack lines and plate edges always meet. Two fronts run out in jolts;
 * some seams open as lenses of light; a few plates are picked to peel off one at a time; the rest come off at the blow.
 * Author 31.10: «в лабе трещины рандомные, аккуратные кусочки падают красиво и горят, а тут мозаика просто накладывается».
 * Coordinates are the lab's plate pixels (the face rect 362..422 x 180..242, the first crack at 404,205), so every constant is the lab's.
 */
final class BtFaceFrac {
    static final double RX=362,RY=180,RW=60,RH=62,OX=404,OY=205;
    static final double[] PEEL1={8.4,9.7,11.0},PEEL2={21.4,22.3,23.2,24.1},PEEL3={22.8,24.6};
    static final double R1=20,R2=48,T2=20.4;

    /** A crack: points (x, y, the moment the front reaches it) in threes; its normal; ink weight; when, how long and how wide it opens. */
    record Seg(double[] pts,double nx,double ny,double w,double open,double od,double ow,double oh,double oc,int stage) { }
    /** A plate that comes off: its bent outline, centre, size, when (a peel time, or the delay after the blow), and how it falls. */
    record Plate(double[][] outline,double cx,double cy,double r,double t,int stage,double outX,double outY,double vx,double spin,
                 double flip,double v,double up,double sw,double g) { }

    final List<Seg> segs=new ArrayList<>();
    final List<Plate> peels=new ArrayList<>();
    final List<Plate> burst=new ArrayList<>();
    private int sd;

    private static final class Cell { double[][] p;int depth;boolean keep,peel; Cell(double[][] p,int depth){this.p=p;this.depth=depth;} }
    private record Cut(double[] a,double[] b,int stage,int depth) { }

    /** lab-common.js bloodRandom. */
    static double random(double n) { double x=Math.sin(n*127.13+19.71)*43758.5453;return x-Math.floor(x); }
    private double rnd() { return random(++sd*7.31+.37); }
    /** btpVN: value noise on a lattice. */
    static double vn(double x,double y,double s) {
        double ix=Math.floor(x),iy=Math.floor(y),fx=x-ix,fy=y-iy;
        return h(ix,iy,s)*(1-fx)*(1-fy)+h(ix+1,iy,s)*fx*(1-fy)+h(ix,iy+1,s)*(1-fx)*fy+h(ix+1,iy+1,s)*fx*fy;
    }
    private static double h(double a,double b,double s) { return random(a*57.1+b*113.7+s*7.3); }
    /** btpBend: kinks like broken porcelain. */
    static double[] bend(double x,double y) {
        return new double[]{x+(vn(x/2.6,y/2.6,1)-.5)*.8+(vn(x/1.1,y/1.1,3)-.5)*.25,y+(vn(x/2.6,y/2.6,2)-.5)*.8+(vn(x/1.1,y/1.1,4)-.5)*.25};
    }
    /**
     * The lab masks everything with the skin of its painted face. On the block face the skin is the square below the hair rows (the
     * frame maps the lab rect so the first crack sits on the cheekbone under the eye row): {@link BtFaceShell#U0}, {@link BtFaceShell#V0}.
     */
    static double onSkin(double x,double y) {
        double u=BtFaceShell.faceU(x),v=BtFaceShell.faceV(y);
        return BtMath.smooth(.18,.26,v)*BtMath.smooth(0,.05,u)*BtMath.smooth(0,.05,1-u)*BtMath.smooth(0,.03,1-v);
    }
    static double jolt(double[][] front,double f) { return BtPeakMath.jolt(front,f); }

    BtFaceFrac() {
        List<Cell> cells=new ArrayList<>();
        cells.add(new Cell(new double[][]{{RX,RY},{RX+RW,RY},{RX+RW,RY+RH},{RX,RY+RH}},0));
        List<Cut> cuts=new ArrayList<>();
        grow(cells,cuts,1,dd->22+.7*dd*dd);
        pick(cells,PEEL1,1,11,8,40,OX,OY,0);
        // Frame 5: 3–4 large polygons of light open on the cheek, picked from the first plates so the second cracking never runs through.
        pick(cells,PEEL2,2,17,45,170,OX,OY,7.5);
        // A second patch by the nose and the other cheek.
        pick(cells,PEEL3,2,12,30,140,386,211,0);
        // The second cracking stays on the cheek where it started; the nose, mouth and chin keep only the big first cracks.
        // The block face fills about 2.6 times more of the frame than the lab's face, so the lab's plates read as big flaps there
        // (codex 31.10, Mac frames): the second and third crackings split finer than the lab's (14+.3dd², 12+.16dd²).
        grow(cells,cuts,2,dd->dd<17?8+.2*dd*dd:1e9);
        // In the last half second before the blow the cracking runs over the whole face.
        grow(cells,cuts,3,dd->6+.09*dd*dd);
        for(Cut c:cuts)segs.add(seg(c));
        for(Cell c:cells) {
            if(c.peel)continue;
            double[] m=centroid(c.p);
            if(onSkin(m[0],m[1])<=.5)continue;
            double dx=m[0]-OX,dy=m[1]-OY,dl=Math.max(1e-9,Math.hypot(dx,dy));
            double fx=m[0]-402,fy=m[1]-206,fl=Math.max(1e-9,Math.hypot(fx,fy));
            double v=230+220*rnd(),up=60+120*rnd(),sw=(rnd()-.3)*90;
            double spin=(rnd()<.5?-1:1)*(3+5*rnd()),flip=4+6*rnd(),g=1.4+1.4*rnd();
            burst.add(new Plate(outline(c.p),m[0],m[1],Math.sqrt(area(c.p)/Math.PI)+1,dl/60*.32,0,fx/fl,fy/fl,0,spin,flip,v,up,sw,g));
        }
    }

    private interface MaxArea { double at(double dd); }

    /** Split the plate that is largest for its place until each is small enough there; every cut is a new crack. */
    private void grow(List<Cell> cells,List<Cut> cuts,int stage,MaxArea maxA) {
        for(int g=0;g<600;g++) {
            int bi=-1;double bs=1;
            for(int i=0;i<cells.size();i++) {
                Cell c=cells.get(i);if(c.keep)continue;
                double[] m=centroid(c.p);double s=area(c.p)/maxA.at(Math.hypot(m[0]-OX,m[1]-OY));
                if(s>bs){bs=s;bi=i;}
            }
            if(bi<0)return;
            Cell c=cells.get(bi);double[] m=centroid(c.p);double L=0,ax=0;
            for(double[] p:c.p)for(double[] q:c.p){double l=Math.hypot(q[0]-p[0],q[1]-p[1]);if(l>L){L=l;ax=Math.atan2(q[1]-p[1],q[0]-p[0]);}}
            double cx=m[0]+(rnd()-.5)*L*.3,cy=m[1]+(rnd()-.5)*L*.3,an=ax+Math.PI/2+(rnd()-.5)*1.1;
            double[][][] r=cut(c.p,cx,cy,an);
            if(r==null){c.keep=true;continue;}
            cells.remove(bi);
            cells.add(bi,new Cell(r[1],c.depth+1));
            cells.add(bi,new Cell(r[0],c.depth+1));
            cuts.add(new Cut(r[2][0],r[2][1],stage,c.depth));
        }
    }

    /** Plates come off nearest to a point first, so the holes join into one growing patch of light, each kept apart by skin. */
    private void pick(List<Cell> cells,double[] times,int stage,double dMax,double aMin,double aMax,double gx,double gy,double sep) {
        record Cand(Cell c,double k) { }
        List<Cand> cand=new ArrayList<>();
        for(Cell c:cells) {
            if(c.keep)continue;
            double[] m=centroid(c.p);double a=area(c.p),dd=Math.hypot(m[0]-gx,m[1]-gy);
            if(!(dd<dMax&&a>=aMin&&a<=aMax&&(stage==1||m[1]>204)&&onSkin(m[0],m[1])>.9))continue;
            boolean inside=true;
            for(double[] p:c.p)if(onSkin(p[0]*.6+m[0]*.4,p[1]*.6+m[1]*.4)<=.6){inside=false;break;}
            if(!inside)continue;
            cand.add(new Cand(c,0));
        }
        List<Cand> keyed=new ArrayList<>();
        for(Cand e:cand){double[] m=centroid(e.c().p);keyed.add(new Cand(e.c(),Math.hypot(m[0]-gx,m[1]-gy)+rnd()*3));}
        keyed.sort(Comparator.comparingDouble(Cand::k));
        List<double[]> got=new ArrayList<>();List<Cell> sel=new ArrayList<>();
        for(Cand e:keyed) {
            if(got.size()>=times.length)break;
            double[] m=centroid(e.c().p);boolean far=true;
            for(double[] g:got)if(Math.hypot(g[0]-m[0],g[1]-m[1])<=sep){far=false;break;}
            if(far){got.add(m);sel.add(e.c());}
        }
        for(int i=0;i<times.length&&i<sel.size();i++) {
            Cell c=sel.get(i);c.keep=true;c.peel=true;
            double[] m=centroid(c.p);double ox=m[0]-395,oy=m[1]-212,ol=Math.max(1e-9,Math.hypot(ox,oy));
            double vx=(rnd()-.4)*7,spin=(rnd()<.5?-1:1)*(1.4+2*rnd()),flip=2+2.5*rnd();
            peels.add(new Plate(outline(c.p),m[0],m[1],Math.sqrt(area(c.p)/Math.PI)+1,times[i],stage,ox/ol,oy/ol,vx,spin,flip,0,0,0,0));
        }
    }

    /** When the front of its stage reaches a point (btpFrac T): the first over the cheek, the second over the face, the third at the end. */
    private static double time(double x,double y,int stage) {
        double dd=Math.max(0,Math.hypot(x-OX,y-OY)+(vn(x/3,y/3,9)-.5)*4);
        if(stage==3)return BtPeakMath.BLOW-.5+.42*Math.min(1,dd/60);
        return stage==1&&dd<=R1?jolt(BtPeakMath.JOLT1,Math.pow(dd/R1,1.25)):jolt(BtPeakMath.JOLT2,Math.pow(Math.min(1,dd/R2),1.25))+(stage==2?.07:0);
    }

    private Seg seg(Cut s) {
        double L=Math.hypot(s.b()[0]-s.a()[0],s.b()[1]-s.a()[1]);int n=Math.max(2,(int)Math.ceil(L/.3));
        double[] pts=new double[(n+1)*3];double tmin=1e9;
        for(int i=0;i<=n;i++) {
            double x=s.a()[0]+(s.b()[0]-s.a()[0])*i/n,y=s.a()[1]+(s.b()[1]-s.a()[1])*i/n,tt=time(x,y,s.stage());
            double[] b=bend(x,y);pts[i*3]=b[0];pts[i*3+1]=b[1];pts[i*3+2]=tt;tmin=Math.min(tmin,tt);
        }
        double nx=-(s.b()[1]-s.a()[1])/Math.max(1e-9,L),ny=(s.b()[0]-s.a()[0])/Math.max(1e-9,L);
        // Ink weight varies two to three times: the first long cracks are the heaviest.
        double w=(s.depth()<1?.72:s.depth()<3?.46:.28)*(.8+.4*rnd());
        // Most seams stay dark; some open and pour light: a few near the start at first, many more later, most in the last moment.
        double mx=(s.a()[0]+s.b()[0])/2,my=(s.a()[1]+s.b()[1])/2,dm=Math.hypot(mx-OX,my-OY),open;
        if(s.stage()==3)open=rnd()<.55&&!(my>163&&my<204)?tmin+.05+rnd()*.12:1e9;
        else if(s.stage()==1&&dm<15&&rnd()<.42)open=tmin+1.3+rnd()*2;
        else open=rnd()<(s.stage()==1?.22:.16)?Math.max(tmin,T2)+.6+rnd()*2.6:1e9;
        double od=s.stage()==3?.25:1.6;
        double ow=s.stage()==3?.5+1.2*rnd():open<T2?.5+1.1*rnd():.6+2*rnd();
        double oh=Math.min(.5,(1.6+3.2*rnd())/Math.max(1e-9,L)),oc=oh+(1-2*oh)*rnd();
        return new Seg(pts,nx,ny,w,open,od,ow,oh,oc,s.stage());
    }

    static double area(double[][] p) { double a=0;for(int i=0;i<p.length;i++){double[] q=p[i],r=p[(i+1)%p.length];a+=q[0]*r[1]-r[0]*q[1];}return Math.abs(a)/2; }
    static double[] centroid(double[][] p) { double x=0,y=0;for(double[] q:p){x+=q[0];y+=q[1];}return new double[]{x/p.length,y/p.length}; }
    /** Cuts a convex polygon by the line through (cx, cy) at angle an: both halves and the two crossing points, or null. */
    static double[][][] cut(double[][] p,double cx,double cy,double an) {
        double dx=Math.cos(an),dy=Math.sin(an);List<double[]> A=new ArrayList<>(),B=new ArrayList<>(),X=new ArrayList<>();
        for(int i=0;i<p.length;i++) {
            double[] q=p[i],r=p[(i+1)%p.length];double sq=(q[0]-cx)*dy-(q[1]-cy)*dx,sr=(r[0]-cx)*dy-(r[1]-cy)*dx;
            (sq>=0?A:B).add(q);
            if((sq>=0)!=(sr>=0)){double f=sq/(sq-sr);double[] x={q[0]+(r[0]-q[0])*f,q[1]+(r[1]-q[1])*f};A.add(x);B.add(x);X.add(x);}
        }
        return X.size()==2&&A.size()>2&&B.size()>2?new double[][][]{A.toArray(double[][]::new),B.toArray(double[][]::new),X.toArray(double[][]::new)}:null;
    }
    /** The bent outline of a plate, about every 0.3 px. */
    static double[][] outline(double[][] p) {
        List<double[]> pts=new ArrayList<>();
        for(int i=0;i<p.length;i++){double[] q=p[i],r=p[(i+1)%p.length];int n=Math.max(1,(int)Math.ceil(Math.hypot(r[0]-q[0],r[1]-q[1])/.3));
            for(int k=0;k<n;k++)pts.add(bend(q[0]+(r[0]-q[0])*k/n,q[1]+(r[1]-q[1])*k/n));}
        return pts.toArray(double[][]::new);
    }
}
