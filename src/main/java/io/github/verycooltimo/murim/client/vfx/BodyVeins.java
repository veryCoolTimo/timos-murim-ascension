package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Светящиеся жилы по телу, ветвящиеся и сгущающиеся к средоточию.
 *
 * <p>Написано под сцену создания даньтяня, а не заранее: примитив без потребителя
 * цементирует абстракцию наугад.
 *
 * <p><b>Почему не сеть меридианов с развилками.</b> Модель игрока шириной шестнадцать
 * пикселей не даёт прочитать сеть: тонкие ветви сливаются в кашу уже на расстоянии
 * нескольких блоков. Поэтому жил немного, они толстые у средоточия и утончаются к
 * концам — читается направление потока, а не топология (отвергнуто в плане MVP 2).
 *
 * <p>Плотность растёт к средоточию за счёт ДВУХ приёмов сразу: линии сходятся, и
 * их толщина растёт. Одной сходимости мало — на силуэте она теряется.
 */
public final class BodyVeins {

    /** Узлов на жилу: меньше даёт изломы, больше не видно в кадре. */
    private static final int STEPS = 10;

    /**
     * Рисует пучок жил от точки к средоточию.
     *
     * @param from     откуда растёт пучок — точка кости конечности
     * @param core     средоточие, куда стекаются жилы
     * @param count    сколько жил в пучке; на теле их единицы, не десятки
     * @param progress насколько жила проросла от 0 до 1; рост идёт ОТ КОНЕЧНОСТИ к ядру
     * @param width    полуширина жилы у средоточия
     * @param alpha    непрозрачность в самом плотном месте, не выше единицы
     * @param seed     семя беспорядка; постоянное, чтобы рисунок жил не мерцал
     */
    public static void draw(VertexConsumer consumer, PoseStack.Pose pose, Vec3 from, Vec3 core,
                            Vec3 cameraLocal, int count, float progress, double width,
                            float alpha, long seed, VfxColour colour) {
        float grown = Mth.clamp(progress, 0.0F, 1.0F);
        if (count <= 0 || alpha <= 0.0F || grown <= 0.0F) {
            return;
        }
        Vec3 axis = core.subtract(from);
        double span = axis.length();
        if (span < 1.0E-4D) {
            return;
        }
        // Ортонормированная пара поперёк жилы: по ней разносятся ветви пучка.
        Vec3 forward = axis.scale(1.0D / span);
        Vec3 reference = Math.abs(forward.y) > 0.95D ? new Vec3(1.0D, 0.0D, 0.0D)
                                                     : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 side = forward.cross(reference).normalize();
        Vec3 up = side.cross(forward).normalize();

        for (int vein = 0; vein < count; vein++) {
            float wander = Chaos.unit(vein, seed) - 0.5F;
            float twist = Chaos.spin(vein, seed, 0.35F);
            // Разброс у КОНЦА больше, чем у средоточия: так пучок собирается в точку.
            double spread = span * (0.14D + 0.16D * Math.abs(wander));

            Vec3[] points = new Vec3[STEPS + 1];
            double[] widths = new double[STEPS + 1];
            float[] alphas = new float[STEPS + 1];
            for (int i = 0; i <= STEPS; i++) {
                float t = i / (float) STEPS;
                // Отклонение гаснет к средоточию как (1-t)^1.4: линии именно СХОДЯТСЯ,
                // а не идут параллельно и обрываются.
                double away = Math.pow(1.0F - t, 1.4D) * spread;
                double angle = twist * t * Math.PI * 1.3D + wander * Math.PI * 2.0D;
                points[i] = from.add(axis.scale(t))
                        .add(side.scale(Math.cos(angle) * away))
                        .add(up.scale(Math.sin(angle) * away));

                // Рост идёт от конечности к ядру: пока grown мал, дальняя часть жилы
                // ещё не существует, а не просто прозрачна.
                float reached = Mth.clamp((grown - t) * 4.0F, 0.0F, 1.0F);
                // Толщина и яркость растут к средоточию — второй приём плотности.
                double thicken = 0.35D + 0.65D * t;
                widths[i] = width * thicken * reached;
                // Нижняя граница поднята: у конца жилы множитель 0.45 в паре с общей
                // яркостью сцены давал непрозрачность около 0.19 — на кадре этого нет.
                alphas[i] = alpha * (0.70F + 0.30F * t) * reached;
            }
            RibbonMesher.draw(consumer, pose, points, widths, alphas, cameraLocal,
                              colour.red(), colour.green(), colour.blue());
        }
    }

    private BodyVeins() {
    }
}
