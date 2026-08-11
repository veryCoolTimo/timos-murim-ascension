package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Запись вершины: ограничение цвета и непрозрачности.
 *
 * <p>Тест существует из-за конкретного дефекта. {@code setColor(float…)} считает
 * {@code (int)(a * 255)} без ограничения, а дальше значение пишется байтом: непрозрачность
 * 1.6 даёт 408 и после усечения 152, то есть 0.60. Эффект не становится ярче единицы, а
 * скачком темнеет. Так была перевёрнута вспышка контакта у ладони — самый яркий кадр
 * техники выходил тусклым.
 *
 * <p>Точечная починка этого не закрыла: ленты рисуются через {@link RibbonMesher}, у
 * которого была своя копия записи вершины. Поэтому проверяется именно общий примитив.
 */
class VfxDrawTest {

    /** Записывает переданные каналы, не трогая ничего больше. */
    private static final class Recorder implements VertexConsumer {
        private final List<int[]> colours = new ArrayList<>();

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            colours.add(new int[] {red, green, blue, alpha});
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    }

    private static Recorder writeAlpha(float alpha) {
        Recorder recorder = new Recorder();
        VfxDraw.vertex(recorder, new com.mojang.blaze3d.vertex.PoseStack().last(),
                       new net.minecraft.world.phys.Vec3(0.0D, 0.0D, 0.0D),
                       new net.minecraft.world.phys.Vec3(0.0D, 0.0D, 1.0D),
                       0.0F, 0.0F, alpha, 1.0F, 1.0F, 1.0F);
        return recorder;
    }

    @Test
    @DisplayName("Непрозрачность выше единицы даёт максимум, а не переполнение")
    void alphaAboveOneSaturates() {
        // Без ограничения 1.6 превращается в 152 из 255 — эффект темнеет там,
        // где по замыслу он ярче всего.
        assertEquals(255, writeAlpha(1.6F).colours.get(0)[3]);
        assertEquals(255, writeAlpha(4.0F).colours.get(0)[3]);
    }

    @Test
    @DisplayName("Непрозрачность растёт монотонно вплоть до предела")
    void alphaIsMonotonic() {
        // Главный симптом дефекта был не в самом усечении, а в НЕМОНОТОННОСТИ:
        // яркость росла, потом обрушивалась и снова росла.
        int previous = -1;
        for (float alpha = 0.0F; alpha <= 2.0F; alpha += 0.05F) {
            int written = writeAlpha(alpha).colours.get(0)[3];
            assertTrue(written >= previous,
                       "непрозрачность упала при росте аргумента: " + alpha);
            previous = written;
        }
        assertEquals(255, previous);
    }

    @Test
    @DisplayName("Отрицательная непрозрачность даёт ноль, а не мусор")
    void negativeAlphaClampsToZero() {
        assertEquals(0, writeAlpha(-0.5F).colours.get(0)[3]);
    }

    @Test
    @DisplayName("Цветовые каналы ограничиваются так же, как непрозрачность")
    void colourChannelsClamp() {
        // Аддитивные слои складываются, и цвет выше единицы возникает так же
        // естественно, как непрозрачность.
        Recorder recorder = new Recorder();
        VfxDraw.vertex(recorder, new com.mojang.blaze3d.vertex.PoseStack().last(),
                       new net.minecraft.world.phys.Vec3(0.0D, 0.0D, 0.0D),
                       new net.minecraft.world.phys.Vec3(0.0D, 0.0D, 1.0D),
                       0.0F, 0.0F, 1.0F, 2.5F, -1.0F, 0.5F);
        int[] written = recorder.colours.get(0);
        assertEquals(255, written[0]);
        assertEquals(0, written[1]);
        assertEquals(127, written[2]);
    }
}
