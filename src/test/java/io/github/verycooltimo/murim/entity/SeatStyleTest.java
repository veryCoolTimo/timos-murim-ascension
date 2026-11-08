package io.github.verycooltimo.murim.entity;

import io.github.verycooltimo.murim.sect.SectLayoutFile;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The way of sitting follows the furniture (author 19.10): chair-class seats are the chair sit, cushion, mat and floor the lotus. */
class SeatStyleTest {

    private static SectLayoutFile.Spot spot(String kind, String posture) {
        BlockPos at = new BlockPos(1, 2, 3);
        return new SectLayoutFile.Spot("spot.x", kind, posture, at, at.south(), null, 0.0F, null, "hall", null, null,
                List.of("all"), "ok", -1, -1);
    }

    @Test
    @DisplayName("Chair, armchair (the throne), stool and bench are the chair sit; cushion and kneeling mat are the lotus")
    void kindsByFurniture() {
        for (String chair : new String[] {"seat_chair", "seat_armchair", "seat_stool", "seat_bench"}) {
            assertEquals(SeatStyle.CHAIR, SeatStyle.of(spot(chair, "sit"), null), chair);
        }
        for (String floor : new String[] {"cushion", "kneel_mat"}) {
            assertEquals(SeatStyle.LOTUS, SeatStyle.of(spot(floor, floor.equals("cushion") ? "sit" : "kneel"), null), floor);
        }
    }

    @Test
    @DisplayName("No spot, or a spot that is no seat, is the floor: the lotus")
    void floorIsTheLotus() {
        assertEquals(SeatStyle.LOTUS, SeatStyle.of(null, null));
        assertEquals(SeatStyle.LOTUS, SeatStyle.of(spot("work_desk", "stand"), null));
        assertNull(SeatStyle.ofKind("work_desk"));
        assertNull(SeatStyle.ofKind(null));
    }

    @Test
    @DisplayName("The synced ordinal round-trips and a bad one is not sitting")
    void ordinal() {
        for (SeatStyle s : SeatStyle.values()) {
            assertEquals(s, SeatStyle.byOrdinal(s.ordinal()));
        }
        assertEquals(SeatStyle.NONE, SeatStyle.byOrdinal(-1));
        assertEquals(SeatStyle.NONE, SeatStyle.byOrdinal(99));
    }
}
