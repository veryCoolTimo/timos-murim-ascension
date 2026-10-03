package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Дуги-эссенции, стягивающиеся к точке: сбор ладони разрушения демонов.
 *
 * <p>Описание автора (docs/design/techniques/demon-palm.md): пять-шесть мягких светящихся
 * дуг, размытых по краям. Они закручиваются к руке с разных сторон, каждая по-своему,
 * плавно и случайно, как турбулентность. Появляются по одной, но быстро.
 *
 * <p>Отличия от прежних прядей, которые автор назвал «линиями»:
 * <ul>
 *   <li>каждая дуга — два прохода одной кривой: широкий бледный ореол и узкое светлое
 *       ядро, оба с размытой кромкой ({@code essence.png});</li>
 *   <li>путь — поворот стартового смещения вокруг СВОЕЙ оси плюс плавный шум со своими
 *       частотами, а не общая спираль с равным шагом по углу;</li>
 *   <li>шум затухает к ладони, поэтому все дуги сходятся ровно в кисть.</li>
 * </ul>
 *
 * <p>Вызывающий берёт буфер {@link MurimRenderTypes#essence()} сам и закрывает его после.
 */
public final class EssenceArc {

    /** Радиус, по которому дуга обвивает кисть в конце, в долях длины дуги. */
    private static final double WRAP = 0.08D;

    /** Узлов на дугу. Меньше двадцати — на изгибах видны изломы. */
    private static final int NODES = 28;

    /**
     * Разновидность для выбора автором.
     *
     * @param haloWidth  полуширина ореола у ладони, блоки
     * @param coreWidth  полуширина ядра у ладони, блоки
     * @param haloAlpha  непрозрачность ореола
     * @param coreAlpha  непрозрачность ядра
     * @param calmWobble размах шума у спокойных дуг, блоки
     * @param wildWobble размах шума у волнистых дуг, блоки
     * @param calmShare  доля спокойных дуг
     * @param satellites тонких спутниц у каждой дуги: ноль — одна кривая
     * @param minReach   длина самой короткой дуги, блоки
     * @param maxReach   длина самой длинной дуги, блоки
     * @param minTurn    наименьшая закрутка, доли π
     * @param maxTurn    наибольшая закрутка, доли π
     */
    public record Style(double haloWidth, double coreWidth, float haloAlpha, float coreAlpha,
                        double calmWobble, double wildWobble, float calmShare, int satellites,
                        float minReach, float maxReach, float minTurn, float maxTurn) {

        // Закрутка не больше пол-оборота с небольшим: при полутора оборотах длинные дуги
        // облетали всё тело и читались кольцами-орбитами, а не потоком в ладонь.

        /** A — мягкая: широкий ореол, длинные плавные дуги. */
        public static final Style SOFT = new Style(0.20D, 0.045D, 0.55F, 0.95F,
                                                   0.05D, 0.10D, 0.5F, 0,
                                                   1.3F, 1.9F, 0.35F, 0.75F);
        /** B — рваная: дуги разной длины, короткие куски эссенции, сильный шум. */
        public static final Style TURBULENT = new Style(0.15D, 0.038D, 0.55F, 0.95F,
                                                        0.08D, 0.30D, 0.25F, 0,
                                                        0.6F, 1.9F, 0.25F, 0.65F);
        /** C — волокнистая: у каждой дуги тонкая спутница со своим шумом. */
        public static final Style FIBROUS = new Style(0.14D, 0.034D, 0.50F, 0.95F,
                                                      0.06D, 0.14D, 0.4F, 1,
                                                      1.2F, 1.8F, 0.35F, 0.75F);

        public static Style byName(String name) {
            if (name == null) {
                return FIBROUS;
            }
            return switch (name.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "a", "soft" -> SOFT;
                case "b", "turbulent" -> TURBULENT;
                // C выбран автором 2026-09-24.
                default -> FIBROUS;
            };
        }
    }

    /**
     * Рисует все дуги сбора.
     *
     * @param palm      точка кисти, куда сходятся дуги
     * @param count     число дуг
     * @param since     тиков с начала сбора
     * @param charge    заряд от 0 до 1; стягивает дуги к кисти
     * @param stagger   тиков между появлением соседних дуг
     * @param growTicks за сколько тиков дуга дорастает от дальнего конца до кисти
     * @param intensity общий множитель яркости
     */
    public static void gather(VertexConsumer consumer, PoseStack.Pose pose, Vec3 palm,
                              Vec3 cameraLocal, Style style, int count, float since,
                              float charge, float stagger, float growTicks, float intensity,
                              long seed, VfxColour halo, VfxColour core) {
        if (intensity <= 0.0F) {
            return;
        }
        // Дуги приходят со стороны руки и спереди, а не из-за спины: там их закрывает тело,
        // и на первых кадрах из шести было видно две. Сторона — от оси тела к кисти.
        double outward = Math.atan2(palm.z, palm.x);
        for (int i = 0; i < count; i++) {
            // Порядок появления перемешан: иначе дуги вырастают по кругу, как стрелка часов.
            int order = Math.floorMod((int) (Chaos.hash(i + seed) & 0xFF), count);
            float head = Mth.clamp((since - order * stagger) / growTicks, 0.0F, 1.0F);
            if (head <= 0.0F) {
                continue;
            }
            Arc arc = Arc.of(i, count, seed, style, outward);
            drawArc(consumer, pose, palm, cameraLocal, arc, style, style.coreWidth(),
                    style.haloWidth(), head, since, charge, intensity, halo, core);
            for (int s = 1; s <= style.satellites(); s++) {
                Arc satellite = Arc.of(i + 97 * s, count, seed ^ (0x51L * s), style, outward)
                        .near(arc, 0.18D * s);
                drawArc(consumer, pose, palm, cameraLocal, satellite, style,
                        style.coreWidth() * 0.6D, style.haloWidth() * 0.45D, head, since,
                        charge, intensity * 0.7F, halo, core);
            }
        }
    }

    /** Параметры одной дуги, выведенные из хеша: одна и та же дуга всегда одинакова. */
    private record Arc(Vec3 start, Vec3 axis, double turn, double wobble,
                       double[] freq, double[] phase, float pulsePhase) {

        static Arc of(int index, int count, long seed, Style style, double outward) {
            // Направление прихода. Первая дуга сверху, вторая снизу — как на референсе;
            // остальные — по кругу со сбитым шагом.
            double elevation;
            if (index == 0) {
                elevation = Math.toRadians(62.0D);
            } else if (index == 1) {
                // Не круче: кисть на сборе у бедра, и дуга снизу уходила под пол.
                elevation = Math.toRadians(-18.0D);
            } else {
                elevation = Math.toRadians(Chaos.range(index, seed ^ 0xE1L, -12.0F, 45.0F));
            }
            // Веер в ±115° от наружной стороны, со сбитым шагом.
            double slot = count <= 1 ? 0.5D : index / (double) (count - 1);
            double azimuth = outward + Math.toRadians(-115.0D + 230.0D * slot)
                    + Chaos.range(index, seed ^ 0xA2L, -0.35F, 0.35F);
            // Длинные: на референсе дуги размером с корпус, а не с кисть.
            double reach = Chaos.range(index, seed ^ 0x3AL, style.minReach(), style.maxReach());
            Vec3 start = new Vec3(Math.cos(elevation) * Math.cos(azimuth),
                                  Math.sin(elevation),
                                  Math.cos(elevation) * Math.sin(azimuth)).scale(reach);

            // Ось закрутки — своя у каждой дуги, перпендикулярная направлению прихода.
            Vec3 random = new Vec3(Chaos.range(index, seed ^ 0x11L, -1.0F, 1.0F),
                                   Chaos.range(index, seed ^ 0x12L, -1.0F, 1.0F),
                                   Chaos.range(index, seed ^ 0x13L, -1.0F, 1.0F));
            Vec3 axis = start.cross(random);
            if (axis.lengthSqr() < 1.0E-6D) {
                axis = start.cross(new Vec3(0.0D, 1.0D, 0.0D));
            }
            axis = axis.normalize();
            double turn = Chaos.spin(index, seed, 0.35F)
                    * Chaos.range(index, seed ^ 0x77L, style.minTurn(), style.maxTurn()) * Math.PI;

            boolean calm = Chaos.unit(index, seed ^ 0xCA1L) < style.calmShare();
            double wobble = calm ? style.calmWobble() : style.wildWobble();
            double[] freq = new double[3];
            double[] phase = new double[3];
            for (int k = 0; k < 3; k++) {
                freq[k] = Chaos.range(index * 3 + k, seed ^ 0xF4L, calm ? 0.8F : 1.4F,
                                      calm ? 1.6F : 3.2F);
                phase[k] = Chaos.range(index * 3 + k, seed ^ 0x9FL, 0.0F, (float) (Math.PI * 2.0D));
            }
            return new Arc(start, axis, turn, wobble, freq, phase,
                           Chaos.unit(index, seed ^ 0x2BL));
        }

        /** Спутница: приходит почти оттуда же, но со своим шумом и чуть другим поворотом. */
        Arc near(Arc main, double spread) {
            Vec3 shifted = main.start.add(start.normalize().scale(main.start.length() * spread))
                    .normalize().scale(main.start.length());
            return new Arc(shifted, main.axis, main.turn * (1.0D + spread * 0.5D), wobble,
                           freq, phase, pulsePhase);
        }

        /**
         * Точка дуги.
         *
         * @param t 0 — дальний конец, 1 — кисть
         */
        Vec3 at(double t, float since, float charge) {
            // Заряд подтягивает дальний конец к кисти.
            double squeeze = 1.0D - 0.25D * charge;
            // Дуга не втыкается в точку, а обвивает кисть по малому радиусу: шесть дуг,
            // сходящихся в одну точку, давали колючую звезду вместо потока.
            double radius = WRAP + (1.0D - WRAP) * Math.pow(1.0D - t, 0.85D) * squeeze;
            Vec3 offset = rotate(start.normalize(), axis, turn * t + since * 0.035D)
                    .scale(radius * start.length());
            // Шум затухает к кисти и медленно течёт во времени: дуга живая, но сходится в точку.
            double fall = (1.0D - t) * Math.min(1.0D, t * 4.0D + 0.25D);
            double flow = since * 0.09D;
            Vec3 noise = new Vec3(
                    Math.sin(freq[0] * t * Math.PI * 2.0D + phase[0] + flow),
                    Math.sin(freq[1] * t * Math.PI * 2.0D + phase[1] - flow * 1.3D),
                    Math.sin(freq[2] * t * Math.PI * 2.0D + phase[2] + flow * 0.7D))
                    .scale(wobble * fall);
            return offset.add(noise);
        }
    }

    private static void drawArc(VertexConsumer consumer, PoseStack.Pose pose, Vec3 palm,
                                Vec3 cameraLocal, Arc arc, Style style, double coreWidth,
                                double haloWidth, float head, float since, float charge,
                                float intensity, VfxColour halo, VfxColour core) {
        int nodes = Math.max(2, Math.round(NODES * head));
        Vec3[] points = new Vec3[nodes + 1];
        double[] haloWidths = new double[nodes + 1];
        double[] coreWidths = new double[nodes + 1];
        float[] haloAlphas = new float[nodes + 1];
        float[] coreAlphas = new float[nodes + 1];
        for (int n = 0; n <= nodes; n++) {
            // Растёт от дальнего конца к кисти: эссенция приходит из воздуха в руку.
            double t = head * n / (double) nodes;
            points[n] = palm.add(arc.at(t, since, charge));
            // Утолщение ближе к кисти, оба конца тонкие: у кисти дуги не должны
            // сливаться в пятно.
            double body = Chaos.widthProfile((float) Math.max(0.02D, Math.min(0.98D, t)), 0.62F);
            haloWidths[n] = haloWidth * body;
            coreWidths[n] = coreWidth * body;
            // Появление из ничего у дальнего конца и мягкая голова у растущего фронта.
            float fadeIn = (float) smooth(0.0D, 0.22D, t);
            float front = head < 1.0F ? (float) (1.0D - smooth(head - 0.12D, head, t)) * 0.6F + 0.4F
                                      : 1.0F;
            // Пульс бежит к кисти: видно, куда течёт.
            float pulse = 0.78F + 0.22F * (float) Math.sin(
                    (t * 2.2D - since * 0.16D + arc.pulsePhase) * Math.PI * 2.0D);
            float base = intensity * fadeIn * front * pulse;
            haloAlphas[n] = base * style.haloAlpha();
            coreAlphas[n] = base * style.coreAlpha() * (0.55F + 0.45F * (float) t);
        }
        // Третий, самый широкий и бледный проход — «размытость»: без него кромка ореола
        // на тёмном фоне всё равно читается линией. Замена настоящему размытию, которого
        // без своего шейдера нет.
        double[] bloomWidths = new double[nodes + 1];
        float[] bloomAlphas = new float[nodes + 1];
        for (int n = 0; n <= nodes; n++) {
            bloomWidths[n] = haloWidths[n] * 2.4D;
            bloomAlphas[n] = haloAlphas[n] * 0.35F;
        }
        RibbonMesher.draw(consumer, pose, points, bloomWidths, bloomAlphas, cameraLocal,
                          halo.red(), halo.green(), halo.blue());
        RibbonMesher.draw(consumer, pose, points, haloWidths, haloAlphas, cameraLocal,
                          halo.red(), halo.green(), halo.blue());
        RibbonMesher.draw(consumer, pose, points, coreWidths, coreAlphas, cameraLocal,
                          core.red(), core.green(), core.blue());
    }

    /** Поворот вектора вокруг единичной оси (формула Родрига). */
    private static Vec3 rotate(Vec3 v, Vec3 axis, double angle) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        return v.scale(cos)
                .add(axis.cross(v).scale(sin))
                .add(axis.scale(axis.dot(v) * (1.0D - cos)));
    }

    private static double smooth(double edge0, double edge1, double x) {
        double t = Mth.clamp((x - edge0) / (edge1 - edge0), 0.0D, 1.0D);
        return t * t * (3.0D - 2.0D * t);
    }

    private EssenceArc() {
    }
}
