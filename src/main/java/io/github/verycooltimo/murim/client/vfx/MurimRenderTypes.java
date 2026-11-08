package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.GameRenderer;
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

    private static final ResourceLocation ESSENCE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/essence.png");

    private static final ResourceLocation RIBBON_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/ribbon.png");
    private static final ResourceLocation INK_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/ink_stroke.png");
    /** Сплошная полоса с тонким сглаживанием краёв: чёткие ленты воздуха без мягкого гаусса ribbon.png. */
    private static final ResourceLocation AIR_BAND_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/air_band.png");
    private static final ResourceLocation MOTE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/mote.png");

    private static final ResourceLocation SPLASH_LIGHT_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/splash_light.png");

    private static final ResourceLocation SPLASH_DARK_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/splash_dark.png");

    private static final ResourceLocation SPLASH_BLACK_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/splash_black.png");

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

    /** Широкая мягкая лента: мягкая поперёк, ровная вдоль — без «бусин» на стыках отрезков. */
    private static final RenderType RIBBON = additive("ribbon", RIBBON_TEXTURE);
    /** Широкая светящаяся полоса с чёткими краями (air_band.png: сплошная, края сглажены на один пиксель). */
    private static final RenderType BAND = additive("band", AIR_BAND_TEXTURE);

    /**
     * Ленты воздуха с альфа-смешением: полупрозрачная поверхность и светлая кромка остаются
     * различимы, а на светлом фоне форма не пропадает, как у чистого additive
     * (docs/03-vfx/11-what-looks-good.md, Ц4).
     */
    private static final RenderType AIR_BAND = flatTranslucent("air_band", AIR_BAND_TEXTURE);

    /** Примеси, выходящие из тела при прорыве: тёмные, затемняют, а не светятся. */
    private static final RenderType IMPURITY = flatTranslucent("impurity", MOTE_TEXTURE);

    /**
     * Тёмное поверх тела без проверки глубины: капли пота и трещины лежат на коже, а точки
     * костей, от которых они считаются, — внутри модели, и тело закрывало их целиком
     * (кадры Mac 01.10: на настоящем GPU капель не было).
     */
    private static final RenderType IMPURITY_SKIN = RenderType.create(MurimMod.MODID + ":impurity_skin",
            DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 4096,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionTexColorShader))
                    .setTextureState(new RenderStateShard.TextureStateShard(MOTE_TEXTURE, true, false))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                    .setOverlayState(RenderStateShard.NO_OVERLAY)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));

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

    /**
     * Дуга-эссенция: мягкая светящаяся лента без жёсткой кромки.
     *
     * <p>Текстура белая, цвет задаёт вершина. Поперёк ленты два гаусса разного масштаба:
     * узкое ядро и широкий спад, поэтому край читается размытым, а не отрезанным.
     * У {@code strand.png} профиль в восемь пикселей и зелёный оттенок в самой текстуре,
     * отсюда и «линии» вместо эссенции.
     */
    private static final RenderType ESSENCE = additive("essence", ESSENCE_TEXTURE);

    /**
     * Мягкая круглая точка без лучей. У {@code impact_core.png} лучи вспышки, и белые
     * точки сбора на кадрах читались колючими звёздочками.
     */
    private static final RenderType MOTE = additive("mote", MOTE_TEXTURE);

    /** Жилы по телу: рисуются поверх модели, см. {@link #overlayGlow}. */
    private static final RenderType BT_BODY_GLOW = overlayGlow("bt_body_glow", MOTE_TEXTURE);
    public static RenderType btBodyGlow() { return BT_BODY_GLOW; }

    private static final RenderType BODY_GLOW = overlayGlow("body_glow", STRAND_TEXTURE);

    // Filming feedback 25.10: continuous white textures preserve pill colours and line width.
    private static final RenderType FLOW_LINE = additive("flow_line", AIR_BAND_TEXTURE);
    private static final RenderType FLOW_MOTE = additive("flow_mote", MOTE_TEXTURE);

    public static RenderType flowLine() { return FLOW_LINE; }
    public static RenderType flowMote() { return FLOW_MOTE; }

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
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .setOverlayState(RenderStateShard.NO_OVERLAY)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));

    public static RenderType strand() {
        return STRAND;
    }

    public static RenderType essence() {
        return ESSENCE;
    }

    public static RenderType mote() {
        return MOTE;
    }

    public static RenderType bodyGlow() {
        return BODY_GLOW;
    }

    /** Жилы поглощения пилюли (docs/design/19b): ровная вдоль лента поверх тела, без «бусин» strand.png. */
    private static final RenderType VEIN_RIBBON = overlayGlow("vein_ribbon", RIBBON_TEXTURE);

    public static RenderType veinRibbon() {
        return VEIN_RIBBON;
    }

    public static RenderType drip() {
        return DRIP;
    }

    /**
     * Слои флипбука всплеска ({@link FlipbookSplash}): кадры уже окрашены, цвет вершины белый.
     *
     * <p>Светлый слой — аддитивный: он светится. Тёмный и чёрный — обычная полупрозрачность
     * через position_tex_color: без лайтмапа, нормалей и затухания alpha от fog в eyes.
     * Формат соответствует шейдеру (как GuiShapes); вершинный цвет и маска текстуры сохраняются.
     */
    private static final RenderType SPLASH_LIGHT = additive("splash_light", SPLASH_LIGHT_TEXTURE);
    private static final RenderType SPLASH_DARK = flatTranslucent("splash_dark", SPLASH_DARK_TEXTURE);
    private static final RenderType SPLASH_BLACK = flatTranslucent("splash_black", SPLASH_BLACK_TEXTURE);

    private static RenderType flatTranslucent(String name, ResourceLocation texture) {
        return RenderType.create(
                MurimMod.MODID + ":" + name,
                DefaultVertexFormat.POSITION_TEX_COLOR,
                VertexFormat.Mode.QUADS,
                4096,
                RenderType.CompositeState.builder()
                        .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionTexColorShader))
                        .setTextureState(new RenderStateShard.TextureStateShard(texture, true, false))
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                        .setOverlayState(RenderStateShard.NO_OVERLAY)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .createCompositeState(false));
    }

    public static RenderType splashLight() {
        return SPLASH_LIGHT;
    }

    public static RenderType splashDark() {
        return SPLASH_DARK;
    }

    public static RenderType splashBlack() {
        return SPLASH_BLACK;
    }

    public static RenderType shard() {
        return SHARD;
    }

    private static final ResourceLocation MIST_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/mist.png");

    /**
     * Mist puffs over Mount Hua ({@link HuaMist}): the text shader (texture × vertex colour ×
     * lightmap, no normal shading — with the entity shader the puffs came out black on the stand),
     * plain translucency, colour only — no depth write, so overlapping puffs do not cut each other.
     */
    private static final RenderType MIST = RenderType.create(
            MurimMod.MODID + ":mist",
            DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
            VertexFormat.Mode.QUADS,
            4096,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_TEXT_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(MIST_TEXTURE, false, false))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));

    public static RenderType mist() {
        return MIST;
    }

    public static RenderType bladeTrail() {
        return BLADE_TRAIL;
    }

    public static RenderType bladeCrescent() {
        return BLADE_CRESCENT;
    }

    public static RenderType impuritySkin() {
        return RenderType.entityTranslucent(MOTE_TEXTURE);
    }

    public static RenderType impurity() {
        // Ванильный полупрозрачный тип сущностей: свой тип на шейдере «глаз» с обычным
        // смешиванием не давал тёмного на кадрах Mac 01.10 (пятна вызывались — лог, — но не
        // рисовались). API: reference/minecraft-src/.../RenderType.java#entityTranslucent
        return RenderType.entityTranslucent(MOTE_TEXTURE);
    }

    /** Мазок туши для чёрного пламени ауры: тёмный, затемняет — тип как у {@link #impurity()}. */
    /**
     * Тёмная сплошная лента (чёрные волны клинка ци высшей ступени): шейдер полупрозрачных
     * сущностей — тёмное на нём видно и на llvmpipe, в отличие от аддитивных слоёв.
     */
    public static RenderType darkBand() {
        return RenderType.entityTranslucent(AIR_BAND_TEXTURE);
    }

    public static RenderType ink() {
        return RenderType.entityTranslucent(INK_TEXTURE);
    }

    /**
     * Крупные маски ауры (docs/design/19 §3ж, разбор astra 01.10): обычное смешивание, без записи
     * глубины — огромные полупрозрачные карточки иначе закрывали бы всё, что рисуется после.
     * Шейдер полупрозрачных сущностей, как у {@link #shard()}: на нём тёмное видно и на llvmpipe.
     */
    private static RenderType auraLayer(String name, String texture) {
        return RenderType.create(MurimMod.MODID + ":" + name, DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 4096,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                        .setTextureState(new RenderStateShard.TextureStateShard(
                                ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, texture), true, false))
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .setOverlayState(RenderStateShard.NO_OVERLAY)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .createCompositeState(false));
    }

    // Self-lit on purpose: the lit translucent aura layer drew these cel flames BLACK at night (filmed 31.10); the emissive entity type keeps
    // the blue and violet of the atlas.
    private static final RenderType BT_FLAME = RenderType.entityTranslucentEmissive(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/bt_flame.png"));
    public static RenderType btFlame() { return BT_FLAME; }

    // Procedural white-centre material: position_color preserves low vertex alpha without eyes fog fade.
    // Source: vanilla 1.21.1 assets/minecraft/shaders/core/{position_color,rendertype_eyes}.fsh.
    private static final RenderType BT_ESSENCE_BODY = RenderType.create(
            MurimMod.MODID + ":bt_essence_body", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 4096,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionColorShader))
                    .setTextureState(new RenderStateShard.TextureStateShard(AIR_BAND_TEXTURE, false, false))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                    .setOverlayState(RenderStateShard.NO_OVERLAY)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));
    public static RenderType btEssenceBody() { return BT_ESSENCE_BODY; }


    private static final RenderType AURA_STROKES = auraLayer("aura_strokes", "textures/vfx/aura_strokes.png");

    // Rainfall pilot: self-lit alpha-over, no depth writes; preserve painted wine shadows at night.
    // API: read-only minecraft-src/net/minecraft/client/renderer/{RenderType,RenderStateShard}.java;
    // same flatTranslucent material used by the existing splash layers.
    private static final RenderType RAIN_PETALS = flatTranslucent("rain_petals",
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/hua/petals_p24.png"));
    private static final RenderType RAIN_AIR = flatTranslucent("rain_air",
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/hua/air_p24.png"));
    public static RenderType rainPetals() { return RAIN_PETALS; }
    public static RenderType rainAir() { return RAIN_AIR; }

    /** Лепестки сливы, атлас 2×2. */
    public static RenderType plumPetals() {
        return RenderType.entityTranslucent(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/plum_petals.png"));
    }

    /** Клубы пыли в манере манхвы: белые с контуром тушью, атлас 4×4. */
    /** Клубы дыма манхвы (4×4): чёткий край, три тона серого, тёмная кайма — без размытия. */
    public static RenderType smokeCel() {
        return RenderType.entityTranslucent(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/smoke_cel.png"));
    }

    public static RenderType dustPuffs() {
        return RenderType.entityTranslucent(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/dust_puffs.png"));
    }

    /** Атлас из восьми мазков туши: экранные штрихи давления и широкие потоки. */
    public static RenderType auraStrokes() {
        return AURA_STROKES;
    }

    /** Сплошные тёмные квадраты: обломки и угли. */
    public static RenderType solid() {
        return RenderType.entityTranslucent(ResourceLocation.withDefaultNamespace("textures/misc/white.png"));
    }

    /** Crisp fullbright pale blade path/edge, normal alpha blending and normal depth test. */
    private static final RenderType SWORD_FINISHER = RenderType.create(
            MurimMod.MODID + ":sword_finisher", DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 4096,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionTexColorShader))
                    .setTextureState(new RenderStateShard.TextureStateShard(
                            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/sword_finisher.png"), false, false))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                    .setOverlayState(RenderStateShard.NO_OVERLAY)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));

    public static RenderType swordFinisher() { return SWORD_FINISHER; }

    public static RenderType airBand() {
        return AIR_BAND;
    }

    /** The ribbon texture, for the glow twin of {@link #ribbon()} (bt/BtGlow). */
    public static ResourceLocation ribbonTexture() {
        return RIBBON_TEXTURE;
    }

    public static RenderType ribbon() {
        return RIBBON;
    }

    /** Аддитивная широкая лента с чёткими краями: полосы подлёта и толстые потоки пилюль. */
    public static RenderType band() {
        return BAND;
    }

    public static RenderType impactCore() {
        return IMPACT_CORE;
    }

    /** Procedural vertex colours need neither a texture nor the eyes shader's alpha fog fade.
     * API: SpeedLines uses GameRenderer#getPositionColorShader; vanilla 1.21.1
     * assets/minecraft/shaders/core/{position_color,rendertype_eyes}.fsh.
     * NEW_ENTITY is accepted too: the shader consumes Position/Color and ignores extra attributes.
     */
    private static RenderType procedural(String name, boolean additive, boolean plain) {
        return procedural(name, additive, plain, false);
    }
    private static RenderType procedural(String name, boolean additive, boolean plain, boolean through) {
        return RenderType.create(MurimMod.MODID + ":" + name,
                plain ? DefaultVertexFormat.POSITION_COLOR : DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS, 4096, RenderType.CompositeState.builder()
                        .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionColorShader))
                        .setDepthTestState(through ? RenderStateShard.NO_DEPTH_TEST : RenderStateShard.LEQUAL_DEPTH_TEST)
                        .setTransparencyState(additive ? RenderStateShard.LIGHTNING_TRANSPARENCY : RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .createCompositeState(false));
    }
    private static final RenderType BAMBOO_LEAVES = procedural("bamboo_leaves", false, false);
    private static final RenderType BAMBOO_DEBRIS = auraLayer("bamboo_debris", "textures/vfx/air_band.png");
    public static RenderType bambooDebris() { return BAMBOO_DEBRIS; }
    public static RenderType bambooLeaves() { return BAMBOO_LEAVES; }
    private static final RenderType PRESET_BODY = procedural("preset_body", false, false);
    private static final RenderType PRESET_LIGHT = procedural("preset_light", true, false);
    private static final RenderType ALPHA_CONTROL = procedural("alpha_control", false, true);
    private static final RenderType DANTIAN_INNER = procedural("dantian_inner", false, false, true);
    // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/client/renderer/RenderStateShard.java
    // LIGHTNING_TRANSPARENCY = SRC_ALPHA/ONE; NO_DEPTH_TEST; COLOR_WRITE = (true, false).
    // API: same directory RenderType.java#CompositeState.CompositeStateBuilder;
    // GameRenderer.java#getPositionColorShader. The existing procedural helper sets NO_CULL.
    private static final RenderType DANTIAN_GLOW = procedural("dantian_glow", true, false, true);
    public static RenderType dantianCore() { return DANTIAN_GLOW; }
    public static RenderType dantianInner() { return DANTIAN_INNER; }
    public static RenderType presetBody() { return PRESET_BODY; }
    public static RenderType presetLight() { return PRESET_LIGHT; }
    public static RenderType alphaControl() { return ALPHA_CONTROL; }

    private MurimRenderTypes() {
    }
}
