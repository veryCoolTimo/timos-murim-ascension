package io.github.verycooltimo.murim.client;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BladeExamFilmCaptureTest {
    @Test
    void frontCompositionUsesFixedStandAxes() {
        Vec3 ring = new Vec3(12.5, 64, -8.25);
        Vec3 eye = BladeExamFilmCapture.Placement.eye(ring, 1.62);
        Vec3 target = BladeExamFilmCapture.Placement.target(ring, 1.62);
        assertEquals(10.7, eye.x, 1.0E-6);
        assertEquals(65.62, eye.y, 1.0E-6);
        assertEquals(-5.65, eye.z, 1.0E-6);
        assertEquals(12.0, target.x, 1.0E-6);
        assertEquals(65.75, target.y, 1.0E-6);
        assertEquals(-8.25, target.z, 1.0E-6);
        assertFalse(BladeExamFilmCapture.Placement.faceHidden(eye, ring.add(1.5, 1.62, 0)));
        assertFalse(BladeExamFilmCapture.Placement.faceHidden(eye, ring.add(0, 1.62, 0)));
    }

    @Test
    void faceGuardRejectsEyeAtOrBehindPupilOnWorldXAxis() {
        Vec3 pupil = new Vec3(12.5, 65.62, -8.25);
        assertFalse(BladeExamFilmCapture.Placement.faceHidden(pupil.add(-0.01, 0, 2.6), pupil));
        assertTrue(BladeExamFilmCapture.Placement.faceHidden(pupil.add(0, 0, 2.6), pupil));
        assertTrue(BladeExamFilmCapture.Placement.faceHidden(pupil.add(0.01, 0, 2.6), pupil));
    }
}
