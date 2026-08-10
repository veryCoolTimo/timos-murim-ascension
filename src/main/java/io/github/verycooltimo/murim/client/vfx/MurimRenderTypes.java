package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Типы рендера эффектов мода. Все — в одном классе, а не по месту вызова: {@link RenderType}
 * иммутабелен и кешируется, а разбросанные определения дают дубликаты состояний GPU.
 *
 * <p>Состав слоя ленты выбран так, чтобы эффект пережил Iris без собственного GLSL:
 * шейдер {@code RENDERTYPE_ENERGY_SWIRL_SHADER} с форматом {@code NEW_ENTITY} и аддитивной
 * прозрачностью молнии попадает у шейдерпаков в {@code gbuffers_spidereyes}, где по умолчанию
 * стоит аддитивное смешивание. Свой GLSL здесь не пишется сознательно: Iris маскирует запись
 * цвета и глубины у неизвестных ему программ. Обоснование — docs/design/research/r2-vfx-stack.md.
 *
 * <p>API проверены javap по jar 1.21.1: {@code RenderStateShard.RENDERTYPE_ENERGY_SWIRL_SHADER},
 * {@code RenderStateShard.LIGHTNING_TRANSPARENCY}, {@code DefaultVertexFormat.NEW_ENTITY},
 * {@code RenderType.create(String, VertexFormat, Mode, int, CompositeState)} — все public.
 */
public final class MurimRenderTypes {

    private static final ResourceLocation TRAIL_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/blade_trail.png");

    private static final ResourceLocation CRESCENT_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/blade_crescent.png");

    private static final ResourceLocation CORE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/impact_core.png");

    /**
     * Лента следа клинка.
     *
     * <p>Без записи в буфер глубины ({@code COLOR_WRITE}) и без отсечения задних граней:
     * лента двусторонняя и не должна закрывать сама себя при развороте дуги.
     */
    private static final RenderType BLADE_TRAIL = RenderType.create(
            MurimMod.MODID + ":blade_trail",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS,
            // Ленте нужно 28 квадов по 4 вершины формата NEW_ENTITY (36 байт) — около 4 КБ.
            4096,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_EYES_SHADER)
                    .setTextureState(// blur = true: текстура 64x16 растягивается на дугу длиной около двух блоков,
                            // при ближайшем соседе кромка идёт лесенкой
                            new RenderStateShard.TextureStateShard(TRAIL_TEXTURE, true, false))
                    .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                    .setOverlayState(RenderStateShard.NO_OVERLAY)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));

    /**
     * Серп удара — вторым слоем поверх ленты.
     *
     * <p>Отдельный тип, а не тот же самый с другой текстурой: {@link RenderType} иммутабелен
     * и кеширует состояние GPU вместе с привязкой текстуры, подменить её на лету нельзя.
     */
    private static final RenderType BLADE_CRESCENT = additive("blade_crescent", CRESCENT_TEXTURE);

    /** Вспышка ядра — третьим слоем в точке контакта. */
    private static final RenderType IMPACT_CORE = additive("impact_core", CORE_TEXTURE);

    private static RenderType additive(String name, ResourceLocation texture) {
        return RenderType.create(
                MurimMod.MODID + ":" + name,
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                4096,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.RENDERTYPE_EYES_SHADER)
                        .setTextureState(new RenderStateShard.TextureStateShard(texture, true, false))
                        .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                        .setOverlayState(RenderStateShard.NO_OVERLAY)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .createCompositeState(false));
    }

    public static RenderType bladeTrail() {
        return BLADE_TRAIL;
    }

    public static RenderType bladeCrescent() {
        return BLADE_CRESCENT;
    }

    public static RenderType impactCore() {
        return IMPACT_CORE;
    }

    private MurimRenderTypes() {
    }
}
