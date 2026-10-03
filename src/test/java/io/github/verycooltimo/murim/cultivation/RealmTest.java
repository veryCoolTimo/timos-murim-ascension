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
    @DisplayName("Без техники второго слоя в третий ранг не прорваться")
    void needsTechnique() {
        assertEquals(Realm.Blocker.NO_TECHNIQUE, Realm.check(awakened(120.0D, 0), Map.of(SWEEP, 1)));
        assertEquals(Realm.Blocker.NONE, Realm.check(awakened(120.0D, 0), Map.of(SWEEP, 2)));
        // Во второй ранг нужен уже третий слой.
        assertEquals(Realm.Blocker.NO_TECHNIQUE, Realm.check(awakened(120.0D, 1), Map.of(SWEEP, 2)));
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
    @DisplayName("Здоровье: по два сердца за ранг, не выше последнего ранга")
    void health() {
        assertEquals(0.0D, Realm.bonusHealth(0), 1.0E-9);
        assertEquals(4.0D, Realm.bonusHealth(Realm.THIRD), 1.0E-9);
        assertEquals(16.0D, Realm.bonusHealth(99), 1.0E-9);
    }
}
