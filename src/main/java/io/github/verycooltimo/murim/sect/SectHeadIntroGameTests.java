package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static io.github.verycooltimo.murim.sect.SectPlaytestRig.clearYard;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.npc;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.only;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.player;

/** Author 22.10: the newcomer is presented to the head inside the Main Hall, once, led by the mentor; the hall is shut again after. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectHeadIntroGameTests {
    private static final String YARD = "sect_yard";

    private SectHeadIntroGameTests() { }

    private static SectAccess.Rule hall() {
        return SectAccess.RULES.stream().filter(r -> "main_hall".equals(r.id())).findFirst().orElseThrow();
    }

    private static boolean mayEnter(ServerPlayer p) {
        return SectAccess.allowed(hall(), SectService.standing(p), SectService.state(p).flags(), SectSchedule.Period.TRAINING);
    }

    private static ServerPlayer newcomer(GameTestHelper h) {
        ServerPlayer p = player(h, 12, 10, SectState.NONE.joined().with(SectArrival.PENDING).record("arrival.day", 1));
        SectArrival.accepted(p);
        return p;
    }

    /** Only's doorstep is at z=-1.5, beyond sect_yard and the x-axis escort road.
     * Keep the entire two-leg walk supported at the same height and entity-ticking.
     * Source: SectEscortArrivalGameTests.road and SectHierarchyGameTests.Only.at.
     */
    private static void hallApproach(GameTestHelper h) {
        clearYard(h);
        for (int x = 0; x <= 24; x++) {
            for (int z = -4; z <= 24; z++) {
                var floor = new net.minecraft.core.BlockPos(x, 1, z);
                h.setBlock(floor, net.minecraft.world.level.block.Blocks.STONE);
                for (int y = 2; y <= 5; y++)
                    h.setBlock(new net.minecraft.core.BlockPos(x, y, z), net.minecraft.world.level.block.Blocks.AIR);
            }
        }
        var a = h.absolutePos(new net.minecraft.core.BlockPos(0, 1, -4));
        var b = h.absolutePos(new net.minecraft.core.BlockPos(24, 1, 24));
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++)
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++)
                h.getLevel().setChunkForced(cx, cz, true);
    }

    /** Scene lifecycle tests skip the approach; walking is covered separately. */
    private static void stageHead(SectDisciple head, SectLayout layout) {
        head.reserveHeadIntro();
        head.sitImmediately(io.github.verycooltimo.murim.entity.SeatStyle.CHAIR,
                SectHeadIntro.seat(layout), layout.yaw(0, -1), null);
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "headintro1027_unseen")
    public static void unseenOutsideHeadIsSeatedBeforeEscortReachesDoors(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        hallApproach(h);
        var layout = only(h, "main_hall");
        var head = npc(h, layout, "tae_hwi", 2, 2, false);
        var mentor = npc(h, layout, "gyeong_pil", 12, 9, false);
        var p = newcomer(h);
        // Hide the outside head from the newcomer; the hall seat remains across the yard.
        for (int z = 0; z <= 24; z++)
            for (int y = 2; y <= 6; y++)
                h.setBlock(new net.minecraft.core.BlockPos(6, y, z), net.minecraft.world.level.block.Blocks.STONE);
        h.assertTrue(!p.hasLineOfSight(head), "fixture does not hide the outside head");
        h.assertTrue(head.position().distanceTo(SectHeadIntro.seat(layout)) > 5,
                "fixture head already at reception");
        h.assertTrue(SectHeadIntro.start(p, mentor), "escort did not start");
        h.assertTrue(head.sitting() && head.position().distanceTo(SectHeadIntro.seat(layout)) < 0.15D,
                "unseen head was not placed in the hall at escort start");
        var door = layout.at("main_hall", 0, -14);
        h.assertTrue(mentor.position().distanceTo(door) > 4, "mentor already reached the doors");
        p.moveTo(door.x, door.y, door.z, 0, 0);
        mentor.moveTo(door.x, door.y, door.z, 0, 0);
        SectHeadIntro.tick(p, layout);
        h.assertTrue(head.sitting() && head.position().distanceTo(SectHeadIntro.seat(layout)) < 0.15D,
                "preparation stood the head up at the doors");
        h.assertTrue(head.headIntroReserved(), "head lost his reservation before the scene");
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 1700, batch = "headintro1027_doors")
    public static void observedHeadWalksAndEscortWaitsOutside(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        var fixture = new EntryTestFixtures(h, "headintro1027_doors");
        fixture.force(h.absolutePos(new net.minecraft.core.BlockPos(0, 1, -4)),
                h.absolutePos(new net.minecraft.core.BlockPos(24, 1, 24)));
        hallApproach(h);
        fixture.whenReady(() -> {
            var layout = only(h, "main_hall");
            var head = fixture.track(npc(h, layout, "tae_hwi", 2, 2, true));
            var mentor = fixture.track(npc(h, layout, "gyeong_pil", 12, 9, false));
            var p = fixture.register(newcomer(h));
            fixture.whenTicking(() -> {
                long readyAt = h.getLevel().getGameTime();
                h.onEachTick(() -> h.assertTrue(h.getLevel().getGameTime() - readyAt <= 500,
                        "head introduction exceeded its movement budget on ready chunks"));
                head.setOnGround(true);
                var original = head.position();
                h.assertTrue(SectHeadIntro.start(p, mentor), "escort did not start");
                var door = layout.at("main_hall", 0, -14);
                p.moveTo(door.x, door.y, door.z, 0, 0);
                mentor.moveTo(door.x, door.y, door.z, 0, 0);
                mentor.escortGoal().tick();
                SectHeadIntro.tick(p, layout);
                h.assertTrue(mentor.escortGoal().holding() && !SectHeadIntro.active(p), "escort entered before head was ready");
                h.assertTrue(head.position().distanceTo(original) < 0.01, "preparation teleported the observed head");
                h.assertTrue(head.headIntroReserved(), "schedule can reclaim summoned head");
                var last = new net.minecraft.world.phys.Vec3[] {head.position()};
                h.onEachTick(() -> {
                    // Strict even without LOS: navigation, approach and seating must remain continuous.
                    h.assertTrue(head.position().distanceTo(last[0]) < 1.5D, "head popped during observed approach");
                    last[0] = head.position();
                    SectHeadIntro.tick(p, layout);
                });
                h.succeedWhen(() -> {
                    h.assertTrue(!mentor.escortGoal().holding(), "escort never resumed from the doors");
                    h.assertTrue(head.sitting() && head.position().distanceTo(SectHeadIntro.seat(layout)) < 0.15D,
                            "escort passed doors before the head was seated inside");
                });
            }, head, mentor);
        });
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "headintro1025_hold")
    public static void mentorWaitsBesideNewcomerUntilSceneHandoff(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        hallApproach(h);
        var layout = only(h, "main_hall");
        SectDisciple mentor = npc(h, layout, "gyeong_pil", 12, 9, false);
        // Keep the head away from his seat so arrival exercises the bounded waiting state.
        SectDisciple head = npc(h, layout, "tae_hwi", 2, 2, false);
        ServerPlayer p = newcomer(h);
        h.assertTrue(SectHeadIntro.start(p, mentor), "escort did not begin");
        var at = layout.at("main_hall", 0, -14);
        p.moveTo(at.x, at.y, at.z, 0, 0);
        // The player reaching the hall alone must not end the walk.
        mentor.escortGoal().tick();
        h.assertTrue(!mentor.escortGoal().holding(), "mentor arrived while still away from player");
        mentor.moveTo(at.x + 1, at.y, at.z, 0, 0);
        mentor.escortGoal().tick();
        h.assertTrue(mentor.escortGoal().holding() && mentor.escorting(), "mentor left before head scene");
        h.assertTrue(p.getPersistentData().contains(SectHeadIntro.WAITING)
                && !SectHeadIntro.active(p) && !SectService.state(p).has(SectHeadIntro.PRESENTED),
                "arrival must wait for the head without completing the introduction");
        mentor.escortGoal().tick();
        h.assertTrue(mentor.escortGoal().holding(), "schedule reclaimed waiting mentor");
        var seat = SectHeadIntro.seat(layout);
        head.reserveHeadIntro();
        head.sitImmediately(io.github.verycooltimo.murim.entity.SeatStyle.CHAIR, seat, layout.yaw(0, -1), null);
        SectHeadIntro.tick(p, layout);
        var inside = EscortRoutes.headIntroduction(layout);
        p.moveTo(inside.x, inside.y, inside.z, 0, 0);
        mentor.moveTo(inside.x, inside.y, inside.z, 0, 0);
        mentor.escortGoal().tick();
        h.assertTrue("murim.sect.head_intro.here".equals(mentor.bubbleKey()), "mentor did not introduce head at scene handoff");
        h.assertTrue(SectHeadIntro.involves(p, head) && mentor.escortGoal().holding() && mentor.escorting(),
                "scene staging released the waiting mentor");
        mentor.escortGoal().tick();
        h.assertTrue(mentor.escortGoal().holding(), "mentor left during the head scene");
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 1500, batch = "headintro1022_escort")
    public static void theEscortLetsTheNewcomerInAndTheHeadReceivesHim(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        // The arrival callback runs in the mentor's AI tick. Mock players do not keep
        // chunks ticking; the approach fixture holds tickets for both escort legs.
        var fixture = new EntryTestFixtures(h, "headintro1022_escort");
        fixture.force(h.absolutePos(new net.minecraft.core.BlockPos(0, 1, -4)),
                h.absolutePos(new net.minecraft.core.BlockPos(24, 1, 24)));
        hallApproach(h);
        fixture.whenReady(() -> {
            var layout = only(h, "main_hall");
            SectDisciple head = fixture.track(npc(h, layout, "tae_hwi", 12, 16, true));
            SectDisciple mentor = fixture.track(npc(h, layout, "gyeong_pil", 12, 9, true));
            ServerPlayer p = fixture.register(newcomer(h));
            fixture.whenTicking(() -> {
                long readyAt = h.getLevel().getGameTime();
                h.onEachTick(() -> h.assertTrue(h.getLevel().getGameTime() - readyAt <= 300,
                        "head introduction exceeded its movement budget on ready chunks"));
                h.assertTrue(!mayEnter(p), "a junior may enter the hall without the escort");
                h.assertTrue(SectHeadIntro.canOffer(p, mentor), "the mentor cannot offer the introduction");
                h.assertTrue(SectHeadIntro.start(p, mentor), "the escort did not start");
                h.assertTrue(mayEnter(p), "the door guards would not let the escorted newcomer in");
                int initialTicks = mentor.tickCount;
                h.succeedWhen(() -> {
                    var destination = EscortRoutes.headIntroduction(layout);
                    p.moveTo(destination.x, destination.y, destination.z, 0, 0);
                    h.assertTrue(mentor.tickCount > initialTicks + 20,
                            "the hall fixture is not entity-ticking at the mentor");
                    h.assertTrue(SectHeadIntro.involves(p, head), "the cutscene did not start from escort arrival");
                    h.assertTrue(!p.getData(ModAttachments.DIALOGUE).active(), "the cutscene opened a dialogue window");
                    h.assertTrue(head.sitting(), "the head is standing during his scene");
                });
            }, head, mentor);
        });
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "headintro1022_done")
    public static void theCeremonyGrantsTheSameMarksAndTheHallIsShutAgain(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        clearYard(h);
        var layout = only(h, "main_hall");
        SectDisciple head = npc(h, layout, "tae_hwi", 12, 16, false);
        SectDisciple mentor = npc(h, layout, "gyeong_pil", 12, 9, false);
        ServerPlayer p = newcomer(h);
        h.assertTrue(SectHeadIntro.start(p, mentor), "the escort did not start");
        h.assertTrue(SectHeadIntro.complete(p, head), "the head's word was refused");
        SectState s = SectService.state(p);
        h.assertTrue(!s.has(SectArrival.PENDING) && s.has(SectHeadIntro.PRESENTED), "not the same marks as the outdoor ceremony");
        h.assertTrue(!p.serverLevel().getDataStorage().computeIfAbsent(SectHousingData.FACTORY, "murim_sect_rooms").newcomers.containsKey(p.getUUID().toString()),
                "the register still waits for the outdoor ceremony");
        h.assertTrue(mayEnter(p), "the pass was taken at once, before he walked out");
        // he walks out of the hall
        p.setPos(p.getX() + 30.0D, p.getY(), p.getZ());
        SectHeadIntro.tick(p, layout);
        h.assertTrue(!SectService.state(p).has(SectAccess.HEAD_ESCORT) && !mayEnter(p), "the hall stayed open to him");
        h.assertTrue(!SectHeadIntro.canOffer(p, mentor), "he can be presented a second time");
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "headintro1022_absent")
    public static void anAbsentHeadIsCalledToHisSeatAndMissingActorsCannotBlockProgression(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        clearYard(h);
        var layout = only(h, "main_hall");
        SectDisciple head = npc(h, layout, "tae_hwi", 2, 2, false);
        SectDisciple mentor = npc(h, layout, "gyeong_pil", 12, 9, false);
        SectPlaytestRig.bed(h, 2, 2);
        head.startSleeping(h.absolutePos(new net.minecraft.core.BlockPos(2, 2, 3)));
        h.assertTrue(head.isSleeping(), "head fixture did not sleep");
        ServerPlayer p = newcomer(h);
        h.assertTrue(SectHeadIntro.start(p, mentor), "the escort did not start");
        long now = h.getLevel().getGameTime();
        // An observed sleeping actor is woken and asked to WALK, never popped into the hall.
        var original = head.position();
        p.getPersistentData().putLong(SectHeadIntro.WAITING, now - SectHeadIntro.SUMMON_AFTER - 1);
        SectHeadIntro.tick(p, layout);
        h.assertTrue(!head.isSleeping() && head.position().distanceTo(original) < 1.0D,
                "summoning visibly teleported the head");
        head.reserveHeadIntro();
        head.sitImmediately(io.github.verycooltimo.murim.entity.SeatStyle.CHAIR, SectHeadIntro.seat(layout), 0, null);
        SectHeadIntro.tick(p, layout);
        SectHeadIntro.arrived(p, mentor);
        h.assertTrue(SectHeadIntro.involves(p, head), "no cutscene after the head came");
        SectHeadIntro.finish(p, layout);
        // A second newcomer: an absent head must not block the journey.
        ServerPlayer q = player(h, 13, 10, SectState.NONE.joined().with(SectArrival.PENDING).record("arrival.day", 1));
        head.discard();
        mentor.escortGoal().cancel();
        h.assertTrue(SectHeadIntro.start(q, mentor), "the second escort did not start");
        q.getPersistentData().putLong(SectHeadIntro.WAITING, now - SectHeadIntro.GIVE_UP - 1);
        SectHeadIntro.tick(q, layout);
        SectState s = SectService.state(q);
        h.assertTrue(!s.has(SectArrival.PENDING) && s.has(SectHeadIntro.PRESENTED),
                "the absent head blocked journey progression");
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "headintro1025_flow")
    public static void cutsceneRejectsEarlySkipAndCompletesExactlyOnce(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        clearYard(h);
        var layout = only(h, "main_hall");
        var head = npc(h, layout, "tae_hwi", 12, 16, false);
        var mentor = npc(h, layout, "gyeong_pil", 12, 9, false);
        var p = newcomer(h);
        h.assertTrue(!p.connection.hasChannel(io.github.verycooltimo.murim.network.HeadIntroPayloads.View.TYPE),
                "fixture must exercise scene start and finish without a client channel");
        h.assertTrue(SectHeadIntro.start(p, mentor), "escort failed");
        mentor.escortGoal().cancel();
        stageHead(head, layout);
        SectHeadIntro.arrived(p, mentor);
        h.assertTrue(SectHeadIntro.active(p), "arrival did not begin scene");
        h.assertTrue(head.sitting() && head.seatAnchor().distanceTo(head.position()) < 0.01,
                "first shot must start with the head already on his seat");
        h.assertTrue(head.pose() == io.github.verycooltimo.murim.entity.SectPose.SIT && head.anim().isEmpty(),
                "a standing schedule clip must not override the head seated pose");
        h.assertTrue(Math.abs(head.position().distanceTo(p.position()) - 5) < 0.1,
                "newcomer must be staged in front of the head");
        head.releaseStaleTalker();
        new io.github.verycooltimo.murim.entity.ScheduleGoal(head).stop();
        h.assertTrue(head.talkingTo() == p.getId() && head.sitting(), "scene lost its seated actor lock");
        long start = p.getPersistentData().getLong(SectHeadIntro.SCENE);
        SectHeadIntro.skip(p, start);
        h.assertTrue(SectHeadIntro.active(p) && !SectService.state(p).has(SectHeadIntro.PRESENTED), "early skip was accepted");
        long eligible = h.getLevel().getGameTime() - SectHeadIntro.SKIP_AFTER;
        p.getPersistentData().putLong(SectHeadIntro.SCENE, eligible);
        SectHeadIntro.skip(p, eligible + 1);
        h.assertTrue(SectHeadIntro.active(p), "stale scene token accepted");
        SectHeadIntro.skip(p, eligible);
        h.assertTrue(!SectHeadIntro.active(p) && head.talkingTo() < 0, "skip left scene ownership behind");
        h.assertTrue(SectService.state(p).has(SectHeadIntro.PRESENTED)
                && !SectService.state(p).has(SectArrival.PENDING), "skip lost ceremony progression");
        long done = p.getPersistentData().getLong(SectHeadIntro.DONE_AT);
        SectHeadIntro.finish(p, layout);
        h.assertTrue(p.getPersistentData().getLong(SectHeadIntro.DONE_AT) == done, "completion ran twice");
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "headintro1025_exit")
    public static void timeoutAndMissingHeadReachTheSameEndState(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        clearYard(h);
        var layout = only(h, "main_hall");
        var head = npc(h, layout, "tae_hwi", 12, 16, false);
        var mentor = npc(h, layout, "gyeong_pil", 12, 9, false);
        var p = newcomer(h);
        h.assertTrue(SectHeadIntro.start(p, mentor), "escort failed");
        mentor.escortGoal().cancel();
        stageHead(head, layout);
        SectHeadIntro.arrived(p, mentor);
        p.getPersistentData().putLong(SectHeadIntro.SCENE, h.getLevel().getGameTime() - SectHeadIntro.SCENE_TICKS);
        SectHeadIntro.advance(p, layout);
        h.assertTrue(!SectHeadIntro.active(p) && SectService.state(p).has(SectHeadIntro.PRESENTED), "timeout did not finish");
        mentor.escortGoal().cancel();
        var q = player(h, 13, 10, SectState.NONE.joined().with(SectArrival.PENDING));
        h.assertTrue(SectHeadIntro.start(q, mentor), "second escort failed");
        mentor.escortGoal().cancel();
        stageHead(head, layout);
        SectHeadIntro.arrived(q, mentor);
        h.assertTrue(SectHeadIntro.active(q), "second scene failed");
        head.discard();
        SectHeadIntro.advance(q, layout);
        h.assertTrue(!SectHeadIntro.active(q) && SectService.state(q).has(SectHeadIntro.PRESENTED)
                && !SectService.state(q).has(SectArrival.PENDING), "missing actor lost progression");
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "headintro1025_damage")
    public static void damageReleasesTheCameraOwnerAndAdvancesTheJourney(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        clearYard(h);
        var layout = only(h, "main_hall");
        var head = npc(h, layout, "tae_hwi", 12, 16, false);
        var mentor = npc(h, layout, "gyeong_pil", 12, 9, false);
        var p = newcomer(h);
        h.assertTrue(SectHeadIntro.start(p, mentor), "escort failed");
        mentor.escortGoal().cancel();
        stageHead(head, layout);
        SectHeadIntro.arrived(p, mentor);
        // Exercise the subscribed damage handler without relying on the fake player's network tick.
        SectHeadIntro.damaged(new net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post(p,
                new net.neoforged.neoforge.common.damagesource.DamageContainer(p.damageSources().generic(), 1)));
        h.assertTrue(!SectHeadIntro.active(p) && head.talkingTo() < 0, "damage left scene ownership behind");
        h.assertTrue(SectService.state(p).has(SectHeadIntro.PRESENTED)
                && !SectService.state(p).has(SectArrival.PENDING), "damage lost journey progression");
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "headintro_dev")
    public static void devCommandStagesAFreshMemberAndStartsTheScene(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        clearYard(h);
        var layout = only(h, "main_hall");
        // A previous yard may leave busy actors with the same roster keys in this level.
        var other = new SectHierarchyGameTests.Only(layout.origin().add(128, 0, 0), java.util.Set.of("main_hall"));
        var otherMentor = npc(h, other, "gyeong_pil", 140, 9, false);
        var otherHead = npc(h, other, "tae_hwi", 140, 16, false);
        var otherPlayer = player(h, 140, 10, SectState.NONE.joined());
        otherMentor.setTalkingTo(otherPlayer);
        otherHead.setTalkingTo(otherPlayer);
        var mentor = npc(h, layout, "gyeong_pil", 12, 9, false);
        var p = player(h, 12, 10, SectState.NONE.joined().with(SectHeadIntro.PRESENTED)
                .with(SectPositions.ELDER).record("old.progress", 9));
        h.getLevel().getServer().getCommands().performPrefixedCommand(
                p.createCommandSourceStack().withPermission(2), "murim dev head intro");
        SectState s = SectService.state(p);
        h.assertTrue(s.member() && s.generation() == SectState.THIRD && s.has(SectArrival.PENDING)
                && s.has(SectAccess.HEAD_ESCORT) && !s.has(SectHeadIntro.PRESENTED)
                && !s.has(SectPositions.ELDER) && s.entry("old.progress", -1) == -1,
                "command did not reset journey to the fresh member's introduction");
        // Only has no authored dais: the fallback seat is at v=4, facing north, with the player five blocks ahead.
        h.assertTrue(p.position().distanceTo(SectLife.stand(p.level(), layout.at("main_hall", 0, -1))) < 0.1,
                "player is not at the scene start");
        var heads = p.level().getEntitiesOfClass(io.github.verycooltimo.murim.entity.SectDisciple.class,
                p.getBoundingBox().inflate(24), d -> d.role() == SectRole.LEADER);
        h.assertTrue(heads.size() == 1 && SectHeadIntro.involves(p, heads.getFirst())
                && heads.getFirst().sitting(), "command did not spawn and seat the scene actor");
        h.assertTrue(mentor.position().distanceTo(p.position()) < 3 && !mentor.escorting(),
                "mentor is not beside the newcomer");
        h.assertTrue(!p.getData(ModAttachments.DIALOGUE).active(), "scene opened dialogue choices");
        // Repeat from an active scene: reuse the actors and clear the completion marks again.
        h.getLevel().getServer().getCommands().performPrefixedCommand(
                p.createCommandSourceStack().withPermission(2), "murim dev head intro");
        h.assertTrue(SectHeadIntro.involves(p, heads.getFirst())
                && !SectService.state(p).has(SectHeadIntro.PRESENTED), "repeat command failed");
        h.assertTrue(otherMentor.layout() == other && otherHead.layout() == other
                && otherMentor.talkingTo() == otherPlayer.getId() && otherHead.talkingTo() == otherPlayer.getId(),
                "command disturbed another yard's actors");
        SectHeadIntro.finish(p, layout);
        mentor.escortGoal().cancel();
        otherMentor.discard();
        otherHead.discard();
        h.succeed();
    }

    /** Pick the actual row sent by the server, preserving condition filtering and synthetic exit rows. */
    private static void reply(GameTestHelper h, ServerPlayer p, String key) {
        var session = p.getData(ModAttachments.DIALOGUE);
        var node = DialogueLoader.get(session.dialogue()).nodes().get(session.node());
        for (int slot = 0; slot < session.shown().size(); slot++) {
            int index = session.shown().get(slot);
            if (index >= 0 && index < node.options().size() && key.equals(node.options().get(index).text())) {
                DialogueService.choose(p, slot);
                return;
            }
        }
        h.assertTrue(false, "reply not shown: " + key + " at " + session.node());
    }

    @GameTest(template = YARD, timeoutTicks = 700, batch = "join1026_reception")
    public static void gatekeeperContinuesAndWaitsForTheRealGuideWelcome(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        SectEscortArrivalGameTests.road(h, 48);
        clearYard(h);
        var layout = only(h, "sect_gate", "mentor");
        var guard = npc(h, layout, "gatekeeper", 12, 12, true);
        var guide = npc(h, layout, "disciple_a", 30, 12, true);
        var mentor = npc(h, layout, "gyeong_pil", 45, 12, false);
        var p = player(h, 12, 10, SectState.NONE);
        var gatePosition = guard.position();
        long[] started = {-1L};
        h.onEachTick(() -> {
            if (started[0] >= 0L) return;
            // Source: SectEscortArrivalGameTests#theGuideComesToTheNewcomerWhereHeStands.
            // Forced chunks start ticking asynchronously; do not spend the walk budget on startup.
            if (guard.tickCount == 0 || guide.tickCount == 0 || mentor.tickCount == 0) return;
            started[0] = h.getLevel().getGameTime();
            h.assertTrue(DialogueService.openDialogue(p, guard, SectArrival.DIALOGUE, "question"), "gate talk failed");
            reply(h, p, "dialogue.murim.arrival.opt.learn");
            var next = p.getData(ModAttachments.DIALOGUE);
            h.assertTrue(next.active() && next.npc() == guard.getId() && next.node().equals("invited"),
                    "asking to join closed or changed the speaker of the talk");
            h.assertTrue(!guide.escortGoal().forPlayer(p) && !guard.awaitingGuide(p)
                    && !SectService.state(p).has(SectArrival.ORDERED), "study answer summoned the guide");
            reply(h, p, "dialogue.murim.arrival.opt.follow");
            h.assertTrue(guide.escortGoal().forPlayer(p), "explicit request did not summon the guide");
            // Closing the screen must not send the gatekeeper away before the guide's greeting.
            DialogueService.choose(p, -1);
            guard.releaseStaleTalker();
            h.assertTrue(guard.awaitingGuide(p) && guard.talkingTo() == p.getId(), "gatekeeper left the reception");
        });
        h.succeedWhen(() -> {
            h.assertTrue(started[0] >= 0L, "reception road has not started entity-ticking");
            h.assertTrue(guard.position().distanceToSqr(gatePosition) < 1, "gatekeeper walked away before handoff");
            h.assertTrue(guide.bubbleKey().startsWith("murim.bubble.guide_welcome"),
                    "guide has not greeted the newcomer: " + guide.escortGoal().debug());
            h.assertTrue(h.getLevel().getGameTime() - started[0] <= 300L, "guide greeting exceeded the walk budget");
            h.assertTrue(!guard.awaitingGuide(p) && guard.talkingTo() < 0, "welcome did not release the gatekeeper");
        });
    }

    @GameTest(template = YARD, timeoutTicks = 1500, batch = "join1026_tour_head")
    public static void joiningAndEveryTourStopLeadIntoTheRealHeadEscort(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        var fixture = new EntryTestFixtures(h, "join1026_tour_head");
        fixture.force(h.absolutePos(new net.minecraft.core.BlockPos(0, 1, -4)),
                h.absolutePos(new net.minecraft.core.BlockPos(24, 1, 24)));
        hallApproach(h);
        fixture.whenReady(() -> {
            var layout = only(h, "main_hall", "mentor");
            var mentor = fixture.track(npc(h, layout, "gyeong_pil", 12, 9, true));
            var head = fixture.track(npc(h, layout, "tae_hwi", 12, 16, true));
            var p = fixture.register(player(h, 12, 10, SectState.NONE.with(SectArrival.INVITED)));
            fixture.whenTicking(() -> {
                long readyAt = h.getLevel().getGameTime();
                h.onEachTick(() -> h.assertTrue(h.getLevel().getGameTime() - readyAt <= 300,
                        "head introduction exceeded its movement budget on ready chunks"));
                DialogueService.open(p, mentor);
                reply(h, p, "dialogue.murim.arrival.opt.accept");
                h.assertTrue(SectService.state(p).member() && SectService.state(p).has(SectArrival.PENDING), "join lost pending intro");
                for (String stop : new String[] {"tour_hall", "tour_dining", "tour_square", "tour_board", "tour_bed"}) {
                    h.assertTrue(p.getData(ModAttachments.DIALOGUE).node().equals(stop), "tour skipped " + stop);
                    reply(h, p, "dialogue.murim.tour.opt.next");
                }
                h.assertTrue(p.getData(ModAttachments.DIALOGUE).node().equals("tour_end"), "tour did not end");
                // The ordinary first answer must lead to the head, regardless of awakening or basics flags.
                DialogueService.choose(p, 0);
                h.assertTrue(mentor.escortGoal().forPlayer(p) && mayEnter(p), "tour did not grant the hall escort exception");
                h.assertTrue(!SectService.state(p).has("arrival.tour") && !p.getData(ModAttachments.DIALOGUE).active(),
                        "tour retained its dialogue or resume mark");
                // The real goal selector walks the mentor; player fast-forward is allowed by this regression fixture.
                var destination = EscortRoutes.headIntroduction(layout);
                p.moveTo(destination.x, destination.y, destination.z, 0, 0);
                h.succeedWhen(() -> {
                    h.assertTrue(SectHeadIntro.involves(p, head), "normal tour never reached the head cutscene");
                    h.assertTrue("murim.sect.head_intro.here".equals(mentor.bubbleKey()), "mentor did not say here he is");
                    h.assertTrue(mentor.escortGoal().holding(), "mentor left during the introduction");
                    SectHeadIntro.finish(p, layout);
                    h.assertTrue(SectService.state(p).has(SectHeadIntro.PRESENTED)
                            && !SectService.state(p).has(SectArrival.PENDING), "introduction did not finish progression");
                });
            }, head, mentor);
        });
    }

    @net.minecraft.gametest.framework.AfterBatch(batch = "headintro1027_doors")
    public static void cleanupObservedHeadWalksAndEscortWaitsOutside(net.minecraft.server.level.ServerLevel level) {
        EntryTestFixtures.closeBatch("headintro1027_doors");
    }

    @net.minecraft.gametest.framework.AfterBatch(batch = "headintro1022_escort")
    public static void cleanupTheEscortLetsTheNewcomerInAndTheHeadReceivesHim(net.minecraft.server.level.ServerLevel level) {
        EntryTestFixtures.closeBatch("headintro1022_escort");
    }

    @net.minecraft.gametest.framework.AfterBatch(batch = "join1026_tour_head")
    public static void cleanupJoiningAndEveryTourStopLeadIntoTheRealHeadEscort(net.minecraft.server.level.ServerLevel level) {
        EntryTestFixtures.closeBatch("join1026_tour_head");
    }
}
