package io.github.verycooltimo.murim.client.bandit;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.bedrock.BedrockGeo;
import io.github.verycooltimo.murim.entity.Bandit;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Рендер бандитов. Модель и анимации — Bedrock-файлы в {@code assets/murim/bedrock/}, текстуры —
 * {@code textures/entity/bandit*.png}. У мечника дао — часть модели (кость {@code weapon}), у
 * лучника лук рисуется ванильным слоем предмета в руке.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class BanditRenderer<T extends Bandit> extends MobRenderer<T, BanditModel<T>> {

    public static final ModelLayerLocation SWORDSMAN_LAYER =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "bandit_swordsman"), "main");
    public static final ModelLayerLocation ARCHER_LAYER =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "bandit_archer"), "main");

    private static final ResourceLocation SWORDSMAN_TEX = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/entity/bandit.png");
    private static final ResourceLocation ELITE_TEX = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/entity/bandit_elite.png");
    private static final ResourceLocation CHIEF_TEX = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/entity/bandit_chief.png");
    private static final ResourceLocation ARCHER_TEX = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/entity/bandit_archer.png");

    private final boolean archer;

    public BanditRenderer(EntityRendererProvider.Context context, ModelLayerLocation layer, String anims, boolean archer) {
        super(context, new BanditModel<>(context.bakeLayer(layer), anims), 0.5F);
        this.archer = archer;
        if (archer) {
            addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
        }
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        if (archer) {
            return ARCHER_TEX;
        }
        // Главарь — в багровом с золотой повязкой (docs/design/24-bandit-camp.md §2).
        if (entity.isChief()) {
            return CHIEF_TEX;
        }
        // Элитный (знает технику ци) — с синей повязкой вместо красной: «этот сильнее» видно заранее.
        return entity.isElite() ? ELITE_TEX : SWORDSMAN_TEX;
    }

    /** Главарь на голову крупнее: силуэт выделяется в толпе издалека. */
    @Override
    protected void scale(T entity, com.mojang.blaze3d.vertex.PoseStack pose, float partialTick) {
        if (entity.isChief()) {
            pose.scale(1.12F, 1.12F, 1.12F);
        }
    }

    /**
     * Последние 3 тика замаха бандит вспыхивает белым (ванильная вспышка урона-подсветки, работает
     * с любыми шейдерами): «сейчас ударит» читается ночью и краем глаза, вместе с бликом на клинке.
     */
    @Override
    protected float getWhiteOverlayProgress(T entity, float partialTick) {
        if (entity.state() == Bandit.WINDUP) {
            float left = entity.move().windup() - entity.stateAge(partialTick);
            if (left <= 3.0F) {
                return 0.3F * Math.max(0.0F, 1.0F - Math.abs(left - 1.5F) / 1.5F) + 0.1F;
            }
        }
        return 0.0F;
    }

    @SubscribeEvent
    static void onLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(SWORDSMAN_LAYER, () -> BedrockGeo.load("/assets/murim/bedrock/bandit.geo.json"));
        event.registerLayerDefinition(ARCHER_LAYER, () -> BedrockGeo.load("/assets/murim/bedrock/bandit_archer.geo.json"));
    }

    @SubscribeEvent
    static void onRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.BANDIT_SWORDSMAN.get(), c -> new BanditRenderer<>(c, SWORDSMAN_LAYER, "bandit", false));
        event.registerEntityRenderer(ModEntities.BANDIT_ARCHER.get(), c -> new BanditRenderer<>(c, ARCHER_LAYER, "bandit_archer", true));
    }
}
