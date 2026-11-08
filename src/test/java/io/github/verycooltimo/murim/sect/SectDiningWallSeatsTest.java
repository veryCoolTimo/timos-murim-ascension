package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Playtest 22.10: in the real dining hall nobody sat at the wall seats, because the benches along the walls are walled in by
 * their tables and were marked "enclosed" (never offered). The shipped layout now enters them from the aisle bench's cell, so the
 * «wall seats first» order of {@link SectPlaces#diningSeats} has wall seats to start with, at every quarter turn of the site.
 */
class SectDiningWallSeatsTest {
    @Test
    void wallSeatsComeFirstInTheRealHallAtEveryRotation() {
        for (int rotation = 0; rotation < 4; rotation++) {
            MountHuaSite site = MountHuaSite.placement(1200 + 137 * rotation, -3400 + 91 * rotation, 70 + rotation, rotation);
            SectLayoutFile file = SectLayoutFile.anchored(site);
            assertTrue(file != null, "layout at rotation " + rotation);
            List<List<SectLayoutFile.Spot>> groups = SectPlaces.diningSeats(file, "gyeong_ha", null, 0L);
            List<SectLayoutFile.Spot> first = groups.get(0);
            SectLayoutFile.Room room = file.room(first.get(0).room());
            assertFalse(first.isEmpty());
            assertTrue(first.size() >= 16, "wall seats offered: " + first.size() + " at rotation " + rotation);
            for (SectLayoutFile.Spot s : first) {
                var b = room.bounds();
                int d = Math.min(Math.min(s.pos().getX() - b.x0(), b.x1() - s.pos().getX()), Math.min(s.pos().getZ() - b.z0(), b.z1() - s.pos().getZ()));
                assertEquals(0, d, "first group is the wall row (" + s.id() + ") at rotation " + rotation);
                assertTrue(s.approach() != null && !s.approach().equals(s.pos()), "wall seat " + s.id() + " has a cell to be entered from");
            }
        }
    }
}
