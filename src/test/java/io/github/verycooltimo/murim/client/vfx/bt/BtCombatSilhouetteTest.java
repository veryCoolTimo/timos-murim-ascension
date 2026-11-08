package io.github.verycooltimo.murim.client.vfx.bt;

import io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone;
import io.github.verycooltimo.murim.technique.SixRules;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class BtCombatSilhouetteTest {
    private static BtBody body(double x, double z, float scale) {
        Map<Bone,Vec3> bones=new EnumMap<>(Bone.class);
        for(Bone bone:Bone.values()) bones.put(bone,new Vec3(x,scale,z));
        bones.put(Bone.HEAD,new Vec3(x,1.7*scale,z));
        bones.put(Bone.CHEST,new Vec3(x,1.3*scale,z));
        bones.put(Bone.LEFT_KNEE,new Vec3(x-.2*scale,.5*scale,z));
        bones.put(Bone.RIGHT_KNEE,new Vec3(x+.2*scale,.5*scale,z));
        bones.put(Bone.LEFT_SHOULDER,new Vec3(x-.3*scale,1.3*scale,z));
        bones.put(Bone.RIGHT_SHOULDER,new Vec3(x+.3*scale,1.3*scale,z));
        // Contact downswing: right hand ahead and below the shoulder.
        bones.put(Bone.RIGHT_HAND,new Vec3(x+.6*scale,.8*scale,z));
        return new BtBody(bones,new Vec3(x,scale,z),new Vec3(1,0,0),new Vec3(0,0,1),0,scale);
    }
    @Test void counterImpactKeepsCasterAndHitTargetAtGiantScale() {
        assertParticipants(4,-6);
    }
    @Test void fearImpactKeepsCasterAndHitTargetAtGiantScale() {
        assertParticipants(3,-3);
    }
    private static void assertParticipants(int form,double z) {
        var giant=new SixRules.GiantState(form==3?SixRules.FEAR_GIANT_END:SixRules.GIANT_END);
        float scale=giant.scale(form==3?SixRules.FEAR_GIANT_END:SixRules.GIANT_END);
        assertEquals(SixRules.GIANT_SCALE,scale);
        var view=new Matrix4f();
        var projection=new Matrix4f().perspective((float)Math.toRadians(70),1.6F,.05F,100);
        var bodies=Map.of(10,body(-1,z,scale),20,body(1,z,1));
        var captured=BtImpactFrame.projectCombat(bodies,Vec3.ZERO,view,projection);
        assertEquals(Set.of(10,20),captured.keySet());
        float giantHeight=height(captured.get(10));
        var normal=BtImpactFrame.projectCombat(Map.of(10,body(-1,z,1)),Vec3.ZERO,view,projection);
        assertTrue(giantHeight>height(normal.get(10))*2.3F);
        giant.impactFrameStarted();
        assertEquals(1,giant.scale(100));
        assertEquals(giantHeight,height(captured.get(10)),"mask must retain the contact snapshot after shrink");
    }
    @Test void outlineCrossingCameraPlaneIsClippedInsteadOfDiscarded() {
        var points=java.util.List.of(new Vec3(-1,0,-2),new Vec3(1,0,-2),
                new Vec3(1,2,1),new Vec3(-1,2,1),new Vec3(-1,0,-2));
        var result=BtProjection.projectPolygon(points,Vec3.ZERO,new Matrix4f(),
                new Matrix4f().perspective(1,1,.05F,100));
        assertFalse(result.isEmpty());
        for(float[] point:result) for(float coordinate:point) assertTrue(Float.isFinite(coordinate));
    }
    private static float height(java.util.List<float[]> polygon) {
        float min=Float.POSITIVE_INFINITY,max=Float.NEGATIVE_INFINITY;
        for(float[] point:polygon) { min=Math.min(min,point[1]);max=Math.max(max,point[1]); }
        return max-min;
    }
}
