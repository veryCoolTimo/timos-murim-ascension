package io.github.verycooltimo.murim.client.sect;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.verycooltimo.murim.client.sect.DialogueCaptureAnswerGate.Result.*;

class DialogueCaptureAnswerGateTest {
    @Test
    void lateOpenAndSlowResponseNeverRepeatAnAnswer() {
        var gate = new DialogueCaptureAnswerGate(false);
        Object first = new Object(), next = new Object();
        for (int tick = 110; tick < 260; tick++) assertEquals(WAIT, gate.poll(tick, null, false));
        assertEquals(WAIT, gate.poll(260, first, false)); // typing/pages/guard still active
        assertEquals(PRESS, gate.poll(300, first, true));
        for (int tick = 301; tick < 700; tick++) assertEquals(WAIT, gate.poll(tick, first, true));
        assertEquals(DONE, gate.poll(700, next, false)); // acknowledgement, even before new text finishes
    }

    @Test
    void redisplayingAnUnchangedNodeDoesNotAcknowledgeTheChoice() {
        var gate = new DialogueCaptureAnswerGate(false);
        assertEquals(PRESS, gate.poll(0, new String("same node"), true));
        assertEquals(WAIT, gate.poll(100, new String("same node"), true));
        assertEquals(FAIL, gate.poll(600, new String("same node"), true));
        assertEquals("next_node_timeout", gate.failure());
    }

    @Test
    void missingOptionOrMissingResponseTimesOut() {
        Object first = new Object();
        var missing = new DialogueCaptureAnswerGate(false);
        assertEquals(WAIT, missing.poll(0, first, false));
        assertEquals(FAIL, missing.poll(600, first, false));
        var lost = new DialogueCaptureAnswerGate(false);
        assertEquals(PRESS, lost.poll(0, first, true));
        assertEquals(FAIL, lost.poll(600, first, true));
    }

    @Test
    void unexpectedClosureFailsButExplicitCloseWaitsForServerClosure() {
        Object first = new Object();
        var answer = new DialogueCaptureAnswerGate(false);
        assertEquals(PRESS, answer.poll(0, first, true));
        assertEquals(FAIL, answer.poll(1, null, false));
        var close = new DialogueCaptureAnswerGate(true);
        assertEquals(PRESS, close.poll(0, first, true));
        assertEquals(WAIT, close.poll(1, first, false));
        assertEquals(DONE, close.poll(2, null, false));
    }
}
