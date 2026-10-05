package io.github.verycooltimo.murim.trade;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Монеты (docs/design/29-economy.md §1): курс 9:9, раскладка суммы и цена на две стопки. */
class CoinsTest {

    @Test
    @DisplayName("сумма раскладывается от золота к меди без потерь")
    void splitKeepsValue() {
        for (int wen = 0; wen < 2000; wen++) {
            List<Coins.Pile> p = Coins.split(wen);
            assertEquals(wen, Coins.total(p));
            assertTrue(p.stream().allMatch(x -> x.count() > 0));
        }
        assertEquals(List.of(new Coins.Pile(Coins.Coin.GOLD, 1), new Coins.Pile(Coins.Coin.SILVER, 1), new Coins.Pile(Coins.Coin.COPPER, 1)),
                Coins.split(Coins.WEN_PER_GOLD + Coins.WEN_PER_SILVER + 1));
    }

    @Test
    @DisplayName("цена всегда в двух стопках, округление — вверх и не больше чем на лян")
    void priceFitsTwoSlots() {
        for (int wen = 1; wen < 3000; wen++) {
            List<Coins.Pile> p = Coins.price(wen);
            assertTrue(p.size() <= 2, wen + " → " + p);
            int paid = Coins.total(p);
            assertTrue(paid >= wen && paid - wen < Coins.WEN_PER_SILVER, wen + " → " + paid);
        }
        assertEquals(List.of(new Coins.Pile(Coins.Coin.SILVER, 7)), Coins.price(Coins.silver(7)));
    }

    @Test
    @DisplayName("отрицательная сумма — ошибка")
    void negative() {
        assertThrows(IllegalArgumentException.class, () -> Coins.split(-1));
    }
}
