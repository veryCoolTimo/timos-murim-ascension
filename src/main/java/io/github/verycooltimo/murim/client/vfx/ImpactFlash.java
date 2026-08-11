package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Кадр удара: короткая вспышка, которая делает кульминацию кульминацией.
 *
 * <p>Живёт считанные тики, но несёт самую высокую яркость техники. Без неё удар читается
 * как продолжение подготовки: измерение показывало отношение пиков ниже единицы, то есть
 * сбор был ярче самого удара.
 *
 * <p><b>Вспышке положено быть круглой.</b> Проверки формы к ней не применяются: замер
 * равномерности на кадре вспышки давал 0.86 при 0.27–0.51 на устойчивых фазах, и это
 * свойство импакт-кадра, а не дефект (ADR-90, правило 04).
 *
 * <p>Яркость задаётся долей от единицы и не превышает её. Значения выше единицы
 * переполняются при записи цвета и дают провал яркости вместо роста — эта ошибка уже
 * один раз перевернула вспышку ладони, см. {@link VfxDraw}.
 */
public final class ImpactFlash {

    /**
     * Рисует вспышку.
     *
     * @param progress доля прожитой жизни вспышки от 0 (момент удара) до 1 (конец)
     * @param size     радиус ядра в блоках на пике
     * @param core     цвет ядра — самое яркое пятно кадра
     * @param halo     цвет расходящегося ореола
     */
    public static void draw(VertexConsumer consumer, PoseStack.Pose pose, Vec3 centre,
                            Vec3 cameraLocal, float progress, double size,
                            VfxColour core, VfxColour halo) {
        float life = Mth.clamp(progress, 0.0F, 1.0F);
        if (life >= 1.0F) {
            return;
        }
        // Яркость падает, размер растёт: после пика ни один параметр не растёт, кроме
        // радиуса рассеяния — этого требует правило 04.
        float fade = 1.0F - life;
        double spread = 1.0D + 2.8D * life;

        VfxDraw.billboard(consumer, pose, centre, cameraLocal,
                          size * spread, fade,
                          core.red(), core.green(), core.blue());
        VfxDraw.billboard(consumer, pose, centre, cameraLocal,
                          size * 1.8D * spread, fade * 0.44F,
                          halo.red(), halo.green(), halo.blue());
    }

    private ImpactFlash() {
    }
}
