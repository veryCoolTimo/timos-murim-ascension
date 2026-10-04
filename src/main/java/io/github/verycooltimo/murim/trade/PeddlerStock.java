package io.github.verycooltimo.murim.trade;

import java.util.List;

/**
 * Товар и цены бродячего торговца (docs/design/24-bandit-camp.md §7) — чистые данные, без реестров:
 * их проверяет юнит-тест экономики ({@code PeddlerStockTest}), а {@link Peddler} собирает из них
 * ванильные предложения торговли.
 *
 * <p>Цены в серебряных лянах, от замера {@code /murim camp lootsim}: поход в лагерь даёт ~20 серебра.
 * Поход окупает две-три простые пилюли, но не две страницы: купить нужную страницу — заметная трата,
 * а не замена походу. Скупка дешёвая: страница уходит за шестую часть своей цены, хлам — за лян.
 */
public final class PeddlerStock {

    /** Серебра за один поход в лагерь (среднее по 1000 походам, docs/design/24 §4). */
    public static final int CAMP_RUN_SILVER = 20;

    /**
     * Строка товара.
     *
     * @param item    id предмета
     * @param count   сколько за раз
     * @param silver  цена в лянах (продажа игроку) или выручка (скупка у игрока)
     * @param uses    сколько раз до пополнения (раз в игровые сутки)
     * @param chance  с какой вероятностью строка есть в этом завозе (1 — всегда)
     * @param page    страница манускрипта: книга выбирается при завозе
     */
    public record Line(String item, int count, int silver, int uses, double chance, boolean page) {
    }

    /** Что торговец продаёт. */
    public static final List<Line> SELLS = List.of(
            new Line("minecraft:bread", 4, 1, 12, 1.0D, false),
            new Line("minecraft:cooked_mutton", 3, 2, 8, 1.0D, false),
            new Line("murim:wooden_sword", 1, 2, 2, 1.0D, false),
            new Line("murim:tang_dagger", 4, 5, 3, 1.0D, false),
            new Line("murim:pill_snow_plum", 1, 7, 3, 1.0D, false),
            new Line("murim:manual_page", 1, 13, 1, 0.5D, true),
            new Line("murim:pill_origin_energy", 1, 30, 1, 0.2D, false));

    /** Что торговец скупает. */
    public static final List<Line> BUYS = List.of(
            new Line("murim:junk_manual", 1, 1, 16, 1.0D, false),
            new Line("murim:junk_manual_blue", 1, 1, 16, 1.0D, false),
            new Line("murim:junk_manual_red", 1, 1, 16, 1.0D, false),
            new Line("murim:junk_letters", 2, 1, 8, 1.0D, false),
            new Line("murim:manual_page", 1, 2, 8, 1.0D, false));

    /**
     * Книги страниц на продажу и их веса — как в сундуках лагеря (data/murim/loot_table/chests/bandit_camp):
     * торговец скупил их у тех же разбойников.
     */
    public static final List<String> PAGE_BOOKS = List.of("murim:six_harmonies", "murim:seven_plum_blossoms",
            "murim:dark_fragrance_step", "murim:wind_god_steps");
    public static final int[] PAGE_WEIGHTS = {5, 3, 3, 1};

    /** Книга страницы по броску {@code roll} в [0, 1). */
    public static String pageBook(double roll) {
        int total = 0;
        for (int w : PAGE_WEIGHTS) {
            total += w;
        }
        double at = roll * total;
        for (int i = 0; i < PAGE_WEIGHTS.length; i++) {
            at -= PAGE_WEIGHTS[i];
            if (at < 0) {
                return PAGE_BOOKS.get(i);
            }
        }
        return PAGE_BOOKS.get(PAGE_BOOKS.size() - 1);
    }

    /** Цена продажи предмета (первая строка), −1 — не продаётся. */
    public static int sellPrice(String item) {
        return SELLS.stream().filter(l -> l.item().equals(item)).mapToInt(Line::silver).findFirst().orElse(-1);
    }

    /** Выручка за скупку предмета (за {@code count} штук строки), −1 — не скупается. */
    public static int buyPrice(String item) {
        return BUYS.stream().filter(l -> l.item().equals(item)).mapToInt(Line::silver).findFirst().orElse(-1);
    }

    private PeddlerStock() {
    }
}
