package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.IntSeating;
import io.github.verycooltimo.murim.decor.SeatEntity;
import io.github.verycooltimo.murim.entity.SeatStyle;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static io.github.verycooltimo.murim.sect.SectPlaytestRig.empty;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.id;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.npc;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.player;

/** Holes review of 21.10 (npc-2, npc-3, npc-4, npc-6, npc-7, robust-2). Every test that spawns named people has a batch of its own. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectHolesGameTests {

    private static final String YARD = "sect_yard";

    private SectHolesGameTests() {
    }

    // ------------------------------------------------------------------ npc-3

    @GameTest(template = YARD, timeoutTicks = 60, batch = "holes_talk_lock")
    public static void talkLockIsReleasedWhenThePlayerIsGone(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        ServerPlayer p = player(h, 12, 12, SectState.NONE.joined());
        SectDisciple d = npc(h, empty(h), "tae_seong", 14, 12, false);
        h.assertTrue(DialogueService.openDialogue(p, d, id("plum_witness"), "recognized"), "no dialogue");
        h.assertTrue(d.talkingTo() == p.getId() && !d.free(), "setup: not talking");
        // death / logout: the session is dropped at once
        DialogueService.dropSession(p);
        h.assertTrue(d.talkingTo() < 0 && d.free() && !p.getData(ModAttachments.DIALOGUE).active(), "the lock stayed after the session was dropped");
        // a lock whose player forgot the session (new body after death, the screen lost): the person's own watchdog frees him
        h.assertTrue(DialogueService.openDialogue(p, d, id("plum_witness"), "recognized"), "no dialogue the second time");
        p.setData(ModAttachments.DIALOGUE, DialogueService.Session.NONE);
        d.releaseStaleTalker();
        h.assertTrue(d.talkingTo() < 0, "the watchdog did not free the person whose talker lost the session");
        // a talker who walked away
        h.assertTrue(DialogueService.openDialogue(p, d, id("plum_witness"), "recognized"), "no dialogue the third time");
        p.moveTo(p.getX() + 30.0D, p.getY(), p.getZ(), 0.0F, 0.0F);
        d.releaseStaleTalker();
        h.assertTrue(d.talkingTo() < 0, "the watchdog did not free the person whose talker walked off");
        // another player may talk to him now
        ServerPlayer q = player(h, 14, 13, SectState.NONE.joined());
        h.assertTrue(DialogueService.openDialogue(q, d, id("plum_witness"), "recognized"), "the person stayed deaf to others");
        h.succeed();
    }

    // ------------------------------------------------------------------ npc-4

    @GameTest(template = YARD, timeoutTicks = 20)
    public static void sleepEndsBeforeTheMorningFormation(GameTestHelper h) {
        h.assertTrue(SectSleep.wakeTime(24000L, 14000L) == 22000L, "a night's sleep does not end 2000 ticks before the morning");
        h.assertTrue(SectSleep.wakeTime(48000L, 25000L) == 46000L, "second night");
        h.assertTrue(SectSleep.wakeTime(24000L, 23500L) == 24000L, "a sleep that would go back in time");
        h.assertTrue(SectSchedule.at(SectSleep.wakeTime(24000L, 14000L) + SectSchedule.Period.FORMATION.start() - 22000L)
                == SectSchedule.Period.FORMATION, "bell");
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 20)
    public static void twilightWakeOnlyInASectBed(GameTestHelper h) {
        ServerPlayer p = player(h, 12, 12, SectState.NONE.joined());
        BlockPos own = h.absolutePos(new BlockPos(10, 2, 10));
        BlockPos camp = h.absolutePos(new BlockPos(16, 2, 10));
        p.setData(ModAttachments.SECT, SectService.state(p).with("room.assigned").with("room.bed").record("room.pos", own.asLong()));
        p.startSleeping(camp);
        h.assertTrue(!SectSleep.inSectBed(h.getLevel(), p), "a bed outside the sect woke the player in the twilight");
        p.stopSleeping();
        p.startSleeping(own);
        h.assertTrue(SectSleep.inSectBed(h.getLevel(), p), "the assigned sect bed does not give the twilight wake-up");
        p.stopSleeping();
        h.succeed();
    }

    // ------------------------------------------------------------------ npc-6

    @GameTest(template = YARD, timeoutTicks = 60, batch = "holes_seat")
    public static void aSeatIsNeverSharedByAPersonAndAPlayer(GameTestHelper h) {
        BlockPos seat = h.absolutePos(new BlockPos(10, 2, 10));
        h.getLevel().setBlock(seat, IntSeating.FLOOR_CUSHION.get().defaultBlockState(), 3);
        BlockPos other = h.absolutePos(new BlockPos(14, 2, 10));
        h.getLevel().setBlock(other, IntSeating.FLOOR_CUSHION.get().defaultBlockState(), 3);
        SectDisciple d = npc(h, empty(h), "tae_seong", 10, 11, false);
        Vec3 top = SectLife.seatTop(h.getLevel(), seat);
        h.assertTrue(top != null, "setup: no seat top");
        d.sit(SeatStyle.LOTUS, top, 0.0F, d.position());
        h.assertTrue(d.sitting(), "setup: the person does not sit");
        ServerPlayer p = player(h, 12, 12, SectState.NONE.joined());
        h.assertTrue(!SeatEntity.sit(h.getLevel(), seat, p), "the player sat on a person's lap");
        // the player takes a free one; the person's routine then sees it taken
        h.assertTrue(SeatEntity.sit(h.getLevel(), other, p), "the player cannot sit on a free cushion");
        h.assertTrue(SectLife.playerSits(h.getLevel(), other), "a seat with a player on it is not seen as taken");
        h.assertTrue(!SectLife.playerSits(h.getLevel(), seat), "a seat with a person on it is seen as taken by a player");
        h.succeed();
    }

    // ------------------------------------------------------------------ npc-7

    @GameTest(template = YARD, timeoutTicks = 60, batch = "holes_cook")
    public static void theCookHandsAColdBowlToALatecomer(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        ServerPlayer p = player(h, 12, 12, SectState.NONE.joined());
        SectDisciple cook = npc(h, empty(h), "cook_kim", 14, 12, false);
        var route = DialogueService.route(p, cook);
        h.assertTrue(route != null && "late".equals(route.node()), "the cook's start node in the training hours is " + (route == null ? null : route.node()));
        h.assertTrue(DialogueService.openDialogue(p, cook, route.id(), route.node()), "no dialogue");
        h.assertTrue(SectMeals.served(p), "the latecomer got no portion from the cook");
        SectGameTests.setPeriod(h, SectSchedule.Period.EVENING);
        ServerPlayer q = player(h, 12, 13, SectState.NONE.joined());
        var route2 = DialogueService.route(q, cook);
        h.assertTrue(route2 != null && "late".equals(route2.node()), "the cook's start node in the evening is " + (route2 == null ? null : route2.node()));
        h.succeed();
    }

    // ------------------------------------------------------------------ npc-2

    @GameTest(template = YARD, timeoutTicks = 80, batch = "holes_gate_greeter")
    public static void aWaitingOutsiderIsReceivedEvenWhenTheGateIsEmpty(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.EVENING);
        SectHierarchyGameTests.Only layout = SectPlaytestRig.only(h, "sect_gate", "mentor");
        // nobody at the gate: the only free person stands 18 blocks away
        SectDisciple far = npc(h, layout, "yul_jin", 22, 12, false);
        ServerPlayer p = player(h, 12, 12, SectState.NONE);
        long now = h.getLevel().getGameTime();
        h.assertTrue(!SectArrival.greeter(far), "setup: a free disciple is a greeter by himself");
        h.assertTrue(SectArrival.callGreeter(p, now) == null, "called at once, before the wait");
        h.assertTrue(SectArrival.callGreeter(p, now + SectArrival.GATE_WAIT_TICKS - 1) == null, "called before the wait is over");
        SectDisciple who = SectArrival.callGreeter(p, now + SectArrival.GATE_WAIT_TICKS);
        h.assertTrue(who == far, "nobody was sent to the waiting outsider");
        h.assertTrue(who.approachingDialogue(p, SectArrival.DIALOGUE) && SectArrival.greeter(who), "the person sent does not come to receive him");
        h.assertTrue(SectArrival.callGreeter(p, now + SectArrival.GATE_WAIT_TICKS + 5) == null, "a second person was sent");
        h.succeed();
    }

    // ------------------------------------------------------------------ robust-2

    @GameTest(template = YARD, timeoutTicks = 800, batch = "holes_pop_in")
    public static void aPersonAppearsInFrontOfAWaitingPlayerAfterAWhile(GameTestHelper h) {
        ServerPlayer p = player(h, 6, 12, SectState.NONE);
        Vec3 spot = SectLife.stand(h.getLevel(), h.absoluteVec(new Vec3(16.5D, 2.0D, 12.5D)));
        h.assertTrue(!SectLife.mayAppear(h.getLevel(), spot, p, "npc:test_pop"), "appeared at once in the player's sight");
        // behind a wall the player cannot see the spot: no wait
        for (int y = 2; y <= 6; y++) {
            for (int z = 8; z <= 16; z++) {
                h.setBlock(new BlockPos(11, y, z), Blocks.STONE);
            }
        }
        h.assertTrue(SectLife.mayAppear(h.getLevel(), spot, p, "npc:test_pop_wall"), "did not appear behind a wall");
        for (int y = 2; y <= 6; y++) {
            for (int z = 8; z <= 16; z++) {
                h.setBlock(new BlockPos(11, y, z), Blocks.AIR);
            }
        }
        h.runAfterDelay(SectLife.POP_IN_WAIT + 20L, () -> {
            h.assertTrue(SectLife.mayAppear(h.getLevel(), spot, p, "npc:test_pop"), "the person never appeared for a waiting player");
            h.succeed();
        });
    }
}
