package io.github.verycooltimo.murim.cultivation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BtTimelineTest {
    @Test void allTargetsFollowLabContract() {
        int[] scenes = {216,304,304,510}, tails = {104,96,96,170};
        double[] blows = {10.8,15.2,15.2,25.5}, ends = {16,20,20,34};
        for (int i=0;i<4;i++) {
            var t=BtTimeline.of(i+1);
            assertEquals(scenes[i],t.sceneTicks()); assertEquals(tails[i],t.releaseTicks());
            assertEquals(blows[i],t.blow()); assertEquals(ends[i],t.end());
            assertEquals(scenes[i],Realm.breakthroughTicks(i+1));
            assertEquals(tails[i],Realm.releaseTicks(i+1));
        }
        assertEquals(14.6,BtTimeline.of(2).freeze());
    }
}
