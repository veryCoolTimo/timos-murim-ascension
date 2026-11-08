package io.github.verycooltimo.murim.cultivation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The numbers of the breakthrough scene (hua-main-1810 section 4): radius, the layered sound, the silence, the afterglow. */
class BreakthroughScriptTest {

    private static final int[] SCENES = {216, 304, 304, 510};

    @Test
    void theEffectRadiusGrowsWithTheRankFromThreeToTenBlocks() {
        assertEquals(3.0D, BreakthroughScript.radius(Realm.THIRD));
        assertEquals(5.0D, BreakthroughScript.radius(Realm.SECOND));
        assertEquals(7.0D, BreakthroughScript.radius(Realm.FIRST));
        assertEquals(10.0D, BreakthroughScript.radius(Realm.PEAK));
        // One scale for every layer: out of range ranks are clamped, never extrapolated.
        assertEquals(3.0D, BreakthroughScript.radius(0));
        assertEquals(10.0D, BreakthroughScript.radius(9));
    }

    @Test
    void sceneLengthsFollowAllFourLabBlows() {
        for (int i = 0; i < SCENES.length; i++) {
            assertEquals(SCENES[i], Realm.breakthroughTicks(i + 1));
        }
    }

    @Test
    void threeCracksThenSilenceThenTheImpact() {
        for (int scene : SCENES) {
            int[] cracks = BreakthroughScript.crackTicks(scene);
            assertEquals(3, cracks.length);
            assertTrue(cracks[0] < cracks[1] && cracks[1] < cracks[2], "cracks come one after another");
            int silence = BreakthroughScript.silenceStart(scene);
            assertTrue(cracks[2] < silence, "the last crack falls before the silence starts");
            assertEquals(8, scene - silence, "0.4 s of silence = 8 ticks");
            assertEquals(8, BreakthroughScript.SILENCE_TICKS);
            assertTrue(cracks[0] > scene / 2, "the cracks belong to the end of the scene");
        }
    }

    @Test
    void theHumRisesAndStopsBeforeTheCracks() {
        for (int scene : SCENES) {
            int starts = 0;
            float lastPitch = 0.0F;
            float lastVolume = 0.0F;
            int lastStart = -1;
            for (int s = 0; s < scene; s++) {
                if (!BreakthroughScript.humStarts(s, scene)) {
                    continue;
                }
                starts++;
                lastStart = s;
                float pitch = BreakthroughScript.humPitch(s, scene);
                float volume = BreakthroughScript.humVolume(s, scene);
                assertTrue(pitch >= lastPitch && volume >= lastVolume, "the hum only rises");
                lastPitch = pitch;
                lastVolume = volume;
            }
            assertTrue(starts >= 8, "a hum that lasts the scene, got " + starts + " starts for " + scene);
            assertTrue(lastStart < BreakthroughScript.crackTicks(scene)[0], "no hum sample starts over the first crack");
            assertTrue(BreakthroughScript.humPitch(0, scene) < 0.7F && BreakthroughScript.humPitch(scene, scene) > 1.3F);
        }
    }

    @Test
    void theBreathIsHeardTwiceInTheOmenAndNeverInTheScene() {
        int breaths = 0;
        for (int t = 0; t < Realm.WARNING_TICKS; t++) {
            if (BreakthroughScript.breathAt(t)) {
                breaths++;
            }
        }
        assertEquals(2, breaths, "one breath in, one out");
        assertEquals(50, Realm.WARNING_TICKS, "the omen is 2.5 s");
    }

    @Test
    void theEchoIsHeardSixtyFourBlocksAndTheAfterglowLastsThirtySeconds() {
        assertEquals(64.0F, BreakthroughScript.ECHO_RANGE);
        assertEquals(600, BreakthroughScript.AFTERGLOW_TICKS);
        assertFalse(BreakthroughWorld.FLEE_RANGE < 8.0D, "mobs within eight blocks run");
        assertEquals(200, BreakthroughWorld.FLEE_TICKS, "for ten seconds");
        assertEquals(6.0D, BreakthroughWorld.GUARD_RANGE, "the guardian stands within six blocks");
    }

    @Test
    void everySoundOfTheSceneHasAFileAndASubtitleInBothLanguages() throws IOException {
        Path root = res("assets/murim");
        JsonObject sounds = read(root.resolve("sounds.json"));
        for (String id : new String[] {"bt_breath", "bt_hum", "bt_crack", "bt_tail"}) {
            assertTrue(sounds.has(id), id + " is in sounds.json");
            for (var entry : sounds.getAsJsonObject(id).getAsJsonArray("sounds")) {
                String name = entry.getAsJsonObject().get("name").getAsString().substring("murim:".length());
                assertTrue(Files.exists(root.resolve("sounds").resolve(name + ".ogg")), name + ".ogg exists");
            }
            for (String lang : new String[] {"ru_ru", "en_us"}) {
                assertTrue(read(root.resolve("lang").resolve(lang + ".json")).has("subtitles.murim." + id), lang + " subtitle for " + id);
            }
        }
        // The echo reaches 64 blocks: the file says so too, or the client would stop it at 16.
        assertEquals(64, sounds.getAsJsonObject("bt_tail").getAsJsonArray("sounds").get(0).getAsJsonObject()
                .get("attenuation_distance").getAsInt());
    }

    @Test
    void theGuardianLinesAndTheShakenMessageExistInBothLanguages() throws IOException {
        Path root = res("assets/murim/lang");
        for (String lang : new String[] {"ru_ru", "en_us"}) {
            JsonObject json = read(root.resolve(lang + ".json"));
            assertTrue(json.has("murim.rank.breakthrough.shaken"), lang + " shaken message");
            for (int i = 1; i <= BreakthroughWorld.GUARD_LINES; i++) {
                assertTrue(json.has("murim.bubble.breakthrough.guard." + i), lang + " guard line " + i);
            }
        }
    }

    /** The resources folder, from the project root or from a test run directory two levels down (as DialogueDataTest does). */
    private static Path res(String rel) {
        Path p = Path.of("src/main/resources/" + rel);
        return Files.exists(p) ? p : Path.of("../../src/main/resources/" + rel);
    }

    private static JsonObject read(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
