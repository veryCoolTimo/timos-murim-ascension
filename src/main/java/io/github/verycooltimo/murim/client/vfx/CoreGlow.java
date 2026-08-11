package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;

/**
 * Средоточие силы в точке: мягкий ореол вокруг плотного ядра.
 *
 * <p>Извлечено из сбора ядовитой ладони, а не придумано заранее. Три слоя разного
 * масштаба: широкий полупрозрачный туман задаёт объём, средний ореол — цвет, маленькое
 * холодное ядро — яркость. Плотность набирается разномасштабностью, а не количеством
 * одинаковых точек (ADR-85).
 *
 * <p><b>Яркость обязана расти от нуля.</b> У прежней версии у каждого слоя было
 * постоянное слагаемое, не зависящее от заряда: эффект вспыхивал на полную с первого
 * тика и сорок тиков стоял ровно. Измерение показало 74% энергии уже на четвёртом тике
 * при заряде 0.125 — то есть фазы у эффекта не было вовсе, и кульминация выходила
 * слабее подготовки. Поэтому {@code intensity} умножает КАЖДЫЙ слой целиком.
 */
public final class CoreGlow {

    /** Клубов тумана: пять достаточно, чтобы масса не читалась как один шар. */
    private static final int PUFFS = 5;

    /** Золотое сечение в радианах: клубы не выстраиваются в узор. */
    private static final double GOLDEN_ANGLE = 2.399D;

    /**
     * Рисует средоточие.
     *
     * <p>Вызывающий обязан взять буфер сам и закрыть его после: общий источник буферов
     * строит только один тип за раз, и запрос второго молча закрывает первый.
     *
     * @param centre    точка средоточия в системе координат вызывающего
     * @param age       возраст эффекта в тиках; задаёт медленный дрейф клубов
     * @param radius    радиус видимой массы в блоках
     * @param intensity общая сила от 0 до 1; ноль означает полное отсутствие свечения
     * @param halo      цвет широкой массы
     * @param core      цвет плотного ядра
     */
    public static void draw(VertexConsumer consumer, PoseStack.Pose pose, Vec3 centre,
                            Vec3 cameraLocal, float age, double radius, float intensity,
                            VfxColour halo, VfxColour core) {
        if (intensity <= 0.0F || radius <= 0.0D) {
            return;
        }
        // Ядро набирает силу кубически: рост должен читаться как всплеск, а не как
        // равномерное разгорание.
        float dense = intensity * intensity * intensity;

        for (int i = 0; i < PUFFS; i++) {
            double angle = i * GOLDEN_ANGLE + age * 0.02D;
            double drift = radius * (0.35D + 0.23D * Math.sin(age * 0.05D + i));
            Vec3 puff = centre.add(new Vec3(
                    Math.cos(angle) * drift,
                    Math.sin(angle) * drift * 0.7D,
                    Math.sin(angle * 1.3D) * drift));
            VfxDraw.billboard(consumer, pose, puff, cameraLocal,
                              radius * (1.0D + 1.3D * dense),
                              intensity * 0.075F + 0.063F * dense,
                              halo.red(), halo.green(), halo.blue());
        }

        VfxDraw.billboard(consumer, pose, centre, cameraLocal,
                          radius * (0.58D + 0.92D * dense),
                          intensity * 0.24F + 0.234F * dense,
                          halo.red(), halo.green(), halo.blue());

        // Холодного мало и оно плотное: на референсах белое занимает сердцевину,
        // а не всю площадь. Первая версия делала наоборот — вспышка на весь кадр.
        VfxDraw.billboard(consumer, pose, centre, cameraLocal,
                          radius * (0.21D + 0.38D * dense),
                          intensity * 0.50F + 0.55F * dense,
                          core.red(), core.green(), core.blue());
    }

    private CoreGlow() {
    }
}
