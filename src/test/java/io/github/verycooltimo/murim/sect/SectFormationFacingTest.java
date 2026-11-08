package io.github.verycooltimo.murim.sect;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The morning row faces the mentor's stand for all four rotations of the site (derived from the markers). */
class SectFormationFacingTest {
    private static SectLayoutFile.Spot spot(String id, String kind, BlockPos at, float yaw, int row, int col) {
        return new SectLayoutFile.Spot(id, kind, "stand", at, null, null, yaw, null, null, "training", null, List.of(), "ok", row, col);
    }

    @Test
    void rowFacesMentorStandInAllRotations() {
        int[][] dais = {{-17, 12}, {17, -12}, {-12, -17}, {12, 17}};
        for (int[] d : dais) {
            BlockPos row = new BlockPos(0, 64, 0);
            SectLayoutFile file = new SectLayoutFile(new SectLayoutFile.Frame(0, 0, 0, 0), Map.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(),
                    List.of(spot("f", "formation", row, 90.0F, 0, 0), spot("m", "mentor_post", new BlockPos(d[0], 64, d[1]), -90.0F, 0, 0)),
                    List.of(), List.of(), List.of());
            SectSchedule.Task t = new SectSchedule.Task(SectSchedule.Kind.FORM_ROW, "training", -7.5D, SectSchedule.FRONT_ROW, 0.0D, 1.0D);
            SectPlaces.Pick pick = SectPlaces.pick(file, SectRoster.of("yul_jin").orElseThrow(), t, 23300, null, 0L, null);
            assertNotNull(pick);
            float want = SectLayout.yawOf(d[0] + 0.5D - pick.x(), d[1] + 0.5D - pick.z());
            assertTrue(Math.abs(Mth.wrapDegrees(pick.yaw() - want)) <= 20.0F, "row does not face the stand for " + d[0] + "," + d[1]);
        }
    }
}
