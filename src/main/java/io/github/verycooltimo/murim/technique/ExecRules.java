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
    public static final int DASH = 46;
    public static final int DASH_TICKS = 12;
    public static final int FINAL = 64;
    public static final int END = 76;
    /** Порядок секторов захода: чередование противоположных сторон, градусы от направления «цель → оригинал». */
    public static final double[] SECTORS = {0.0D, 180.0D, 60.0D, 240.0D, 120.0D, 300.0D};
    public static final double APPROACH = 3.5D;

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
     * Положение клона {@code i} в тик {@code s}: выход из оригинала → огибание к точке захода →
     * бег к цели → пролёт сквозь неё на 2,5 блока → тормозит, рассыпаясь.
     */
    public static Vec3 clone(Vec3 origin, Vec3 centre, double base, int i, double s) {
        Vec3 dir = sector(base, i);
        Vec3 approach = centre.add(dir.scale(APPROACH)).add(new Vec3(-dir.z, 0.0D, dir.x).scale(0.35D * ((i % 2) * 2 - 1)));
        double exitA = base + Math.PI + Math.toRadians(60.0D * i - 150.0D);
        Vec3 exitP = origin.add(Math.cos(exitA) * 1.2D, 0.0D, Math.sin(exitA) * 1.2D);
        int c = contact(i);
        if (s <= EXIT) {
            return origin.lerp(exitP, Math.max(0.0D, s) / EXIT);
        }
        if (s <= SPREAD) {
            double k = (s - EXIT) / (double) (SPREAD - EXIT);
            Vec3 ctrl = exitP.lerp(approach, 0.5D).add(approach.subtract(centre).normalize().scale(1.8D));
            double q = 1.0D - k;
            return exitP.scale(q * q).add(ctrl.scale(2.0D * q * k)).add(approach.scale(k * k));
        }
        if (s <= c) {
            double k = (s - SPREAD) / (double) (c - SPREAD);
            return approach.lerp(centre, k * k);
        }
        Vec3 through = centre.subtract(approach);
        through = new Vec3(through.x, 0.0D, through.z).normalize();
        if (s <= c + PASS_TICKS) {
            return centre.add(through.scale(2.5D * (s - c) / PASS_TICKS));
        }
        double k = Math.min(1.0D, (s - c - PASS_TICKS) / (double) DISSOLVE);
        return centre.add(through.scale(2.5D + 0.8D * (1.0D - (1.0D - k) * (1.0D - k))));
    }

    private ExecRules() {
    }
}
