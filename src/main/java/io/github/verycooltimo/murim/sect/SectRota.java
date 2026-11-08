package io.github.verycooltimo.murim.sect;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Дежурство второго поколения (состав секты, принято автором 05.10: «охрана — ученики второго поколения по сменам:
 * 2 днём и 2 ночью; отдельной стражи нет»). Чистая функция от дня секты: её читают распорядок
 * ({@link SectSchedule}), охрана ({@link SectWatch}) и юнит-тесты.
 *
 * <p>Пул дежурных — второе поколение без старшего (Со Ран всегда на площадке поединков): семь человек. В день
 * {@code d} дневная смена — {@code pool[2d], pool[2d+1]}, ночная — {@code pool[2d+4], pool[2d+5]} (по модулю семи).
 * Сдвиг на четыре нужен, чтобы ночная смена вчерашнего дня ({@code pool[2d+2], pool[2d+3]}), которая утром спит,
 * не совпала ни с сегодняшней дневной, ни с сегодняшней ночной. Свободны днём трое: {@code pool[2d+4..2d+6]}
 * (ночные дежурные днём ещё занимаются).
 *
 * <p>Посты: 0 — вход в главный зал со стороны площади, 1 — казна. Ворота днём держит Гён Так;
 * ночью пост 0 переносится от главного зала к воротам.
 */
public final class SectRota {

    /** Постов на смене. */
    public static final int POSTS = 2;

    private SectRota() {
    }

    /** Дежурный: пост и смена. */
    public record Duty(int post, boolean night) {
    }

    /** Пул дежурных: второе поколение без старшего, в порядке списка. */
    public static List<SectRoster> pool() {
        List<SectRoster> out = new ArrayList<>();
        for (SectRoster m : SectRoster.generation(2)) {
            if (m.role() != SectRole.SENIOR) {
                out.add(m);
            }
        }
        return out;
    }

    private static SectRoster at(long offset) {
        List<SectRoster> pool = pool();
        return pool.get((int) Math.floorMod(offset, (long) pool.size()));
    }

    /** Дневной дежурный дня {@code day} на посту {@code post}. */
    public static SectRoster dayWatch(long day, int post) {
        return at(2L * day + post);
    }

    /** Ночной дежурный дня {@code day} (ночь 13000–23000 этого дня секты). */
    public static SectRoster nightWatch(long day, int post) {
        return at(2L * day + 4L + post);
    }

    /** Пост человека в смене дня {@code day}: −1 — не дежурит. */
    static int postOf(SectRoster m, long day, boolean night) {
        for (int i = 0; i < POSTS; i++) {
            if ((night ? nightWatch(day, i) : dayWatch(day, i)).equals(m)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Дежурство сейчас: дневная смена — с рассвета до вечера, ночная — ночью. Пусто — не на посту.
     */
    public static Optional<Duty> duty(SectRoster m, long dayTime) {
        if (m.generation() != 2 || m.role() == SectRole.SENIOR) {
            return Optional.empty();
        }
        boolean night = SectSchedule.at(dayTime) == SectSchedule.Period.NIGHT;
        int post = postOf(m, SectSchedule.day(dayTime), night);
        return post < 0 ? Optional.empty() : Optional.of(new Duty(post, night));
    }

    /** На посту сейчас. */
    public static boolean onDuty(SectRoster m, long dayTime) {
        return duty(m, dayTime).isPresent();
    }

    /**
     * Смена только что кончилась (сменяемый ещё ждёт на посту сменщика, окно {@link SectSchedule#HANDOVER}):
     * дневная — в начале ночи, ночная — в начале строя. Пусто — не сменяемый.
     */
    public static Optional<Duty> ending(SectRoster m, long dayTime) {
        if (m.generation() != 2 || m.role() == SectRole.SENIOR) {
            return Optional.empty();
        }
        SectSchedule.Period p = SectSchedule.at(dayTime);
        long day = SectSchedule.day(dayTime);
        if (p == SectSchedule.Period.NIGHT) {
            int post = postOf(m, day, false);
            return post < 0 ? Optional.empty() : Optional.of(new Duty(post, false));
        }
        if (p == SectSchedule.Period.FORMATION) {
            int post = postOf(m, day - 1L, true);
            return post < 0 ? Optional.empty() : Optional.of(new Duty(post, true));
        }
        return Optional.empty();
    }

    /** Сменщик того, у кого кончилась смена {@code ended}: ночная → утренняя дневная, дневная → ночная того же дня. */
    public static SectRoster relief(Duty ended, long dayTime) {
        long day = SectSchedule.day(dayTime);
        return ended.night() ? dayWatch(day, ended.post()) : nightWatch(day, ended.post());
    }

    /** Кого сменяет заступающий {@code duty} в этот час: дневной — вчерашнего ночного, ночной — дневного. */
    public static SectRoster relieved(Duty duty, long dayTime) {
        long day = SectSchedule.day(dayTime);
        return duty.night() ? dayWatch(day, duty.post()) : nightWatch(day - 1L, duty.post());
    }

    /** Отстоял ночь: в этот день секты спит до {@link SectSchedule#NIGHT_WATCH_WAKE}, потом отдыхает. */
    public static boolean afterNight(SectRoster m, long dayTime) {
        if (m.generation() != 2 || m.role() == SectRole.SENIOR || SectSchedule.at(dayTime) == SectSchedule.Period.NIGHT) {
            return false;
        }
        return postOf(m, SectSchedule.day(dayTime) - 1L, true) >= 0;
    }

    /**
     * Второе поколение, кто днём {@code day} занимается (не дежурит днём и не отсыпается после ночи): старший первым,
     * затем по списку.
     */
    public static List<SectRoster> training(long day) {
        List<SectRoster> out = new ArrayList<>();
        for (SectRoster m : SectRoster.generation(2)) {
            if (m.role() == SectRole.SENIOR || postOf(m, day, false) < 0 && postOf(m, day - 1L, true) < 0) {
                out.add(m);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the day of a guard (round, meal relief)

    /** One stop of a round lasts this long (ticks); then the guard walks on to the next. */
    public static final int ROUND_STOP = 1200;

    /**
     * The places a guard of the day stands at in turn (watch-1, 21.10: every closed place has a keeper). Post 0 is the hall
     * (and the vault beneath it) with the ancestors' and scriptures' halls; post 1 the treasury with the elders' houses and the
     * penance cave. At night post 0 stays at the gate (the gatekeeper sleeps and the gate must not stand empty) and post 1 walks
     * all the places in one round.
     */
    static String[] route(Duty duty) {
        if (duty.night()) {
            return duty.post() == 0 ? new String[] {"sect_gate"}
                    : new String[] {"treasury", "main_hall", "ancestors", "scriptures", "elders", "penance"};
        }
        // The hall front is the place the author looks at first (sect check 24.10: «в главном зале нет охраны»): its guard is there four stops
        // of six, not two of four; the round to the ancestors and the scriptures is short (and keeps off the stop of the council hours, 6000-7200, when
        // this guard stands at the council door anyway). Same for the treasury (post 1: «и там тоже нет охраны»).
        return duty.post() == 0 ? new String[] {"main_hall", "main_hall", "ancestors", "main_hall", "scriptures", "main_hall"}
                : new String[] {"treasury", "treasury", "elders", "penance", "treasury", "treasury"};
    }

    /**
     * Where the guard of {@code duty} stands now. The first {@link SectSchedule#HANDOVER} ticks of a shift he stays at his own post
     * (the relief and the bow happen there); then he walks the round, a stop every {@link #ROUND_STOP} ticks.
     */
    public static String stopZone(Duty duty, long dayTime) {
        String[] r = route(duty);
        SectSchedule.Period p = SectSchedule.at(dayTime);
        boolean shiftStart = (p == SectSchedule.Period.NIGHT || p == SectSchedule.Period.FORMATION)
                && SectSchedule.sincePeriodStart(dayTime) < SectSchedule.HANDOVER;
        if (r.length == 1 || shiftStart) {
            return r[0];
        }
        return r[(int) Math.floorMod(Math.floorDiv(Math.floorMod(dayTime, (long) SectSchedule.DAY), (long) ROUND_STOP), (long) r.length)];
    }

    /**
     * The second guard of the main-hall doors by day (author 22.10: «у главного зала вообще нет охраны»): the first-generation
     * disciple whose turn of the day is 3 ({@link SectSchedule#firstTurn}) stands in the training hours at the other guard post of
     * the hall front ({@code hall_front} in the layout), beside the guard of post 0 on his hall stops. He turns back the juniors.
     */
    public static boolean atHallDoor(SectRoster m, long dayTime) {
        return m.generation() == 1 && m.role() == SectRole.FIRST && SectSchedule.at(dayTime) == SectSchedule.Period.TRAINING
                && SectSchedule.firstTurn(m, SectSchedule.day(dayTime)) == 3;
    }

    /** What a person does in a meal period besides his post. */
    public enum Break { EAT, COVER }

    /** The guard's meal: who eats in which third of the period, and where the cover stands meanwhile. */
    public record Meal(Break kind, String zone) {
    }

    /** Length of a meal period (ticks): breakfast 1000, dinner 2000. */
    public static int mealLength(SectSchedule.Period p) {
        return p == SectSchedule.Period.BREAKFAST ? 1000 : 2000;
    }

    /**
     * Meal relief (watch-2, 21.10: «no meals»). In a meal period the gatekeeper eats in the first third, the guard of post 0 in
     * the second, the guard of post 1 in the third. While one eats, the other guard of the day stands in his place: post 0 covers
     * the gate, post 1 the hall, post 0 the treasury. Empty: this person has no part in it now.
     */
    public static Optional<Meal> meal(SectRoster m, long dayTime) {
        SectSchedule.Period p = SectSchedule.at(dayTime);
        if (!p.meal()) {
            return Optional.empty();
        }
        int third = Math.min(2, SectSchedule.sincePeriodStart(dayTime) * 3 / mealLength(p));
        long day = SectSchedule.day(dayTime);
        if (m.role() == SectRole.GATEKEEPER) {
            return third == 0 ? Optional.of(new Meal(Break.EAT, "sect_gate")) : Optional.empty();
        }
        int post = m.generation() == 2 ? postOf(m, day, false) : -1;
        if (post < 0 || m.role() == SectRole.SENIOR) {
            return Optional.empty();
        }
        if (third == 0 && post == 0) {
            return Optional.of(new Meal(Break.COVER, "sect_gate"));
        }
        if (third == 1) {
            return Optional.of(post == 0 ? new Meal(Break.EAT, "main_hall") : new Meal(Break.COVER, "main_hall"));
        }
        if (third == 2) {
            return Optional.of(post == 1 ? new Meal(Break.EAT, "treasury") : new Meal(Break.COVER, "treasury"));
        }
        return Optional.empty();
    }

    /** Eating his break meal now (not on watch while he does). */
    public static boolean eating(SectRoster m, long dayTime) {
        return meal(m, dayTime).map(x -> x.kind() == Break.EAT).orElse(false);
    }

    /** Responsibility lasts throughout the round, including the time spent walking between stops.
     * During a meal the remaining guard covers all closed places. The vault belongs to the hall post.
     */
    public static SectRoster keeper(String zone, long dayTime) {
        int post = switch (zone) {
            case "main_hall", "vault", "ancestors", "scriptures" -> 0;
            case "treasury", "elders", "penance" -> 1;
            default -> -1;
        };
        if (post < 0) return null;
        long day = SectSchedule.day(dayTime);
        if (SectSchedule.at(dayTime) == SectSchedule.Period.NIGHT) {
            // Post 0 must stay at the gate; post 1 keeps the entire inner round.
            return nightWatch(day, 1);
        }
        SectRoster keeper = dayWatch(day, post);
        return eating(keeper, dayTime) ? dayWatch(day, 1 - post) : keeper;
    }
}
