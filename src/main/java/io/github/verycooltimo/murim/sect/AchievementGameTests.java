package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.advancement.MurimAdvancements;
import io.github.verycooltimo.murim.quest.Quests;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The Mount Hua achievements load, and the helper and the hooks grant them. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class AchievementGameTests {

    private AchievementGameTests() {
    }

    @GameTest(template = "sect_yard", timeoutTicks = 40, batch = "achievements")
    public static void everyAchievementLoadsAndGrants(GameTestHelper helper) {
        ServerPlayer p = SectGameTests.fakePlayer(helper);
        var all = helper.getLevel().getServer().getAdvancements().getAllAdvancements().stream()
                .filter(a -> a.id().getNamespace().equals(MurimMod.MODID)).toList();
        helper.assertTrue(all.size() >= 12, "murim advancements loaded: " + all.size());
        helper.assertTrue(MurimAdvancements.grant(p, "join"), "join granted");
        helper.assertFalse(MurimAdvancements.grant(p, "join"), "second grant is a no-op");
        SectPilfer.took(p, "supplies");
        AdvancementHolder sticky = helper.getLevel().getServer().getAdvancements().get(MurimAdvancements.id("sticky_fingers"));
        helper.assertTrue(sticky != null && p.getAdvancements().getOrStartProgress(sticky).isDone(), "sticky_fingers is done after a pilfer");
        MurimAdvancements.stars(p, 12);
        MurimAdvancements.rank(p, 4);
        Quests.set(p, "quest.debt.done");
        helper.assertTrue(MurimAdvancements.grant(p, "stars_12") == false, "stars_12 already granted");
        for (String id : new String[] {"rank_peak"}) {
            AdvancementHolder h = helper.getLevel().getServer().getAdvancements().get(MurimAdvancements.id(id));
            helper.assertTrue(h != null && p.getAdvancements().getOrStartProgress(h).isDone(), id + " is done");
        }
        AdvancementHolder allQuests = helper.getLevel().getServer().getAdvancements().get(MurimAdvancements.id("all_quests"));
        helper.assertFalse(p.getAdvancements().getOrStartProgress(allQuests).isDone(), "parked stories must not complete the fragment criterion");
        helper.succeed();
    }

    /** The stele is parked (23.10): the fragments errand is done with the four fragments that can still be found. */
    @GameTest(template = "sect_yard", timeoutTicks = 40, batch = "achievements")
    public static void fragmentsErrandDoesNotNeedTheParkedSteleFragments(GameTestHelper helper) {
        helper.assertTrue(MurimAdvancements.requiredFragments().equals(java.util.List.of(0, 1, 3, 5)), "required: " + MurimAdvancements.requiredFragments());
        ServerPlayer p = SectGameTests.fakePlayer(helper);
        AdvancementHolder allQuests = helper.getLevel().getServer().getAdvancements().get(MurimAdvancements.id("all_quests"));
        try (var stream = AchievementGameTests.class.getResourceAsStream("/data/murim/advancement/all_quests.json")) {
            helper.assertTrue(stream != null, "missing all_quests resource");
            var json = com.google.gson.JsonParser.parseString(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            helper.assertTrue(json.getAsJsonObject("display").get("hidden").getAsBoolean(), "all_quests is visible");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        helper.assertTrue(allQuests.value().criteria().keySet().equals(java.util.Set.of("fragments")), "parked criteria remain");
        for (int i : java.util.List.of(0, 1, 3)) Quests.set(p, "clue.frag_" + i);
        helper.assertFalse(p.getAdvancements().getOrStartProgress(allQuests).getCriterion("fragments").isDone(), "done with three fragments");
        Quests.set(p, "clue.frag_5");
        helper.assertTrue(p.getAdvancements().getOrStartProgress(allQuests).getCriterion("fragments").isDone(), "not done with F0, F1, F3, F5");
        helper.succeed();
    }
}
