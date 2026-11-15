package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.GateDoorBlock;
import io.github.verycooltimo.murim.decor.GateDoors;
import io.github.verycooltimo.murim.entity.SectAlarmGoal;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

import static io.github.verycooltimo.murim.sect.SectPlaytestRig.clearYard;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.empty;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.npc;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.player;

/** Sect safety review 15.11: blame for blasts, golems, the player's own chest, the war gate, doors left by a lost opener. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectSafetyGameTests {

    private static final String YARD = "sect_yard";

    private SectSafetyGameTests() {
    }

    private static Vec3 blast(GameTestHelper h) {
        return h.absoluteVec(new Vec3(3.5D, 2.0D, 3.5D));
    }

    private static void ready(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        SectLife.data(h.getLevel()).lastAlarm = Long.MIN_VALUE / 2;
        clearYard(h);
    }

    // ------------------------------------------------------------------ 15: blame

    @GameTest(template = YARD, timeoutTicks = 100, batch = "safety1115_creeper")
    public static void aCreepersBlastIsNotBlamedOnThePlayerNearby(GameTestHelper h) {
        ready(h);
        ServerPlayer p = player(h, 5, 5, SectState.NONE.joined().contribute(20));
        SectDisciple elder = npc(h, empty(h), "tae_rok", 21, 5, true);
        Creeper creeper = new Creeper(EntityType.CREEPER, h.getLevel());
        Vec3 b = blast(h);
        creeper.moveTo(b.x, b.y, b.z);
        h.getLevel().explode(creeper, b.x, b.y, b.z, 2.0F, Level.ExplosionInteraction.MOB);
        h.runAfterDelay(10, () -> {
            h.assertTrue(elder.alarmGoal().mode(h.getLevel().getGameTime()) != null, "the sect did not hear the blast at all");
            h.assertTrue(elder.alarmGoal().mode(h.getLevel().getGameTime()) != SectAlarmGoal.Mode.CONFRONT, "the elder went for the player after a creeper");
            h.assertTrue(SectAlarm.strikes(p) == 0, "a strike was written against the player: " + SectAlarm.strikes(p));
            h.succeed();
        });
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "safety1115_wind")
    public static void aWindChargeIsNoBlastAgainstTheSect(GameTestHelper h) {
        ready(h);
        ServerPlayer p = player(h, 5, 5, SectState.NONE.joined().contribute(20));
        SectDisciple elder = npc(h, empty(h), "tae_rok", 21, 5, true);
        Vec3 b = blast(h);
        h.getLevel().explode(p, b.x, b.y, b.z, 1.2F, Level.ExplosionInteraction.TRIGGER);
        h.runAfterDelay(10, () -> {
            h.assertTrue(elder.alarmGoal().mode(h.getLevel().getGameTime()) == null, "the sect raised the alarm over a wind charge");
            h.assertTrue(SectAlarm.strikes(p) == 0, "a strike for a wind charge");
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ 18: golems

    @GameTest(template = YARD, timeoutTicks = 40, batch = "safety1115_golem")
    public static void golemsLeaveTheSectAloneUnlessStruckFirst(GameTestHelper h) {
        ready(h);
        SectDisciple d = npc(h, empty(h), "yul_jin", 10, 10, false);
        IronGolem iron = new IronGolem(EntityType.IRON_GOLEM, h.getLevel());
        SnowGolem snow = new SnowGolem(EntityType.SNOW_GOLEM, h.getLevel());
        iron.setTarget(d);
        snow.setTarget(d);
        h.assertTrue(iron.getTarget() == null, "an iron golem took a person of the sect for prey");
        h.assertTrue(snow.getTarget() == null, "a snow golem took a person of the sect for prey");
        iron.setLastHurtByMob(d);
        iron.setTarget(d);
        h.assertTrue(iron.getTarget() == d, "a golem struck by the person may not answer");
        h.succeed();
    }

    // ------------------------------------------------------------------ 9: own chest

    @GameTest(template = YARD, timeoutTicks = 40, batch = "safety1115_pilfer")
    public static void takingBackYourOwnThingsIsNoTheft(GameTestHelper h) {
        SectLayoutFile file = SectLayoutFile.shipped();
        SectLayoutFile.Room mine = null;
        SectLayoutFile.Room other = null;
        for (SectLayoutFile.Room r : file.rooms()) {
            if (!"sleeping_room".equals(r.function()) || file.roomAt(cell(r)) != r) continue;
            if (mine == null) mine = r;
            else if (other == null && !r.id().equals(mine.id())) other = r;
        }
        h.assertTrue(mine != null && other != null, "the shipped layout has no two sleeping rooms");
        ServerPlayer p = player(h, 5, 5, SectState.NONE.joined());
        BlockPos bed = cell(mine);
        p.setData(ModAttachments.SECT, SectService.state(p).with("room.bed").record("room.pos", bed.asLong()));
        // his own chest: what he takes out is his
        SimpleContainer chest = new SimpleContainer(9);
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 5));
        SectPilfer.opened(p, chest);
        chest.removeItem(0, 5);
        SectPilfer.closed(p, cell(mine), chest, file);
        h.assertTrue(!SectPilfer.has(p, "room"), "the chest of his own room counted as theft");
        // a chest of another room: the sect's, but what he put in himself comes back out freely
        SimpleContainer theirs = new SimpleContainer(9);
        SectPilfer.opened(p, theirs);
        theirs.setItem(0, new ItemStack(Items.BREAD, 3));
        SectPilfer.closed(p, cell(other), theirs, file);
        SectPilfer.opened(p, theirs);
        theirs.removeItem(0, 3);
        SectPilfer.closed(p, cell(other), theirs, file);
        h.assertTrue(!SectPilfer.has(p, "room"), "taking back what he had put in counted as theft");
        // and a real taking from it still counts
        SimpleContainer full = new SimpleContainer(9);
        full.setItem(0, new ItemStack(Items.GOLD_INGOT, 2));
        SectPilfer.opened(p, full);
        full.removeItem(0, 2);
        SectPilfer.closed(p, cell(other).east(), full, file);
        h.assertTrue(SectPilfer.has(p, "room"), "a real theft from another room no longer counts");
        h.succeed();
    }

    private static BlockPos cell(SectLayoutFile.Room r) {
        return new BlockPos((r.bounds().x0() + r.bounds().x1()) / 2, r.y(), (r.bounds().z0() + r.bounds().z1()) / 2);
    }

    // ------------------------------------------------------------------ 17: war gate

    @GameTest(template = YARD, timeoutTicks = 40, batch = "safety1115_wargate")
    public static void theWarGateDoesNotShutOnSomebodyInTheDoorway(GameTestHelper h) {
        var item = GateDoors.GATE_DOOR_ITEM.get();
        BlockPos click = h.absolutePos(new BlockPos(6, 2, 6));
        var hit = new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(click), Direction.SOUTH, click, false);
        item.place(new net.minecraft.world.item.context.BlockPlaceContext(h.getLevel(), null, net.minecraft.world.InteractionHand.MAIN_HAND,
                new ItemStack(item), hit));
        BlockPos origin = h.absolutePos(new BlockPos(5, 2, 6));
        var state = h.getLevel().getBlockState(origin);
        var gate = (GateDoorBlock) state.getBlock();
        gate.setOpen(h.getLevel(), origin, true);
        BlockPos in = GateDoorBlock.partPos(origin, state.getValue(GateDoorBlock.FACING), 1, 0);
        var cat = new net.minecraft.world.entity.animal.Cat(EntityType.CAT, h.getLevel());
        cat.setNoAi(true);
        cat.setPos(in.getX() + 0.5D, in.getY(), in.getZ() + 0.5D);
        h.getLevel().addFreshEntity(cat);
        SectWar.closeGate(h.getLevel(), Vec3.atCenterOf(origin));
        h.assertTrue(h.getLevel().getBlockState(origin).getValue(GateDoorBlock.OPEN), "the war gate shut on somebody standing in it");
        cat.discard();
        SectWar.closeGate(h.getLevel(), Vec3.atCenterOf(origin));
        h.assertFalse(h.getLevel().getBlockState(origin).getValue(GateDoorBlock.OPEN), "the war gate stays open with nobody in it");
        h.succeed();
    }

    // ------------------------------------------------------------------ 19: a lost opener

    private static BlockPos openDoor(GameTestHelper h) {
        BlockPos base = h.absolutePos(new BlockPos(12, 2, 12));
        var door = io.github.verycooltimo.murim.decor.HallJoinery.HALL_DOOR.get();
        for (var half : DoubleBlockHalf.values()) {
            h.getLevel().setBlock(half == DoubleBlockHalf.LOWER ? base : base.above(),
                    door.defaultBlockState().setValue(DoorBlock.HALF, half).setValue(DoorBlock.FACING, Direction.EAST).setValue(DoorBlock.OPEN, true), 3);
        }
        return base;
    }

    @GameTest(template = YARD, timeoutTicks = 800, batch = "safety1115_orphan")
    public static void aLayoutDoorLeftOpenByALostOpenerIsShutLater(GameTestHelper h) {
        clearYard(h);
        BlockPos base = openDoor(h);
        var owner = npc(h, empty(h), "porter_jang", 8, 12, false);
        SectDoors.mark(owner, base, h.getLevel().getGameTime());
        owner.discard();
        SectDoors.layoutDoorHook = b -> true;
        h.onEachTick(() -> SectDoors.sweep(h.getLevel()));
        h.runAfterDelay(300, () -> h.assertTrue(h.getLevel().getBlockState(base).getValue(DoorBlock.OPEN), "shut at once, before the wait was over"));
        h.runAfterDelay(700, () -> {
            SectDoors.layoutDoorHook = null;
            h.assertFalse(h.getLevel().getBlockState(base).getValue(DoorBlock.OPEN), "the door stayed open for ever after its opener was lost");
            h.succeed();
        });
    }

    @GameTest(template = YARD, timeoutTicks = 800, batch = "safety1115_orphan_player")
    public static void aDoorOutsideTheLayoutIsNeverShutForALostOpener(GameTestHelper h) {
        clearYard(h);
        BlockPos base = openDoor(h);
        var owner = npc(h, empty(h), "porter_jang", 8, 12, false);
        SectDoors.mark(owner, base, h.getLevel().getGameTime());
        owner.discard();
        h.onEachTick(() -> SectDoors.sweep(h.getLevel(), b -> false));
        h.runAfterDelay(700, () -> {
            h.assertTrue(h.getLevel().getBlockState(base).getValue(DoorBlock.OPEN), "a door that is not the sect's was shut");
            h.succeed();
        });
    }
}
