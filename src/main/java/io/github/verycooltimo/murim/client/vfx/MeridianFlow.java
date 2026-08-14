package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Меридианы: ВЕТВЯЩАЯСЯ СЕТЬ по всему телу, сходящаяся к ядру.
 *
 * <p>Вторая переделка, и первая была сделана по неверно прочитанному замечанию. Автор
 * сказал: «все меридианы идут в одну сторону, не по всему телу». Я услышал только вторую
 * половину — «текут снизу вверх» — и сделал ОДНУ цепочку снизу вверх, то есть ту же одну
 * сторону, только другую. Референс при этом лежал в проекте, и я его не открыл.
 *
 * <p><b>Что на референсе.</b> Яркий вертикальный ствол по центру тела от живота к груди;
 * от него молниевидные ветви расходятся по торсу, плечам и рукам; каждая ветвь дважды-трижды
 * раздваивается и утончается к концам. Сеть покрывает тело целиком, а не идёт одной линией.
 * Ядро линий бело-горячее, края голубые.
 *
 * <p><b>Устройство.</b> Дерево строится от опорных костей: ствол ядро → грудь → голова,
 * ветви к плечам и кистям, ветви к коленям и ступням. От каждого отрезка отходят короткие
 * отростки, те — свои, до заданной глубины. Дерево детерминированное: рисунок жил не
 * должен мерцать между кадрами.
 *
 * <p>Поток идёт СНИЗУ ВВЕРХ по высоте: отрезок проявляется, когда фронт поднялся до его
 * нижнего конца. Так одновременно выполняются оба требования — сеть по всему телу и
 * направление течения.
 */
public final class MeridianFlow {

    /** Сколько раз ветвь делится. Три уже даёт кашу на модели в шестнадцать пикселей. */
    private static final int FORK_DEPTH = 2;

    /** Отростков от одного отрезка. */
    private static final int FORKS_PER_SEGMENT = 2;

    /** Отрезок сети с высотами концов: по ним считается фронт потока. */
    private record Vein(Vec3 from, Vec3 to, double width, float generation) {
    }

    /**
     * Рисует сеть меридианов.
     *
     * @param anchors пары костей — стволы и крупные ветви; {@code null} внутри допустимы
     * @param lowest  высота, с которой начинается поток
     * @param highest высота, на которой он заканчивается
     * @param front   фронт потока от 0 до 1
     */
    public static void draw(VertexConsumer consumer, PoseStack.Pose pose, Vec3 cameraLocal,
                            List<Vec3[]> anchors, Vec3 axis, double surface,
                            double lowest, double highest, float front,
                            double width, float alpha, long seed,
                            VfxColour line, VfxColour hot) {
        float reached = Mth.clamp(front, 0.0F, 1.0F);
        if (reached <= 0.0F || alpha <= 0.0F) {
            return;
        }
        double span = Math.max(0.1D, highest - lowest);
        double level = lowest + span * reached;

        List<Vein> veins = new ArrayList<>();
        int index = 0;
        for (Vec3[] pair : anchors) {
            if (pair[0] == null || pair[1] == null) {
                continue;
            }
            grow(veins, pair[0], pair[1], width, 0, seed + index * 977L);
            index++;
        }

        for (Vein vein : veins) {
            // Отрезок проявляется, когда поток поднялся до его НИЖНЕГО конца, и
            // разгорается, пока фронт идёт до верхнего. Так течение читается вверх,
            // а сеть при этом покрывает тело целиком.
            double bottom = Math.min(vein.from().y, vein.to().y);
            double top = Math.max(vein.from().y, vein.to().y);
            if (level < bottom) {
                continue;
            }
            // Разгорание вдвое быстрее длины отрезка: иначе большая часть сети всё время
            // висит в полусиле и до порога видимости не доходит.
            float lit = (float) Mth.clamp((level - bottom) / Math.max(0.02D, (top - bottom) * 0.5D),
                                          0.0D, 1.0D);
            // Иерархия сохраняется, но пол поднят: при множителе 0.35 дочерние ветви
            // уходили на треть яркости и пропадали. На референсе тускнеют КОНЦЫ, а
            // не целые поколения ветвей.
            float rank = 1.0F - vein.generation() * 0.20F;
            float glow = alpha * lit * rank;
            if (glow <= 0.02F) {
                continue;
            }
            double thickness = vein.width() * rank;
            int beads = Math.max(2, (int) Math.ceil(vein.from().distanceTo(vein.to())
                                                    / (thickness * 2.2D)));
            for (int i = 0; i <= beads; i++) {
                double t = i / (double) beads;
                Vec3 at = onSkin(vein.from().add(vein.to().subtract(vein.from()).scale(t)),
                                 axis, cameraLocal, surface);
                // Два слоя: мягкий ореол связывает точки в линию, плотное ядро даёт
                // бело-горячую середину, как на референсе.
                // Кончик ветви тоньше и тусклее её начала — так утончение читается
                // внутри линии, а не скачком между поколениями.
                float taper = 0.55F + 0.45F * (float) (1.0D - t);
                VfxDraw.billboard(consumer, pose, at, cameraLocal, thickness * 3.0D * taper,
                                  glow * 0.38F, line.red(), line.green(), line.blue());
                VfxDraw.billboard(consumer, pose, at, cameraLocal, thickness * 1.25D * taper,
                                  glow, hot.red(), hot.green(), hot.blue());
            }
        }
    }

    /**
     * Выносит точку на ПОВЕРХНОСТЬ тела.
     *
     * <p>Кости лежат по центру конечностей, то есть внутри модели. Рисуя по ним, мы
     * получаем тонкую линию по осевой — именно это и было на кадрах вместо сети. На
     * референсе жилы лежат на груди, плечах и руках, то есть на КОЖЕ.
     *
     * <p>Точка отталкивается от вертикальной оси тела наружу. Если она сама на оси
     * (грудь, живот), горизонтального направления нет — тогда её выносит к камере,
     * иначе жила осталась бы внутри торса.
     */
    private static Vec3 onSkin(Vec3 point, Vec3 axis, Vec3 cameraLocal, double surface) {
        double dx = point.x - axis.x;
        double dz = point.z - axis.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat > 1.0E-3D) {
            return point.add(dx / flat * surface, 0.0D, dz / flat * surface);
        }
        Vec3 toCamera = cameraLocal.subtract(point);
        double horizontal = Math.sqrt(toCamera.x * toCamera.x + toCamera.z * toCamera.z);
        if (horizontal < 1.0E-3D) {
            return point;
        }
        return point.add(toCamera.x / horizontal * surface, 0.0D,
                         toCamera.z / horizontal * surface);
    }

    /**
     * Рекурсивно выращивает ветвь и её отростки.
     *
     * <p>Отростки отходят не от концов, а от СЕРЕДИНЫ отрезка: ветвление на стыках
     * читается как звезда, а ветвление по длине — как жила.
     */
    private static void grow(List<Vein> out, Vec3 from, Vec3 to, double width,
                             int depth, long seed) {
        out.add(new Vein(from, to, width, depth));
        if (depth >= FORK_DEPTH) {
            return;
        }
        Vec3 axis = to.subtract(from);
        double length = axis.length();
        if (length < 0.05D) {
            return;
        }
        Vec3 forward = axis.scale(1.0D / length);
        Vec3 reference = Math.abs(forward.y) > 0.95D ? new Vec3(1.0D, 0.0D, 0.0D)
                                                     : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 side = forward.cross(reference).normalize();
        Vec3 up = side.cross(forward).normalize();

        for (int i = 0; i < FORKS_PER_SEGMENT; i++) {
            long branchSeed = seed * 31L + i * 131L + depth * 17L;
            float where = Chaos.range(i, branchSeed, 0.35F, 0.8F);
            float angle = Chaos.unit(i, branchSeed ^ 0x2BL) * (float) (Math.PI * 2.0D);
            float lean = Chaos.range(i, branchSeed ^ 0x3CL, 0.35F, 0.75F);
            Vec3 root = from.add(axis.scale(where));
            // Отросток уходит вбок и ВВЕРХ по течению: сеть тянется к ядру и к голове,
            // а не свисает вниз.
            Vec3 tip = root
                    .add(side.scale(Math.cos(angle) * length * lean * 0.55D))
                    .add(up.scale(Math.sin(angle) * length * lean * 0.45D))
                    .add(forward.scale(length * lean * 0.35D));
            grow(out, root, tip, width * 0.62D, depth + 1, branchSeed);
        }
    }

    private MeridianFlow() {
    }
}
