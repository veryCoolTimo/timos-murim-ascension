package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectAlarmGoal;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static io.github.verycooltimo.murim.sect.SectPlaytestRig.clearYard;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.empty;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.npc;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.player;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.spot;

/** No calls to raise or manually posted events: entity ticking and item use publish the events. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectAlarmRealPathGameTests {
    private SectAlarmRealPathGameTests() {}

    @GameTest(template = "sect_yard", timeoutTicks = 100, batch = "alarm1029_owned")
    public static void survivalOwnedTntDispatchesShoutsAndMovesGuard(GameTestHelper h) {
        tnt(h, true);
    }

    @GameTest(template = "sect_yard", timeoutTicks = 100, batch = "alarm1029_unowned")
    public static void ownerlessTntDispatchesShoutsAndMovesGuard(GameTestHelper h) {
        tnt(h, false);
    }

    private static void tnt(GameTestHelper h, boolean owned) {
        clearYard(h);
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        SectLife.data(h.getLevel()).lastAlarm = Long.MIN_VALUE / 2;
        ServerPlayer p = player(h, 3, 3, SectState.NONE);
        // Source: SectBlastReactionGameTests.everyoneReactsAtOnceAndEachInHisOwnWay.
        // Gyeong Tak is also an elder: owned survival TNT gives him CONFRONT before the guard RUN branch.
        long day = SectSchedule.day(h.getLevel().getDayTime());
        SectDisciple guard = npc(h, empty(h), SectRota.dayWatch(day, 0).key(), 21, 21, true);
        h.assertTrue(SectAlarm.guard(guard) && !SectAlarm.elder(guard), "fixture needs an on-duty guard without an elder role");
        SectDisciple servant = npc(h, empty(h), "porter_oh", 3, 20, true);
        Vec3 incident = spot(h, 3, 3);
        Vec3 start = guard.position();
        PrimedTnt tnt = new PrimedTnt(h.getLevel(), incident.x, incident.y, incident.z, owned ? p : null);
        tnt.setFuse(4);
        h.getLevel().addFreshEntity(tnt);
        h.runAfterDelay(35, () -> {
            h.assertTrue(tnt.isRemoved(), "TNT never detonated from its tick");
            h.assertTrue(SectLife.data(h.getLevel()).lastAlarm >= 0, "no alarm state from vanilla detonation");
            h.assertTrue(guard.alarmGoal().mode(h.getLevel().getGameTime()) == SectAlarmGoal.Mode.RUN,
                    "guard should RUN, got " + guard.alarmGoal().mode(h.getLevel().getGameTime()));
            h.assertTrue(guard.bubbleKey().startsWith("murim.bubble.alarm_guard"), "guard did not shout");
            h.assertTrue(servant.bubbleKey().startsWith("murim.bubble.alarm_"), "servant did not shout");
            h.assertTrue(guard.position().distanceTo(start) > 1, "guard never started moving");
            h.succeed();
        });
    }

    @GameTest(template = "sect_yard", timeoutTicks = 100, batch = "alarm1029_fire")
    public static void unrelatedIgnitionCannotSwallowFlintFireNextToBuilding(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        var level = h.getLevel();
        SectLife.data(level).lastAlarm = Long.MIN_VALUE / 2;
        SectLife.data(level).lastFireLook = Long.MIN_VALUE / 2;
        // Use the real mountain grounds when ServerStarted has chosen a site. GameTest servers without a site use the yard.
        var site = MountHuaSites.get(level.getServer());
        Vec3 incident = site == null ? spot(h, 5, 5) : SectLayout.hua(site).at("training", 0, 0);
        BlockPos fire = BlockPos.containing(incident);
        // API: SectNewcomerGameTests forces remote fixture chunks for entity ticking.
        for (int cx = (fire.getX() - 16) >> 4; cx <= (fire.getX() + 40) >> 4; cx++) {
            for (int cz = (fire.getZ() - 16) >> 4; cz <= (fire.getZ() + 40) >> 4; cz++) {
                if (site != null) level.setChunkForced(cx, cz, true);
            }
        }
        // A small paved approach beside a wooden building, outside the blast fixture's damage.
        for (int x = -2; x <= 24; x++) {
            for (int z = -2; z <= 24; z++) {
                BlockPos cell = fire.offset(x, 0, z);
                level.setBlockAndUpdate(cell.below(), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(cell, Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(cell.above(), Blocks.AIR.defaultBlockState());
            }
        }
        level.setBlockAndUpdate(fire.west(), Blocks.OAK_PLANKS.defaultBlockState());
        ServerPlayer p = player(h, 5, 5, SectState.NONE);
        p.moveTo(fire.getX() + 0.5, fire.getY(), fire.getZ() + 2.5, 0, 0);
        SectDisciple guard = npc(h, empty(h), "gyeong_tak", 20, 20, true);
        guard.moveTo(fire.getX() + 20.5, fire.getY(), fire.getZ() + 20.5, 0, 0);
        Vec3 start = guard.position();
        BlockPos unrelated = fire.offset(300, 0, 0);
        level.setBlockAndUpdate(unrelated.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(unrelated, Blocks.AIR.defaultBlockState());
        ignite(p, unrelated);
        ignite(p, fire);
        h.assertTrue(level.getBlockState(fire).is(net.minecraft.tags.BlockTags.FIRE), "flint did not place fire");
        h.assertTrue(guard.bubbleKey().startsWith("murim.bubble.alarm_guard"), "earlier unrelated fire swallowed the shout");
        h.runAfterDelay(35, () -> {
            h.assertTrue(guard.alarmGoal().mode(level.getGameTime()) == SectAlarmGoal.Mode.RUN, "no fire alarm errand");
            h.assertTrue(guard.position().distanceTo(start) > 1, "guard never started moving toward fire");
            level.removeBlock(fire, false);
            level.removeBlock(unrelated, false);
            guard.discard();
            for (int cx = (fire.getX() - 16) >> 4; cx <= (fire.getX() + 40) >> 4; cx++) {
                for (int cz = (fire.getZ() - 16) >> 4; cz <= (fire.getZ() + 40) >> 4; cz++) {
                    if (site != null) level.setChunkForced(cx, cz, false);
                }
            }
            h.succeed();
        });
    }

    private static void ignite(ServerPlayer p, BlockPos fire) {
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FLINT_AND_STEEL));
        BlockPos floor = fire.below();
        // API: NeoForge 1.21.1 FlintAndSteelItem patch; vanilla item use places fire and notifies neighbors.
        // [НЕПРОВЕРЕНО: UseOnContext constructor; compile on the permitted tester machine.]
        Items.FLINT_AND_STEEL.useOn(new UseOnContext(p, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false)));
    }

    @GameTest(template = "sect_yard", timeoutTicks = 80, batch = "alarm1029_dormancy")
    public static void activeAlarmPreventsDistantResponderSleepingAgain(GameTestHelper h) {
        clearYard(h);
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        var layout = new SectHierarchyGameTests.Only(spot(h, 12, 12), java.util.Set.copyOf(SectLayout.SECT_ZONES));
        SectDisciple guard = npc(h, layout, "gyeong_tak", 12, 12, false);
        guard.setKeepAwake(false);
        var routine = SectLife.resolve(guard);
        h.assertTrue(routine != null, "fixture has no scheduled post");
        guard.moveTo(routine.spot().x, routine.spot().y, routine.spot().z, 0, 0);
        // Hold at his post while inspecting: the normal dormant predicate would put him to sleep again.
        guard.alarmGoal().assign(SectAlarmGoal.Mode.LOOK, guard.position(), guard.position(),
                h.getLevel().getGameTime() + 100);
        h.runAfterDelay(40, () -> {
            h.assertTrue(!guard.dormant(), "active responder became dormant again without a nearby player");
            h.succeed();
        });
    }
}
