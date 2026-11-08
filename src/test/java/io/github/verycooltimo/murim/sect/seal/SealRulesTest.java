package io.github.verycooltimo.murim.sect.seal;

import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.technique.Styles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SealRulesTest {

    @Test
    @DisplayName("Холодное железо: Пик не берёт, выше Пика — только с полной ци, и за BREAK_TICKS")
    void coldIron() {
        assertEquals(0.0F, ColdIronRules.progress(Realm.PEAK, 100, 100, true));
        assertEquals(0.0F, ColdIronRules.progress(Realm.PEAK, 100, 100, false));
        assertEquals(0.0F, ColdIronRules.progress(ColdIronRules.MIN_RANK, 50, 100, true), "half qi starts a strike");
        float p = ColdIronRules.progress(ColdIronRules.MIN_RANK, 95, 100, true);
        assertEquals(1.0F / ColdIronRules.BREAK_TICKS, p, 1e-6);
        assertTrue(ColdIronRules.progress(ColdIronRules.MIN_RANK, 50, 100, false) > 0.0F, "a begun strike stops at half qi");
        assertEquals(0.0F, ColdIronRules.progress(ColdIronRules.MIN_RANK, 0.1, 100, false), "a dry reserve still digs");
        // A full strike costs less than what it needs to begin: one block per full reserve.
        assertTrue(ColdIronRules.costPerTick(100) * ColdIronRules.BREAK_TICKS < 100 * ColdIronRules.FULL);
    }

    @Test
    @DisplayName("Дверь: любая одна форма 24 Движений; шаги и основа не считаются")
    void ladder() {
        List<VaultLadder.Step> l = VaultLadder.LADDER;
        assertEquals(Styles.TWENTY_FOUR_PLUM.forms().size(), l.size());
        for (int i = 0; i < l.size(); i++) {
            assertEquals(Styles.TWENTY_FOUR_PLUM.forms().get(i), l.get(i).technique());
        }
        for (VaultLadder.Step form : l) {
            assertEquals(l.size(), VaultLadder.advance(l, 0, form), "one form must complete the trial");
            assertEquals(l.size(), VaultLadder.advance(l, 3, form), "old partial progress must not require an order");
        }
        assertEquals(0, VaultLadder.advance(l, 0, VaultLadder.Step.technique(Styles.SEVEN_PLUM.forms().get(0))));
        assertFalse(VaultLadder.relevant(l, VaultLadder.Step.technique(Styles.DARK_FRAGRANCE.forms().get(0))), "footwork counts");
        assertFalse(VaultLadder.relevant(l, VaultLadder.Step.technique(Styles.SEVEN_PLUM.basic().orElseThrow())), "basic strikes count");
        assertTrue(VaultLadder.relevant(l, VaultLadder.Step.technique(Styles.TANG_DAGGERS.forms().get(0))));
    }

    @Test
    @DisplayName("Покаяние: второй проступок за окно — приговор, старые прощаются; срок или медитация")
    void penance() {
        int c = PenanceRules.count(0, 0, 10);
        assertFalse(PenanceRules.sentence(c));
        assertTrue(PenanceRules.sentence(PenanceRules.count(c, 10, 11)));
        assertFalse(PenanceRules.sentence(PenanceRules.count(c, 10, 10 + PenanceRules.WINDOW_DAYS + 1)), "old offences not forgiven");
        long until = PenanceRules.until(1000);
        assertFalse(PenanceRules.served(until - 1, until, 0));
        assertTrue(PenanceRules.served(until, until, 0));
        assertTrue(PenanceRules.served(0, until, PenanceRules.MEDITATION_QUOTA));
    }
}
