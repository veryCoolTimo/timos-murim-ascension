package io.github.verycooltimo.murim.mastery;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.technique.Styles;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.Map;
import java.util.Set;

/** Regression contracts for real save codecs, learned forms, caps, pacing and HUD notation. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class StarMasteryGameTests {
    private static ResourceLocation id(String name) { return ResourceLocation.fromNamespaceAndPath("murim", name); }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void legacySaveMigratesOnceWithoutOpeningForms(GameTestHelper h) {
        var json = JsonParser.parseString("""
                {"techniques": {
                  "murim:seven_plum_basic": {"layer": 4, "progress": 13, "unprocessed": 9, "day": 5, "cap": 8},
                  "murim:seven_plum_blossoms": {"layer": 2, "progress": 7, "day": 5, "cap": 3}
                }, "wisdom": 8, "comprehended": ["murim:seven_plum_blossoms"]}
                """);
        MasteryState state = MasteryState.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        TechniqueProgress p = state.progress(id("seven_plum"));
        h.assertTrue(p.stars() == 6 && p.cap() == 12, "4/8 must become 6/12 stars");
        h.assertTrue(Math.abs(p.progress() / MasteryRules.need(6) - 13 / MasteryRules.need(4)) < 1E-9,
                "next-star fraction was lost");
        h.assertTrue(Math.abs(p.unprocessed() / MasteryRules.need(6) - 9 / MasteryRules.need(4)) < 1E-9,
                "pending experience was lost");
        h.assertTrue(state.techniques().get(id("seven_plum_basic")).equals(state.techniques().get(id("seven_plum_blossoms"))),
                "foundation and form must share mastery");
        h.assertFalse(state.knows(id("seven_plum_execution")), "migration opened an unknown form");
        h.assertTrue(state.wisdom() == 8 && state.comprehended().equals(Set.of(id("seven_plum"))), "wisdom was duplicated");
        var saved = MasteryState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow().getAsJsonObject();
        h.assertTrue(saved.get("version").getAsInt() == TechniqueProgress.VERSION && saved.getAsJsonObject("arts").size() == 1,
                "save still contains per-form experience");
        h.assertFalse(saved.toString().contains("\"layer\""), "new save writes legacy layer fields");
        h.assertTrue(state.equals(MasteryState.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow()), "migration ran twice");
        h.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void allStylesShareOnlyKnownFormsAndKeepCaps(GameTestHelper h) {
        for (var style : Styles.ALL) {
            if (Styles.individualBooks(style)) continue; // a curriculum, not one art
            ResourceLocation a = style.basic().orElse(style.forms().get(0));
            ResourceLocation b = style.forms().get(style.basic().isPresent() ? 0 : 1);
            int cap = Arts.cap(a);
            MasteryState state = MasteryState.EMPTY.with(a, TechniqueProgress.learned(2, cap));
            h.assertFalse(state.knows(b), "progress granted a form without a manual");
            state = state.with(b, state.progress(a));
            var gain = MasteryRules.study(state.progress(b), MasteryRules.need(2), 0, cap, false);
            state = state.with(b, gain.progress());
            h.assertTrue(state.techniques().get(a).equals(state.techniques().get(b)), "practice diverged by form");
            h.assertTrue(state.arts().size() == 1 && state.progress(a).stars() == 3, "art practice was duplicated");
        }
        for (var definition : io.github.verycooltimo.murim.technique.TechniqueLoader.all().values()) {
            boolean footwork = "step".equals(definition.form())
                    || io.github.verycooltimo.murim.combat.FootworkService.family(definition) != null
                    || Styles.FOOTWORK.stream().anyMatch(s -> s.forms().contains(definition.id()));
            int expectedCap = footwork ? 10 : 12;
            h.assertTrue(definition.starCap() == expectedCap && Arts.cap(definition.id()) == expectedCap,
                    "wrong art cap: " + definition.id());
        }
        for (var method : io.github.verycooltimo.murim.cultivation.MethodLoader.all().values()) {
            h.assertTrue(method.starCap() == 12 && Arts.cap(Arts.methodArt(method.id())) == 12, "non-universal method cap");
        }
        for (ResourceLocation form : io.github.verycooltimo.murim.technique.TechniqueLoader.all().keySet()) {
            int oldMaximum = Arts.legacySteps(form);
            for (int old = 0; old <= oldMaximum; old++) {
                int stars = (int) Math.round(old / (double) oldMaximum * Arts.cap(form));
                h.assertTrue(Arts.executionTier(form, stars) == old, "migration lost an execution tier: " + form + " at " + old);
            }
        }
        var torn = new TechniqueProgress(2, 0, 0, 0, 2, 0).migrate(id("wind_god_steps"));
        h.assertTrue(torn.stars() == 3 && torn.cap() == 3, "torn save used manuscript as full denominator");
        h.assertTrue(Arts.executionTier(id("wind_god_steps"), torn.stars()) == 2, "torn book granted maximum power");
        var bounded = MasteryState.EMPTY.with(id("wind_god_steps"), TechniqueProgress.learned(12, 12));
        h.assertTrue(bounded.progress(id("wind_god_steps")).stars() == 10
                && bounded.progress(id("wind_god_steps")).cap() == 10, "progress exceeded the art cap");
        var state = MasteryState.EMPTY.with(Arts.methodArt(id("six_harmonies")), TechniqueProgress.learned(6, 6));
        h.assertTrue(state.executionTiers().isEmpty(), "breathing mastery bypasses combat rank gates");
        h.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void finalStarsNeedInsightAndHudShowsTheArtCap(GameTestHelper h) {
        TechniqueProgress initial = TechniqueProgress.learned(10, 12);
        var practice = MasteryRules.experience(initial, MasteryRules.Source.TRAINING,
                MasteryRules.need(10) * 20, 0, 0, 12).progress();
        h.assertTrue(practice.stars() == 10 && practice.progress() >= MasteryRules.need(10), "training crossed the insight wall");
        var study = MasteryRules.study(practice, 1, 0, 12, false).progress();
        h.assertTrue(study.stars() == 10, "pages bypass the final insight wall");
        var reflected = MasteryRules.meditate(practice, 0, 0).progress();
        h.assertTrue(reflected.stars() > 10, "meditation did not release stored experience");
        var sparred = MasteryRules.experience(practice, MasteryRules.Source.SPARRING, 1, 0, 0, 12).progress();
        h.assertTrue(sparred.stars() > 10, "sparring did not release stored experience");
        var bookPractice = MasteryRules.study(initial, MasteryRules.need(10), 0, 12, false).progress();
        h.assertTrue(MasteryRules.meditate(bookPractice, 0, 1).progress().stars() == 11,
                "meditation lost a completed insight wall when pending experience cooled");
        h.assertTrue(StarDisplay.gauge(4, 12).equals("★★★★☆☆☆☆☆☆☆☆"), "wrong twelve-star HUD text");
        h.assertTrue(StarDisplay.gauge(0, 12).equals("☆☆☆☆☆☆☆☆☆☆☆☆") && StarDisplay.gauge(12, 12).equals("★★★★★★★★★★★★"),
                "HUD does not show twelve positions");
        h.assertTrue(Arts.executionTier(id("wind_god_steps"), 0) == 0, "zero stars emit mastered VFX");
        h.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void perArtStarSavesRemigrateOnceWithTornLimits(GameTestHelper h) {
        var json = JsonParser.parseString("""
                {"version": 2, "arts": {
                  "murim:seven_plum": {"version": 2, "stars": 5, "progress": 13, "unprocessed": 9, "day": 5, "cap": 7},
                  "murim:method/six_harmonies": {"version": 2, "stars": 6, "progress": 0, "cap": 6}
                }, "known": ["murim:seven_plum_basic", "murim:seven_plum_blossoms", "murim:method/six_harmonies"],
                   "wisdom": 8, "comprehended": ["murim:seven_plum"]}
                """);
        MasteryState state = MasteryState.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        TechniqueProgress p = state.progress(id("seven_plum"));
        h.assertTrue(p.stars() == 6 && p.cap() == 8 && p.version() == TechniqueProgress.VERSION, "5/10 or torn 7/10 remigrated incorrectly");
        h.assertTrue(Math.abs(p.progress() / MasteryRules.need(6) - 13 / MasteryRules.need(5)) < 1E-9, "progress fraction lost");
        h.assertTrue(Math.abs(p.unprocessed() / MasteryRules.need(6) - 9 / MasteryRules.need(5)) < 1E-9, "pending fraction lost");
        h.assertTrue(state.progress(id("method/six_harmonies")).stars() == 12, "cultivation mastery lost");
        h.assertTrue(state.techniques().size() == 3 && !state.knows(id("seven_plum_execution")), "migration opened forms");
        h.assertTrue(state.wisdom() == 8 && state.comprehended().equals(Set.of(id("seven_plum"))), "wisdom/comprehension changed");
        var saved = MasteryState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow();
        h.assertTrue(state.equals(MasteryState.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow()), "current save remigrated twice");
        for (String form : java.util.List.of("bandit_rush", "six_harmonies", "wind_god_steps", "four_horizons")) {
            ResourceLocation id = id(form);
            int previous = Arts.previousStarMaximum(id, 12, 0);
            for (int old = 0; old <= previous; old++) {
                var migrated = new TechniqueProgress(old, 0, 0, 0, previous, 2).migrate(id);
                h.assertTrue(migrated.stars() == Math.round(old / (double) previous * Arts.cap(id)), "wrong proportional star conversion");
                int formerTier = 0;
                for (int step = 1; step <= Arts.legacySteps(id); step++) {
                    if (old >= Math.round(step / (double) Arts.legacySteps(id) * previous)) formerTier = step;
                }
                h.assertTrue(Arts.executionTier(id, migrated.stars()) >= formerTier, "rounding closed a mastered ability");
            }
        }
        var paper = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER);
        paper.set(io.github.verycooltimo.murim.registry.ModDataComponents.MANUAL_STARS.get(), 5);
        h.assertTrue(ManualDepth.stars(paper, id("six_harmonies")) == 10, "old torn book became shallower");
        paper.set(io.github.verycooltimo.murim.registry.ModDataComponents.MANUAL_STAR_VERSION.get(), 3);
        h.assertTrue(ManualDepth.stars(paper, id("six_harmonies")) == 5, "new torn book remigrated");
        h.succeed();
    }

    private StarMasteryGameTests() {}
}
