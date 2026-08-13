package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Поток по меридианам: рисуется ли он и растёт ли снизу вверх. */
class MeridianFlowTest {

    private static final class Counter implements VertexConsumer {
        int visible;

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            if (alpha > 0) {
                visible++;
            }
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

    private static int drawn(float progress) {
        Counter counter = new Counter();
        List<Vec3[]> chain = List.of(
                new Vec3[] {new Vec3(0.2D, 0.0D, 0.0D), new Vec3(0.15D, 0.35D, 0.0D)},
                new Vec3[] {new Vec3(0.15D, 0.35D, 0.0D), new Vec3(0.0D, 0.75D, 0.0D)},
                new Vec3[] {new Vec3(0.0D, 0.75D, 0.0D), new Vec3(0.0D, 1.25D, 0.0D)});
        MeridianFlow.draw(counter, counter, new com.mojang.blaze3d.vertex.PoseStack().last(),
                          new Vec3(0.0D, 1.0D, -4.0D), chain, progress,
                          0.030D, 0.95F, VfxColour.VENOM, VfxColour.COLD_CORE);
        return counter.visible;
    }

    @Test
    @DisplayName("Поток рисуется, когда фронт прошёл часть пути")
    void flowProducesGeometry() {
        // На кадрах фаза меридианов дала 12 пикселей вместо сотен: тест отделяет
        // «примитив ничего не рисует» от «слой не виден на экране».
        assertTrue(drawn(0.6F) > 0, "поток не дал ни одной видимой вершины");
    }

    @Test
    @DisplayName("Чем дальше фронт, тем больше нарисовано")
    void flowGrows() {
        assertTrue(drawn(0.3F) < drawn(0.9F), "рост фронта не удлиняет канал");
    }
}
