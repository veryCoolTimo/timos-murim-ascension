package io.github.verycooltimo.murim.sect;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Закрытые места секты (С3, часть 2; автор 04.10: «охрана, которая не пропускает в места не разрешённые»):
 * какое положение ({@link SectStanding}) нужно, чтобы войти днём и ночью, и какой пропуск (флаг секты)
 * открывает место без положения. Чистые функции от раскладки и позиции — их проверяют юнит-тесты; охраняет
 * {@link SectWatch}.
 *
 * <p>Канон: порядок старшинства строг (гл. 104); тайник под холмом открывает только глава (гл. 24–26);
 * пещера покаяния — для наказанных (вики). Остальное — [ПРЕДЛАГАЮ]: залы старших для младших закрыты, ночью
 * по горе ходят только доверенные.
 */
public final class SectAccess {

    /** Особая «площадка» правила: вся земля секты, кроме ворот (чужак дальше ворот не идёт). */
    public static final String GROUNDS = "*";

    /**
     * Правило места.
     *
     * @param id    имя места: {@code murim.sect.area.<id>}
     * @param zone  площадка горы ({@code MountHuaPlan.ZONES}) или {@link #GROUNDS}
     * @param inset насколько место меньше площадки с каждой стороны (казна — внутренняя часть хозяйственного двора)
     * @param below ниже пола площадки — столько блоков ещё внутри
     * @param above выше пола — столько
     * @param day   нужное положение днём (с рассвета до вечера)
     * @param night нужное положение ночью
     * @param pass  флаг-пропуск: открывает место при любом положении члена секты (зов, разрешение старейшины)
     */
    public record Rule(String id, String zone, double inset, double below, double above,
                       SectStanding day, SectStanding night, String pass) {

        public String nameKey() {
            return "murim.sect.area." + id;
        }

        /** Нужное положение в эту часть суток. */
        public SectStanding need(SectSchedule.Period period) {
            return period == SectSchedule.Period.NIGHT ? night : day;
        }
    }

    public static final List<Rule> RULES = List.of(
            // Тайник под главным залом (пол ниже на 10): допускает глава — флаг ставит испытание двери (С4).
            new Rule("vault", "vault", 0.0D, 3.0D, 6.0D, SectStanding.TRUSTED, SectStanding.TRUSTED, "vault.permitted"),
            new Rule("main_hall", "main_hall", 0.0D, 2.0D, 24.0D, SectStanding.DISCIPLE, SectStanding.TRUSTED, "pass.main_hall"),
            new Rule("ancestors", "ancestors", 0.0D, 4.0D, 24.0D, SectStanding.DISCIPLE, SectStanding.TRUSTED, "pass.ancestors"),
            new Rule("scriptures", "scriptures", 0.0D, 4.0D, 24.0D, SectStanding.GRADUATE, SectStanding.TRUSTED, "pass.scriptures"),
            // Казна — середина хозяйственного двора; управляющий и кладовая — по краю, туда можно всем своим.
            new Rule("treasury", "treasury", 3.0D, 4.0D, 24.0D, SectStanding.DISCIPLE, SectStanding.TRUSTED, "pass.treasury"),
            new Rule("elders", "elders", 0.0D, 4.0D, 24.0D, SectStanding.GRADUATE, SectStanding.TRUSTED, "pass.elders"),
            // Пещера покаяния: только наказанный (флаг) или доверенный.
            new Rule("penance", "penance", 0.0D, 3.0D, 8.0D, SectStanding.TRUSTED, SectStanding.TRUSTED, "penance.sentenced"),
            // Чужак: дальше ворот секты — ни днём, ни ночью.
            new Rule("grounds", GROUNDS, 0.0D, 6.0D, 30.0D, SectStanding.NOVICE, SectStanding.NOVICE, ""));

    /** Предупреждают, если чужое место ближе стольких блоков. */
    public static final double WARN = 4.0D;

    private SectAccess() {
    }

    /** Можно ли сюда с этим положением и флагами в эту часть суток. */
    public static boolean allowed(Rule rule, SectStanding standing, Set<String> flags, SectSchedule.Period period) {
        if (standing.atLeast(rule.need(period))) {
            return true;
        }
        // Пропуск действует только для своих: чужаку дальше ворот не помогает.
        return standing != SectStanding.OUTSIDER && !rule.pass().isEmpty() && flags.contains(rule.pass());
    }

    /** Точка внутри места правила с запасом {@code margin} (по горизонтали; высота — от пола площадки). */
    public static boolean inside(SectLayout layout, Rule rule, Vec3 pos, double margin) {
        if (GROUNDS.equals(rule.zone())) {
            for (String zone : SectLayout.SECT_ZONES) {
                if (!"sect_gate".equals(zone) && insideZone(layout, zone, rule, pos, margin)) {
                    return true;
                }
            }
            return false;
        }
        return insideZone(layout, rule.zone(), rule, pos, margin);
    }

    private static boolean insideZone(SectLayout layout, String zone, Rule rule, Vec3 pos, double margin) {
        double[] l = layout.local(zone, pos);
        double[] h = layout.half(zone);
        Vec3 floor = layout.at(zone, 0.0D, 0.0D);
        if (l == null || h == null || floor == null) {
            return false;
        }
        if (pos.y < floor.y - rule.below() || pos.y > floor.y + rule.above()) {
            return false;
        }
        return Math.abs(l[0]) <= h[0] - rule.inset() + margin && Math.abs(l[1]) <= h[1] - rule.inset() + margin;
    }

    /** Места, куда игроку с этим положением сейчас нельзя и где он стоит (с запасом {@code margin}). */
    public static List<Rule> forbidden(SectLayout layout, Vec3 pos, SectStanding standing, Set<String> flags,
                                       SectSchedule.Period period, double margin) {
        List<Rule> out = new ArrayList<>();
        for (Rule r : RULES) {
            if (!allowed(r, standing, flags, period) && inside(layout, r, pos, margin)) {
                out.add(r);
            }
        }
        return out;
    }

    /** Центр места (куда охрана не пускает): площадка правила или ближайшая площадка земли секты. */
    public static Vec3 center(SectLayout layout, Rule rule, Vec3 pos) {
        if (!GROUNDS.equals(rule.zone())) {
            return layout.at(rule.zone(), 0.0D, 0.0D);
        }
        Vec3 best = null;
        for (String zone : SectLayout.SECT_ZONES) {
            Vec3 c = layout.at(zone, 0.0D, 0.0D);
            if (!"sect_gate".equals(zone) && c != null && (best == null || c.distanceToSqr(pos) < best.distanceToSqr(pos))) {
                best = c;
            }
        }
        return best;
    }

    /**
     * Насколько далеко видит охрана (блоков). Днём — далеко; ночью — ближе, присевшего — совсем близко;
     * невидимого — только вплотную. Ночью пробраться можно, но рискованно (запрос автора).
     */
    public static double sight(boolean night, boolean crouching, boolean invisible) {
        if (invisible) {
            return 2.5D;
        }
        if (night) {
            return crouching ? 4.0D : 9.0D;
        }
        return crouching ? 12.0D : 16.0D;
    }

    /** Ночью охрана видит только перед собой (половина угла, градусов), вплотную — всегда. */
    public static final double NIGHT_HALF_ANGLE = 70.0D;
    public static final double CLOSE = 2.5D;
}
