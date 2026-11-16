package io.github.verycooltimo.murim.client.vfx.fx;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FxTuneStoreTest {
    @Test void defaultsComeBackWhenNothingIsLoaded() {
        var s = new FxTuneStore();
        assertEquals(.06F, s.f("a.b.c", .06F));
        assertEquals(.17, s.d("a.b.d", .17));
        assertEquals(3, s.i("a.b.i", 3));
        assertTrue(s.b("a.b.on", true));
        assertEquals(0xFBD86E, s.rgb("a.b.col", 0xFBD86E));
        assertArrayEquals(new float[]{1, 2}, s.fa("a.b.arr", 1, 2));
    }

    @Test void overridesReplaceDefaultsByType() {
        var s = new FxTuneStore();
        assertNull(s.load("{\"revision\":4,\"w\":0.5,\"d\":0.25,\"n\":7.4,\"flag\":false,\"c\":\"#ff8000\",\"c2\":\"0x00ff10\",\"c3\":255,\"k\":[0,1.5,3]}"));
        assertEquals(4, s.revision());
        assertEquals(.5F, s.f("w", 1));
        assertEquals(.25, s.d("d", 1));
        assertEquals(7, s.i("n", 0));
        assertFalse(s.b("flag", true));
        assertEquals(0xFF8000, s.rgb("c", 0));
        assertEquals(0x00FF10, s.rgb("c2", 0));
        assertEquals(255, s.rgb("c3", 0));
        assertArrayEquals(new float[]{0, 1.5F, 3}, s.fa("k", 9));
    }

    @Test void wrongTypeKeepsTheDefaultAndIsReported() {
        var s = new FxTuneStore();
        assertNull(s.load("{\"w\":\"wide\",\"c\":\"red\",\"k\":[1,\"x\"],\"flag\":\"maybe\"}"));
        assertEquals(.3F, s.f("w", .3F));
        assertEquals(0x123456, s.rgb("c", 0x123456));
        assertArrayEquals(new float[]{5}, s.fa("k", 5));
        assertTrue(s.b("flag", true));
        assertEquals(4, s.badValues().size());
    }

    @Test void malformedTextKeepsTheOldValues() {
        var s = new FxTuneStore();
        assertNull(s.load("{\"revision\":1,\"w\":2}"));
        assertNotNull(s.load("{\"revision\":2,\"w\":"));
        assertNotNull(s.load("[1,2,3]"));
        assertNotNull(s.load("not json"));
        assertEquals(1, s.revision());
        assertEquals(2F, s.f("w", 0));
    }

    @Test void unknownKeysAreTheOnesNoCodeAskedFor() {
        var s = new FxTuneStore();
        assertNull(s.load("{\"revision\":1,\"_time\":3,\"_shot\":1,\"used\":1,\"typo.key\":2}"));
        s.f("used", 0);
        s.f("other", .5F);
        assertEquals(java.util.List.of("typo.key"), s.unknownKeys());
        // a key that code asks for later stops being unknown
        s.f("typo.key", 0);
        assertTrue(s.unknownKeys().isEmpty());
    }

    @Test void knownKeysCarryTheirDefaultsAndReportChange() {
        var s = new FxTuneStore();
        s.f("x", .25F);
        s.rgb("col", 0xFBD86E);
        s.fa("cam", 0, 2.5F);
        assertTrue(s.takeKnownChanged());
        assertFalse(s.takeKnownChanged());
        var known = s.knownKeys();
        assertEquals("0.25", known.get("x"));
        assertEquals("#fbd86e", known.get("col"));
        assertEquals("[0.0, 2.5]", known.get("cam"));
        s.f("x", .25F);
        assertFalse(s.takeKnownChanged());
    }

    @Test void controlsAreReadWithoutBecomingKnownKeys() {
        var s = new FxTuneStore();
        assertNull(s.load("{\"_time\":4.5,\"_loop\":[1,3],\"_loop_bad\":[1,\"a\"]}"));
        assertEquals(4.5, s.control("_time", -1));
        assertEquals(-1, s.control("_speed", -1));
        assertArrayEquals(new double[]{1, 3}, s.controlArray("_loop"));
        assertNull(s.controlArray("_loop_bad"));
        assertNull(s.controlArray("_none"));
        assertTrue(s.knownKeys().isEmpty());
        assertTrue(s.unknownKeys().isEmpty());
    }

    @Test void colourParsing() {
        assertEquals(0xABCDEF, FxTuneStore.parseHex(" #ABCDEF "));
        assertNull(FxTuneStore.parseHex("#abc"));
        assertNull(FxTuneStore.parseHex("zzzzzz"));
        assertEquals("#00ff10", FxTuneStore.hex(0x00FF10));
    }
}
