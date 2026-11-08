package io.github.verycooltimo.murim.client.vfx.bt;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class BtSilhouetteTest {
    private static BtSilhouette sample() {
        return new BtSilhouette(List.of(new BtSilhouette.Point(-.6,0),new BtSilhouette.Point(.6,0),
                new BtSilhouette.Point(.5,.3),new BtSilhouette.Point(.25,.7),new BtSilhouette.Point(.12,1.1),
                new BtSilhouette.Point(-.12,1.1),new BtSilhouette.Point(-.25,.7),new BtSilhouette.Point(-.5,.3)));
    }
    @Test void closedAndEvenOffset() {
        var a=sample().polygon();var b=sample().outline(.02);
        assertEquals(a.getFirst(),a.getLast());assertEquals(b.getFirst(),b.getLast());
        for(int i=0;i<a.size();i++)assertEquals(.02,Math.hypot(a.get(i).x()-b.get(i).x(),a.get(i).y()-b.get(i).y()),1e-10);
    }
    @Test void noCrossings() {
        var p=sample().polygon();int n=p.size()-1;
        for(int i=0;i<n;i++)for(int j=i+2;j<n;j++) {
            if(i==0&&j==n-1)continue;
            var a=p.get(i);var b=p.get(i+1);var c=p.get(j);var d=p.get(j+1);
            assertFalse(cross(a,b,c)*cross(a,b,d)<0&&cross(c,d,a)*cross(c,d,b)<0);
        }
    }
    private static double cross(BtSilhouette.Point a,BtSilhouette.Point b,BtSilhouette.Point c){return (b.x()-a.x())*(c.y()-a.y())-(b.y()-a.y())*(c.x()-a.x());}
}
