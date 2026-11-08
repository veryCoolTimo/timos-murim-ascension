package io.github.verycooltimo.murim.dev;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AirhandCapturePlanTest {
    @Test void preservesSimultaneousCuesAndImmutableOrder() {
        var cues = AirhandCapturePlan.parse("stage@0,rush@10,cast@10,release@115");
        assertEquals(java.util.List.of(new AirhandCapturePlan.Cue("stage", 0),
                new AirhandCapturePlan.Cue("rush", 10), new AirhandCapturePlan.Cue("cast", 10),
                new AirhandCapturePlan.Cue("release", 115)), cues);
        assertThrows(UnsupportedOperationException.class, cues::clear);
    }
    @Test void rejectsMalformedCuesBeforeFilming() {
        for (String script : new String[]{"", "cast", "cast@", "cast@-1", "cast@1201", "cast@2147483648",
                "typo@1", "cast@1@2", "cast@1,", "@1"})
            assertThrows(IllegalArgumentException.class, () -> AirhandCapturePlan.parse(script), script);
    }
}
