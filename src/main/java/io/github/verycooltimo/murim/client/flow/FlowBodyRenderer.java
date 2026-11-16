package io.github.verycooltimo.murim.client.flow;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.vfx.*;
import io.github.verycooltimo.murim.cultivation.flow.FlowGame;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Render stage of the pill scenes and the high pill game (depth copies, bone anchors); the game's picture is {@link FlowMeridianFx}. API and render-stage pattern: MeditationVfxRenderer, BodyMeridians, VfxDraw. */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class FlowBodyRenderer {
    // Render-thread resources only; released as soon as the flow ends, rebuilt on resize.
    private static RenderTarget worldDepth;
    private static RenderTarget sceneDepth;
    private static boolean depthReady;

    @SubscribeEvent
    static void onRender(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) {
            depthReady = false;
            if (!FlowClient.active() && !FlowClient.lingering()) {
                releaseDepth();
                return;
            }
            // API: NeoForge 1.21.1 RenderLevelStageEvent.Stage; terrain precedes entities.
            // API: Mojang 1.21.1 client mappings, RenderTarget#copyDepthFrom and TextureTarget ctor.
            RenderTarget main = mc.getMainRenderTarget();
            if (worldDepth == null || worldDepth.width != main.width || worldDepth.height != main.height) {
                releaseDepth();
                worldDepth = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
                sceneDepth = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
            }
            worldDepth.copyDepthFrom(main);
            main.bindWrite(false);
            depthReady = true;
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || !(FlowClient.active() || FlowClient.lingering()) || !depthReady) return;
        if (mc.player == null) return;
        boolean game = FlowClient.game() == null || !(PillScene.active() || FlowClient.commonQuick());
        FlowBodySurface surface = null;
        if (!game) {
            Vec3 bottom = BoneAnchorLayer.position(mc.player, BoneAnchorLayer.Bone.FLOW_BOTTOM);
            Vec3 top = BoneAnchorLayer.position(mc.player, BoneAnchorLayer.Bone.FLOW_TOP);
            Vec3 front = BoneAnchorLayer.position(mc.player, BoneAnchorLayer.Bone.FLOW_FRONT);
            if (bottom == null || top == null || front == null) return;
            surface = new FlowBodySurface(bottom, top, front);
        }
        Vec3 camera = event.getCamera().getPosition();
        PoseStack stack = event.getPoseStack();
        RenderTarget main = mc.getMainRenderTarget();
        // AFTER_LEVEL has finished Fabulous composition as well. Preserve every entity's depth.
        mc.renderBuffers().bufferSource().endBatch();
        sceneDepth.copyDepthFrom(main);
        stack.pushPose();
        try {
            // AFTER_LEVEL arrives with an identity pose stack and an identity RenderSystem model-view: the camera's rotation is only in the event's own
            // matrix. The game's picture is placed with it (the pill scenes keep their old, calibrated placement).
            if (!PillScene.active() && !FlowClient.commonQuick()) stack.mulPose(event.getModelViewMatrix());
            stack.translate(-camera.x, -camera.y, -camera.z);
            var buffers = mc.renderBuffers().bufferSource();
            if (!PillScene.active() && !FlowClient.commonQuick() && FlowClient.gameForFxOrLingering()) {
                // The space of the high pill game (vortex, sheets, rim, dust, rays): the scene's own depth, so the figure stands inside it.
                main.copyDepthFrom(sceneDepth);
                main.bindWrite(false);
                FlowMeridianFx.drawSpace(mc, stack.last(), camera, buffers);
            } else if (PillCalmFx.running()) {
                // the calm absorption of common and middle pills: the same space layers, no board
                main.copyDepthFrom(sceneDepth);
                main.bindWrite(false);
                PillCalmFx.drawSpace(mc, stack.last(), camera, buffers);
            }
            main.copyDepthFrom(worldDepth);
            main.bindWrite(false);
            if (PillScene.active()) {
                PillSceneRenderer.draw(mc, surface, stack.last(), camera, buffers, event.getPartialTick().getGameTimeDeltaPartialTick(false));
            } else if (FlowClient.commonQuick()) {
                PillCommonFx.draw(mc, surface, stack.last(), camera, buffers, FlowClient.shown(), FlowClient.visibility(), FlowClient.pillColour());
            } else {
                // The high pill game: the floor light (the engine's ground light), the meridian over the body (world depth only).
                FlowMeridianFx.updateFloor(mc);
                PillCalmFx.updateFloor(mc);
                FlowMeridianFx.drawBody(mc, stack.last(), camera, buffers);
                FlowScrollBoard.drawWorld(event.getModelViewMatrix(), camera);
            }
        } finally {
            main.copyDepthFrom(sceneDepth);
            main.bindWrite(false);
            depthReady = false;
            stack.popPose();
        }
    }

    private static void releaseDepth() {
        if (worldDepth != null) worldDepth.destroyBuffers();
        if (sceneDepth != null) sceneDepth.destroyBuffers();
        worldDepth = sceneDepth = null;
    }
}
