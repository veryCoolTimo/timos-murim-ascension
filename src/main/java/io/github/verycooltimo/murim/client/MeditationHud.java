package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.MeditationService;
import io.github.verycooltimo.murim.network.SyncMeditationPayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
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
 * Подсказки медитации: ход сессии до семени и окно удержания кольца.
 *
 * <p>Временный интерфейс-заготовка: «внутренний взгляд» и видения осмысления
 * (docs/design/19 §3, §3б) придут отдельным срезом. Здесь только то, без чего такт
 * удержания непроходим — игрок должен видеть, когда держать и сколько удержано.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class MeditationHud {

    private static final ResourceLocation LAYER =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "meditation");

    private static final int BAR_WIDTH = 120;

    @SubscribeEvent
    static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, LAYER, MeditationHud::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.player == null) {
            return;
        }
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();

        int flash = ClientMeditationState.seedFlashTicks();
        if (flash > 0) {
            // Заглушка «внутреннего взгляда»: мягкая вспышка, гаснущая за 12 секунд.
            float t = flash / (float) ClientMeditationState.SEED_FLASH_TICKS;
            int alpha = (int) (Mth.clamp(t, 0.0F, 1.0F) * 0x60);
            graphics.fill(0, 0, width, height, (alpha << 24) | 0x9FE8C8);
        }

        SyncMeditationPayload state = ClientMeditationState.state();
        if (!state.active() || state.beats() >= 3) {
            return;
        }
        int x = (width - BAR_WIDTH) / 2;
        int y = height - 74;

        // Ход сессии.
        float progress = Mth.clamp(state.ticks() / (float) MeditationService.SESSION_TICKS, 0.0F, 1.0F);
        graphics.fill(x - 1, y - 1, x + BAR_WIDTH + 1, y + 4, 0xAA000000);
        graphics.fill(x, y, x + (int) (BAR_WIDTH * progress), y + 3, 0xFF64D8C8);

        if (state.beats() != 1) {
            return;
        }
        // Окно удержания отмечено на полосе, чтобы его приближение было видно заранее.
        int from = x + BAR_WIDTH * MeditationService.RING_FROM / MeditationService.SESSION_TICKS;
        int to = x + BAR_WIDTH * MeditationService.RING_TO / MeditationService.SESSION_TICKS;
        graphics.fill(from, y + 4, to, y + 5, 0xFFE0C060);

        int hy = y + 8;
        float held = Mth.clamp(state.holdTicks() / (float) MeditationService.HOLD_REQUIRED, 0.0F, 1.0F);
        graphics.fill(x - 1, hy - 1, x + BAR_WIDTH + 1, hy + 3, 0xAA000000);
        graphics.fill(x, hy, x + (int) (BAR_WIDTH * held), hy + 2, held >= 1.0F ? 0xFF7FC46A : 0xFFE0C060);

        if (ClientMeditationState.ringWindowOpen()) {
            Component hint = Component.translatable("murim.meditation.hud.hold",
                    minecraft.options.keyJump.getTranslatedKeyMessage());
            graphics.drawCenteredString(minecraft.font, hint, width / 2, y - 12, 0xFFE0C060);
        }
    }

    private MeditationHud() {
    }
}
