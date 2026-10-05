package io.github.verycooltimo.murim.client.trade;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.bandit.BanditAnimations;
import io.github.verycooltimo.murim.client.bandit.BanditRenderer;
import io.github.verycooltimo.murim.trade.ModTrade;
import io.github.verycooltimo.murim.trade.Peddler;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.KeyframeAnimations;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.joml.Vector3f;

import java.util.Map;

/**
 * Рендер бродячего торговца: геометрия лучника-бандита (без оружия; колчан на спине стал котомкой)
 * в своей текстуре ({@code tools/art/peddler_texture.py}) и его клипы покоя и шага.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class PeddlerRenderer extends MobRenderer<Peddler, PeddlerRenderer.Model> {

    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/entity/peddler.png");

    public PeddlerRenderer(EntityRendererProvider.Context context) {
        super(context, new Model(context.bakeLayer(BanditRenderer.ARCHER_LAYER)), 0.5F);
    }

    @Override
    public ResourceLocation getTextureLocation(Peddler entity) {
        return TEXTURE;
    }

    @SubscribeEvent
    static void onRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModTrade.PEDDLER.get(), PeddlerRenderer::new);
    }

    /** Покой и шаг смешиваются по скорости, как у бандита (BanditModel), голова — на собеседника. */
    public static class Model extends HierarchicalModel<Peddler> {

        private static final Vector3f CACHE = new Vector3f();

        private final ModelPart root;

        public Model(ModelPart root) {
            this.root = root;
        }

        @Override
        public ModelPart root() {
            return root;
        }

        @Override
        public void setupAnim(Peddler entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
            root.getAllParts().forEach(ModelPart::resetPose);
            Map<String, AnimationDefinition> clips = BanditAnimations.get("bandit_archer");
            float walk = Mth.clamp(limbSwingAmount * 2.5F, 0.0F, 1.0F);
            AnimationDefinition idle = clips.get("idle");
            AnimationDefinition step = clips.get("walk");
            if (idle != null && walk < 1.0F) {
                play(idle, ageInTicks, 1.0F - walk);
            }
            if (step != null && walk > 0.0F) {
                play(step, limbSwing / 9.43F * step.lengthInSeconds() * 20.0F, walk);
            }
            getAnyDescendantWithName("head").ifPresent(h -> {
                h.yRot += netHeadYaw * Mth.DEG_TO_RAD;
                h.xRot += headPitch * Mth.DEG_TO_RAD;
            });
        }

        private void play(AnimationDefinition def, float ticks, float scale) {
            // API: reference/minecraft-src/net/minecraft/client/animation/KeyframeAnimations.java#animate (время в мс)
            KeyframeAnimations.animate(this, def, (long) (ticks * 50.0F), scale, CACHE);
        }
    }
}
