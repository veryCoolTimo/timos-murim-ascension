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

    /**
     * What a page gives: a tenth of the next star's cost (author 18.10: "10 % of the experience of the transition, two pages
     * at most"). Two pages fill the help pool of the transition ({@link MasteryRules#HELP_CAP}); lessons, readings and the
     * Apex Rush draw from the same pool.
     */
    public static final double PAGE_INSIGHT = 0.10D;

    /** What a reread familiar book gives: the whole help pool of one transition. */
    public static final double BOOK_INSIGHT = MasteryRules.HELP_CAP;

    /** Pages that fit one transition (the pool is two pages). */
    public static final int MAX_PAGES_PER_TRANSITION = 2;

    /** Price of a shop page in merit: {@code 8 + 2 s}, where {@code s} is the star the page leads to (10 / 20 / 32 at ★1 / ★6 / ★12). */
    public static int pagePrice(int targetStar) {
        return 8 + 2 * Math.max(1, Math.min(Arts.MAX_STARS, targetStar));
    }

    /**
     * May a page of this art still be sold? Every page the player already carries keeps a tenth of the pool for itself,
     * so a later lesson never makes a bought page useless and the pack cannot hold more than the transition takes.
     *
     * @param room      what is left of the help pool of the next transition, in experience
     * @param cost      what the next star costs
     * @param pagesHeld pages of this book the player already carries
     */
    public static boolean saleOpen(double room, double cost, int pagesHeld) {
        return room + 1.0E-9D >= (pagesHeld + 1) * cost * PAGE_INSIGHT;
    }

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
        return insight(stars, share, Arts.MAX_STARS);
    }

    /** The same for an art with {@code artCap} stars (footwork has ten). */
    public static double insight(int stars, double share, int artCap) {
        return MasteryRules.need(stars, artCap) * share;
    }

    private PageRules() {
    }
}
