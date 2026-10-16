package io.github.verycooltimo.murim.mastery;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.cultivation.CultivationMethod;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Real codec regressions for per-art scales; no world or datapack loader required. */
class StarMasteryTest {
    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath("murim", name);
    }

    private static MasteryState decode(String json) {
        return MasteryState.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    @Test
    void oldLayersMigrateDirectlyToTwelveAndDoNotOpenForms() {
        var state = decode("""
                {"techniques": {
                  "murim:seven_plum_basic": {"layer": 4, "progress": 13, "unprocessed": 9, "day": 5, "cap": 8},
                  "murim:seven_plum_blossoms": {"layer": 2, "progress": 7, "day": 5, "cap": 3}
                }, "wisdom": 8, "comprehended": ["murim:seven_plum_blossoms"]}
                """);
        var p = state.progress(id("seven_plum"));
        assertEquals(6, p.stars());
        assertEquals(12, p.cap());
        assertEquals(13 / MasteryRules.need(4), p.progress() / MasteryRules.need(6), 1E-9);
        assertEquals(9 / MasteryRules.need(4), p.unprocessed() / MasteryRules.need(6), 1E-9);
        assertEquals(2, state.techniques().size());
        assertEquals(1, state.arts().size());
        assertFalse(state.knows(id("seven_plum_execution")));
        assertEquals(Set.of(id("seven_plum")), state.comprehended());
        assertEquals(8, state.wisdom());
        assertRoundTrip(state);
    }

    @Test
    void versionTwoSharedArtSavePreservesTornLimitAndCultivationMastery() {
        var state = decode("""
                {"version": 2, "arts": {
                  "murim:seven_plum": {"version": 2, "stars": 5, "progress": 13, "unprocessed": 9, "day": 5, "cap": 7},
                  "murim:method/six_harmonies": {"version": 2, "stars": 6, "progress": 0, "cap": 6}
                }, "known": ["murim:seven_plum_basic", "murim:seven_plum_blossoms", "murim:method/six_harmonies"],
                   "wisdom": 8, "comprehended": ["murim:seven_plum"]}
                """);
        var p = state.progress(id("seven_plum"));
        assertEquals(6, p.stars());
        assertEquals(8, p.cap());
        assertEquals(13 / MasteryRules.need(5), p.progress() / MasteryRules.need(6), 1E-9);
        assertEquals(9 / MasteryRules.need(5), p.unprocessed() / MasteryRules.need(6), 1E-9);
        assertEquals(5, p.day());
        assertEquals(12, state.progress(id("method/six_harmonies")).stars());
        assertEquals(3, state.techniques().size());
        assertEquals(state.techniques().get(id("seven_plum_basic")), state.techniques().get(id("seven_plum_blossoms")));
        assertEquals(8, state.wisdom());
        assertRoundTrip(state);
    }

    @Test
    void everyFormerDenominatorPreservesProgressAndPreviouslyUnlockedAbilities() {
        for (String name : List.of("bandit_rush", "six_harmonies", "wind_god_steps", "four_horizons",
                "method/street_strength", "method/six_harmonies", "method/mountain_root", "method/reverse_breathing")) {
            var form = id(name);
            int oldMaximum = Arts.previousStarMaximum(form, 12, 0);
            for (int old = 0; old <= oldMaximum; old++) {
                for (int cap = old; cap <= oldMaximum; cap++) {
                    for (int version : List.of(1, 2)) {
                        var raw = new TechniqueProgress(old, MasteryRules.need(old) * .4,
                                MasteryRules.need(old) * .7, 5, cap, version);
                        var migrated = raw.migrate(form);
                        assertEquals((int) Math.round(old / (double) oldMaximum * Arts.cap(form)), migrated.stars());
                        assertEquals((int) Math.round(cap / (double) oldMaximum * Arts.cap(form)), migrated.cap());
                        assertTrue(migrated.stars() >= old);
                        assertEquals(.4, migrated.progress() / MasteryRules.need(migrated.stars()), 1E-9);
                        assertEquals(.7, migrated.unprocessed() / MasteryRules.need(migrated.stars()), 1E-9);
                        assertEquals(TechniqueProgress.VERSION, migrated.version());
                        assertSame(migrated, migrated.migrate(form));
                        if (!Arts.isMethod(form)) {
                            int formerTier = 0;
                            for (int step = 1; step <= Arts.legacySteps(form); step++) {
                                if (old >= Math.round(step / (double) Arts.legacySteps(form) * oldMaximum)) formerTier = step;
                            }
                            assertTrue(Arts.executionTier(form, migrated.stars()) >= formerTier, name + " at " + old);
                        }
                    }
                }
            }
        }
        assertEquals(3, new TechniqueProgress(2, 0, 0, 0, 2, 0).migrate(id("wind_god_steps")).cap());
        assertEquals(6, new TechniqueProgress(3, 0, 0, 0, 3, 2).migrate(id("six_harmonies")).cap());
    }

    @Test
    void starFieldsWithoutVersionAreNotMistakenForLayers() {
        var state = decode("""
                {"techniques": {"murim:six_harmonies": {"stars": 3, "progress": 0, "cap": 3}}}
                """);
        assertEquals(6, state.progress(id("six_harmonies")).stars());
        assertRoundTrip(state);
    }

    @Test
    void unknownOldDatapacksKeepAchievementAndCurrentDataIsBounded() {
        var custom = ResourceLocation.fromNamespaceAndPath("custom", "art");
        var migrated = new TechniqueProgress(3, 7, 9, 4, 5, 2).migrate(custom);
        assertEquals(7, migrated.stars());
        assertEquals(12, migrated.cap());
        assertEquals(7 / MasteryRules.need(3), migrated.progress() / MasteryRules.need(7), 1E-9);
        var state = new MasteryState(Map.of(custom, TechniqueProgress.learned(20, 20)), 0, Set.of());
        assertEquals(12, state.progress(custom).stars());
        assertEquals(12, state.progress(custom).cap());
    }

    @Test
    void insightWallRequiresSparringMeditationOrMentorReviewAtBothFinalTransitions() {
        for (int stars : List.of(10, 11)) {
            double need = MasteryRules.need(stars);
            var ready = new TechniqueProgress(stars, need, 0, 0, 12);
            for (var source : List.of(MasteryRules.Source.TRAINING, MasteryRules.Source.FIGHT)) {
                assertEquals(stars, MasteryRules.experience(ready, source, 1, 0, 0).progress().stars());
            }
            assertEquals(stars, MasteryRules.study(ready, 1, 0).progress().stars());
            assertEquals(stars + 1, MasteryRules.meditate(ready, 0, 0).progress().stars());
            assertEquals(stars + 1, MasteryRules.experience(ready, MasteryRules.Source.SPARRING, 1, 0, 0).progress().stars());
            assertEquals(stars + 1, MasteryRules.study(ready, 0, 0, 12, true).progress().stars());
        }
        var torn = new TechniqueProgress(8, MasteryRules.need(8), 0, 0, 9);
        assertEquals(9, MasteryRules.study(torn, 0, 0).progress().stars(), "partial manuscript is not the insight denominator");
        assertEquals(9, MasteryRules.study(torn, 1E9, 0, 12, true).progress().stars(), "insight cannot exceed manuscript");
        assertEquals(10, MasteryRules.startStars(TechniqueTier.BASIC, 12, 1E9), "wisdom cannot bypass enlightenment");
    }

    @Test
    void hudUsesTheSuppliedFullArtCap() {
        assertEquals("★★★★☆☆☆☆☆☆☆☆", StarDisplay.gauge(4, 12));
        assertEquals("★★★★", StarDisplay.gauge(4, 4));
        assertEquals("★★★★☆☆☆☆☆☆", StarDisplay.gauge(4, 10));
        assertEquals("☆☆☆☆☆☆☆☆☆☆☆☆", StarDisplay.gauge(-1, 12));
        assertEquals("★★★★★★★★★★★★", StarDisplay.gauge(20, 12));
    }

    @Test
    void definitionCodecsDefaultToTwelveAndRespectArtCaps() throws Exception {
        try (var stream = getClass().getResourceAsStream("/data/murim/murim_techniques/ceremonial_draw.json")) {
            assertNotNull(stream);
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            json.remove("star_cap");
            assertEquals(12, TechniqueDefinition.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().starCap());
            json.addProperty("star_cap", 6);
            var definition = TechniqueDefinition.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
            assertEquals(6, definition.starCap());
            var loaded = io.github.verycooltimo.murim.technique.TechniqueLoader.all();
            try {
                io.github.verycooltimo.murim.technique.TechniqueLoader.replaceAll(Map.of(definition.id(), definition));
                assertEquals(6, Arts.cap(definition.id()), "datapack cap was ignored");
            } finally {
                io.github.verycooltimo.murim.technique.TechniqueLoader.replaceAll(loaded);
            }
        }
        var json = JsonParser.parseString("""
                {"id":"murim:test_method", "nature":"pure", "purity":0.8, "capacity":10,
                 "impurity":0.2, "route":"six_harmonies"}
                """).getAsJsonObject();
        assertEquals(12, CultivationMethod.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().starCap());
        json.addProperty("star_cap", 5);
        assertEquals(12, CultivationMethod.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().starCap());
    }

    @Test
    void everyFootworkFormAndSharedArtCompletesAtTenBeforeDatapacksLoad() throws Exception {
        for (String name : List.of("wind_god_steps", "wind_god_shadow", "wind_god_lightning", "wind_god_death",
                "dark_fragrance_step", "dark_fragrance_behind", "dark_fragrance_trail", "high_cloud_steps", "shadow_step")) {
            var form = id(name);
            assertEquals(10, Arts.cap(form), name);
            assertEquals(10, Arts.cap(Arts.masteryId(form)), name);
            assertEquals(Arts.legacySteps(form), Arts.executionTier(form, 10), name);
            try (var stream = getClass().getResourceAsStream("/data/murim/murim_techniques/" + name + ".json")) {
                assertNotNull(stream);
                var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                assertEquals(10, json.get("star_cap").getAsInt());
                json.remove("star_cap");
                assertEquals(10, TechniqueDefinition.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().starCap());
                json.addProperty("star_cap", 12);
                assertEquals(10, TechniqueDefinition.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().starCap());
            }
        }
        assertEquals(12, Arts.cap(id("seven_plum")));
        assertEquals(12, Arts.cap(id("unknown_art")));
        assertEquals(12, Arts.cap(id("method/six_harmonies")));
    }

    @Test
    void versionThreeFootworkPreservesFractionsTornLimitsAndEveryUnlockedTier() {
        for (String name : List.of("wind_god_steps", "wind_god_shadow", "wind_god_death", "wind_god_lightning",
                "dark_fragrance_step", "dark_fragrance_behind", "dark_fragrance_trail", "high_cloud_steps", "shadow_step")) {
            var form = id(name);
            int steps = Arts.legacySteps(form);
            int previous = Arts.previousStarMaximum(form, 12, 0);
            for (int old = 0; old <= 12; old++) {
                for (int limit = old; limit <= 12; limit++) {
                    var p = new TechniqueProgress(old, MasteryRules.need(old) * 1.3,
                            MasteryRules.need(old) * .7, 5, limit, 3).migrate(form);
                    assertEquals((int) Math.round(old / 12.0 * 10), p.stars());
                    assertEquals((int) Math.round(limit / 12.0 * 10), p.cap());
                    assertEquals(1.3, p.progress() / MasteryRules.need(p.stars()), 1E-9);
                    assertEquals(.7, p.unprocessed() / MasteryRules.need(p.stars()), 1E-9);
                    int oldTier = 0;
                    for (int step = 1; step <= steps; step++) {
                        int threshold = Math.min((int) Math.round(step / (double) steps * 12),
                                (int) Math.round(Math.round(step / (double) steps * previous) / (double) previous * 12));
                        if (old >= threshold) oldTier = step;
                    }
                    assertTrue(Arts.executionTier(form, p.stars()) >= oldTier, name + " at " + old);
                    assertSame(p, p.migrate(form));
                }
            }
        }
        var oldSword = new TechniqueProgress(11, 123, 45, 6, 12, 3);
        assertEquals(new TechniqueProgress(11, 123, 45, 6, 12), oldSword.migrate(id("seven_plum_basic")));
        assertEquals(new TechniqueProgress(11, 123, 45, 6, 12), oldSword.migrate(id("method/six_harmonies")));
        var state = decode("""
                {"version":3,"arts":{"murim:wind_god":{"version":3,"stars":6,
                  "progress":13,"unprocessed":9,"day":5,"cap":9}},
                 "known":["murim:wind_god_steps","murim:wind_god_shadow"],
                 "wisdom":8,"comprehended":["murim:wind_god"]}
                """);
        assertEquals(5, state.progress(id("wind_god")).stars());
        assertEquals(8, state.progress(id("wind_god")).cap());
        assertEquals(2, state.techniques().size());
        assertFalse(state.knows(id("wind_god_death")));
        assertEquals(8, state.wisdom());
        assertEquals(Set.of(id("wind_god")), state.comprehended());
        assertRoundTrip(state);
    }

    @Test
    void footworkInsightWallUsesFullArtEvenForTornManuscripts() {
        assertEquals(8, MasteryRules.insightStar(10));
        assertEquals(10, MasteryRules.insightStar(12));
        for (int stars : List.of(8, 9)) {
            var ready = new TechniqueProgress(stars, MasteryRules.need(stars), 0, 0, 10);
            for (var source : List.of(MasteryRules.Source.FIGHT, MasteryRules.Source.TRAINING)) {
                var gained = MasteryRules.experience(ready, source, 1, 0, 0, 10).progress();
                assertEquals(stars, gained.stars());
                assertTrue(gained.progress() >= ready.progress());
            }
            assertEquals(stars, MasteryRules.study(ready, 0, 0, 10, false).progress().stars());
            assertEquals(stars + 1, MasteryRules.study(ready, 0, 0, 10, true).progress().stars());
            assertEquals(stars + 1, MasteryRules.meditate(ready, 0, 0, 10).progress().stars());
            assertEquals(stars + 1, MasteryRules.experience(ready, MasteryRules.Source.SPARRING, 1, 0, 0, 10).progress().stars());
        }
        var torn = new TechniqueProgress(6, MasteryRules.need(6), 0, 0, 7);
        assertEquals(7, MasteryRules.study(torn, 0, 0, 10, false).progress().stars());
        assertEquals(8, MasteryRules.startStars(TechniqueTier.BASIC, 10, 1E9, 10));
        assertEquals(6, MasteryRules.startStars(TechniqueTier.BASIC, 7, 1E9, 10));
    }

    private static void assertRoundTrip(MasteryState state) {
        var saved = MasteryState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow().getAsJsonObject();
        assertEquals(TechniqueProgress.VERSION, saved.get("version").getAsInt());
        assertFalse(saved.toString().contains("\"layer\""));
        assertEquals(state, MasteryState.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow());
    }
}
