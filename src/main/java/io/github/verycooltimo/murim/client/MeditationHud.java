package io.github.verycooltimo.murim.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.MeditationService;
import io.github.verycooltimo.murim.network.SyncMeditationPayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Интерфейс медитации — минимальный: главное показывает кольцо на теле.
 *
 * <p>Всё стоит у ВЕРХНЕГО края экрана. Первая версия клала полосы над хотбаром, и в
 * переднем ракурсе они ложились ровно на пояс персонажа — туда, где кольцо и должно
 * быть видно; сообщение над хотбаром при этом перечёркивало полосу.
 *
 * <p>Во время сцены семени края экрана темнеют в синеву: взгляд уходит внутрь тела.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class MeditationHud {

    private static final ResourceLocation LAYER =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "meditation");

    /** API: reference/minecraft-src/net/minecraft/client/gui/Gui.java#VIGNETTE_LOCATION */
    private static final ResourceLocation VIGNETTE =
            ResourceLocation.withDefaultNamespace("textures/misc/vignette.png");

    private static final int LINE_WIDTH = 90;
    private static final int TOP = 26;

    @SubscribeEvent
    static void onRegisterLayers(RegisterGuiLayersEvent event) {
        // Под остальными слоями: затемнение не должно гасить сердца и хотбар.
        event.registerBelow(VanillaGuiLayers.CAMERA_OVERLAYS, LAYER, MeditationHud::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        int sceneAge = ClientMeditationState.seedSceneAge();
        if (sceneAge >= 0) {
            vignette(graphics, sceneAge + delta.getGameTimeDeltaPartialTick(false));
        }
        if (minecraft.options.hideGui) {
            return;
        }
        SyncMeditationPayload state = ClientMeditationState.state();
        if (!state.active() || state.beats() >= 3) {
            return;
        }
        int width = graphics.guiWidth();
        int x = (width - LINE_WIDTH) / 2;
        int ticks = ClientMeditationState.sessionTicks();

        // Ход сессии — тонкая линия, чтобы было видно, сколько сидеть.
        float progress = Mth.clamp(ticks / (float) MeditationService.SESSION_TICKS, 0.0F, 1.0F);
        graphics.fill(x, TOP, x + LINE_WIDTH, TOP + 1, 0x60FFFFFF);
        graphics.fill(x, TOP, x + (int) (LINE_WIDTH * progress), TOP + 1, 0xE0A8E4FF);

        if (state.beats() != 1) {
            return;
        }
        // Окно удержания — отрезок на линии, заметный заранее.
        int from = x + LINE_WIDTH * MeditationService.RING_FROM / MeditationService.SESSION_TICKS;
        int to = x + LINE_WIDTH * MeditationService.RING_TO / MeditationService.SESSION_TICKS;
        graphics.fill(from, TOP - 1, to, TOP + 2, 0x90E0C060);

        boolean open = ClientMeditationState.ringWindowOpen();
        boolean soon = !open && ticks >= MeditationService.RING_FROM - 40 && ticks < MeditationService.RING_FROM;
        if (open || soon) {
            prompt(graphics, minecraft.font, minecraft, width, open, state.holdTicks());
        }
    }

    /** Подсказка «[Пробел] удерживайте кольцо» с клавишей в рамке и шкалой удержания. */
    private static void prompt(GuiGraphics graphics, Font font, Minecraft minecraft, int width,
                               boolean open, int held) {
        Component key = minecraft.options.keyJump.getTranslatedKeyMessage();
        Component text = Component.translatable(open
                ? "murim.meditation.hud.hold" : "murim.meditation.hud.ready");
        int keyWidth = font.width(key) + 8;
        int total = keyWidth + 6 + font.width(text);
        int left = (width - total) / 2;
        int y = TOP + 8;
        boolean holding = ClientMeditationState.holding();
        int keyColour = holding ? 0xFF7FE0A0 : 0xFFE0C060;
        int alpha = open ? 0xFF : 0x90;

        graphics.fill(left - 4, y - 3, left + total + 4, y + 12, (alpha / 2) << 24);
        graphics.fill(left, y - 1, left + keyWidth, y + 10, keyColour & 0x00FFFFFF | (alpha << 24));
        graphics.fill(left + 1, y, left + keyWidth - 1, y + 9, 0xFF101418 & 0x00FFFFFF | (alpha << 24));
        graphics.drawString(font, key, left + 4, y + 1, keyColour & 0x00FFFFFF | (alpha << 24), false);
        graphics.drawString(font, text, left + keyWidth + 6, y + 1, 0xFFFFFF | (alpha << 24), true);

        if (open) {
            float share = Mth.clamp(held / (float) MeditationService.HOLD_REQUIRED, 0.0F, 1.0F);
            int bar = left + (int) (total * share);
            graphics.fill(left, y + 14, left + total, y + 16, 0x80000000);
            graphics.fill(left, y + 14, bar, y + 16, share >= 1.0F ? 0xFF7FE0A0 : 0xFFE0C060);
        }
    }

    /**
     * Затемнение краёв в синеву — тот же приём, что у ванильной виньетки: смешивание
     * «ноль × источник, (1 − цвет) × приёмник» гасит каналы по цвету текстуры.
     * Синий гасится слабее красного, поэтому края уходят в глубокую синеву, а не в серость.
     */
    private static void vignette(GuiGraphics graphics, float age) {
        float total = ClientMeditationState.SEED_SCENE_TICKS;
        float in = Mth.clamp(age / 20.0F, 0.0F, 1.0F);
        float out = Mth.clamp((total - age) / 40.0F, 0.0F, 1.0F);
        float k = in * out;
        if (k <= 0.0F) {
            return;
        }
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ZERO,
                GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        graphics.setColor(0.95F * k, 0.85F * k, 0.55F * k, 1.0F);
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        // Три прохода: одна ванильная виньетка слишком мягкая для «взгляда внутрь».
        graphics.blit(VIGNETTE, 0, 0, -90, 0.0F, 0.0F, w, h, w, h);
        graphics.blit(VIGNETTE, 0, 0, -90, 0.0F, 0.0F, w, h, w, h);
        graphics.blit(VIGNETTE, 0, 0, -90, 0.0F, 0.0F, w, h, w, h);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    private MeditationHud() {
    }
}
