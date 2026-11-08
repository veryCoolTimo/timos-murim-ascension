package io.github.verycooltimo.murim.client.vfx.preset;

import io.github.verycooltimo.murim.client.vfx.MurimRenderTypes;
import io.github.verycooltimo.murim.client.vfx.VfxDraw;
import io.github.verycooltimo.murim.client.vfx.bt.BtCtx;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Six columns, red above black, alpha .5. Same world stage/depth/buffer path as the elements.
 * API: VfxDraw.vertex, SpeedLines POSITION_COLOR, MurimRenderTypes.solid vanilla white texture.
 */
final class BenchAlphaTest {
    private static int loggedSecond=-1;
    // Exact pre-fix eyes + opaque centre texel control, beside the fog-independent body.
    private static final RenderType LEGACY = RenderType.create("murim:bench_legacy_eyes",
            com.mojang.blaze3d.vertex.DefaultVertexFormat.NEW_ENTITY,
            com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,4096,
            RenderType.CompositeState.builder()
                    .setShaderState(net.minecraft.client.renderer.RenderStateShard.RENDERTYPE_EYES_SHADER)
                    .setTextureState(new net.minecraft.client.renderer.RenderStateShard.TextureStateShard(
                            ResourceLocation.fromNamespaceAndPath("murim","textures/vfx/air_band.png"),false,false))
                    .setTransparencyState(net.minecraft.client.renderer.RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(net.minecraft.client.renderer.RenderStateShard.NO_CULL)
                    .setWriteMaskState(net.minecraft.client.renderer.RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));
    static void render(BtCtx c) {
        int second=(int)c.labSeconds();
        if(second!=loggedSecond) {
            loggedSecond=second;
            // API: vanilla 1.21.1 client mappings, RenderSystem#getShaderFogStart/End.
            io.github.verycooltimo.murim.MurimMod.LOGGER.info("Bench alpha_test t {} fog {}..{} camera {} quality {}",
                    c.labSeconds(),com.mojang.blaze3d.systems.RenderSystem.getShaderFogStart(),
                    com.mojang.blaze3d.systems.RenderSystem.getShaderFogEnd(),c.camera(),
                    io.github.verycooltimo.murim.client.vfx.bt.BtScenes.quality(c.mc()));
        }
        RenderType[] types={MurimRenderTypes.btEssenceBody(),MurimRenderTypes.presetBody(),
                MurimRenderTypes.presetLight(),MurimRenderTypes.ribbon(),
                RenderType.entityTranslucent(ResourceLocation.withDefaultNamespace("textures/misc/white.png")),
                MurimRenderTypes.alphaControl()};
        // Lift above the horizon and toward the camera. No faceClear/silhouette masking in a blend control.
        Vec3 centre=new Vec3(c.body().axis().x,c.body().ground()+1.37,c.body().axis().z)
                .add(c.body().near().scale(.45));
        for(int col=0;col<types.length;col++)for(int row=0;row<2;row++) {
            Vec3 at=centre.add(c.body().right().scale((col-2.5)*.25)).add(0,-row*.17,0);
            quad(c,types[col],at,.07F,row==0?1:0,col==5,.5F,.5F);
            // Small patches below each pair expose the actual textured preset dark type and fire atlas.
            if(col==0)quad(c,LEGACY,at.add(0,-.095,0),.02F,row==0?1:0,false,.5F,.5F);
            if(col==1)quad(c,MurimRenderTypes.airBand(),at.add(0,-.095,0),.02F,row==0?1:0,false,.5F,.5F);
            if(col==3)quad(c,MurimRenderTypes.btFlame(),at.add(0,-.095,0),.02F,row==0?1:0,false,.125F,.75F);
        }
    }
    private static void quad(BtCtx c,RenderType type,Vec3 at,float half,float red,boolean plain,float u,float v) {
        var buffer=c.buffers().getBuffer(type);
        try {
            Vec3 right=c.body().right().scale(half),up=new Vec3(0,half,0);
            for(Vec3 p:new Vec3[]{at.subtract(right).subtract(up),at.subtract(right).add(up),at.add(right).add(up),at.add(right).subtract(up)}) {
                if(plain)buffer.addVertex(c.pose().pose(),(float)p.x,(float)p.y,(float)p.z).setColor(red,0,0,.5F);
                else VfxDraw.vertex(buffer,c.pose(),p,c.body().near(),u,v,.5F,red,0,0);
            }
        } finally { c.buffers().endBatch(type); }
    }
    private BenchAlphaTest() {}
}
