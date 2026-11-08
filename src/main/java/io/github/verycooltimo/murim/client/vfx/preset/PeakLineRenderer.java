package io.github.verycooltimo.murim.client.vfx.preset;

import io.github.verycooltimo.murim.client.vfx.MurimRenderTypes;
import io.github.verycooltimo.murim.client.vfx.bt.BtCtx;
import net.minecraft.world.phys.Vec3;
import static io.github.verycooltimo.murim.client.vfx.preset.EssenceMath.curve;

/** Shared element renderer, ready for a scene to call after visual acceptance.
 * API: PresetRuntime buffer ownership; BtBody's measured lab-pixel scale.
 */
public final class PeakLineRenderer {
    public static void render(BtCtx c,PeakLineElement element) { render(c,element,true); }
    static void render(BtCtx c,PeakLineElement element,boolean flush) { render(c,element,flush,null); }
    static void render(BtCtx c,PeakLineElement element,boolean flush,com.mojang.blaze3d.vertex.VertexConsumer target) {
        for(var ribbon:element.ribbons(c.labSeconds()))ribbon(c,ribbon,element.orbitRadius(),flush,target);
    }
    static void ribbon(BtCtx c,PeakLineElement.Ribbon ribbon,double radius) { ribbon(c,ribbon,radius,true); }
    /** {@code flush=false}: the caller ends the preset batch once after all its ribbons (one draw call instead of one per ribbon). */
    static void ribbon(BtCtx c,PeakLineElement.Ribbon ribbon,double radius,boolean flush) { ribbon(c,ribbon,radius,flush,null); }
    /** {@code target}: a recorder instead of the scene buffer (PeakEssences builds each tick's mesh once). */
    static void ribbon(BtCtx c,PeakLineElement.Ribbon ribbon,double radius,boolean flush,com.mojang.blaze3d.vertex.VertexConsumer target) {
        double yaw=Math.toRadians(c.mc().player.getYRot());
        Vec3 right=new Vec3(Math.cos(yaw),0,Math.sin(yaw)),forward=new Vec3(-Math.sin(yaw),0,Math.cos(yaw));
        // Source: lab plate floor 585, btpQ origin 540; 360 pixels per block.
        double height=350.0/360,t=c.labSeconds();
        Vec3 feet=new Vec3(c.body().axis().x,c.body().ground()+PeakLineElement.blocksY(0),c.body().axis().z);
            var source=ribbon.layer();
            var params=new java.util.HashMap<>(source.params());
            // The lab's essence material as it is (lab-bt_peak.js ESS: halo 3.2, full width). A scene override of halo 1.8 and width x.85
            // (87d8c4dd9) left narrow neon tracks without the wide soft glow (codex 31.10 next to the lab, the zoomed 22.5 s frame).
            var l=new EssencePreset.Layer(java.util.Map.copyOf(params),source.colors(),source.path(),
                    source.widthProfile(),source.folds(),source.veils(),source.seed());
            var depth=ribbon.depth().stream().map(d->new EssencePreset.Point(d,0)).toList();
            // Table D's flatness is already in the projected path; depth retains R/360.
            // Do not compress every ring to a shared .26 radius around the torso.
            java.util.function.DoubleFunction<Vec3> path=u->{var p=curve(l.path(),u);double d=curve(depth,u).x();
                // The extra spread round the block body gives way in the squeeze before the blow, so the whirl gathers tight as in the
                // lab (codex 31.10: at 24-25 s the port's orbits stayed broad and separate, the flash had no squeeze before it).
                double spread=radius==285?.9:1+.18*io.github.verycooltimo.murim.client.vfx.bt.BtMath.smooth(20,22.5,t)
                        *(1-io.github.verycooltimo.murim.client.vfx.bt.BtPeakMath.press(t));
                double lift=radius==285?0:.18*io.github.verycooltimo.murim.client.vfx.bt.BtMath.smooth(16.5,19.5,t);
                return feet.add(right.scale(p.x()*height*spread)).add(0,p.y()*height+lift,0).add(forward.scale(d*radius/360));};
            var type=MurimRenderTypes.presetBody();
            try {
                new PresetMesh(l,feet,right,forward,c.camera(),height,t-l.p("start"),l.p("exposure"),
                        target!=null?target:c.buffers().getBuffer(type),c.pose(),false,path).draw();
            }finally{if(flush)c.buffers().endBatch(type);}
    }
    private PeakLineRenderer(){}
}
