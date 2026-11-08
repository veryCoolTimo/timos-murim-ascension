package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.CultivationState;
import io.github.verycooltimo.murim.cultivation.MeditationState;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.Set;

/**
 * Every test here has its own batch and puts the world clock back before it succeeds: the sect schedule, the review
 * window (day % 7) and the door and desk tests of other classes read the shared day time, so a test that leaves it
 * somewhere else breaks them (seen on build/path-v2: doorIsClosedBehindTheWalker, leavesTheSeatWhenThePeriodEnds).
 *
 * <p>Doc 39 items 1, 2, 6 and 7 on a real server: the goal reminder toggle, the method scroll at joining, the bowl from the
 * cook, and people who walk up to the player by themselves.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectPathV2GameTests {
    private static final String YARD = "sect_yard";

    private SectPathV2GameTests() { }

    private static SectGameTests.Yard yard(GameTestHelper h) {
        return new SectGameTests.Yard(SectLife.stand(h.getLevel(), h.absoluteVec(new Vec3(12.5D, 2.0D, 12.5D))), 1.0D);
    }

    private static SectDisciple npc(GameTestHelper h, SectLayout layout, String key, double x, double z) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setLayout(layout);
        d.setKeepAwake(true);
        Vec3 at = SectLife.stand(h.getLevel(), h.absoluteVec(new Vec3(x + 0.5D, 2.0D, z + 0.5D)));
        d.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        h.getLevel().addFreshEntity(d);
        return d;
    }

    private static ServerPlayer member(GameTestHelper h, double x, double z) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        Vec3 at = SectLife.stand(h.getLevel(), h.absoluteVec(new Vec3(x + 0.5D, 2.0D, z + 0.5D)));
        p.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        p.setOnGround(true);
        p.tickCount = 2000;
        p.setData(ModAttachments.SECT, SectState.NONE.joined().with("met_mentor"));
        return p;
    }

    private static int count(ServerPlayer p, net.minecraft.world.item.Item item) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (s.is(item)) n += s.getCount();
        return n;
    }

    /** Stacks of the cook's food (any dish or older bowl) in the main inventory. */
    private static int meals(ServerPlayer p) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (SectMeals.isServed(s)) n++;
        return n;
    }

    // ------------------------------------------------------------------ item 1: goal

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_path_v2_goalremindertoggleandlongchain")
    public static void goalReminderToggleAndLongChain(GameTestHelper h) {
        long savedTime = h.getLevel().getDayTime();
        ServerPlayer p = member(h, 4, 12);
        SectJourney.Goal goal = SectJourney.next(p);
        h.assertTrue(SectJourney.hudLine(p).equals(goal.hudText()) && !SectJourney.hudLine(p).getString().isEmpty(), "reminder not shown by default");
        h.assertTrue(!SectJourney.toggleHud(p) && SectService.state(p).has(SectJourney.HUD_OFF)
                && SectJourney.hudLine(p).getString().isEmpty(), "journal did not switch the reminder off");
        h.assertTrue(SectJourney.line(p).getString().contains(goal.text().getString()), "the sect log lost the goal when the reminder was off");
        h.assertTrue(SectJourney.toggleHud(p) && !SectService.state(p).has(SectJourney.HUD_OFF), "reminder did not come back");
        // The chain goes on at the Peak without taking the optional footwork lesson.
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withTags("pure", "six").withRank(4));
        p.setData(ModAttachments.SECT, SectService.state(p).with(SectStanding.LESSON_ONE).with(SectStanding.LESSON_TWO)
                .with(SectProgression.DIRECT).with(SectStanding.APPROVAL).contribute(200).with("request.pills.done"));
        // Stages are goals too, each with its own blocker.
        p.setData(ModAttachments.MASTERY, p.getData(ModAttachments.MASTERY)
                .with(SectJourney.PETAL, io.github.verycooltimo.murim.mastery.TechniqueProgress.learned(0, 6))
                .with(SectJourney.SEVEN, io.github.verycooltimo.murim.mastery.TechniqueProgress.learned(0, 10))
                .with(SectService.SIX, io.github.verycooltimo.murim.mastery.TechniqueProgress.learned(2, 6)));
        h.assertTrue(SectJourney.next(p).id().equals("peak_stage"), "no goal after the Peak: " + SectJourney.next(p).id());
        h.getLevel().setDayTime(savedTime);
            h.succeed();
    }

    // ------------------------------------------------------------------ item 2: scroll at joining

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_path_v2_joininggivesthemethodscrollwithoutamentor")
    public static void joiningGivesTheMethodScrollWithoutAMentor(GameTestHelper h) {
        long savedTime = h.getLevel().getDayTime();
        ServerPlayer p = SectGameTests.fakePlayer(h);
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL);
        p.setData(ModAttachments.CULTIVATION, CultivationState.NONE);
        SectService.join(p);
        h.assertTrue(count(p, ModItems.METHOD_SCROLL.get()) == 1 && SectService.state(p).has(SectJourney.SCROLL_GIVEN),
                "joining did not hand out the method scroll");
        h.assertTrue(SectJourney.next(p).id().equals("read_method"), "goal after joining: " + SectJourney.next(p).id());
        SectService.join(p);
        h.assertTrue(count(p, ModItems.METHOD_SCROLL.get()) == 1, "second join duplicated the scroll");
        // Until the seed forms the newcomer lives on plain attacks and exercises: the journal accepts them without a centre.
        h.assertTrue(!p.getData(ModAttachments.PROFILE).isAwakened() && SectAttendance.record(p, "pushups"),
                "an exercise did not count for a newcomer without a centre");
        // A player who already has a centre gets no scroll.
        ServerPlayer old = SectGameTests.fakePlayer(h);
        old.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withTags("pure", "existing").withRank(1));
        SectService.join(old);
        h.assertTrue(count(old, ModItems.METHOD_SCROLL.get()) == 0, "a player with a centre got a scroll");
        h.getLevel().setDayTime(savedTime);
            h.succeed();
    }

    // ------------------------------------------------------------------ item 6: meals

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_path_v2_bowloncepermealthenmarkstheeatenmeal")
    public static void bowlOncePerMealThenEatenMarksTheMeal(GameTestHelper h) {
        long savedTime = h.getLevel().getDayTime();
        ServerPlayer p = member(h, 4, 12);
        long day = Math.floorDiv(h.getLevel().getDayTime(), 24000L) * 24000L;
        h.getLevel().setDayTime(day + 15000L);
        h.assertTrue(SectMeals.serve(p) == SectMeals.Result.NOT_MEAL && meals(p) == 0, "bowl at night");
        h.getLevel().setDayTime(day + 1300L);
        int dishes = SectMenu.menu(SectMeals.mealId(day + 1300L)).size();
        h.assertTrue(SectMeals.canServe(p) && SectMeals.serve(p) == SectMeals.Result.SERVED && meals(p) == dishes,
                "the cook did not hand the meal");
        h.assertTrue(SectMeals.serve(p) == SectMeals.Result.ALREADY && meals(p) == dishes, "second portion in one meal");
        // The claim survives a save; the next meal of the day is served again.
        h.getLevel().setDayTime(day + 9500L);
        h.assertTrue(SectMeals.canServe(p), "dinner not served after breakfast");
        h.getLevel().setDayTime(day + 1300L);
        // Eat the bowl: food and the MEAL mark in the journal; the empty bowl comes back.
        p.getFoodData().setFoodLevel(6);
        int slot = -1;
        for (int i = 0; i < p.getInventory().items.size(); i++) if (SectMeals.isServed(p.getInventory().items.get(i))) slot = i;
        h.assertTrue(slot >= 0, "no bowl in the inventory");
        ItemStack bowl = p.getInventory().items.get(slot);
        p.getInventory().items.set(slot, ItemStack.EMPTY);
        ItemStack rest = bowl.finishUsingItem(h.getLevel(), p);
        h.assertTrue(p.getFoodData().getFoodLevel() > 6, "the bowl did not feed");
        SectMeals.eaten(p);
        h.assertTrue(SectAttendance.current(p).today().did(SectAttendance.Activity.MEAL), "eating the bowl left no MEAL mark");
        h.assertTrue(rest.isEmpty(), "an empty bowl litters the inventory: " + rest);
        // A stranger is not served.
        ServerPlayer stranger = SectGameTests.fakePlayer(h);
        h.assertTrue(SectMeals.serve(stranger) == SectMeals.Result.NOT_MEMBER && meals(stranger) == 0, "outsider served");
        h.getLevel().setDayTime(savedTime);
            h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_path_v2_cookdialogueservesthebowlandsittingstillmarksthemeal")
    public static void cookDialogueServesTheBowlAndSittingStillMarksTheMeal(GameTestHelper h) {
        long savedTime = h.getLevel().getDayTime();
        SectGameTests.Yard yard = yard(h);
        ServerPlayer p = member(h, 4, 12);
        SectDisciple cook = npc(h, yard, "cook_kim", 6, 12);
        cook.setNoAi(true);
        long day = Math.floorDiv(h.getLevel().getDayTime(), 24000L) * 24000L;
        h.getLevel().setDayTime(day + 1300L);
        DialogueService.Route route = DialogueService.route(p, cook);
        h.assertTrue("serving".equals(route.node()), "the cook does not serve at breakfast: " + route.node());
        var node = DialogueLoader.get(cook.dialogue()).nodes().get("serving");
        h.assertTrue(node.enter().stream().anyMatch(a -> a.type().equals("serve_bowl")), "cook dialogue has no bowl action");
        h.assertTrue(DialogueService.validate(DialogueLoader.get(cook.dialogue())).isEmpty(), "invalid cook dialogue");
        // Fallback: a player who only sits with everyone gets the journal mark but no food.
        ServerPlayer sitter = member(h, 4, 14);
        sitter.getFoodData().setFoodLevel(6);
        for (int i = 0; i < SectAttendance.MEAL_TICKS / 20 + 1; i++) SectAttendance.tick(sitter, yard, 20);
        h.assertTrue(SectAttendance.current(sitter).today().did(SectAttendance.Activity.MEAL), "sitting no longer marks the meal");
        h.assertTrue(sitter.getFoodData().getFoodLevel() == 6, "sitting still feeds automatically");
        h.getLevel().setDayTime(savedTime);
            h.succeed();
    }

    // ------------------------------------------------------------------ item 7: approaches

    @GameTest(template = YARD, timeoutTicks = 700, batch = "sect_path_v2_personwalksupstopsandleaves")
    public static void personWalksUpStopsAndLeaves(GameTestHelper h) {
        long savedTime = h.getLevel().getDayTime();
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        SectGameTests.Yard yard = yard(h);
        ServerPlayer p = member(h, 3, 12);
        SectDisciple npc = npc(h, yard, "seo_rang", 19, 12);
        SectApproach.Plan plan = SectApproach.choose(p, 0);
        h.assertTrue(plan != null && plan.npc() == npc, "no approach planned: " + plan);
        h.assertTrue(SectApproach.begin(p, plan), "approach did not start");
        h.assertTrue(!SectApproach.due(p), "no rate limit after the approach");
        double[] closest = {1000.0D};
        boolean[] arrived = {false};
        h.onEachTick(() -> {
            double d = Math.sqrt(npc.distanceToSqr(p));
            closest[0] = Math.min(closest[0], d);
            if (npc.approachArrived()) {
                arrived[0] = true;
                h.assertTrue(d > 1.9D && d < 3.6D, "stopped at " + d + " blocks");
            }
            if (arrived[0] && npc.approachKind() == null) {
                h.assertTrue(closest[0] > 1.9D, "came closer than two blocks: " + closest[0]);
                h.getLevel().setDayTime(savedTime);
            h.succeed();
            }
        });
    }

    @GameTest(template = YARD, timeoutTicks = 200, batch = "sect_path_v2_ignoredapproachgivesup")
    public static void ignoredApproachGivesUp(GameTestHelper h) {
        long savedTime = h.getLevel().getDayTime();
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        SectGameTests.Yard yard = yard(h);
        ServerPlayer p = member(h, 3, 12);
        SectDisciple npc = npc(h, yard, "seo_rang", 22, 12);
        h.assertTrue(npc.approach(p, "greet", 30), "approach not accepted");
        h.assertTrue(npc.approachKind() != null, "no approach kind");
        h.runAfterDelay(60, () -> {
            h.assertTrue(npc.approachKind() == null && !npc.blocking() && !npc.approachArrived(), "he kept following an ignored player");
            h.getLevel().setDayTime(savedTime);
            h.succeed();
        });
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_path_v2_neverduringcombatmeditationdialogueornight")
    public static void neverDuringCombatMeditationDialogueOrNight(GameTestHelper h) {
        long savedTime = h.getLevel().getDayTime();
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        SectGameTests.Yard yard = yard(h);
        ServerPlayer p = member(h, 3, 12);
        SectDisciple npc = npc(h, yard, "seo_rang", 19, 12);
        npc.setNoAi(true);
        h.assertTrue(SectApproach.choose(p, 0) != null, "nobody would come to an idle player");
        p.setData(ModAttachments.MEDITATION, MeditationState.started(true, 0));
        h.assertTrue(SectApproach.choose(p, 0) == null, "approach during meditation");
        p.setData(ModAttachments.MEDITATION, MeditationState.IDLE);
        p.setData(ModAttachments.DIALOGUE, new DialogueService.Session(npc.getId(), null, "x", java.util.List.of()));
        h.assertTrue(SectApproach.choose(p, 0) == null, "approach during a dialogue");
        p.setData(ModAttachments.DIALOGUE, DialogueService.Session.NONE);
        p.setLastHurtByMob(npc);
        h.assertTrue(SectApproach.choose(p, 0) == null, "approach during combat");
        p.tickCount += 400;
        h.assertTrue(SectApproach.choose(p, 0) != null, "combat pause never ends");
        npc.startSpar(p, 0);
        h.assertTrue(SectApproach.choose(p, 0) == null, "approach during a bout");
        npc.stopBout();
        SectGameTests.setPeriod(h, SectSchedule.Period.NIGHT);
        h.assertTrue(SectApproach.choose(p, 0) == null, "approach at night");
        h.getLevel().setDayTime(savedTime);
            h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_path_v2_everykindcanbechosenandsayssomething")
    public static void everyKindCanBeChosenAndSaysSomething(GameTestHelper h) {
        long savedTime = h.getLevel().getDayTime();
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        SectGameTests.Yard yard = yard(h);
        ServerPlayer p = member(h, 3, 12);
        p.setData(ModAttachments.SECT, SectService.state(p).with(SectStanding.LESSON_ONE));
        SectDisciple rang = npc(h, yard, "seo_rang", 19, 10);
        SectDisciple gyun = npc(h, yard, "tae_gyun", 19, 14);
        rang.setNoAi(true);
        gyun.setNoAi(true);
        Set<SectApproach.Kind> kinds = new HashSet<>();
        for (int roll = 0; roll < 12; roll++) {
            SectApproach.Plan plan = SectApproach.choose(p, roll);
            h.assertTrue(plan != null, "no plan for roll " + roll);
            kinds.add(plan.kind());
        }
        for (SectApproach.Kind k : Set.of(SectApproach.Kind.GREET, SectApproach.Kind.SPAR, SectApproach.Kind.GOAL, SectApproach.Kind.HELP)) {
            h.assertTrue(kinds.contains(k), "never chosen: " + k + " of " + kinds);
        }
        for (SectApproach.Kind k : SectApproach.Kind.values()) {
            SectDisciple speaker = k == SectApproach.Kind.TEACHER ? npc(h, yard, "tae_rok", 20, 12) : rang;
            Component line = SectApproach.line(speaker, p, k, "pills");
            h.assertTrue(line != null && !line.getString().isEmpty(), "no line for " + k);
        }
        h.assertTrue(!SectApproach.speaks(rang, p, SectApproach.Kind.TEACHER, "")
                && SectApproach.line(rang, p, SectApproach.Kind.TEACHER, "") == null, "junior offered teacher acknowledgement");
        // The request for help is real: the sect request starts and the pills are handed over.
        SectApproach.arrive(gyun, p, "help:pills");
        h.assertTrue(SectService.state(p).has(SectRequests.flag("pills", "active")) && count(p, ModItems.PILL_SNOW_PLUM.get()) == 3,
                "the request for help did not start the sect request");
        // Before the formation bell and at a meal the invitation is the only reason to come.
        SectGameTests.setPeriod(h, SectSchedule.Period.DINNER);
        p.setData(ModAttachments.SECT, SectService.state(p).without(SectApproach.LAST));
        SectApproach.Plan table = SectApproach.choose(p, 0);
        h.assertTrue(table != null && table.kind() == SectApproach.Kind.TABLE, "no table invitation at a meal: " + table);
        h.getLevel().setDayTime(savedTime);
            h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_path_v2_oneapproachperintervalandnothingforoutsiders")
    public static void oneApproachPerIntervalAndNothingForOutsiders(GameTestHelper h) {
        long savedTime = h.getLevel().getDayTime();
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        SectGameTests.Yard yard = yard(h);
        ServerPlayer p = member(h, 3, 12);
        SectDisciple npc = npc(h, yard, "seo_rang", 19, 12);
        npc.setNoAi(true);
        SectApproach.Plan plan = SectApproach.choose(p, 0);
        h.assertTrue(SectApproach.begin(p, plan), "approach did not start");
        npc.clearBlock();
        h.assertTrue(SectApproach.choose(p, 0) == null, "second approach inside the interval");
        long now = h.getLevel().getGameTime();
        p.setData(ModAttachments.SECT, SectService.state(p).record(SectApproach.LAST, now - SectApproach.INTERVAL - 1));
        p.setData(ModAttachments.SECT, SectService.state(p).record("approach.at." + npc.memberKey(), -1_000_000L));
        h.assertTrue(SectApproach.choose(p, 0) != null, "interval never ends");
        ServerPlayer stranger = SectGameTests.fakePlayer(h);
        Vec3 at = p.position();
        stranger.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        h.assertTrue(SectApproach.choose(stranger, 0) == null, "an outsider was approached");
        h.getLevel().setDayTime(savedTime);
            h.succeed();
    }

    // ------------------------------------------------------------------ small details: meals

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_path_v2_latecomergetsacoldportiononceandkeepsit")
    public static void latecomerGetsAColdPortionOnceAndKeepsIt(GameTestHelper h) {
        long savedTime = h.getLevel().getDayTime();
        ServerPlayer p = member(h, 4, 12);
        long day = Math.floorDiv(h.getLevel().getDayTime(), 24000L) * 24000L;
        // The period after breakfast: the cook keeps a cold portion; one per meal.
        h.getLevel().setDayTime(day + 3000L);
        h.assertTrue(SectMeals.lateMealId(day + 3000L) >= 0 && SectMeals.canServe(p), "no cold portion for a latecomer");
        var menu = SectMenu.menu(SectMeals.lateMealId(day + 3000L));
        h.assertTrue(SectMeals.serve(p) == SectMeals.Result.COLD && meals(p) == menu.size(), "the latecomer did not get the cold portion");
        h.assertTrue(SectMeals.serve(p) == SectMeals.Result.ALREADY, "a second cold portion");
        for (ItemStack st : p.getInventory().items) {
            if (!SectMeals.isServed(st)) continue;
            h.assertTrue(st.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY)
                            .copyTag().getBoolean(io.github.verycooltimo.murim.item.MealBowlItem.COLD), "the cold portion is not marked cold");
            h.assertTrue(st.get(net.minecraft.core.component.DataComponents.FOOD).nutrition()
                    < st.getItem().components().get(net.minecraft.core.component.DataComponents.FOOD).nutrition()
                    || st.getItem().components().get(net.minecraft.core.component.DataComponents.FOOD).nutrition() == 1,
                    "the cold portion feeds as much as the hot one");
        }
        // Not in the middle of the day.
        ServerPlayer other = member(h, 4, 14);
        h.getLevel().setDayTime(day + 15000L);
        h.assertTrue(SectMeals.serve(other) == SectMeals.Result.NOT_MEAL, "a portion at night");
        // UX #21: uneaten portions remain edible after their period ends.
        h.getLevel().setDayTime(day + 3000L);
        h.assertTrue(SectMeals.collectStale(p) == 0, "the bowl vanished inside its period");
        long expiry = 0;
        for (ItemStack st : p.getInventory().items) if (SectMeals.isServed(st)) expiry = SectMeals.expiry(st);
        h.assertTrue(expiry > h.getLevel().getGameTime() && expiry <= h.getLevel().getGameTime() + 9000L, "bad expiry " + expiry);
        h.getLevel().setDayTime(day + 9000L);
        h.getLevel().getServer().overworld();
        p.tickCount = 2000;
        // advance the game clock past the expiry by faking the expiry into the past
        for (int i = 0; i < p.getInventory().items.size(); i++) {
            ItemStack st = p.getInventory().items.get(i);
            if (SectMeals.isServed(st)) {
                var t = st.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                        net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
                t.putLong("expires", h.getLevel().getGameTime() - 1);
                st.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(t));
            }
        }
        h.assertTrue(SectMeals.collectStale(p) == 0 && meals(p) == menu.size(), "a cold portion was removed or cooled twice");
        for (ItemStack st : p.getInventory().items) {
            if (!SectMeals.isServed(st)) continue;
            h.assertTrue(SectMeals.expiry(st) == 0L, "a cold portion kept its expiry");
            h.assertTrue(st.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.EMPTY).copyTag()
                    .getBoolean(io.github.verycooltimo.murim.item.MealBowlItem.COLD), "a cold portion lost its flag");
        }
        h.assertTrue(SectMeals.collectStale(p) == 0 && meals(p) == menu.size(), "a repeated sweep changed cold portions");
        // Food from the creative tab carries no expiry and stays.
        p.getInventory().items.set(5, new ItemStack(ModItems.STEAMED_BUN.get()));
        h.assertTrue(SectMeals.collectStale(p) == 0 && meals(p) == menu.size() + 1, "the cook collected a creative-tab bun");
        h.getLevel().setDayTime(savedTime);
            h.succeed();
    }

    // ------------------------------------------------------------------ small details: goals

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_path_v2_goalshowsacountandfinishingchangesthegoal")
    public static void goalShowsACountAndFinishingChangesTheGoal(GameTestHelper h) {
        long savedTime = h.getLevel().getDayTime();
        ServerPlayer p = member(h, 4, 12);
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL);
        p.setData(ModAttachments.CULTIVATION, CultivationState.NONE.withMethod(SectService.SIX).withBeats(2));
        SectJourney.Goal goal = SectJourney.next(p);
        // The three beats are three sittings, not three things to feel: the step is in the words, no counter (playtest 22.10).
        h.assertTrue(goal.id().equals("seed_compress") && goal.progress().isEmpty() && !goal.hudText().getString().contains("/3")
                && goal.text().getString().contains("3"), "seed goal must name its step and carry no counter: " + goal.progress());
        SectJourney.cue(p);
        h.assertTrue(p.getPersistentData().getString("murim_journey_goal").equals(goal.id()), "cue did not remember the goal");
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withTags("pure", "murim:six_harmonies"));
        p.setData(ModAttachments.CULTIVATION, CultivationState.NONE.withMethod(SectService.SIX).withBeats(3));
        h.assertTrue(!SectJourney.next(p).id().equals(goal.id()), "finishing the seed did not move the goal");
        SectJourney.cue(p);
        h.assertTrue(p.getPersistentData().getString("murim_journey_goal").equals(SectJourney.next(p).id()), "cue lost the new goal");
        for (String partner : java.util.List.of("gyeong_on", "gyeong_dan")) {
            p.setData(ModAttachments.SECT, SectService.state(p).with("mentor.partner." + partner));
        }
        h.getLevel().setDayTime(savedTime);
            h.succeed();
    }

    // ------------------------------------------------------------------ small details: approaches

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_path_v2_notnowsendsthepersonawayuntiltomorrow")
    public static void notNowSendsThePersonAwayUntilTomorrow(GameTestHelper h) {
        long savedTime = h.getLevel().getDayTime();
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        SectGameTests.Yard yard = yard(h);
        ServerPlayer p = member(h, 3, 12);
        SectDisciple npc = npc(h, yard, "seo_rang", 19, 12);
        npc.setNoAi(true);
        h.assertTrue(SectApproach.begin(p, SectApproach.choose(p, 0)), "approach did not start");
        h.assertTrue(SectSocial.matches(p, npc, "approaching"), "the approaching person does not offer 'not now'");
        var greet = DialogueLoader.get(npc.dialogue()).nodes().get("greet");
        var notNow = greet.options().stream().filter(o -> o.actions().stream().anyMatch(a -> a.type().equals("approach_decline")))
                .findFirst().orElseThrow();
        h.assertTrue(DialogueService.applyHeadless(p, npc, notNow), "'not now' rejected");
        h.assertTrue(npc.approachKind() == null && SectApproach.sentAway(p, npc), "the person is not sent away");
        p.setData(ModAttachments.SECT, SectService.state(p).record("approach.at." + npc.memberKey(), -1_000_000L).record("approach.gap", 0L));
        h.assertTrue(SectApproach.choose(p, 0) == null || SectApproach.choose(p, 0).npc() != npc, "he came back the same day");
        // The next sect day he may come again.
        h.getLevel().setDayTime(h.getLevel().getDayTime() + 24000L);
        h.assertTrue(!SectApproach.sentAway(p, npc), "'not now' lasted longer than the day");
        h.getLevel().setDayTime(savedTime);
            h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_path_v2_gapsvaryandrecenteventsgettheirowntopics")
    public static void gapsVaryAndRecentEventsGetTheirOwnTopics(GameTestHelper h) {
        long savedTime = h.getLevel().getDayTime();
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        SectGameTests.Yard yard = yard(h);
        ServerPlayer p = member(h, 3, 12);
        SectDisciple npc = npc(h, yard, "seo_rang", 19, 12);
        npc.setNoAi(true);
        SectEvents.record(p, SectEvents.Event.BOUT_LOST);
        Set<SectApproach.Kind> seen = new HashSet<>();
        for (int roll = 0; roll < 12; roll++) seen.add(SectApproach.choose(p, roll).kind());
        h.assertTrue(seen.contains(SectApproach.Kind.CONSOLE), "a lost bout drew no kind word: " + seen);
        p.setData(ModAttachments.SECT_MEMORY, io.github.verycooltimo.murim.sect.SectMemory.EMPTY);
        SectEvents.record(p, SectEvents.Event.BOUT_WON);
        seen.clear();
        for (int roll = 0; roll < 12; roll++) seen.add(SectApproach.choose(p, roll).kind());
        h.assertTrue(seen.contains(SectApproach.Kind.CONGRATS), "a win drew no congratulation: " + seen);
        // The wait before the next approach is drawn between three and five minutes.
        long min = Long.MAX_VALUE, max = 0;
        for (int i = 0; i < 30; i++) {
            SectDisciple other = npc;
            // Reset the person actually sent: choose() can select another nearby fixture.
            p.setData(ModAttachments.SECT, SectService.state(p).record("approach.at." + other.memberKey(), -1_000_000L));
            other.clearBlock();
            h.assertTrue(SectApproach.begin(p, new SectApproach.Plan(other, SectApproach.Kind.GREET, "")), "approach did not start " + i);
            long gap = SectService.state(p).entry("approach.gap", -1L);
            min = Math.min(min, gap);
            max = Math.max(max, gap);
            p.setData(ModAttachments.SECT, SectService.state(p).record(SectApproach.LAST, -1_000_000L));
        }
        h.assertTrue(min >= SectApproach.INTERVAL_MIN && max <= SectApproach.INTERVAL && max > min, "gaps " + min + ".." + max);
        // The same line is not repeated back to back (the whole pool: SectLinesGameTests).
        String a = SectLines.pick(p, "murim.approach.greet.third", SectApproach.VARIANTS, 5);
        String b = SectLines.pick(p, "murim.approach.greet.third", SectApproach.VARIANTS, 5);
        h.assertTrue(!a.equals(b), "the same greeting twice in a row");
        h.getLevel().setDayTime(savedTime);
            h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 300, batch = "sect_path_v2_approachisdroppedwhentheplayerbecomesbusy")
    public static void approachIsDroppedWhenThePlayerBecomesBusy(GameTestHelper h) {
        long savedTime = h.getLevel().getDayTime();
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        SectGameTests.Yard yard = yard(h);
        ServerPlayer p = member(h, 3, 12);
        SectDisciple npc = npc(h, yard, "seo_rang", 22, 12);
        h.assertTrue(npc.approach(p, "greet", 600), "approach not accepted");
        p.setData(ModAttachments.MEDITATION, MeditationState.started(true, 0));
        h.runAfterDelay(40, () -> {
            h.assertTrue(npc.approachKind() == null && !npc.approachArrived(), "he kept walking to a meditating player");
            h.getLevel().setDayTime(savedTime);
            h.succeed();
        });
    }

    /** A bowl held in the off hand goes cold like any other (the sweep only looked at the main inventory). */
    @GameTest(template = YARD, timeoutTicks = 40, batch = "sect_path_v2_bowl_offhand")
    public static void aStaleBowlInTheOffHandCoolsAndStays(GameTestHelper h) {
        ServerPlayer p = member(h, 4, 12);
        ItemStack bowl = new ItemStack(ModItems.MEAL_BOWL.get());
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putLong("expires", h.getLevel().getGameTime() - 1);
        bowl.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
        p.getInventory().offhand.set(0, bowl);
        ItemStack fresh = new ItemStack(ModItems.MEAL_BOWL.get());
        net.minecraft.nbt.CompoundTag later = new net.minecraft.nbt.CompoundTag();
        later.putLong("expires", h.getLevel().getGameTime() + 2000L);
        fresh.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(later));
        p.getInventory().items.set(3, fresh);
        h.assertTrue(SectMeals.collectStale(p) == 1, "the stale bowl in the off hand did not cool");
        h.assertTrue(p.getInventory().offhand.get(0) == bowl && bowl.getCount() == 1, "the off-hand bowl was removed or replaced");
        h.assertTrue(SectMeals.expiry(bowl) == 0L && bowl.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag()
                .getBoolean(io.github.verycooltimo.murim.item.MealBowlItem.COLD), "the off-hand bowl did not become permanently cold");
        h.assertTrue(bowl.get(net.minecraft.core.component.DataComponents.FOOD).equals(
                new ItemStack(ModItems.COLD_MEAL_BOWL.get()).get(net.minecraft.core.component.DataComponents.FOOD)),
                "the cooled bowl has the wrong food values");
        h.assertTrue(SectMeals.collectStale(p) == 0 && p.getInventory().offhand.get(0) == bowl, "the bowl cooled twice");
        h.assertTrue(p.getInventory().items.get(3).is(ModItems.MEAL_BOWL.get()), "a bowl inside its period was taken");
        h.succeed();
    }
}
