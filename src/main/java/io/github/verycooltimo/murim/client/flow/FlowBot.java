package io.github.verycooltimo.murim.client.flow;

import io.github.verycooltimo.murim.cultivation.flow.FlowGame;

/**
 * Dev-only hands for filming the high pill game (MURIM_CAPTURE_FLOW): {@code auto} is the game's own clean autopilot, {@code sloppy} plays
 * with mistakes and high pressure (first fork wrong, the second closed spot not restrained), {@code burst} never
 * restrains and takes every wrong branch until the backlash. Never active in normal play. Pure: reads and writes the game's public hands only.
 */
final class FlowBot {
    /** Above this pressure the messy bot brakes whatever it was doing. */
    static final double SAFE = 101;

    private FlowBot() { }

    static String mode() {
        String m = System.getenv("MURIM_CAPTURE_FLOW");
        return m == null ? "" : m;
    }

    static boolean drives() { return mode().equals("sloppy") || mode().equals("burst"); }

    static void drive(FlowGame g, String mode) {
        if (g.state != FlowGame.State.RUN) return;
        boolean burst = mode.equals("burst");
        FlowGame.Narrow ahead = null, in = null;
        int idx = 0, aheadIdx = -1, inIdx = -1;
        for (FlowGame.Narrow n : g.narrows) {
            if (Math.abs(g.s - n.s) < .022D) { in = n; inIdx = idx; }
            if (ahead == null && n.s - g.s > 0 && n.s - g.s < .03D && !n.open) { ahead = n; aheadIdx = idx; }
            idx++;
        }
        boolean inPoison = false;
        for (double[] sp : g.poison) if (g.s > sp[0] && g.s < sp[1]) inPoison = true;
        boolean want = ahead != null || (in != null && !in.open) || g.surgeT > .25D || (inPoison && g.flowT > .4D);
        boolean sloppy = !burst;
        // the messy run lets the first two closed spots and the poison burn (high pressure, red frame) but brakes when the backlash is near
        boolean skip = sloppy ? aheadIdx == 1 || inIdx == 1 : false;
        g.hold = !burst && ((want && !skip) || g.pressure > SAFE);
        int k = 0;
        for (FlowGame.Fork f : g.forks) {
            if (!f.done) {
                boolean wrong = burst || k == 0;
                g.side = wrong ? -f.goodKey : f.goodKey;
                break;
            }
            k++;
        }
    }
}
