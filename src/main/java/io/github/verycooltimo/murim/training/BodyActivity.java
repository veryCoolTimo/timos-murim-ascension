package io.github.verycooltimo.murim.training;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * How ordinary play becomes body points (docs/design/27-body-training.md §2): the pure half. {@link VanillaBody} reads
 * the vanilla statistics into a {@code Map<BodyProbe, Long>}; this class turns two such readings into points, with the
 * daily limit of each {@link BodySource}. No Minecraft types: {@code BodyActivityTest} drives it with plain numbers.
 */
public final class BodyActivity {

    private BodyActivity() {
    }

    /** What the plain rates would pay for the statistics as they stand (no daily limits): the base of the initial grant. */
    public static double lifetimeRaw(Map<BodyProbe, Long> now) {
        double sum = 0.0D;
        for (BodyProbe p : BodyProbe.values()) {
            sum += Math.max(0L, now.getOrDefault(p, 0L)) * p.rate();
        }
        return sum;
    }

    /**
     * The first look at a player: credits the statistics he already has ({@link BodyRules#initialGrant}), takes the
     * reading as the baseline and marks the state seeded. Points he has already (the old squat ladder) are kept, not
     * added to: the grant never lowers and never stacks above its cap.
     */
    public static BodyState seed(BodyState state, long day, Map<BodyProbe, Long> now) {
        BodyState s = state.onDay(day);
        double grant = BodyRules.initialGrant(lifetimeRaw(now));
        return s.withPoints(Math.max(s.points(), grant)).withSeen(keyed(now), true);
    }

    /**
     * A later look: each statistic's growth since the last look is worth its rate; the work of each kind is added to
     * today's counter and pays through the daily limit. A statistic that went DOWN (the stats file was reset) only moves
     * the baseline. {@code pays == false} (creative, spectator) moves the baseline and credits nothing.
     */
    public static BodyState advance(BodyState state, long day, Map<BodyProbe, Long> now, boolean pays) {
        BodyState s = state.onDay(day);
        Map<BodySource, Double> add = new EnumMap<>(BodySource.class);
        for (BodyProbe p : BodyProbe.values()) {
            long cur = Math.max(0L, now.getOrDefault(p, 0L));
            Long prev = s.seen().get(p.key());
            if (pays && prev != null && cur > prev) {
                add.merge(p.source(), (cur - prev) * p.rate(), Double::sum);
            }
        }
        for (Map.Entry<BodySource, Double> e : add.entrySet()) {
            s = s.credited(e.getKey(), e.getValue());
        }
        return s.withSeen(keyed(now), false);
    }

    private static Map<String, Long> keyed(Map<BodyProbe, Long> now) {
        Map<String, Long> out = new HashMap<>();
        for (BodyProbe p : BodyProbe.values()) {
            out.put(p.key(), Math.max(0L, now.getOrDefault(p, 0L)));
        }
        return out;
    }
}
