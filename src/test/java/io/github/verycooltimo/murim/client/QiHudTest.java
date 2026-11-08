package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.profile.DantianProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QiHudTest {
    @Test
    void pulseFollowsReserveWallAndClearsImmediately() {
        var profile = new DantianProfile(30.0D, 0.8D, 0.5D, "pure", "six_harmonies", 0, 0, 1, 0, 0);
        assertFalse(QiHud.wallPulse(DantianProfile.INITIAL));
        assertFalse(QiHud.wallPulse(profile));
        assertTrue(QiHud.wallPulse(profile.withPool(Realm.wall(profile))));
        assertFalse(QiHud.wallPulse(profile.withPool(Realm.wall(profile) * 0.9D)));
        var promoted = profile.withPool(Realm.wall(profile)).withRank(Realm.THIRD);
        assertFalse(QiHud.wallPulse(promoted));
    }
}
