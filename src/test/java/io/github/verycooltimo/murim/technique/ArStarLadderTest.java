package io.github.verycooltimo.murim.technique;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.verycooltimo.murim.technique.ArStarLadder.*;

class ArStarLadderTest {
    @Test void everyStarAndBoundary() {
        Stage[] expected = {Stage.BODY, Stage.BODY, Stage.CLOTH, Stage.GLINT, Stage.AIR,
            Stage.TRAIL, Stage.SMEAR, Stage.QI, Stage.DETACH, Stage.VOLUME, Stage.ORDER, Stage.COMPLETE};
        for (int stars = 1; stars <= 12; stars++) {
            assertEquals(expected[stars - 1], stage(stars), "star " + stars);
            for (Stage row : Stage.values())
                assertEquals(row.ordinal() <= expected[stars - 1].ordinal(), has(stars, row));
        }
        assertEquals(Stage.BODY, stage(-1));
        assertFalse(has(0, Stage.BODY));
        assertEquals(Stage.COMPLETE, stage(99));
    }
}
