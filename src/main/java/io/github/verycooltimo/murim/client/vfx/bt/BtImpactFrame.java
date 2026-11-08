package io.github.verycooltimo.murim.client.vfx.bt;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import io.github.verycooltimo.murim.client.ClientConfig;
import io.github.verycooltimo.murim.client.ClientMeditationState;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import java.util.ArrayList;
import java.util.List;

/** Two-tick graphic blow frame, rendered before meditation HUD text. No post-chain shader. */
public final class BtImpactFrame {
    private static List<float[]> silhouette=List.of();
    private static List<Vec3> modelVertices=List.of();
    private static List<float[]> modelQuads=List.of();
    /** Capture actual posed model faces, including visible skin layers, without inflation or blur.
     * ModelPart.renderToBuffer and VertexConsumer follow MeditationEcho/ExecVfx's 1.21.1 API.
     */
    public static void captureModel(net.minecraft.client.model.PlayerModel<?> model,PoseStack pose) {
        List<Vec3> points=new ArrayList<>();
        Vec3 camera=net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        VertexConsumer collector=new VertexConsumer() {
            public VertexConsumer addVertex(float x,float y,float z){points.add(new Vec3(x,y,z).add(camera));return this;}
            public VertexConsumer setColor(int red,int green,int blue,int alpha){return this;}
            public VertexConsumer setUv(float u,float v){return this;}
            public VertexConsumer setUv1(int u,int v){return this;}
            public VertexConsumer setUv2(int u,int v){return this;}
            public VertexConsumer setNormal(float x,float y,float z){return this;}
        };
        model.renderToBuffer(pose,collector,0x00f000f0,net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,0xffffffff);
        modelVertices=List.copyOf(points);
    }
    private static final BtImpactHold hold=new BtImpactHold();
    private static int impactTarget;
    private static float[] dantian,coreEdge,haloEdge;
    public static void trigger(int target) {
        if(impactTarget==target)return; // Packet hand-off must not restart or clear the scene-end latch.
        reset();
        if(target==3||target==4){impactTarget=target;hold.trigger(target,System.nanoTime());}
    }
    public static void reset() { hold.reset();impactTarget=0; }
    private static List<List<float[]>> combatSilhouettes=List.of();
    private static boolean combatCapture;
    public static void triggerCombat() {
        if(!ClientConfig.SCREEN_FLASHES.get())return;
        combatSilhouettes=List.of();combatCapture=true;
    }
    /** Freeze the giant's posed outline before its scale resets; keep it throughout the mask. */
    public static void captureCombat(java.util.Map<Integer,BtBody> bodies,Vec3 camera,Matrix4f view,Matrix4f projection) {
        if(!combatCapture||bodies.isEmpty())return;
        var snapshot=projectCombat(bodies,camera,view,projection);
        if(snapshot.isEmpty())return;
        combatSilhouettes=List.copyOf(snapshot.values());combatCapture=false;
    }
    /** Shared Fear/Counter snapshot, retaining identity for participant regression checks. */
    static java.util.Map<Integer,List<float[]>> projectCombat(java.util.Map<Integer,BtBody> bodies,
                                                             Vec3 camera,Matrix4f view,Matrix4f projection) {
        java.util.Map<Integer,List<float[]>> polygons=new java.util.LinkedHashMap<>();
        bodies.forEach((id,body)-> {
            List<Vec3> outline=new ArrayList<>();
            for(var q:body.silhouette().outline(.03)) outline.add(body.silhouettePoint(q,0));
            List<float[]> points=BtProjection.projectPolygon(outline,camera,view,projection);
            if(!points.isEmpty())polygons.put(id,points);
        });
        return polygons;
    }
    /** Six Forms mask uses client ticks, independent of render rate and wall time. */
    public static boolean renderCombat(GuiGraphics g, int age) {
        if(!ClientConfig.SCREEN_FLASHES.get() || age < 0
                || age >= io.github.verycooltimo.murim.technique.SixRules.IMPACT_FRAME_TICKS)return false;
        draw(g,4,1,combatSilhouettes,null,null,null);
        return !combatSilhouettes.isEmpty();
    }
    private BtImpactFrame() {}
    public static void capture(BtBody body,Vec3 camera,Matrix4f view,Matrix4f projection) {
        if(body==null)return; // Preserve the last posed polygon through the hand-off.
        List<float[]> projected=new ArrayList<>();
        for(Vec3 point:modelVertices) {
            float[] screen=BtProjection.project(point,camera,view,projection);
            if(screen==null){projected.clear();break;}
            projected.add(screen);
        }
        modelQuads=List.copyOf(projected);
        List<float[]> p=new ArrayList<>();
        for(var q:body.silhouette().outline(.03)) {
            float[] s=BtProjection.project(body.silhouettePoint(q,0),camera,view,projection);
            if(s==null)return;p.add(s);
        }
        // lab-bt_peak.js btpImpactFrame: «his face burning white» — the focus is on the face (402, 212), not the chest.
        Vec3 centre=body.bone(io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer.Bone.HEAD);
        float[] d=BtProjection.project(centre,camera,view,projection);
        float[] core=BtProjection.project(centre.add(body.right().scale(.08)),camera,view,projection);
        float[] halo=BtProjection.project(centre.add(body.right().scale(.175)),camera,view,projection);
        if(d==null||core==null||halo==null)return;
        dantian=d;coreEdge=core;haloEdge=halo;
        silhouette=List.copyOf(p);
    }
    /** Creation's short impact (three frames at 60 FPS), using the current seated polygon and the shared graphic. */
    public static void renderCreation(GuiGraphics g,double seconds) {
        double age=seconds-io.github.verycooltimo.murim.client.vfx.preset.DantianFlowMath.BURST_START;
        BtSpeedLines.renderCreation(g,age);
        if(age>=0&&age<.05&&ClientConfig.SCREEN_FLASHES.get()&&!silhouette.isEmpty())
            draw(g,1,1,List.of(silhouette),null,null,null);
    }

    public static void render(GuiGraphics g,float partial) {
        if(io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.benchActive())return;
        BtSpeedLines.render(g,partial);
        if(!ClientConfig.SCREEN_FLASHES.get()){reset();return;}
        if(silhouette.isEmpty())return;
        int target=ClientMeditationState.peakSceneAge()>=0?4:impactTarget;
        var frame=target==4
                ?new BtImpactHold.Frame(BtImpactHold.peakAlpha(BtClock.labSeconds(4,partial)),true,true,true)
                :hold.frame(System.nanoTime(),!silhouette.isEmpty());
        float alpha=frame.alpha();
        // The blow tick and its successor always show the full first frame, even below 20 FPS.
        int age=ClientMeditationState.rankUpAge();
        if(target==3&&ClientMeditationState.rankUpRank()==target&&age>=0&&age<2)alpha=1;
        if(alpha<=0||!frame.gradient()||!frame.silhouette())return;
        draw(g,target,alpha,List.of(silhouette),dantian,coreEdge,haloEdge);
    }
    // Shared breakthrough graphic: white field, dark posed silhouette and radial speed lines.
    private static void draw(GuiGraphics g,int target,float alpha,List<List<float[]>> silhouettes,
                             float[] dantian,float[] coreEdge,float[] haloEdge) {
        // Peak: the lab's blaze, white in the middle to deep blue at the edges round the upper body (lab (400,300), 560 of 800 px);
        // the pale set it had read as a white frame next to the lab (31.10).
        int[] colours=target==4||target==1?new int[]{0xffffff,0xe2f7ff,0x6da8ee,0x203c8a}:new int[]{0xffffff,0xefe6ff,0x8f6fe0,0x26145e};
        int w=g.guiWidth(),h=g.guiHeight();float cx=w*.5F,cy=target==4?h*.44F:h*.59F,r=Math.max(w,h)*(target==4?.7F:.8F);
        // Peak speed lines have already written GUI depth at z=0. This full-screen overlay
        // must ignore that depth, or its gradient and silhouette fail the same-depth test.
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder b=Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES,DefaultVertexFormat.POSITION_COLOR);
        Matrix4f m=g.pose().last().pose();double[] stops={0,.3,.75,1};
        for(int ring=0;ring<3;ring++)for(int i=0;i<96;i++) {
            double a=i*Math.PI*2/96,z=(i+1)*Math.PI*2/96;
            float x0=cx+(float)Math.cos(a)*r*(float)stops[ring],y0=cy+(float)Math.sin(a)*r*(float)stops[ring];
            float x1=cx+(float)Math.cos(z)*r*(float)stops[ring],y1=cy+(float)Math.sin(z)*r*(float)stops[ring];
            float x2=cx+(float)Math.cos(z)*r*(float)stops[ring+1],y2=cy+(float)Math.sin(z)*r*(float)stops[ring+1];
            float x3=cx+(float)Math.cos(a)*r*(float)stops[ring+1],y3=cy+(float)Math.sin(a)*r*(float)stops[ring+1];
            vertex(b,m,x0,y0,colours[ring],alpha);vertex(b,m,x1,y1,colours[ring],alpha);vertex(b,m,x2,y2,colours[ring+1],alpha);
            vertex(b,m,x0,y0,colours[ring],alpha);vertex(b,m,x2,y2,colours[ring+1],alpha);vertex(b,m,x3,y3,colours[ring+1],alpha);
        }
        for(int i=0;i<64;i++) {
            double a=(i+.6*BtMath.random(i*53+9701))/64*Math.PI*2;
            float dx=(float)Math.cos(a),dy=(float)Math.sin(a),inner=(float)(.3+.15*BtMath.random(i*53+9702))*h;
            float mid=inner+h*.18F,edge=w+h,hw=(float)(1+4*BtMath.random(i*53+9703))*h/680F;
            int ink=target==3?0x221252:target==4?0x14286e:0x10213a;
            vertex(b,m,cx+dx*inner,cy+dy*inner,ink,alpha*.7F);vertex(b,m,cx+dx*mid-dy*hw,cy+dy*mid+dx*hw,ink,alpha*.7F);vertex(b,m,cx+dx*edge,cy+dy*edge,ink,alpha*.7F);
            vertex(b,m,cx+dx*inner,cy+dy*inner,ink,alpha*.7F);vertex(b,m,cx+dx*edge,cy+dy*edge,ink,alpha*.7F);vertex(b,m,cx+dx*mid+dy*hw,cy+dy*mid-dx*hw,ink,alpha*.7F);
        }
        if(target==4) {
            // Opaque union of projected model quads preserves limb gaps and square head corners.
            for(int i=0;i+3<modelQuads.size();i+=4)for(int index:new int[]{0,1,2,0,2,3}) {
                float[] v=modelQuads.get(i+index);vertex(b,m,v[0]*w,v[1]*h,0x05060c,1);
            }
        } else {
        // Ear clipping supports the concave neck/shoulder outline; a fan would fill across the neck.
        for(List<float[]> silhouette:silhouettes) {
            List<float[]> polygon=new ArrayList<>(silhouette);if(!polygon.isEmpty())polygon.removeLast();
            double area=0;for(int i=0;i<polygon.size();i++){var a=polygon.get(i);var z=polygon.get((i+1)%polygon.size());area+=a[0]*z[1]-a[1]*z[0];}
            double sign=Math.signum(area);
            while(polygon.size()>2) {
                boolean cut=false;int n=polygon.size();
                for(int i=0;i<n;i++) {
                    var a=polygon.get((i+n-1)%n);var z=polygon.get(i);var c=polygon.get((i+1)%n);
                    if(cross(a,z,c)*sign<=0)continue;boolean inside=false;
                    for(int j=0;j<n;j++){if(j==i||j==(i+n-1)%n||j==(i+1)%n)continue;var p=polygon.get(j);if(cross(a,z,p)*sign>=0&&cross(z,c,p)*sign>=0&&cross(c,a,p)*sign>=0){inside=true;break;}}
                    if(inside)continue;
                    vertex(b,m,a[0]*w,a[1]*h,0x05060c,1);vertex(b,m,z[0]*w,z[1]*h,0x05060c,1);vertex(b,m,c[0]*w,c[1]*h,0x05060c,1);
                    polygon.remove(i);cut=true;break;
                }
                if(!cut)break;
            }
        }
        }
        // The white focus on the face, as the lab's (402, 212): .16-block core, .35-block halo.
        if(target==4&&dantian!=null) {
            float dx=dantian[0]*w,dy=dantian[1]*h;
            float coreRadius=(float)Math.hypot((coreEdge[0]-dantian[0])*w,(coreEdge[1]-dantian[1])*h);
            float haloRadius=(float)Math.hypot((haloEdge[0]-dantian[0])*w,(haloEdge[1]-dantian[1])*h);
            float[] radii={0,coreRadius*.45F,coreRadius,haloRadius};
            float[] opacity={1,1,.45F,0};
            for(int ring=0;ring<3;ring++)for(int i=0;i<64;i++) {
                double a=i*Math.PI*2/64,z=(i+1)*Math.PI*2/64;
                float x0=dx+(float)Math.cos(a)*radii[ring],y0=dy+(float)Math.sin(a)*radii[ring];
                float x1=dx+(float)Math.cos(z)*radii[ring],y1=dy+(float)Math.sin(z)*radii[ring];
                float x2=dx+(float)Math.cos(z)*radii[ring+1],y2=dy+(float)Math.sin(z)*radii[ring+1];
                float x3=dx+(float)Math.cos(a)*radii[ring+1],y3=dy+(float)Math.sin(a)*radii[ring+1];
                vertex(b,m,x0,y0,0xffffff,opacity[ring]*alpha);vertex(b,m,x1,y1,0xffffff,opacity[ring]*alpha);vertex(b,m,x2,y2,0xffffff,opacity[ring+1]*alpha);
                vertex(b,m,x0,y0,0xffffff,opacity[ring]*alpha);vertex(b,m,x2,y2,0xffffff,opacity[ring+1]*alpha);vertex(b,m,x3,y3,0xffffff,opacity[ring+1]*alpha);
            }
        }
        MeshData mesh=b.build();if(mesh!=null)BufferUploader.drawWithShader(mesh);
        RenderSystem.enableCull();RenderSystem.disableBlend();RenderSystem.enableDepthTest();
    }
    private static double cross(float[] a,float[] b,float[] c){return (b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]);}
    static void vertex(BufferBuilder b,Matrix4f m,float x,float y,int rgb,float a) {
        b.addVertex(m,x,y,0).setColor((rgb>>16&255)/255F,(rgb>>8&255)/255F,(rgb&255)/255F,a);
    }
}
