package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.decor.SectMissionBoard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Playtest 22.10: the 3x2 contract board's cells and the loot kind of a room. */
class SectPlaytestCTest {
    @Test
    void boardIsThreeWideTwoHighForEveryFacing() {
        BlockPos base = new BlockPos(10, 20, 30);
        for (Direction f : Direction.Plane.HORIZONTAL) {
            Set<BlockPos> cells = new HashSet<>();
            for (int c = 0; c < 3; c++) for (int r = 0; r < 2; r++) cells.add(SectMissionBoard.part(base, f, c, r));
            assertEquals(6, cells.size(), "six distinct cells facing " + f);
            assertEquals(base, SectMissionBoard.part(base, f, 1, 0), "the bottom middle part is the board's spot");
            // col 0 is the viewer's left: the viewer looks against the facing, so his left is FACING.getClockWise()
            assertEquals(base.relative(f.getClockWise()), SectMissionBoard.part(base, f, 0, 0));
            assertEquals(base.relative(f.getCounterClockWise()).above(), SectMissionBoard.part(base, f, 2, 1));
        }
    }

    @Test
    void lootKindFollowsTheRoom() {
        assertNull(SectPilfer.kindOf(null));
        assertEquals("food", SectPilfer.kindOf(room("dining_hall")));
        assertEquals("room", SectPilfer.kindOf(room("sleeping_room")));
        assertEquals("scrolls", SectPilfer.kindOf(room("library")));
        assertEquals("supplies", SectPilfer.kindOf(room("storeroom")));
    }

    private static SectLayoutFile.Room room(String function) {
        return new SectLayoutFile.Room("r", "b", 0, 0, new SectLayoutFile.Rect(0, 0, 1, 1), function, "ok", java.util.List.of(), java.util.List.of(), 1);
    }
}
