package io.github.verycooltimo.murim.sect;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Дежурство второго поколения (состав секты, принято автором 05.10: «охрана — ученики второго поколения по сменам:
 * 2 днём и 2 ночью; отдельной стражи нет»). Чистая функция от дня секты: её читают распорядок
 * ({@link SectSchedule}), охрана ({@link SectWatch}) и юнит-тесты.
 *
 * <p>Пул дежурных — второе поколение без старшего (Пэк Чхон всегда на площадке поединков): семь человек. В день
 * {@code d} дневная смена — {@code pool[2d], pool[2d+1]}, ночная — {@code pool[2d+4], pool[2d+5]} (по модулю семи).
 * Сдвиг на четыре нужен, чтобы ночная смена вчерашнего дня ({@code pool[2d+2], pool[2d+3]}), которая утром спит,
 * не совпала ни с сегодняшней дневной, ни с сегодняшней ночной. Свободны днём трое: {@code pool[2d+4..2d+6]}
 * (ночные дежурные днём ещё занимаются).
 *
 * <p>Посты: 0 — вход в главный зал со стороны площади, 1 — казна. Ворота днём держит Ун Ам; ночью ворота без поста
 * (ночью чужака ловит дальний обход — не сделано, [ПРЕДЛАГАЮ] при нужде третий пост).
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
}
