package io.github.verycooltimo.murim.entity.boss;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D-2 (docs/design/research/hua-main-1810.md section 10): the master picks only three readable moves, each for its own distance. */
class BossCoreMovesTest {

    private static boolean[] allReady() {
        boolean[] ready = new boolean[BossMove.ALL.length];
        java.util.Arrays.fill(ready, true);
        return ready;
    }

    @Test
    @DisplayName("The core is the combo, the pounce and the ram; the rest waits for the core to be checked")
    void theCoreIsThreeMoves() {
        assertFalse(BossRules.FULL_MOVESET, "the full set was switched on");
        Set<BossMove> core = new HashSet<>();
        for (BossMove m : BossMove.ALL) {
            if (BossRules.inCore(m)) {
                core.add(m);
            }
        }
        assertEquals(Set.of(BossMove.CHAIN, BossMove.POUNCE, BossMove.RAM), core);
    }

    @Test
    @DisplayName("In every phase, at every distance, with any roll, the choice is a move of the core or none (close in)")
    void theChoiceNeverLeavesTheCore() {
        for (int phase = 1; phase <= 3; phase++) {
            for (double d = 0.0D; d <= 20.0D; d += 0.25D) {
                for (float roll = 0.0F; roll < 1.0F; roll += 0.05F) {
                    BossMove m = BossRules.choose(d, phase, allReady(), roll);
                    assertTrue(m == null || BossRules.inCore(m), "phase " + phase + " at " + d + " chose " + (m == null ? null : m.key()));
                }
            }
        }
    }

    @Test
    @DisplayName("Each of the three has its own range: the combo close, the pounce in the middle, the ram far")
    void eachMoveHasItsRange() {
        assertEquals(BossMove.CHAIN, BossRules.choose(2.0D, 1, allReady(), 0.9F));
        assertEquals(BossMove.POUNCE, BossRules.choose(6.0D, 1, allReady(), 0.1F));
        assertEquals(BossMove.RAM, BossRules.choose(BossMove.RAM_FROM + 1.0D, 1, allReady(), 0.1F));
        // a move on cooldown is not chosen: the next one in its place
        boolean[] noPounce = allReady();
        noPounce[BossMove.POUNCE.id()] = false;
        assertTrue(BossRules.choose(6.0D, 1, noPounce, 0.1F) != BossMove.POUNCE);
    }

    @Test
    @DisplayName("When everybody is down the fortress resets ten times faster; the plain reset stays half a minute")
    void theResetTimes() {
        assertEquals(600, BossRules.RESET_TICKS);
        assertEquals(60, BossRules.DOWN_RESET_TICKS);
        assertEquals(10, BossRules.RESET_TICKS / BossRules.DOWN_RESET_TICKS);
    }
}
