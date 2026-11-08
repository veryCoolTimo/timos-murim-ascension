package io.github.verycooltimo.murim.sect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The personal stock of the merit shop: refilled by the player's own play time and by nothing else (docs/adr/0012). */
class MeritStockTest {
    private static final MeritShop.Refill PLUM = new MeritShop.Refill(2, 30);
    private static final MeritShop.Refill SWORD = new MeritShop.Refill(1, 20);
    private static final long MIN = MeritStock.TICKS_PER_MINUTE;

    @Test
    @DisplayName("Untouched stock is full; taking from a full stock starts the clock, taking the second does not restart it")
    void takeStartsTheClockOnce() {
        SectState s = SectState.NONE.joined();
        long t0 = 5_000L;
        assertEquals(2, MeritStock.level(s, "snow_plum", PLUM, t0).count());
        SectState one = MeritStock.take(s, "snow_plum", PLUM, t0);
        assertEquals(1, MeritStock.level(one, "snow_plum", PLUM, t0).count());
        // Ten minutes later the second unit is taken: the clock of the first stays where it was.
        SectState two = MeritStock.take(one, "snow_plum", PLUM, t0 + 10 * MIN);
        MeritStock.Level empty = MeritStock.level(two, "snow_plum", PLUM, t0 + 10 * MIN);
        assertEquals(0, empty.count());
        assertEquals(t0, empty.clock(), "a second purchase must not delay the first unit");
        assertEquals(20, MeritStock.minutesToNext(empty, t0 + 10 * MIN, PLUM), "30 minutes from the first purchase");
    }

    @Test
    @DisplayName("One unit per period of play: 29 minutes give nothing, 30 give one, 60 give both")
    void refillByPlayTime() {
        long t0 = 1_000L;
        SectState empty = MeritStock.take(MeritStock.take(SectState.NONE.joined(), "snow_plum", PLUM, t0), "snow_plum", PLUM, t0);
        assertEquals(0, MeritStock.level(empty, "snow_plum", PLUM, t0 + 30 * MIN - 1).count());
        assertEquals(1, MeritStock.level(empty, "snow_plum", PLUM, t0 + 30 * MIN).count());
        assertEquals(1, MeritStock.level(empty, "snow_plum", PLUM, t0 + 59 * MIN).count());
        assertEquals(2, MeritStock.level(empty, "snow_plum", PLUM, t0 + 60 * MIN).count());
        assertEquals(2, MeritStock.level(empty, "snow_plum", PLUM, t0 + 600 * MIN).count(), "never above the maximum");
        // The wait is counted to the next unit, not to the full stock.
        MeritStock.Level one = MeritStock.level(empty, "snow_plum", PLUM, t0 + 40 * MIN);
        assertEquals(20, MeritStock.minutesToNext(one, t0 + 40 * MIN, PLUM));
    }

    @Test
    @DisplayName("The sword: one per 20 minutes of play")
    void swordEveryTwentyMinutes() {
        long t0 = 77L;
        SectState taken = MeritStock.take(SectState.NONE.joined(), "sword", SWORD, t0);
        assertEquals(0, MeritStock.level(taken, "sword", SWORD, t0 + 20 * MIN - 1).count());
        assertEquals(1, MeritStock.level(taken, "sword", SWORD, t0 + 20 * MIN).count());
        assertEquals(20, MeritStock.minutesToNext(MeritStock.level(taken, "sword", SWORD, t0), t0, SWORD));
    }

    @Test
    @DisplayName("The day plays no part: the stock reads the play-time counter only")
    void noDayInIt() {
        // The state holds two numbers; there is no world time anywhere in it, so a night slept through or /time set cannot move it.
        SectState taken = MeritStock.take(SectState.NONE.joined(), "sword", SWORD, 100L);
        assertEquals(java.util.Set.of(MeritStock.countKey("sword"), MeritStock.clockKey("sword")), taken.journal().keySet());
        // The same play time gives the same stock however often it is read.
        for (int i = 0; i < 5; i++) {
            assertEquals(0, MeritStock.level(taken, "sword", SWORD, 100L + 10 * MIN).count());
        }
    }

    @Test
    @DisplayName("A play-time counter that went back is counted from its new zero, and the read does not slide the clock")
    void counterGoingBack() {
        SectState taken = MeritStock.take(MeritStock.take(SectState.NONE.joined(), "snow_plum", PLUM, 9_000_000L), "snow_plum", PLUM, 9_000_000L);
        MeritStock.Level reset = MeritStock.level(taken, "snow_plum", PLUM, 1_000L);
        assertEquals(0, reset.count());
        assertEquals(30, MeritStock.minutesToNext(reset, 1_000L, PLUM));
        // Reading again later (nothing was saved in between) still finds the wait running out.
        assertEquals(1, MeritStock.level(taken, "snow_plum", PLUM, 1_000L + 30 * MIN).count());
        assertEquals(2, MeritStock.level(taken, "snow_plum", PLUM, 1_000L + 60 * MIN).count());
    }

    @Test
    @DisplayName("An old save has no shop keys in its journal: every stock reads full")
    void oldSaveReadsFull() {
        SectState old = SectState.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, com.google.gson.JsonParser.parseString(
                "{\"version\":2,\"member\":true,\"generation\":3,\"flags\":[],\"contribution\":50}")).getOrThrow();
        assertTrue(old.journal().isEmpty());
        assertEquals(PLUM.max(), MeritStock.level(old, "snow_plum", PLUM, 123_456L).count());
        assertEquals(0, MeritStock.minutesToNext(MeritStock.level(old, "snow_plum", PLUM, 123_456L), 123_456L, PLUM));
    }

    @Test
    @DisplayName("Damaged journal values are clamped into the stock range")
    void clamped() {
        SectState odd = SectState.NONE.joined().record(MeritStock.countKey("snow_plum"), 99L).record(MeritStock.clockKey("snow_plum"), 5L);
        assertEquals(2, MeritStock.level(odd, "snow_plum", PLUM, 6L).count());
        SectState negative = SectState.NONE.joined().record(MeritStock.countKey("snow_plum"), -4L).record(MeritStock.clockKey("snow_plum"), 5L);
        assertEquals(0, MeritStock.level(negative, "snow_plum", PLUM, 6L).count());
    }
}
