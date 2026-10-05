package io.github.verycooltimo.murim.client.sect;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.verycooltimo.murim.client.HudText;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.network.SectBubblePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.Map;

/**
 * Пузыри с репликами над головами людей горы (живая гора, автор 05.10). Строку выбирает сервер
 * ({@link SectBubblePayload}); здесь — только показ: билборд над головой в стиле {@link HudText} (светлый текст на тёмной
 * полупрозрачной подложке, ровное появление и угасание без мигания — rules/04), не дальше {@link #RANGE} блоков, не больше
 * {@link #MAX_VISIBLE} сразу: новый вытесняет самый старый.
 *
 * <p>Состояние — только визуальное и только клиента (пузыри по id сущности), игровых решений здесь нет.
 * API: reference/minecraft-src/net/minecraft/client/renderer/entity/EntityRenderer.java#renderNameTag (тот же приём:
 * поворот к камере, масштаб 0,025, {@code Font#drawInBatch} с цветом подложки).
 */
public final class SpeechBubbles {

    /** Видно не дальше. */
    public static final double RANGE = 12.0D;
    /** Пузырей сразу. */
    public static final int MAX_VISIBLE = 3;
    /** Появление и угасание, тиков. */
    static final float FADE_IN = 5.0F;
    static final float FADE_OUT = 12.0F;
    /** Тёплый белый текст. */
    static final int TEXT = 0xF4ECDA;
    static final float SCALE = 0.025F;

    private record Bubble(Component text, long start, int ticks) {
    }

    private static final Map<Integer, Bubble> BUBBLES = new HashMap<>();

    private SpeechBubbles() {
    }

    public static void onBubble(SectBubblePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        long now = mc.level.getGameTime();
        BUBBLES.entrySet().removeIf(e -> now > e.getValue().start() + e.getValue().ticks());
        BUBBLES.put(payload.entity(), new Bubble(payload.text(), now, payload.ticks()));
        while (BUBBLES.size() > MAX_VISIBLE) {
            Integer oldest = null;
            long at = Long.MAX_VALUE;
            for (Map.Entry<Integer, Bubble> e : BUBBLES.entrySet()) {
                if (e.getValue().start() < at) {
                    at = e.getValue().start();
                    oldest = e.getKey();
                }
            }
            BUBBLES.remove(oldest);
        }
    }

    /** Стенд и проверка: сколько пузырей сейчас висит. */
    public static int active() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return 0;
        }
        long now = mc.level.getGameTime();
        int n = 0;
        for (Bubble b : BUBBLES.values()) {
            if (now <= b.start() + b.ticks()) {
                n++;
            }
        }
        return n;
    }

    /** Нарисовать пузырь над человеком, если он есть (из {@code DiscipleRenderer#render}). */
    public static void render(SectDisciple e, float partial, PoseStack pose, MultiBufferSource buffers, Font font,
                              Quaternionf camera, double distSqr) {
        if (BUBBLES.isEmpty() || distSqr > RANGE * RANGE) {
            return;
        }
        Bubble b = BUBBLES.get(e.getId());
        if (b == null) {
            return;
        }
        float age = e.level().getGameTime() - b.start() + partial;
        if (age > b.ticks()) {
            BUBBLES.remove(e.getId());
            return;
        }
        float a = Math.min(1.0F, age / FADE_IN) * Math.min(1.0F, (b.ticks() - age) / FADE_OUT);
        // Дальний край — тише: не обрывается на границе.
        double dist = Math.sqrt(distSqr);
        a *= (float) Mth.clamp((RANGE - dist) / 2.0D, 0.0D, 1.0D);
        int alpha = Math.round(a * 255.0F);
        if (alpha < 8) {
            return;
        }
        Component line = Component.literal(" ").append(b.text()).append(" ");
        pose.pushPose();
        // Над головой, чуть выше места таблички с именем.
        pose.translate(0.0D, e.getBbHeight() + 0.6D, 0.0D);
        pose.mulPose(camera);
        pose.scale(SCALE, -SCALE, SCALE);
        Matrix4f m = pose.last().pose();
        int bg = Mth.clamp(Math.round(alpha * HudText.BACKDROP_K), 0, 255) << 24 | HudText.BACKDROP;
        float x = -font.width(line) / 2.0F;
        font.drawInBatch(line, x, 0.0F, alpha << 24 | TEXT, false, m, buffers, Font.DisplayMode.NORMAL, bg, LightTexture.FULL_BRIGHT);
        pose.popPose();
    }
}
