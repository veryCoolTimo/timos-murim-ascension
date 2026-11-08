package io.github.verycooltimo.murim.sect;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anti-repeat memory and voices (docs/design/39-player-path-v2.md, «Реплики: объём и частота»): the pure part of
 * {@link SectLines}, the bounded {@link SectMemory}, the voices and registers of {@link SectVoice}. The volume of the pools:
 * {@code SectLinePoolsTest}.
 */
class SectLinesTest {

    // ------------------------------------------------------------------ anti-repeat (pure part)

    private static List<String> pool() {
        return SectLines.keys("murim.test.pool", 6);
    }

    @Test
    @DisplayName("Nothing heard yet: every line is allowed")
    void freshPlayerMayHearAnything() {
        assertEquals(pool(), SectLines.allowed(pool(), List.of(), 5L));
    }

    @Test
    @DisplayName("A line heard today is not allowed again today while others remain")
    void todayIsExcluded() {
        List<String> keys = pool();
        List<Long> memory = new ArrayList<>(List.of(SectMemory.pack(5, keys.get(0)), SectMemory.pack(5, keys.get(1))));
        List<String> ok = SectLines.allowed(keys, memory, 5L);
        assertEquals(4, ok.size());
        assertFalse(ok.contains(keys.get(0)) || ok.contains(keys.get(1)));
        // yesterday does not count
        assertEquals(6, SectLines.allowed(keys, memory, 6L).size());
    }

    @Test
    @DisplayName("When the pool is used up, the most recent half is rested, then at least the last line")
    void usedUpPoolRestsTheRecentOnes() {
        List<String> keys = pool();
        List<Long> memory = new ArrayList<>();
        for (String k : keys) {
            memory.add(SectMemory.pack(9, k));
        }
        List<String> ok = SectLines.allowed(keys, memory, 9L);
        assertEquals(3, ok.size());
        assertEquals(keys.subList(0, 3), ok, "the three oldest of six may be said again");
        assertFalse(ok.contains(keys.get(5)));
        // a pool of two, both heard: only the older one
        List<String> two = SectLines.keys("murim.test.two", 2);
        List<Long> m2 = List.of(SectMemory.pack(9, two.get(0)), SectMemory.pack(9, two.get(1)));
        assertEquals(List.of(two.get(0)), SectLines.allowed(two, m2, 9L));
    }

    @Test
    @DisplayName("Simulated day: 60 picks from a pool of six never repeat a line back to back and use all of them")
    void longRunNeverRepeatsBackToBack() {
        List<String> keys = pool();
        List<Long> memory = new ArrayList<>();
        String last = "";
        Set<String> used = new HashSet<>();
        for (int i = 0; i < 60; i++) {
            List<String> ok = SectLines.allowed(keys, memory, 3L);
            String pick = ok.get(Math.floorMod(i * 7, ok.size()));
            assertNotEquals(last, pick, "back to back at " + i);
            if (i < 6) {
                assertFalse(used.contains(pick), "a repeat within the first six at " + i);
            }
            memory.add(SectMemory.pack(3, pick));
            if (memory.size() > SectMemory.KEEP) {
                memory.remove(0);
            }
            used.add(pick);
            last = pick;
        }
        assertEquals(6, used.size());
    }

    @Test
    @DisplayName("Keys of a pool: the stem is the key without the number; a key without a number is its own pool")
    void poolOfKey() {
        assertEquals("murim.bubble.meal", SectLines.poolOf("murim.bubble.meal.3"));
        assertEquals("murim.sect.plum.alarm", SectLines.poolOf("murim.sect.plum.alarm"));
        assertEquals("mixed", SectLines.commonPool(List.of("a.b.1", "a.c.1")));
        assertEquals("a.b", SectLines.commonPool(List.of("a.b.1", "a.b.2")));
    }

    // ------------------------------------------------------------------ memory and voices

    @Test
    @DisplayName("The memory is bounded: twelve lines per pool, a limited number of pools, packed day and key")
    void memoryIsBounded() {
        SectMemory m = SectMemory.EMPTY;
        for (int i = 0; i < 50; i++) {
            m = m.withHeard("p", 1L + i / 10, "p." + i);
        }
        assertEquals(SectMemory.KEEP, m.heard("p").size());
        assertEquals(5L, SectMemory.dayOf(m.heard("p").get(SectMemory.KEEP - 1)));
        for (int i = 0; i < SectMemory.MAX_POOLS + 40; i++) {
            m = m.withHeard("pool" + i, 100L + i, "k");
        }
        assertEquals(SectMemory.MAX_POOLS, m.heard().size());
        assertTrue(m.heard().containsKey("pool" + (SectMemory.MAX_POOLS + 39)), "the newest pool was dropped");
        // the same line twice in a row is one entry
        SectMemory same = SectMemory.EMPTY.withHeard("q", 2L, "q.1").withHeard("q", 2L, "q.1");
        assertEquals(1, same.heard("q").size());
    }

    @Test
    @DisplayName("The memory survives the save format; a save without it reads as an empty memory")
    void memorySurvivesTheSaveFormat() {
        SectMemory m = SectMemory.EMPTY.withHeard("a.b", 3L, "a.b.2").withHeard("a.b", 3L, "a.b.5").withEvent("bout_won", 99L)
                .withReacted("bout_won", 5L).withStamp("gap", 7L);
        JsonElement json = SectMemory.CODEC.encodeStart(JsonOps.INSTANCE, m).getOrThrow();
        assertEquals(m, SectMemory.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
        assertEquals(SectMemory.EMPTY, SectMemory.CODEC.parse(JsonOps.INSTANCE, new JsonObject()).getOrThrow());
    }

    @Test
    @DisplayName("An event starts a new occurrence: the people who answered the old one may answer again")
    void newOccurrenceClearsTheMask() {
        SectMemory m = SectMemory.EMPTY.withEvent("bout_won", 100L).withReacted("bout_won", 0b101L);
        assertEquals(0b101L, m.reacted("bout_won"));
        m = m.withEvent("bout_won", 900L);
        assertEquals(0L, m.reacted("bout_won"));
        assertEquals(900L, m.event("bout_won"));
        assertEquals(Long.MIN_VALUE, m.event("realm_up"));
    }

    @Test
    @DisplayName("The roster fits the 64 bits of a reaction mask")
    void rosterFitsTheMask() {
        assertTrue(SectRoster.ALL.size() < 63, "the reaction mask holds 63 people");
    }

    @Test
    @DisplayName("Voices follow the generation; registers follow the rank of the listener")
    void voicesAndRegisters() {
        assertEquals(SectVoice.Voice.LAY, SectVoice.of(SectRoster.of("cook_kim").orElseThrow()));
        assertEquals(SectVoice.Voice.THIRD, SectVoice.of(SectRoster.of("yul_ak").orElseThrow()));
        assertEquals(SectVoice.Voice.SECOND, SectVoice.of(SectRoster.of("seo_rang").orElseThrow()));
        assertEquals(SectVoice.Voice.FIRST, SectVoice.of(SectRoster.of("gyeong_pil").orElseThrow()));
        assertEquals(SectVoice.Voice.ELDER, SectVoice.of(SectRoster.of("tae_rok").orElseThrow()));
        assertEquals(SectVoice.Register.LAY, SectVoice.register(SectVoice.Voice.LAY, SectRank.DISCIPLE));
        assertEquals(SectVoice.Register.PEER, SectVoice.register(SectVoice.Voice.THIRD, SectRank.NOVICE));
        assertEquals(SectVoice.Register.SENIOR, SectVoice.register(SectVoice.Voice.FIRST, SectRank.GRADUATE));
        assertEquals(SectVoice.Register.SENIOR, SectVoice.register(SectVoice.Voice.ELDER, SectRank.TRUSTED));
        // an elder or a first disciple is spoken to up (juniors, servants) or as a colleague (the older generations), never instructed
        for (SectRank rank : List.of(SectRank.ELDER, SectRank.FIRST)) {
            assertEquals(SectVoice.Register.UP, SectVoice.register(SectVoice.Voice.LAY, rank));
            assertEquals(SectVoice.Register.UP, SectVoice.register(SectVoice.Voice.THIRD, rank));
            assertEquals(SectVoice.Register.UP, SectVoice.register(SectVoice.Voice.SECOND, rank));
            assertEquals(SectVoice.Register.COLLEAGUE, SectVoice.register(SectVoice.Voice.FIRST, rank));
            assertEquals(SectVoice.Register.COLLEAGUE, SectVoice.register(SectVoice.Voice.ELDER, rank));
        }
    }
}
