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
            // The water carrier helps at the serving counter during the meal instead of sitting down (doc 39 item 6).
            case BREAKFAST, DINNER -> new Task(Kind.SERVE, "dining", 1.0D, 0.0D, 0.0D, 1.0D);
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

    /** Метельщик: двор там, где сейчас пусто — ворота в строй, площадь в завтрак и днём, двор лагеря в ужин. */
    private static Task sweeper(SectRoster m, Period p) {
        return switch (p) {
            case FORMATION -> new Task(Kind.SWEEP, "sect_gate", 0.0D, 0.0D, 0.0D, -1.0D);
            case DINNER -> new Task(Kind.SWEEP, "camp", 11.0D, 8.0D, 0.0D, 1.0D);
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

    // ------------------------------------------------------------------ Round 4: the staff's own day (servants-1..6, peers-6)

    /** Meal lengths and starts (ticks of the day). */
    private static final int BREAKFAST_START = Period.BREAKFAST.start(), BREAKFAST_END = BREAKFAST_START + 1000;
    private static final int DINNER_START = Period.DINNER.start(), DINNER_END = DINNER_START + 2000;
    /** Morning delivery: the porters carry between these ticks, one starting a little after the other. */
    static int deliveryFrom() { return SectLessons.n("deliveryFrom", 2400); }
    static int deliveryTo() { return SectLessons.n("deliveryTo", 4800); }

    private static boolean within(int t, int from, int to) { return t >= from && t < to; }

    /**
     * The timed part of a servant's day: kitchen prepare / serve / clear / tea (servants-3), the morning delivery and the
     * count (servants-1, servants-6), the water trips before meals (servants-2), the herbalist's cycle (servants-4), the
     * sweeper's patches (servants-5) and a personal tea break (servants-6). Null: the plain routine of the period.
     * Pure function of the time of day, like the rest of the schedule.
     */
    static Task timed(SectRoster m, long dayTime) {
        int t = (int) Math.floorMod(dayTime, (long) SectSchedule.DAY);
        long day = SectSchedule.day(dayTime);
        Period p = SectSchedule.at(dayTime);
        if (p == Period.NIGHT) {
            return null;
        }
        int clear = SectLessons.n("clearTicks", 400), tea = SectLessons.n("kitchenTea", 400);
        switch (m.role()) {
            case COOK -> {
                if (within(t, BREAKFAST_END, BREAKFAST_END + clear) || within(t, DINNER_END, DINNER_END + clear)) {
                    return new Task(Kind.CHORE, "dining", 0.0D, 0.0D, 0.0D, 1.0D);
                }
                if (within(t, BREAKFAST_END + clear, BREAKFAST_END + clear + tea)) {
                    return new Task(Kind.WORK, "dining", -3.0D, 2.5D, 0.0D, -1.0D);
                }
            }
            case WATER_CARRIER -> {
                if (within(t, BREAKFAST_END, BREAKFAST_END + clear) || within(t, DINNER_END, DINNER_END + clear)) {
                    return SectSchedule.eat(m); // the helper eats after his own service
                }
                if (p == Period.TRAINING) {
                    if (t < SectLessons.n("waterAlchemyTo", 3000)) {
                        return SectSchedule.carry("alchemy", -6.0D, 3.0D, "camp", 14.0D, -20.0D);
                    }
                    if (t >= DINNER_START - SectLessons.n("waterPrep", 1500)) {
                        return SectSchedule.carry("dining", 5.0D, 2.0D, "camp", 14.0D, -20.0D);
                    }
                    Task tea1 = teaBreak(m, t, day);
                    return tea1 != null ? tea1 : new Task(Kind.WORK, "dining", -1.0D, 2.5D, 0.0D, -1.0D);
                }
            }
            case PORTER -> {
                int i = "porter_oh".equals(m.key()) ? 1 : 0;
                int from = deliveryFrom() + i * SectLessons.n("deliveryShift", 360), to = deliveryTo() + i * SectLessons.n("deliveryShift", 360);
                if (p == Period.TRAINING) {
                    if (t < from) {
                        return new Task(Kind.WORK, "sect_gate", 9.0D + i * 2.0D, 3.0D, 0.0D, -1.0D);
                    }
                    if (t < to) {
                        return SectSchedule.carry("treasury", 9.0D, i == 0 ? -5.0D : 5.0D, "sect_gate", 9.0D + i * 2.0D, -3.0D);
                    }
                    if (t < to + SectLessons.n("storeTicks", 1200)) {
                        return new Task(Kind.WORK, "treasury", -9.5D, i * 3.0D - 1.5D, -1.0D, 0.0D); // stack the load, help in the storeroom
                    }
                    Task tea1 = teaBreak(m, t, day);
                    return tea1 != null ? tea1 : new Task(Kind.WORK, "treasury", -9.5D, i * 3.0D - 1.5D, -1.0D, 0.0D);
                }
            }
            case STEWARD -> {
                // counts what came after the last trip, never later than the end of the window
                int last = deliveryTo() + SectLessons.n("deliveryShift", 360);
                if (p == Period.TRAINING && within(t, last, last + SectLessons.n("countTicks", 600))) {
                    return new Task(Kind.WORK, "sect_gate", 8.0D, 2.0D, 0.0D, -1.0D);
                }
                Task tea1 = p == Period.TRAINING ? teaBreak(m, t, day) : null;
                if (tea1 != null) {
                    return tea1;
                }
            }
            case GARDENER -> {
                if (p == Period.TRAINING || p == Period.FORMATION) {
                    if (within(t, SectLessons.n("handBagFrom", 7300), SectLessons.n("handBagTo", 7700))) {
                        return SectSchedule.carry("training", -11.0D, -7.0D, "alchemy", 0.0D, 3.0D); // the bag to the heal post
                    }
                    if (p == Period.TRAINING) {
                        int phase = Math.floorMod(t, 800) / 200;
                        return switch (phase) {
                            case 0 -> new Task(Kind.TEND, "alchemy", 0.0D, 3.0D, 0.0D, 1.0D);
                            case 1 -> SectSchedule.carry("alchemy", -3.0D, 1.0D, "alchemy", 0.0D, 3.0D); // basket to the pavilion
                            case 2 -> new Task(Kind.WORK, "alchemy", -3.0D, 2.0D, 0.0D, -1.0D);          // hand over, sort
                            default -> new Task(Kind.WORK, "alchemy", 3.0D, -2.0D, 0.0D, 1.0D);          // dry
                        };
                    }
                }
            }
            case SWEEPER -> {
                if (within(t, BREAKFAST_END, BREAKFAST_END + clear) || within(t, DINNER_END, DINNER_END + clear)) {
                    return new Task(Kind.SWEEP, "dining", 0.0D, -3.0D, 0.0D, 1.0D); // the dining hall after the meal
                }
                Task tea1 = p == Period.TRAINING ? teaBreak(m, t, day) : null;
                if (tea1 != null) {
                    return tea1;
                }
            }
            default -> { }
        }
        return null;
    }

    /** One tea break of 160-240 ticks at his own moment of the afternoon (servants-6). */
    private static Task teaBreak(SectRoster m, int t, long day) {
        int from = SectLessons.n("teaFrom", 6000) + SectStagger.ticks(m.key(), "tea" + day, 0, 1200);
        int len = 160 + SectStagger.ticks(m.key(), "tealen" + day, 0, 80);
        if (!within(t, from, from + len)) {
            return null;
        }
        return new Task(Kind.WORK, "dining", -4.0D + index(m) * 0.8D, 2.5D, 0.0D, -1.0D);
    }

    /**
     * Night life (peers-6): three overlapping windows of 3800 ticks, each with one civilian: a pupil who reads, a pupil
     * who does the forms, a servant who walks the camp with a light. Never a person on the night rota (the caller has
     * already handled those), so one to three are awake at once.
     */
    static Task nightAwake(SectRoster m, long dayTime) {
        int t = (int) Math.floorMod(dayTime, (long) SectSchedule.DAY);
        long day = SectSchedule.day(dayTime);
        for (int slot = 0; slot < 3; slot++) {
            int from = SectLessons.n("nightFrom", 14000) + slot * SectLessons.n("nightStep", 3000);
            if (!within(t, from, from + SectLessons.n("nightLen", 3800))) {
                continue;
            }
            List<SectRoster> pool = slot == 2 ? lazy() : SectRoster.generation(3);
            if (pool.isEmpty()) {
                continue;
            }
            int pick = (int) Math.floorMod(day * 5L + slot * 7L + 3L, (long) pool.size());
            if (!pool.get(pick).equals(m)) {
                continue;
            }
            return switch (slot) {
                case 0 -> m.lay() ? rest(m) : SectSchedule.rest(m);
                case 1 -> new Task(Kind.DRILL, "training", -8.0D, -9.0D, 0.0D, 1.0D);
                default -> new Task(Kind.CHORE, "camp", 6.0D, 0.0D, 0.0D, 1.0D);
            };
        }
        return null;
    }

    private static List<SectRoster> lazy() {
        List<SectRoster> out = new ArrayList<>();
        for (SectRoster r : all()) {
            if (r.role() != SectRole.STEWARD) {
                out.add(r);
            }
        }
        return out;
    }
}
