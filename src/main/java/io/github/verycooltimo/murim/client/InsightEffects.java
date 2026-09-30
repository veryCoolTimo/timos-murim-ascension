package io.github.verycooltimo.murim.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.vfx.BillboardBurst;
import io.github.verycooltimo.murim.client.vfx.BoneAnchorLayer;
import io.github.verycooltimo.murim.client.vfx.CoreGlow;
import io.github.verycooltimo.murim.client.vfx.MurimRenderTypes;
import io.github.verycooltimo.murim.client.vfx.VfxColour;
import io.github.verycooltimo.murim.network.InsightPayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Озарение — техника перешла на новый слой (docs/design/19 §3г, автор 30.09).
 *
 * <p>В бою: замирание кадра на четверть секунды, мягкая вспышка по краям экрана, искры
 * ци от груди и титр — бой не прерывается. В медитации: двойник сразу и ярко выполняет
 * этот приём, титр тот же.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class InsightEffects {

    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "insight");

    /** Сколько живёт титр и эффекты. */
    static final int TICKS = 70;

    private static final VfxColour HALO = new VfxColour(0.16F, 0.42F, 0.95F);
    private static final VfxColour CORE = new VfxColour(0.62F, 0.93F, 1.0F);

    private static InsightPayload current;
    private static int age = -1;

    public static void onInsight(InsightPayload payload) {
        current = payload;
        age = 0;
        if (payload.meditating()) {
            MeditationEcho.insight(payload.technique());
        } else {
            // Замирание — тот же механизм, что у hit stop: мир на миг останавливается.
            HitStopHandler.request(5);
        }
    }

    /** Возраст идущего озарения в тиках, или -1. */
    public static int age() {
        return age;
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (age >= 0 && ++age > TICKS) {
            age = -1;
            current = null;
        }
    }

    @SubscribeEvent
    static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, LAYER, InsightEffects::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        if (current == null || age < 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        float t = age + delta.getGameTimeDeltaPartialTick(false);
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();

        // Мягкая вспышка по краям — только в бою и только если игрок не отключил вспышки.
        if (!current.meditating() && ClientConfig.SCREEN_FLASHES.get()) {
            float flash = Mth.clamp(1.0F - t / 12.0F, 0.0F, 1.0F);
            if (flash > 0.0F) {
                int a = (int) (flash * flash * 0x70);
                int edge = h / 5;
                graphics.fillGradient(0, 0, w, edge, (a << 24) | 0xCFF2FF, 0x00CFF2FF);
                graphics.fillGradient(0, h - edge, w, h, 0x00CFF2FF, (a << 24) | 0xCFF2FF);
            }
        }

        // Титр: «Озарение» и техника со слоем — проявляется и гаснет.
        float alpha = Mth.clamp(t / 8.0F, 0.0F, 1.0F) * Mth.clamp((TICKS - t) / 16.0F, 0.0F, 1.0F);
        if (alpha <= 0.02F || minecraft.options.hideGui && !ClientMeditationState.cinematic()) {
            return;
        }
        int a = Math.max(4, (int) (alpha * 255)) << 24;
        Font font = minecraft.font;
        Component title = Component.translatable("murim.mastery.insight");
        Component line = Component.translatable("murim.mastery.insight.layer",
                io.github.verycooltimo.murim.mastery.MasteryService.name(current.technique()), current.layer());
        // Выше названия техники (его слой — на трети высоты), иначе титры налезают.
        int y = (int) (h * 0.16F);
        graphics.pose().pushPose();
        graphics.pose().translate(w / 2.0F, y, 0.0F);
        graphics.pose().scale(1.5F, 1.5F, 1.0F);
        graphics.drawString(font, title, -font.width(title) / 2, 0, a | 0xCFEFFF, true);
        graphics.pose().popPose();
        graphics.drawString(font, line, (w - font.width(line)) / 2, y + 16, a | 0x8FC8F0, true);
    }

    /** Искры ци от груди игрока — озарение видно и со стороны тела. */
    @SubscribeEvent
    static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || current == null
                || age < 0 || current.meditating()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.player instanceof AbstractClientPlayer player)) {
            return;
        }
        Vec3 chest = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.CHEST);
        if (chest == null) {
            return;
        }
        float t = age + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float k = Mth.clamp(1.0F - t / 30.0F, 0.0F, 1.0F);
        if (k <= 0.0F) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        try {
            poseStack.translate(-camera.x, -camera.y, -camera.z);
            PoseStack.Pose pose = poseStack.last();
            VertexConsumer glow = minecraft.renderBuffers().bufferSource().getBuffer(MurimRenderTypes.impactCore());
            BillboardBurst.outward(glow, pose, chest, camera, 30, t, 0.9D, k, HALO, CORE);
            CoreGlow.draw(glow, pose, chest, camera, t, 0.12D * k + 0.05D, k * k, HALO, CORE);
            minecraft.renderBuffers().bufferSource().endBatch(MurimRenderTypes.impactCore());
        } finally {
            poseStack.popPose();
        }
    }

    public static void reset() {
        current = null;
        age = -1;
    }

    private InsightEffects() {
    }
}
