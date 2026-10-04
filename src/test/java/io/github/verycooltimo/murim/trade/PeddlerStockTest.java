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
    @DisplayName("поход в лагерь окупает две-три пилюли, но не две страницы")
    void campRunBuysPillsNotPages() {
        int pill = PeddlerStock.sellPrice("murim:pill_snow_plum");
        int page = PeddlerStock.sellPrice("murim:manual_page");
        assertTrue(PeddlerStock.CAMP_RUN_SILVER / pill >= 2 && PeddlerStock.CAMP_RUN_SILVER / pill <= 3, "пилюль за поход: " + PeddlerStock.CAMP_RUN_SILVER / pill);
        assertTrue(PeddlerStock.CAMP_RUN_SILVER / page == 1, "страниц за поход: " + PeddlerStock.CAMP_RUN_SILVER / page);
        assertTrue(PeddlerStock.sellPrice("murim:pill_origin_energy") > PeddlerStock.CAMP_RUN_SILVER, "редкая пилюля дешевле похода");
    }

    @Test
    @DisplayName("перепродажей серебро не делается: скупка много дешевле продажи")
    void noArbitrage() {
        int page = PeddlerStock.sellPrice("murim:manual_page");
        int back = PeddlerStock.buyPrice("murim:manual_page");
        assertTrue(back * 5 <= page, "страница: продажа " + page + ", скупка " + back);
        for (PeddlerStock.Line l : PeddlerStock.BUYS) {
            assertTrue(l.silver() <= 2, "скупка дороже двух лян: " + l);
        }
    }

    @Test
    @DisplayName("еда и учебное оружие доступны новичку")
    void basicsAreCheap() {
        assertTrue(PeddlerStock.sellPrice("minecraft:bread") <= 1);
        assertTrue(PeddlerStock.sellPrice("murim:wooden_sword") <= 3);
        assertTrue(PeddlerStock.sellPrice("murim:tang_dagger") <= 6);
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
        assertEquals(5000, n.get("murim:six_harmonies"), 2);
        assertEquals(1000, n.get("murim:wind_god_steps"), 2);
    }
}
