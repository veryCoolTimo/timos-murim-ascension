package io.github.verycooltimo.murim.dev;

import java.util.List;
import java.util.Set;

/** Strict, pure cue grammar for the air/hand film stand. */
public final class AirhandCapturePlan {
    public record Cue(String action, int tick) { }
    public static List<Cue> parse(String text) {
        var result = new java.util.ArrayList<Cue>();
        for (String part : text.split(",", -1)) {
            String[] pair = part.trim().split("@", -1);
            if (pair.length != 2 || !Set.of("stage", "step_off", "cast", "swing", "hit", "rush", "shoot", "release").contains(pair[0]))
                throw new IllegalArgumentException("Invalid scene cue: " + part);
            int tick = Integer.parseInt(pair[1]);
            if (tick < 0 || tick > 1200) throw new IllegalArgumentException("Invalid scene tick: " + part);
            result.add(new Cue(pair[0], tick));
        }
        return List.copyOf(result);
    }
    private AirhandCapturePlan() { }
}
