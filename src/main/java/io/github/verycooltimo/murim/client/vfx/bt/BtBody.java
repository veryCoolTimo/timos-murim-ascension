package io.github.verycooltimo.murim.client.vfx.bt;

import io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer;
import io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone;
import net.minecraft.world.phys.Vec3;
import java.util.EnumMap;
import java.util.Map;

/** Per-frame animated body, camera-relative plate frame. API: BreakthroughVfx.context / BoneAnchorLayer.position. */
public record BtBody(Map<Bone,Vec3> bones, Vec3 axis, Vec3 right, Vec3 near, double ground, double scale) {
    public static BtBody of(net.minecraft.world.entity.Entity player,Vec3 camera) {
        Map<Bone,Vec3> bones=new EnumMap<>(Bone.class);
        for(Bone b:Bone.values()) { Vec3 v=BoneAnchorLayer.position(player,b);if(v!=null)bones.put(b,v); }
        Vec3 d=bones.get(Bone.DANTIAN),ch=bones.get(Bone.CHEST);
        if(d==null||ch==null)return null;
        Vec3 near=new Vec3(camera.x-ch.x,0,camera.z-ch.z).normalize();
        if(near.lengthSqr()<1e-8)near=new Vec3(0,0,1);
        return new BtBody(bones,new Vec3(ch.x,d.y,ch.z),new Vec3(near.z,0,-near.x),near,player.getY(),BtMath.bodyScale(d.y-player.getY()));
    }
    public BtSilhouette silhouette() {
        double head=bone(Bone.HEAD).y-ground;
        double headX=bone(Bone.HEAD).subtract(axis).dot(right);
        double shoulder=bone(Bone.CHEST).y-ground+.06;
        double kneeY=Math.max(bone(Bone.LEFT_KNEE).y,bone(Bone.RIGHT_KNEE).y)-ground;
        double knees=Math.max(.45,Math.abs(bone(Bone.LEFT_KNEE).subtract(bone(Bone.RIGHT_KNEE)).dot(right))*.5+.1);
        double shoulders=Math.max(.37,Math.abs(bone(Bone.LEFT_SHOULDER).subtract(bone(Bone.RIGHT_SHOULDER)).dot(right))*.5+.13);
        // Floor -> right knee/hand/shoulder -> neck/head/knot -> left side. CCW.
        return new BtSilhouette(java.util.List.of(
            new BtSilhouette.Point(-knees-.08,.025),new BtSilhouette.Point(knees+.08,.025),
            new BtSilhouette.Point(knees+.1,Math.max(.12,kneeY-.08)),new BtSilhouette.Point(knees,Math.max(.22,kneeY+.09)),
            new BtSilhouette.Point(Math.max(shoulders,Math.abs(bone(Bone.RIGHT_HAND).subtract(axis).dot(right))+.14),bone(Bone.RIGHT_HAND).y-ground),
            new BtSilhouette.Point(shoulders,shoulder),new BtSilhouette.Point(headX+.10,head-.15),
            new BtSilhouette.Point(headX+.25,head),new BtSilhouette.Point(headX+.25,head+.19),
            new BtSilhouette.Point(headX+.10,head+.23),new BtSilhouette.Point(headX-.10,head+.23),
            new BtSilhouette.Point(headX-.25,head+.19),new BtSilhouette.Point(headX-.25,head),
            new BtSilhouette.Point(headX-.10,head-.15),new BtSilhouette.Point(-shoulders,shoulder),
            new BtSilhouette.Point(-Math.max(shoulders,Math.abs(bone(Bone.LEFT_HAND).subtract(axis).dot(right))+.14),bone(Bone.LEFT_HAND).y-ground),
            new BtSilhouette.Point(-knees,Math.max(.22,kneeY+.09)),new BtSilhouette.Point(-knees-.1,Math.max(.12,kneeY-.08))));
    }
    public Vec3 silhouettePoint(BtSilhouette.Point p,double depth) {
        return new Vec3(axis.x,ground+p.y(),axis.z).add(right.scale(p.x())).add(near.scale(depth));
    }
    public double px(double v) { return BtMath.blocks(v,scale); }
    public Vec3 bone(Bone b) { return bones.getOrDefault(b,axis); }
    /** The plate ellipse is perspective compression, not an actual narrow ring: undo .22 in depth. */
    public Vec3 plate(double x,double floorY,double height) {
        return new Vec3(axis.x,ground+.012,axis.z).add(right.scale(px(x-400))).add(near.scale(px((floorY-594)/.22))).add(0,px(height),0);
    }
    /** Bind plate skin points to the nearest measured channel, preserving local offsets around its bones. */
    public Vec3 skinPoint(double x,double y) {
        if(y<252) return surface(bone(Bone.HEAD)).add(right.scale(px(x-402))).add(0,px(212-y),0);
        double best=Double.POSITIVE_INFINITY;Vec3 result=axis;
        for(int ch=0;ch<5;ch++) {
            double[][] line=BtChannels.LINES[ch];Vec3[] anchors=BtChannels.anchors(this,ch);
            for(int j=1;j<line.length;j++) {
                double dx=line[j][0]-line[j-1][0],dy=line[j][1]-line[j-1][1];
                double u=BtMath.clamp(((x-line[j-1][0])*dx+(y-line[j-1][1])*dy)/(dx*dx+dy*dy));
                double qx=line[j-1][0]+u*dx,qy=line[j-1][1]+u*dy,d=(x-qx)*(x-qx)+(y-qy)*(y-qy);
                if(d<best){best=d;result=surface(anchors[j-1].scale(1-u).add(anchors[j].scale(u))).add(right.scale(px(x-qx))).add(0,px(qy-y),0);}
            }
        }
        return result;
    }
    public Vec3 surface(Vec3 p) { return p.add(near.scale(.17)); }
    public boolean faceClear(Vec3 p) { Vec3 q=p.subtract(axis);return !(q.dot(near)>0&&p.y-ground>.8&&Math.abs(q.dot(right))<.22); }
}
