package io.github.verycooltimo.murim.mastery;

/**
 * Правила обрывков манускриптов и дубликатов книг (docs/design/24-bandit-camp.md §3, этап M3:
 * «решение о пользе обрывков и дубликатов»). Без мира и игрока — только решение.
 *
 * <p>Ни одна находка не бесполезна:
 * <ul>
 *   <li>техника незнакома — три страницы одной книги сшиваются в рваный манускрипт (до слоя 2);</li>
 *   <li>знакома, но выучена по рваной книге — страница продолжает её: предел +1 слой;</li>
 *   <li>предел полный — страница даёт озарение: доля пережитого к следующему слою сразу в освоение;</li>
 *   <li>техника освоена до конца — страница даёт немного мудрости.</li>
 * </ul>
 * Дубликат книги, которой нечему больше научить, при перечитывании даёт озарение крупнее страницы.
 */
public final class PageRules {

    /** Сколько страниц одной книги сшиваются в рваный манускрипт. */
    public static final int PAGES_TO_BIND = 3;

    /** До какого слоя учит сшитый из страниц манускрипт. */
    public static final int BOUND_DEPTH = 2;

    /** Озарение от страницы: доля пережитого, нужного на текущий слой. */
    public static final double PAGE_INSIGHT = 0.4D;

    /** Озарение от перечитанной знакомой книги: целый слой пережитого. */
    public static final double BOOK_INSIGHT = 1.0D;

    /** Мудрость, когда техника освоена до конца: страница и книга. */
    public static final double PAGE_WISDOM = 0.15D;
    public static final double BOOK_WISDOM = 0.5D;

    /** Что даёт прочтение страницы. */
    public enum Outcome {
        /** Незнакомая техника, страниц хватает: сшить рваный манускрипт. */
        BIND,
        /** Незнакомая техника, страниц мало: только счёт «1/3». */
        NEED_MORE,
        /** Знакомая техника с неполным пределом: продолжение, предел +1. */
        DEEPEN,
        /** Предел полный, слой ниже предела: озарение. */
        INSIGHT,
        /** Освоено до конца: мудрость. */
        WISDOM
    }

    /**
     * @param known      знает ли игрок технику (для стиля — хоть одну его форму)
     * @param pagesHeld  страниц этой книги в стопке в руке
     * @param cap        предел слоёв по найденным книгам (для стиля — у формы-фронтира)
     * @param layers     сколько слоёв у техники всего
     * @param layer      пройденный слой
     * @param anyShallow есть ли среди выученных техник этой книги неполный предел
     */
    public static Outcome page(boolean known, int pagesHeld, int cap, int layers, int layer, boolean anyShallow) {
        if (!known) {
            return pagesHeld >= PAGES_TO_BIND ? Outcome.BIND : Outcome.NEED_MORE;
        }
        if (anyShallow || cap < layers) {
            return Outcome.DEEPEN;
        }
        return layer < cap ? Outcome.INSIGHT : Outcome.WISDOM;
    }

    /** Перечитанная знакомая книга, которой нечему научить: озарение или, если освоено всё, мудрость. */
    public static Outcome reread(int cap, int layer) {
        return layer < cap ? Outcome.INSIGHT : Outcome.WISDOM;
    }

    /** Новый предел после страницы-продолжения: на один слой глубже, не выше полного. */
    public static int deepen(int cap, int layers) {
        return Math.min(layers, cap + 1);
    }

    /** Сколько освоения даёт озарение на слое {@code layer}. */
    public static double insight(int layer, double share) {
        return MasteryRules.need(layer) * share;
    }

    private PageRules() {
    }
}
