package io.github.verycooltimo.murim.client.vfx;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.ClientConfig;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Экранный акцент в момент попадания: затемнение по краям и короткие лучи к центру.
 *
 * <p>Зачем слой интерфейса, а не мировой эффект: удар происходит перед корпусом, и при виде
 * из-за спины вторую половину взмаха закрывает сам игрок (ADR-75). Момент контакта нужно
 * донести тем, что нельзя перекрыть, — экраном. Слои интерфейса к тому же не трогает Iris,
 * поэтому эффект одинаков с шейдерпаком и без.
 *
 * <p><b>Сознательно не полноэкранная вспышка.</b> Заливка кадра белым — именно тот приём,
 * против которого направлены требования доступности. Виньетка по краям и лучи дают тот же
 * акцент, не засвечивая центр, где игрок смотрит на противника.
 *
 * <p>Частота под контролем: эффект живёт шесть тиков, а перезарядка техники — сорок,
 * поэтому чаще трёх раз в секунду он повториться не может. Переключатель вспышек
 * отключает слой целиком.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class ImpactScreenLayer {

    private static final ResourceLocation LAYER =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "impact");

    /** Длительность эффекта. Короткая: правило 04 требует пик в первые проценты жизни. */
    private static final float DURATION_TICKS = 6.0F;

    /** Доля половины экрана, которую занимает виньетка. */
    private static final float VIGNETTE_FRACTION = 0.22F;

    /** Ширина полоски виньетки в пикселях. Два — незаметный шаг при вменяемом числе вызовов. */
    private static final int STRIP = 2;

    private static float remaining;
    private static int ticks;

    /** Запускает акцент. Вызывается по событию попадания. */
    public static void trigger() {
        remaining = DURATION_TICKS;
    }

    /** Гасит акцент: смена мира не должна оставлять на экране чужую засветку. */
    public static void reset() {
        remaining = 0.0F;
    }

    @SubscribeEvent
    static void onRegisterLayers(RegisterGuiLayersEvent event) {
        // Над прицелом, но ниже чата и меню: акцент не должен мешать читать текст.
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, LAYER, ImpactScreenLayer::render);
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        ticks++;
        if (remaining > 0.0F) {
            remaining = Math.max(0.0F, remaining - 1.0F);
        }
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        if (remaining <= 0.0F || !ClientConfig.SCREEN_FLASHES.get()) {
            return;
        }
        float age = remaining - delta.getGameTimeDeltaPartialTick(false);
        float progress = Mth.clamp(age / DURATION_TICKS, 0.0F, 1.0F);
        if (progress <= 0.0F) {
            return;
        }
        // Кубическое затухание: удар обязан гаснуть быстрее, чем нарастал.
        float alpha = progress * progress * progress;

        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int band = (int) (Math.min(width, height) * VIGNETTE_FRACTION);
        float peak = 130.0F * alpha;

        // Виньетка рисуется полосками, а не через fillGradient: тот интерполирует цвет
        // строго по вертикали (colorFrom на верхней кромке, colorTo на нижней — проверено
        // по исходнику GuiGraphics), поэтому боковые стороны выходили прямоугольными
        // пятнами с жёсткой границей вместо мягкого затемнения. Поймано на кадрах 2026-08-10.
        for (int i = 0; i < band; i += STRIP) {
            // Квадратичный спад от края к центру: линейный даёт заметную кромку.
            float t = 1.0F - (float) i / band;
            int a = (int) (peak * t * t);
            if (a <= 0) {
                continue;
            }
            int colour = a << 24;
            graphics.fill(0, i, width, i + STRIP, colour);
            graphics.fill(0, height - i - STRIP, width, height - i, colour);
            graphics.fill(i, 0, i + STRIP, height, colour);
            graphics.fill(width - i - STRIP, 0, width - i, height, colour);
        }

        if (!ClientConfig.DISTORTION_EFFECTS.get()) {
            return;
        }
        // Лучи к центру. Идут по диагонали удара — сверху справа вниз налево,
        // тем же направлением, что и клинок, иначе акцент спорит с движением.
        int streak = (int) (200 * alpha) << 24 | 0x00DCE8FF;
        int length = (int) (band * (0.55F + 0.45F * alpha));
        for (int i = 1; i <= 3; i++) {
            int offset = band / 2 + i * band / 5;
            graphics.fill(width - offset - length, offset, width - offset, offset + 2, streak);
            graphics.fill(offset, height - offset - 2, offset + length, height - offset, streak);
        }
    }

    private ImpactScreenLayer() {
    }
}
