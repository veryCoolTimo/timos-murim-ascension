package io.github.verycooltimo.murim.client.sect;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class DialogueLayoutTest {
    @Test
    void twoThroughEightWrappedAnswersFitAtGuiScalesTwoAndThree() {
        for (int scale : new int[] {2, 3}) {
            int guiHeight = 1080 / scale;
            for (int count = 2; count <= 8; count++) {
                // Two Font.split lines, including Cyrillic replies, at OPTION=0.9 and lineHeight=9.
                int[] heights = new int[count + 1];
                Arrays.fill(heights, 17);
                DialogueLayout layout = DialogueLayout.fit(guiHeight - 48, heights);
                assertEquals(count + 1, layout.rows(), "scale=" + scale + " count=" + count);
                assertTrue(layout.panelHeight() <= guiHeight - 48);
                assertTrue(DialogueLayout.TOP + count * (layout.rowHeight() + 2)
                        + layout.rowHeight() <= layout.panelHeight() - DialogueLayout.BOTTOM);
            }
        }
    }

    @Test
    void eightShortAnswersAndExitFitTheMinimumGuiHeight() {
        int[] heights = new int[9];
        Arrays.fill(heights, 9);
        DialogueLayout layout = DialogueLayout.fit(200, heights);
        assertEquals(9, layout.rows());
        assertTrue(layout.panelHeight() <= 200);
    }

    @Test
    void overflowKeepsWrappedTextHeightAndLimitsVisibleRows() {
        DialogueLayout layout = DialogueLayout.fit(300, new int[] {36, 36, 36, 36, 36, 36, 36, 36, 36, 36});
        assertTrue(layout.rows() < 10);
        assertEquals(39, layout.rowHeight());
        assertTrue(layout.panelHeight() <= 300);
    }
}
