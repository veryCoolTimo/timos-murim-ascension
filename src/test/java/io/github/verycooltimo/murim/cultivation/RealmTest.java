package io.github.verycooltimo.murim.cultivation;

import io.github.verycooltimo.murim.profile.DantianProfile;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ранги, стена и прорыв (docs/design/19-dantian-qi-meditation.md §3е). */
class RealmTest {

    private static final ResourceLocation SWEEP = ResourceLocation.fromNamespaceAndPath("murim", "crescent_sweep");
    /** The main art from the first rank on (art ladder, 24.10). */
    private static final ResourceLocation PLUM = ResourceLocation.fromNamespaceAndPath("murim", "seven_plum_basic");

    /** Пробуждённый даньтянь ёмкостью 30 с запасом {@code pool}. */
    private static DantianProfile awakened(double pool, int rank) {
        // Fixed total capacity fixture, not a simulated rank-up.
        return new DantianProfile(30.0D, 0.8D, 0.5D, "pure", "six_harmonies", pool, 0, 1, rank, 0);
    }

    @Test
    @DisplayName("Lab blow for Third/Second; legacy scene timing for First/Peak")
    void breakthroughSceneLength() {
        assertEquals(216, Realm.breakthroughTicks(Realm.THIRD));
        assertEquals(304, Realm.breakthroughTicks(Realm.SECOND));
        assertEquals(304, Realm.breakthroughTicks(Realm.FIRST));
        assertEquals(510, Realm.breakthroughTicks(Realm.PEAK));
    }

    @Test
    @DisplayName("Стена — потолок запаса: ёмкость × 4")
    void wall() {
        assertEquals(120.0D, Realm.wall(awakened(0.0D, 0)), 1.0E-9);
        assertTrue(Realm.atWall(awakened(119.0D, 0)));
        assertEquals(Realm.Blocker.NOT_AT_WALL, Realm.check(awakened(100.0D, 0), Map.of(SWEEP, 5)));
    }

    @Test
    @DisplayName("Прорыв 1 — любая техника на ★1: прочитанная (★0) не считается, освоенная (★1) — да")
    void needsTechnique() {
        assertEquals(Realm.Blocker.NO_TECHNIQUE, Realm.check(awakened(120.0D, 0), Map.of()));
        assertEquals(Realm.Blocker.NO_TECHNIQUE, Realm.check(awakened(120.0D, 0), Map.of(SWEEP, 0)));
        assertEquals(Realm.Blocker.NONE, Realm.check(awakened(120.0D, 0), Map.of(SWEEP, 1)));
        // Во второй ранг — оба искусства и подтверждения спаррингов (автор 21.10).
        assertEquals(Realm.Blocker.NO_SECT_SKILLS, Realm.check(awakened(120.0D, 1), Map.of(SWEEP, 9)));
        assertEquals(Realm.Blocker.NONE, Realm.check(awakened(120.0D, 1), Map.of(ResourceLocation.fromNamespaceAndPath("murim", "six_harmonies"), 6, ResourceLocation.fromNamespaceAndPath("murim", "falling_petal_sword"), 3), true));
        assertEquals(Realm.Blocker.NOT_AT_WALL, Realm.check(awakened(100.0D, 1), Map.of(SWEEP, 1), true));
        // В первый ранг босс уже не нужен — снова звёзды: ★6.
        assertEquals(Realm.Blocker.NO_TECHNIQUE, Realm.check(awakened(400.0D, 2), Map.of(PLUM, 5), true));
        assertEquals(Realm.Blocker.NONE, Realm.check(awakened(400.0D, 2), Map.of(PLUM, 6), true));
        assertEquals(Realm.Blocker.NO_TECHNIQUE, Realm.check(awakened(400.0D, 2), Map.of(SWEEP, 12), true), "only Seven Plum counts");
    }

    @Test
    @DisplayName("Условия прорывов: 1 — любая техника, 2 — искусства и спарринги, 3 и Пик — звёзды из таблицы")
    void conditions() {
        assertEquals(Realm.Condition.MASTER_ANY, Realm.condition(Realm.THIRD));
        assertEquals(Realm.Condition.SECT_SKILLS, Realm.condition(Realm.SECOND));
        assertEquals(Realm.Condition.FORM_MASTERY, Realm.condition(Realm.FIRST));
        assertEquals(Realm.Condition.FORM_MASTERY, Realm.condition(Realm.PEAK));
    }

    @Test
    @DisplayName("D-4: требования рангов — таблица звёзд (прорыв 1 ★1, прорыв 3 ★6, Пик ★8; у шагов ★5 и ★7), не legacySteps")
    void requirementsAreATableOfStars() {
        assertEquals(1, RankRequirements.stars(RankRequirements.Gate.THIRD, 12));
        assertEquals(6, RankRequirements.stars(RankRequirements.Gate.FIRST, 12));
        assertEquals(8, RankRequirements.stars(RankRequirements.Gate.PEAK, 12));
        assertEquals(9, RankRequirements.stars(RankRequirements.Gate.SETTLED, 12));
        assertEquals(11, RankRequirements.stars(RankRequirements.Gate.SUMMIT, 12));
        // Шаги (десять звёзд): та же доля звёзд искусства.
        assertEquals(1, RankRequirements.stars(RankRequirements.Gate.THIRD, 10));
        assertEquals(5, RankRequirements.stars(RankRequirements.Gate.FIRST, 10));
        assertEquals(7, RankRequirements.stars(RankRequirements.Gate.PEAK, 10));
        assertEquals(8, RankRequirements.stars(RankRequirements.Gate.SETTLED, 10));
        assertEquals(9, RankRequirements.stars(RankRequirements.Gate.SUMMIT, 10));
        assertEquals(RankRequirements.Gate.THIRD, Realm.gate(Realm.THIRD));
        assertEquals(RankRequirements.Gate.FIRST, Realm.gate(Realm.FIRST));
        assertEquals(RankRequirements.Gate.PEAK, Realm.gate(Realm.PEAK));
        assertTrue(RankRequirements.gate(Realm.SECOND).isEmpty(), "прорыв 2 — босс, а не звёзды");
        // Шесть Равновесий требовали ★10 (старый legacySteps), Цветок ★6: теперь одно правило для всех искусств.
        ResourceLocation six = ResourceLocation.fromNamespaceAndPath("murim", "six_harmonies");
        ResourceLocation wind = ResourceLocation.fromNamespaceAndPath("murim", "wind_god_steps");
        assertEquals(Realm.Blocker.NO_TECHNIQUE, Realm.check(awakened(400.0D, 2), Map.of(six, 12), true), "Six Harmonies is the third-rank art");
        assertEquals(Realm.Blocker.NO_TECHNIQUE, Realm.check(awakened(400.0D, 2), Map.of(wind, 10), true), "footwork does not open the First Rate");
        assertEquals(Realm.Blocker.NO_TECHNIQUE, Realm.check(awakened(1000.0D, 3), Map.of(PLUM, 7), true));
        assertEquals(Realm.Blocker.NONE, Realm.check(awakened(1000.0D, 3), Map.of(PLUM, 8), true), "Peak: Seven Plum 8");
        // Слова, а не числа: у каждой ступени есть ключ с описанием.
        for (RankRequirements.Gate gate : RankRequirements.Gate.values()) {
            assertTrue(gate.wordKey().startsWith("murim.mastery.requirement."), gate.name());
        }
    }

    @Test
    @DisplayName("Множитель силы техник — таблица автора 03.10")
    void powerTable() {
        double[] expected = {0.8D, 1.0D, 1.25D, 1.5D, 1.75D, 2.2D, 2.5D, 2.8D, 3.1D, 3.4D, 3.7D};
        for (int rank = 0; rank <= Realm.TOP; rank++) {
            assertEquals(expected[rank], Realm.power(rank), 1.0E-9, "ранг " + rank);
        }
        // Подступени Пика: начальная ×1,75, утвердившаяся ×1,85, вершина ×1,95.
        assertEquals(1.85D, Realm.power(Realm.PEAK, 1), 1.0E-9);
        assertEquals(1.95D, Realm.power(Realm.PEAK, 2), 1.0E-9);
        assertEquals(1.95D, Realm.power(Realm.PEAK, 9), 1.0E-9);
        // Ниже Пика подступеней нет.
        assertEquals(1.5D, Realm.power(Realm.FIRST, 2), 1.0E-9);
        // За пределами лестницы — края.
        assertEquals(0.8D, Realm.power(-3), 1.0E-9);
        assertEquals(3.7D, Realm.power(99), 1.0E-9);
        // Растёт строго и остаётся маленьким: вершина лестницы меньше ×4.
        for (int rank = 1; rank <= Realm.TOP; rank++) {
            assertTrue(Realm.power(rank) > Realm.power(rank - 1));
        }
        assertTrue(Realm.power(Realm.TOP, 2) < 4.0D);
    }

    @Test
    @DisplayName("Скорость: +3 % за ранг, Пик — +12 %")
    void speed() {
        assertEquals(0.0D, Realm.bonusSpeed(Realm.NONE), 1.0E-9);
        assertEquals(0.09D, Realm.bonusSpeed(Realm.FIRST), 1.0E-9);
        assertEquals(0.12D, Realm.bonusSpeed(Realm.PEAK), 1.0E-9);
        assertEquals(0.30D, Realm.bonusSpeed(99), 1.0E-9);
    }

    @Test
    @DisplayName("Без даньтяня и на последнем ранге прорыва нет")
    void blocked() {
        assertEquals(Realm.Blocker.NO_DANTIAN, Realm.check(DantianProfile.INITIAL, Map.of(SWEEP, 12)));
        assertEquals(Realm.Blocker.MAX_RANK, Realm.check(awakened(120.0D, Realm.MAX), Map.of(SWEEP, 12)));
    }

    @Test
    @DisplayName("Прорыв: ранг выше, ёмкость ×1,8, запас сохранён и уже ниже новой стены")
    void advance() {
        DantianProfile after = Realm.advance(awakened(120.0D, 0));
        assertEquals(Realm.THIRD, after.rank());
        assertEquals(54.0D, after.capacity(), 1.0E-9);
        assertEquals(120.0D, after.pool(), 1.0E-9);
        assertTrue(!Realm.atWall(after));
        assertEquals(0.6D, after.meridians(), 1.0E-9);
    }

    @Test
    @DisplayName("Прерванный прорыв отнимает треть запаса, ранг прежний")
    void injure() {
        DantianProfile after = Realm.injure(awakened(120.0D, 0));
        assertEquals(80.0D, after.pool(), 1.0E-9);
        assertEquals(Realm.NONE, after.rank());
    }

    @Test
    @DisplayName("Здоровье: по два сердца за ранг, не выше вершины лестницы")
    void health() {
        assertEquals(0.0D, Realm.bonusHealth(0), 1.0E-9);
        assertEquals(4.0D, Realm.bonusHealth(Realm.THIRD), 1.0E-9);
        assertEquals(16.0D, Realm.bonusHealth(Realm.PEAK), 1.0E-9);
        assertEquals(40.0D, Realm.bonusHealth(99), 1.0E-9);
    }

    @Test
    @DisplayName("Подступени Пика: стена и техника на ★9 / ★11, центр ×1,25, сила +0,1")
    void peakStages() {
        DantianProfile peak = awakened(120.0D, Realm.PEAK);
        assertEquals(Realm.Blocker.NOT_AT_WALL, Realm.checkStage(awakened(60.0D, Realm.PEAK), Map.of(SWEEP, 12)));
        assertEquals(Realm.Blocker.NO_TECHNIQUE, Realm.checkStage(peak, Map.of(PLUM, 8)));
        assertEquals(Realm.Blocker.NONE, Realm.checkStage(peak, Map.of(PLUM, 9)));
        DantianProfile settled = Realm.settle(peak);
        assertEquals(Realm.STAGE_SETTLED, settled.stage());
        assertEquals(37.5D, settled.capacity(), 1.0E-9);
        assertEquals(Realm.Blocker.NOT_AT_WALL, Realm.checkStage(settled, Map.of(SWEEP, 12)), "новая стена дальше");
        DantianProfile full = settled.withPool(Realm.wall(settled));
        assertEquals(Realm.Blocker.NO_TECHNIQUE, Realm.checkStage(full, Map.of(PLUM, 10)));
        assertEquals(Realm.Blocker.NONE, Realm.checkStage(full, Map.of(PLUM, 11)));
        DantianProfile summit = Realm.settle(full);
        assertEquals(Realm.STAGE_SUMMIT, summit.stage());
        assertEquals(Realm.Blocker.MAX_RANK, Realm.checkStage(summit.withPool(Realm.wall(summit)), Map.of(SWEEP, 12)));
        assertEquals(Realm.Blocker.MAX_RANK, Realm.checkStage(awakened(120.0D, Realm.FIRST), Map.of(SWEEP, 12)),
                "ниже Пика — прорыв, не подступень");
        assertEquals(1.75D, Realm.power(peak), 1.0E-9);
        assertEquals(1.85D, Realm.power(settled), 1.0E-9);
        assertEquals(1.95D, Realm.power(summit), 1.0E-9);
    }
}
