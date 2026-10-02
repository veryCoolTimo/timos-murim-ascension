package io.github.verycooltimo.murim.technique;

/**
 * «Вихрь Цветущей Сливы» — третья форма Меча Семи Цветков Сливы. Шкала и числа —
 * docs/design/techniques/seven-plum-whirlwind-spec.md (сжатая до 9,8 с версия разбора codex).
 * Все тики — от первого Разреза вверх (тик 28 техники, начало фазы IMPACT).
 */
public final class WhirlRules {

    /** Тик техники, на котором идёт Разрез вверх (конец windup). */
    public static final int SLASH = 28;
    /**
     * Шкала после Разреза (автор 02.10: «слишком быстро и симметрично; вихрь — вокруг игрока, а не
     * в противнике; сначала основная закрутка, потом медленно два мини-урагана, они проходят по
     * противнику, потом мы»).
     */
    public static final int[] WALL_STROKES = {16, 26, 36};
    public static final int TENSION = 38;
    public static final int CONVERGE = 52;
    public static final int SHATTER = 68;
    public static final int WHIRL = 78;
    public static final int WHIRL_END = 140;
    /** Два мини-урагана: медленно отделяются от вихря, идут к цели и сквозь неё. */
    public static final int TORNADO_FORM = 112;
    public static final int TORNADO_GO = 136;
    public static final int TORNADO_END = 176;
    public static final int ARMS = TORNADO_FORM;
    public static final int QUIET = 176;
    public static final int PASS = 188;
    public static final int PASS_TICKS = 10;
    public static final int EXIT = 198;
    public static final int END = 210;
    /** Импульсы урона основного вихря (вокруг мастера). */
    public static final int[] PULSES = {80, 90, 100, 110, 120, 130};
    /** Импульс мини-урагана — раз в столько тиков, пока он в пути. */
    public static final int TORNADO_PULSE = 6;

    /** Положение мини-урагана {@code which} (−1/+1) в тик {@code since}: от края вихря к цели и на 2 блока за неё. */
    public static net.minecraft.world.phys.Vec3 tornado(net.minecraft.world.phys.Vec3 centre, net.minecraft.world.phys.Vec3 target,
                                                       double radius, int which, double since) {
        net.minecraft.world.phys.Vec3 to = target.subtract(centre);
        net.minecraft.world.phys.Vec3 dir = new net.minecraft.world.phys.Vec3(to.x, 0.0D, to.z);
        double dist = Math.max(0.5D, dir.length());
        dir = dir.scale(1.0D / dist);
        net.minecraft.world.phys.Vec3 side = new net.minecraft.world.phys.Vec3(-dir.z, 0.0D, dir.x);
        net.minecraft.world.phys.Vec3 start = centre.add(dir.scale(radius * 0.7D)).add(side.scale(which * radius * 0.55D));
        net.minecraft.world.phys.Vec3 end = target.add(dir.scale(2.2D)).add(side.scale(which * 0.4D));
        double k = Math.max(0.0D, Math.min(1.0D, (since - TORNADO_GO) / (double) (TORNADO_END - TORNADO_GO)));
        // Разгон медленный, к цели — быстрее; пути выгнуты наружу и сходятся на цели.
        double e = k * k * (3.0D - 2.0D * k);
        net.minecraft.world.phys.Vec3 p = start.lerp(end, e);
        double bow = Math.sin(Math.PI * e) * radius * 0.35D * which * (which > 0 ? 1.0D : 0.8D);
        return p.add(side.scale(bow));
    }

    public static final double DMG_SLASH = 0.8D;
    public static final double DMG_WALL = 0.25D;
    public static final double DMG_CONVERGE = 0.9D;
    public static final double DMG_PULSE = 0.2D;
    public static final double DMG_PASS = 2.2D;

    public static final double MAX_PULL = 0.16D;

    /** Радиус зоны по слою (блоки). */
    /** Радиус вихря вокруг мастера по слою (блоки). */
    public static double radius(int layer) {
        return switch (Math.max(0, Math.min(7, layer))) {
            case 0, 1 -> 1.6D;
            case 2 -> 2.0D;
            case 3 -> 2.4D;
            case 4 -> 2.8D;
            case 5 -> 3.1D;
            case 6 -> 3.4D;
            default -> 3.7D;
        };
    }

    public static double height(int layer) {
        return Math.min(6.0D, 2.5D + 0.5D * Math.max(0, layer));
    }

    /** Число столпов: 0 на слоях 0–1, 4 на 2-м, 6 на 3-м, 8 на 4–6-м, 12 на 7-м. */
    public static int pillars(int layer) {
        return layer <= 1 ? 0 : layer == 2 ? 4 : layer == 3 ? 6 : layer <= 6 ? 8 : 12;
    }

    public static boolean walls(int layer) {
        return layer >= 4;
    }

    public static boolean whirl(int layer) {
        return layer >= 5;
    }

    public static boolean finale(int layer) {
        return layer >= 6;
    }

    private WhirlRules() {
    }
}
