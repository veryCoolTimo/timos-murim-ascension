package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.npc.GuestFighter;
import io.github.verycooltimo.murim.npc.NpcProfile;
import io.github.verycooltimo.murim.quest.QuestSites;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static io.github.verycooltimo.murim.sect.SectPlaytestRig.npc;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.only;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.player;

/**
 * Playtest 22.10 (evening): the sect leaves a peaceful guest alone, the doors of the main hall have guards who turn a junior back,
 * and the big gate is shut behind the player and at night, and raises the alarm when it is broken. One batch per test (named people).
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectPeaceGameTests {
    private static final String YARD = "sect_yard";

    private SectPeaceGameTests() {
    }

    // ------------------------------------------------------------------ guests

    @GameTest(template = YARD, timeoutTicks = 100, batch = "peace_guest")
    public static void theSectIgnoresAPeacefulGuestButNotAMonster(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        SectLife.forgetMonsters(h.getLevel());
        SectLife.data(h.getLevel()).lastAlarm = Long.MIN_VALUE / 2;
        var layout = only(h, "training");
        SectDisciple watcher = npc(h, layout, "seo_mu", 4, 4, false);
        BlockPos at = h.absolutePos(new BlockPos(10, 2, 4));
        for (NpcProfile quest : new NpcProfile[] {NpcProfile.SUPPLIER, NpcProfile.IMPOSTOR, NpcProfile.TRADER}) {
            GuestFighter g = QuestSites.spawn(h.getLevel(), quest, at);
            h.assertTrue(g != null && !SectLife.threat(g), quest + " is taken for an enemy");
            SectLife.watchMonsters(watcher, h.getLevel());
            h.assertTrue(!SectLife.data(h.getLevel()).reportedMonsters.containsKey(g.getUUID()), quest + " was reported to the sect");
            g.discard();
        }
        h.assertTrue(SectLife.data(h.getLevel()).lastAlarm < 0L, "the sect raised an alarm over a peaceful guest");
        Zombie z = EntityType.ZOMBIE.create(h.getLevel());
        Vec3 zAt = SectLife.stand(h.getLevel(), h.absoluteVec(new Vec3(10.5D, 2.0D, 4.5D)));
        z.moveTo(zAt.x, zAt.y, zAt.z, 0.0F, 0.0F);
        z.setNoAi(true);
        z.setPersistenceRequired();
        h.getLevel().addFreshEntity(z);
        h.assertTrue(SectLife.threat(z), "a zombie is no threat");
        SectLife.watchMonsters(watcher, h.getLevel());
        h.assertTrue(SectLife.data(h.getLevel()).reportedMonsters.containsKey(z.getUUID()), "the setup is wrong: a zombie in sight was not reported");
        h.succeed();
    }

    // ------------------------------------------------------------------ the doors of the main hall

    @GameTest(template = YARD, timeoutTicks = 100, batch = "peace_hall")
    public static void theHallGuardTurnsBackAJuniorAndLetsAFirstDiscipleBe(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        var layout = only(h, "main_hall");
        long day = SectSchedule.day(h.getLevel().getDayTime());
        SectDisciple guard = npc(h, layout, SectRota.dayWatch(day, 0).key(), 12, 3, false);
        h.assertTrue(SectWatch.guards(guard), "the setup guard is not on the watch");
        ServerPlayer junior = player(h, 1, 12, SectState.NONE.joined());
        SectWatch.check(junior, layout);
        h.assertTrue(guard.blockTarget() == junior.getId(), "the guard did not step in the junior's way");
        h.assertTrue("warn".equals(SectWatch.of(junior).last()), "no word for the junior: '" + SectWatch.of(junior).last() + "'");
        h.assertTrue(guard.guardTarget() == null && guard.spar() == SectDisciple.Spar.NONE, "a junior was attacked for coming near");
        h.assertTrue(junior.getData(ModAttachments.SECT).merit() == 0, "merit was taken");
        guard.clearBlock();
        // the first lesson is no longer a key to the hall (author 22.10): even a graduate is turned back, only offices pass
        ServerPlayer pupil = player(h, 1, 12, SectState.NONE.joined().with(SectStanding.LESSON_ONE).with(SectStanding.LESSON_TWO));
        SectWatch.of(pupil).warned.clear();
        SectWatch.check(pupil, layout);
        h.assertTrue(guard.blockTarget() == pupil.getId(), "the guard let a pupil with the lessons by");
        guard.clearBlock();
        ServerPlayer first = player(h, 1, 12, SectState.NONE.joined().with(SectPositions.FIRST).with("pass.main_hall"));
        SectWatch.check(first, layout);
        h.assertTrue(guard.blockTarget() != first.getId(), "the guard stopped a first disciple");
        h.succeed();
    }

    // ------------------------------------------------------------------ the gate

    private static BlockPos placeGate(GameTestHelper h) {
        var item = io.github.verycooltimo.murim.decor.GateDoors.GATE_DOOR_ITEM.get();
        BlockPos click = h.absolutePos(new BlockPos(6, 2, 6));
        var hit = new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(click), net.minecraft.core.Direction.SOUTH, click, false);
        item.place(new net.minecraft.world.item.context.BlockPlaceContext(h.getLevel(), null, net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.item.ItemStack(item), hit));
        return h.absolutePos(new BlockPos(5, 2, 6));
    }

    private static boolean gateOpen(GameTestHelper h, BlockPos origin) {
        return h.getLevel().getBlockState(origin).getValue(io.github.verycooltimo.murim.decor.GateDoorBlock.OPEN);
    }

    /** A gate a player opened is shut again once he is through and nobody stands in the passage. */
    @GameTest(template = YARD, timeoutTicks = 260, batch = "peace_gate_close")
    public static void theSectGateIsShutBehindThePlayer(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        var layout = only(h, "sect_gate");
        BlockPos origin = placeGate(h);
        var gate = (io.github.verycooltimo.murim.decor.GateDoorBlock) h.getLevel().getBlockState(origin).getBlock();
        h.assertTrue(gate.toggle(h.getLevel(), origin, null), "setup: the swing did not start");
        h.assertTrue(SectGate.opened(h.getLevel(), origin, layout), "the gate beside the gate zone is not the sect gate");
        h.assertTrue(!SectGate.opened(h.getLevel(), origin.offset(0, 0, 40), layout), "a gate far from the gate zone was taken for the sect gate");
        npc(h, layout, "gatekeeper", 12, 6, false);
        boolean[] opened = {false};
        h.succeedWhen(() -> {
            opened[0] |= gateOpen(h, origin);
            h.assertTrue(opened[0], "the gate never finished opening");
            h.assertTrue(!gateOpen(h, origin), "the gate was left open behind the player");
        });
    }

    /** At night the duty keeper shuts a gate found open after the passage clears. */
    @GameTest(template = YARD, timeoutTicks = 260, batch = "peace_gate_night")
    public static void theSectGateIsKeptShutAtNight(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.NIGHT);
        BlockPos origin = placeGate(h);
        var gate = (io.github.verycooltimo.murim.decor.GateDoorBlock) h.getLevel().getBlockState(origin).getBlock();
        gate.setOpen(h.getLevel(), origin, true);
        SectDoors.markUnattended(h.getLevel(), origin, h.getLevel().getGameTime());
        npc(h, only(h, "sect_gate"), "gatekeeper", 12, 6, false);
        h.succeedWhen(() -> h.assertTrue(!gateOpen(h, origin), "the gate stands open at night"));
    }

    /** A broken gate calls the guards (it used to break without a sound). */
    @GameTest(template = YARD, timeoutTicks = 100, batch = "peace_gate_broken")
    public static void aBrokenSectGateRaisesTheAlarm(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        SectLife.data(h.getLevel()).lastAlarm = Long.MIN_VALUE / 2;
        var layout = only(h, "sect_gate");
        BlockPos origin = placeGate(h);
        long day = SectSchedule.day(h.getLevel().getDayTime());
        SectDisciple guard = npc(h, layout, SectRota.dayWatch(day, 0).key(), 12, 14, false);
        h.assertTrue(SectGate.broken(h.getLevel(), origin.offset(0, 0, 40), layout, null) == 0, "a far gate raised the alarm");
        h.assertTrue(SectGate.broken(h.getLevel(), origin, layout, null) > 0, "nobody heard the gate break");
        h.assertTrue(guard.alarmGoal().mode(h.getLevel().getGameTime()) != null, "the guard does not go to the broken gate");
        h.succeed();
    }
}
