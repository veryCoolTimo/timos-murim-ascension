package io.github.verycooltimo.murim.client.flow;

import io.github.verycooltimo.murim.cultivation.PillKind;
import io.github.verycooltimo.murim.cultivation.flow.FlowCharacter;
import io.github.verycooltimo.murim.cultivation.flow.FlowGame;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dev bot of the capture stand: the messy run ends (not by a backlash), the burst run ends in a backlash. */
class FlowBotTest {
    static double maxPressure;

    private static FlowGame play(long seed, String mode) {
        FlowCharacter c = FlowCharacter.of(List.of(PillKind.ORIGIN_ENERGY.flow(), PillKind.THOUSAND_POISON.flow()));
        FlowGame g = new FlowGame(c, seed, false, false, false);
        for (int i = 0; i < 60 * 80 && g.running(); i++) {
            FlowBot.drive(g, mode);
            g.update(1 / 60.0D);
            maxPressure = Math.max(maxPressure, g.pressure);
        }
        return g;
    }

    @Test
    void outcomes() {
        int burstOk = 0, messyDone = 0, messyMistakes = 0;
        StringBuilder sb = new StringBuilder();
        for (long seed = 1; seed <= 12; seed++) {
            FlowGame b = play(seed, "burst"), m = play(seed, "sloppy");
            if (b.state == FlowGame.State.BURST) burstOk++;
            if (m.state == FlowGame.State.DONE) messyDone++;
            if (m.lost > 10) messyMistakes++;
            maxPressure = 0; play(seed, "sloppy");
            sb.append(String.format("seed %d burst=%s(s=%.2f) sloppy=%s lost=%.0f maxP=%.0f%n", seed, b.state, b.s, m.state, m.lost, maxPressure));
        }
        System.out.println(sb);
        assertTrue(burstOk >= 6, "burst bot rarely bursts");
        assertTrue(messyDone >= 6, "sloppy bot rarely finishes");
    }
}
