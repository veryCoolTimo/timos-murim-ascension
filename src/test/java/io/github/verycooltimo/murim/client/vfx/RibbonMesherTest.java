package io.github.verycooltimo.murim.client.vfx;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Сшивка ленты. Проверяется устойчивость к вырожденным входам: рисование само по себе
 * требует буфера вершин и в юнит-тесте недоступно, но обвалиться на плохих данных
 * мешер не должен ни при каких условиях.
 */
class RibbonMesherTest {

    @Test
    @DisplayName("Пустые и слишком короткие ломаные не роняют мешер")
    void degenerateInputsSafe() {
        assertDoesNotThrow(() -> RibbonMesher.draw(null, null,
                new Vec3[0], new double[0], new float[0], Vec3.ZERO, 1, 1, 1));
        assertDoesNotThrow(() -> RibbonMesher.draw(null, null,
                new Vec3[]{Vec3.ZERO}, new double[]{0.1}, new float[]{1.0F},
                Vec3.ZERO, 1, 1, 1));
    }

    @Test
    @DisplayName("Рассогласованные массивы отбрасываются, а не читаются за границей")
    void mismatchedArraysSafe() {
        // Иначе опечатка в вызывающем коде дала бы выход за границу массива
        // в горячем пути рендера — краш посреди боя.
        assertDoesNotThrow(() -> RibbonMesher.draw(null, null,
                new Vec3[]{Vec3.ZERO, new Vec3(1, 0, 0)},
                new double[]{0.1},
                new float[]{1.0F, 1.0F}, Vec3.ZERO, 1, 1, 1));
    }
}
