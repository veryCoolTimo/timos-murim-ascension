package io.github.verycooltimo.murim.cultivation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Пилюли и мини-игра поглощения (docs/design/19b, решение автора 03.10). */
class PillRulesTest {

    /** Играет внимательный игрок: спокойный — коротко, бурный — обходом. */
    private static AbsorbGame play(List<PillKind> pills, long seed, int policy) {
        Random random = new Random(seed);
        AbsorbGame game = AbsorbGame.start(pills, false, random::nextDouble);
        for (int i = 0; i < 5000 && game.result() == AbsorbGame.Result.PLAYING; i++) {
            if (game.showsTemper()) {
                int shortSide = game.currentFork().shortSide();
                int side = switch (policy) {
                    case 0 -> game.wildNow() ? -shortSide : shortSide; // внимательный
                    case 1 -> shortSide;                               // жадный: всегда коротко
                    default -> 0;                                      // не трогает мышь
                };
                game.choose(side);
            }
            game.tick(random::nextDouble);
        }
        return game;
    }

    @Test
    @DisplayName("Внимательный игрок поглощает всё и не получает искажения")
    void attentiveFinishes() {
        for (long seed = 0; seed < 40; seed++) {
            AbsorbGame g = play(List.of(PillKind.ORIGIN_ENERGY, PillKind.THOUSAND_POISON, PillKind.BEAUTY_TEAR), seed, 0);
            assertEquals(AbsorbGame.Result.FINISHED, g.result(), "seed " + seed);
            for (int i = 0; i < g.clots().size(); i++) {
                assertTrue(g.settled(i) >= 0.59D, "сгусток осел хотя бы на 60 %");
            }
        }
    }

    @Test
    @DisplayName("Жадный игрок на одной слезе почти всегда получает искажение ци")
    void greedyTearBacklashes() {
        int backlash = 0;
        for (long seed = 0; seed < 40; seed++) {
            if (play(List.of(PillKind.BEAUTY_TEAR), seed, 1).result() == AbsorbGame.Result.BACKLASH) {
                backlash++;
            }
        }
        assertTrue(backlash > 25, "искажений: " + backlash);
    }

    @Test
    @DisplayName("Щит канонического состава смягчает удар слезы")
    void shieldSoftens() {
        assertEquals(1.0D, PillRules.shield(List.of(PillKind.BEAUTY_TEAR)));
        assertEquals(0.75D, PillRules.shield(List.of(PillKind.THOUSAND_POISON, PillKind.BEAUTY_TEAR)));
        assertEquals(0.5D, PillRules.shield(List.of(PillKind.ORIGIN_ENERGY, PillKind.THOUSAND_POISON, PillKind.BEAUTY_TEAR)));
    }

    @Test
    @DisplayName("Поглощённая пилюля даёт в десять раз больше несъеденной")
    void tenTimes() {
        double wall = 128.0D;
        double sat = PillRules.poolGain(PillKind.ORIGIN_ENERGY, wall, 1.0D, 0, 1.0D);
        double not = PillRules.poolGain(PillKind.ORIGIN_ENERGY, wall, 0.0D, 0, 1.0D);
        assertEquals(10.0D, sat / not, 1.0E-9D);
        assertEquals(0.5D, PillRules.repeatFactor(1));
    }

    @Test
    @DisplayName("Окно: 12 с, +3 с за следующую, не больше 18 с от первой")
    void window() {
        long d = PillRules.deadline(0, 0, 0, true);
        assertEquals(240, d);
        d = PillRules.deadline(0, d, 100, false);
        assertEquals(300, d);
        d = PillRules.deadline(0, d, 200, false);
        assertEquals(360, d);
        d = PillRules.deadline(0, d, 300, false);
        assertEquals(360, d);
    }

    @Test
    @DisplayName("Состав: разные редкие, сливовая отдельно, не больше трёх")
    void combos() {
        assertEquals(PillRules.Refusal.SAME_KIND, PillRules.canAdd(List.of(PillKind.ORIGIN_ENERGY), PillKind.ORIGIN_ENERGY));
        assertEquals(PillRules.Refusal.SNOW_ALONE, PillRules.canAdd(List.of(PillKind.ORIGIN_ENERGY), PillKind.SNOW_PLUM));
        assertEquals(PillRules.Refusal.NONE, PillRules.canAdd(List.of(PillKind.ORIGIN_ENERGY), PillKind.BEAUTY_TEAR));
    }

    @Test
    @DisplayName("Сливовая пилюля — короткая сессия, тройка — не дольше 35 с")
    void durations() {
        assertTrue(AbsorbGame.expectedTicks(List.of(PillKind.SNOW_PLUM)) <= 180);
        assertTrue(AbsorbGame.expectedTicks(List.of(PillKind.ORIGIN_ENERGY, PillKind.THOUSAND_POISON, PillKind.BEAUTY_TEAR)) <= 700);
    }
}
