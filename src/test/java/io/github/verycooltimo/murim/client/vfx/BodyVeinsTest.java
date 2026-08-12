package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Жилы по телу: рисуются ли они вообще и растут ли от конечности к средоточию. */
class BodyVeinsTest {

    private static final class Counter implements VertexConsumer {
        int vertices;
        int visible;
        private int alpha;

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            vertices++;
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int a) {
            alpha = a;
            if (a > 0) {
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

    private static Counter draw(float progress) {
        Counter counter = new Counter();
        BodyVeins.draw(counter, new com.mojang.blaze3d.vertex.PoseStack().last(),
                       new Vec3(0.6D, 1.5D, 0.0D), new Vec3(0.0D, 1.0D, 0.0D),
                       new Vec3(0.0D, 1.4D, -4.0D), 3, progress, 0.028D, 0.8F, 42L,
                       VfxColour.VENOM);
        return counter;
    }

    @Test
    @DisplayName("При полном прорастании жилы рисуются")
    void grownVeinsProduceGeometry() {
        // Первая съёмка сцены дала по жилам НОЛЬ энергии при видимом ядре.
        // Тест отделяет «примитив ничего не рисует» от «слой не виден на экране».
        Counter counter = draw(1.0F);
        assertTrue(counter.vertices > 0, "жилы не дали ни одной вершины");
        assertTrue(counter.visible > 0, "все вершины прозрачные");
    }

    @Test
    @DisplayName("До начала роста жил нет")
    void ungrownVeinsAreEmpty() {
        assertEquals(0, draw(0.0F).visible);
    }

    @Test
    @DisplayName("Наполовину проросшая жила короче полной")
    void growthIsProgressive() {
        assertTrue(draw(0.5F).visible < draw(1.0F).visible,
                   "рост не влияет на длину жилы");
    }
}
