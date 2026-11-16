package io.github.verycooltimo.murim.client.flow;

import io.github.verycooltimo.murim.cultivation.PillKind;
import io.github.verycooltimo.murim.cultivation.flow.FlowCharacter;
import io.github.verycooltimo.murim.cultivation.flow.FlowGame;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pure maths of the high pill meridian: the way is one continuous line, stays off the neck, head and legs; the drawn s and the node aperture are continuous. */
class FlowMeridianTest {
    /** A seated nominal pose in torso pixels x 0.0625 blocks: torso 8 wide, 12 tall; arms hang by the sides, hands a little forward. */
    private static FlowMeridian.Anchors nominal(double yaw, double pitch) {
        double px = .0625;
        Vec3[] k = new Vec3[10];
        Vec3 o = new Vec3(0, 0, 0), x = new Vec3(4 * px, 0, 0), y = new Vec3(0, 12 * px, 0), n = new Vec3(0, 0, -px);
        // right arm (player's right = screen left, a < 0): top at shoulder, bottom 12 px lower and 3 px forward
        Vec3 rTop = new Vec3(-6 * px, 12 * px, 0), rBot = new Vec3(-6 * px, .5 * px, -3 * px), rLat = rTop.add(px, 0, 0);
        Vec3 lTop = new Vec3(6 * px, 12 * px, 0), lBot = new Vec3(6 * px, .5 * px, -3 * px), lLat = lTop.add(px, 0, 0);
        Vec3[] all = {o, x, y, n, rTop, rBot, rLat, lTop, lBot, lLat};
        for (int i = 0; i < all.length; i++) k[i] = turn(all[i], yaw, pitch);
        return FlowMeridian.Anchors.of(k[0], k[1], k[2], k[3], k[4], k[5], k[6], k[7], k[8], k[9]);
    }

    private static Vec3 turn(Vec3 v, double yaw, double pitch) {
        return v.xRot((float) Math.toRadians(pitch)).yRot((float) Math.toRadians(yaw)).add(10, 64, -3);
    }

    private static double across(FlowMeridian.Anchors a, Vec3 p) { return p.subtract(a.body().o()).dot(a.body().right()) / a.body().px(); }
    private static double up(FlowMeridian.Anchors a, Vec3 p) { return p.subtract(a.body().o()).dot(a.body().up()) / a.body().px(); }

    @Test
    void pathIsContinuousWithoutKinks() {
        for (double[] pose : new double[][] {{0, 0}, {35, 0}, {0, 12}, {200, -10}}) {
            FlowMeridian.Anchors a = nominal(pose[0], pose[1]);
            FlowMeridian.Path path = FlowMeridian.build(a);
            double mean = path.length / (FlowMeridian.SAMPLES - 1);
            for (int i = 0; i < FlowMeridian.SAMPLES - 1; i++) {
                double step = path.p[i].distanceTo(path.p[i + 1]);
                assertTrue(step < mean * 1.6 && step > mean * .4, "uneven spacing at " + i + ": " + step + " vs " + mean);
                double cos = path.t[i].dot(path.t[i + 1]);
                boolean palm = false;
                for (int tn : path.turns) palm |= Math.abs(i - tn) <= 2;
                // the only sharp turns are the two palms: the way goes down the arm's one channel and comes back up it
                if (!palm) assertTrue(cos > .7, "kink of " + Math.toDegrees(Math.acos(Math.min(1, cos))) + " deg at " + i);
            }
            assertTrue(path.length > 2.8 && path.length < 6, "length " + path.length);
            assertEquals(0, path.p[0].distanceTo(path.p[FlowMeridian.SAMPLES - 1]), .01, "the way is a loop back to the dantian");
            for (double u = 0; u < 1; u += .0005) assertTrue(path.at(u).distanceTo(path.at(u + .0005)) < mean * 2.2);
        }
    }

    @Test
    void anArmIsOneChannelTheWayBackRetracesTheWayDown() {
        FlowMeridian.Anchors a = nominal(20, 5);
        FlowMeridian.Path path = FlowMeridian.build(a);
        int pairs = 0;
        for (int i = 0; i < FlowMeridian.SAMPLES; i++) {
            if (path.mate[i] < 0) continue;
            assertEquals(0, path.p[i].distanceTo(path.p[path.mate[i]]), .012, "the two passes of an arm lie on one line");
            boolean nearTurn = false;
            for (int tn : path.turns) nearTurn |= Math.abs(i - tn) <= 2;
            if (path.second[i] && !nearTurn) assertTrue(!path.second[path.mate[i]], "mate of a way-back sample is on the way down");
            pairs++;
        }
        assertTrue(pairs > 40, "arm lines too short: " + pairs);
        assertEquals(2, path.turns.length);
    }

    @Test
    void neverOnTheNeckHeadOrLegs() {
        for (double[] pose : new double[][] {{0, 0}, {35, 0}, {0, 12}, {200, -10}}) {
            FlowMeridian.Anchors a = nominal(pose[0], pose[1]);
            FlowMeridian.Path path = FlowMeridian.build(a);
            for (int i = 0; i < FlowMeridian.SAMPLES; i++) {
                double ac = across(a, path.p[i]), v = up(a, path.p[i]);
                // the neck and head stand above the collar: the way stays under the shoulder line and 1.6 px under it between the shoulders
                assertTrue(v <= 12.8, "above the shoulder line at " + i + " v=" + v);
                if (Math.abs(ac) < 3.2) assertTrue(v <= 10.3, "on the neck column at " + i + " a=" + ac + " v=" + v);
                // legs: nothing under the hands' lowest point, and the torso part keeps off the belt
                assertTrue(v >= -1.5, "down to the legs at " + i + " v=" + v);
                if (path.zone[i] != FlowMeridian.Zone.ARM) assertTrue(v >= 2.5 && v <= 11.9 && Math.abs(ac) <= 5.7, "torso part leaves the torso at " + i + " a=" + ac + " v=" + v);
            }
        }
    }

    @Test
    void nodeApertureIsContinuousAndShutBelowTheGamesThreshold() {
        double period = 1.6, phase = 2.2, prevR = -1, prevK = -1;
        for (double t = 0; t < 12; t += 1 / 240.0) {
            double a = FlowMeridian.aperture(t, period, phase);
            double r = FlowMeridian.nodeRadius(a), k = FlowMeridian.nodeKnot(a);
            if (prevR >= 0) {
                assertTrue(Math.abs(r - prevR) < .003, "radius jumps");
                assertTrue(Math.abs(k - prevK) < .06, "knot jumps");
            }
            assertTrue(r >= .04 - 1e-9 && r <= .06 + 1e-9, "ring radius " + r);
            if (a < -.5) assertEquals(1, k, 1e-9);
            if (a > .1) assertEquals(0, k, 1e-9);
            prevR = r; prevK = k;
        }
    }

    @Test
    void followerHasNoOvershootAndSmoothsAStep() {
        double[] st = {.5, 0};
        double prev = .5, maxStep = 0;
        for (int i = 0; i < 240; i++) {
            FlowMeridian.follow(st, .465, 12.5, 1 / 60.0);
            maxStep = Math.max(maxStep, Math.abs(st[0] - prev));
            assertTrue(st[0] >= .465 - 1e-9, "overshoot");
            prev = st[0];
        }
        assertEquals(.465, st[0], 1e-3);
        assertTrue(maxStep < .035 * .3, "a step of .035 must be spread, max step " + maxStep);
    }

    @Test
    void drawnSIsContinuousThroughAWrongFork() {
        for (long seed = 1; seed <= 12; seed++) {
            FlowCharacter c = FlowCharacter.of(List.of(PillKind.ORIGIN_ENERGY.flow(), PillKind.THOUSAND_POISON.flow()));
            FlowGame g = new FlowGame(c, seed, false, false, false);
            double[] st = {0, 0};
            double prev = 0, maxStep = 0, maxGame = 0, lastS = 0;
            boolean bounced = false;
            for (int i = 0; i < 60 * 90 && g.running(); i++) {
                FlowBot.drive(g, "burst");   // takes every wrong branch
                g.update(1 / 60.0);
                maxGame = Math.max(maxGame, Math.abs(g.s - lastS));
                if (g.s < lastS - .02) bounced = true;
                lastS = g.s;
                FlowMeridian.follow(st, g.s, 12.5, 1 / 60.0);
                maxStep = Math.max(maxStep, Math.abs(st[0] - prev));
                prev = st[0];
            }
            if (bounced) {
                assertTrue(maxGame >= .03, "the game steps back in one frame");
                assertTrue(maxStep < .01, "drawn s jumps by " + maxStep + " (seed " + seed + ")");
            }
        }
    }

    @Test
    void forkBumpIsSmooth() {
        double prev = 0;
        for (double u = .3; u < .5; u += .0002) {
            double b = FlowMeridian.bump(u, .4, FlowMeridian.FORK_HALF);
            assertTrue(Math.abs(b - prev) < .01);
            prev = b;
        }
        assertEquals(0, FlowMeridian.bump(.4 - FlowMeridian.FORK_HALF, .4, FlowMeridian.FORK_HALF), 1e-9);
        assertEquals(1, FlowMeridian.bump(.4, .4, FlowMeridian.FORK_HALF), 1e-6);
    }
}
