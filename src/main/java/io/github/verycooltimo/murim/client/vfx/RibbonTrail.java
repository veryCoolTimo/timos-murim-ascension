package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;

/**
 * Веер закрученных лент вдоль направления.
 *
 * <p>Извлечено из выброса ядовитой ладони. Ленты расходятся веером, каждая закручивается
 * по своему радиусу и в свою сторону; часть идёт против общего вращения. Беспорядок
 * детерминированный ({@link Chaos}): силуэт техники должен запоминаться игроком и
 * воспроизводиться при отладке.
 *
 * <p><b>Раствор веера — не украшение, а измеряемое свойство.</b> Узкий сектор сжимает
 * энергию в клин: замер спереди дал 0.86 при пределе 0.62. Полный круг даёт ровное
 * кольцо — тот самый «ураган в руке». Нужен широкий, но смещённый веер.
 */
public final class RibbonTrail {

    /** Узлов на ленту. Меньше — видны изломы, больше — лишние вершины без разницы в кадре. */
    private static final int STEPS = 12;

    /**
     * Рисует веер лент.
     *
     * @param origin     точка, из которой растут ленты
     * @param direction  единичное направление веера
     * @param count      число лент; на референсах их четыре-восемь, не десятки
     * @param length     полная длина самой длинной ленты в блоках
     * @param width      полуширина ленты в самом широком месте
     * @param alpha      непрозрачность в самом плотном месте, не выше единицы
     * @param spread     раствор веера в радианах; около трёх — широкий, но не круг
     * @param bearing    куда смотрит середина веера, в радианах
     * @param seed       семя беспорядка; постоянное для техники
     */
    public static void draw(VertexConsumer consumer, PoseStack.Pose pose, Vec3 origin,
                            Vec3 direction, Vec3 cameraLocal, int count, double length,
                            double width, float alpha, float spread, float bearing,
                            long seed, VfxColour colour) {
        if (count <= 0 || alpha <= 0.0F || length <= 0.0D) {
            return;
        }
        for (int strand = 0; strand < count; strand++) {
            float phase = bearing + (Chaos.unit(strand, seed) - 0.5F) * spread;
            float spin = Chaos.spin(strand, seed, 0.3F);
            // Плотность растёт к средоточию: ленты, тяготеющие к нему, идут по меньшему
            // радиусу и держатся ближе к точке выхода — как на референсе.
            float bias = Chaos.densityBias(strand, count, seed);
            // Оборотов ВОКРУГ ОСИ должно быть меньше половины.
            //
            // Здесь стояло 1.2..2.4 — то есть прядь обходила ось выброса больше чем на
            // полный круг. С фронта, то есть глядя вдоль оси, такая прядь рисует
            // окружность, и весь веер читается зелёным кольцом-порталом. Это и был
            // источник «кольца», а не текстура и не пересвет: два прежних объяснения
            // я проверил и обе версии отпали, потратив по прогону каждая.
            float turns = Chaos.range(strand, seed ^ 0x1FL, 0.12F, 0.38F);
            float peak = Chaos.range(strand, seed ^ 0x2FL, 0.68F, 0.9F);
            double maxRadius = width * (0.95D + 3.2D * bias);
            double reach = length * (0.55D + 0.45D * bias);

            Vec3[] points = new Vec3[STEPS + 1];
            double[] halfWidths = new double[STEPS + 1];
            float[] alphas = new float[STEPS + 1];
            for (int i = 0; i <= STEPS; i++) {
                float t = i / (float) STEPS;
                double angle = phase + spin * t * Math.PI * turns;
                double radius = maxRadius * Math.sin(Math.PI * Math.pow(t, 0.7D));
                points[i] = origin.add(direction.scale(reach * t))
                        .add(new Vec3(Math.cos(angle) * radius, Math.sin(angle) * radius, 0.0D));
                float profile = Chaos.widthProfile(t, peak);
                halfWidths[i] = width * profile;
                alphas[i] = alpha * profile;
            }
            RibbonMesher.draw(consumer, pose, points, halfWidths, alphas, cameraLocal,
                              colour.red(), colour.green(), colour.blue());
        }
    }

    private RibbonTrail() {
    }
}
