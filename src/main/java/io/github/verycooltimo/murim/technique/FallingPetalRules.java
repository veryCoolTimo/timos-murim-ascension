package io.github.verycooltimo.murim.technique;

import net.minecraft.world.phys.Vec3;

/**
 * «Меч Падающего Цветка» (낙화검), v3 by the author 09.10: the basic technique plus ONE move.
 * Stance and a short gather (ref1), then three blinks: the master vanishes in a burst and
 * reappears BEHIND the target, each time from another side (rear, rear-left, rear-right of the
 * target's current facing), cuts once in one tick and holds while the target turns toward him;
 * the third cut ends in the ref7 pose (free hand on the target's head), then back to idle.
 * Numbers shared by the server (teleports, damage) and the client (trails, bursts).
 *
 * <p>Ticks {@code since} count from IMPACT (technique tick 24, after the 24-tick stance).
 */
public final class FallingPetalRules {

    /**
     * Stance and anticipation (WINDUP in the json), v5 1.2 s: settle low, breathe, draw back, a still
     * beat, then the first blink — about 3x the blink-and-cut it prepares (35-animation-lessons.md).
     */
    public static final int STANCE = 24;
    /** Last tick of the timeline from IMPACT (the technique is 80 ticks / 4.0 s, since 0..55). */
    public static final int END = 55;

    /** Vanish ticks: the dash pose and the burst at the old spot. */
    public static final int[] VANISH = {6, 20, 34};
    /** The server moves the master behind the target one tick after the vanish. */
    public static final int APPEAR_DELAY = 1;
    /** Contact ticks of the three cuts (the arm snaps hit - 1 -> hit). */
    public static final int[] STRIKES = {10, 24, 40};
    /** Blade trail before contact (ticks); the arm itself moves in one tick. */
    public static final int CUT = 2;
    /** Strike pose held after contact (ticks). */
    public static final int HOLD = 4;
    /** After a cut the target turns toward the master over these ticks (from hit + 2). */
    public static final int TURN_TICKS = 6;
    /** Degrees per tick the target turns. */
    public static final float TURN_SPEED = 32.0F;

    /** Damage shares per cut (sum 3.0). */
    public static final double[] DAMAGE = {0.8D, 0.9D, 1.3D};

    /** Targeting and follow range. */
    public static final double RANGE = 16.0D;
    /** Blade reach from the master's centre to the target's hitbox edge. */
    public static final double REACH = 2.6D;
    /** Distance behind the target's hitbox where the master reappears (author: not hugging it). */
    public static final double BEHIND = 2.4D;
    /** The cut closes the gap: a lunge slide starting this many ticks before contact... */
    public static final int LUNGE_STEP_TICKS = 2;
    /** ...that stops this far from the target's hitbox edge. */
    public static final double STRIKE_GAP = 1.0D;
    /** Side of each reappearance, degrees from the target's back (+ = target's left). */
    public static final double[] SIDES = {0.0D, 55.0D, -55.0D};

    /** No target: short lunge forward and one cut. */
    public static final double LUNGE = 3.0D;
    public static final int LUNGE_TICKS = 8;
    public static final int LUNGE_CUT = 8;

    /**
     * Reappear point {@code k}: behind the target ({@code t} its feet, {@code facing} its flat look,
     * {@code half} half its hitbox width), turned by {@link #SIDES}[k] around it.
     */
    public static Vec3 behind(int k, Vec3 t, Vec3 facing, double half) {
        return behind(t, facing, half, SIDES[k], BEHIND);
    }

    public static Vec3 behind(Vec3 t, Vec3 facing, double half, double sideDeg, double gap) {
        Vec3 back = facing.scale(-1.0D);
        double a = Math.toRadians(sideDeg);
        // Rotate the back vector toward the target's left (left(f) = (f.z, 0, -f.x)).
        Vec3 l = left(facing);
        Vec3 dir = back.scale(Math.cos(a)).add(l.scale(Math.sin(a)));
        return t.add(dir.scale(half + gap));
    }

    public static Vec3 left(Vec3 f) {
        return new Vec3(f.z, 0.0D, -f.x);
    }

    /** Damage multiplier by executionTier: executionTier 0 is a student's cut, executionTier 7 the full one. */
    public static double power(int executionTier) {
        return 0.55D + 0.45D * Math.min(7, Math.max(0, executionTier)) / 7.0D;
    }

    /** Cut whose contact is at tick {@code since}, or −1. */
    public static int strikeAt(int since) {
        for (int i = 0; i < STRIKES.length; i++) {
            if (STRIKES[i] == since) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Blade tip path of cut {@code k} in the master's frame: {θ0, θ1 (degrees, + = left),
     * h0, h1 (height above the waist), radius}. Rear: high right down to low left; rear-left:
     * rising from low left to high right (codex 09.10: three distinct cuts); finale: across and up-out over both (ref7).
     */
    public static final double[][] ARCS = {
            {-70.0D, 65.0D, 1.15D, -0.5D, 1.45D},
            {60.0D, -70.0D, -0.4D, 1.3D, 1.5D},
            {-60.0D, 70.0D, 1.1D, 1.6D, 1.4D}};

    /** Tip of the blade: {@code base} the master's waist, {@code f} toward the target, p in 0..1. */
    public static Vec3 tip(Vec3 base, Vec3 f, double[] arc, double p) {
        double e = p <= 0.0D ? 0.0D : p >= 1.0D ? 1.0D + (p - 1.0D) * 0.6D : 1.0D - Math.pow(1.0D - p, 2.2D);
        double th = Math.toRadians(arc[0] + (arc[1] - arc[0]) * e);
        Vec3 s = left(f);
        double h = arc[2] + (arc[3] - arc[2]) * Math.min(1.2D, e);
        double r = arc[4] * (0.82D + 0.18D * Math.sin(Math.PI * Math.min(1.0D, e)));
        return base.add(f.scale(Math.cos(th) * r)).add(s.scale(Math.sin(th) * r)).add(0.0D, h, 0.0D);
    }

    private FallingPetalRules() {
    }
}
