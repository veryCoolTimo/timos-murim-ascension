package io.github.verycooltimo.murim.dev;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaptureViewTest {
    private static final CaptureView.Point EYE = new CaptureView.Point(7, 3.62, -11);

    @Test void victimIsAtTargetEyesAndLooksAtCasterHeadInEveryQuadrant() {
        for (double[] delta : new double[][] {{3, 1, 4}, {-3, 2, 4}, {3, -2, -4},
                {-3, 0, -4}, {0, 5, 0}, {0, -5, 0}}) {
            var head = new CaptureView.Point(EYE.x() + delta[0], EYE.y() + delta[1], EYE.z() + delta[2]);
            var pose = CaptureView.victim(EYE, head, null);
            assertEquals(EYE, pose.position());
            double yaw = Math.toRadians(pose.yaw()), pitch = Math.toRadians(pose.pitch());
            double length = Math.sqrt(delta[0]*delta[0] + delta[1]*delta[1] + delta[2]*delta[2]);
            assertEquals(delta[0]/length, -Math.sin(yaw)*Math.cos(pitch), 1e-6);
            assertEquals(delta[1]/length, -Math.sin(pitch), 1e-6);
            assertEquals(delta[2]/length, Math.cos(yaw)*Math.cos(pitch), 1e-6);
        }
    }

    @Test void victimUpUsesSuppliedAboveHorizonDegreesAndPreservesYaw() {
        var head = new CaptureView.Point(-2, 1, 8);
        for (double degrees : new double[] {55, 23.5, 0, 90}) {
            var pose = CaptureView.victim(EYE, head, degrees);
            assertEquals(EYE, pose.position());
            assertEquals(-degrees, pose.pitch(), 1e-6);
            assertEquals(CaptureView.victim(EYE, head, null).yaw(), pose.yaw());
        }
    }

    @Test void thirdIsLevelAndRequestedDistanceBehindCaster() {
        for (var target : new CaptureView.Point[] {new CaptureView.Point(0, 12, 0),
                new CaptureView.Point(20, -3, -20), new CaptureView.Point(7, 3, 10)}) {
            for (double distance : new double[] {4, 2.5, 8}) {
                var pose = CaptureView.third(EYE, target, distance);
                double dx = pose.position().x() - EYE.x(), dz = pose.position().z() - EYE.z();
                assertEquals(distance, Math.hypot(dx, dz), 1e-6);
                assertEquals(EYE.y(), pose.position().y());
                assertEquals(0, pose.pitch());
                assertTrue(dx * (target.x()-EYE.x()) + dz * (target.z()-EYE.z()) < 0);
            }
        }
    }
}
