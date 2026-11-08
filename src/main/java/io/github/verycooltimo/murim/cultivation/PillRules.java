package io.github.verycooltimo.murim.cultivation;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Правила пилюль без мира: окно «сразу», ранг и нагрузка, привыкание, награда (docs/design/19b; numbers of the author, 25.10).
 *
 * <p>Канон: «простое употребление пилюли не увеличивает силу — сила растёт, только если ци в таблетках правильно поглощается»
 * (Возрождение Хуашань, гл. 41). The qi of a pill is fixed ({@link PillKind#qi()}), never a share of the wall. Eat and do not sit:
 * {@link #STANDING_SHARE}. Sit: the game «Провести поток» decides 40-100 % ({@link io.github.verycooltimo.murim.cultivation.flow.FlowGame}).
 */
public final class PillRules {

    /** Окно «сразу»: 12 с от первой пилюли, +3 с за каждую следующую, не больше 18 с от первой. */
    public static final int WINDOW_TICKS = 240;
    public static final int WINDOW_EXTEND = 60;
    public static final int WINDOW_MAX = 360;

    /** Съел и не сел: срабатывает пятая часть ци (автор 25.10). */
    public static final double STANDING_SHARE = 0.20D;

    /** A game ended at the end of the stream settles at least this much (the mockup: «Усвоено» is 40..100 %). */
    public static final double MIN_SAT_QUALITY = 0.40D;

    /** Burst (pressure to the top): a tenth of the qi still settles, and the body is hurt. */
    public static final double BURST_QUALITY = 0.10D;

    /** Several pills in one stream: a clean absorption (quality from this) gives +10 % for every extra pill. */
    public static final double CLEAN_QUALITY = 0.80D;
    public static final double COMBO_BONUS = 0.10D;
    /** A flawless absorption of a high pill lifts the player (the old scene is kept). */
    public static final double FLAWLESS_QUALITY = 0.95D;

    /** The load (qi of all the pills times the rank rule) beyond this many walls of the player tears the body. */
    public static final double TORN_LOAD = 1.5D;

    /** Same-kind repeats recover independently, one step per quarter day. */
    public static final double TOLERANCE = 0.75D;
    public static final double MIN_TOLERANCE = 0.25D;
    public static final long TOLERANCE_RECOVERY_TICKS = 6000L;
    public static final double GENTLE_SHARE = 0.5D;

    public static double toleranceFactor(int steps) {
        return Math.max(MIN_TOLERANCE, Math.pow(TOLERANCE, Math.max(0, steps)));
    }

    /** Vitality feeds an awakened beginner; gentle treasures cannot tear by rank. */
    public static double rankMultiplier(int playerRank, PillKind kind) {
        int d = delta(playerRank, kind);
        if (kind == PillKind.VITALITY && playerRank == Realm.NONE) return 1.0D;
        if (kind.gentle() && d < 0) return GENTLE_SHARE;
        return mult(d);
    }

    /** A pill that would tear the body is not eaten at the first try; the second try within this many ticks confirms. */
    public static final int WARN_TICKS = 80;

    /** Torn body: half of the maximal health (not below one heart), a part of the pool knocked, the circulating qi gone. */
    public static final double TORN_HEALTH = 0.5D;
    public static final double TORN_POOL_LOSS = Realm.INJURY_POOL_LOSS;

    /** Health share lost to a burst (high pills tear harder). */
    public static double backlashLoss(PillTier top) {
        return top.lifts() ? 0.6D : 0.4D;
    }

    /**
     * Ordinary qi pills now run the short automatic scene. This legacy reserve is paid over ten minutes of play.
     */
    public static final int RESERVE_TICKS = 12000;
    public static final int RESERVE_STEP = 20;

    public static final double PERFECT_MERIDIANS = 0.01D;

    /** Сколько пилюль в составе. */
    public static final int MAX_COMBO = 3;

    /**
     * Пилюли можно и в бою (автор 19.10), но сидящий за поглощением уязвим: любой удар — искажение ци,
     * потеря половины максимального здоровья (не ниже единицы).
     */
    public static final double DEVIATION_LOSS = 0.5D;

    /** Почему пилюлю нельзя съесть сейчас. */
    public enum Refusal { NONE, MEDITATING, SAME_KIND, FULL, NO_DANTIAN, ABSORBING }

    private PillRules() {
    }

    // ------------------------------------------------------------------ the rank rule

    /** Rank difference: positive when the player is above the pill's rank. */
    public static int delta(int playerRank, PillKind kind) {
        return playerRank - kind.rank();
    }

    /**
     * The rank rule (author 25.10): two ranks below the pill tears the body (0), one below halves the qi and makes the game harder,
     * the own rank takes it whole, one above gives +10 %, two or more above +20 %.
     */
    public static double mult(int d) {
        return d <= -2 ? 0.0D : d == -1 ? 0.5D : d == 0 ? 1.0D : d == 1 ? 1.1D : 1.2D;
    }

    /**
     * What a sitting is worth before the game is played.
     *
     * @param mult      the rank multiplier of every pill
     * @param tolerance the tolerance of the same pill in a row (1 - none); it applies to a single pill only
     * @param load      sum of qi × rank multiplier, times the tolerance
     * @param wall      the player's wall
     * @param tooHigh   some pill is two or more ranks above the player
     * @param overload  the load is beyond {@link #TORN_LOAD} walls
     * @param hard      some pill is exactly one rank above the player: the game is harder
     */
    public record Plan(List<PillKind> kinds, double[] mult, double tolerance, double load, double wall, boolean tooHigh,
                       boolean overload, boolean hard, boolean assisted) {

        public boolean torn() {
            return tooHigh || overload;
        }

        /** Loaded qi if absorbed at quality {@code q} (0..1): the bonus of a clean combination is included. */
        public double gain(double q) {
            return absorbedLoad() * q * combo(q);
        }

        public double share(int i) {
            return !assisted && kinds.get(i).greatElixir() ? 2.0D / 3.0D : 1.0D;
        }

        public double absorbedLoad() {
            double qi = 0.0D;
            for (int i = 0; i < kinds.size(); i++) qi += kinds.get(i).qi() * mult[i] * tolerance * share(i);
            return qi;
        }

        /** +10 % for every extra pill in the stream, but only for a clean absorption. */
        public double combo(double q) {
            return q >= CLEAN_QUALITY ? 1.0D + COMBO_BONUS * (kinds.size() - 1) : 1.0D;
        }
    }

    public static Plan plan(List<PillKind> kinds, int playerRank, double wall, double tolerance) {
        return plan(kinds, playerRank, wall, tolerance, false);
    }

    /** Elder guidance is only for a single great elixir: full qi, no rank/load tearing. */
    public static Plan plan(List<PillKind> kinds, int playerRank, double wall, double tolerance, boolean assisted) {
        if (assisted && kinds.size() == 1 && kinds.get(0).greatElixir()) {
            return new Plan(List.copyOf(kinds), new double[] {1.0D}, 1.0D, kinds.get(0).qi(), wall, false, false, false, true);
        }
        double[] mult = new double[kinds.size()];
        double load = 0.0D;
        boolean tooHigh = false;
        boolean hard = false;
        for (int i = 0; i < mult.length; i++) {
            int d = delta(playerRank, kinds.get(i));
            mult[i] = rankMultiplier(playerRank, kinds.get(i));
            load += kinds.get(i).qi() * mult[i];
            tooHigh |= d <= -2 && !kinds.get(i).gentle();
            hard |= d < 0 && !(kinds.get(i) == PillKind.VITALITY && playerRank == Realm.NONE);
        }
        load *= tolerance;
        // Clean Purple Heaven qi and the ordinary herbs cannot tear even in a small body.
        boolean overload = !tooHigh && kinds.stream().anyMatch(k -> !k.gentle()) && load > wall * TORN_LOAD;
        return new Plan(List.copyOf(kinds), mult, tolerance, load, wall, tooHigh, overload, hard, false);
    }

    /**
     * Tolerance factor of a sitting: ×0.75 per same-kind repeat, at least ×0.25. It does not apply to
     * several pills eaten together (and they reset it).
     */
    public static double tolerance(PillState state, List<PillKind> kinds, long now) {
        if (kinds.size() != 1) {
            return 1.0D;
        }
        return toleranceFactor(state.streakOf(kinds.get(0), now));
    }

    // ------------------------------------------------------------------ the window

    /**
     * Можно ли добавить пилюлю к уже съеденным в окне: до трёх, разных, в один поток.
     *
     * @param pending съеденные в открытом окне
     */
    public static Refusal canAdd(List<PillKind> pending, PillKind kind) {
        if (pending.isEmpty()) {
            return Refusal.NONE;
        }
        if (pending.contains(kind)) {
            return Refusal.SAME_KIND;
        }
        if (pending.size() >= MAX_COMBO) {
            return Refusal.FULL;
        }
        return Refusal.NONE;
    }

    /**
     * Конец окна после очередной пилюли.
     *
     * @param first    тик первой пилюли окна
     * @param deadline прежний конец окна; для первой пилюли не важен
     * @param now      тик этой пилюли
     */
    public static long deadline(long first, long deadline, long now, boolean firstPill) {
        if (firstPill) {
            return now + WINDOW_TICKS;
        }
        return Math.min(first + WINDOW_MAX, Math.max(deadline, now) + WINDOW_EXTEND);
    }

    // ------------------------------------------------------------------ order and extras

    /** Высшая ступень состава; пустой состав — обычная. */
    public static PillTier top(List<PillKind> pills) {
        PillTier top = PillTier.COMMON;
        for (PillKind k : pills) {
            top = top.max(k.tier());
        }
        return top;
    }

    /** Подлёт: безупречное усвоение и в составе есть высшая пилюля. */
    public static boolean lifts(List<PillKind> pills, boolean flawless) {
        return flawless && top(pills).lifts();
    }

    private static Set<PillKind> set(List<PillKind> pills) {
        return pills.isEmpty() ? EnumSet.noneOf(PillKind.class) : EnumSet.copyOf(pills);
    }

    /** Полный канонический состав (гл. 228): изначальная + тысяча ядов + слеза. */
    public static boolean canonTriple(List<PillKind> pills) {
        return set(pills).containsAll(EnumSet.of(PillKind.ORIGIN_ENERGY, PillKind.THOUSAND_POISON, PillKind.BEAUTY_TEAR));
    }

    /** Добавка чистоты за состав «изначальная + тысяча ядов» (один раз за сессию). */
    public static double comboPurity(List<PillKind> pills) {
        Set<PillKind> s = set(pills);
        return s.contains(PillKind.ORIGIN_ENERGY) && s.contains(PillKind.THOUSAND_POISON) ? 0.02D : 0.0D;
    }
}
