package io.github.verycooltimo.murim.client.bandit;

/**
 * Чистая логика выбора клипа и времени для бандитов (без классов Minecraft, чтобы проверять
 * юнит-тестами): перевод тиков фазы боя во время клипа и выбор фазы лука у лучника.
 */
public final class ClipPhases {

    /** «Никогда не было»: тиков с события больше любого окна клипа. */
    public static final float NEVER = 1.0E6F;

    /** Натяжение лука: клип draw 0,4 с. */
    public static final float DRAW_TICKS = 8.0F;
    /** Отдача после выстрела: клип shoot 0,15 с (3 тика). */
    public static final float SHOOT_TICKS = 3.0F;
    /** Опускание лука: клип lower 0,4 с. */
    public static final float LOWER_TICKS = 8.0F;

    /**
     * Время клипа фазы боя. Дуэльные фазы мечника короче клипов (замах 10 → 6 тиков, удар 6 → 2):
     * тики фазы переводятся в тики клипа линейно, внутри каждой фазы по отдельности, так что доли
     * клипа (ключ замаха, ключ удара) сохраняются, а не растягиваются на весь бой. Конец клипа не
     * перескакивается, если сервер задержал смену фазы.
     *
     * @param age        тиков с начала фазы (с долей кадра)
     * @param clipTicks  длина клипа этой фазы
     * @param phaseTicks сколько тиков фаза идёт в бою (равно clipTicks вне дуэльного сжатия)
     */
    public static float clipTime(float age, int clipTicks, int phaseTicks) {
        if (phaseTicks <= 0 || phaseTicks == clipTicks) {
            return age;
        }
        return Math.max(0.0F, Math.min(age * clipTicks / phaseTicks, clipTicks));
    }

    /** Фазы лучника: клипы draw, hold, shoot, lower; NONE — обычные покой и шаг. */
    public enum Phase { NONE, DRAW, HOLD, SHOOT, LOWER }

    /** Фаза и время клипа в ней (в тиках). */
    public record Pose(Phase phase, float ticks) {
        /** Имя клипа или null. */
        public String clip() {
            return switch (phase) {
                case DRAW -> "draw";
                case HOLD -> "hold";
                case SHOOT -> "shoot";
                case LOWER -> "lower";
                default -> null;
            };
        }

        /** Вес позы лука над покоем: быстро входит при натяжении, плавно уходит при опускании. */
        public float weight() {
            return switch (phase) {
                case DRAW -> smooth(ticks / 3.0F);
                case HOLD, SHOOT -> 1.0F;
                case LOWER -> 1.0F - smooth(ticks / LOWER_TICKS);
                default -> 0.0F;
            };
        }
    }

    /**
     * Выбор фазы по состоянию сущности.
     *
     * @param stunned       оглушён: лук опущен, играет клип оглушения
     * @param using         идёт натяжение (предмет в использовании)
     * @param usingTicks    тиков натяжения
     * @param sinceShot     тиков с выстрела ({@link #NEVER}, если не стрелял)
     * @param sinceDrawEnd  тиков с конца натяжения ({@link #NEVER}, если не тянул)
     */
    public static Pose pick(boolean stunned, boolean using, float usingTicks, float sinceShot, float sinceDrawEnd) {
        if (stunned) {
            return new Pose(Phase.NONE, 0.0F);
        }
        if (using) {
            return usingTicks < DRAW_TICKS
                    ? new Pose(Phase.DRAW, usingTicks)
                    : new Pose(Phase.HOLD, usingTicks - DRAW_TICKS);
        }
        // Выстрел и конец натяжения приходят в один тик: считаем выстрелом, если он не раньше конца натяжения.
        boolean shot = sinceShot <= sinceDrawEnd + 1.0F && sinceShot < SHOOT_TICKS + LOWER_TICKS;
        if (shot) {
            return sinceShot < SHOOT_TICKS
                    ? new Pose(Phase.SHOOT, sinceShot)
                    : new Pose(Phase.LOWER, sinceShot - SHOOT_TICKS);
        }
        if (sinceDrawEnd < LOWER_TICKS) {
            return new Pose(Phase.LOWER, sinceDrawEnd);
        }
        return new Pose(Phase.NONE, 0.0F);
    }

    private static float smooth(float t) {
        t = Math.max(0.0F, Math.min(1.0F, t));
        return t * t * (3.0F - 2.0F * t);
    }

    private ClipPhases() {
    }
}
