package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Запись геометрии эффектов: единственное место, где мод пишет вершины.
 *
 * <p>До выделения этого класса запись вершины была скопирована в пяти рендерерах, и
 * ограничение непрозрачности стояло ровно в одном из них. Остальные молча переполнялись:
 * {@code setColor(float…)} в 1.21.1 считает {@code (int)(a * 255)} без всякого ограничения
 * (проверено дизассемблером: {@code fmul}, {@code f2i}), а дальше значение пишется байтом.
 * Непрозрачность 1.6 даёт 408 и после усечения 152, то есть 0.60 — эффект не становится
 * ярче единицы, а <b>скачком темнеет</b>. Так была перевёрнута вспышка контакта у ладони:
 * самый яркий кадр техники выходил тусклым, а пик приходился на середину затухания.
 *
 * <p>Дефект пережил даже точечную починку: ленты выброса рисуются через
 * {@link RibbonMesher}, у которого была своя копия записи вершины, и правка в рендерере
 * ладони её не касалась. Поэтому запись вершины существует ровно в одном экземпляре, а
 * ограничение стоит внутри неё, а не на вызывающей стороне.
 *
 * <p>API: {@code com.mojang.blaze3d.vertex.VertexConsumer#setColor(float,float,float,float)}
 *
 * <p>Формат вершин {@code DefaultVertexFormat.NEW_ENTITY} требует ВСЕ элементы: цвет,
 * текстурные координаты, перекрытие, свет и нормаль. Пропуск любого даёт мусор в буфере,
 * а не ошибку компиляции — ещё одна причина держать запись в одном месте.
 */
public final class VfxDraw {

    /** Полная яркость: эффекты не участвуют в освещении сцены. */
    private static final int FULL_BRIGHT = 0x00F000F0;

    /**
     * Одна вершина.
     *
     * <p>Цвет и непрозрачность ограничиваются здесь — см. пояснение в описании класса.
     */
    public static void vertex(VertexConsumer consumer, PoseStack.Pose pose, Vec3 position,
                              Vec3 normal, float u, float v, float alpha,
                              float red, float green, float blue) {
        consumer.addVertex(pose.pose(), (float) position.x, (float) position.y, (float) position.z)
                .setColor(Mth.clamp(red, 0.0F, 1.0F), Mth.clamp(green, 0.0F, 1.0F),
                          Mth.clamp(blue, 0.0F, 1.0F), Mth.clamp(alpha, 0.0F, 1.0F))
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT)
                .setNormal(pose, (float) normal.x, (float) normal.y, (float) normal.z);
    }

    /**
     * Четырёхугольник вдоль отрезка, развёрнутый шириной к камере.
     *
     * <p>Ширина откладывается перпендикулярно И отрезку, И направлению на камеру, поэтому
     * лента не пропадает при взгляде с ребра. Если отрезок смотрит точно в камеру, ширина
     * не определена — такой четырёхугольник не рисуется вовсе.
     *
     * @param cameraLocal положение камеры в той же системе координат, что и точки
     */
    public static void segment(VertexConsumer consumer, PoseStack.Pose pose, Vec3 from, Vec3 to,
                               Vec3 cameraLocal, double halfWidth, float alpha,
                               float red, float green, float blue) {
        if (alpha <= 0.0F || halfWidth <= 0.0D) {
            return;
        }
        Vec3 axis = to.subtract(from);
        if (axis.lengthSqr() < 1.0E-8D) {
            return;
        }
        Vec3 mid = from.add(to).scale(0.5D);
        Vec3 toCamera = cameraLocal.subtract(mid);
        if (toCamera.lengthSqr() < 1.0E-8D) {
            return;
        }
        toCamera = toCamera.normalize();
        Vec3 side = axis.normalize().cross(toCamera);
        if (side.lengthSqr() < 1.0E-6D) {
            return;
        }
        Vec3 offset = side.normalize().scale(halfWidth);

        vertex(consumer, pose, from.subtract(offset), toCamera, 0.0F, 0.0F, alpha, red, green, blue);
        vertex(consumer, pose, to.subtract(offset), toCamera, 1.0F, 0.0F, alpha, red, green, blue);
        vertex(consumer, pose, to.add(offset), toCamera, 1.0F, 1.0F, alpha, red, green, blue);
        vertex(consumer, pose, from.add(offset), toCamera, 0.0F, 1.0F, alpha, red, green, blue);
    }

    /**
     * Квадрат, всегда обращённый к камере.
     *
     * <p>Опорная ось выбирается по вертикали, а при взгляде почти вертикально сверху или
     * снизу — по горизонтали: иначе векторное произведение вырождается и квадрат схлопывается.
     */
    public static void billboard(VertexConsumer consumer, PoseStack.Pose pose, Vec3 centre,
                                 Vec3 cameraLocal, double size, float alpha,
                                 float red, float green, float blue) {
        if (alpha <= 0.0F || size <= 0.0D) {
            return;
        }
        Vec3 toCamera = cameraLocal.subtract(centre);
        if (toCamera.lengthSqr() < 1.0E-8D) {
            return;
        }
        Vec3 forward = toCamera.normalize();
        Vec3 reference = Math.abs(forward.y) > 0.95D ? new Vec3(1.0D, 0.0D, 0.0D)
                                                     : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 right = forward.cross(reference).normalize().scale(size);
        Vec3 up = right.normalize().cross(forward).normalize().scale(size);

        vertex(consumer, pose, centre.subtract(right).subtract(up), forward,
               0.0F, 0.0F, alpha, red, green, blue);
        vertex(consumer, pose, centre.subtract(right).add(up), forward,
               0.0F, 1.0F, alpha, red, green, blue);
        vertex(consumer, pose, centre.add(right).add(up), forward,
               1.0F, 1.0F, alpha, red, green, blue);
        vertex(consumer, pose, centre.add(right).subtract(up), forward,
               1.0F, 0.0F, alpha, red, green, blue);
    }

    private VfxDraw() {
    }
}
