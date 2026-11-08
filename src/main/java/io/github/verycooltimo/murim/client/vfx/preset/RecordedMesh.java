package io.github.verycooltimo.murim.client.vfx.preset;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix4f;

/**
 * Vertices recorded in world space (the pose is ignored while recording and applied on replay), so a mesh built on one
 * client tick can be drawn again on the frames until the next one.
 * API: VertexConsumer 1.21.1 — abstract addVertex/setColor(int)/setUv/setUv1/setUv2/setNormal; the Matrix4f and Pose
 * overloads are defaults that transform first, overridden here to keep raw coordinates.
 */
final class RecordedMesh implements VertexConsumer {
    private static final int STRIDE=12;
    private float[] data=new float[STRIDE*4096];
    private int count=-1;

    void clear() { count=-1; }
    int vertices() { return count+1; }

    private void grow() { if((count+1)*STRIDE>=data.length)data=java.util.Arrays.copyOf(data,data.length*2); }
    @Override public VertexConsumer addVertex(float x,float y,float z) {
        count++;grow();int o=count*STRIDE;data[o]=x;data[o+1]=y;data[o+2]=z;
        data[o+3]=data[o+4]=data[o+5]=data[o+6]=1;data[o+7]=data[o+8]=0;data[o+9]=0;data[o+10]=1;data[o+11]=0;
        return this;
    }
    @Override public VertexConsumer addVertex(Matrix4f pose,float x,float y,float z) { return addVertex(x,y,z); }
    @Override public VertexConsumer setColor(int r,int g,int b,int a) {
        int o=count*STRIDE;data[o+3]=r/255F;data[o+4]=g/255F;data[o+5]=b/255F;data[o+6]=a/255F;return this;
    }
    @Override public VertexConsumer setUv(float u,float v) { int o=count*STRIDE;data[o+7]=u;data[o+8]=v;return this; }
    @Override public VertexConsumer setUv1(int u,int v) { return this; }
    @Override public VertexConsumer setUv2(int u,int v) { return this; }
    @Override public VertexConsumer setNormal(float x,float y,float z) { int o=count*STRIDE;data[o+9]=x;data[o+10]=y;data[o+11]=z;return this; }
    @Override public VertexConsumer setNormal(PoseStack.Pose pose,float x,float y,float z) { return setNormal(x,y,z); }

    /** Draws {@code to}, blended from {@code from} by {@code k} when both hold the same mesh (same vertex count). */
    static void replay(RecordedMesh from,RecordedMesh to,double k,VertexConsumer out,PoseStack.Pose pose) {
        replay(from,to,k,out,pose,null,false);
    }
    /** Main pass retains depth everywhere; the optional rim only marks silhouette crossings. */
    static void replay(RecordedMesh from,RecordedMesh to,double k,VertexConsumer out,PoseStack.Pose pose,
                       io.github.verycooltimo.murim.client.vfx.bt.BtBody body,boolean inner) {
        boolean blend=from!=null&&from.count==to.count&&k<1;
        float a=(float)(blend?Math.max(0,Math.min(1,k)):1),b=1-a;
        float[] p=blend?from.data:to.data,q=to.data;
        var silhouette=inner&&body!=null?body.silhouette():null;
        for(int i=0;i<=to.count;i++) {
            int o=i*STRIDE;
            float brightness=1;
            if(inner && body!=null) {
                int start=(i/4)*4;
                double x=0,y=0,z=0;
                for(int j=start;j<Math.min(start+4,to.count+1);j++) {
                    int v=j*STRIDE;
                    x+=p[v]*b+q[v]*a;y+=p[v+1]*b+q[v+1]*a;z+=p[v+2]*b+q[v+2]*a;
                }
                var at=new net.minecraft.world.phys.Vec3(x/4,y/4,z/4);
                var offset=at.subtract(body.axis());
                double[] row=silhouette.row(at.y-body.ground());
                double edge=Math.abs(offset.dot(body.right())-row[0])-row[1];
                brightness=(float)DantianFlowMath.edgeRim(offset.dot(body.near()),edge,body.scale());
                if(brightness<=0)continue;
            }
            out.addVertex(pose.pose(),p[o]*b+q[o]*a,p[o+1]*b+q[o+1]*a,p[o+2]*b+q[o+2]*a)
                    .setColor((p[o+3]*b+q[o+3]*a)*brightness,(p[o+4]*b+q[o+4]*a)*brightness,(p[o+5]*b+q[o+5]*a)*brightness,p[o+6]*b+q[o+6]*a)
                    .setUv(q[o+7],q[o+8])
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(LightTexture.FULL_BRIGHT)
                    .setNormal(pose,q[o+9],q[o+10],q[o+11]);
        }
    }
}
