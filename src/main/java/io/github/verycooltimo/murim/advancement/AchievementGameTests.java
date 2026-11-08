package io.github.verycooltimo.murim.advancement;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.CultivationState;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.mastery.Arts;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.sect.SectGameTests;
import io.github.verycooltimo.murim.sect.SectProgression;
import io.github.verycooltimo.murim.sect.SectRoster;
import io.github.verycooltimo.murim.sect.SectService;
import io.github.verycooltimo.murim.sect.SectState;
import io.github.verycooltimo.murim.training.BodyRules;
import io.github.verycooltimo.murim.training.BodySource;
import io.github.verycooltimo.murim.training.TrainingRegistry;
import io.github.verycooltimo.murim.training.TrainingService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Gameplay regression coverage; no manual grants are used to complete an achievement.
 * API: the existing sect/AchievementGameTests.java uses getOrStartProgress/isDone on the same server-player fixture.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class AchievementGameTests {
    private AchievementGameTests() { }

    public static void done(GameTestHelper h, ServerPlayer p, String path) {
        var holder = p.getServer().getAdvancements().get(MurimAdvancements.id(path));
        h.assertTrue(holder != null, "Missing advancement: " + path);
        h.assertTrue(p.getAdvancements().getOrStartProgress(holder).isDone(), "Not completed: " + path);
        h.assertFalse(MurimAdvancements.grant(p, path), "Second award succeeded: " + path);
    }

    public static void pending(GameTestHelper h, ServerPlayer p, String path) {
        var holder = p.getServer().getAdvancements().get(MurimAdvancements.id(path));
        h.assertTrue(holder != null, "Missing advancement: " + path);
        h.assertFalse(p.getAdvancements().getOrStartProgress(holder).isDone(), "Premature completion: " + path);
    }

    /** Loaded displays exercise advancement decoding and item registration, including future additions.
     * API: client/FilmAdvancements and sect/AchievementGameTests; registry lookup: FunnyJunkGameTests.
     */
    @GameTest(template = "small_floor", timeoutTicks = 40)
    public static void everyDisplayedAchievementUsesRegisteredMurimIcon(GameTestHelper h) {
        var displayed = h.getLevel().getServer().getAdvancements().getAllAdvancements().stream()
                .filter(a -> MurimMod.MODID.equals(a.id().getNamespace()) && a.value().display().isPresent())
                .toList();
        h.assertTrue(displayed.size() >= 23, "Expected at least 23 displayed Murim advancements, got " + displayed.size());
        for (var advancement : displayed) {
            var icon = advancement.value().display().orElseThrow().getIcon();
            h.assertFalse(icon.isEmpty(), "Empty icon: " + advancement.id());
            var itemId = BuiltInRegistries.ITEM.getKey(icon.getItem());
            h.assertTrue(MurimMod.MODID.equals(itemId.getNamespace()),
                    "Non-Murim icon for " + advancement.id() + ": " + itemId);
            h.assertTrue(BuiltInRegistries.ITEM.containsKey(itemId),
                    "Unregistered icon for " + advancement.id() + ": " + itemId);
        }
        h.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 40)
    public static void starsFollowRealMasteryRewards(GameTestHelper h) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        var method = MurimAdvancements.id("six_harmonies");
        var art = Arts.methodArt(method);
        p.setData(ModAttachments.CULTIVATION, CultivationState.NONE.withMethod(method));
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(art, TechniqueProgress.learned(0, 12)));
        pending(h, p, "stars_1");
        for (int star = 1; star <= 12; star++) {
            MasteryService.rankBreathingStar(p);
            h.assertTrue(MasteryService.stars(p, art) == star, "Mastery reward did not add a star");
            if (star == 1) done(h, p, "stars_1");
            if (star == 5) pending(h, p, "stars_6");
            if (star == 6) done(h, p, "stars_6");
            if (star == 11) pending(h, p, "stars_12");
        }
        done(h, p, "stars_12");
        try (var stream = AchievementGameTests.class.getResourceAsStream("/data/murim/advancement/stars_12.json")) {
            h.assertTrue(stream != null, "Missing twelve-star resource");
            var json = com.google.gson.JsonParser.parseString(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            h.assertTrue(json.getAsJsonObject().get("parent").getAsString().equals("murim:stars_6"), "Wrong twelve-star parent");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        h.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 40)
    public static void bodyWorkCrossesBothStageGates(GameTestHelper h) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        pending(h, p, "body_first");
        TrainingService.credit(p, BodySource.LEGS, 20.0D);
        TrainingService.credit(p, BodySource.LABOUR, 20.0D);
        done(h, p, "body_first");
        pending(h, p, "body_ready");
        // Set only the precondition; the actual crossing must come from credited gameplay work.
        p.setData(TrainingRegistry.BODY, p.getData(TrainingRegistry.BODY).withPoints(
                BodyRules.threshold(BodyRules.breakthroughNeed(io.github.verycooltimo.murim.cultivation.Realm.FIRST)) - 1.0D));
        TrainingService.credit(p, BodySource.FIGHT, 2.0D);
        done(h, p, "body_ready");
        h.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 40)
    public static void countedSparWinsRecognizeOnlyThePersonalTeacher(GameTestHelper h) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        p.setData(ModAttachments.SECT, SectState.NONE.joined().with(SectProgression.DIRECT));
        SectDisciple npc = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        npc.setMember(SectRoster.of("seo_rang").orElseThrow());
        SectService.onSparEnd(p, npc, false);
        pending(h, p, "spar_win");
        SectService.onSparEnd(p, npc, true);
        done(h, p, "spar_win");
        pending(h, p, "spar_teacher");
        npc.setMember(SectRoster.of(SectProgression.MENTOR).orElseThrow());
        SectService.onSparEnd(p, npc, false);
        pending(h, p, "spar_teacher");
        p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).without(SectProgression.DIRECT));
        SectService.onSparEnd(p, npc, true);
        pending(h, p, "spar_teacher");
        p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).with(SectProgression.DIRECT));
        SectService.onSparEnd(p, npc, true);
        done(h, p, "spar_teacher");
        h.succeed();
    }
}
