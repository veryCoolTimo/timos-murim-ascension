package io.github.verycooltimo.murim.trade;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Экономика торговца против добычи лагеря (docs/design/24-bandit-camp.md §7). */
class PeddlerStockTest {

    @Test
    void rareRootsAndIceHaveVisitLimits() {
        var root = PeddlerStock.SELLS.stream().filter(l -> l.item().equals("murim:thousand_ginseng")).findFirst().orElseThrow();
        assertEquals(0.35D, root.chance());
        assertEquals(1, root.count() * root.uses());
        var ice = PeddlerStock.SELLS.stream().filter(l -> l.item().equals("murim:ice_crystal")).findFirst().orElseThrow();
        assertTrue(ice.count() * ice.uses() >= 1 && ice.count() * ice.uses() <= 2);
        assertEquals(Coins.silver(35), ice.wen());
    }

    @Test
    void huaMapIsGuaranteedForMembersAndOutsidersAtSamePrice() {
        assertTrue(PeddlerStock.offersHuaMap(false));
        assertTrue(PeddlerStock.offersHuaMap(true));
        assertEquals(1.0D, PeddlerStock.HUA_MAP.chance());
        assertEquals(Coins.silver(2), PeddlerStock.sellPrice("minecraft:filled_map"));
    }

    @Test
    @DisplayName("поход в лагерь окупает две-три пилюли, но не две страницы")
    void campRunBuysPillsNotPages() {
        int run = Coins.silver(PeddlerStock.CAMP_RUN_SILVER);
        int pill = PeddlerStock.sellPrice("murim:pill_snow_plum");
        int page = PeddlerStock.sellPrice("murim:manual_page");
        assertTrue(run / pill >= 2 && run / pill <= 3, "пилюль за поход: " + run / pill);
        assertTrue(run / page == 1, "страниц за поход: " + run / page);
        assertTrue(PeddlerStock.sellPrice("murim:pill_small_restoration") > run, "редкая пилюля дешевле похода");
        // Origin Energy is a finite treasure, removed from renewable stock on 31.10.
        assertEquals(-1, PeddlerStock.sellPrice("murim:pill_origin_energy"));
    }

    @Test
    @DisplayName("перепродажей серебро не делается: скупка много дешевле продажи")
    void noArbitrage() {
        int page = PeddlerStock.sellPrice("murim:manual_page");
        int back = PeddlerStock.buyPrice("murim:manual_page");
        assertTrue(back * 5 <= page, "страница: продажа " + page + ", скупка " + back);
        for (PeddlerStock.Line l : PeddlerStock.BUYS) {
            assertTrue(l.wen() <= Coins.silver(2), "скупка дороже двух лян: " + l);
        }
    }

    @Test
    @DisplayName("еда и учебное оружие доступны новичку")
    void basicsAreCheap() {
        assertTrue(PeddlerStock.sellPrice("minecraft:bread") < Coins.WEN_PER_SILVER, "хлеб — за медь");
        assertTrue(PeddlerStock.sellPrice("murim:wooden_sword") <= Coins.silver(3));
        assertTrue(PeddlerStock.sellPrice("murim:tang_dagger") <= Coins.silver(6));
        assertTrue(PeddlerStock.sellPrice("murim:technique_manual") < PeddlerStock.sellPrice("murim:manual_page"),
                "книжка уличного искусства дороже страницы настоящей книги");
    }

    @Test
    @DisplayName("хлам из архива скупается за медь: 16 томов не дают больше 6 лян")
    void junkSellsForCopper() {
        int junk = PeddlerStock.buyPrice("murim:junk_manual");
        assertTrue(junk > 0 && junk < Coins.WEN_PER_SILVER, "том хлама: " + junk);
        assertTrue(junk * 16 <= Coins.silver(6), "полный завоз хлама: " + junk * 16);
    }

    @Test
    @DisplayName("любая цена ложится на прилавок двумя монетами и не дешевле таблицы")
    void pricesFitTwoSlots() {
        for (PeddlerStock.Line l : PeddlerStock.SELLS) {
            java.util.List<Coins.Pile> p = Coins.price(l.wen());
            assertTrue(p.size() >= 1 && p.size() <= 2, l + " → " + p);
            assertTrue(Coins.total(p) >= l.wen(), l + " дешевле таблицы: " + p);
            assertTrue(p.stream().allMatch(x -> x.count() <= 64), l + " не влезает в стопку: " + p);
        }
    }

    @Test
    @DisplayName("книги страниц на продажу — по весам сундуков лагеря")
    void pageBooksFollowWeights() {
        Map<String, Integer> n = new HashMap<>();
        int runs = 12000;
        for (int i = 0; i < runs; i++) {
            n.merge(PeddlerStock.pageBook((i + 0.5D) / runs), 1, Integer::sum);
        }
        assertEquals(PeddlerStock.PAGE_BOOKS.size(), n.size());
        assertEquals(runs * 5.0D / 23, n.get("murim:six_harmonies"), 2);
        assertEquals(runs / 23.0D, n.get("murim:wind_god_steps"), 2);
    }
}
