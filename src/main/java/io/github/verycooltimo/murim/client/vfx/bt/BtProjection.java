package io.github.verycooltimo.murim.client.vfx.bt;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Same world-to-screen projection used by the meditation seed, shared with the graphic impact frame. */
public final class BtProjection {
    private BtProjection() {}
    /** Clip the closed outline before perspective division: a close-up giant may cross the camera plane. */
    public static java.util.List<float[]> projectPolygon(java.util.List<Vec3> points,Vec3 camera,Matrix4f view,Matrix4f projection) {
        java.util.List<Vector4f> clip=new java.util.ArrayList<>();
        for(Vec3 p:points) {
            Vector4f v=new Vector4f((float)(p.x-camera.x),(float)(p.y-camera.y),(float)(p.z-camera.z),1);
            view.transform(v);projection.transform(v);clip.add(v);
        }
        java.util.List<float[]> result=new java.util.ArrayList<>();
        if(clip.isEmpty())return result;
        Vector4f previous=clip.getLast();
        float near=.001F;
        for(Vector4f current:clip) {
            if((previous.w>=near)!=(current.w>=near)) {
                float t=(near-previous.w)/(current.w-previous.w);
                float x=previous.x+(current.x-previous.x)*t,y=previous.y+(current.y-previous.y)*t;
                result.add(new float[]{x/near*.5F+.5F,.5F-y/near*.5F});
            }
            if(current.w>=near)result.add(new float[]{current.x/current.w*.5F+.5F,.5F-current.y/current.w*.5F});
            previous=current;
        }
        if(result.size()<3)return java.util.List.of();
        float[] first=result.getFirst(),last=result.getLast();
        if(first[0]!=last[0]||first[1]!=last[1])result.add(first);
        return java.util.List.copyOf(result);
    }
    public static float[] project(Vec3 point,Vec3 camera,Matrix4f view,Matrix4f projection) {
        Vector4f v=new Vector4f((float)(point.x-camera.x),(float)(point.y-camera.y),(float)(point.z-camera.z),1);
        view.transform(v);projection.transform(v);
        if(v.w()<=1e-4F)return null;
        return new float[]{v.x()/v.w()*.5F+.5F,1-(v.y()/v.w()*.5F+.5F)};
    }
}
