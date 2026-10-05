package io.github.verycooltimo.murim.training;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * The player's tempered body (docs/design/27-body-training.md §3). Saved and kept on death: a trained body is the
 * result of play, like cultivation.
 *
 * @param version    format version (0 — read from an older save)
 * @param points     tempering points of all time; the level is {@link BodyRules#level}
 * @param day        Minecraft day of {@code today}
 * @param today      points gained on {@code day} (daily diminishing returns)
 * @param fatigue    0..1, slows gains; meals, sleep and time take it away
 * @param bestClimb  best South Peak climb, ticks; 0 — none yet
 * @param bestTrail  best trail sprint, ticks; 0 — none yet
 * @param climbsToday finished climbs on {@code day} (canon ch. 159: five and more a day)
 */
public record BodyState(int version, double points, long day, double today, double fatigue, int bestClimb, int bestTrail,
                        int climbsToday) {

    public static final int VERSION = 1;
    public static final BodyState NONE = new BodyState(VERSION, 0.0D, 0L, 0.0D, 0.0D, 0, 0, 0);

    public static final Codec<BodyState> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("version", 0).forGetter(BodyState::version),
            Codec.DOUBLE.optionalFieldOf("points", 0.0D).forGetter(BodyState::points),
            Codec.LONG.optionalFieldOf("day", 0L).forGetter(BodyState::day),
            Codec.DOUBLE.optionalFieldOf("today", 0.0D).forGetter(BodyState::today),
            Codec.DOUBLE.optionalFieldOf("fatigue", 0.0D).forGetter(BodyState::fatigue),
            Codec.INT.optionalFieldOf("best_climb", 0).forGetter(BodyState::bestClimb),
            Codec.INT.optionalFieldOf("best_trail", 0).forGetter(BodyState::bestTrail),
            Codec.INT.optionalFieldOf("climbs_today", 0).forGetter(BodyState::climbsToday)
    ).apply(i, BodyState::new));

    public int level() {
        return BodyRules.level(points);
    }

    /** A new day: the daily counters start over. */
    public BodyState onDay(long now) {
        return now == day ? this : new BodyState(VERSION, points, now, 0.0D, fatigue, bestClimb, bestTrail, 0);
    }

    /** Adds a gain already reduced by {@link BodyRules#gain}. */
    public BodyState gained(double gain) {
        return new BodyState(VERSION, points + gain, day, today + gain, fatigue, bestClimb, bestTrail, climbsToday);
    }

    public BodyState withFatigue(double f) {
        return new BodyState(VERSION, points, day, today, Math.max(0.0D, Math.min(1.0D, f)), bestClimb, bestTrail, climbsToday);
    }

    public BodyState withPoints(double p) {
        return new BodyState(VERSION, Math.max(0.0D, p), day, today, fatigue, bestClimb, bestTrail, climbsToday);
    }

    public BodyState climbed(int ticks) {
        int best = bestClimb == 0 ? ticks : Math.min(bestClimb, ticks);
        return new BodyState(VERSION, points, day, today, fatigue, best, bestTrail, climbsToday + 1);
    }

    public BodyState sprinted(int ticks) {
        int best = bestTrail == 0 ? ticks : Math.min(bestTrail, ticks);
        return new BodyState(VERSION, points, day, today, fatigue, bestClimb, best, climbsToday);
    }
}
