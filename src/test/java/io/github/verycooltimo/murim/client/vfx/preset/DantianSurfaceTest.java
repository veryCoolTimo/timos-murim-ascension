package io.github.verycooltimo.murim.client.vfx.preset;

import io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone;
import io.github.verycooltimo.murim.client.vfx.bt.BtBody;
import io.github.verycooltimo.murim.client.vfx.bt.BtChannels;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.EnumMap;
import static org.junit.jupiter.api.Assertions.*;

/** API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/phys/Vec3.java: constructor, add, scale, lerp, normalize, dot, subtract, lengthSqr. */
class DantianSurfaceTest {
    @Test void actualGeneratedSurfacePointsStayInsideTheSilhouetteBandAtEveryScale() {
        for(double scale:new double[]{.5,1,2}) {
            var bones=new EnumMap<Bone,Vec3>(Bone.class);
            put(bones,Bone.DANTIAN,0,.62,scale);put(bones,Bone.CHEST,0,1.06,scale);
            put(bones,Bone.HEAD,0,1.4,scale);
            for(int sign:new int[]{-1,1}) {
                boolean left=sign<0;
                put(bones,left?Bone.LEFT_SHOULDER:Bone.RIGHT_SHOULDER,sign*.25,1.12,scale);
                put(bones,left?Bone.LEFT_ELBOW:Bone.RIGHT_ELBOW,sign*.37,.85,scale);
                put(bones,left?Bone.LEFT_HAND:Bone.RIGHT_HAND,sign*.26,.64,scale);
                put(bones,left?Bone.LEFT_HIP:Bone.RIGHT_HIP,sign*.16,.51,scale);
                put(bones,left?Bone.LEFT_KNEE:Bone.RIGHT_KNEE,sign*.54,.35,scale);
                put(bones,left?Bone.LEFT_FOOT:Bone.RIGHT_FOOT,-sign*.22,.17,scale);
            }
            var body=new BtBody(bones,new Vec3(0,.62*scale,0),new Vec3(1,0,0),new Vec3(0,0,1),0,scale);
            for(var route:DantianVeins.routes()) {
                var points=DantianFlow.routePoints(body,route);
                assertEquals(points,DantianFlow.routePoints(body,route));
                if(route.region()==DantianVeins.Region.CENTRAL&&route.segment()==0) {
                    Vec3 centre=DantianFlow.veinSurface(body,body.bone(Bone.DANTIAN));
                    for(double t=DantianFlowMath.MERIDIAN_START;t<=DantianFlowMath.RETRACT_START;t+=.05) {
                        Vec3 delta=DantianFlow.routePoints(body,route,t).getFirst().subtract(centre);
                        // Measure in the body plane; surface depth varies over the torso.
                        double x=delta.dot(body.right());
                        assertEquals(DantianVeins.coreRadius(t,scale),Math.hypot(x,delta.y),1e-12);
                    }
                }
                Vec3[] a=BtChannels.anchors(body,route.channel());
                Vec3 key=switch(route.region()) {
                    case CENTRAL -> a[route.segment()+1];
                    case CHEST -> BtChannels.anchors(body,0)[2].lerp(a[2],.65).add(0,-.07*scale,0);
                    case COLLAR -> a[1];
                    case SHOULDER -> a[2];
                    case UPPER_ARM -> a[6];
                    case FOREARM -> a[11];
                    case HAND -> a[11].add(0,-.065*scale,0).add(body.right().scale((route.channel()==1?-1:1)*.025*scale));
                    case LAP -> a[1];
                    case THIGH -> a[4];
                    case SHIN -> a[7].add(0,-.035*scale,0);
                    case NECK -> body.bone(Bone.HEAD).add(0,-.11*scale,0);
                };
                Vec3 expected=DantianFlow.veinSurface(body,key);
                if(route.channel()>=3)expected=expected.add(body.near().scale((route.channel()==3?.05:-.05)*scale));
                assertTrue(expected.subtract(points.getLast()).lengthSqr()<1e-20,route.region().name());
                if(route.region()==DantianVeins.Region.UPPER_ARM) {
                    assertTrue(DantianFlow.veinSurface(body,a[2]).subtract(points.getFirst()).lengthSqr()<1e-20,
                            "Upper arm must start at the shoulder node");
                }
                if(route.channel()==3)for(var other:DantianVeins.routes())if(other.channel()==4) {
                    var q=DantianFlow.routePoints(body,other);
                    for(int i=1;i<points.size();i++)for(int j=1;j<q.size();j++)
                        for(int k=0;k<=10;k++)for(int l=0;l<=10;l++)
                            assertTrue(points.get(i-1).lerp(points.get(i),k/10.0)
                                    .subtract(q.get(j-1).lerp(q.get(j),l/10.0)).lengthSqr()
                                    >=.04*.04*scale*scale-1e-12,"Crossed leg routes overlap");
                }
                for(var other:DantianVeins.routes()) {
                    if(other==route)continue;
                    var q=DantianFlow.routePoints(body,other);
                    Vec3 direction=points.getLast().subtract(points.getFirst()).normalize();
                    Vec3 otherDirection=q.getLast().subtract(q.getFirst()).normalize();
                    if(Math.abs(direction.dot(otherDirection))<.98)continue;
                    // Shared junctions are allowed; nearby parallel interiors are not.
                    for(double u:new double[]{.25,.5,.75}) {
                        Vec3 at=points.getFirst().lerp(points.getLast(),u);
                        Vec3 delta=q.getLast().subtract(q.getFirst());
                        double along=at.subtract(q.getFirst()).dot(delta)/delta.lengthSqr();
                        if(along<=.1||along>=.9)continue;
                        assertTrue(at.subtract(q.getFirst().lerp(q.getLast(),along)).lengthSqr()>=.03*.03*scale*scale,
                                route.region()+" parallels "+other.region());
                    }
                }

                for(var p:points) {
                    double[] row=body.silhouette().row(p.y);
                    double x=p.subtract(body.axis()).dot(body.right());
                    assertTrue(row[1]-Math.abs(x-row[0])>=Math.min(row[1],.055*scale)-1e-12,
                            route.region()+" touches the outer silhouette band");
                }
            }
        }
    }
    private static void put(EnumMap<Bone,Vec3> bones,Bone bone,double x,double y,double scale) {
        bones.put(bone,new Vec3(x*scale,y*scale,0));
    }
}
