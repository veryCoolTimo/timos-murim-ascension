package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.profile.DantianProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ворота ци-меча: только с устоявшегося Пика (канон Keen Qi, автор 03.10). */
class QiSwordGateTest {

    private static DantianProfile at(int rank, int stage) {
        return DantianProfile.INITIAL.withTags("pure", "debug").withRank(rank).withStage(stage);
    }

    @Test
    @DisplayName("Ниже Пика и на начальном Пике ци-меча нет; с утвердившегося — есть")
    void gate() {
        assertAll(
                () -> assertFalse(QiSword.available(DantianProfile.INITIAL.withRank(Realm.PEAK).withStage(2)),
                        "без даньтяня"),
                () -> assertFalse(QiSword.available(at(Realm.NONE, 0))),
                () -> assertFalse(QiSword.available(at(Realm.FIRST, 2)), "подступени ниже Пика нет"),
                () -> assertFalse(QiSword.available(at(Realm.PEAK, Realm.STAGE_INITIAL)), "начальный Пик ещё не устоялся"),
                () -> assertTrue(QiSword.available(at(Realm.PEAK, Realm.STAGE_SETTLED))),
                () -> assertTrue(QiSword.available(at(Realm.PEAK, Realm.STAGE_SUMMIT))),
                () -> assertTrue(QiSword.available(at(Realm.TRANSCENDENT, 0)), "выше Пика — всегда"));
    }
}
