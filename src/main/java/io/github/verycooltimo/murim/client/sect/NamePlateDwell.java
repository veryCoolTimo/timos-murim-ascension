package io.github.verycooltimo.murim.client.sect;

/**
 * The timer behind the sect name plate (author 21.10: "names only when you look at the NPC, and after one second, so they do
 * not flicker"). Pure logic with no Minecraft types, so it is unit tested: feed it the id of the person under the crosshair
 * every client tick (or -1); it says who may wear a plate and how opaque it is.
 */
public final class NamePlateDwell {
    /** How long the crosshair has to rest on the same person before the plate appears (ticks). */
    public static final int DELAY = 20;
    /** The plate fades in over this many ticks after the delay. */
    public static final int FADE = 6;

    /** A short slip of the aim off a walking person (nothing under the crosshair) does not restart the second (UX review 21.10). */
    public static final int GRACE = 4;

    private int target = -1;
    private int ticks;
    private int missed;

    /** One client tick with the person under the crosshair now (-1: nobody). Looking away or at another resets at once. */
    public void tick(int aimed) {
        if (aimed == target) {
            missed = 0;
            if (aimed >= 0) ticks++;
        } else if (aimed == -1 && target >= 0 && ++missed <= GRACE) {
            // the aim slipped for a moment: keep counting, the plate stays
            ticks++;
        } else {
            target = aimed;
            ticks = 0;
            missed = 0;
        }
    }

    /** The person whose plate shows now, or -1. */
    public int shown() {
        return target >= 0 && ticks >= DELAY ? target : -1;
    }

    /** Opacity 0..1 of the plate of {@code id}: 0 before the delay, rising over {@link #FADE} ticks. */
    public float alpha(int id) {
        if (id < 0 || id != target || ticks < DELAY) return 0.0F;
        return Math.min(1.0F, (ticks - DELAY + 1) / (float) FADE);
    }

    public void reset() {
        target = -1;
        ticks = 0;
        missed = 0;
    }
}
