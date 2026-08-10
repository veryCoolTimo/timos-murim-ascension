package io.github.verycooltimo.murim.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.TrainingDummy;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Отрисовка манекена. Модель ванильного зомби, текстура кожи по умолчанию —
 * собственная модель на этапе 2 не окупается, проверяется поведение, а не внешность.
 *
 * <p>Единственное, что добавлено к ванильному рендеру, — <b>подсветка фазы</b>: манекен
 * краснеет на замахе и синеет в окне наказания. Телеграф обязан читаться с одного взгляда,
 * иначе цикл нечестный.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public class DummyRenderer extends LivingEntityRenderer<TrainingDummy, HumanoidModel<TrainingDummy>> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/entity/zombie/zombie.png");

    public DummyRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE)), 0.5F);
    }

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.DUMMY.get(), DummyRenderer::new);
    }

    @Override
    public ResourceLocation getTextureLocation(TrainingDummy entity) {
        return TEXTURE;
    }

    @Override
    protected int getBlockLightLevel(TrainingDummy entity, net.minecraft.core.BlockPos pos) {
        // На замахе манекен подсвечивается сам: в тёмной пещере телеграф иначе не виден.
        return entity.phase() == 1 ? 15 : super.getBlockLightLevel(entity, pos);
    }

    @Override
    protected float getWhiteOverlayProgress(TrainingDummy entity, float partialTick) {
        // Ванильная белая вспышка переиспользуется как индикатор замаха: она уже поддержана
        // шейдером сущностей и работает с любыми шейдерпаками.
        return entity.phase() == 1 ? 0.6F : 0.0F;
    }

    @Override
    public void render(TrainingDummy entity, float yaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int light) {
        if (entity.phase() == 1) {
            // Лёгкое приседание на замахе: движение читается быстрее, чем цвет.
            poseStack.pushPose();
            poseStack.translate(0.0D, -0.08D, 0.0D);
            super.render(entity, yaw, partialTick, poseStack, buffers, light);
            poseStack.popPose();
            return;
        }
        super.render(entity, yaw, partialTick, poseStack, buffers, light);
    }
}
