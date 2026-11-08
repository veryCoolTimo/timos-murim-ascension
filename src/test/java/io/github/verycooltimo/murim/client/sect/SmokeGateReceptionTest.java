package io.github.verycooltimo.murim.client.sect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.verycooltimo.murim.client.sect.SmokeGateReception.Stage.*;

class SmokeGateReceptionTest {
    @Test
    void absentWalkingPhaseStillAllowsKnockGreeterTalkAndGuideHandoff() {
        String phase = null; // done(walk_trail) cleared the walking segment
        String requested = SmokeGateReception.requestPhase(phase, true);
        assertEquals("gate_reception", requested);
        var gate = new SmokeGateReception();
        for (int tick = 0; tick < 100; tick++) {
            assertTrue(SmokeGateReception.accepts("gate", requested, "gate", phase, true));
            assertFalse(gate.knock(false));
        }
        assertEquals(AIM, gate.stage());
        assertFalse(gate.greet(true));
        assertFalse(gate.finish(true));
        assertTrue(gate.knock(true));
        assertFalse(gate.knock(true)); // exactly one use pulse/log
        assertEquals(GREETER, gate.stage());
        assertFalse(gate.greet(false));
        assertFalse(gate.finish(true));
        assertTrue(gate.greet(true));
        assertFalse(gate.greet(true));
        assertEquals(TALK, gate.stage());
        assertFalse(gate.finish(false)); // dialogue has not ordered the guide yet
        assertTrue(gate.finish(true));
        assertEquals(DONE, gate.stage());
        assertFalse(SmokeGateReception.accepts("gate", requested, "npc:guide", "walk_join", false));
    }

    @Test
    void staleWalkingAndAbsentPhaseResponsesAreRejected() {
        assertFalse(SmokeGateReception.accepts("gate", "walk_trail", "gate", null, true));
        assertFalse(SmokeGateReception.accepts("gate", null, "gate", null, true));
        assertFalse(SmokeGateReception.accepts("gate", null, "gate", null, false));
        assertTrue(SmokeGateReception.accepts("npc:guide", "walk_join", "npc:guide", "walk_join", false));
    }
}
