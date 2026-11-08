package io.github.verycooltimo.murim.client.camera;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keyframed camera shots: end points, no overshoot, easing, mirroring. */
class ShotTrackTest {

    private static ShotTrack.Key key(float t, float az, float pitch, float dist) {
        return new ShotTrack.Key(t, new ShotPose(az, pitch, dist, 0.9F, 0.0F, 1.0F));
    }

    @Test
    @DisplayName("A shot starts on its first key and ends on its last")
    void endPoints() {
        ShotTrack s = new ShotTrack("t", 1, ShotTrack.Ease.IN_OUT, 0.5F, true,
                List.of(key(0.0F, 10.0F, 5.0F, 1.0F), key(0.4F, 20.0F, 15.0F, 0.8F), key(1.0F, 30.0F, 10.0F, 2.0F)));
        assertEquals(10.0F, s.at(0.0F).azimuth(), 1.0E-5F);
        assertEquals(30.0F, s.at(1.0F).azimuth(), 1.0E-5F);
        assertEquals(2.0F, s.at(5.0F).distance(), 1.0E-5F, "past the end it holds");
        assertEquals(1.0F, s.at(-1.0F).distance(), 1.0E-5F, "before the start too");
    }

    @Test
    @DisplayName("The curve never overshoots its keys: a pull-in to 0.8 blocks stays at or above 0.8")
    void noOvershoot() {
        ShotTrack s = new ShotTrack("t", 1, ShotTrack.Ease.LINEAR, 0.5F, true,
                List.of(key(0.0F, 0.0F, 5.0F, 1.0F), key(0.4F, 0.0F, 15.0F, 0.8F), key(1.0F, 0.0F, 10.0F, 2.0F)));
        float min = Float.MAX_VALUE;
        float maxPitch = 0.0F;
        for (int i = 0; i <= 200; i++) {
            ShotPose p = s.at(i / 200.0F);
            min = Math.min(min, p.distance());
            maxPitch = Math.max(maxPitch, p.pitch());
        }
        assertTrue(min >= 0.8F - 1.0E-4F, "min distance " + min);
        assertTrue(maxPitch <= 15.0F + 1.0E-4F, "max pitch " + maxPitch);
    }

    @Test
    @DisplayName("Two keys glide steadily; an in-out ease starts and ends slowly")
    void easing() {
        ShotTrack linear = new ShotTrack("l", 1, ShotTrack.Ease.LINEAR, 0.5F, true,
                List.of(key(0.0F, 0.0F, 0.0F, 1.0F), key(1.0F, 100.0F, 0.0F, 1.0F)));
        assertEquals(25.0F, linear.at(0.25F).azimuth(), 1.0E-3F);
        ShotTrack eased = new ShotTrack("e", 1, ShotTrack.Ease.IN_OUT, 0.5F, true,
                List.of(key(0.0F, 0.0F, 0.0F, 1.0F), key(1.0F, 100.0F, 0.0F, 1.0F)));
        assertTrue(eased.at(0.1F).azimuth() < 10.0F, "slow start");
        assertTrue(eased.at(0.9F).azimuth() > 90.0F, "slow finish");
        assertEquals(50.0F, eased.at(0.5F).azimuth(), 1.0E-3F);
    }

    @Test
    @DisplayName("Mirroring swaps the sides: azimuth and roll change sign, the rest stays")
    void mirrored() {
        ShotPose p = new ShotPose(40.0F, 10.0F, 2.0F, 0.8F, 3.0F, 0.9F);
        ShotPose m = p.mirrored(-1);
        assertEquals(-40.0F, m.azimuth());
        assertEquals(-3.0F, m.roll());
        assertEquals(10.0F, m.pitch());
        assertEquals(p, p.mirrored(1));
    }

    @Test
    @DisplayName("Bad data is refused loudly: no keys, keys out of order")
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> new ShotTrack("x", 1, ShotTrack.Ease.LINEAR, 0.5F, true, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ShotTrack("x", 1, ShotTrack.Ease.LINEAR, 0.5F, true,
                List.of(key(0.5F, 0, 0, 1), key(0.2F, 0, 0, 1))));
    }
}
