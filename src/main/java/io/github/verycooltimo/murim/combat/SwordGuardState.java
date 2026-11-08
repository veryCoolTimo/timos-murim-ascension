package io.github.verycooltimo.murim.combat;

/** Variant A timing, in server ticks. Transient and owned by one entity. */
public final class SwordGuardState {
    private static final long NEVER = Long.MIN_VALUE / 2;
    public long swing = NEVER;
    public long ready = NEVER;
    public long press = NEVER;
    public long start = NEVER;
    public long end = NEVER;
    public long contact = NEVER;
    public long brokenUntil = NEVER;
    public boolean held;
    public boolean spent;
    public boolean consumed;
    public long knockbackTick = NEVER;
    public float knockbackScale = 1;
    public double knockbackX, knockbackZ;
    public int shown = -1;
    public boolean suppressPose;
    public int areaDepth;

    public void swing(long now, int recovery) {
        swing = now;
        ready = now + Math.max(4, recovery);
        start = end = NEVER;
        spent = consumed = false;
    }

    public void hold(boolean down, long now) {
        if (down && !held) {
            press = now;
            if (!spent && now >= swing && now < ready) {
                start = Math.max(now, swing + 3);
                end = start + 5;
                spent = true;
                consumed = false;
            }
        }
        held = down;
        if (!down) start = end = NEVER; // Release cannot bank an active window.
    }

    public boolean blocking(long now) {
        return now >= brokenUntil && held && (now < swing || now - swing >= 3);
    }

    public boolean deflecting(long now) {
        return blocking(now) && !consumed && now >= start && now < end;
    }

    /**
     * Ticks before a contact in which a fresh press still counts as exact. The server handles a player's packets BEFORE the entities
     * of the next tick move (MinecraftServer#waitUntilNextTick runs the tasks, then tickChildren advances the game time and ticks
     * the entities), so a press that reaches the server "in the tick of the contact" is stamped with the PREVIOUS game time; a
     * strict {@code press == now} never matched a mob's strike or a flying dagger (probe of 24.10: 0 of 24 exact presses parried).
     * Two ticks keep it the exact timing of ADR 0020, about 100 ms, and the stamp still comes from the server, never the client.
     */
    public static final int EXACT_TICKS = 2;

    public boolean exact(long now) {
        // A fresh press stamped by the server in this tick or the two before it; no backdating or client timestamps.
        return blocking(now) && now - press >= 0 && now - press <= EXACT_TICKS && !consumed && (!spent || deflecting(now));
    }

    public void consume(long now) {
        consumed = true;
        spent = true;
        contact = now;
    }
}
