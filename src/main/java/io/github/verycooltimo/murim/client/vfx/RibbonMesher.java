package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;

/**
 * Сшивка ленты по ломаной.
 *
 * <p><b>Что было не так.</b> Перпендикуляр считался отдельно для каждого квада, по его
 * собственной оси. На прямом участке это незаметно, а на витке соседние квады оказываются
 * в разных плоскостях: между ними возникает щель или самопересечение, и лента распадается
 * на отдельные палки. Ровно это и делало спираль похожей на набор линий.
 *
 * <p>Здесь перпендикуляр считается ОДИН РАЗ НА УЗЕЛ по направлению соседних сегментов,
 * поэтому кромка непрерывна. Добавлены две поправки, без которых сшивка всё равно ломается:
 * компенсация на изломе, иначе лента визуально худеет на поворотах, и защита от перекрута,
 * иначе на спирали кромка переворачивается и лента складывается бабочкой.
 */
public final class RibbonMesher {

    /**
     * Минимальная полуширина в блоках.
     *
     * <p>Субпиксельная лента мерцает через кадр: на одном кадре пиксель попадает в растр,
     * на другом нет. Глаз читает это как дрожание неизвестной природы.
     */
    private static final double MIN_HALF_WIDTH = 0.012D;

    /** Предел компенсации на изломе. Без него острый угол раздувает ленту до бесконечности. */
    private static final double MAX_MITER = 3.0D;

    /**
     * Рисует ленту по ломаной.
     *
     * @param points     узлы ломаной в локальных координатах, минимум два
     * @param halfWidths полуширина в каждом узле, той же длины
     * @param alphas     непрозрачность в каждом узле, той же длины
     * @param toCamera   точка камеры в тех же координатах: лента разворачивается к зрителю
     */
    public static void draw(VertexConsumer consumer, PoseStack.Pose pose,
                            Vec3[] points, double[] halfWidths, float[] alphas,
                            Vec3 toCamera, float red, float green, float blue) {
        int count = points.length;
        if (count < 2 || halfWidths.length != count || alphas.length != count) {
            return;
        }

        Vec3[] sides = new Vec3[count];
        Vec3[] normals = new Vec3[count];
        Vec3 previousSide = null;

        for (int i = 0; i < count; i++) {
            // Касательная по СОСЕДЯМ, а не по своему сегменту: так кромка непрерывна.
            Vec3 before = points[Math.max(0, i - 1)];
            Vec3 after = points[Math.min(count - 1, i + 1)];
            Vec3 tangent = after.subtract(before);
            if (tangent.lengthSqr() < 1.0E-10D) {
                tangent = new Vec3(0.0D, 1.0D, 0.0D);
            }
            tangent = tangent.normalize();

            Vec3 view = toCamera.subtract(points[i]);
            if (view.lengthSqr() < 1.0E-10D) {
                view = new Vec3(0.0D, 0.0D, 1.0D);
            }
            view = view.normalize();

            Vec3 side = tangent.cross(view);
            if (side.lengthSqr() < 1.0E-6D) {
                // Взгляд почти вдоль ленты: направление ширины не определено. Берём
                // предыдущее, иначе кромка скачком развернётся на пол-оборота.
                side = previousSide != null ? previousSide : new Vec3(1.0D, 0.0D, 0.0D);
            } else {
                side = side.normalize();
            }

            // Защита от перекрута: если кромка развернулась относительно соседней,
            // возвращаем её обратно. Без этого спираль складывается бабочкой.
            if (previousSide != null && side.dot(previousSide) < 0.0D) {
                side = side.scale(-1.0D);
            }

            // Компенсация на изломе: на повороте проекция ширины уменьшается на косинус
            // половины угла, и лента визуально худеет именно там, где должна быть плотной.
            double miter = 1.0D;
            if (i > 0 && i < count - 1) {
                Vec3 in = points[i].subtract(points[i - 1]);
                Vec3 out = points[i + 1].subtract(points[i]);
                if (in.lengthSqr() > 1.0E-10D && out.lengthSqr() > 1.0E-10D) {
                    double cosHalf = Math.sqrt(Math.max(0.0D,
                            (1.0D + in.normalize().dot(out.normalize())) * 0.5D));
                    miter = cosHalf > 1.0D / MAX_MITER ? 1.0D / cosHalf : MAX_MITER;
                }
            }

            sides[i] = side.scale(Math.max(MIN_HALF_WIDTH, halfWidths[i]) * miter);
            normals[i] = side.cross(tangent).normalize();
            previousSide = side;
        }

        for (int i = 0; i < count - 1; i++) {
            float u0 = i / (float) (count - 1);
            float u1 = (i + 1) / (float) (count - 1);
            quad(consumer, pose,
                 points[i].subtract(sides[i]), points[i].add(sides[i]),
                 points[i + 1].add(sides[i + 1]), points[i + 1].subtract(sides[i + 1]),
                 normals[i], normals[i + 1], alphas[i], alphas[i + 1], u0, u1,
                 red, green, blue);
        }
    }

    private static void quad(VertexConsumer consumer, PoseStack.Pose pose,
                             Vec3 a, Vec3 b, Vec3 c, Vec3 d,
                             Vec3 normalA, Vec3 normalB, float alphaA, float alphaB,
                             float u0, float u1, float red, float green, float blue) {
        VfxDraw.vertex(consumer, pose, a, normalA, u0, 0.0F, alphaA, red, green, blue);
        VfxDraw.vertex(consumer, pose, b, normalA, u0, 1.0F, alphaA, red, green, blue);
        VfxDraw.vertex(consumer, pose, c, normalB, u1, 1.0F, alphaB, red, green, blue);
        VfxDraw.vertex(consumer, pose, d, normalB, u1, 0.0F, alphaB, red, green, blue);
    }

    private RibbonMesher() {
    }
}
