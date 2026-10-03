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

    /** Пробуждённый даньтянь ёмкостью 30 с запасом {@code pool}. */
    private static DantianProfile awakened(double pool, int rank) {
        return DantianProfile.INITIAL.withAxes(30.0D, 0.8D, 0.5D).withTags("pure", "six_harmonies")
                .withPool(pool).withRank(rank);
    }

    @Test
    @DisplayName("Стена — потолок запаса: ёмкость × 4")
    void wall() {
        assertEquals(120.0D, Realm.wall(awakened(0.0D, 0)), 1.0E-9);
        assertTrue(Realm.atWall(awakened(119.0D, 0)));
        assertEquals(Realm.Blocker.NOT_AT_WALL, Realm.check(awakened(100.0D, 0), Map.of(SWEEP, 5)));
    }

    @Test
    @DisplayName("Прорыв 1 — освоить любую технику: прочитанная (слой 0) не считается, освоенная (слой 1) — да")
    void needsTechnique() {
        assertEquals(Realm.Blocker.NO_TECHNIQUE, Realm.check(awakened(120.0D, 0), Map.of()));
        assertEquals(Realm.Blocker.NO_TECHNIQUE, Realm.check(awakened(120.0D, 0), Map.of(SWEEP, 0)));
        assertEquals(Realm.Blocker.NONE, Realm.check(awakened(120.0D, 0), Map.of(SWEEP, 1)));
        // Во второй ранг (пока вместо босса) нужен уже третий слой.
        assertEquals(Realm.Blocker.NO_TECHNIQUE, Realm.check(awakened(120.0D, 1), Map.of(SWEEP, 2)));
        assertEquals(Realm.Blocker.NONE, Realm.check(awakened(120.0D, 1), Map.of(SWEEP, 3)));
    }

    @Test
    @DisplayName("Условия прорывов: 1 — освоить любую, 2 — босс (M4), 3 и Пик — слой формы")
    void conditions() {
        assertEquals(Realm.Condition.MASTER_ANY, Realm.condition(Realm.THIRD));
        assertEquals(Realm.Condition.DEFEAT_BOSS, Realm.condition(Realm.SECOND));
        assertEquals(Realm.Condition.FORM_LAYER, Realm.condition(Realm.FIRST));
        assertEquals(Realm.Condition.FORM_LAYER, Realm.condition(Realm.PEAK));
        assertEquals(1, Realm.layerNeed(Realm.THIRD));
        assertEquals(3, Realm.layerNeed(Realm.SECOND));
        assertEquals(4, Realm.layerNeed(Realm.FIRST));
        assertEquals(5, Realm.layerNeed(Realm.PEAK));
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
        assertEquals(Realm.Blocker.NO_DANTIAN, Realm.check(DantianProfile.INITIAL, Map.of(SWEEP, 8)));
        assertEquals(Realm.Blocker.MAX_RANK, Realm.check(awakened(120.0D, Realm.MAX), Map.of(SWEEP, 8)));
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
    @DisplayName("Подступени Пика: стена и техника до слоя 6 / 7, центр ×1,25, сила +0,1")
    void peakStages() {
        DantianProfile peak = awakened(120.0D, Realm.PEAK);
        assertEquals(Realm.Blocker.NOT_AT_WALL, Realm.checkStage(awakened(60.0D, Realm.PEAK), Map.of(SWEEP, 8)));
        assertEquals(Realm.Blocker.NO_TECHNIQUE, Realm.checkStage(peak, Map.of(SWEEP, 5)));
        assertEquals(Realm.Blocker.NONE, Realm.checkStage(peak, Map.of(SWEEP, 6)));
        DantianProfile settled = Realm.settle(peak);
        assertEquals(Realm.STAGE_SETTLED, settled.stage());
        assertEquals(37.5D, settled.capacity(), 1.0E-9);
        assertEquals(Realm.Blocker.NOT_AT_WALL, Realm.checkStage(settled, Map.of(SWEEP, 8)), "новая стена дальше");
        DantianProfile full = settled.withPool(Realm.wall(settled));
        assertEquals(Realm.Blocker.NO_TECHNIQUE, Realm.checkStage(full, Map.of(SWEEP, 6)));
        DantianProfile summit = Realm.settle(full);
        assertEquals(Realm.STAGE_SUMMIT, summit.stage());
        assertEquals(Realm.Blocker.MAX_RANK, Realm.checkStage(summit.withPool(Realm.wall(summit)), Map.of(SWEEP, 8)));
        assertEquals(Realm.Blocker.MAX_RANK, Realm.checkStage(awakened(120.0D, Realm.FIRST), Map.of(SWEEP, 8)),
                "ниже Пика — прорыв, не подступень");
        assertEquals(1.75D, Realm.power(peak), 1.0E-9);
        assertEquals(1.85D, Realm.power(settled), 1.0E-9);
        assertEquals(1.95D, Realm.power(summit), 1.0E-9);
    }
}
