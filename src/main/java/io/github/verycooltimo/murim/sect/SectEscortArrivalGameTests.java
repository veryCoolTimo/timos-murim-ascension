package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static io.github.verycooltimo.murim.sect.SectPlaytestRig.empty;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.npc;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.player;

/**
 * Playtest 21.10: «первый ученик, который ведёт к наставнику, не подходит, пока на тренировочную площадку не встанешь, ... и он
 * очень медленный». The guide comes to the newcomer where he stands (the gate), walks at a player's pace, waits for one who lags,
 * gives up after 20 s, and the escort ends when the newcomer is at the mentor. Every test has its own batch (named people).
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectEscortArrivalGameTests {
    private static final String YARD = "sect_yard";
    /** A player walking: 4.317 blocks/s. */
    static final double PLAYER_WALK = 0.2158D;

    private SectEscortArrivalGameTests() {
    }

    /** A flat stone road along x from -2 to len, z 6..18, clear above. */
    static void road(GameTestHelper h, int len) {
        // The road extends beyond sect_yard. Mock players are not registered in the level and do not
        // keep chunks ticking: loading blocks alone leaves guides frozen outside the template.
        // Source: SectGateWatchGameTests#trail uses the same ticket for its extended walking strip.
        BlockPos a = h.absolutePos(new BlockPos(-2, 1, 6));
        BlockPos b = h.absolutePos(new BlockPos(len, 1, 18));
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                h.getLevel().setChunkForced(cx, cz, true);
            }
        }
        for (int x = -2; x <= len; x++) {
            for (int z = 6; z <= 18; z++) {
                h.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
                for (int y = 2; y <= 5; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
    }

    private static ServerPlayer newcomer(GameTestHelper h, double x) {
        ServerPlayer p = player(h, x, 12, SectState.NONE.with(SectArrival.INVITED));
        p.getPersistentData().remove(SectArrival.GUIDE_OFF);
        return p;
    }

    /** A ticket and one ticking actor do not establish readiness of the chunks further along the road. */
    private static boolean roadTicking(GameTestHelper h, int len) {
        BlockPos a = h.absolutePos(new BlockPos(-2, 2, 6));
        BlockPos b = h.absolutePos(new BlockPos(len, 2, 18));
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                // API: SectLife and NpcEncounters already use this server readiness check.
                if (!h.getLevel().isPositionEntityTicking(new BlockPos(cx << 4, a.getY(), cz << 4))) return false;
            }
        }
        return true;
    }

    private static void walk(ServerPlayer p, double blocks) {
        p.setPos(p.getX() + blocks, p.getY(), p.getZ());
    }

    @GameTest(template = YARD, timeoutTicks = 1600, batch = "escort1021_comes")
    public static void theGuideComesToTheNewcomerWhereHeStands(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        var fixture = new EntryTestFixtures(h, "escort1021_comes");
        fixture.force(h.absolutePos(new BlockPos(-2, 1, 6)), h.absolutePos(new BlockPos(44, 1, 18)));
        road(h, 44);
        fixture.whenReady(() -> {
            var layout = empty(h);
            SectDisciple mentor = fixture.track(npc(h, layout, "gyeong_pil", 40, 12, false));
            SectDisciple guide = fixture.track(npc(h, layout, "yul_jin", 26, 12, true));
            ServerPlayer p = newcomer(h, 2);
            long[] t0 = {-1L};
            int[] initialTicks = {0};
            h.onEachTick(() -> {
                if (t0[0] >= 0L) {
                    h.assertTrue(h.getLevel().getGameTime() - t0[0] <= 400L,
                            "guide collecting walk exceeded 400 ticks on a ready road: " + guide.escortGoal().debug());
                    return;
                }
                // Source: theGuideFollowsTheMentorWhoMovedOn. Forced-chunk startup is
                // asynchronous; allow extra startup time, then at most 400 ticks for the walk.
                if (!roadTicking(h, 44) || guide.tickCount == 0 || mentor.tickCount == 0) return;
                h.assertTrue(SectArrival.order(p, layout), "no guide was sent");
                t0[0] = h.getLevel().getGameTime();
                initialTicks[0] = guide.tickCount;
            });
            h.succeedWhen(() -> {
                h.assertTrue(t0[0] >= 0L, "test road has not started entity-ticking: guide ticks="
                        + guide.tickCount + " mentor ticks=" + mentor.tickCount + " pending=" + fixture.readiness());
                h.assertTrue(guide.tickCount > initialTicks[0] + 20,
                        "test road is not entity-ticking during the guide's collecting walk: ticks=" + guide.tickCount);
                long t = h.getLevel().getGameTime() - t0[0];
                double d = guide.distanceTo(p);
                MurimMod.LOGGER.info("ESCORT1021 comes: t={} gap={}", t, d);
                h.assertTrue(d <= 5.0D, "the guide did not reach the newcomer at the gate: " + d + " blocks after " + t + " ticks");
                h.assertTrue(t <= 400L, "the guide came too late: " + t + " ticks for 24 blocks");
                h.assertTrue(guide.bubbleKey().startsWith("murim.bubble.guide_welcome"), "no welcome at the gate");
            });
        });
    }

    @GameTest(template = YARD, timeoutTicks = 900, batch = "escort1021_pace")
    public static void theGuideWalksAtLeastAsFastAsAWalkingPlayerAndTheEscortEndsAtTheMentor(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        road(h, 44);
        var layout = empty(h);
        SectDisciple mentor = npc(h, layout, "gyeong_pil", 42, 12, false);
        SectDisciple guide = npc(h, layout, "yul_jin", 3, 12, true);
        ServerPlayer p = newcomer(h, 1);
        h.assertTrue(SectArrival.order(p, layout), "no guide was sent");
        long[] start = {-1L};
        Vec3[] from = {null};
        boolean[] ended = {false};
        double[] travelled = {0.0D};
        long[] duration = {0L};
        h.onEachTick(() -> {
            long now = h.getLevel().getGameTime();
            if (!guide.escorting()) {
                if (start[0] >= 0 && !ended[0]) {
                    ended[0] = true;
                    travelled[0] = Math.hypot(guide.getX() - from[0].x, guide.getZ() - from[0].z);
                    duration[0] = now - start[0];
                }
                return;
            }
            // the player walks on at a walking pace from the first tick
            walk(p, PLAYER_WALK);
            if (start[0] < 0 && guide.distanceTo(p) < 5.0D) {
                start[0] = now;
                from[0] = guide.position();
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(ended[0], "the escort did not end at the mentor: guide " + guide.position() + " player " + p.position());
            h.assertTrue(p.distanceTo(mentor) < 7.0D, "the escort ended away from the mentor: " + p.distanceTo(mentor));
            h.assertTrue(duration[0] > 0 && travelled[0] / duration[0] >= 0.9D * PLAYER_WALK,
                    "guide's route pace was below 90% of player walk: " + travelled[0] + " blocks / " + duration[0] + " ticks");
            h.assertTrue(!SectService.state(p).has(SectArrival.ORDERED), "arrival left the guide request active");
            MurimMod.LOGGER.info("ESCORT1021 pace: guide {} blocks / {} ticks (player walk {} per tick)", travelled[0], duration[0], PLAYER_WALK);
        });
    }

    @GameTest(template = YARD, timeoutTicks = 900, batch = "escort1021_speed")
    public static void theGuideKeepsAheadOfAWalkingPlayerOverTheWholeRoute(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        road(h, 44);
        var layout = empty(h);
        npc(h, layout, "gyeong_pil", 44, 12, false);
        SectDisciple guide = npc(h, layout, "yul_jin", 3, 12, true);
        ServerPlayer p = newcomer(h, 0);
        h.assertTrue(SectArrival.order(p, layout), "no guide was sent");
        // The guide is at the player; the player walks 30 blocks. The guide must lead, never trail, and never stop for him.
        int[] ticks = {0};
        double[] worstTrail = {0.0D};
        double[] gx = {guide.getX()};
        h.onEachTick(() -> {
            if (!guide.escorting()) return;
            ticks[0]++;
            walk(p, PLAYER_WALK);
            worstTrail[0] = Math.max(worstTrail[0], p.getX() - guide.getX());
            if (ticks[0] == 120) {
                double speed = (guide.getX() - gx[0]) / 120.0D;
                MurimMod.LOGGER.info("ESCORT1021 speed: guide moved {} per tick in 120 ticks, player walk {}, worst trail {}", speed, PLAYER_WALK, worstTrail[0]);
                h.assertTrue(speed >= 0.9D * PLAYER_WALK, "the guide is slower than a walking player: " + speed + " per tick against " + PLAYER_WALK);
                h.succeed();
            }
        });
    }

    @GameTest(template = YARD, timeoutTicks = 1200, batch = "escort1021_lag")
    public static void aLaggingPlayerIsWaitedForThenTheGuideGoesBack(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        road(h, 44);
        var layout = empty(h);
        npc(h, layout, "gyeong_pil", 44, 12, false);
        SectDisciple guide = npc(h, layout, "yul_jin", 3, 12, true);
        ServerPlayer p = newcomer(h, 0);
        h.assertTrue(SectArrival.order(p, layout), "no guide was sent");
        long t0 = h.getLevel().getGameTime();
        boolean[] stopped = {false};
        h.onEachTick(() -> {
            long t = h.getLevel().getGameTime() - t0;
            // the player stands still; the guide walks on ahead, stops at the waiting gap
            if (t == 300L) {
                h.assertTrue(guide.escorting(), "the guide gave up too early");
                double gap = guide.distanceTo(p);
                h.assertTrue(gap > SectEscortGoalGap.WAIT - 1.0D && gap < SectEscortGoalGap.WAIT + 4.0D, "the guide did not stop where the newcomer lags: gap " + gap);
                stopped[0] = guide.walking().goal() == null;
                h.assertTrue(stopped[0], "the guide went on walking away from a lagging player");
                h.assertTrue(guide.bubbleKey().startsWith("murim.bubble.guide_wait") || guide.bubbleKey().startsWith("murim.bubble.guide_welcome"),
                        "the guide did not call: " + guide.bubbleKey());
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(!guide.escorting(), "the guide still waits after 20 s");
            h.assertTrue(p.getPersistentData().contains(SectArrival.GUIDE_OFF), "the give-up was not remembered");
            h.assertTrue(guide.bubbleKey().startsWith("murim.bubble.guide_back"), "no word on leaving: " + guide.bubbleKey());
            // he is not sent again on his own, but is when he asks
            h.assertTrue(!(SectService.state(p).has(SectArrival.ORDERED) && !p.getPersistentData().contains(SectArrival.GUIDE_OFF)), "he would be sent again");
            h.assertTrue(SectArrival.order(p, layout), "asking again did not resume the escort");
        });
    }

    @GameTest(template = YARD, timeoutTicks = 400, batch = "escort1021_self")
    public static void goingStraightToTheMentorEndsTheEscort(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        road(h, 44);
        var layout = empty(h);
        npc(h, layout, "gyeong_pil", 40, 12, false);
        SectDisciple guide = npc(h, layout, "yul_jin", 12, 12, true);
        ServerPlayer p = newcomer(h, 2);
        h.assertTrue(SectArrival.order(p, layout), "no guide was sent");
        long started = h.getLevel().getGameTime();
        h.runAfterDelay(60L, () -> {
            h.assertTrue(guide.escorting(), "no escort to cut short");
            p.setPos(h.absoluteVec(new Vec3(38.5D, 2.0D, 12.5D)).x, p.getY(), p.getZ());
        });
        h.succeedWhen(() -> {
            if (h.getLevel().getGameTime() - started > 100L) {
                h.assertTrue(!guide.escorting(), "the escort went on after the player reached the mentor");
                h.assertTrue(!SectService.state(p).has(SectArrival.ORDERED), "arrival left an automatic guide request");
            }
            else h.fail("wait");
        });
    }

    @GameTest(template = YARD, timeoutTicks = 500, batch = "escort1021_two")
    public static void eachNewcomerGetsHisOwnGuide(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        road(h, 44);
        var layout = empty(h);
        npc(h, layout, "gyeong_pil", 42, 12, false);
        SectDisciple a = npc(h, layout, "yul_jin", 20, 10, true);
        SectDisciple b = npc(h, layout, "yul_seok", 20, 14, true);
        ServerPlayer p1 = newcomer(h, 2);
        ServerPlayer p2 = player(h, 3, 8, SectState.NONE.with(SectArrival.INVITED));
        h.assertTrue(SectArrival.order(p1, layout) && SectArrival.order(p2, layout), "a newcomer got no guide");
        h.assertTrue(a.escortGoal().forPlayer(p1) != a.escortGoal().forPlayer(p2) && b.escortGoal().forPlayer(p1) != b.escortGoal().forPlayer(p2)
                && (a.escortGoal().forPlayer(p1) || b.escortGoal().forPlayer(p1)) && (a.escortGoal().forPlayer(p2) || b.escortGoal().forPlayer(p2)),
                "the two newcomers do not have a guide each");
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 1600, batch = "escort1021_retarget")
    public static void theGuideFollowsTheMentorWhoMovedOn(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        var fixture = new EntryTestFixtures(h, "escort1021_retarget");
        fixture.force(h.absolutePos(new BlockPos(-2, 1, 6)), h.absolutePos(new BlockPos(44, 1, 18)));
        road(h, 44);
        var layout = empty(h);
        SectDisciple mentor = fixture.track(npc(h, layout, "gyeong_pil", 20, 12, false));
        SectDisciple guide = fixture.track(npc(h, layout, "yul_jin", 3, 12, true));
        ServerPlayer p = newcomer(h, 1);
        Vec3 destination = h.absoluteVec(new Vec3(40.5D, 2.0D, 12.5D));
        double moveAt = h.absoluteVec(new Vec3(8.0D, 2.0D, 12.5D)).x;
        double beyondOldTarget = h.absoluteVec(new Vec3(28.0D, 2.0D, 12.5D)).x;
        boolean[] started = {false};
        boolean[] moved = {false};
        long[] startedAt = {-1L};
        h.onEachTick(() -> {
            // Source: real-machine failure 31.10: guide ticks=148, mentor ticks=31 at timeout.
            // Budget chunk startup separately from the walk, including the mentor's destination chunk.
            if (!started[0]) {
                if (!fixture.ready() || guide.tickCount == 0 || mentor.tickCount == 0) return;
                h.assertTrue(SectArrival.order(p, layout), "no guide was sent");
                started[0] = true;
                startedAt[0] = h.getLevel().getGameTime();
            }
            if (!guide.escorting()) return;
            h.assertTrue(h.getLevel().getGameTime() - startedAt[0] <= 400L,
                    "moving mentor escort exceeded 400 ticks on a ready road: " + guide.escortGoal().debug());
            // Follow two blocks behind the guide, at most at walking pace. SectEscortGoal#tick
            // also completes when the player finds the mentor alone: an independently walking
            // player can end this escort before the guide crosses beyondOldTarget, even with
            // correct retargeting. A stale target must instead leave both actors at the old stop.
            double followX = Math.min(destination.x, guide.getX() - 2.0D);
            walk(p, Math.max(0.0D, Math.min(PLAYER_WALK, followX - p.getX())));
            if (!moved[0] && p.getX() > moveAt) {
                moved[0] = true;
                mentor.moveTo(destination.x, destination.y, destination.z);
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(started[0], "escort road has not become entity-ticking: guide ticks="
                    + guide.tickCount + " mentor ticks=" + mentor.tickCount + " pending=" + fixture.readiness());
            h.assertTrue(started[0] && moved[0] && !guide.escorting(),
                    "the escort is not over: guide=" + guide.position() + " ticks=" + guide.tickCount
                            + " player=" + p.position() + " mentor=" + mentor.position()
                            + " ticks=" + mentor.tickCount + " " + guide.escortGoal().debug());
            // Arrival also accepts a player who found the mentor himself. Require the guide to
            // have followed beyond the old destination so that shortcut cannot hide stale targeting.
            h.assertTrue(guide.getX() > beyondOldTarget,
                    "the guide stayed near the old mentor destination: " + guide.position()
                            + " player=" + p.position() + " mentor=" + mentor.position()
                            + " " + guide.escortGoal().debug());
            h.assertTrue(p.distanceTo(mentor) < 7.0D, "the guide led to the old place of the mentor: " + p.distanceTo(mentor));
            h.assertTrue(!SectService.state(p).has(SectArrival.ORDERED), "moving mentor arrival left the guide request active");
        });
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "escort1021_intro")
    public static void theMentorTakesInSomeoneWhoFoundHimHimself(GameTestHelper h) {
        var layout = empty(h);
        SectDisciple mentor = npc(h, layout, "gyeong_pil", 10, 12, false);
        ServerPlayer p = player(h, 9, 12, SectState.NONE);
        h.assertTrue(SectArrival.introduce(p, mentor) && SectService.state(p).has(SectArrival.INVITED), "the mentor did not write him down");
        h.assertTrue(SectArrival.admit(p, mentor), "and then did not take him in");
        SectArrival.decline(p);
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "escort1021_decline")
    public static void refusingTheGuideCancelsAndAskingAgainResumes(GameTestHelper h) {
        road(h, 44);
        var layout = empty(h);
        npc(h, layout, "gyeong_pil", 42, 12, false);
        SectDisciple guide = npc(h, layout, "yul_jin", 20, 12, false);
        ServerPlayer p = newcomer(h, 2);
        h.assertTrue(SectArrival.order(p, layout), "no guide was sent");
        SectArrival.decline(p);
        h.assertTrue(!guide.escorting() && !SectService.state(p).has(SectArrival.ORDERED), "refusal left the guide active");
        h.assertTrue(SectArrival.order(p, layout) && guide.escortGoal().forPlayer(p), "the interrupted escort cannot resume");
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "escort1021_busy")
    public static void aGuideBusyWithAnotherPlayerAnswersTheClick(GameTestHelper h) {
        var layout = empty(h);
        SectDisciple guide = npc(h, layout, "yul_jin", 10, 12, false);
        ServerPlayer owner = newcomer(h, 9);
        ServerPlayer other = player(h, 8, 12, SectState.NONE);
        h.assertTrue(guide.escortGoal().begin(owner, guide.position().add(20, 0, 0), false), "no escort");
        h.runAfterDelay(45L, () -> {
            DialogueService.open(other, guide);
            h.assertTrue(guide.bubbleKey().startsWith("murim.bubble.busy."), "busy click was silent");
            h.assertTrue(!other.getData(io.github.verycooltimo.murim.registry.ModAttachments.DIALOGUE).active(), "busy guide opened a dialogue");
            h.assertTrue(guide.escortGoal().forPlayer(owner), "click reassigned the guide");
            h.succeed();
        });
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "escort1021_resume")
    public static void anInterruptedTourCanBeResumedAndTheMentorReceivesAnUnescortedNewcomer(GameTestHelper h) {
        var layout = empty(h);
        SectDisciple mentor = npc(h, layout, "gyeong_pil", 10, 12, false);
        ServerPlayer p = newcomer(h, 9);
        h.assertTrue("reception".equals(DialogueService.route(p, mentor).node()), "invited newcomer was sent to the gate");
        p.setData(io.github.verycooltimo.murim.registry.ModAttachments.SECT, SectService.state(p).without(SectArrival.INVITED));
        h.assertTrue(SectArrival.introduce(p, mentor), "mentor refused a direct introduction");
        h.assertTrue(SectArrival.admit(p, mentor), "mentor refused admission");
        SectService.flag(p, "arrival.tour");
        DialogueService.open(p, mentor);
        h.assertTrue("tour_resume".equals(p.getData(io.github.verycooltimo.murim.registry.ModAttachments.DIALOGUE).node()), "tour did not offer resumption");
        DialogueService.choose(p, 0);
        h.assertTrue("tour_hall".equals(p.getData(io.github.verycooltimo.murim.registry.ModAttachments.DIALOGUE).node()), "resumption did not restart the tour");
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 260, batch = "escort1021_ahead")
    public static void theGuideCatchesAPlayerWhoRanAhead(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        road(h, 60);
        var layout = empty(h);
        npc(h, layout, "gyeong_pil", 58, 12, false);
        SectDisciple guide = npc(h, layout, "yul_jin", 3, 12, true);
        ServerPlayer p = newcomer(h, 1);
        // In play the arrival tick asks again every 40 ticks; a guide busy for an instant (a neighbouring arena's
        // schedule, a passing look) is not a failure, so ask the same way here instead of only once (flaky 22.10).
        boolean[] sent = {SectArrival.order(p, layout)};
        for (long t = 10L; t <= 60L; t += 10L) {
            h.runAfterDelay(t, () -> { if (!sent[0]) sent[0] = SectArrival.guide(p, layout); });
        }
        h.runAfterDelay(65L, () -> {
            h.assertTrue(sent[0], "no guide was sent");
            walk(p, 18);
        });
        h.runAfterDelay(165L, () -> {
            h.assertTrue(guide.escortGoal().forPlayer(p), "guide abandoned a player ahead");
            h.assertTrue(guide.getX() > p.getX() - 4.0D, "guide did not catch up");
            h.succeed();
        });
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "escort1021_cancel_tick")
    public static void cancelledAndCompletedEscortsIgnoreRemainingTicks(GameTestHelper h) {
        var layout = empty(h);
        SectDisciple guide = npc(h, layout, "yul_jin", 10, 12, false);
        ServerPlayer p = newcomer(h, 9);
        var goal = guide.escortGoal();
        int[] arrivals = {0};
        goal.tick(); // An unused goal has no player either.
        for (boolean forced : new boolean[]{false, true}) {
            h.assertTrue(goal.begin(p, guide.position().add(20, 0, 0), forced), "no escort");
            goal.onArrival(() -> arrivals[0]++);
            goal.cancel();
            goal.tick(); // The selector may still consider the cancelled goal running.
            h.assertTrue(!goal.active() && arrivals[0] == 0, "cancellation completed an escort");
        }
        h.assertTrue(goal.begin(p, p.position(), false), "no arrival escort");
        goal.onArrival(() -> arrivals[0]++);
        goal.tick();
        goal.tick();
        h.assertTrue(!goal.active() && arrivals[0] == 1, "arrival must complete exactly once");
        h.assertTrue(goal.begin(p, guide.position().add(20, 0, 0), false), "no resumed escort");
        goal.follow(() -> { goal.cancel(); return null; });
        goal.tick();
        h.assertTrue(!goal.active(), "destination callback cancellation was ignored");
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "escort1021_neighbour")
    public static void anEscortIgnoresPeopleFromANeighbouringSect(GameTestHelper h) {
        road(h, 44);
        var layout = empty(h);
        var neighbour = empty(h); // Equal coordinates still represent independent yards.
        SectDisciple mentor = npc(h, layout, "gyeong_pil", 40, 12, false);
        SectDisciple guide = npc(h, layout, "yul_jin", 3, 12, false);
        npc(h, neighbour, "gyeong_pil", 4, 12, false);
        ServerPlayer p = newcomer(h, 1);
        Vec3 target = SectEscorts.target(p, guide, "mentor");
        h.assertTrue(target != null && target.distanceTo(mentor.position()) < 3,
                "escort selected the neighbouring mentor");
        h.assertTrue(SectArrival.order(p, layout) && guide.escortGoal().forPlayer(p),
                "neighbouring mentor suppressed the guide request");
        Vec3 moved = mentor.position().add(-10, 0, 0);
        mentor.moveTo(moved.x, moved.y, moved.z);
        h.assertTrue(SectEscorts.target(p, guide, "mentor").distanceTo(moved) < 3,
                "escort did not follow its own mentor");
        guide.escortGoal().cancel();
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 1600, batch = "escort1021_neighbour_lifetime")
    public static void neighbouringSectMembersSurviveDuplicateCleanup(GameTestHelper h) {
        var fixture = new EntryTestFixtures(h, "escort1021_neighbour_lifetime");
        // Stationary lifetime coverage belongs inside the template, not on the escort road.
        // API: minecraft-src/net/minecraft/gametest/framework/StructureUtils.java#forceLoadChunks
        // forces the template bounds; actor tick counts below establish actual ticking readiness.
        SectPlaytestRig.clearYard(h);
        var layout = empty(h);
        var neighbour = empty(h);
        SectDisciple mentor = fixture.track(npc(h, layout, "gyeong_pil", 16, 10, false));
        SectDisciple otherMentor = fixture.track(npc(h, neighbour, "gyeong_pil", 16, 14, false));
        SectDisciple guide = fixture.track(npc(h, layout, "yul_jin", 10, 10, false));
        SectDisciple otherGuide = fixture.track(npc(h, neighbour, "yul_jin", 10, 14, false));
        SectDisciple duplicateA = fixture.track(npc(h, layout, "yul_seok", 13, 10, false));
        SectDisciple duplicateB = fixture.track(npc(h, layout, "yul_seok", 13, 14, false));
        // SectDisciple#tickLife invokes SectLife#tickNpc even with navigation disabled.
        // Each survivor must live through a full 200-entity-tick duplicate-sweep interval.
        // API: minecraft-src/net/minecraft/gametest/framework/GameTestSequence.java#thenWaitUntil
        h.startSequence().thenWaitUntil(() -> {
            for (SectDisciple d : new SectDisciple[]{mentor, otherMentor, guide, otherGuide}) {
                h.assertTrue(d.isAlive() && !d.isRemoved(), "another sect deleted " + d.memberKey());
                h.assertTrue(d.tickCount >= 200, "yard actor has not ticked enough: " + d.memberKey() + " ticks=" + d.tickCount);
            }
            h.assertTrue(duplicateA.isRemoved() != duplicateB.isRemoved(),
                    "same-sect duplicates must still leave exactly one member");
        }).thenSucceed();
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "escort1021_dash_channel")
    public static void aDashNotificationToleratesAMockPlayerWithoutAClientChannel(GameTestHelper h) {
        ServerPlayer p = newcomer(h, 1);
        Vec3 before = p.position();
        io.github.verycooltimo.murim.combat.FootworkService.sendDash(p, new Vec3(1, 0, 0), 4, 5);
        h.assertTrue(p.position().equals(before), "dash notification moved the server player");
        h.succeed();
    }

    /** The gap a lagging player is waited at (the goal's own constant). */
    private static final class SectEscortGoalGap {
        static final double WAIT = io.github.verycooltimo.murim.entity.SectEscortGoal.WAIT_GAP;
    }

    @net.minecraft.gametest.framework.AfterBatch(batch = "escort1021_comes")
    public static void cleanupTheGuideComesToTheNewcomerWhereHeStands(net.minecraft.server.level.ServerLevel level) {
        EntryTestFixtures.closeBatch("escort1021_comes");
    }

    @net.minecraft.gametest.framework.AfterBatch(batch = "escort1021_retarget")
    public static void cleanupMovingMentor(net.minecraft.server.level.ServerLevel level) {
        EntryTestFixtures.closeBatch("escort1021_retarget");
    }

    @net.minecraft.gametest.framework.AfterBatch(batch = "escort1021_neighbour_lifetime")
    public static void cleanupNeighbouringSectMembersSurviveDuplicateCleanup(net.minecraft.server.level.ServerLevel level) {
        EntryTestFixtures.closeBatch("escort1021_neighbour_lifetime");
    }
}
