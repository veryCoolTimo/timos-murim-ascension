package io.github.verycooltimo.murim.technique;

/**
 * Pure mechanics for Plum Blossom Crush; all times are absolute cast ticks.
 * Source: docs/design/techniques/twenty-four-plum-crush-spec.md (10 Nov 2026).
 * No world state or Minecraft API: the server executor must own contact and payment.
 */
public final class CrushRules {
    public static final int STANCE = 0;
    public static final int VORTEX = 8;
    public static final int CHARGE = 30;
    public static final int CRACK = 44;
    public static final int AIM = 50;
    public static final int STEP = 50;
    public static final int PLANT = 52;
    public static final int STRIKE = 54;
    public static final int HOLD = 54;
    public static final int STUMBLE = 66;
    public static final int RECOVER = 80;
    public static final int END = 100;
    public static final int PROJECTILE_END = 58;
    public static final int RECOIL = 14;
    public static final int MOB_STUN = 20;
    public static final int PLAYER_STUN = 12;
    public static final int BOSS_STUN = 10;
    public static final int COOLDOWN = 900;
    public static final int HIT_STOP = 4;
    public static final double INTERRUPT_DAMAGE = 8;
    public static final double AIM_RANGE = 6;
    public static final double AIM_CONE = 40;
    public static final double LUNGE = 2.5;
    public static final double RANGE = 4.5;
    public static final double CONE = 70;
    public static final double PROJECTILE_CONE = 90;
    public static final double DMG_PRIMARY = 10;
    public static final double DMG_AREA = 4;
    public static final double PUSH_PRIMARY = 1.4;
    public static final double PUSH_AREA = .8;
    public static final double PUSH_LIFT = .3;
    public static final double PUSH_MAX = 1.6;

    /** Same accepted execution-tier power curve as River; stars never gate mechanics. */
    public static double power(int executionTier) {
        return RiverRules.power(executionTier);
    }

    /** Area share of hand damage: 4H at contact, 2H at the outer edge. */
    public static double areaShare(double distance) {
        double d = Math.max(0, Math.min(RANGE, distance));
        return DMG_AREA * (1 - .5 * d / RANGE);
    }

    /** Extra payment is measured against the full circulating pool, after normal cost. */
    public static double qiMultiplier(double extraSpent, double fullPool, boolean free) {
        if (free) return 1;
        double share = fullPool > 0 ? Math.max(0, Math.min(1, extraSpent / fullPool)) : 0;
        return .8 + .45 * share;
    }

    public static boolean projectileWindow(int tick) {
        return tick >= AIM && tick <= PROJECTILE_END;
    }

    public static boolean interrupts(int tick, double damage) {
        return tick >= STANCE && tick < AIM && damage >= INTERRUPT_DAMAGE;
    }

    private CrushRules() {}
}
