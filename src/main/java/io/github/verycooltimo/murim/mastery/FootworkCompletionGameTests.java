package io.github.verycooltimo.murim.mastery;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.FootworkService;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.technique.Styles;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Set;

/** Completion, enlightenment and real v3 save/book migration regressions. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class FootworkCompletionGameTests {
    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, name);
    }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void everyFootworkCompletesAtTenAndOtherArtsAtTwelve(GameTestHelper h) {
        int footworkCount = 0;
        for (var definition : TechniqueLoader.all().values()) {
            boolean footwork = "step".equals(definition.form()) || FootworkService.family(definition) != null
                    || Styles.FOOTWORK.stream().anyMatch(s -> s.forms().contains(definition.id()));
            int cap = footwork ? 10 : 12;
            h.assertTrue(definition.starCap() == cap && Arts.cap(definition.id()) == cap
                    && Arts.cap(Arts.masteryId(definition.id())) == cap, "wrong cap: " + definition.id());
            h.assertTrue(Arts.executionTier(definition.id(), cap) == Arts.legacySteps(definition.id()), "top tier lost");
            if (footwork) footworkCount++;
        }
        h.assertTrue(footworkCount >= 9, "standalone footwork forms were missed");
        h.assertTrue(StarDisplay.gauge(4, 10).equals("★★★★☆☆☆☆☆☆"), "HUD still uses twelve positions");
        h.assertTrue(Arts.cap(id("method/six_harmonies")) == 12, "breathing cap changed");
        h.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void insightWallStartsAtEightAndKeepsExperience(GameTestHelper h) {
        for (int cap : new int[] {10, 12}) {
            for (int star = cap - 2; star < cap; star++) {
                var ready = new TechniqueProgress(star, MasteryRules.need(star), 0, 0, cap);
                for (var source : new MasteryRules.Source[] {MasteryRules.Source.FIGHT, MasteryRules.Source.TRAINING}) {
                    var practiced = MasteryRules.experience(ready, source, 1, 0, 0, cap).progress();
                    h.assertTrue(practiced.stars() == star && practiced.progress() >= ready.progress(), "practice crossed wall or lost experience");
                }
                h.assertTrue(MasteryRules.study(ready, 0, 0, cap, false).progress().stars() == star, "page bypassed insight");
                h.assertTrue(MasteryRules.meditate(ready, 0, 0, cap).progress().stars() == star + 1, "meditation did not release experience");
                h.assertTrue(MasteryRules.experience(ready, MasteryRules.Source.SPARRING, 1, 0, 0, cap).progress().stars() == star + 1,
                        "sparring did not release experience");
                h.assertTrue(MasteryRules.study(ready, 0, 0, cap, true).progress().stars() == star + 1, "mentor review did not release experience");
            }
        }
        h.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void versionThreeSaveAndBooksRescaleOnlyFootworkOnce(GameTestHelper h) {
        var json = JsonParser.parseString("""
                {"version":3,"arts":{
                  "murim:wind_god":{"version":3,"stars":6,"progress":13,"unprocessed":9,"day":5,"cap":9},
                  "murim:seven_plum":{"version":3,"stars":11,"progress":17,"unprocessed":4,"day":5,"cap":12},
                  "murim:method/six_harmonies":{"version":3,"stars":12,"progress":0,"cap":12}},
                 "known":["murim:wind_god_steps","murim:wind_god_shadow","murim:seven_plum_basic","murim:method/six_harmonies"],
                 "wisdom":8,"comprehended":["murim:wind_god"]}
                """);
        var state = MasteryState.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        var p = state.progress(id("wind_god"));
        h.assertTrue(p.stars() == 5 && p.cap() == 8 && p.version() == 4, "v3 used torn limit as denominator");
        h.assertTrue(Math.abs(p.progress() / MasteryRules.need(5) - 13 / MasteryRules.need(6)) < 1E-9, "progress fraction lost");
        h.assertTrue(Math.abs(p.unprocessed() / MasteryRules.need(5) - 9 / MasteryRules.need(6)) < 1E-9, "pending fraction lost");
        h.assertTrue(state.techniques().size() == 4 && !state.knows(id("wind_god_death")), "migration opened forms");
        h.assertTrue(state.wisdom() == 8 && state.comprehended().equals(Set.of(id("wind_god"))), "understanding changed");
        h.assertTrue(state.progress(id("seven_plum")).equals(new TechniqueProgress(11, 17, 4, 5, 12)), "sword mastery changed");
        h.assertTrue(state.progress(id("method/six_harmonies")).stars() == 12, "method mastery changed");
        var encoded = MasteryState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow();
        h.assertTrue(state.equals(MasteryState.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow()), "v4 migrated twice");
        var completed = new TechniqueProgress(12, 0, 0, 0, 12, 3).migrate(id("wind_god_steps"));
        h.assertTrue(completed.stars() == 10 && Arts.executionTier(id("wind_god_steps"), 10) == 8, "completed footwork lost top tier");
        int top = Arts.executionTier(id("wind_god_steps"), 10);
        h.assertTrue(io.github.verycooltimo.murim.technique.FootworkFamily.windVfxTier(top) == 3, "top VFX tier lost");
        h.assertTrue(io.github.verycooltimo.murim.technique.TraverseRules.acceleratedSpeed(
                io.github.verycooltimo.murim.technique.FootworkFamily.WIND_GOD, top, 20) == 30.0D, "top run speed changed");
        h.assertTrue(Math.abs(MasteryRules.costFactor(10, 10) - MasteryRules.costFactor(12, 12)) < 1E-9,
                "completion changed relative qi cost");
        h.assertTrue(Math.abs(MasteryRules.powerFactor(10, 10) - MasteryRules.powerFactor(12, 12)) < 1E-9,
                "completion changed relative power");

        var book = new ItemStack(Items.PAPER);
        book.set(ModDataComponents.MANUAL_STARS.get(), 6);
        book.set(ModDataComponents.MANUAL_STAR_VERSION.get(), 3);
        h.assertTrue(ManualDepth.stars(book, id("wind_god_steps")) == 5, "v3 torn book not rescaled");
        h.assertTrue(ManualDepth.stars(book, id("seven_plum_basic")) == 6, "sword book changed");
        book.set(ModDataComponents.MANUAL_STAR_VERSION.get(), 4);
        h.assertTrue(ManualDepth.stars(book, id("wind_god_steps")) == 6, "v4 book migrated twice");
        book.set(ModDataComponents.MANUAL_STAR_VERSION.get(), 2);
        h.assertTrue(ManualDepth.stars(book, id("wind_god_steps")) == 6, "v2 book used /12 denominator");
        book.set(ModDataComponents.MANUAL_STARS.get(), 0);
        h.assertTrue(ManualDepth.stars(book, id("wind_god_steps")) == 0, "complete book became torn");
        h.succeed();
    }

    private FootworkCompletionGameTests() {}
}
