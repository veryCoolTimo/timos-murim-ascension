package io.github.verycooltimo.murim.client.vfx.bt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BtFireLightTest {
    @Test
    void faceTurnedToTheCameraGetsOnlyTheBaseAndTheRimGetsAll() {
        // a vertex two blocks ahead of the camera: a normal pointing back at the camera, then one across the view
        assertEquals(.1F, BtFireLight.weight(0, 0, 2, 0, 0, -1, .5F, .1F), 1e-6);
        assertEquals(.6F, BtFireLight.weight(0, 0, 2, 1, 0, 0, .5F, .1F), 1e-6);
        // the head has no base: its front stays dark
        assertEquals(0F, BtFireLight.weight(0, 0, 2, 0, 0, -1, .35F, 0), 1e-6);
    }

    @Test
    void lightIsClampedToAByte() {
        assertEquals(255, BtFireLight.to255(1.7F));
        assertEquals(0, BtFireLight.to255(-.2F));
    }
}
