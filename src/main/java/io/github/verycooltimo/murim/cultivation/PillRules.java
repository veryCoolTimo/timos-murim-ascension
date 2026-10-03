package io.github.verycooltimo.murim.cultivation;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Правила пилюль без мира: окно «сразу», составы, повтор, награда (docs/design/19b §1–2).
 *
 * <p>Канон: «простое употребление пилюли не увеличивает силу — сила растёт, только если ци
 * в таблетках правильно поглощается» (Возрождение Хуашань, гл. 41). Решение автора 03.10:
 * съел и сразу сел — мини-игра; выполнил — эффект ×10. Отсюда «не сел» = десятая часть.
 */
public final class PillRules {

    /** Окно «сразу»: 12 с от первой пилюли, +3 с за каждую следующую, не больше 18 с от первой. */
    public static final int WINDOW_TICKS = 240;
    public static final int WINDOW_EXTEND = 60;
    public static final int WINDOW_MAX = 360;

    /** Не сел — срабатывает десятая часть (×1 против ×10). */
    public static final double BASE_SHARE = 0.1D;

    /** Сколько пилюль в составе. */
    public static final int MAX_COMBO = 3;

    /** Пилюли не едят в бою: если били последние 5 с. */
    public static final int COMBAT_LOCK_TICKS = 100;

    /** Почему пилюлю нельзя съесть сейчас. */
    public enum Refusal { NONE, SAME_KIND, FULL, SNOW_ALONE, IN_COMBAT, NO_DANTIAN }

    private PillRules() {
    }

    /**
     * Можно ли добавить пилюлю к уже съеденным в окне.
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
        // Сливовую едят отдельно: в составе она ничего не даёт, только занимает место.
        if (kind == PillKind.SNOW_PLUM || pending.contains(PillKind.SNOW_PLUM)) {
            return Refusal.SNOW_ALONE;
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

    /** Множитель за повтор того же типа: вторая — половина, третья — четверть… */
    public static double repeatFactor(int alreadyTaken) {
        return Math.pow(0.5D, Math.max(0, alreadyTaken));
    }

    private static Set<PillKind> set(List<PillKind> pills) {
        return pills.isEmpty() ? EnumSet.noneOf(PillKind.class) : EnumSet.copyOf(pills);
    }

    /** Полный канонический состав (гл. 228): изначальная + тысяча ядов + слеза. */
    public static boolean canonTriple(List<PillKind> pills) {
        return set(pills).containsAll(EnumSet.of(PillKind.ORIGIN_ENERGY, PillKind.THOUSAND_POISON, PillKind.BEAUTY_TEAR));
    }

    /** Множитель ко всей сумме состава. */
    public static double comboMultiplier(List<PillKind> pills) {
        Set<PillKind> s = set(pills);
        if (canonTriple(pills)) {
            return 1.25D;
        }
        if (s.size() == 2 && s.contains(PillKind.ORIGIN_ENERGY) && s.contains(PillKind.THOUSAND_POISON)) {
            return 1.2D;
        }
        return 1.0D;
    }

    /**
     * Щит для слезы: «ци двух таблеток окружил даньтянь» (гл. 228). Множитель удара бурной
     * слезы по напряжению: 1 — щита нет.
     */
    public static double shield(List<PillKind> pills) {
        Set<PillKind> s = set(pills);
        if (!s.contains(PillKind.BEAUTY_TEAR)) {
            return 1.0D;
        }
        if (canonTriple(pills)) {
            return 0.5D;
        }
        if (s.contains(PillKind.THOUSAND_POISON)) {
            return 0.75D;
        }
        return 1.0D;
    }

    /** Добавка чистоты за состав «изначальная + тысяча ядов» (один раз за сессию). */
    public static double comboPurity(List<PillKind> pills) {
        Set<PillKind> s = set(pills);
        return s.contains(PillKind.ORIGIN_ENERGY) && s.contains(PillKind.THOUSAND_POISON) ? 0.02D : 0.0D;
    }

    /**
     * Эффективная доля поглощения: не сел или всё потерял — десятая часть, поглотил
     * полностью — единица, рискованные короткие пути — до 1,3.
     */
    public static double effective(double settled) {
        return Math.max(BASE_SHARE, settled);
    }

    /**
     * Прибавка к запасу от одной пилюли.
     *
     * @param wall      стена текущего ранга (ёмкость × 4)
     * @param settled   осевшая доля сгустка (0 — не сел)
     * @param taken     сколько таких уже съедено раньше
     * @param comboMult множитель состава
     */
    public static double poolGain(PillKind kind, double wall, double settled, int taken, double comboMult) {
        return kind.wallShare() * wall * effective(settled) * repeatFactor(taken) * comboMult;
    }

    /**
     * Постоянная прибавка (ёмкость, меридианы, чистота) масштабируется долей осевшего
     * (не больше единицы: риск даёт ци, а не потолок) и половинится при повторе.
     */
    public static double permanent(double base, double settled, int taken) {
        return base * Math.min(1.0D, effective(settled)) * repeatFactor(taken);
    }
}
