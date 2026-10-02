package io.github.verycooltimo.murim.technique;

import net.minecraft.world.phys.Vec3;

/**
 * «Казнь Цветущей Сливы» — форма Меча Семи Цветков Сливы (docs/design/techniques/seven-plum-execution-spec.md):
 * шесть клонов из лепестков заходят с шести сторон, бьют по разу и рассыпаются; оригинал за
 * спиной цели; по ней разом проходят шесть разрезов. Тики — от выхода клонов (начало IMPACT,
 * тик 48 техники). Маршруты общие для сервера (тайминги) и клиента (рисунок).
 */
public final class ExecRules {

    public static final int EXIT = 4;
    public static final int SPREAD = 14;
    /** Контакт i-го клона. */
    public static final int FIRST_CONTACT = 24;
    public static final int CONTACT_GAP = 3;
    public static final int PASS_TICKS = 6;
    public static final int DISSOLVE = 6;
    /** Клоны рассыпаются через 2 тика после раскрытия разрезов. */
    public static final int DISSOLVE_DELAY = 2;
    public static final int DASH = 46;
    public static final int DASH_TICKS = 12;
    public static final int FINAL = 64;
    public static final int END = 76;
    /**
     * Секторы захода — веером на стороне оригинала (автор 02.10: клоны выпрыгивают и летят к цели,
     * а в итоге все оказываются за спиной жертвы, по разным сторонам). Градусы от «цель → оригинал».
     */
    public static final double[] SECTORS = {-20.0D, 20.0D, -60.0D, 60.0D, -100.0D, 100.0D};
    public static final double APPROACH = 3.5D;
    /** Разные высоты дуг и боковые выносы: шесть маршрутов читаются отдельно (codex 02.10). */
    private static final double[] ARC = {0.5D, 1.9D, 1.0D, 2.4D, 0.3D, 1.5D};
    private static final double[] BOW = {-0.6D, 0.6D, -1.2D, 1.2D, -1.8D, 1.8D};

    public static final double DMG_CLONE = 0.2D;
    public static final double DMG_FINAL = 0.4D;

    public static int clones(int layer) {
        return layer <= 1 ? 1 : layer == 2 ? 2 : layer == 3 ? 3 : layer == 4 ? 4 : 6;
    }

    public static boolean finale(int layer) {
        return layer >= 6;
    }

    public static int contact(int i) {
        return FIRST_CONTACT + CONTACT_GAP * i;
    }

    /** Направление сектора {@code i} на плоскости (base — угол «цель → оригинал»). */
    public static Vec3 sector(double base, int i) {
        double a = base + Math.toRadians(SECTORS[i]);
        return new Vec3(Math.cos(a), 0.0D, Math.sin(a));
    }

    /**
     * Положение клона {@code i} в тик {@code s}: прыжок из оригинала → полёт дугой к своей точке
     * захода → прыжок-удар сквозь цель → приземление за её спиной (зеркально точке захода) → стоит
     * там до финала. Полосы веером — копии не пересекаются.
     */
    public static Vec3 clone(Vec3 origin, Vec3 centre, double base, int i, double s) {
        Vec3 dir = sector(base, i);
        // Заход подстраивается под дистанцию: близко — тесный веер, далеко — широкий.
        double dist = Math.sqrt((origin.x - centre.x) * (origin.x - centre.x) + (origin.z - centre.z) * (origin.z - centre.z));
        double reach = Math.max(1.6D, Math.min(APPROACH, dist * 0.6D));
        Vec3 approach = centre.add(dir.scale(reach));
        Vec3 out = new Vec3(approach.x - origin.x, 0.0D, approach.z - origin.z);
        out = out.lengthSqr() < 1.0E-6D ? dir : out.normalize();
        Vec3 exitP = origin.add(out.scale(1.0D));
        // Клоны садятся близко за жертвой; оригинал уходит дальше них (автор 02.10).
        Vec3 behind = centre.subtract(dir.scale(Math.min(2.2D, reach * 0.7D)));
        Vec3 sideV = new Vec3(-dir.z, 0.0D, dir.x);
        double arc = ARC[i];
        int c = contact(i);
        if (s <= EXIT) {
            double k = Math.max(0.0D, s) / EXIT;
            return origin.lerp(exitP, k).add(0.0D, 1.0D * Math.sin(Math.PI * 0.5D * k), 0.0D);
        }
        if (s <= SPREAD) {
            double k = (s - EXIT) / (double) (SPREAD - EXIT);
            return exitP.lerp(approach, k).add(sideV.scale(BOW[i] * Math.sin(Math.PI * k)))
                    .add(0.0D, 1.0D + arc * Math.sin(Math.PI * k), 0.0D);
        }
        if (s <= c) {
            double k = (s - SPREAD) / (double) (c - SPREAD);
            return approach.lerp(centre, k * k).add(0.0D, 1.0D * (1.0D - k) + 0.4D * arc * Math.sin(Math.PI * k), 0.0D);
        }
        if (s <= c + PASS_TICKS) {
            double k = (s - c) / (double) PASS_TICKS;
            return centre.lerp(behind, k).add(0.0D, 0.6D * Math.sin(Math.PI * k), 0.0D);
        }
        return behind;
    }

    private ExecRules() {
    }
}
