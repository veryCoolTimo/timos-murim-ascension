package io.github.verycooltimo.murim.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TechniqueSlotsHudLayoutTest {
    @Test
    void nineCellsStayOnScreenAtNarrowGuiWidths() {
        // Bug: with 9 cells at GUI width 341 (1024x768, scale 3) the outer columns were drawn at x = -13 and -35.
        for (int w : new int[] {341, 320, 256, 427, 480}) {
            for (int i = 0; i < 9; i++) {
                int[] at = TechniqueSlotsHud.leftPosition(i, 9, w, 192);
                assertTrue(at[0] >= 0 && at[0] + TechniqueSlotsHud.SIZE <= w, "w=" + w + " cell " + i + " x=" + at[0]);
                assertTrue(at[1] >= 0, "w=" + w + " cell " + i + " y=" + at[1]);
            }
        }
    }

    @Test
    void wideGuiKeepsOriginalColumnsAndNoCellsOverlap() {
        int[] first = TechniqueSlotsHud.leftPosition(0, 9, 480, 270);
        assertEquals(480 / 2 - 91 - 6 - TechniqueSlotsHud.SIZE, first[0]);
        assertEquals(270 - TechniqueSlotsHud.SIZE - 1, first[1]);
        for (int w : new int[] {341, 480}) {
            var seen = new java.util.HashSet<String>();
            for (int i = 0; i < 9; i++) {
                int[] at = TechniqueSlotsHud.leftPosition(i, 9, w, 270);
                assertTrue(seen.add(at[0] + "," + at[1]), "overlap at " + i);
            }
        }
    }
}
