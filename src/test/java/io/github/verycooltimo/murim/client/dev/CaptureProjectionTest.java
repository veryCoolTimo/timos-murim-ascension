package io.github.verycooltimo.murim.client.dev;

import static org.junit.jupiter.api.Assertions.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class CaptureProjectionTest {
    private final Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(90), 2, .1F, 100);

    @Test
    void knownPixelsAndDepthWithTopLeftOrigin() {
        var point = CaptureProjection.project(1, 1, -2, new Matrix4f(), projection, 800, 400);
        assertEquals(500, point.x(), .001);
        assertEquals(100, point.y(), .001);
        assertEquals(2, point.depth(), .001);
        assertTrue(point.visible());
    }

    @Test
    void behindOutsideAndCameraPlaneAreNotVisible() {
        assertFalse(CaptureProjection.project(0, 0, 2, new Matrix4f(), projection, 800, 400).visible());
        assertFalse(CaptureProjection.project(10, 0, -2, new Matrix4f(), projection, 800, 400).visible());
        var plane = CaptureProjection.project(0, 0, 0, new Matrix4f(), projection, 800, 400);
        assertFalse(plane.visible());
        assertTrue(Double.isFinite(plane.x()));
    }

    @Test
    void usesViewRotationRatherThanAssumingCameraAxes() {
        var view = new Matrix4f().rotationY((float) Math.PI / 2);
        var point = CaptureProjection.project(2, 0, 0, view, projection, 800, 400);
        assertEquals(400, point.x(), .001);
        assertEquals(200, point.y(), .001);
        assertEquals(2, point.depth(), .001);
        assertTrue(point.visible());
    }
}
