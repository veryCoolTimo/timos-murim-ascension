package io.github.verycooltimo.murim.client.vfx.bt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BtRank2MathTest {
    @Test void orbitLiesOnOneTiltedPlaneAndSpansTheBody() {
        for(double radius:new double[]{288,342,396})for(int i=0;i<64;i++) {
            double angle=i*Math.PI*2/64,z=radius*Math.sin(angle);
            double h=BtRank2Math.orbitHeight(radius,angle);
            assertEquals(252,h+.36*z,1e-9);
            assertTrue(h/360>=.19&&h/360<=1.21);
        }
    }
    @Test void landedStonesStopMovingUntilFinalHalfSecond() {
        for(var o:BtRank2Math.make())if(o.kind()==BtRank2Math.Kind.PEBBLE) {
            var a=BtRank2Math.position(o,18);var b=BtRank2Math.position(o,19.5);
            assertEquals(0,a.height());assertEquals(a.x(),b.x());assertEquals(a.floorY(),b.floorY());
        }
    }
    @Test void deterministicItemsAndCounts() {
        assertEquals(BtRank2Math.make(),BtRank2Math.make());
        for(var o:BtRank2Math.make())assertTrue(o.radius()>=288&&o.radius()<=396);
        assertEquals(362,BtRank2Math.make().size());
        assertEquals(88,BtRank2Math.make().stream().filter(o->o.kind()==BtRank2Math.Kind.PEBBLE).count());
    }
    @Test void frozenOrbitAndContinuity() {
        for(var o:BtRank2Math.make()) {
            var a=BtRank2Math.position(o,14.6);var b=BtRank2Math.position(o,15.199);
            assertEquals(a.angle(),b.angle());assertEquals(a.height(),b.height());assertEquals(a.floorY(),b.floorY());
            // Lab intentionally adds 1.3px stone hum and keeps leaf flutter during the held orbital position.
            assertEquals(a.x(),b.x(),o.kind()==BtRank2Math.Kind.LEAF?28:o.kind()==BtRank2Math.Kind.PEBBLE?2.6:1e-9);
            var before=BtRank2Math.position(o,14.6-1e-8);
            assertEquals(before.height(),a.height(),1e-4);assertEquals(before.angle(),a.angle(),1e-6);
        }
    }
    @Test void landingSolvesBallisticEquation() {
        double h=125,up=240,g=2600,t=BtRank2Math.landingTime(h,up,g);
        assertEquals(0,h+up*t-.5*g*t*t,1e-9);
        var stone=BtRank2Math.make().stream().filter(o->o.kind()==BtRank2Math.Kind.PEBBLE).findFirst().orElseThrow();
        double land=BtRank2Math.position(stone,15.3).landing();
        assertEquals(0,BtRank2Math.position(stone,land+.17).height());
    }
    @Test void counterClockwiseAndScale() {
        assertTrue(BtRank2Math.spin(9)>BtRank2Math.spin(7));
        var item=BtRank2Math.make().get(0);
        assertTrue(BtRank2Math.position(item,9).angle()<BtRank2Math.position(item,7).angle());
        // X right, Z toward camera: decreasing angle is CCW viewed from above (+Y).
        assertEquals(1,BtMath.blocks(360,1));assertEquals(.5,BtMath.blocks(180,1));
        assertEquals(1,BtMath.bodyScale(145.0/360));assertEquals(.8,BtMath.bodyScale(0));assertEquals(1.25,BtMath.bodyScale(4));
    }
}
