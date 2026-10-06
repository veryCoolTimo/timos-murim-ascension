package io.github.verycooltimo.murim.sect;

/**
 * Разница во времени между людьми горы (автор 06.10: «не хорошо, что они все одновременно делают — нужна разница во
 * времени»). Личные сдвиги по ключу человека: детерминированные (один и тот же человек всегда встаёт из-за стола
 * позже соседа), без состояния. Строй делает формы в такт намеренно — сдвигается только приход в строй.
 */
public final class SectStagger {

    /** Опоздание к смене части суток, тиков: 5..40 с. */
    public static final int LAG_MIN = 100;
    public static final int LAG_MAX = 800;
    /** Отклик на дождь (и выход из-под крыши), тиков: 2..20 с. */
    public static final int RAIN_MIN = 40;
    public static final int RAIN_MAX = 400;

    private SectStagger() {
    }

    /** Число 0..1 по ключу человека и назначению. */
    public static double unit(String key, String what) {
        long z = (long) key.hashCode() * 0x9E3779B97F4A7C15L + what.hashCode();
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }

    /** Личное число тиков в [min, max]. */
    public static int ticks(String key, String what, int min, int max) {
        return min + (int) Math.round(unit(key, what) * (max - min));
    }

    /** Личный множитель 1 ± spread (темп шага). */
    public static double factor(String key, String what, double spread) {
        return 1.0D + spread * (2.0D * unit(key, what) - 1.0D);
    }

    /**
     * Сдвигается ли этот человек по распорядку. Глава, старейшины, наставник и привратник живут по колоколу: у них
     * доклады, совет и строй с окнами короче сдвига. Сдвигаются ученики и слуги — толпа.
     */
    public static boolean staggered(SectRoster m) {
        return switch (m.role()) {
            case LEADER, ELDER, MENTOR, GATEKEEPER -> false;
            default -> true;
        };
    }

    /** Опоздание этого человека к смене части суток, тиков. */
    public static int lag(SectRoster m) {
        return staggered(m) ? ticks(m.key(), "lag", LAG_MIN, LAG_MAX) : 0;
    }

    /**
     * Время распорядка для человека: в первые {@link #lag} тиков новой части суток он ещё доделывает прежнее дело (не
     * все встают из-за стола в один тик). Внутри части суток — время мира.
     */
    public static long scheduleTime(SectRoster m, long dayTime) {
        int lag = lag(m);
        if (lag <= 0) {
            return dayTime;
        }
        long late = dayTime - lag;
        return SectSchedule.at(late) == SectSchedule.at(dayTime) ? dayTime : late;
    }
}
