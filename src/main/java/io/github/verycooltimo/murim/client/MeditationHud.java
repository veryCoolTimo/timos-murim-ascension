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
            float age = sceneAge + delta.getGameTimeDeltaPartialTick(false);
            vignette(graphics, age);
            seedScene(graphics, minecraft, age);
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

    /** Мягкая круглая текстура свечения — та же, что у ядра удара в мире. */
    private static final ResourceLocation GLOW =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/impact_core.png");

    /**
     * Взрыв рождения семени (замечание автора 29.09: «затемнить всё, нет текста,
     * вспышку белее, экспозицию сильнее, чуть тряски»).
     *
     * <p>Порядок слоёв: затемнение всего экрана → семя поверх темноты (иначе затемнение
     * гасит и его) → белая вспышка-передержка → титр. Вспышка одна за сцену и
     * отключается настройкой экранных вспышек (правило 04).
     */
    private static void seedScene(GuiGraphics graphics, Minecraft minecraft, float age) {
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        float total = ClientMeditationState.SEED_SCENE_TICKS;
        float out = Mth.clamp((total - age) / 40.0F, 0.0F, 1.0F);

        // Затемнение: весь мир уходит в тёмную синеву, пока идёт «взгляд внутрь».
        // Не до черноты: силуэт тела и жилы должны угадываться.
        float dark = Mth.clamp((age - 2.0F) / 10.0F, 0.0F, 1.0F) * out;
        if (dark > 0.0F) {
            int a = (int) (dark * 0xA8);
            graphics.fill(0, 0, w, h, (a << 24) | 0x03060F);
        }

        // Семя пробивает темноту: дорисовывается по своей экранной точке.
        float[] seed = io.github.verycooltimo.murim.client.vfx.MeditationVfxRenderer.seedOnScreen();
        if (seed != null && dark > 0.0F) {
            float beat = (age % 30.0F) / 30.0F;
            float pulse = (float) (Math.exp(-beat * 18.0D) + 0.6D * Math.exp(-Math.abs(beat - 0.22D) * 18.0D));
            int cx = (int) (seed[0] * w);
            int cy = (int) (seed[1] * h);
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
            glow(graphics, cx, cy, (int) (h * (0.55F + 0.08F * pulse)), 0.16F, 0.42F, 0.95F, 0.55F * dark);
            glow(graphics, cx, cy, (int) (h * (0.22F + 0.05F * pulse)), 0.45F, 0.88F, 1.0F, 0.8F * dark);
            glow(graphics, cx, cy, (int) (h * (0.07F + 0.03F * pulse)), 0.9F, 0.98F, 1.0F, dark);
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }

        // Вспышка: резкая передержка в белое за два тика и спад за полсекунды.
        if (ClientConfig.SCREEN_FLASHES.get()) {
            // Спад кубический: удар белым, без долгой серой дымки после него.
            float flash = age < 2.0F ? age / 2.0F : Mth.clamp(1.0F - (age - 2.0F) / 9.0F, 0.0F, 1.0F);
            if (flash > 0.0F) {
                graphics.fill(0, 0, w, h, ((int) (flash * flash * flash * 0xFA) << 24) | 0xF4FBFF);
            }
        }

        // Титр: появляется, когда вспышка отгорела, и гаснет до конца сцены.
        float title = Mth.clamp((age - 24.0F) / 16.0F, 0.0F, 1.0F) * Mth.clamp((total - 30.0F - age) / 30.0F, 0.0F, 1.0F);
        if (title > 0.02F) {
            int alpha = Math.max(4, (int) (title * 255)) << 24;
            Font font = minecraft.font;
            Component name = Component.translatable("murim.meditation.seed_title");
            graphics.pose().pushPose();
            graphics.pose().translate(w / 2.0F, h * 0.24F, 0.0F);
            graphics.pose().scale(2.0F, 2.0F, 1.0F);
            graphics.drawString(font, name, -font.width(name) / 2, 0, alpha | 0xCFEFFF, true);
            graphics.pose().popPose();
            String imprint = ClientProfileState.profile().imprint();
            if (imprint != null && imprint.contains(":")) {
                ResourceLocation id = ResourceLocation.tryParse(imprint);
                if (id != null) {
                    Component method = Component.translatable("method." + id.getNamespace() + "." + id.getPath());
                    graphics.drawString(font, method, (w - font.width(method)) / 2, (int) (h * 0.24F) + 24,
                            alpha | 0x8FB8E8, true);
                }
            }
        }
    }

    private static void glow(GuiGraphics graphics, int cx, int cy, int size, float r, float g, float b, float a) {
        if (size <= 0 || a <= 0.0F) {
            return;
        }
        graphics.setColor(r, g, b, Mth.clamp(a, 0.0F, 1.0F));
        graphics.blit(GLOW, cx - size / 2, cy - size / 2, 0.0F, 0.0F, size, size, size, size);
    }

    private MeditationHud() {
    }
}
