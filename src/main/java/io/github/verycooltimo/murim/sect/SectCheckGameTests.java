package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.GateDoorBlock;
import io.github.verycooltimo.murim.decor.GateDoors;
import io.github.verycooltimo.murim.decor.HallJoinery;
import io.github.verycooltimo.murim.entity.SectDisciple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static io.github.verycooltimo.murim.sect.SectPlaytestRig.empty;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.npc;

/**
 * The sect check of 24.10 (doors and gates of the sect after the author's playtest of the 23-24.10 jars): what the real-world run on a fresh
 * world found, as tests of the code. The gate and the hall doors were found open at noon with the guards standing on their posts.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectCheckGameTests {
    private static final String YARD = "sect_yard";

    private SectCheckGameTests() {
    }

    private static BlockPos placeGate(GameTestHelper h) {
        var item = GateDoors.GATE_DOOR_ITEM.get();
        BlockPos click = h.absolutePos(new BlockPos(6, 2, 6));
        var hit = new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(click), Direction.SOUTH, click, false);
        item.place(new net.minecraft.world.item.context.BlockPlaceContext(h.getLevel(), null, net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.item.ItemStack(item), hit));
        return h.absolutePos(new BlockPos(5, 2, 6));
    }

    /**
     * «ворота ... открыты нараспашку»: the two guards of the sect gate stand one cell beside the gate leaves all day (posts of the layout);
     * a gate that was opened for somebody is shut once nobody walks through it, with the guards standing where they stand.
     */
    @GameTest(template = YARD, timeoutTicks = 330, batch = "sect_check_gate_guards")
    public static void guardsOnTheirPostsBesideTheGateDoNotKeepItOpen(GameTestHelper h) {
        long saved = h.getLevel().getDayTime();
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        BlockPos origin = placeGate(h);
        var state = h.getLevel().getBlockState(origin);
        var gate = (GateDoorBlock) state.getBlock();
        Direction facing = state.getValue(GateDoorBlock.FACING);
        gate.setOpen(h.getLevel(), origin, true);
        BlockPos front = GateDoorBlock.partPos(origin, facing, 1, 0).relative(facing);
        BlockPos back = GateDoorBlock.partPos(origin, facing, 2, 0).relative(facing.getOpposite());
        for (BlockPos cell : new BlockPos[] {front, back}) {
            // two bystanders (a person of the sect would be folded into one of the same key by SectLife.duplicate when another test left him)
            var guard = new net.minecraft.world.entity.animal.Cat(net.minecraft.world.entity.EntityType.CAT, h.getLevel());
            guard.setNoAi(true);
            guard.setPos(cell.getX() + 0.5D, cell.getY(), cell.getZ() + 0.5D);
            h.getLevel().addFreshEntity(guard);
        }
        npc(h, empty(h), "gatekeeper", 12, 6, false);
        SectDoors.mark(h.getLevel(), origin, h.getLevel().getGameTime() - 40);
        h.onEachTick(() -> {
            if (h.getTick() % 5 == 2) {
                SectDoors.sweep(h.getLevel());
            }
        });
        h.runAfterDelay(120, () -> h.assertTrue(h.getLevel().getBlockState(origin).getValue(GateDoorBlock.OPEN),
                "shut at once on somebody who may be about to go through"));
        h.runAfterDelay(290, () -> {
            h.assertFalse(h.getLevel().getBlockState(origin).getValue(GateDoorBlock.OPEN), "the gate stands open with the guards beside it");
            h.getLevel().setDayTime(saved);
            h.succeed();
        });
    }

    /** A person who stands IN the doorway of the gate still keeps it open (nobody is shut in). */
    @GameTest(template = YARD, timeoutTicks = 330, batch = "sect_check_gate_inside")
    public static void aPersonInTheGatewayStillKeepsItOpen(GameTestHelper h) {
        long saved = h.getLevel().getDayTime();
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        BlockPos origin = placeGate(h);
        var state = h.getLevel().getBlockState(origin);
        var gate = (GateDoorBlock) state.getBlock();
        gate.setOpen(h.getLevel(), origin, true);
        BlockPos in = GateDoorBlock.partPos(origin, state.getValue(GateDoorBlock.FACING), 1, 0);
        var mob = new net.minecraft.world.entity.animal.Cat(net.minecraft.world.entity.EntityType.CAT, h.getLevel());
        mob.setNoAi(true);
        mob.setPos(in.getX() + 0.5D, in.getY(), in.getZ() + 0.5D);
        h.getLevel().addFreshEntity(mob);
        SectDoors.mark(h.getLevel(), origin, h.getLevel().getGameTime() - 40);
        h.onEachTick(() -> {
            if (h.getTick() % 5 == 2) {
                SectDoors.sweep(h.getLevel());
            }
        });
        h.runAfterDelay(290, () -> {
            h.assertTrue(h.getLevel().getBlockState(origin).getValue(GateDoorBlock.OPEN), "shut on somebody who stands in the gateway");
            h.getLevel().setDayTime(saved);
            h.succeed();
        });
    }

    /**
     * A guard who answers the knock of a member without a tablet swings the gate open for him, but never shuts an open gate in his face
     * (the old code toggled it: a second knock or a gate left open closed it).
     */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "sect_check_knock_open")
    public static void aKnockAnsweredAtAnOpenGateLeavesItOpen(GameTestHelper h) {
        BlockPos origin = placeGate(h);
        var state = h.getLevel().getBlockState(origin);
        var gate = (GateDoorBlock) state.getBlock();
        SectDisciple who = npc(h, empty(h), "porter_oh", 12.5D, 12.5D, false);
        gate.setOpen(h.getLevel(), origin, true);
        h.assertTrue(SectKnock.openFor(h.getLevel(), origin, gate, who), "the open gate was not accepted");
        h.assertTrue(h.getLevel().getBlockState(origin).getValue(GateDoorBlock.OPEN), "an open gate was shut by the answer to a knock");
        gate.setOpen(h.getLevel(), origin, false);
        h.assertTrue(SectKnock.openFor(h.getLevel(), origin, gate, who), "the shut gate was not swung");
        h.assertTrue(SectDoors.tracked(h.getLevel()) > 0, "the opened gate is not entered for closing");
        h.succeed();
    }

    private static BlockState hall(boolean upper) {
        return HallJoinery.HALL_DOOR.get().defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST)
                .setValue(DoorBlock.HALF, upper ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER);
    }
}
