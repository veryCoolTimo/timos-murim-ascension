package io.github.verycooltimo.murim.client;

import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;

/** Transition contract for PAL layers; step assets ease to rest during their last four ticks.
 * See docs/design/research/r1-player-animation.md for the PAL animation pipeline. */
public final class AnimationTransitions {
    public static final int CROSSFADE_TICKS = 3;
    public static final int STEP_FADE_OUT_TICKS = 4;

    static boolean start(boolean active, BooleanSupplier hardStart, IntPredicate blendedStart) {
        return active ? blendedStart.test(CROSSFADE_TICKS) : hardStart.getAsBoolean();
    }

    public static boolean fadeFootworkOnFinish(boolean dipper, boolean owned) {
        return owned && !dipper;
    }

    private AnimationTransitions() { }
}
