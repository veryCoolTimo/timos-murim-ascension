package io.github.verycooltimo.murim.client.vfx.preset;

import java.util.List;
import static java.lang.Math.*;
import static io.github.verycooltimo.murim.client.vfx.preset.EssencePreset.*;

/** lab.js bez/bezD/pathPoint/twistAt/trimAt/openFold. Units: body heights, Y UP.
 * Screen-space normal [-dy,dx] becomes [dy,-dx] after reflecting Y. No game dependencies.
 */
public final class EssenceMath {
    public record Sample(Point position, Point tangent, Point normal) { }
    public record Twist(double facing, double side, double sine) { }
    public record Trim(double a, double b, double luma, double out) { }
    public static double clamp(double x, double a, double b) { return min(b, max(a, x)); }
    public static double mix(double a, double b, double t) { return a + (b-a)*t; }
    public static double fract(double x) { return x-floor(x); }
    public static double smooth(double a, double b, double x) {
        double f = clamp((x-a)/max(1e-12,b-a),0,1); return f*f*(3-2*f);
    }
    public static double hash(double i, double k, double seed) {
        return fract(sin((i+seed*1013.7)*12.9898+k*78.233)*43758.5453);
    }
    public static double noise(double x, double seed) {
        double i=floor(x), f=fract(x); return mix(hash(i,3.1,seed),hash(i+1,3.1,seed),f*f*(3-2*f));
    }
    public static Point curve(List<Point> p, double t) {
        if (p.size()!=4) {
            double q=clamp(t,0,1)*(p.size()-1); int i=min(p.size()-2,(int)floor(q)); double f=q-i;
            Point a=p.get(max(0,i-1)), b=p.get(i), c=p.get(i+1), d=p.get(min(p.size()-1,i+2));
            return new Point(cat(a.x(),b.x(),c.x(),d.x(),f), cat(a.y(),b.y(),c.y(),d.y(),f));
        }
        double m=1-t,a=m*m*m,b=3*m*m*t,c=3*m*t*t,d=t*t*t;
        return new Point(a*p.get(0).x()+b*p.get(1).x()+c*p.get(2).x()+d*p.get(3).x(),
                a*p.get(0).y()+b*p.get(1).y()+c*p.get(2).y()+d*p.get(3).y());
    }
    private static double cat(double a,double b,double c,double d,double f) {
        return .5*(2*b+(-a+c)*f+(2*a-5*b+4*c-d)*f*f+(-a+3*b-3*c+d)*f*f*f);
    }
    public static Point derivative(List<Point> p, double t) {
        if(p.size()!=4) {
            Point a=curve(p,max(0,t-.0001)), b=curve(p,min(1,t+.0001));
            return new Point(b.x()-a.x(),b.y()-a.y());
        }
        double m=1-t;
        return new Point(3*m*m*(p.get(1).x()-p.get(0).x())+6*m*t*(p.get(2).x()-p.get(1).x())+3*t*t*(p.get(3).x()-p.get(2).x()),
                3*m*m*(p.get(1).y()-p.get(0).y())+6*m*t*(p.get(2).y()-p.get(1).y())+3*t*t*(p.get(3).y()-p.get(2).y()));
    }
    public static Point normalized(Point p) { double n=hypot(p.x(),p.y()); return n==0?new Point(0,0):new Point(p.x()/n,p.y()/n); }
    public static Sample path(Layer l,double u,double t) {
        Point b=curve(l.path(),u),d=normalized(derivative(l.path(),u)),n=new Point(d.y(),-d.x());
        double off=l.p("turbAmp")*((noise(u*l.p("turbFreq")*4+t*l.p("turbSpeed")*2+11,l.seed())*2-1)
                +.18*sin(u*69+t*l.p("turbSpeed")+l.seed())*sin(PI*u));
        return new Sample(new Point(b.x()+n.x()*off,b.y()+n.y()*off),d,n);
    }
    public static Twist twist(Layer l,double u,double t,int sheet) {
        double seed=sheet*17.3;
        double warp=l.p("twistChaos")*(noise(u*l.p("twistFreq")*3+seed+t*l.p("twistSpeed")*.5,l.seed())-.5)*2*PI;
        double th=2*PI*l.p("turns")*u+warp+t*l.p("twistSpeed")*2+.7+seed;
        double k=1-l.p("twistRand")*(1-clamp(noise(u*l.p("twistRandFreq")*3+seed*1.7+50+t*l.p("twistSpeed")*.3,l.seed())*1.6-.3,0,1));
        double c=cos(th); return new Twist(1-k*(1-abs(c)),c>=0?1:-1,sin(th)*k);
    }
    public static double profile(Layer l,double u) {
        List<Double> p=l.widthProfile();
        if(p.isEmpty()) return 1+l.p("fan")*.2*sin(u*17+l.seed()*.7);
        double f=clamp(u,0,1)*(p.size()-1); int i=min(p.size()-2,(int)floor(f)); double z=f-i;
        return mix(p.get(i),p.get(i+1),z*z*(3-2*z));
    }
    public static double halfWidth(Layer l,double u,double t,int sheet) {
        return l.p("width")*.5*profile(l,u)*(l.p("edgeMin")+(1-l.p("edgeMin"))*twist(l,u,t,sheet).facing());
    }
    public static Trim trim(Layer l,double t,double lag) {
        double tt=t-lag,reveal=1-pow(1-clamp(tt/l.p("inDur"),0,1),l.p("easeIn"));
        double out=pow(clamp((tt-(l.p("dur")-l.p("outDur")))/l.p("outDur"),0,1),2);
        return new Trim(l.p("outMode")!=2?out:0,reveal,l.p("outMode")!=1?out:0,out);
    }
    /** GLSL hash/vnoise/fbm (column-major mat2), separate from the JS path noise. */
    public static double hash2(double x,double y) {
        x=fract(x*123.34); y=fract(y*456.21); double d=x*(x+45.32)+y*(y+45.32);
        return fract((x+d)*(y+d));
    }
    public static double noise2(double x,double y) {
        double ix=floor(x),iy=floor(y),u=smooth(0,1,fract(x)),v=smooth(0,1,fract(y));
        return mix(mix(hash2(ix,iy),hash2(ix+1,iy),u),mix(hash2(ix,iy+1),hash2(ix+1,iy+1),u),v);
    }
    public static double fbm(double x,double y,double oct) {
        double s=0,a=.5,n=0;
        for(int i=0;i<8;i++) {
            double w=clamp(oct-i,0,1); if(w<=0)break;
            s+=a*w*noise2(x,y);n+=a*w;double nx=1.6*x-1.2*y+17.1;y=1.2*x+1.6*y+9.2;x=nx;a*=.5;
        }
        return s/max(n,1e-4);
    }
    private EssenceMath() { }
}
