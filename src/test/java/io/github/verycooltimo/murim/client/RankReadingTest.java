package io.github.verycooltimo.murim.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Чтение ранга словами (docs/design/19 §3ж, 01 «Чтение ранга»). */
class RankReadingTest {

    @Test
    @DisplayName("Категория по разнице рангов: слабее, равен, сильнее, намного, непостижимо")
    void category() {
        assertEquals("murim.read.weaker", RankReading.categoryKey(-2));
        assertEquals("murim.read.equal", RankReading.categoryKey(0));
        assertEquals("murim.read.stronger", RankReading.categoryKey(1));
        assertEquals("murim.read.much", RankReading.categoryKey(4));
        assertEquals("murim.read.beyond", RankReading.categoryKey(5));
    }

    @Test
    @DisplayName("Новичок ранг не называет; второй ранг не видит выше себя больше чем на ступень")
    void precision() {
        assertEquals(null, RankReading.rankWords(0, 3));
        assertEquals("murim.read.rank.3", key(RankReading.rankWords(2, 3)));
        assertEquals("murim.read.unknowable", key(RankReading.rankWords(2, 6)));
        assertEquals("murim.read.past_peak", key(RankReading.rankWords(4, 6)));
    }

    private static String key(net.minecraft.network.chat.Component c) {
        return ((net.minecraft.network.chat.contents.TranslatableContents) c.getContents()).getKey();
    }
}
