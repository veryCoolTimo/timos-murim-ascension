package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Отладочные маркеры костей.
 *
 * <p>Существует только чтобы ПРОВЕРИТЬ привязку: рассуждать о знаках осей и о том, в какой
 * системе координат оказалась матрица, бессмысленно — надо посмотреть, куда попала точка.
 * Включается свойством {@code -Dmurim.debug.bones=true}, в обычной игре не делает ничего.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class BoneDebugRenderer {

    private static final boolean ENABLED = Boolean.getBoolean("murim.debug.bones");

    @SubscribeEvent
    static void onRenderStage(RenderLevelStageEvent event) {
        if (!ENABLED || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();

        for (AbstractClientPlayer player : minecraft.level.players()) {
            marker(poseStack, buffers, camera, BoneAnchorLayer.position(player,
                    BoneAnchorLayer.Bone.RIGHT_HAND), 1.0F, 0.3F, 0.3F);
            marker(poseStack, buffers, camera, BoneAnchorLayer.position(player,
                    BoneAnchorLayer.Bone.CHEST), 0.3F, 1.0F, 0.4F);
            marker(poseStack, buffers, camera, BoneAnchorLayer.position(player,
                    BoneAnchorLayer.Bone.DANTIAN), 0.4F, 0.6F, 1.0F);
        }
        buffers.endBatch(MurimRenderTypes.impactCore());
    }

    private static void marker(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                               Vec3 camera, Vec3 point, float red, float green, float blue) {
        if (point == null) {
            return;
        }
        poseStack.pushPose();
        try {
            poseStack.translate(point.x - camera.x, point.y - camera.y, point.z - camera.z);
            PoseStack.Pose pose = poseStack.last();
            var consumer = buffers.getBuffer(MurimRenderTypes.impactCore());
            float size = 0.22F;  // крупнее: мелкие маркеры теряются внутри модели
            for (int i = 0; i < 4; i++) {
                float u = (i == 1 || i == 2) ? 1.0F : 0.0F;
                float v = (i >= 2) ? 1.0F : 0.0F;
                float x = (u - 0.5F) * size * 2.0F;
                float y = (v - 0.5F) * size * 2.0F;
                VfxDraw.vertex(consumer, pose, new net.minecraft.world.phys.Vec3(x, y, 0.0D),
                               new net.minecraft.world.phys.Vec3(0.0D, 0.0D, 1.0D),
                               u, v, 1.0F, red, green, blue);
            }
        } finally {
            poseStack.popPose();
        }
    }

    private BoneDebugRenderer() {
    }
}
