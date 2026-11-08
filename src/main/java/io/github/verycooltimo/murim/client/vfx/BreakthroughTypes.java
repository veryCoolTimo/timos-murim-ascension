package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Render types of the rank breakthrough scene that {@link MurimRenderTypes} does not have: the crisp flat band.
 *
 * <p>Same state as the other additive layers (eyes shader, lightning transparency, no light, colour write only, so Iris
 * treats it like them), but the texture is sampled with the nearest neighbour: the band edge stays a hard line instead of
 * the soft gauss of {@code ribbon.png}. The author's refs and mockups have crisp edges, never blur
 * (feedback «technique layers»: «crisp manhwa style, no blur»).
 */
final class BreakthroughTypes {

    private static final ResourceLocation BAND_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/band_flat.png");

    /** A flat band: full across the middle, one texel of edge. Colour comes from the vertex; width and length from the quad. */
    private static final RenderType BAND = RenderType.create(
            MurimMod.MODID + ":breakthrough_band",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS,
            4096,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_EYES_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(BAND_TEXTURE, false, false))
                    .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                    .setOverlayState(RenderStateShard.NO_OVERLAY)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));

    static RenderType band() {
        return BAND;
    }

    private BreakthroughTypes() {
    }
}
