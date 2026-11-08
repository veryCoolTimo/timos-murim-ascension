package io.github.verycooltimo.murim.sect;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Standing counts the merit earned in all, purchases come from the balance (author 19.10): spending never lowers standing,
 * and an old save reads as it always did (docs/adr/0012).
 */
class SectMeritTotalTest {
    private static SectState decode(String json) {
        return SectState.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    @Test
    @DisplayName("Spending every point does not lower the standing, whichever step it stands on")
    void spendingKeepsStanding() {
        // Trusted needs 30 merit earned in all (plus both lessons and the leader's approval); the balance may be zero.
        SectState trusted = SectState.NONE.joined().with(SectStanding.LESSON_ONE).with(SectStanding.LESSON_TWO)
                .with(SectStanding.APPROVAL).contribute(SectStanding.TRUSTED_CONTRIBUTION);
        assertEquals(SectStanding.TRUSTED, SectStanding.of(trusted, 1));
        SectState broke = trusted.spend(trusted.merit());
        assertEquals(0, broke.merit());
        assertEquals(SectStanding.TRUSTED_CONTRIBUTION, broke.contribution());
        assertEquals(SectStanding.TRUSTED, SectStanding.of(broke, 1), "spending lowered the standing");
        // Disciple by labour: rank and 15 merit earned in all.
        SectState labour = SectState.NONE.joined().contribute(SectStanding.DISCIPLE_CONTRIBUTION);
        assertEquals(SectStanding.DISCIPLE, SectStanding.of(labour, SectStanding.DISCIPLE_RANK));
        assertEquals(SectStanding.DISCIPLE, SectStanding.of(labour.spend(labour.merit()), SectStanding.DISCIPLE_RANK));
    }

    @Test
    @DisplayName("Offices and dialogue thresholds read the total as well")
    void officesReadTheTotal() {
        SectState s = SectState.NONE.joined().contribute(200).spend(200);
        assertEquals(0, s.merit());
        assertEquals(200, s.contribution(), "the total earned is untouched by a purchase");
        assertTrue(s.contribution() >= 150, "the elder's threshold is read from the total");
    }

    @Test
    @DisplayName("A purchase of any size lowers only the balance")
    void purchaseLowersOnlyTheBalance() {
        SectState s = SectState.NONE.joined().contribute(100);
        SectState after = s.spend(20).spend(6).spend(24);
        assertEquals(100, after.contribution());
        assertEquals(50, after.merit());
        assertEquals(50, after.spent());
    }

    @Test
    @DisplayName("A penalty larger than the balance leaves no hidden debt that eats the next income")
    void penaltyLeavesNoDebt() {
        SectState s = SectState.NONE.joined().contribute(30).spend(30);
        SectState fined = s.contribute(-10);
        assertEquals(0, fined.merit());
        assertEquals(20, fined.contribution(), "a penalty lowers the total earned: it is not a purchase");
        // The ten points earned afterwards are all there to spend.
        assertEquals(10, fined.contribute(10).merit());
        // The balance never goes below zero either way.
        assertEquals(0, SectState.NONE.joined().contribute(5).contribute(-50).merit());
    }

    @Test
    @DisplayName("Old saves: no spent field means nothing spent; a spent figure above the total is cut to it")
    void oldSaves() {
        SectState v2 = decode("{\"version\":2,\"member\":true,\"generation\":3,\"flags\":[],\"contribution\":50}");
        assertEquals(50, v2.merit());
        assertEquals(50, v2.contribution());
        assertEquals(5, v2.version());
        SectState v3 = decode("{\"version\":3,\"member\":true,\"generation\":3,\"flags\":[\"lesson.six.done\"],\"contribution\":50,\"spent\":20}");
        assertEquals(30, v3.merit());
        assertEquals(SectStanding.DISCIPLE, SectStanding.of(v3, 0));
        SectState odd = decode("{\"version\":4,\"member\":true,\"generation\":3,\"flags\":[],\"contribution\":10,\"spent\":40}");
        assertEquals(0, odd.merit());
        assertEquals(10, odd.spent(), "spent is never above the total");
    }
}
