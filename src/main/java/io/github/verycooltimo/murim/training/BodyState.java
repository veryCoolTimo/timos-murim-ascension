package io.github.verycooltimo.murim.training;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.HashMap;
import java.util.Map;

/**
 * The player's tempered body (docs/design/27-body-training.md §3). Saved and kept on death: a trained body is the
 * result of play, like cultivation.
 *
 * <p>Format 2 (25.10): the body grows from the vanilla statistics. Format 1 had {@code today} and {@code fatigue} (squat
 * sets); they are not read any more and an old save loads with {@code seeded = false}, which makes the system look at
 * the player once and credit what he has already done ({@link BodyActivity#seed}).
 *
 * @param version     format version (0 — read from an older save)
 * @param points      tempering points of all time; the level is {@link BodyRules#level}
 * @param day         Minecraft day of {@code raw}
 * @param raw         work done on {@code day}, per {@link BodySource#id()}, before the daily limit (the limit is applied to the sum)
 * @param seen        last statistic value looked at, per {@link BodyProbe#key()}: the next look credits the difference
 * @param seeded      the existing statistics were credited once (the initial grant); false until the first look
 * @param bestClimb   best South Peak climb, ticks; 0 — none yet
 * @param bestTrail   best trail sprint, ticks; 0 — none yet
 * @param climbsToday finished climbs on {@code day} (canon ch. 159: five and more a day)
 */
public record BodyState(int version, double points, long day, Map<String, Double> raw, Map<String, Long> seen, boolean seeded,
                        int bestClimb, int bestTrail, int climbsToday) {

    public static final int VERSION = 2;
    public static final BodyState NONE = new BodyState(VERSION, 0.0D, 0L, Map.of(), Map.of(), false, 0, 0, 0);

    public static final Codec<BodyState> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("version", 0).forGetter(BodyState::version),
            Codec.DOUBLE.optionalFieldOf("points", 0.0D).forGetter(BodyState::points),
            Codec.LONG.optionalFieldOf("day", 0L).forGetter(BodyState::day),
            Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).optionalFieldOf("raw", Map.of()).forGetter(BodyState::raw),
            Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("seen", Map.of()).forGetter(BodyState::seen),
            Codec.BOOL.optionalFieldOf("seeded", false).forGetter(BodyState::seeded),
            Codec.INT.optionalFieldOf("best_climb", 0).forGetter(BodyState::bestClimb),
            Codec.INT.optionalFieldOf("best_trail", 0).forGetter(BodyState::bestTrail),
            Codec.INT.optionalFieldOf("climbs_today", 0).forGetter(BodyState::climbsToday)
    ).apply(i, BodyState::new));

    public int level() {
        return BodyRules.level(points);
    }

    /** Today's raw work of one kind. */
    public double rawOf(BodySource source) {
        return raw.getOrDefault(source.id(), 0.0D);
    }

    /** Points today's work has given so far, over all kinds (the daily limits applied). */
    public double today() {
        double sum = 0.0D;
        for (BodySource s : BodySource.values()) {
            sum += s.dayTotal(rawOf(s));
        }
        return sum;
    }

    /** A new day: the daily counters start over. */
    public BodyState onDay(long now) {
        return now == day ? this : new BodyState(VERSION, points, now, Map.of(), seen, seeded, bestClimb, bestTrail, 0);
    }

    /** {@code add} raw work of one kind: the raw counter grows, the points grow by what the daily limit lets through. */
    public BodyState credited(BodySource source, double add) {
        if (add <= 0.0D) {
            return this;
        }
        double before = rawOf(source);
        double gain = source.dayTotal(before + add) - source.dayTotal(before);
        Map<String, Double> next = new HashMap<>(raw);
        next.put(source.id(), before + add);
        return new BodyState(VERSION, points + gain, day, Map.copyOf(next), seen, seeded, bestClimb, bestTrail, climbsToday);
    }

    public BodyState withSeen(Map<String, Long> now, boolean markSeeded) {
        return new BodyState(VERSION, points, day, raw, Map.copyOf(now), markSeeded || seeded, bestClimb, bestTrail, climbsToday);
    }

    public BodyState withPoints(double p) {
        return new BodyState(VERSION, Math.max(0.0D, p), day, raw, seen, seeded, bestClimb, bestTrail, climbsToday);
    }

    public BodyState climbed(int ticks) {
        int best = bestClimb == 0 ? ticks : Math.min(bestClimb, ticks);
        return new BodyState(VERSION, points, day, raw, seen, seeded, best, bestTrail, climbsToday + 1);
    }

    public BodyState sprinted(int ticks) {
        int best = bestTrail == 0 ? ticks : Math.min(bestTrail, ticks);
        return new BodyState(VERSION, points, day, raw, seen, seeded, bestClimb, best, climbsToday);
    }
}
