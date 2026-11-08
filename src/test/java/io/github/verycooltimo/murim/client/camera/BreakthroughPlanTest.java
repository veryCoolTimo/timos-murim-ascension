package io.github.verycooltimo.murim.client.camera;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.verycooltimo.murim.cultivation.Realm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shipped camera data file: every rank has a sequence, the shots stay sane, motion 0 holds still. */
class BreakthroughPlanTest {
    @Test void creationPushBurstPullAndTitleFollowFinaleClock() {
        var start = new ShotPose(30, 18, 1.3F, .95F, 0, 1);
        var steps=BreakthroughPlan.creationSteps(start);
        float total=io.github.verycooltimo.murim.client.vfx.preset.DantianFlowMath.SEED_TICKS;
        double burst=io.github.verycooltimo.murim.client.vfx.preset.DantianFlowMath.BURST_START;
        double grow=io.github.verycooltimo.murim.client.vfx.preset.DantianFlowMath.MERIDIAN_START;
        double title=io.github.verycooltimo.murim.client.vfx.preset.DantianFlowMath.TITLE_START;
        assertEquals(start, BreakthroughPlan.poseAt(steps, 0, total, 1, 1));
        assertPoseNear(start, BreakthroughPlan.poseAt(steps, 1.5F, total, 1, 1));
        assertPoseNear(start, BreakthroughPlan.poseAt(steps, 3, total, 1, 1));
        var expectedClose = new ShotPose(0, 8, .85F, .48F, 0, 1);
        var expectedFront = new ShotPose(0, 12, 2.6F, .68F, 0, 1);
        assertEquals(expectedClose, steps.getFirst().shot().at(1));
        assertEquals(expectedClose, BreakthroughPlan.poseAt(steps, (float) (burst * 20), total, 1, 1));
        assertEquals(expectedClose, steps.get(1).shot().at(0));
        assertEquals(expectedClose, steps.get(2).shot().at(0));
        assertEquals(expectedFront, steps.get(2).shot().at(1));
        assertEquals(expectedFront, steps.get(3).shot().at(0));
        assertEquals(expectedFront, steps.get(3).shot().at(1));
        assertEquals(expectedFront, steps.get(2).shot().restPose());
        for (var step : steps) {
            assertEquals(0, step.blend());
            assertEquals(false, step.flip());
            assertEquals(false, step.reverse());
            assertEquals(false, step.shot().mirror());
        }
        assertEquals("dantian_push",BreakthroughPlan.cue(steps,4,total).step().shot().name());
        assertEquals("dantian_burst",BreakthroughPlan.cue(steps,(float)(burst*20+.01),total).step().shot().name());
        assertEquals("dantian_pull",BreakthroughPlan.cue(steps,(float)(grow*20+.01),total).step().shot().name());
        assertEquals("dantian_title",BreakthroughPlan.cue(steps,(float)(title*20+.01),total).step().shot().name());
        var close=BreakthroughPlan.poseAt(steps,(float)(burst*20+.01),total,1,1);
        var front=BreakthroughPlan.poseAt(steps,(float)(title*20+.01),total,1,1);
        assertTrue(close.distance()<front.distance());
        assertEquals(0,front.azimuth());
        assertEquals(front,BreakthroughPlan.poseAt(steps,total,total,1,1));
        assertEquals(BreakthroughPlan.poseAt(steps,4,total,1,0),
                BreakthroughPlan.poseAt(steps,8,total,1,0));
    }


    private static void assertPoseNear(ShotPose expected, ShotPose actual) {
        float[] a = expected.channels();
        float[] b = actual.channels();
        for (int i = 0; i < a.length; i++) {
            assertEquals(a[i], b[i], 1.0E-5F);
        }
    }

    private static BreakthroughPlan plan;
    private static BreakthroughPlan samples;

    @BeforeAll
    static void load() throws Exception {
        try (Reader reader = new InputStreamReader(
                BreakthroughPlanTest.class.getResourceAsStream("/assets/murim/camera/breakthrough.json"), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            plan = BreakthroughPlan.parse(root);
            JsonObject fixture = root.deepCopy();
            JsonObject named = fixture.getAsJsonObject("plans");
            for (String key : List.of("4a", "4b", "4c", "4d", "4e", "4f", "4g")) {
                named.add(key, JsonParser.parseString("[{\"shot\":\"peak_in\",\"share\":0.3},{\"shot\":\"peak_wide\",\"share\":0.42,\"blend\":30},{\"shot\":\"peak_out\",\"share\":0.28,\"blend\":30}]"));
            }
            samples = BreakthroughPlan.parse(fixture);
        }
    }

    private static List<String> names(int rank) {
        List<String> out = new ArrayList<>();
        for (BreakthroughPlan.Step s : plan.steps(rank)) {
            out.add(s.shot().name());
        }
        return out;
    }

    /** A test-owned sequence: shipped target plans are deliberately all static. */
    private static List<BreakthroughPlan.Step> sample() {
        return plan.sequence("closeup:0.25,orbit:0.4,crane:0.35");
    }

    @Test
    @DisplayName("All four targets resolve to the single static lab shot")
    void defaultSequences() {
        for (int target = Realm.THIRD; target <= Realm.PEAK; target++) {
            assertEquals(List.of("bt_wide"), names(target));
            assertEquals(1.0F, plan.steps(target).getFirst().share());
        }
        assertEquals(13, plan.shots().size(), "capture stand shot library is preserved");
        assertEquals(List.of(13, 1, 2, 3, 4, 6, 7, 8, 10, 11, 12, 9, 5), plan.shots().values().stream().map(ShotTrack::id).toList());
    }

    @Test
    @DisplayName("Named sample plans: close, wide, close, joined by glides and no hard cut")
    void peakVariants() {
        for (String key : List.of("4a", "4b", "4c", "4d", "4e", "4f", "4g")) {
            List<BreakthroughPlan.Step> steps = samples.named(key);
            assertEquals(3, steps.size(), key);
            assertEquals("peak_in", steps.get(0).shot().name(), key);
            assertTrue(steps.get(1).shot().name().startsWith("peak_wide"), key);
            assertEquals("peak_out", steps.get(2).shot().name(), key);
            assertTrue(steps.get(1).blend() > 0 && steps.get(2).blend() > 0, key + ": glides, not cuts");
        }
        assertEquals(null, plan.named("4z"));
    }

    @Test
    @DisplayName("A scene tick finds its step: start, the cut, the end, and after the end the last step holds")
    void cues() {
        List<BreakthroughPlan.Step> steps = sample();
        float total = Realm.breakthroughTicks(Realm.FIRST);
        assertEquals(0, BreakthroughPlan.cue(steps, 0.0F, total).index());
        assertEquals(0, BreakthroughPlan.cue(steps, total * 0.24F, total).index());
        assertEquals(1, BreakthroughPlan.cue(steps, total * 0.25F, total).index());
        BreakthroughPlan.Cue end = BreakthroughPlan.cue(steps, total + 500.0F, total);
        assertEquals(2, end.index());
        assertEquals(1.0F, end.local(), 1.0E-6F, "holds the last pose through the aura release");
        assertEquals(0.5F, BreakthroughPlan.cue(steps, total * 0.45F, total).local(), 1.0E-4F);
    }

    @Test
    @DisplayName("Every pose of every rank stays sane: no closer than 0.5 blocks, no vertical looks, roll within 8 degrees")
    void sanePoses() {
        for (int rank = Realm.THIRD; rank <= Realm.PEAK; rank++) {
            float total = Realm.breakthroughTicks(rank);
            for (int side : new int[] {1, -1}) {
                for (int i = 0; i <= 120; i++) {
                    ShotPose p = BreakthroughPlan.poseAt(plan.steps(rank), total * i / 120.0F, total, side, 1.0F);
                    String where = "rank " + rank + " tick " + i + " " + p;
                    assertTrue(p.distance() >= 0.5F && p.distance() <= 7.0F, where);
                    assertTrue(p.pitch() >= -40.0F && p.pitch() <= 70.0F, where);
                    assertTrue(Math.abs(p.roll()) <= 8.0F, where);
                    assertTrue(p.focus() >= 0.3F && p.focus() <= 1.4F, where);
                    assertTrue(p.fov() >= 0.6F && p.fov() <= 1.2F, where);
                }
            }
        }
    }

    @Test
    @DisplayName("Motion 0 (shake slider at 0): inside a shot the camera does not move at all; the cut stays")
    void reducedMotionHolds() {
        for (int rank = Realm.THIRD; rank <= Realm.PEAK; rank++) {
            List<BreakthroughPlan.Step> steps = sample();
            float total = Realm.breakthroughTicks(rank);
            ShotPose[] firstOfStep = new ShotPose[steps.size()];
            for (int i = 0; i <= 200; i++) {
                float tick = total * i / 200.0F;
                BreakthroughPlan.Cue cue = BreakthroughPlan.cue(steps, tick, total);
                ShotPose p = BreakthroughPlan.poseAt(steps, tick, total, 1, 0.0F);
                if (firstOfStep[cue.index()] == null) {
                    firstOfStep[cue.index()] = p;
                }
                assertEquals(firstOfStep[cue.index()], p, "rank " + rank + " step " + cue.index() + " moved at tick " + tick);
            }
            if (steps.size() > 1) {
                assertTrue(!firstOfStep[0].equals(firstOfStep[1]), "the cut between shots remains");
            }
        }
    }

    @Test
    @DisplayName("Half motion moves half as far: between the rest pose and the authored pose")
    void halfMotion() {
        BreakthroughPlan.Step orbit = sample().get(1);
        ShotPose full = BreakthroughPlan.shotPose(orbit, 1.0F, 1, 1.0F);
        ShotPose rest = orbit.shot().restPose();
        ShotPose half = BreakthroughPlan.shotPose(orbit, 1.0F, 1, 0.5F);
        assertEquals((full.azimuth() + rest.azimuth()) / 2.0F, half.azimuth(), 1.0E-3F);
    }

    @Test
    @DisplayName("The scene chooses a side: mirrored shots turn the other way, a flip takes the opposite side")
    void sides() {
        BreakthroughPlan.Step orbit = sample().get(1);
        ShotPose right = BreakthroughPlan.shotPose(orbit, 1.0F, 1, 1.0F);
        ShotPose left = BreakthroughPlan.shotPose(orbit, 1.0F, -1, 1.0F);
        assertEquals(-right.azimuth(), left.azimuth(), 1.0E-5F);
        BreakthroughPlan.Step flipped = new BreakthroughPlan.Step(orbit.shot(), 1.0F, 0, true, false);
        assertEquals(left.azimuth(), BreakthroughPlan.shotPose(flipped, 1.0F, 1, 1.0F).azimuth(), 1.0E-5F);
    }

    @Test
    @DisplayName("A reversed orbit starts where the orbit ends and ends where it starts: it can lead into the crane without a cut")
    void reversed() {
        ShotTrack orbit = plan.shots().get("orbit");
        ShotTrack crane = plan.shots().get("crane");
        BreakthroughPlan.Step back = new BreakthroughPlan.Step(orbit, 1.0F, 0, false, true);
        assertEquals(orbit.at(1.0F), BreakthroughPlan.shotPose(back, 0.0F, 1, 1.0F));
        assertEquals(orbit.at(0.0F), BreakthroughPlan.shotPose(back, 1.0F, 1, 1.0F));
        ShotPose orbitEnd = BreakthroughPlan.shotPose(back, 1.0F, 1, 1.0F);
        ShotPose craneStart = crane.at(0.0F);
        assertEquals(craneStart.azimuth(), orbitEnd.azimuth(), 8.0F, "azimuth carries over");
        assertEquals(craneStart.distance(), orbitEnd.distance(), 0.3F, "distance carries over");
        assertEquals(craneStart.pitch(), orbitEnd.pitch(), 4.0F, "pitch carries over");
        assertEquals(craneStart.focus(), orbitEnd.focus(), 0.1F, "focus carries over");
    }

    @Test
    @DisplayName("A glide blends from the end of the previous shot; a cut (blend 0) does not")
    void blend() {
        ShotTrack a = plan.shots().get("closeup");
        ShotTrack b = plan.shots().get("orbit");
        List<BreakthroughPlan.Step> cut = List.of(new BreakthroughPlan.Step(a, 1.0F, 0, false, false), new BreakthroughPlan.Step(b, 1.0F, 0, false, false));
        List<BreakthroughPlan.Step> glide = List.of(new BreakthroughPlan.Step(a, 1.0F, 0, false, false), new BreakthroughPlan.Step(b, 1.0F, 20, false, false));
        float total = 200.0F;
        ShotPose endOfA = BreakthroughPlan.shotPose(cut.get(0), 1.0F, 1, 1.0F);
        assertEquals(endOfA.distance(), BreakthroughPlan.poseAt(glide, 100.0F, total, 1, 1.0F).distance(), 1.0E-4F,
                "the glide starts where the previous shot ended");
        assertEquals(b.at(0.0F).distance(), BreakthroughPlan.poseAt(cut, 100.0F, total, 1, 1.0F).distance(), 1.0E-4F);
        assertEquals(b.at(0.2F).distance(), BreakthroughPlan.poseAt(glide, 120.0F, total, 1, 1.0F).distance(), 1.0E-3F,
                "after the glide it is on the shot");
    }

    @Test
    @DisplayName("Capture stand lists: numbers, names, shares")
    void sequenceSpec() {
        List<BreakthroughPlan.Step> s = plan.sequence("1, 3");
        assertEquals(List.of("closeup", "shoulder"), s.stream().map(x -> x.shot().name()).toList());
        List<BreakthroughPlan.Step> t = plan.sequence("orbit:2,crane:1");
        assertEquals(2.0F, t.get(0).share());
        assertEquals("crane", t.get(1).shot().name());
        assertThrows(IllegalArgumentException.class, () -> plan.sequence("nonsense"));
        assertThrows(IllegalArgumentException.class, () -> plan.sequence(" , "));
    }

    @Test
    @DisplayName("Broken data is refused with a message, the fallback has one still shot for every rank")
    void brokenData() {
        JsonObject bad = JsonParser.parseString("{\"shots\":{\"a\":{\"keys\":[{\"t\":0}]}},\"plans\":{\"1\":[{\"shot\":\"b\"}]}}").getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> BreakthroughPlan.parse(bad));
        BreakthroughPlan fallback = BreakthroughPlan.fallback();
        for (int rank = 1; rank <= 4; rank++) {
            assertEquals(ShotPose.FRONT, BreakthroughPlan.poseAt(fallback.steps(rank), 100.0F, 240.0F, 1, 1.0F));
        }
    }
}
