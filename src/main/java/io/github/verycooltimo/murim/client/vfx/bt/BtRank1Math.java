package io.github.verycooltimo.murim.client.vfx.bt;

import java.util.ArrayList;
import java.util.List;
import static io.github.verycooltimo.murim.client.vfx.bt.BtMath.*;

/** br1Level, br1InStreams, br1Bez: deterministic lab seconds and plate pixels. */
public final class BtRank1Math {
    public static final double BLOW=15.2;
    public record Level(double energy,double gust,double control,double tear,double shell,double violet,double ball,double dense,double gather,double flash,double aura) {}
    public static Level level(double t) {
        double e=t-BLOW,control=smooth(8.6,11.6,t),gust=smooth(2.8,4.4,t)*(1-smooth(8.8,10.4,t))*smooth(.4,.75,noise(t*1.2,17));
        return new Level(.95*smooth(1,3.6,t)*(1+.45*gust)*(1-control),gust,control,
                smooth(4.8,6.4,t)*(1-smooth(8.6,9.6,t)),e>=0?0:smooth(9.2,11.6,t),smooth(11.6,14.2,t),
                smooth(5.4,8,t)*(1-smooth(BLOW,BLOW+.2,t)),smooth(11,14.5,t),e>=0?0:smooth(14.4,15.15,t),e>=0?Math.exp(-e*6):0,e>=0?1-.15*smooth(1.5,4.5,e):0);
    }
    public record Point(double x,double y) {}
    public record Stream(Point start,Point bend,Point end,int t0,int t1,double size,double seed) {}
    private static double r(int i,int k){return random(i*37+k+9100);}
    public static List<Stream> streams() {
        List<Stream> out=new ArrayList<>();
        for(int i=0;i<14;i++) {
            double f=(i/2+.15+.7*r(i,1))/7,an=i%2==1?-Math.PI/2+.45+f*1.35:-Math.PI/2-.45-f*1.35,dx=Math.cos(an),dy=Math.sin(an),d=10;
            while(d<400&&BtPlate.inside(400+dx*d,400+dy*d))d+=2;
            Point end=new Point(400+dx*(d-6),400+dy*(d-6)),start=new Point(400+dx*(680+160*r(i,7)),400+dy*(680+160*r(i,7)));
            double bend=(r(i,2)-.5)*300;Point middle=new Point((start.x+end.x)/2-dy*bend,(start.y+end.y)/2+dx*bend);
            int t1=(int)Math.round((BLOW-.45*Math.pow(r(i,3),1.7))*20);
            out.add(new Stream(start,middle,end,t1-(int)Math.round(9+6*r(i,4)),t1,.34+.16*r(i,5),r(i,6)));
        }
        return List.copyOf(out);
    }
    public static Point bezier(Stream s,double q) {double u=1-q;return new Point(u*u*s.start.x+2*u*q*s.bend.x+q*q*s.end.x,u*u*s.start.y+2*u*q*s.bend.y+q*q*s.end.y);}
    private BtRank1Math() {}
}
