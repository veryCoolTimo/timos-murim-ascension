package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.technique.WedgeProjectile;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Отрисовка летящего клина ци.
 *
 * <p>Модели нет намеренно: клин — это светящаяся плоскость, развёрнутая вдоль полёта
 * и к камере одновременно. Собственная геометрия дешевле модели и совпадает по языку
 * с остальными эффектами мода.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public class WedgeRenderer extends EntityRenderer<WedgeProjectile> {

    private static final float LENGTH = 1.1F;
    private static final float HALF_WIDTH = 0.2F;

    public WedgeRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.WEDGE.get(), WedgeRenderer::new);
    }

    @Override
    public void render(WedgeProjectile entity, float yaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int light) {
        Vec3 forward = entity.travelDirection();
        // Ширина разворачивается к камере: иначе клин, летящий точно от игрока,
        // виден с ребра и исчезает.
        Vec3 toCamera = entityRenderDispatcher.camera.getPosition()
                .subtract(entity.position()).normalize();
        Vec3 side = forward.cross(toCamera);
        if (side.lengthSqr() < 1.0E-6D) {
            side = new Vec3(0.0D, 1.0D, 0.0D).cross(forward);
        }
        Vec3 offset = side.normalize().scale(HALF_WIDTH);
        Vec3 tip = forward.scale(LENGTH * 0.5D);
        Vec3 tail = forward.scale(-LENGTH * 0.5D);

        VertexConsumer consumer = buffers.getBuffer(MurimRenderTypes.bladeCrescent());
        PoseStack.Pose pose = poseStack.last();
        Vec3 normal = toCamera;

        quad(consumer, pose, tail.subtract(offset), tip.subtract(offset.scale(0.25D)),
             tip.add(offset.scale(0.25D)), tail.add(offset), normal);

        super.render(entity, yaw, partialTick, poseStack, buffers, light);
    }

    private static void quad(VertexConsumer consumer, PoseStack.Pose pose,
                             Vec3 a, Vec3 b, Vec3 c, Vec3 d, Vec3 normal) {
        vertex(consumer, pose, a, normal, 0.0F, 0.0F);
        vertex(consumer, pose, b, normal, 1.0F, 0.0F);
        vertex(consumer, pose, c, normal, 1.0F, 1.0F);
        vertex(consumer, pose, d, normal, 0.0F, 1.0F);
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, Vec3 position,
                               Vec3 normal, float u, float v) {
        consumer.addVertex(pose.pose(), (float) position.x, (float) position.y, (float) position.z)
                .setColor(0.72F, 0.98F, 0.86F, 0.85F)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(0x00F000F0)
                .setNormal(pose, (float) normal.x, (float) normal.y, (float) normal.z);
    }

    @Override
    public ResourceLocation getTextureLocation(WedgeProjectile entity) {
        // Слой сам привязывает текстуру, но переопределить метод обязательно: базовый абстрактный.
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/blade_crescent.png");
    }
}
