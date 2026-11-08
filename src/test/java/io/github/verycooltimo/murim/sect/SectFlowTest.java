package io.github.verycooltimo.murim.sect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Docs/design/38 section 5.5: queues on one-wide stairs and narrow doors. */
class SectFlowTest {

    private static final List<SectFlow.Need> STAIR = List.of(new SectFlow.Need("stair.1", SectFlow.STAIR_CAPACITY, true));
    private static final List<SectFlow.Need> DOOR = List.of(new SectFlow.Need("door.1", SectFlow.DOOR_CAPACITY, false));

    @Test
    @DisplayName("A one-wide stair carries one direction at a time")
    void stairIsOneDirectionAtATime() {
        SectFlow flow = new SectFlow();
        assertTrue(flow.enter(STAIR, "a", 1, 0));
        assertTrue(flow.enter(STAIR, "b", 1, 0), "same direction follows");
        assertFalse(flow.enter(STAIR, "c", -1, 0), "the opposite direction waits");
        flow.leave(STAIR, "a");
        flow.leave(STAIR, "b");
        assertTrue(flow.enter(STAIR, "c", -1, 1), "free again once the others left");
    }

    @Test
    @DisplayName("A narrow door lets two through, the third waits")
    void doorCapacity() {
        SectFlow flow = new SectFlow();
        assertTrue(flow.enter(DOOR, "a", 0, 0));
        assertTrue(flow.enter(DOOR, "b", 0, 0));
        assertFalse(flow.enter(DOOR, "c", 0, 0));
        assertTrue(flow.enter(DOOR, "a", 0, 10), "a holder renews");
        assertEquals(2, flow.occupants("door.1", 10));
    }

    @Test
    @DisplayName("Leases run out: a walker who vanished never blocks the stair")
    void leasesExpire() {
        SectFlow flow = new SectFlow();
        assertTrue(flow.enter(STAIR, "ghost", 1, 0));
        assertFalse(flow.enter(STAIR, "x", -1, SectFlow.TTL - 1));
        assertTrue(flow.enter(STAIR, "x", -1, SectFlow.TTL + 1));
    }

    @Test
    @DisplayName("Several passages are taken together or not at all")
    void allOrNothing() {
        SectFlow flow = new SectFlow();
        List<SectFlow.Need> both = List.of(DOOR.get(0), STAIR.get(0));
        assertTrue(flow.enter(STAIR, "up", 1, 0));
        assertFalse(flow.enter(both, "down", -1, 0));
        assertEquals(0, flow.occupants("door.1", 0), "the door must not stay reserved by a failed attempt");
    }
}
