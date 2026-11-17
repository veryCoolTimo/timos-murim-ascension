package io.github.verycooltimo.murim.client.flow;

import net.minecraft.client.Minecraft;

/**
 * Dev only (film stand, {@code MURIM_FLOW_TICKCLOCK=1}): the game's frame step comes from the world's tick clock instead of the wall clock, so a film that grabs
 * one frame per tick on a slow software renderer plays in real time (1 s of the film = 1 s of the game, nothing jumps after a long frame).
 * Each consumer has its own id (0 the game, 1 the director, 2 the scroll). Normal play: the wall-clock step is returned unchanged.
 */
final class FlowTick {
    static final boolean ON = "1".equals(System.getenv("MURIM_FLOW_TICKCLOCK"));
    private static final double[] LAST = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};

    private FlowTick() { }

    static double dt(int id, double wallDt) {
        if (!ON) return wallDt;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return wallDt;
        double now = (mc.level.getGameTime() + mc.getTimer().getGameTimeDeltaPartialTick(false)) / 20.0;
        double d = Double.isNaN(LAST[id]) ? 0 : Math.max(0, Math.min(.25, now - LAST[id]));
        LAST[id] = now;
        return d;
    }
}
