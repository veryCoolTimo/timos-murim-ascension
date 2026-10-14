package io.github.verycooltimo.murim.mastery;

/**
 * Правила обрывков манускриптов и дубликатов книг (docs/design/24-bandit-camp.md §3, этап M3:
 * «решение о пользе обрывков и дубликатов»). Без мира и игрока — только решение.
 *
 * <p>Ни одна находка не бесполезна:
 * <ul>
 *   <li>техника незнакома — три страницы одной книги сшиваются в рваный манускрипт (предел пропорционален прежней глубине 2);</li>
 *   <li>знакома, но выучена по рваной книге — страница продолжает её: предел +1 звезда;</li>
 *   <li>предел полный — страница даёт озарение: доля пережитого к следующей звезде сразу в освоение;</li>
 *   <li>техника освоена до конца — страница даёт немного мудрости.</li>
 * </ul>
 * Дубликат книги, которой нечему больше научить, при перечитывании даёт озарение крупнее страницы.
 */
public final class PageRules {

    /** Сколько страниц одной книги сшиваются в рваный манускрипт. */
    public static final int PAGES_TO_BIND = 3;

    /** До какого звезды учит сшитый из страниц манускрипт. */
    public static final int BOUND_DEPTH = 2;

    /** Озарение от страницы: доля пережитого, нужного на текущую звезду. */
    public static final double PAGE_INSIGHT = 0.4D;

    /** Озарение от перечитанной знакомой книги: целая звезда пережитого. */
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
        /** Предел полный, звезда ниже предела: озарение. */
        INSIGHT,
        /** Освоено до конца: мудрость. */
        WISDOM
    }

    /**
     * @param known      знает ли игрок технику (для стиля — хоть одну его форму)
     * @param pagesHeld  страниц этой книги в стопке в руке
     * @param cap        предел звёзд по найденным книгам (для стиля — у формы-фронтира)
     * @param artCap     сколько звёзд у искусства всего
     * @param stars      достигнутые звёзды
     * @param anyShallow есть ли среди выученных техник этой книги неполный предел
     */
    public static Outcome page(boolean known, int pagesHeld, int cap, int artCap, int stars, boolean anyShallow) {
        if (!known) {
            return pagesHeld >= PAGES_TO_BIND ? Outcome.BIND : Outcome.NEED_MORE;
        }
        if (anyShallow || cap < artCap) {
            return Outcome.DEEPEN;
        }
        return stars < cap ? Outcome.INSIGHT : Outcome.WISDOM;
    }

    /** Перечитанная знакомая книга, которой нечему научить: озарение или, если освоено всё, мудрость. */
    public static Outcome reread(int cap, int stars) {
        return stars < cap ? Outcome.INSIGHT : Outcome.WISDOM;
    }

    /** Новый предел после страницы-продолжения: на одну звезду глубже, не выше полного. */
    public static int deepen(int cap, int artCap) {
        return Math.min(artCap, cap + 1);
    }

    /** Сколько освоения даёт озарение на звезде {@code stars}. */
    public static double insight(int stars, double share) {
        return MasteryRules.need(stars) * share;
    }

    private PageRules() {
    }
}
