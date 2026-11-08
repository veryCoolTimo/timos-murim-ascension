package io.github.verycooltimo.murim.cultivation;

/**
 * The numbers of the rank breakthrough scene that both sides agree on, with no game objects (hua-main-1810 section 4,
 * author 18.10): how far the effect reaches per rank, when the layered sound plays, how long the afterglow lasts.
 *
 * <p>The scene is {@link Realm#WARNING_TICKS} of omen and then {@code sceneTicks} (12 / 14 / 16 / 20 s). The sound goes
 * breathing in the omen, a hum that rises through the scene, three crack clicks, a silence of {@link #SILENCE_TICKS}
 * and the impact; the echo after the impact is heard up to {@link #ECHO_RANGE} blocks away.
 */
public final class BreakthroughScript {

    /** Effect radius in blocks per rank the breakthrough leads to: third, second, first, Peak (one scale for every layer). */
    private static final double[] RADIUS = {3.0D, 5.0D, 7.0D, 10.0D};

    /** The silence between the last crack and the impact: 0.4 s. */
    public static final int SILENCE_TICKS = 8;

    /** A hum sample is started every this many ticks. */
    public static final int HUM_PERIOD = 16;

    /** The hum is not started inside the last stretch before the cracks: its sample would run over them. */
    public static final int HUM_STOP_BEFORE_END = 36;

    /** Where the three cracks fall, in ticks before the impact. */
    private static final int[] CRACKS_BEFORE_END = {34, 26, 18};

    /** How far the echo after the impact is heard, in blocks (the sound event has a fixed range). */
    public static final float ECHO_RANGE = 64.0F;

    /** The afterglow after the rank-up: aura, breath vapour and eyes last 30 seconds. */
    public static final int AFTERGLOW_TICKS = 600;

    private BreakthroughScript() {
    }

    /** Effect radius for a breakthrough into {@code targetRank} (1 third .. 4 Peak); out of range ranks are clamped. */
    public static double radius(int targetRank) {
        return RADIUS[Math.max(0, Math.min(RADIUS.length - 1, targetRank - 1))];
    }

    /** Scene ticks (counted from the end of the omen) at which the three crack clicks play. */
    public static int[] crackTicks(int sceneTicks) {
        int[] out = new int[CRACKS_BEFORE_END.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = sceneTicks - CRACKS_BEFORE_END[i];
        }
        return out;
    }

    /** The scene tick at which every scene sound is cut, leaving {@link #SILENCE_TICKS} of silence before the impact. */
    public static int silenceStart(int sceneTicks) {
        return sceneTicks - SILENCE_TICKS;
    }

    /** Does a hum sample start on this scene tick? */
    public static boolean humStarts(int sceneTick, int sceneTicks) {
        return sceneTick >= 0 && sceneTick % HUM_PERIOD == 0 && sceneTick < sceneTicks - HUM_STOP_BEFORE_END;
    }

    /** Share of the scene gone, 0..1. */
    private static double share(int sceneTick, int sceneTicks) {
        return Math.max(0.0D, Math.min(1.0D, sceneTick / (double) Math.max(1, sceneTicks)));
    }

    /** Pitch of the hum at this scene tick: rises from 0.6 to about 1.5. */
    public static float humPitch(int sceneTick, int sceneTicks) {
        return (float) (0.6D + 0.9D * Math.pow(share(sceneTick, sceneTicks), 1.2D));
    }

    /**
     * Volume of the hum at this scene tick: swells from 0.2 to 0.6. A sample is two seconds long and a new one starts every 0.8 s,
     * so three or four overlap: the stack is what is heard (author 03.10: «не взрывать уши»).
     */
    public static float humVolume(int sceneTick, int sceneTicks) {
        return (float) (0.2D + 0.4D * share(sceneTick, sceneTicks));
    }

    /** The omen ticks at which the breath is heard (in, then out). */
    public static boolean breathAt(int omenTick) {
        return omenTick == 3 || omenTick == 27;
    }
}
