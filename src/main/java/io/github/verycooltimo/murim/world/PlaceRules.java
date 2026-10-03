package io.github.verycooltimo.murim.world;

/**
 * Правила мест силы без мира (docs/design/19b §3): радиус, множитель, цикл волн.
 *
 * <p>У узла свой цикл, независимый от игрока: вставание отсчёт не сбрасывает (иначе можно
 * вставать каждые 29 с и брать ×2 без риска — замечание codex, раунд 2). Одна функция для
 * сервера и клиента: клиент по тому же времени мира показывает предупреждение.
 */
public final class PlaceRules {

    /** Медитация в этом радиусе от камня ускоряется. */
    public static final double RADIUS = 6.0D;

    /** Прирост запаса у узла (автор 01.10: места силы «только немного ускоряют»). */
    public static final double GAIN = 2.0D;

    /** Природное место (пик, вода, старое дерево) — слабее камня жилы. */
    public static final double NATURAL_GAIN = 1.5D;

    /** Высота пика: с неё открытое небо — место силы. */
    public static final int PEAK_HEIGHT = 110;

    /** Сколько текущей (не стоячей) воды рядом делает место водным: водопад, стремнина. */
    public static final int WATER_FLOWING = 4;

    /** Сколько брёвен рядом — старое большое дерево (у обычного 4–6). */
    public static final int FOREST_LOGS = 14;

    /** Волна раз в 30–45 с — у каждого узла свой период. */
    public static final int PERIOD_MIN = 600;
    public static final int PERIOD_SPREAD = 300;

    /** Предупреждение за 4 с: аура вспыхивает, земля гудит. */
    public static final int WARNING = 80;

    /** После волны приток сбит на 3 с. */
    public static final int STUN = 60;

    private PlaceRules() {
    }

    /** Перемешанный хэш позиции: у соседних узлов — разные периоды и фазы. */
    public static long hash(long packedPos) {
        long z = packedPos * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public static int period(long packedPos) {
        return PERIOD_MIN + (int) Math.floorMod(hash(packedPos), (long) PERIOD_SPREAD);
    }

    /** Тиков до следующей волны (0 — волна на этом тике). */
    public static int untilWave(long packedPos, long gameTime) {
        int period = period(packedPos);
        long phase = Math.floorMod(gameTime + (hash(packedPos) >>> 8), (long) period);
        return (int) ((period - phase) % period);
    }

    public static boolean waveNow(long packedPos, long gameTime) {
        return untilWave(packedPos, gameTime) == 0;
    }

    /** Предупреждение: 0..1, единица — волна вот-вот. */
    public static float warning(long packedPos, long gameTime) {
        int left = untilWave(packedPos, gameTime);
        return left <= WARNING ? 1.0F - left / (float) WARNING : 0.0F;
    }

    /** Тиков с последней волны. */
    public static int sinceWave(long packedPos, long gameTime) {
        int period = period(packedPos);
        return (period - untilWave(packedPos, gameTime)) % period;
    }
}
