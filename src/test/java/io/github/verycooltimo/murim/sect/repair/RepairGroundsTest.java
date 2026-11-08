package io.github.verycooltimo.murim.sect.repair;

import io.github.verycooltimo.murim.sect.SectLayoutFile;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The grounds the repairs cover (docs/design/39-player-path-v2.md, «Ремонт секты и казна»): buildings and yards, not the mountain. */
class RepairGroundsTest {

    private final RepairEnv.Scope scope = grounds();

    private static RepairEnv.Scope grounds() {
        SectLayoutFile file = SectLayoutFile.shipped();
        assertNotNull(file, "the shipped layout file is missing");
        return RepairEnv.grounds(file);
    }

    @Test
    @DisplayName("Walls and roofs of the buildings and the paving of the yards are the sect's grounds")
    void buildingsAndYards() {
        // the north wall of the dining hall, the second floor of the treasury, the training square, the forecourt of the main hall
        assertTrue(scope.repairable(new BlockPos(-1118, 212, 4245)));
        assertTrue(scope.repairable(new BlockPos(-1020, 218, 4220)));
        assertTrue(scope.repairable(new BlockPos(-1066, 225, 4230)));
        assertTrue(scope.repairable(new BlockPos(-1066, 231, 4265)));
        // the roof of the main hall (walls reach y 262)
        assertTrue(scope.repairable(new BlockPos(-1066, 266, 4278)));
        // a crater under the paving of the square
        assertTrue(scope.repairable(new BlockPos(-1066, 221, 4230)));
    }

    @Test
    @DisplayName("The mountain round the sect, the sky above it and the sealed penance cave are not")
    void notTheMountain() {
        assertFalse(scope.repairable(new BlockPos(-1066, 300, 4230)), "high above the buildings");
        assertFalse(scope.repairable(new BlockPos(-1066, 150, 4230)), "deep in the mountain under the square");
        assertFalse(scope.repairable(new BlockPos(0, 70, 0)), "the far world");
        assertFalse(scope.repairable(new BlockPos(-1112, 238, 4330)), "inside the sealed penance cave");
    }

    @Test
    @DisplayName("Player building is noted a little wider than the grounds, so a chest by a wall guards it")
    void trackedIsWider() {
        // north of the dining hall wall box (z 4245) grown by six blocks (4239), and the tracked ring three more (4236)
        BlockPos edge = new BlockPos(-1118, 212, 4237);
        assertFalse(scope.repairable(edge));
        assertTrue(scope.tracked(edge));
        assertTrue(scope.tracked(new BlockPos(-1118, 212, 4245)));
        assertFalse(scope.tracked(new BlockPos(0, 70, 0)));
    }
}
