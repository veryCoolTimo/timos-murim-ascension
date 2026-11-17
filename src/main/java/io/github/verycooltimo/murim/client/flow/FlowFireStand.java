package io.github.verycooltimo.murim.client.flow;

import io.github.verycooltimo.murim.client.vfx.fx.FxTune;
import net.minecraft.client.Minecraft;

/**
 * Dev stand for the high pill's melt and rising aura ({@link FlowAura}): {@code MURIM_CAPTURE_FLOW=aurastand}. The game runs
 * as usual under it, but the director ({@link FlowMeridianFx}) takes everything from here: its clock is game time (so a film that grabs a frame every
 * second tick is real time), the pressure is a slow wave (period {@code flow.fire.stand_period} s, never above 0.125 Hz), the progress is fixed, the
 * intro and the finale are skipped, and only the aura and the floor vortex are drawn (no meridian on the body, no rim, no lamp). The camera stands
 * still on the seated figure, framed like the reference panel (figure about 55 % of the frame height, in profile).
 * Nothing here runs in normal play.
 */
final class FlowFireStand {
    static final boolean ON = "firestand".equals(System.getenv("MURIM_CAPTURE_FLOW")) || "aurastand".equals(System.getenv("MURIM_CAPTURE_FLOW"));
    /** The aura stand: the melt and the level script of {@link FlowAura} (the fire stand draws the same element without its melt). */
    static final boolean AURA = "aurastand".equals(System.getenv("MURIM_CAPTURE_FLOW"));
    private static double t0 = Double.NaN, last = Double.NaN, dt;

    private FlowFireStand() { }

    /** Seconds of game time since the stand started (monotonic). */
    static double clock() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return 0;
        double now = (mc.level.getGameTime() + mc.getTimer().getGameTimeDeltaPartialTick(false)) / 20.0;
        if (Double.isNaN(t0)) t0 = now;
        double c = now - t0;
        dt = Double.isNaN(last) ? 0 : Math.max(0, Math.min(.25, c - last));
        last = c;
        return c;
    }

    /** The step of the last {@link #clock()} call. */
    static double dt() { return dt; }

    /** Pressure 0..1 as a slow wave between the two keys. */
    static double pressure(double clock) {
        double lo = FxTune.d("flow.fire.stand_p_lo", .3), hi = FxTune.d("flow.fire.stand_p_hi", .95), period = FxTune.d("flow.fire.stand_period", 8);
        return lo + (hi - lo) * (.5 - .5 * Math.cos(2 * Math.PI * clock / period));
    }

    static double progress() { return FxTune.d("flow.fire.stand_progress", .55); }

    /** Camera: blocks from the focus, degrees of pitch, focus nudge in blocks. Framed on the panel: the seated figure fills about 55 % of the height. */
    static float camDist() { return lerp(FxTune.f("flow.aura.cam_melt_dist", 2.0F), FxTune.f("flow.fire.cam_dist", 3.2F)); }
    static float camPitch() { return FxTune.f("flow.fire.cam_pitch", 2F); }
    static float camFocus() { return lerp(FxTune.f("flow.aura.cam_melt_focus", .4F), FxTune.f("flow.fire.cam_focus", .8F)); }
    /** The aura stand starts close on the melt (figure about 70 % of the frame) and eases out to the aura framing. */
    private static float lerp(float melt, float aura) { float k = AURA ? (float) FlowAura.camBlend() : 1F; return melt + (aura - melt) * k; }
}
