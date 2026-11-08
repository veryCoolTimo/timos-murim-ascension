package io.github.verycooltimo.murim.client.sect;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.verycooltimo.murim.client.sect.DialogueCaptureGate.Result.*;
import static org.junit.jupiter.api.Assertions.*;

class DialogueCaptureGateTest {
    @Test
    void standIssuesUseWithin40TicksOfPlacementAtFourBlocks() throws Exception {
        // Guard the production wiring too: a boolean-only gate test missed the original regression.
        // Like DialogueAckClosesTest, locate sources from nested test-runner working directories.
        Path relativeSource = Path.of("src/main/java/io/github/verycooltimo/murim/client/sect/DialogueCapture.java");
        Path workingDirectory = Path.of("").toAbsolutePath();
        Path root = workingDirectory;
        while (root != null && !Files.isRegularFile(root.resolve(relativeSource))) {
            root = root.getParent();
        }
        assertNotNull(root, "DialogueCapture.java source tree not found above " + workingDirectory);
        String capture = Files.readString(root.resolve(relativeSource));
        assertTrue(capture.contains("DialogueCaptureGate.ready(target != null, mc.gameMode != null, mc.screen != null)"));
        assertFalse(capture.contains("canInteractWithEntity("));
        assertFalse(capture.contains("mc.hitResult"));
        assertTrue(capture.contains("if (result == DialogueCaptureGate.Result.USE) runStep(mc, s.action())"));
        for (int rotation : new int[] {0, 1}) {
            // Logged smoke stand: player (8.5,120,8.5), NPC four blocks forward.
            double npcX = rotation == 0 ? 8.5 : 4.5;
            double npcZ = rotation == 0 ? 12.5 : 8.5;
            assertEquals(4.0, Math.hypot(npcX - 8.5, npcZ - 8.5));
            var gate = new DialogueCaptureGate();
            int placed = 170;
            int firstUse = -1;
            for (int tick = 110; tick <= placed + 40; tick++) {
                boolean ready = DialogueCaptureGate.ready(tick >= placed, true, false);
                if (gate.poll(tick, ready, false) == USE) {
                    firstUse = tick;
                    break;
                }
            }
            assertTrue(firstUse >= placed && firstUse <= placed + 40, "rotation " + rotation);
        }
        assertFalse(DialogueCaptureGate.ready(true, false, false));
        assertFalse(DialogueCaptureGate.ready(true, true, true));
    }

    @Test
    void delayedPlacementDoesNotConsumeClickAndOpeningReleasesClock() {
        var gate = new DialogueCaptureGate();
        for (int tick = 110; tick < 170; tick++) assertEquals(WAIT, gate.poll(tick, false, false));
        assertEquals(USE, gate.poll(170, true, false));
        assertEquals(WAIT, gate.poll(171, true, false));
        assertEquals(OPEN, gate.poll(183, false, true));
    }

    @Test
    void retriesThreeTimesAndFailsOnlyAfterLastResponseWindow() {
        var gate = new DialogueCaptureGate();
        for (int tick = 110; tick < 190; tick++) {
            assertEquals((tick - 110) % 20 == 0 ? USE : WAIT, gate.poll(tick, true, false));
        }
        assertEquals(FAIL, gate.poll(190, true, false));
    }

    @Test
    void screenAtRetryDeadlineWinsOverRetryOrFailure() {
        var gate = new DialogueCaptureGate();
        for (int tick = 0; tick <= 60; tick += 20) assertEquals(USE, gate.poll(tick, true, false));
        assertEquals(OPEN, gate.poll(80, false, true));
    }

    @Test
    void absentOrLostTargetHasBoundedWait() {
        var missing = new DialogueCaptureGate();
        assertEquals(WAIT, missing.poll(110, false, false));
        assertEquals(FAIL, missing.poll(310, false, false));
        var lost = new DialogueCaptureGate();
        assertEquals(USE, lost.poll(110, true, false));
        assertEquals(WAIT, lost.poll(130, false, false));
        assertEquals(USE, lost.poll(150, true, false));
        assertEquals(FAIL, lost.poll(370, false, false));
    }
}
