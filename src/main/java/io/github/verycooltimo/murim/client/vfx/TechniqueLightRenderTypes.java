package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import java.util.HashMap;
import java.util.Map;

/** API: supplied minecraft-src/.../renderer/{RenderType,RenderStateShard}.java, same blend as BtGlow. */
public final class TechniqueLightRenderTypes {
    private TechniqueLightRenderTypes() {}
    private static final RenderStateShard.TransparencyStateShard ADD = new RenderStateShard.TransparencyStateShard(
            "murim_light_add", () -> {
                RenderSystem.enableBlend();
                RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                        GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
            }, () -> { RenderSystem.disableBlend(); RenderSystem.defaultBlendFunc(); });
    // MAIN_TARGET itself is a no-op in 1.21.1. Bind explicitly during Fabulous' particles stage,
    // and return to its particles target for later subscribers (RenderStateShard#PARTICLES_TARGET).
    private static final RenderStateShard.OutputStateShard GROUND_TARGET = new RenderStateShard.OutputStateShard(
            "murim_light_ground_target", () -> Minecraft.getInstance().getMainRenderTarget().bindWrite(false), () -> {
                var mc = Minecraft.getInstance();
                if (Minecraft.useShaderTransparency() && mc.levelRenderer.getParticlesTarget() != null)
                    mc.levelRenderer.getParticlesTarget().bindWrite(false);
                else mc.getMainRenderTarget().bindWrite(false);
            });
    public static final RenderType GROUND = RenderType.create("murim:technique_ground", DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS, 4096, RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionColorShader))
                    .setTransparencyState(ADD).setOutputState(GROUND_TARGET).setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST).setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));
    private static final Map<ResourceLocation, RenderType> BODY = new HashMap<>();
    public static RenderType body(ResourceLocation texture) {
        return BODY.computeIfAbsent(texture, t -> RenderType.create("murim:technique_body_" + t,
                DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 4096, RenderType.CompositeState.builder()
                        .setShaderState(new RenderStateShard.ShaderStateShard(TechniqueBodyLight::shader))
                        .setTextureState(new RenderStateShard.TextureStateShard(t, false, false))
                        .setTransparencyState(ADD).setCullState(RenderStateShard.NO_CULL)
                        .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST).setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .createCompositeState(false)));
    }
}
