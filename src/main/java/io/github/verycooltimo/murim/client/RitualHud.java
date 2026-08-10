package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.SyncRitualPayload;
import io.github.verycooltimo.murim.profile.DantianProfile;
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
 * Интерфейс ритуала: круг циркуляции, напряжение и запас ци.
 *
 * <p>Решение «остановиться или рискнуть» невозможно без обратной связи. Игрок должен видеть,
 * сколько кругов пройдено и насколько выросло напряжение, иначе выбор превращается в лотерею.
 *
 * <p>Полоса напряжения меняет цвет от зелёного к красному не плавно, а с заметным переломом
 * на пороге риска: момент, после которого круг может сорваться, обязан быть виден, а не
 * угадываться по оттенку.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class RitualHud {

    private static final ResourceLocation LAYER =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "ritual");

    private static final int BAR_WIDTH = 120;
    private static final int BAR_HEIGHT = 5;

    private static String cachedText;
    private static int cachedCycles = -1;
    private static int cachedQi = -1;
    private static int cachedMax = -1;
    private static String cachedWarning;

    @SubscribeEvent
    static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, LAYER, RitualHud::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.player == null || minecraft.player.isSpectator()) {
            return;
        }
        SyncRitualPayload ritual = ClientProfileState.ritual();
        if (!ritual.active()) {
            return;
        }

        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int x = (width - BAR_WIDTH) / 2;
        int y = height - 74;

        DantianProfile profile = ClientProfileState.profile();

        // Круг циркуляции.
        graphics.fill(x - 1, y - 1, x + BAR_WIDTH + 1, y + BAR_HEIGHT + 1, 0xAA000000);
        int filled = (int) (BAR_WIDTH * Mth.clamp(ritual.cycleProgress(), 0.0F, 1.0F));
        graphics.fill(x, y, x + filled, y + BAR_HEIGHT, 0xFF64D8C8);

        // Напряжение: до порога зелёное, за порогом красное. Перелом резкий намеренно.
        int sy = y + BAR_HEIGHT + 3;
        float strain = Mth.clamp(ritual.strain(), 0.0F, 1.6F);
        int strainWidth = (int) (BAR_WIDTH * (strain / 1.6F));
        int colour = strain >= 1.0F ? 0xFFE04040 : 0xFF7FC46A;
        graphics.fill(x - 1, sy - 1, x + BAR_WIDTH + 1, sy + 3, 0xAA000000);
        graphics.fill(x, sy, x + strainWidth, sy + 2, colour);

        // Строка пересобирается только когда меняются числа, а не каждый кадр: при 144 fps
        // прежний вариант создавал тысячи короткоживущих объектов в секунду ради текста,
        // который обновляется двадцать раз в секунду.
        int qi = (int) profile.circulating();
        int max = (int) profile.maxCirculating();
        if (cachedText == null || cachedCycles != ritual.cycles() || cachedQi != qi || cachedMax != max) {
            cachedCycles = ritual.cycles();
            cachedQi = qi;
            cachedMax = max;
            cachedText = Component.translatable("murim.hud.ritual", cachedCycles,
                    String.valueOf(qi), String.valueOf(max)).getString();
        }
        String text = cachedText;
        graphics.drawString(minecraft.font, text, x, y - 11, 0xFFD8E8FF, true);

        if (strain >= 1.0F) {
            if (cachedWarning == null) {
                cachedWarning = Component.translatable("murim.hud.ritual.risk").getString();
            }
            String warn = cachedWarning;
            graphics.drawString(minecraft.font, warn,
                    x + BAR_WIDTH - minecraft.font.width(warn), y - 11, 0xFFE07070, true);
        }
    }

    private RitualHud() {
    }
}
