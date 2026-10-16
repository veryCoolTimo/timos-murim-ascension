package io.github.verycooltimo.murim.sect;

import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.CultivationState;
import io.github.verycooltimo.murim.cultivation.MeditationService;
import io.github.verycooltimo.murim.cultivation.RingMinigame;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.boss.BossRegistry;
import io.github.verycooltimo.murim.item.TechniqueManualItem;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.commands.Commands;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Set;

/** Inventory, loaded dialogue actions, real meditation ticks and saved attachments; no client channel required. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectJourneyGameTests {
    private SectJourneyGameTests() { }

    private static ServerPlayer newcomer(GameTestHelper h) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        Vec3 at = h.absoluteVec(new Vec3(12.5, 2, 12.5));
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.xOld = p.getX(); p.zOld = p.getZ(); p.setOnGround(true);
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL);
        p.setData(ModAttachments.CULTIVATION, CultivationState.NONE);
        SectService.join(p);
        return p;
    }

    private static SectDisciple mentor(GameTestHelper h, ServerPlayer p) {
        SectDisciple npc = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        npc.setMember(SectRoster.of("gyeong_pil").orElseThrow());
        npc.moveTo(p.getX() + 1, p.getY(), p.getZ(), 0, 0);
        npc.setNoAi(true); npc.setKeepAwake(true);
        h.getLevel().addFreshEntity(npc);
        return npc;
    }

    private static SectState roundTrip(SectState state) {
        return SectState.CODEC.parse(JsonOps.INSTANCE, SectState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow()).getOrThrow();
    }

    private static void goal(GameTestHelper h, ServerPlayer p, String id) {
        h.assertTrue(SectJourney.next(p).id().equals(id), "expected " + id + ", got " + SectJourney.next(p));
        h.assertTrue(SectJourney.line(p).getContents() instanceof TranslatableContents t
                && t.getKey().equals("murim.sect.journey.next") && t.getArgs().length == 3,
                "goal lacks action, blocker or contact");
    }

    private static void select(ServerPlayer p, java.util.function.Predicate<ItemStack> wanted) {
        for (int i = 0; i < p.getInventory().items.size(); i++) {
            if (wanted.test(p.getInventory().items.get(i))) {
                if (i > 8) {
                    ItemStack previous = p.getInventory().items.get(0);
                    p.getInventory().items.set(0, p.getInventory().items.get(i));
                    p.getInventory().items.set(i, previous);
                    i = 0;
                }
                p.getInventory().selected = i;
                return;
            }
        }
        throw new IllegalStateException("missing teaching item");
    }

    private static void finish(GameTestHelper h, ServerPlayer p, SectDisciple mentor, String flag) {
        Dialogue.Option option = DialogueLoader.get(mentor.dialogue()).nodes().get("lessons").options().stream()
                .filter(o -> o.actions().stream().anyMatch(a -> a.type().equals("set_flag") && a.value().orElse("").equals(flag)))
                .findFirst().orElseThrow();
        h.assertTrue(DialogueService.applyHeadless(p, mentor, option), "milestone dialogue action rejected: " + flag);
        int merit = SectService.state(p).merit();
        h.assertTrue(!DialogueService.applyHeadless(p, mentor, option) && SectService.state(p).merit() == merit,
                "milestone reward repeated: " + flag);
    }

    @GameTest(template = "sect_yard", batch = "sect_journey", timeoutTicks = 100)
    public static void newcomerGetsMethodSeedAndBasicsOnce(GameTestHelper h) {
        ServerPlayer p = newcomer(h); SectDisciple mentor = mentor(h, p);
        goal(h, p, "method");
        h.assertTrue(!MeditationService.start(p, true), "meditation without method accepted");
        var help = DialogueLoader.get(mentor.dialogue()).nodes().get("journey").options().stream()
                .filter(o -> o.actions().stream().anyMatch(a -> a.type().equals("journey_help")))
                .findFirst().orElseThrow();
        h.assertTrue(DialogueService.applyHeadless(p, mentor, help), "mentor did not issue scroll");
        int scrolls = new SectService.ItemNeed(ModItems.METHOD_SCROLL.get(), 1).count(p);
        p.setData(ModAttachments.SECT, roundTrip(SectService.state(p)));
        h.assertTrue(DialogueService.applyHeadless(p, mentor, help), "repeat help with owned scroll rejected");
        h.assertTrue(scrolls == 1 && new SectService.ItemNeed(ModItems.METHOD_SCROLL.get(), 1).count(p) == 1, "duplicate method scroll");
        goal(h, p, "read_method");
        select(p, stack -> stack.is(ModItems.METHOD_SCROLL.get()));
        ModItems.METHOD_SCROLL.get().use(h.getLevel(), p, InteractionHand.MAIN_HAND);
        goal(h, p, "seed_feel");
        h.assertTrue(!DialogueService.applyHeadless(p, mentor, help), "method owner received another reward");
        h.assertTrue(!p.getData(ModAttachments.PROFILE).isAwakened(), "mentor created a free seed");
        h.assertTrue(MasteryService.learn(p, SectService.SIX, 0) == MasteryService.Learn.NO_DANTIAN, "technique gate bypassed");
        h.assertTrue(MeditationService.start(p, true), "seed practice could not start");
        p.getRandom().setSeed(37);
        for (int i = 0; i < 1500 && !p.getData(ModAttachments.CULTIVATION).seeded(); i++) {
            var state = p.getData(ModAttachments.MEDITATION);
            int beat = p.getData(ModAttachments.CULTIVATION).beats();
            if (beat > 0) MeditationService.setHolding(p, state.ring().radius() + state.ring().surge() * 0.9
                    > RingMinigame.bandCentre(beat, state.ring().ticks() + 1));
            MeditationService.tick(p);
        }
        h.assertTrue(p.getData(ModAttachments.CULTIVATION).seeded() && p.getData(ModAttachments.PROFILE).isAwakened(), "real seed practice did not finish");
        h.assertTrue(p.getData(ModAttachments.PROFILE).rank() == 0, "newcomer received a free rank");
        goal(h, p, "learn_six");
        select(p, stack -> SectService.SIX.equals(stack.get(ModDataComponents.TECHNIQUE.get())));
        TechniqueManualItem.learnFromHand(p, InteractionHand.MAIN_HAND, SectService.SIX);
        h.assertTrue(MasteryService.knows(p, SectService.SIX), "issued manual could not teach the basics");
        var profile = p.getData(ModAttachments.PROFILE);
        int merit = SectService.state(p).merit();
        SectService.join(p);
        h.assertTrue(!SectJourney.help(p, mentor) && p.getData(ModAttachments.PROFILE).equals(profile)
                && SectService.state(p).merit() == merit, "repeated conversation changed seed or income");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_journey", timeoutTicks = 100)
    public static void lostScrollRecoversKnowledgeWithoutItemsOrIncome(GameTestHelper h) {
        ServerPlayer p = newcomer(h); SectDisciple mentor = mentor(h, p);
        h.assertTrue(SectJourney.help(p, mentor), "first scroll missing");
        p.getInventory().clearContent();
        p.setData(ModAttachments.SECT, roundTrip(SectService.state(p)));
        h.assertTrue(SectJourney.help(p, mentor), "lost scroll left newcomer stuck");
        h.assertTrue(p.getData(ModAttachments.CULTIVATION).method().orElseThrow().equals(SectService.SIX)
                && !p.getData(ModAttachments.CULTIVATION).seeded(), "recovery granted more than the method");
        h.assertTrue(new SectService.ItemNeed(ModItems.METHOD_SCROLL.get(), 1).count(p) == 0
                && SectService.state(p).contribution() == 0 && !SectJourney.help(p, mentor), "recovery created saleable items or income");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_journey", timeoutTicks = 100)
    public static void fullInventoryDoesNotSpendTheScrollClaimAndOffhandDoesNotDuplicate(GameTestHelper h) {
        ServerPlayer p = newcomer(h); SectDisciple mentor = mentor(h, p);
        for (int i = 0; i < p.getInventory().items.size(); i++)
            p.getInventory().items.set(i, new ItemStack(Items.COBBLESTONE, 64));
        h.assertTrue(!SectJourney.help(p, mentor) && !SectService.state(p).has(SectJourney.SCROLL_GIVEN), "full inventory lost scroll claim");
        p.getInventory().items.set(0, ItemStack.EMPTY);
        h.assertTrue(SectJourney.help(p, mentor), "claim did not recover after clearing a slot");
        ItemStack scroll = p.getInventory().items.get(0);
        p.getInventory().items.set(0, ItemStack.EMPTY);
        p.setItemInHand(InteractionHand.OFF_HAND, scroll);
        p.setData(ModAttachments.SECT, SectService.state(p).without(SectJourney.SCROLL_GIVEN));
        h.assertTrue(SectJourney.help(p, mentor) && new SectService.ItemNeed(ModItems.METHOD_SCROLL.get(), 1).count(p) == 0
                && p.getOffhandItem().getCount() == 1, "offhand scroll was duplicated");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_journey", timeoutTicks = 100)
    public static void existingMethodCentreAndLateJoinHaveNoDuplicateOrCalendarGate(GameTestHelper h) {
        ServerPlayer p = newcomer(h); SectDisciple mentor = mentor(h, p);
        ResourceLocation other = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "street_strength");
        p.setData(ModAttachments.CULTIVATION, CultivationState.NONE.withMethod(other).withBeats(1));
        h.assertTrue(!SectJourney.help(p, mentor) && p.getData(ModAttachments.CULTIVATION).method().orElseThrow().equals(other), "existing method replaced");
        goal(h, p, "seed_hold");
        p.setData(ModAttachments.CULTIVATION, CultivationState.NONE);
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withTags("pure", "existing").withRank(3));
        h.assertTrue(!SectJourney.help(p, mentor), "existing centre got a scroll");
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL);
        long previousTime = h.getLevel().getDayTime();
        h.getLevel().setDayTime(24000L * 400 + 14000);
        ServerPlayer senior = SectGameTests.fakePlayer(h);
        senior.setData(ModAttachments.SECT, SectState.NONE.joined().with(SectStanding.LESSON_TWO).contribute(150));
        h.assertTrue(SectJourney.help(p, mentor), "late joiner waited for server week or seniors");
        h.getLevel().setDayTime(previousTime);
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_journey", timeoutTicks = 100)
    public static void goalFollowsLoadedLessonMilestonesAndRealRankBlockers(GameTestHelper h) {
        ServerPlayer p = newcomer(h); SectDisciple mentor = mentor(h, p);
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withTags("pure", "murim:six_harmonies"));
        p.setData(ModAttachments.CULTIVATION, CultivationState.NONE.withMethod(SectService.SIX).withBeats(3));
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(SectService.SIX, TechniqueProgress.learned(2, 6)));
        goal(h, p, "take_six");
        p.setData(ModAttachments.SECT, SectService.state(p).with("lesson.six"));
        goal(h, p, "rank_one");
        h.assertTrue(SectJourney.next(p).blocker().getContents() instanceof TranslatableContents t
                && t.getKey().endsWith(".wall"), "real wall blocker missing");
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withRank(1));
        goal(h, p, "submit_six"); finish(h, p, mentor, SectStanding.LESSON_ONE);
        goal(h, p, "learn_petal");
        p.setData(ModAttachments.MASTERY, p.getData(ModAttachments.MASTERY).with(SectJourney.PETAL, TechniqueProgress.learned(0, 6)));
        goal(h, p, "take_spar");
        p.setData(ModAttachments.SECT, SectService.state(p).with(SectService.LESSON_SPAR));
        goal(h, p, "clean_hits");
        p.setData(ModAttachments.SECT, SectService.state(p).with(SectService.SPAR_CLEAN));
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withPool(io.github.verycooltimo.murim.cultivation.Realm.wall(p.getData(ModAttachments.PROFILE))));
        goal(h, p, "rank_two");
        h.assertTrue(SectJourney.next(p).blocker().getContents() instanceof TranslatableContents t
                && t.getKey().endsWith(".boss"), "rank 2 forgot the fortress master");
        p.setData(BossRegistry.BOSS_DEFEATED, true);
        h.assertTrue(SectJourney.next(p).blocker().getContents() instanceof TranslatableContents t
                && t.getKey().endsWith(".breakthrough"), "boss proof did not clear blocker");
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withRank(2));
        goal(h, p, "submit_spar"); finish(h, p, mentor, SectStanding.LESSON_TWO);
        goal(h, p, "learn_seven");
        p.setData(ModAttachments.MASTERY, p.getData(ModAttachments.MASTERY).with(SectJourney.SEVEN, TechniqueProgress.learned(0, 10)));
        goal(h, p, "request");
        p.setData(ModAttachments.SECT, SectService.state(p).with("request.pills.active")); goal(h, p, "deliver_pills");
        p.setData(ModAttachments.SECT, SectService.state(p).without("request.pills.active").with("request.pills.done"));
        goal(h, p, "partners");
        for (String partner : java.util.List.of("gyeong_on", "gyeong_dan", "gyeong_ha"))
            p.setData(ModAttachments.SECT, SectService.state(p).with("mentor.partner." + partner));
        goal(h, p, "direct");
        p.setData(ModAttachments.SECT, SectService.state(p).with("mentor.ceremony")); goal(h, p, "ceremony");
        p.setData(ModAttachments.SECT, SectService.state(p).without("mentor.ceremony").with(SectProgression.DIRECT));
        goal(h, p, "standing");
        p.setData(ModAttachments.SECT, SectService.state(p).contribute(30)); goal(h, p, "approval");
        p.setData(ModAttachments.SECT, SectService.state(p).with(SectStanding.APPROVAL)); goal(h, p, "personal_lesson");
        p.setData(ModAttachments.MASTERY, p.getData(ModAttachments.MASTERY).with(SectService.SIX, TechniqueProgress.learned(12, 12)));
        goal(h, p, "free_practice");
        h.assertTrue(DialogueService.validate(DialogueLoader.get(mentor.dialogue())).isEmpty(), "invalid mentor dialogue");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_journey", timeoutTicks = 100)
    public static void absenceAndLegacyChoresPreserveProgressAndWelcomeBack(GameTestHelper h) {
        ServerPlayer p = newcomer(h);
        SectState before = SectService.state(p).contribute(20).with(SectStanding.LESSON_ONE);
        p.setData(ModAttachments.SECT, before);
        long day = SectSchedule.day(p.level().getDayTime());
        var incomplete = new SectAttendance.Day(day - 100, Set.of("formation", "lesson"), Set.of());
        var old = new SectAttendance.Log(incomplete, SectAttendance.Day.NONE, 6, 2, true, Long.MIN_VALUE);
        var oldJson = SectAttendance.Log.CODEC.encodeStart(JsonOps.INSTANCE, old).getOrThrow().getAsJsonObject();
        oldJson.addProperty("missed_row", 2); oldJson.addProperty("chores", true);
        var saved = SectAttendance.Log.CODEC.parse(JsonOps.INSTANCE, oldJson).getOrThrow();
        p.setData(ModAttachments.SECT_ATTENDANCE, saved);
        var current = SectAttendance.current(p);
        h.assertTrue(current.streak() == 6 && !current.chores() && current.missedRow() == 0, "absence punished diligence");
        h.assertTrue(SectService.state(p).equals(before) && SectService.standing(p) == SectStanding.DISCIPLE, "absence changed standing or progress");
        h.assertTrue(SectAttendance.verdict(p).getContents() instanceof TranslatableContents t
                && t.getKey().endsWith(".away"), "returning player was not welcomed");
        var full = new SectAttendance.Day(day, Set.of("formation"), Set.of("formation"));
        var settled = SectAttendance.roll(new SectAttendance.Log(full, incomplete, current.streak(), 0, false, Long.MIN_VALUE), day + 1);
        h.assertTrue(settled.log().streak() == 7 && settled.bonus() > 0, "catch-up full day lost bonus");
        h.assertTrue(SectAttendance.roll(settled.log(), day + 1).bonus() == 0, "full-day bonus repeated");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_journey", timeoutTicks = 100)
    public static void logIsPublicAndAdminCommandsStayProtected(GameTestHelper h) {
        var dispatcher = new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
        dispatcher.register(Commands.literal("murim").requires(s -> s.hasPermission(2))
                .then(Commands.literal("sect").then(Commands.literal("log").executes(c -> 1))
                        .then(Commands.literal("reset").executes(c -> 2)))
                .then(Commands.literal("rank").executes(c -> 3)));
        SectCommand.makeLogPublic(dispatcher);
        ServerPlayer p = newcomer(h);
        var source = p.createCommandSourceStack().withPermission(0);
        try {
            h.assertTrue(dispatcher.execute("murim sect log", source) == 1, "public log inaccessible");
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) { throw new IllegalStateException(e); }
        h.assertTrue(dispatcher.parse("murim sect reset", source).getReader().canRead()
                && dispatcher.parse("murim rank", source).getReader().canRead(), "admin branches opened to ordinary player");
        h.assertTrue(dispatcher.getRoot().getChild("murim").getChild("rank").canUse(source.withPermission(2)), "admin access lost");
        h.succeed();
    }
}
