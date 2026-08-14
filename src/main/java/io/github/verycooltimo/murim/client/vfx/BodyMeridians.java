package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Меридианы: МНОГО ТОНКИХ полосок строго по поверхности частей тела.
 *
 * <p>Третья переделка. Первые две провалились по разным причинам, и обе стоит помнить.
 *
 * <p>Первая вела прямые спицы от конечностей к центру — «в одну сторону, не по всему телу».
 * Вторая строила ветвящееся дерево от костей и выносила его наружу от вертикальной оси на
 * постоянную величину. Автор про неё: «в референсе сотни тонких полосок, идущих строго по
 * телу, а у нас случайные линии, даже выходят за грань тела». И это арифметически неизбежно:
 * полуглубина торса — 0.125 блока, а вынос стоял 0.16, то есть линии выталкивались наружу
 * гарантированно.
 *
 * <p><b>Устройство теперь.</b> Тело описано ЧАСТЯМИ, у каждой своя ось и свои полуразмеры.
 * По каждой части кладётся пучок тонких полосок: они лежат на её передней поверхности,
 * разнесены по ширине в пределах полуширины и слегка вьются вдоль оси. Ни одна не может
 * выйти за габарит части, потому что смещение считается в долях её собственных размеров.
 *
 * <p>Полосок много и они тонкие — это и создаёт вид сети. Десяток толстых линий читается
 * как провода, а не как меридианы.
 */
public final class BodyMeridians {

    /**
     * Часть тела: отрезок оси и её полуразмеры в блоках.
     *
     * @param from      начало оси
     * @param to        конец оси
     * @param halfWidth половина ширины части
     * @param halfDepth половина глубины части
     * @param lines     сколько полосок положить
     */
    public record Part(Vec3 from, Vec3 to, double halfWidth, double halfDepth, int lines) {
    }

    /**
     * Узлов на полоску вдоль оси.
     *
     * <p>Их должно быть столько, чтобы соседние точки ПЕРЕКРЫВАЛИСЬ. При девяти узлах
     * полоски читались как сетка штрихов — «как клавиатура», а не как линии. Шаг обязан
     * быть меньше размера точки, иначе линия распадается на пунктир.
     */
    private static final int STEPS = 30;

    /**
     * Рисует меридианы по частям тела.
     *
     * @param front  фронт потока от 0 до 1, поднимается по высоте
     * @param lowest высота начала потока
     * @param height высота, на которой поток заканчивается
     */
    public static void draw(VertexConsumer consumer, PoseStack.Pose pose, Vec3 cameraLocal,
                            List<Part> parts, double lowest, double height, float front,
                            double thickness, float alpha, long seed,
                            VfxColour line, VfxColour hot) {
        float reached = Mth.clamp(front, 0.0F, 1.0F);
        if (reached <= 0.0F || alpha <= 0.0F) {
            return;
        }
        double span = Math.max(0.1D, height - lowest);
        double level = lowest + span * reached;

        int partIndex = 0;
        for (Part part : parts) {
            if (part.from() == null || part.to() == null) {
                continue;
            }
            Vec3 axis = part.to().subtract(part.from());
            double length = axis.length();
            if (length < 1.0E-3D) {
                continue;
            }
            Vec3 along = axis.scale(1.0D / length);
            // Поперечная пара для КОНКРЕТНОЙ части: полоски раскладываются по её ширине
            // и прижимаются к её передней поверхности, а не к общей оси тела.
            Vec3 toCamera = cameraLocal.subtract(part.from().add(axis.scale(0.5D)));
            Vec3 depth = toCamera.subtract(along.scale(toCamera.dot(along)));
            if (depth.lengthSqr() < 1.0E-8D) {
                continue;
            }
            depth = depth.normalize();
            Vec3 side = along.cross(depth).normalize();

            for (int i = 0; i < part.lines(); i++) {
                long lineSeed = seed + partIndex * 7919L + i * 131L;
                // Полоска стоит на своей доле ширины. Крайние прижаты к краю части,
                // но не за него: смещение считается в долях полуширины.
                float across = part.lines() == 1 ? 0.0F
                        : (i / (float) (part.lines() - 1)) * 2.0F - 1.0F;
                across *= 0.92F;
                float wobbleSeed = Chaos.unit(i, lineSeed) - 0.5F;
                // Разброс яркости между полосками сильнее: одинаковые линии читаются
                // как растр, разные — как живая сеть.
                float bright = 0.35F + 0.65F * Chaos.unit(i, lineSeed ^ 0x9AL);

                for (int step = 0; step < STEPS; step++) {
                    double t = (step + 0.5D) / STEPS;
                    Vec3 at = part.from().add(axis.scale(t));
                    // Лёгкое виляние вдоль оси: прямые полоски читаются как штрихкод.
                    double wave = Math.sin(t * Math.PI * 2.6D + wobbleSeed * 6.0D) * 0.22D;
                    double lateral = (across + wave * 0.35D) * part.halfWidth();
                    at = at.add(side.scale(lateral)).add(depth.scale(part.halfDepth()));

                    if (at.y > level) {
                        continue;
                    }
                    // Разгорание по мере подъёма фронта над точкой.
                    float lit = (float) Mth.clamp((level - at.y) / 0.18D, 0.0D, 1.0D);
                    float glow = alpha * lit * bright;
                    if (glow <= 0.02F) {
                        continue;
                    }
                    VfxDraw.billboard(consumer, pose, at, cameraLocal, thickness * 2.4D,
                                      glow * 0.30F, line.red(), line.green(), line.blue());
                    VfxDraw.billboard(consumer, pose, at, cameraLocal, thickness,
                                      glow, hot.red(), hot.green(), hot.blue());
                }
            }
            partIndex++;
        }
    }

    private BodyMeridians() {
    }
}
