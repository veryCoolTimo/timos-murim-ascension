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
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.Iterator;
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
@EventBusSubscriber(modid = io.github.verycooltimo.murim.MurimMod.MODID, value = Dist.CLIENT)
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

    /**
     * Все пузыри кадра — в мировой стадии после частиц (rules/04), а не из рендера сущности: рендер сущности зовут и вне
     * мира (кадр стенда 05.10 — вторая, огромная копия строки вверху экрана).
     * API: reference/neoforge-src/net/neoforged/neoforge/client/event/RenderLevelStageEvent.java#Stage.AFTER_PARTICLES
     */
    @SubscribeEvent
    static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || BUBBLES.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        Quaternionf rot = event.getCamera().rotation();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        long now = mc.level.getGameTime();
        for (Iterator<Map.Entry<Integer, Bubble>> it = BUBBLES.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Bubble> e = it.next();
            Bubble b = e.getValue();
            float age = now - b.start() + partial;
            if (age > b.ticks() || !(mc.level.getEntity(e.getKey()) instanceof SectDisciple d) || !d.isAlive()) {
                it.remove();
                continue;
            }
            Vec3 at = d.getPosition(partial).add(0.0D, d.getBbHeight() + 0.6D, 0.0D);
            draw(b, age, at.subtract(cam), rot, pose, buffers, mc.font);
        }
        buffers.endBatch();
    }

    private static void draw(Bubble b, float age, Vec3 rel, Quaternionf camera, PoseStack pose, MultiBufferSource buffers, Font font) {
        double dist = rel.length();
        if (dist > RANGE) {
            return;
        }
        float a = Math.min(1.0F, age / FADE_IN) * Math.min(1.0F, (b.ticks() - age) / FADE_OUT);
        // Дальний край — тише: не обрывается на границе.
        a *= (float) Mth.clamp((RANGE - dist) / 2.0D, 0.0D, 1.0D);
        // Вплотную к камере строка во весь экран — у самого лица пузырь гаснет.
        a *= (float) Mth.clamp((dist - 1.5D) / 1.0D, 0.0D, 1.0D);
        int alpha = Math.round(a * 255.0F);
        if (alpha < 8) {
            return;
        }
        Component line = Component.literal(" ").append(b.text()).append(" ");
        pose.pushPose();
        try {
            pose.translate(rel.x, rel.y, rel.z);
            pose.mulPose(camera);
            // Дальше — чуть крупнее (до ×1,6 на краю), чтобы строка читалась и на 10 блоках.
            float k = SCALE * (float) Mth.clamp(dist / 7.0D, 1.0D, 1.6D);
            pose.scale(k, -k, k);
            Matrix4f m = pose.last().pose();
            int bg = Mth.clamp(Math.round(alpha * HudText.BACKDROP_K), 0, 255) << 24 | HudText.BACKDROP;
            float x = -font.width(line) / 2.0F;
            // Подложка Font ложится на 0,01 ближе букв и с проверкой глубины съедала часть букв (кадр стенда 05.10: «После
            // строя» без «всё вкусно.»). Два прохода: подложка, затем буквы чуть ближе к камере.
            font.drawInBatch(line, x, 0.0F, alpha << 24 | TEXT, false, m, buffers, Font.DisplayMode.NORMAL, bg, LightTexture.FULL_BRIGHT);
            pose.translate(0.0D, 0.0D, 0.03D);
            font.drawInBatch(line, x, 0.0F, alpha << 24 | TEXT, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0,
                    LightTexture.FULL_BRIGHT);
            // Хвостик вниз — к говорящему (codex 05.10: в толпе неясно, кто говорит).
            Component tail = Component.literal("\u25BC");
            pose.translate(-font.width(tail) * 0.3F, 9.5F, 0.0F);
            pose.scale(0.6F, 0.6F, 0.6F);
            font.drawInBatch(tail, 0.0F, 0.0F, Mth.clamp(Math.round(alpha * HudText.BACKDROP_K), 0, 255) << 24 | HudText.BACKDROP, false,
                    pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        } finally {
            pose.popPose();
        }
    }
}
