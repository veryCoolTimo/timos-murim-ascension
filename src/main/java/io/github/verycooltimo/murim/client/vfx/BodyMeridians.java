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
        draw(consumer, pose, cameraLocal, parts, lowest, height, front, thickness, alpha, seed,
             line, hot, null);
    }

    /**
     * То же, но полоски лежат на ПЕРЕДНЕЙ поверхности тела, а не на стороне, обращённой к
     * камере. Раскладка по камере не приклеена к телу: персонаж поворачивался, а жилы
     * оставались развёрнутыми к зрителю (замечание автора 30.09).
     *
     * @param facing направление «вперёд» тела; {@code null} — раскладка по камере
     */
    public static void draw(VertexConsumer consumer, PoseStack.Pose pose, Vec3 cameraLocal,
                            List<Part> parts, double lowest, double height, float front,
                            double thickness, float alpha, long seed,
                            VfxColour line, VfxColour hot, Vec3 facing) {
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
            Vec3 toCamera = facing != null ? facing
                    : cameraLocal.subtract(part.from().add(axis.scale(0.5D)));
            Vec3 depth = toCamera.subtract(along.scale(toCamera.dot(along)));
            if (depth.lengthSqr() < 1.0E-8D) {
                continue;
            }
            depth = depth.normalize();
            Vec3 side = along.cross(depth).normalize();

            for (int i = 0; i < part.lines(); i++) {
                long lineSeed = seed + partIndex * 7919L + i * 131L;
                // Положение полоски по ширине — СЛУЧАЙНОЕ, а не по равной сетке.
                // Равномерная раскладка давала частые параллельные полосы, которые
                // второе мнение назвало шумом и «светящимся нагрудником»: силуэт
                // тела в них пропадал.
                float across = (Chaos.unit(i, lineSeed ^ 0x4DL) - 0.5F) * 1.7F;
                float wobbleSeed = Chaos.unit(i, lineSeed) - 0.5F;
                // Полоска идёт НАИСКОСОК: чистая вертикаль читается как штриховка.
                float drift = (Chaos.unit(i, lineSeed ^ 0x71L) - 0.5F) * 0.9F;
                float bright = 0.30F + 0.70F * Chaos.unit(i, lineSeed ^ 0x9AL);
                // Толщина у каждой полоски своя: одинаковые линии читаются как штриховка.
                double own = thickness * Chaos.range(i, lineSeed ^ 0xE9L, 0.6F, 1.5F);
                // Ветвь: у части линий есть развилка в верхней половине.
                boolean forks = Chaos.unit(i, lineSeed ^ 0xB3L) > 0.45F;
                float forkAt = Chaos.range(i, lineSeed ^ 0xC5L, 0.45F, 0.7F);
                float forkAway = (Chaos.unit(i, lineSeed ^ 0xD7L) - 0.5F) * 1.6F;

                strip(consumer, pose, cameraLocal, part, axis, side, depth, level,
                      across, drift, wobbleSeed, bright, 0.0F, 1.0F,
                      thickness, alpha, line, hot);
                if (forks) {
                    // Развилка уходит вбок от родителя и живёт до конца части:
                    // именно Y-образные ветви к ключицам и плечам отличают сеть
                    // меридианов от штриховки.
                    strip(consumer, pose, cameraLocal, part, axis, side, depth, level,
                          across + forkAway, drift + forkAway * 0.6F, wobbleSeed + 0.4F,
                          bright * 0.75F, forkAt, 1.0F,
                          own * 0.7D, alpha, line, hot);
                }
            }
            partIndex++;
        }
    }

    /**
     * Одна полоска на части тела.
     *
     * <p>Яркость падает от центра части к её краю: второе мнение прямо указало, что в
     * референсе центральный поток ярче боковых, а равномерная яркость превращает тело
     * в светящийся прямоугольник.
     *
     * @param from доля длины части, с которой полоска начинается
     */
    private static void strip(VertexConsumer consumer, PoseStack.Pose pose, Vec3 cameraLocal,
                              Part part, Vec3 axis, Vec3 side, Vec3 depth, double level,
                              float across, float drift, float wobbleSeed, float bright,
                              float from, float to, double thickness, float alpha,
                              VfxColour line, VfxColour hot) {
        for (int step = 0; step < STEPS; step++) {
            double t = from + (to - from) * ((step + 0.5D) / STEPS);
            Vec3 at = part.from().add(axis.scale(t));
            double wave = Math.sin(t * Math.PI * 2.2D + wobbleSeed * 6.0D) * 0.18D;
            // Наклон копится вдоль полоски, поэтому она идёт по диагонали.
            double lateral = (across + drift * t + wave) * part.halfWidth();
            lateral = Mth.clamp(lateral, -part.halfWidth(), part.halfWidth());
            at = at.add(side.scale(lateral)).add(depth.scale(part.halfDepth()));

            if (at.y > level) {
                continue;
            }
            float lit = (float) Mth.clamp((level - at.y) / 0.18D, 0.0D, 1.0D);
            // Концы полоски РАСТВОРЯЮТСЯ. Второе мнение: «линии обрываются слишком
            // резко, мало растворения в свечении» — обрубленный конец читается как
            // нарисованный штрих, а не как канал под кожей.
            float ends = Mth.clamp((float) Math.min(t - from, to - t) / 0.18F, 0.0F, 1.0F);
            // Спад к краю части: центр ярче, бока слабее.
            float middle = 1.0F - 0.55F * Math.min(1.0F, Math.abs((float) lateral)
                    / (float) Math.max(1.0E-4D, part.halfWidth()));
            float glow = alpha * lit * bright * middle * ends;
            if (glow <= 0.02F) {
                continue;
            }
            VfxDraw.billboard(consumer, pose, at, cameraLocal, thickness * 3.0D,
                              glow * 0.22F, line.red(), line.green(), line.blue());
            VfxDraw.billboard(consumer, pose, at, cameraLocal, thickness,
                              glow * 0.85F, hot.red(), hot.green(), hot.blue());
        }
    }

    private BodyMeridians() {
    }
}
