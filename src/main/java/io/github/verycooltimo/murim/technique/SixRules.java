package io.github.verycooltimo.murim.technique;

/** Pure author tables, 24–25.10; ticks are relative to IMPACT unless explicitly named. */
public final class SixRules {
    /** Round 2 author geometry: (caster right, up, forward), measured from the feet.
     * Same quadratic author-table approach as FallingPetalRules.ARCS; no item-model sampling. */
    public record Point(double right, double up, double forward) { }
    public record Arc(Point start, Point control, Point end, boolean thrust) { }
    private static Arc arc(double... p) {
        return new Arc(new Point(p[0], p[1], p[2]), new Point(p[3], p[4], p[5]),
                new Point(p[6], p[7], p[8]), false);
    }
    public static final Arc[][] ARCS = {
        {arc(.9, 2.3, .6, .2, 1.7, 1.9, -.7, .3, 1.6)},
        {new Arc(new Point(0, 1.3, .4), new Point(0, 1.3, 1.5), new Point(0, 1.3, 2.7), true),
         arc(1.2, 1.2, .8, 0, 1.2, 2.2, -1.3, 1.1, .8),
         arc(-1, 2, .8, 0, 1.3, 2.2, 1.1, .4, 1),
         arc(-1.3, 1.1, .8, 0, 1.2, 2.2, 1.2, 1.2, .8),
         arc(0, 2.6, .2, 0, 2, 2, 0, .4, 1.8)},
        {}, // Wave contacts have marks, while the travelling wave owns its silhouette.
        {arc(0, 2.9, .4, 0, 2, 1.6, 0, 1, 1.3)},
        {arc(0, 2.9, .4, 0, 2, 1.6, 0, 1, 1.3),
         arc(1.8, 1.4, .6, 0, 1.4, 3.2, -1.8, 1, .6)}
    };
    public static final double[] BLADE_WIDTH = {0, 0, .10, .16, .34};
    public static final double[] WAVE_HALF_HEIGHT = {0, 0, .45, .6, .9},
            WAVE_RIBBON_WIDTH = {0, 0, .12, .16, .26};
    public static final int ARC_SEGMENTS = 24, HIT_LIFE = 5, SLIT_LIFE = 2,
            BLADE_LIFE = 4, MASTER_BLADE_LIFE = 5;

    public static final String[] FORMS = {"strike", "chain", "wave", "fear", "counter"};
    // Source: docs/design/mvp-fun-1101.md, Top-12 row 3: one new form at each star before the exam.
    public static final int[] UNLOCK = {1, 2, 4, 5, 6};
    public static final int[] WINDUP = {12, 8, 10, 20, 14};
    public static final int[] RECOVERY = {14, 30, 14, 20, 48};
    public static final int[] DISSIPATION = {4, 6, 4, 4, 4};
    public static final int[] COOLDOWN = {50, 90, 70, 140, 160};
    public static final int IMPACT = 2, STAR_CAP = 12, T2_STAR = 4, T3_STAR = 7, T4_STAR = 10;
    public static final double BREAK_DAMAGE = 6;
    public static final int[] CHAIN_SLOW = {0, 6, 12, 18, 25}, CHAIN_FAST = {0, 5, 10, 15, 21};
    public static final double[] CHAIN_REACH = {3.8, 3, 3.2, 3, 3.4};
    public static final double[] CHAIN_ARC = {30, 150, 110, 150, 70};
    public static final double[] CHAIN_SHARE = {.30, .30, .35, .30, .55};
    public static final double STRIKE_REACH = 3.3, STRIKE_ARC = 90, STRIKE_STEP = 1.2;
    public static final int STRIKE_TARGETS = 3, CONE_TARGETS = 24;
    public static final double STRIKE_GROWTH = .08, STRIKE_MASTER = 1.15, CHAIN_GROWTH = .06,
            CHAIN_MASTER = 1.2, CHAIN_STEP = .9, CHAIN_TRAVEL = 3.2;
    public static final double[] WAVE_RANGE = {0, 0, 3.5, 5.5, 10}, WAVE_WIDTH = {0, 0, .8, 1, 1.4},
            WAVE_SPEED = {0, 0, 1, 1, 1.2}, WAVE_SHARE = {0, 0, .8, .9, 1};
    public static final double WAVE_EYE_DROP = .3, WAVE_HEIGHT = 1.2, WAVE_FRONT_DEPTH = .25;
    public static final int FREEZE_TICK = 6, FEAR_SLOW = 30, FEAR_STUN = 14, FEAR_FREEZE = 28,
            FEAR_DARK = 30;
    public static final double FEAR_RANGE = 7, FEAR_ARC = 30, FEAR_CUT_ARC = 40,
            FEAR_GAP = 1.6, FEAR_MASTER_GAP = 1.4, FEAR_SHARE = 1.1, FEAR_MASTER_SHARE = 1.5,
            FREEZE_BONUS = 1.2;
    public static final int COUNTER_WINDOW = 14, COUNTER_EXPIRE = 6, COUNTER_ANSWER = 20,
            COUNTER_VERTICAL = 34, COUNTER_SLASH = 44, COUNTER_HOLD = 16, COUNTER_MASTER_HOLD = 22,
            COUNTER_BLIND = 40, COUNTER_PLAYER_BLIND = 30, GIANT_DELAY = 0, GIANT_END = COUNTER_SLASH + 1;
    public static final int GIANT_GROW = 2, GIANT_SETTLE = 2, GIANT_HOLD = 8, MENACE_STRIKE = 2, GIANT_RETURN = 1;
    public static final int COUNTER_TURN = 10;
    public static final int MENACE_SNAP = 1, MENACE_WINDUP = 3;
    public static final float GIANT_SCALE = 2.6F;
    public static final double FEAR_MASTER_REACH = STRIKE_REACH * GIANT_SCALE;
    public static final double WAVE_TRAIL_DISTANCE = 1.2;
    public static final float WAVE_TRAIL_ALPHA = .5F;
    public static final int GIANT_SAFETY = 10, IMPACT_FRAME_TICKS = 3;
    /** Pure client latch: only a drawn impact mask normally releases the giant. */
    public static final class GiantState {
        private final float contact;
        private boolean impactStarted;
        public GiantState(float contact) { this.contact = contact; }
        public void impactFrameStarted() { impactStarted = true; }
        public float scale(float age) {
            return age < 0 || impactStarted || age >= contact + GIANT_SAFETY ? 1 : GIANT_SCALE;
        }
    }
    /** Fear round 10 (author 31.10 on round 9: "the slowdown fell on the strike itself, not on the wind-up"): snap to the clip's own wind-up
     * (0.5-0.6 s, sword raised with the blade cocked back), creep a little further back-up while the giant holds, then cut through the
     * overhead pose to contact in two ticks. Clip measurement: docs/design/techniques/six-harmonies-forms-spec.md. */
    private static float fearTick(int age, int start) {
        int strike = WINDUP[3] - MENACE_STRIKE;
        float windup = 12F, held = 12.3F;
        if (age < start) return age * windup / start;
        if (age <= strike) return windup + (held - windup) * (age - start) / (strike - start);
        if (age <= WINDUP[3]) return held + (FEAR_REFERENCE_CUT - held) * (age - strike) / MENACE_STRIKE;
        return FEAR_REFERENCE_CUT + age - WINDUP[3];
    }
    /** Counter retains round 5's eased wind-up across the entire hold. */
    private static float menaceTick(int age, int start, int ready, int cut, int hit) {
        int strike = hit - MENACE_STRIKE;
        if (age < start) return age * (ready - 2F) / Math.max(1, start);
        if (age <= strike) {
            float t = (age - start) / (float) (strike - start);
            float eased = t * t * (3 - 2 * t);
            float first = ready == 22 ? 14 : ready - 2F;
            return first + (ready - first) * eased;
        }
        if (age <= hit) return ready + (cut - ready) * (age - strike) / (float) MENACE_STRIKE;
        return cut + age - hit;
    }
    public static float darkAlpha(float age, float hit) {
        return Math.max(0, Math.min(1, age / 3F)) * Math.max(0, Math.min(1, (hit + 3 - age) / 3F));
    }

    public static float hitAlpha(float age) {
        return Math.max(0, Math.min(1, age)) * Math.max(0, Math.min(1, (HIT_LIFE - age) / 2));
    }
    public static final double COUNTER_RANGE = 8, COUNTER_SIDE = 1.8, COUNTER_BEHIND = 2,
            COUNTER_REACH = 3.2, COUNTER_ARC = 100, COUNTER_VERTICAL_ARC = 60,
            COUNTER_SLASH_REACH = 4, COUNTER_SLASH_ARC = 200,
            COUNTER_SHARE = 1.4, COUNTER_VERTICAL_SHARE = 1.3, COUNTER_SLASH_SHARE = 1.5;
    public static final int STRIKE_SLOW = 20, CHAIN_SLOW_TICKS = 12, STRIKE_HOLD = 8,
            STRIKE_MASTER_HOLD = 14, CHAIN_MASTER_HOLD = 16, STEP_TICKS = 2,
            HIT_STOP = 2, MASTER_HIT_STOP = 3, SHAKE_TICKS = 16, AFTERIMAGE_TICKS = 10;
    public static final double STRIKE_PUSH = .25, CHAIN_PUSH = .15, WAVE_PUSH = .3, COUNTER_PUSH = .6,
            SHAKE_RANGE = 8, LIGHT_SHAKE = .25, MASTER_SHAKE = 2;
    public static final double[] COUNTER_ANGLES = {0, 25, -25, 50, -50, 90, -90};
    public static final double COUNTER_MIN_MOVE = .5;
    public static final double WALL_CLEARANCE = .5, FLOOR_CHECK = .6, EPSILON = 1.0E-6;
    public static int tier(int stars) { return stars < T2_STAR ? 1 : stars < T3_STAR ? 2 : stars < T4_STAR ? 3 : 4; }
    public static int total(int form) { return WINDUP[form] + IMPACT + RECOVERY[form] + DISSIPATION[form]; }
    public static int[] chain(int tier) { return (tier == 1 ? CHAIN_SLOW : CHAIN_FAST).clone(); }
    public static double strikePower(int tier) { return (1 + STRIKE_GROWTH * (tier - 1)) * (tier == 4 ? STRIKE_MASTER : 1); }
    public static double chainPower(int tier) { return 1 + CHAIN_GROWTH * (tier - 1); }
    public static final int COUNTER_REFERENCE_TRIGGER = 14, COUNTER_GUARD_END = 13,
            COUNTER_RECOVER = 36, COUNTER_FADE = 6;
    // Retain authored cut keys; the shared phase clock holds the anticipation pose.
    public static final int FEAR_REFERENCE_CUT = 16;
    // Contact offset carried by GIANT; client releases on the drawn impact event.
    public static final int FEAR_GIANT_END = WINDUP[3];
    /** One authored clip follows the server's star/trigger clock through PAL's existing speed modifier. */
    public static float animationTick(int form, int tier, int age, int trigger) {
        if (form == 3) {
            return fearTick(age, FREEZE_TICK + (tier < 4 ? 4 : 0));
        }
        if (form == 1 && tier >= 2) {
            int[] fast = chain(tier), slow = chain(1);
            int previousFrom = 0, previousTo = 0;
            for (int i = 0; i <= fast.length; i++) {
                int from = i == fast.length ? total(form) : WINDUP[form] + fast[i];
                int to = i == slow.length ? total(form) : WINDUP[form] + slow[i];
                if (age <= from) return previousTo + (age - previousFrom) * (to - previousTo) / (float) (from - previousFrom);
                previousFrom = from; previousTo = to;
            }
        }
        if (form == 4) {
            if (trigger < 0) return Math.min(age, COUNTER_GUARD_END);
            int since = age - trigger;
            if (tier < 4 && since > COUNTER_VERTICAL) return 24;
            if (since > COUNTER_SLASH) return Math.min(58, 34
                    + (since - COUNTER_SLASH) * (58 - 34)
                    / (float) Math.max(1, total(form) - trigger - COUNTER_SLASH));
            int start = tier == 4 ? 0 : COUNTER_ANSWER + 4;
            return since < start
                    ? COUNTER_REFERENCE_TRIGGER + Math.max(0, since - COUNTER_TURN) * 6F / (start - COUNTER_TURN)
                    : menaceTick(since, start, 22, 24, COUNTER_VERTICAL);
        }
        return age;
    }
    private SixRules() { }
}
