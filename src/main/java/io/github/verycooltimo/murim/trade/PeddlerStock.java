package io.github.verycooltimo.murim.trade;

import java.util.List;

import static io.github.verycooltimo.murim.trade.Coins.silver;

/**
 * Товар и цены бродячего торговца — ЕДИНАЯ таблица цен мода (docs/design/29-economy.md). Чистые данные, без
 * реестров: их проверяет юнит-тест экономики ({@code PeddlerStockTest}), а {@link Peddler} собирает из них
 * ванильные предложения торговли и раскладывает цену в монеты ({@link Coins#price}).
 *
 * <p>Все суммы — в медных вэнях ({@link Coins}: 9 вэней = лян серебра, 9 лян = лян золота). Баланс правится
 * здесь и только здесь; добыча (таблицы лута) задаётся генераторами tools/loot/*.py с теми же единицами.
 *
 * <p>Опора — замер {@code /murim dev camp lootsim}: поход в лагерь даёт ~20 лян серебра и горсть меди. Поход
 * окупает две-три простые пилюли, но не две страницы: купить нужную страницу — заметная трата, а не замена
 * походу. Скупка дешёвая: страница уходит за шестую часть своей цены, хлам из архива — за медяки.
 */
public final class PeddlerStock {

    /**
     * Серебра за один поход в лагерь в пересчёте (медь и золото по курсу; среднее по 1000 походам,
     * tools/loot/bandit_camp_loot.py, docs/design/29-economy.md §3). До меди было 20 лян серебром.
     */
    public static final int CAMP_RUN_SILVER = 22;

    /**
     * Строка товара.
     *
     * @param item    id предмета
     * @param count   сколько за раз
     * @param wen     цена в вэнях (продажа игроку) или выручка (скупка у игрока)
     * @param uses    сколько раз до пополнения (раз в игровые сутки)
     * @param chance  с какой вероятностью строка есть в этом завозе (1 — всегда)
     * @param book    что вписать в предмет: {@code page} — книга страницы по весам лагеря, {@code junk_art} —
     *                техника третьего сорта ({@code technique/JunkArts}), пусто — ничего
     */
    public record Line(String item, int count, int wen, int uses, double chance, String book) {
        public Line(String item, int count, int wen, int uses, double chance) {
            this(item, count, wen, uses, chance, "");
        }
    }

    public static final Line HUA_MAP = new Line("minecraft:filled_map", 1, silver(2), Integer.MAX_VALUE, 1.0D, "hua_map");

    public static boolean offersHuaMap(boolean member) { return true; }

    /** Что торговец продаёт. */
    public static final List<Line> SELLS = List.of(
            HUA_MAP,
            new Line("minecraft:bread", 4, 5, 12, 1.0D),
            new Line("minecraft:cooked_mutton", 3, 13, 8, 1.0D),
            new Line("murim:wooden_sword", 1, silver(2), 2, 1.0D),
            new Line("murim:tang_dagger", 4, silver(5), 3, 1.0D),
            // MVP K2: common roots replace guaranteed stacks of ancient treasures.
            new Line("murim:pill_vitality", 4, silver(4), 16, 1.0D),
            new Line("murim:hundred_ginseng", 2, silver(5), 6, 1.0D),
            new Line("murim:thousand_ginseng", 1, silver(28), 1, 0.35D),
            new Line("murim:ice_crystal", 1, silver(35), 2, 1.0D),
            new Line("murim:thousand_lingzhi", 4, silver(32), 4, 1.0D),
            new Line("murim:pill_snow_plum", 1, silver(7), 3, 1.0D),
            // Книжка уличного искусства: дешевле страницы — слабая техника с честным изъяном.
            new Line("murim:technique_manual", 1, silver(3), 1, 0.6D, "junk_art"),
            new Line("murim:manual_page", 1, silver(13), 1, 0.5D, "page"),
            new Line("murim:pill_small_restoration", 1, silver(24), 1, 0.08D));

    /** Что торговец скупает. */
    public static final List<Line> BUYS = List.of(
            new Line("murim:junk_manual", 1, 3, 16, 1.0D),
            new Line("murim:junk_manual_blue", 1, 3, 16, 1.0D),
            new Line("murim:junk_manual_red", 1, 3, 16, 1.0D),
            new Line("murim:junk_letters", 2, 2, 8, 1.0D),
            new Line("murim:manual_page", 1, silver(2), 8, 1.0D));

    /**
     * Размен (меняла при торговце): серебро ↔ медь и золото ↔ серебро без потерь, по курсу {@link Coins}.
     * То же делает верстак; у торговца — чтобы заплатить за хлеб, не отходя от прилавка.
     */
    public static final int EXCHANGE_USES = 64;

    /**
     * Page books and weights. Includes Falling Petal so sect pupils can deepen their curriculum through trade.
     */
    public static final List<String> PAGE_BOOKS = List.of("murim:six_harmonies", "murim:falling_petal_sword", "murim:seven_plum_blossoms",
            "murim:dark_fragrance_step", "murim:wind_god_steps",
            "murim:crescent_sweep", "murim:wedge_fan", "murim:ceremonial_draw");
    public static final int[] PAGE_WEIGHTS = {5, 3, 3, 3, 1, 3, 3, 2};

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

    /** Цена продажи предмета в вэнях (первая строка), −1 — не продаётся. */
    public static int sellPrice(String item) {
        return SELLS.stream().filter(l -> l.item().equals(item)).mapToInt(Line::wen).findFirst().orElse(-1);
    }

    /** Выручка за скупку предмета в вэнях (за {@code count} штук строки), −1 — не скупается. */
    public static int buyPrice(String item) {
        return BUYS.stream().filter(l -> l.item().equals(item)).mapToInt(Line::wen).findFirst().orElse(-1);
    }

    private PeddlerStock() {
    }
}
