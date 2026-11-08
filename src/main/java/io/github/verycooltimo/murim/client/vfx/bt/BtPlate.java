package io.github.verycooltimo.murim.client.vfx.bt;
import java.util.ArrayList;
import java.util.List;

/** lab-common bloodSilhouette quadratic boundary, sampled for deterministic 5px fire rows and branch clipping.
 * Canvas path rasterisation is replaced with polygon crossing; quadratic curves use 32 subdivisions. */
final class BtPlate {
    private static final List<double[]> OUTLINE=outline();
    private static final double[][] ROWS=rows();
    private BtPlate() {}
    private static List<double[]> outline() {
        List<double[]> p=new ArrayList<>();p.add(new double[]{383,86});
        q(p,402,80,408,105);q(p,443,118,440,176);l(p,433,209);q(p,442,231,461,243);l(p,516,279);l(p,514,305);l(p,548,382);l(p,542,412);q(p,581,428,578,478);l(p,599,530);l(p,630,557);q(p,611,581,465,584);l(p,405,596);l(p,325,584);q(p,214,586,159,546);l(p,180,517);l(p,211,454);q(p,220,432,242,420);l(p,245,385);l(p,269,286);q(p,296,255,334,240);l(p,348,208);l(p,343,163);q(p,337,125,370,112);
        return List.copyOf(p);
    }
    private static void l(List<double[]> p,double x,double y){p.add(new double[]{x,y});}
    private static void q(List<double[]> p,double cx,double cy,double x,double y) {
        double[] a=p.get(p.size()-1);for(int i=1;i<=32;i++){double t=i/32.0,u=1-t;p.add(new double[]{u*u*a[0]+2*u*t*cx+t*t*x,u*u*a[1]+2*u*t*cy+t*t*y});}
    }
    static boolean inside(double x,double y) {
        boolean in=false;for(int i=0,j=OUTLINE.size()-1;i<OUTLINE.size();j=i++) {double[] a=OUTLINE.get(i),b=OUTLINE.get(j);if((a[1]>y)!=(b[1]>y)&&x<(b[0]-a[0])*(y-a[1])/(b[1]-a[1])+a[0])in=!in;}return in;
    }
    private static double[][] rows() {
        double[][] rows=new double[97][];
        for(int i=0;i<97;i++) {
            int yy=100+i*5,left=-1,right=-1;
            for(int x=150;x<640;x++)if(inside(x,yy)){if(left<0)left=x;right=x;}
            rows[i]=left<0?new double[]{400,40}:new double[]{(left+right)/2.0,(right-left)/2.0};
        }
        return rows;
    }
    static double[] row(double y) { return ROWS[(Math.max(100,Math.min(580,(int)Math.round(y/5)*5))-100)/5]; }
}
