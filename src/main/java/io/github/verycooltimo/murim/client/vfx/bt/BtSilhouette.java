package io.github.verycooltimo.murim.client.vfx.bt;

import java.util.ArrayList;
import java.util.List;

/** Closed camera-plane geometry in blocks; three Chaikin passes round the live bone envelope. */
public final class BtSilhouette {
    public record Point(double x, double y) {
        Point mix(Point b,double f) { return new Point(x+(b.x-x)*f,y+(b.y-y)*f); }
    }
    private final List<Point> points;
    public BtSilhouette(List<Point> envelope) {
        if(envelope.size()<3)throw new IllegalArgumentException("silhouette needs three points");
        List<Point> p=new ArrayList<>(envelope);
        if(p.getFirst().equals(p.getLast()))p.removeLast();
        if(p.size()<3)throw new IllegalArgumentException("silhouette needs three distinct points");
        for(int pass=0;pass<3;pass++) {
            List<Point> q=new ArrayList<>();
            for(int i=0;i<p.size();i++){Point a=p.get(i),z=p.get((i+1)%p.size());q.add(a.mix(z,.25));q.add(a.mix(z,.75));}
            p=q;
        }
        points=List.copyOf(p);
    }
    /** Horizontal hull row: centre and half width, used to bind lab fire spawn rows to the seated bones. */
    public double[] row(double y) {
        double lo=Double.POSITIVE_INFINITY,hi=Double.NEGATIVE_INFINITY;
        for(int i=0;i<points.size();i++) {
            Point a=points.get(i),b=points.get((i+1)%points.size());
            if((a.y()>y)!=(b.y()>y)) {
                double x=a.x()+(b.x()-a.x())*(y-a.y())/(b.y()-a.y());
                lo=Math.min(lo,x);hi=Math.max(hi,x);
            }
        }
        return Double.isFinite(lo)?new double[]{(lo+hi)*.5,Math.max(.04,(hi-lo)*.5)}:new double[]{0,.1};
    }
    /** Closed polygon (last vertex repeats first). Counter-clockwise input gives outward positive offsets. */
    public List<Point> polygon() { return outline(0); }
    public List<Point> outline(double offset) {
        List<Point> out=new ArrayList<>();int n=points.size();
        for(int i=0;i<n;i++) {
            Point p=points.get(i),a=points.get((i+n-1)%n),b=points.get((i+1)%n);
            double ax=p.x-a.x,ay=p.y-a.y,bx=b.x-p.x,by=b.y-p.y;
            double al=Math.hypot(ax,ay),bl=Math.hypot(bx,by);
            double nx=ay/al+by/bl,ny=-ax/al-bx/bl,len=Math.hypot(nx,ny);
            out.add(new Point(p.x+offset*nx/len,p.y+offset*ny/len));
        }
        out.add(out.getFirst());return List.copyOf(out);
    }
}
