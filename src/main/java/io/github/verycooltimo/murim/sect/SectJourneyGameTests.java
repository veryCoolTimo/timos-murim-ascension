package io.github.verycooltimo.murim.sect;

import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.technique.Styles;
import io.github.verycooltimo.murim.cultivation.CultivationState;
import io.github.verycooltimo.murim.cultivation.MeditationService;
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

    private static ServerPlayer newcomer(GameTestHelper h) { return newcomer(h, true); }

    private static ServerPlayer newcomer(GameTestHelper h, boolean recoverScroll) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        Vec3 at = h.absoluteVec(new Vec3(12.5, 2, 12.5));
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.xOld = p.getX(); p.zOld = p.getZ(); p.setOnGround(true);
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL);
        p.setData(ModAttachments.CULTIVATION, CultivationState.NONE);
        SectService.join(p);
        if (!recoverScroll) return p;
        // Joining already hands out the scroll (doc 39 item 2); these tests exercise the mentor's recovery path.
        for (int i = 0; i < p.getInventory().items.size(); i++) {
            if (p.getInventory().items.get(i).is(ModItems.METHOD_SCROLL.get())) p.getInventory().items.set(i, ItemStack.EMPTY);
        }
        p.setData(ModAttachments.SECT, SectService.state(p).without(SectJourney.SCROLL_GIVEN));
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
        if (flag.equals("lesson.six.done")) completeDrill(h, p);
        h.assertTrue(DialogueService.applyHeadless(p, mentor, option), "milestone dialogue action rejected: " + flag);
        int merit = SectService.state(p).merit();
        h.assertTrue(!DialogueService.applyHeadless(p, mentor, option) && SectService.state(p).merit() == merit,
                "milestone reward repeated: " + flag);
    }

    private static void completeDrill(GameTestHelper h, ServerPlayer p) {
        var strike = io.github.verycooltimo.murim.technique.Styles.SIX_HARMONIES.forms().getFirst();
        MasteryService.learn(p, strike, 0);
        var dummy = new io.github.verycooltimo.murim.entity.TrainingDummy(ModEntities.DUMMY.get(), h.getLevel());
        dummy.setYardSlot(0);
        Vec3 start = p.position();
        p.setYRot(0); p.setXRot(0);
        dummy.moveTo(start.x, start.y, start.z + 2, 0, 0);
        h.getLevel().addFreshEntity(dummy);
        for (int i = 0; i < io.github.verycooltimo.murim.quest.MentorStrikeDrill.REQUIRED; i++) {
            p.moveTo(start.x, start.y, start.z, 0, 0);
            dummy.invulnerableTime = 0;
            p.setData(ModAttachments.TECHNIQUE_STATE,
                    io.github.verycooltimo.murim.combat.TechniqueState.started(strike, p.level().getGameTime()));
            io.github.verycooltimo.murim.technique.SixExecutor.begin(p, strike);
            io.github.verycooltimo.murim.technique.SixExecutor.tick(p, strike, 12, 0);
            io.github.verycooltimo.murim.technique.SixExecutor.end(p, false);
        }
        p.removeData(ModAttachments.TECHNIQUE_STATE);
        p.moveTo(start.x, start.y, start.z, 0, 0);
        dummy.discard();
        h.assertTrue(SectService.state(p).has("quest.mentor_strike.done"), "real downward cuts did not complete the drill");
        h.assertTrue(SectService.state(p).entry("quest.mentor_strike.hits", 0) == 8, "wrong drill count");
        h.assertTrue(!SectService.state(p).has("quest.strike.done"), "dummy drill completed the parked clue quest");
    }

    private static ServerPlayer examPlayer(GameTestHelper h, int stars) {
        ServerPlayer p = newcomer(h);
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withTags("pure", "murim:six_harmonies").withRank(3));
        p.setData(ModAttachments.CULTIVATION, CultivationState.NONE.withMethod(SectService.SIX).withBeats(3));
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(SectService.SIX, TechniqueProgress.learned(stars, 6)));
        p.setData(ModAttachments.SECT, SectState.NONE.joined().with("lesson.six"));
        return p;
    }

    @GameTest(template = "sect_yard", batch = "sect_journey", timeoutTicks = 100)
    public static void firstExamRequiresStrikePracticeAndNamesTheMissingExercise(GameTestHelper h) {
        ServerPlayer p = examPlayer(h, 2);
        SectDisciple npc = mentor(h, p);
        h.assertTrue(!SectProgression.firstExamReady(p), "exam passed without strike practice");
        h.assertTrue("strike".equals(SectProgression.firstExamMissing(p)), "missing exercise not named");
        h.assertTrue(SectJourney.next(p).blocker().getContents() instanceof TranslatableContents missing
                && missing.getKey().equals("murim.sect.journey.blocker.strike"), "guide hides the exercise");
        completeDrill(h, p);
        h.assertTrue(SectProgression.firstExamReady(p), "completed exercise did not unlock exam");
        goal(h, p, "submit_six");
        var hook = SectGoalTalk.goalHook(p, npc, DialogueLoader.get(npc.dialogue()));
        h.assertTrue(hook != null && DialogueService.applyHeadless(p, npc, hook.option()), "ready hook did not submit exam");
        h.assertTrue(SectService.state(p).has(SectStanding.LESSON_ONE), "submission did not pass");
        h.assertTrue(!DialogueService.applyHeadless(p, npc, hook.option()), "exam rewarded twice");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_journey", timeoutTicks = 100)
    public static void oneMissingExamRequirementIsNamedByTheReply(GameTestHelper h) {
        ServerPlayer p = examPlayer(h, 1);
        SectDisciple npc = mentor(h, p);
        h.assertTrue(!SectProgression.firstExamReady(p), "one star passed the exam");
        goal(h, p, "practice_six");
        var attempt = DialogueLoader.get(npc.dialogue()).nodes().get("lessons").options().stream()
                .filter(o -> o.actions().stream().anyMatch(a -> a.type().equals("goal_talk")
                        && a.value().orElse("").equals("submit_six"))).findFirst().orElseThrow();
        h.assertTrue(DialogueService.applyHeadless(p, npc, attempt), "missing-requirement response unavailable");
        h.assertTrue(!SectService.state(p).has(SectStanding.LESSON_ONE), "failed exam granted its reward");
        h.assertTrue(SectGoalTalk.reply(p, npc).getContents() instanceof TranslatableContents t
                && t.getKey().equals("murim.sect.exam.missing.practice"), "reply did not name missing stars");
        h.succeed();
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
        goal(h, p, "read_method");
        h.assertTrue(io.github.verycooltimo.murim.item.MethodScrollItem.requestLearn(p,
                p.getMainHandItem().get(ModDataComponents.METHOD.get())).status().equals("learned"),
                "introductory scroll confirmation rejected");
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
                    > state.ring().bandCentre());
            MeditationService.tick(p);
            if (beat == 1 && p.getData(ModAttachments.CULTIVATION).beats() == 2) {
                var next = p.getData(ModAttachments.MEDITATION).ring();
                h.assertTrue(next.ticks() == 0 && next.radius() == next.bandStart()
                        && next.bandCentre() == next.radius(), "settling band reset its carried start");
                var snapshot = MeditationService.snapshot(p,
                        io.github.verycooltimo.murim.network.SyncMeditationPayload.Event.NONE);
                h.assertTrue(snapshot.ring().centre() == (float) next.bandCentre()
                        && snapshot.ring().halfWidth() == (float) next.bandHalfWidth(), "HUD band differs from server");
            }
        }
        h.assertTrue(p.getData(ModAttachments.CULTIVATION).seeded() && p.getData(ModAttachments.PROFILE).isAwakened(), "real seed practice did not finish");
        h.assertTrue(p.getData(ModAttachments.PROFILE).rank() == 0, "newcomer received a free rank");
        var seededProfile = p.getData(ModAttachments.PROFILE).withPool(7.0D);
        p.setData(ModAttachments.PROFILE, seededProfile);
        for (int i = 0; i < 5; i++) MeditationService.tick(p);
        h.assertTrue(p.getData(ModAttachments.PROFILE).equals(seededProfile), "extra completion ticks refilled qi");
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

    /** No commands, injected completion flags, ranks or mastery. Time is stepped through service events. */
    @GameTest(template = "sect_yard", batch = "sect_journey", timeoutTicks = 200)
    public static void freshPlayerNoCommandsReachesRankTwoCurriculumGate(GameTestHelper h) {
        ServerPlayer p = newcomer(h, false);
        SectDisciple teacher = mentor(h, p);
        h.assertTrue(new SectService.ItemNeed(ModItems.METHOD_SCROLL.get(), 1).count(p) == 1, "join did not issue the method");
        select(p, stack -> stack.is(ModItems.METHOD_SCROLL.get()));
        ModItems.METHOD_SCROLL.get().use(h.getLevel(), p, InteractionHand.MAIN_HAND);
        goal(h, p, "read_method");
        h.assertTrue(io.github.verycooltimo.murim.item.MethodScrollItem.requestLearn(p,
                p.getMainHandItem().get(ModDataComponents.METHOD.get())).status().equals("learned"),
                "introductory scroll confirmation rejected");
        p.getRandom().setSeed(37);
        h.assertTrue(MeditationService.start(p, true), "seed session rejected");
        for (int i = 0; i < 1500 && !p.getData(ModAttachments.CULTIVATION).seeded(); i++) {
            var session = p.getData(ModAttachments.MEDITATION);
            int beat = p.getData(ModAttachments.CULTIVATION).beats();
            if (beat > 0) MeditationService.setHolding(p, session.ring().radius() + session.ring().surge() * 0.9
                    > session.ring().bandCentre());
            MeditationService.tick(p);
        }
        h.assertTrue(p.getData(ModAttachments.CULTIVATION).seeded(), "seed did not form");
        select(p, stack -> SectService.SIX.equals(stack.get(ModDataComponents.TECHNIQUE.get())));
        TechniqueManualItem.learnFromHand(p, InteractionHand.MAIN_HAND, SectService.SIX);
        var lessons = DialogueLoader.get(teacher.dialogue()).nodes().get("lessons").options();
        var take = lessons.stream().filter(o -> o.actions().stream().anyMatch(a ->
                a.type().equals("set_flag") && a.value().orElse("").equals("lesson.six"))).findFirst().orElseThrow();
        h.assertTrue(DialogueService.applyHeadless(p, teacher, take), "first lesson rejected");
        // Real practice and reserve processing, bounded so an inaccessible star fails rather than hangs.
        for (int i = 0; i < 20000 && MasteryService.stars(p, SectService.SIX) < 2; i++) {
            MasteryService.onMiss(p, SectService.SIX);
            if (i % 32 == 0) MasteryService.comprehend(p, SectService.SIX);
        }
        h.assertTrue(MasteryService.stars(p, SectService.SIX) >= 2, "Six Elements cannot reach the exam stars");
        completeDrill(h, p);
        // The ordinary activity credit still applies the daily diminishing returns and body rules.
        for (var source : io.github.verycooltimo.murim.training.BodySource.values()) {
            io.github.verycooltimo.murim.training.TrainingService.credit(p, source, 100);
        }
        p.xOld = p.getX(); p.zOld = p.getZ(); p.setOnGround(true);
        MeditationService.start(p, true);
        for (int i = 0; i < 40000 && p.getData(ModAttachments.PROFILE).rank() == 0; i++) MeditationService.tick(p);
        h.assertTrue(p.getData(ModAttachments.PROFILE).rank() == 1, "ordinary meditation cannot reach rank one");
        teacher.moveTo(p.getX() + 1, p.getY(), p.getZ(), 0, 0);
        finish(h, p, teacher, SectStanding.LESSON_ONE);
        select(p, stack -> SectJourney.PETAL.equals(stack.get(ModDataComponents.TECHNIQUE.get())));
        h.assertTrue(io.github.verycooltimo.murim.mastery.ManualDepth.stars(p.getMainHandItem(), SectJourney.PETAL) == 0,
                "mentor issued a torn Petal book");
        // API: Minecraft 1.21.1 official client mappings, ItemCooldowns#tick:
        // https://piston-data.mojang.com/v1/objects/2244b6f072256667bcd9a73df124d6c58de77992/client.txt
        // Service loops above do not advance the player's ordinary item cooldown ticks.
        for (int i = 0; i < 20; i++) p.getCooldowns().tick();
        TechniqueManualItem.learnFromHand(p, InteractionHand.MAIN_HAND, SectJourney.PETAL);
        h.assertTrue(MasteryService.knows(p, SectJourney.PETAL), "complete Petal manual was not learned");
        h.assertTrue(p.getData(ModAttachments.MASTERY).progress(SectJourney.PETAL).cap() >= 3,
                "Petal cannot reach the rank-two star requirement");
        for (var art : java.util.List.of(SectService.SIX, SectJourney.PETAL)) {
            int needed = art.equals(SectService.SIX) ? 6 : 3;
            for (int i = 0; i < 100000 && MasteryService.stars(p, art) < needed; i++) {
                MasteryService.onMiss(p, art);
                if (i % 32 == 0) MasteryService.comprehend(p, art);
            }
        }
        h.assertTrue(io.github.verycooltimo.murim.cultivation.Realm.secondArtsMet(
                p.getData(ModAttachments.MASTERY).combatStars()), "curriculum cannot reach rank-two stars");
        teacher.moveTo(p.getX() + 1, p.getY(), p.getZ(), 0, 0);
        var spar = lessons.stream().filter(o -> o.actions().stream().anyMatch(a ->
                a.type().equals("set_flag") && a.value().orElse("").equals("lesson.spar"))).findFirst().orElseThrow();
        h.assertTrue(DialogueService.applyHeadless(p, teacher, spar), "second lesson rejected");
        SectDisciple partner = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        partner.setMember(SectRoster.of("seo_rang").orElseThrow());
        for (int i = 0; i < SectService.CLEAN_NEEDED; i++) SectService.onCleanHit(p, partner);
        goal(h, p, "rank_two");
        h.assertTrue(io.github.verycooltimo.murim.trade.PeddlerStock.PAGE_BOOKS.contains("murim:falling_petal_sword"),
                "peddler cannot sell Petal pages");
        h.assertTrue(SectContracts.page(0, 1, 1, 1).get(ModDataComponents.TECHNIQUE.get()).equals(SectJourney.PETAL),
                "entry contracts cannot reward Petal pages");
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
                && t.getKey().equals("murim.rank.second.checklist"), "rank 2 checklist missing");
        p.setData(BossRegistry.BOSS_DEFEATED, true);
        h.assertTrue(!SectProgression.secondSparProof(p), "boss supplied sect proof");
        // The independent body gate survives the switch from boss proof to sect skills.
        h.assertTrue(!io.github.verycooltimo.murim.training.BodyRules.breakthroughReady(2,
                io.github.verycooltimo.murim.training.TrainingService.level(p)), "an untempered body passed the gate");
        io.github.verycooltimo.murim.training.TrainingService.setPoints(p, 130.0D);
        h.assertTrue(io.github.verycooltimo.murim.training.BodyRules.breakthroughReady(2,
                io.github.verycooltimo.murim.training.TrainingService.level(p)), "trained body did not clear its gate");
        h.assertTrue(!SectProgression.secondSparProof(p), "body training supplied sect proof");
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withRank(2));
        goal(h, p, "submit_spar"); finish(h, p, mentor, SectStanding.LESSON_TWO);
        goal(h, p, "learn_seven");
        select(p, stack -> ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "seven_plum_blossoms")
                .equals(stack.get(ModDataComponents.TECHNIQUE.get())));
        h.assertTrue(io.github.verycooltimo.murim.mastery.ManualDepth.stars(p.getMainHandItem(), SectJourney.SEVEN) == 0,
                "lesson two issued a torn Seven Plum manuscript");
        TechniqueManualItem.learnFromHand(p, InteractionHand.MAIN_HAND, SectJourney.SEVEN);
        h.assertTrue(p.getData(ModAttachments.MASTERY).progress(SectJourney.SEVEN).cap() >= 6,
                "sect Seven Plum cannot reach the rank-three requirement");
        p.setData(ModAttachments.MASTERY, p.getData(ModAttachments.MASTERY).with(SectJourney.SEVEN, TechniqueProgress.learned(0, 10)));
        goal(h, p, "request");
        p.setData(ModAttachments.SECT, SectService.state(p).with("request.pills.active"));
        // The pills are gone (eaten, lost): the goal sends the player back to the giver for the missing ones.
        goal(h, p, "request");
        p.getInventory().add(new net.minecraft.world.item.ItemStack(io.github.verycooltimo.murim.registry.ModItems.PILL_SNOW_PLUM.get(), 3));
        goal(h, p, "deliver_pills");
        p.setData(ModAttachments.SECT, SectService.state(p).without("request.pills.active").with("request.pills.done"));
        goal(h, p, "partners");
        for (String partner : java.util.List.of("gyeong_on", "gyeong_dan", "gyeong_ha"))
            p.setData(ModAttachments.SECT, SectService.state(p).with("mentor.partner." + partner));
        goal(h, p, "direct");
        p.setData(ModAttachments.SECT, SectService.state(p).with("mentor.ceremony")); goal(h, p, "ceremony");
        p.setData(ModAttachments.SECT, SectService.state(p).without("mentor.ceremony").with(SectProgression.DIRECT));
        goal(h, p, "standing");
        p.setData(ModAttachments.SECT, SectService.state(p).contribute(30)); goal(h, p, "approval");
        p.setData(ModAttachments.SECT, SectService.state(p).with(SectStanding.APPROVAL));
        // The chain goes on after the second lesson: rank three, then the Peak, then the mentor's lessons.
        goal(h, p, "rank_three");
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withRank(3));
        goal(h, p, "peak");
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withRank(4).withStage(2));
        goal(h, p, "personal_lesson");
        // Learning Seven Plum can fill an empty foundation slot; cap the art this mentor lesson actually uses.
        p.setData(ModAttachments.MASTERY, p.getData(ModAttachments.MASTERY)
                .with(SectProgression.lessonTechnique(p), TechniqueProgress.learned(12, 12)));
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
        var legacyMeal = new SectAttendance.Day(day, Set.of("formation", "lesson", "meal"),
                Set.of("formation", "lesson"));
        var mealJson = SectAttendance.Day.CODEC.encodeStart(JsonOps.INSTANCE, legacyMeal).getOrThrow().getAsJsonObject();
        mealJson.getAsJsonArray("expected").add("meal");
        var loadedMeal = SectAttendance.Day.CODEC.parse(JsonOps.INSTANCE, mealJson).getOrThrow();
        h.assertTrue(loadedMeal.full() && !SectAttendance.required(loadedMeal, SectAttendance.Activity.MEAL,
                p.level().getDayTime()), "legacy meal roll call still required food");
        var onlyMeal = new SectAttendance.Day(day, Set.of("meal"), Set.of("meal"));
        h.assertFalse(onlyMeal.full(), "a meal alone earned daily practice merit");
        var full = new SectAttendance.Day(day, Set.of("formation"), Set.of("formation"));
        var settled = SectAttendance.roll(new SectAttendance.Log(full, incomplete, current.streak(), 0, false, Long.MIN_VALUE), day + 1);
        h.assertTrue(settled.log().streak() == 7 && settled.bonus() > 0, "catch-up full day lost bonus");
        h.assertTrue(SectAttendance.roll(settled.log(), day + 1).bonus() == 0, "full-day bonus repeated");
        h.succeed();
    }
}
