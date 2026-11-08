package io.github.verycooltimo.murim.client.sect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueClickTest {
    @Test
    @DisplayName("The click that skips the printing also picks the answer, after the first instant of the line")
    void firstClickPicks() {
        // skipTyping() arms guardUntil = lineTicks + 8; the very same click must not be eaten by it
        for (int ticks = DialogueClick.SKIP_GUARD; ticks < 200; ticks++) {
            assertTrue(DialogueClick.live(true, ticks, ticks + 8), "eaten at " + ticks);
        }
    }

    @Test
    @DisplayName("A stray click in the first ticks of a line is still swallowed, with or without a skip")
    void strayClickSwallowed() {
        assertFalse(DialogueClick.live(true, 1, 9));
        assertFalse(DialogueClick.live(false, 5, 8));
        assertTrue(DialogueClick.live(false, 8, 8));
    }

    @Test
    @DisplayName("A fresh line (guard 0) takes the first click at once")
    void freshLine() {
        assertTrue(DialogueClick.live(false, 0, 0));
    }
}
