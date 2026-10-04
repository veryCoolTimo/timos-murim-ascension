package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.sect.SectSchedule.Kind;
import io.github.verycooltimo.murim.sect.SectSchedule.Period;
import io.github.verycooltimo.murim.sect.SectSchedule.Task;

import java.util.ArrayList;
import java.util.List;

/**
 * Распорядок мирян при секте (С3, часть 2): управляющий хозяйством и слуги — повар, водонос, носильщики,
 * травник, метельщик. Чистая функция, как {@link SectSchedule}: «время → место → дело».
 *
 * <p>Канон: секта бедна, «злаки и орехи — самая дешёвая диета» (гл. 76), лавки Хуаиня когда-то принадлежали
 * секте (гл. 20, 32), позже снабжение берёт гильдия Ынха (гл. 178, 414). Поимённо слуг в романе нет —
 * имена мирские и простые (план §4.1: «повар — v2»).
 *
 * <p>Жильё слуг — угол двора лагеря (юго-восток), там же вечером отдыхают; едят с учениками за столом во дворе.
 */
public final class SectStaff {

    /** Угол слуг во дворе лагеря: первое место и шаг. */
    static final double CORNER_U = 10.0D;
    static final double CORNER_V = 15.0D;


    private SectStaff() {
    }

    /** Миряне в порядке списка. */
    static List<SectRoster> all() {
        List<SectRoster> out = new ArrayList<>();
        for (SectRoster m : SectRoster.ALL) {
            if (m.lay()) {
                out.add(m);
            }
        }
        return out;
    }

    /** Номер мирянина в списке (0…). */
    static int index(SectRoster m) {
        return Math.max(0, all().indexOf(m));
    }

    static Task task(SectRoster m, Period p, long day) {
        if (p == Period.NIGHT) {
            return sleep(m);
        }
        return switch (m.role()) {
            case STEWARD -> steward(p);
            case COOK -> cook(p);
            case WATER_CARRIER -> water(m, p);
            case PORTER -> porter(m, p);
            case GARDENER -> herbalist(m, p);
            case SWEEPER -> sweeper(m, p);
            default -> rest(m);
        };
    }

    /**
     * Управляющий: днём — у кладовой на краю хозяйственного двора (внешняя часть площадки {@code treasury},
     * вне казны), в трапезы — на кухне, вечером у ворот считает принесённое носильщиками.
     */
    private static Task steward(Period p) {
        return switch (p) {
            case BREAKFAST, DINNER -> new Task(Kind.WORK, "dining", -3.0D, 2.5D, 0.0D, -1.0D);
            case EVENING -> new Task(Kind.WORK, "sect_gate", 8.0D, 2.0D, 0.0D, -1.0D);
            default -> new Task(Kind.WORK, "treasury", -9.5D, 0.0D, -1.0D, 0.0D);
        };
    }

    /** Повар: на рассвете готовит у очага, в трапезы раздаёт, днём снова у очага; вечером — отдых. */
    private static Task cook(Period p) {
        return switch (p) {
            case BREAKFAST, DINNER -> new Task(Kind.SERVE, "dining", 0.0D, 0.0D, 0.0D, 1.0D);
            case EVENING -> rest(SectRoster.of("cook_kim").orElseThrow());
            default -> new Task(Kind.COOK, "dining", 6.0D, -2.5D, 1.0D, 0.0D);
        };
    }

    /** Водонос: от колодца в лагере на кухню; днём — и в павильон алхимии. */
    private static Task water(SectRoster m, Period p) {
        return switch (p) {
            case BREAKFAST, DINNER -> SectSchedule.eat(m);
            case EVENING -> rest(m);
            case TRAINING -> SectSchedule.carry("alchemy", -6.0D, 3.0D, "camp", 14.0D, -20.0D);
            default -> SectSchedule.carry("dining", 5.0D, 2.0D, "camp", 14.0D, -20.0D);
        };
    }

    /**
     * Носильщики: груз, поднятый по лестнице, принимают на площадке ворот (там кончаются ступени) и несут в кладовую
     * (снабжение гильдии Ынха, гл. 178). Двое ходят со сдвигом: у каждого своё место у ворот и в кладовой.
     * Сам подъём по тропе не показан: тропа под воротами — вне площадок секты, NPC там не тикают без игрока.
     */
    private static Task porter(SectRoster m, Period p) {
        int i = "porter_oh".equals(m.key()) ? 1 : 0;
        return switch (p) {
            case BREAKFAST, DINNER -> SectSchedule.eat(m);
            case EVENING -> rest(m);
            default -> SectSchedule.carry("treasury", 9.0D, i == 0 ? -5.0D : 5.0D, "sect_gate", 9.0D + i * 2.0D, -3.0D);
        };
    }

    /** Травник: грядки у павильона алхимии; рано утром и днём. */
    private static Task herbalist(SectRoster m, Period p) {
        return switch (p) {
            case BREAKFAST, DINNER -> SectSchedule.eat(m);
            case EVENING -> rest(m);
            default -> new Task(Kind.TEND, "alchemy", 0.0D, 3.0D, 0.0D, 1.0D);
        };
    }

    /** Метельщик: двор там, где сейчас пусто — ворота в строй, площадь в завтрак и днём, песок поединков в ужин. */
    private static Task sweeper(SectRoster m, Period p) {
        return switch (p) {
            case FORMATION -> new Task(Kind.SWEEP, "sect_gate", 0.0D, 0.0D, 0.0D, -1.0D);
            case DINNER -> new Task(Kind.SWEEP, "sparring", 0.0D, 0.0D, 0.0D, 1.0D);
            case EVENING -> rest(m);
            default -> new Task(Kind.SWEEP, "training", 0.0D, -4.0D, 0.0D, 1.0D);
        };
    }

    /** Вечер: угол слуг во дворе лагеря, сидя. */
    static Task rest(SectRoster m) {
        double[] s = corner(m);
        return new Task(Kind.REST, "camp", s[0], s[1], -1.0D, 0.0D);
    }

    /** Ночь: угол слуг (управляющий — у кладовой). Кроватей слугам автор не ставит — спят сидя. */
    static Task sleep(SectRoster m) {
        if (m.role() == SectRole.STEWARD) {
            return new Task(Kind.SLEEP, "treasury", -9.5D, 6.0D, 1.0D, 0.0D);
        }
        double[] s = corner(m);
        return new Task(Kind.SLEEP, "camp", s[0], s[1], -1.0D, 0.0D);
    }

    private static double[] corner(SectRoster m) {
        int i = index(m);
        return new double[] {CORNER_U + (i % 3) * 2.5D, CORNER_V + (i / 3) * 3.0D};
    }
}
