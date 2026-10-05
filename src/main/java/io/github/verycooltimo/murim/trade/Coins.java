package io.github.verycooltimo.murim.trade;

import java.util.ArrayList;
import java.util.List;

/**
 * Деньги мира мурим (docs/design/29-economy.md): медный вэнь (文), серебряный лян (銀子) и золотой лян (金子).
 * Все цены мода считаются в вэнях — одна единица, одна таблица ({@link PeddlerStock}); в монеты они
 * раскладываются только на прилавке.
 *
 * <p>Курс упрощён для верстака: 9 вэней = 1 лян серебра, 9 лян серебра = 1 лян золота — сетка 3×3, как самородки
 * и слитки. В каноне (Return of the Mount Hua Sect) лян серебра — это связка в тысячу медяков, а золото
 * в десятки раз дороже серебра; соотношение «мелочь — серебро — золото» сохранено, числа — нет.
 *
 * <p>Чистые данные без реестров: проверяются юнит-тестом ({@code CoinsTest}).
 */
public final class Coins {

    /** Вэней в ляне серебра. */
    public static final int WEN_PER_SILVER = 9;
    /** Лян серебра в ляне золота. */
    public static final int SILVER_PER_GOLD = 9;
    /** Вэней в ляне золота. */
    public static final int WEN_PER_GOLD = WEN_PER_SILVER * SILVER_PER_GOLD;

    /** Монета: золото, серебро, медь. */
    public enum Coin {
        GOLD("murim:gold_tael", WEN_PER_GOLD),
        SILVER("murim:silver_tael", WEN_PER_SILVER),
        COPPER("murim:copper_coin", 1);

        private final String item;
        private final int wen;

        Coin(String item, int wen) {
            this.item = item;
            this.wen = wen;
        }

        public String item() {
            return item;
        }

        public int wen() {
            return wen;
        }
    }

    /** Монеты и их число. */
    public record Pile(Coin coin, int count) {
    }

    /** Цена в лянах серебра — в вэнях. */
    public static int silver(int liang) {
        return liang * WEN_PER_SILVER;
    }

    /** Разложить сумму на монеты от крупной к мелкой (без нулевых). */
    public static List<Pile> split(int wen) {
        if (wen < 0) {
            throw new IllegalArgumentException("Отрицательная сумма: " + wen);
        }
        List<Pile> out = new ArrayList<>();
        int left = wen;
        for (Coin c : Coin.values()) {
            int n = left / c.wen();
            if (n > 0) {
                out.add(new Pile(c, n));
                left -= n * c.wen();
            }
        }
        return out;
    }

    /**
     * Цена на прилавке: ванильная сделка берёт не больше двух стопок, поэтому сумма раскладывается в две
     * монеты. Если нужно три (золото, серебро и медь), медь округляется вверх до серебра — в пользу торговца.
     * Одна стопка не больше 64: дороже 64 лян золота мод ничего не продаёт.
     */
    public static List<Pile> price(int wen) {
        List<Pile> piles = split(wen);
        if (piles.size() <= 2) {
            return piles;
        }
        return split((wen / WEN_PER_SILVER + 1) * WEN_PER_SILVER);
    }

    /** Сумма стопок в вэнях. */
    public static int total(List<Pile> piles) {
        return piles.stream().mapToInt(p -> p.coin().wen() * p.count()).sum();
    }

    private Coins() {
    }
}
