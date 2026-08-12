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

    private static final ResourceLocation STRAND_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/strand.png");

    private static final ResourceLocation SHARD_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/shard.png");

    private static final ResourceLocation DRIP_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/drip.png");

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

    /**
     * Свечение ПОВЕРХ тела, без проверки глубины.
     *
     * <p>Меридианы идут внутри тела, и с обычной проверкой глубины модель игрока их
     * съедает: измерение показало два видимых пикселя на всю сцену. Жилы должны читаться
     * как свечение, проступающее сквозь кожу, а для этого геометрия внутри модели обязана
     * рисоваться поверх неё.
     *
     * <p>Цена решения: такое свечение видно и сквозь стены. Для церемонии, которая идёт
     * вокруг самого игрока и длится секунды, это приемлемо; для боевых эффектов — нет,
     * поэтому слой отдельный, а не общий.
     */
    private static RenderType overlayGlow(String name, ResourceLocation texture) {
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
                        .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .createCompositeState(false));
    }

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

    /** Тонкая светящаяся прядь. Из пучка таких собирается фактура смазанного движения. */
    private static final RenderType STRAND = additive("strand", STRAND_TEXTURE);

    /** Жилы по телу: рисуются поверх модели, см. {@link #overlayGlow}. */
    private static final RenderType BODY_GLOW = overlayGlow("body_glow", STRAND_TEXTURE);

    /** Стекающая субстанция с каплей. */
    private static final RenderType DRIP = additive("drip", DRIP_TEXTURE);

    /**
     * Тёмные осколки.
     *
     * <p>Единственный НЕаддитивный слой мода, и это принципиально: аддитивное смешивание
     * складывает яркость, поэтому чёрное на нём просто невидимо. Тёмные элементы требуют
     * обычной полупрозрачности, иначе их на экране не будет вовсе.
     */
    private static final RenderType SHARD = RenderType.create(
            MurimMod.MODID + ":shard",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS,
            4096,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(SHARD_TEXTURE, false, false))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                    .setOverlayState(RenderStateShard.NO_OVERLAY)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));

    public static RenderType strand() {
        return STRAND;
    }

    public static RenderType bodyGlow() {
        return BODY_GLOW;
    }

    public static RenderType drip() {
        return DRIP;
    }

    public static RenderType shard() {
        return SHARD;
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
