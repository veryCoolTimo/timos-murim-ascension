package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.IntStore;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectNavigation;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Root causes of the 17.10 real-world day simulation (450 stuck relocations): furniture paths, rug under a lintel, rug cells. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectStuckGameTests {
    private SectStuckGameTests() { }

    private static SectDisciple npc(GameTestHelper h, SectLayout layout, String key, double x, double z) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setLayout(layout);
        d.setKeepAwake(true);
        Vec3 p = h.absoluteVec(new Vec3(x, 2, z));
        d.moveTo(p.x, p.y, p.z, 0, 0);
        h.getLevel().addFreshEntity(d);
        return d;
    }

    /** Vanilla calls a cabinet walkable (not a full cube); the sect navigation must route round it. */
    @GameTest(template = "sect_yard", timeoutTicks = 100, batch = "sect_stuck_furniture")
    public static void pathsGoRoundCabinetsNotThroughThem(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        var layout = new SectGameTests.Yard(h.absoluteVec(new Vec3(20.5, 2, 12.5)), 0.05D);
        BlockPos cabinet = new BlockPos(8, 2, 12);
        h.setBlock(cabinet, IntStore.CABINET.get().defaultBlockState());
        SectDisciple d = npc(h, layout, "cook_kim", 4.5, 12.5);
        h.assertTrue(SectNavigation.tallObstacle(h.getLevel(), h.absolutePos(cabinet), h.getLevel().getBlockState(h.absolutePos(cabinet))),
                "a cabinet is a tall obstacle");
        // The body has to stand on the floor first: ground navigation makes no path in the air.
        h.runAfterDelay(4, () -> {
            Path path = d.getNavigation().createPath(h.absolutePos(new BlockPos(12, 2, 12)), 0);
            h.assertTrue(path != null && path.canReach(), "no path past the cabinet: " + path + " ground=" + d.onGround() + " y=" + d.getY() + " at " + d.blockPosition());
            for (int i = 0; i < path.getNodeCount(); i++) {
                h.assertFalse(path.getNode(i).asBlockPos().equals(h.absolutePos(cabinet)), "the path leads through the cabinet");
            }
            h.succeed();
        });
    }

    /** Rugs, slabs, stairs and full blocks are not obstacles; cabinets, racks and chests are. */
    @GameTest(template = "sect_yard", timeoutTicks = 20, batch = "sect_stuck_furniture")
    public static void obstacleClassification(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos at = h.absolutePos(new BlockPos(3, 2, 3));
        level.setBlock(at, IntStore.RUG_RED.get().defaultBlockState(), 2);
        h.assertFalse(SectNavigation.tallObstacle(level, at, level.getBlockState(at)), "a rug is walked over");
        level.setBlock(at, Blocks.STONE_SLAB.defaultBlockState(), 2);
        h.assertFalse(SectNavigation.tallObstacle(level, at, level.getBlockState(at)), "a slab is walked over");
        level.setBlock(at, Blocks.OAK_STAIRS.defaultBlockState(), 2);
        h.assertFalse(SectNavigation.tallObstacle(level, at, level.getBlockState(at)), "stairs are walked over");
        level.setBlock(at, Blocks.STONE.defaultBlockState(), 2);
        h.assertFalse(SectNavigation.tallObstacle(level, at, level.getBlockState(at)), "a full cube is vanilla's business");
        level.setBlock(at, IntStore.RACK.get().defaultBlockState(), 2);
        h.assertTrue(SectNavigation.tallObstacle(level, at, level.getBlockState(at)), "a rack is an obstacle");
        level.setBlock(at, IntStore.CHEST.get().defaultBlockState(), 2);
        h.assertTrue(SectNavigation.tallObstacle(level, at, level.getBlockState(at)), "a chest is an obstacle");
        level.setBlock(at, IntStore.BED.get().defaultBlockState(), 2);
        h.assertTrue(SectNavigation.lowFurniture(level, at, level.getBlockState(at)), "a bed is low furniture: kept off, not walked over");
        h.assertFalse(SectNavigation.tallObstacle(level, at, level.getBlockState(at)), "a bed is not tall");
        level.setBlock(at, IntStore.RUG_RED.get().defaultBlockState(), 2);
        h.assertFalse(SectNavigation.lowFurniture(level, at, level.getBlockState(at)), "a rug is never in the way");
        level.setBlock(at, Blocks.AIR.defaultBlockState(), 2);
        h.succeed();
    }

    /** A rug (0.075) under a two-block lintel stopped a 1.95 tall body for good: the body is 1.9 now. */
    @GameTest(template = "sect_yard", timeoutTicks = 500, batch = "sect_stuck_lintel")
    public static void walksThroughLowDoorwayOverARug(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.NIGHT);
        h.assertTrue(ModEntities.SECT_DISCIPLE.get().getHeight() + 0.075F <= 2.0F, "body + rug must fit a two-block opening");
        var layout = new SectGameTests.Yard(h.absoluteVec(new Vec3(20.5, 2, 12.5)), 0.05D);
        for (int z = 1; z <= 23; z++) {
            for (int y = 2; y <= 4; y++) {
                if (z != 12 || y == 4) {
                    h.setBlock(new BlockPos(12, y, z), Blocks.STONE_BRICKS);
                }
            }
        }
        for (int x = 10; x <= 14; x++) {
            h.setBlock(new BlockPos(x, 2, 12), IntStore.RUG_RED.get().defaultBlockState());
        }
        SectDisciple d = npc(h, layout, "cook_kim", 4.5, 12.5);
        h.succeedWhen(() -> h.assertTrue(d.getX() > h.absolutePos(new BlockPos(16, 2, 12)).getX(),
                "the body did not get through a two-block doorway with a rug on the sill"));
    }

    /** A rug cell is a place to stand; the walker may finish its last step on it. */
    @GameTest(template = "sect_yard", timeoutTicks = 20, batch = "sect_stuck_furniture")
    public static void rugCellsAreStandable(GameTestHelper h) {
        BlockPos at = h.absolutePos(new BlockPos(5, 2, 5));
        h.getLevel().setBlock(at, IntStore.RUG_RED.get().defaultBlockState(), 2);
        h.assertTrue(SectLife.standable(h.getLevel(), at), "a rug cell must be standable");
        Vec3 stand = SectLife.stand(h.getLevel(), Vec3.atBottomCenterOf(at));
        h.assertTrue(Math.abs(stand.y - at.getY()) < 0.2D, "standing height on a rug: " + stand.y);
        h.getLevel().setBlock(at, Blocks.AIR.defaultBlockState(), 2);
        h.succeed();
    }

    /** Waking up on a bed top (a sleeper stands where he lay), he steps off and walks on; he is not stuck on the bed. */
    @GameTest(template = "sect_yard", timeoutTicks = 400, batch = "sect_stuck_bed")
    public static void leavesTheBedTopAfterSleep(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        var layout = new SectGameTests.Yard(h.absoluteVec(new Vec3(20.5, 2, 12.5)), 0.05D);
        var bed = IntStore.BED_BLUE.get().defaultBlockState().setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING,
                net.minecraft.core.Direction.WEST);
        h.setBlock(new BlockPos(4, 2, 12), bed.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.FOOT));
        h.setBlock(new BlockPos(3, 2, 12), bed.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD));
        h.setBlock(new BlockPos(3, 2, 13), IntStore.RUG_RED.get().defaultBlockState());
        h.setBlock(new BlockPos(4, 2, 13), IntStore.RUG_RED.get().defaultBlockState());
        h.setBlock(new BlockPos(5, 2, 12), IntStore.RUG_RED.get().defaultBlockState());
        SectDisciple d = npc(h, layout, "cook_kim", 3.5, 12.5);
        Vec3 top = h.absoluteVec(new Vec3(3.5, 2.5625, 12.5));
        d.moveTo(top.x, top.y, top.z, 0, 0);
        h.succeedWhen(() -> h.assertTrue(d.getX() > h.absolutePos(new BlockPos(9, 2, 12)).getX(), "stuck on the bed top at " + d.position()));
    }

    /** A sleeper left on top of the bed (vanilla found no room beside it) is put on the cell beside the bed. */
    @GameTest(template = "sect_yard", timeoutTicks = 40, batch = "sect_stuck_bed")
    public static void gettingUpLeavesTheBedTop(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        var layout = new SectGameTests.Yard(h.absoluteVec(new Vec3(20.5, 2, 12.5)), 0.05D);
        var bed = IntStore.BED_BLUE.get().defaultBlockState().setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING,
                net.minecraft.core.Direction.WEST);
        h.setBlock(new BlockPos(8, 2, 12), bed.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.FOOT));
        h.setBlock(new BlockPos(7, 2, 12), bed.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD));
        SectDisciple d = npc(h, layout, "porter_jang", 7.5, 12.5);
        d.setNoAi(true);
        Vec3 top = h.absoluteVec(new Vec3(7.5, 2.5625, 12.5));
        d.moveTo(top.x, top.y, top.z, 0, 0);
        d.setPos(top.x, top.y, top.z);
        SectLife.standBesideBed(d, h.absolutePos(new BlockPos(7, 2, 12)));
        h.assertTrue(d.getY() < h.absolutePos(new BlockPos(7, 2, 12)).getY() + 0.3D, "still on the bed top: " + d.position());
        h.succeed();
    }

    // ------------------------------------------------------------------ doors shut behind people (author 17.10)

    private static net.minecraft.world.level.block.state.BlockState oakDoor(boolean upper) {
        return Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING, net.minecraft.core.Direction.EAST)
                .setValue(net.minecraft.world.level.block.DoorBlock.HALF, upper
                        ? net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER
                        : net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
    }

    /** A person walks through a wooden door; the door is shut again after he has passed. */
    @GameTest(template = "sect_yard", timeoutTicks = 900, batch = "sect_doors_shut")
    public static void doorIsClosedBehindTheWalker(GameTestHelper h) {
        // Template beds would make the cook stop before the door instead of walking to the layout.
        SectPlaytestRig.clearYard(h);
        SectGameTests.setPeriod(h, SectSchedule.Period.NIGHT);
        var layout = new SectGameTests.Yard(h.absoluteVec(new Vec3(20.5, 2, 12.5)), 0.05D);
        for (int z = 1; z <= 23; z++) {
            for (int y = 2; y <= 4; y++) {
                h.setBlock(new BlockPos(12, y, z), Blocks.OAK_PLANKS);
            }
        }
        h.setBlock(new BlockPos(12, 2, 12), oakDoor(false));
        h.setBlock(new BlockPos(12, 3, 12), oakDoor(true));
        SectDisciple d = npc(h, layout, "cook_kim", 4.5, 12.5);
        // Exercise the walker independently of night-time bed searches and the shared world clock.
        Vec3 target = h.absoluteVec(new Vec3(20.5D, 2.0D, 12.5D));
        d.alarmGoal().assign(io.github.verycooltimo.murim.entity.SectAlarmGoal.Mode.RUN, target, target,
                h.getLevel().getGameTime() + 850L);
        boolean[] passed = {false};
        h.succeedWhen(() -> {
            if (d.getX() > h.absolutePos(new BlockPos(16, 2, 12)).getX()) {
                passed[0] = true;
            }
            h.assertTrue(passed[0], "the walker has not come through yet: at " + d.position() + " doing=" + d.doingKind() + " asleep=" + d.isSleeping() + " time=" + h.getLevel().getDayTime() + " tick=" + h.getTick() + " door=" + h.getBlockState(new BlockPos(12, 2, 12)).getValue(net.minecraft.world.level.block.DoorBlock.OPEN));
            h.assertFalse(h.getBlockState(new BlockPos(12, 2, 12)).getValue(net.minecraft.world.level.block.DoorBlock.OPEN),
                    "the door stayed open behind the walker");
        });
    }

    /** Ordinary doors without a living opener are left as found, including stale registrations. */
    @GameTest(template = "sect_yard", timeoutTicks = 200, batch = "sect_doors_shut")
    public static void forgottenHouseDoorStaysAsFound(GameTestHelper h) {
        BlockPos low = h.absolutePos(new BlockPos(12, 2, 12));
        h.setBlock(new BlockPos(12, 2, 12), oakDoor(false));
        h.setBlock(new BlockPos(12, 3, 12), oakDoor(true));
        var state = h.getLevel().getBlockState(low);
        ((net.minecraft.world.level.block.DoorBlock) state.getBlock()).setOpen(null, h.getLevel(), state, low, true);
        SectDoors.mark(h.getLevel(), low, h.getLevel().getGameTime());
        h.runAfterDelay(100, () -> {
            h.assertTrue(h.getLevel().getBlockState(low).getValue(net.minecraft.world.level.block.DoorBlock.OPEN), "house door closed without its opener");
            h.succeed();
        });
    }

    /** A door with somebody standing in front of it stays open until he leaves. */
    @GameTest(template = "sect_yard", timeoutTicks = 120, batch = "sect_doors_shut")
    public static void doorStaysOpenWhileSomebodyIsNear(GameTestHelper h) {
        BlockPos low = h.absolutePos(new BlockPos(12, 2, 12));
        h.setBlock(new BlockPos(12, 2, 12), oakDoor(false));
        h.setBlock(new BlockPos(12, 3, 12), oakDoor(true));
        var state = h.getLevel().getBlockState(low);
        ((net.minecraft.world.level.block.DoorBlock) state.getBlock()).setOpen(null, h.getLevel(), state, low, true);
        SectDoors.mark(h.getLevel(), low, h.getLevel().getGameTime());
        // Another man than the walker test: tests of one batch run side by side, and two people with one key make
        // SectLife.duplicate() discard the younger (by random UUID), which froze the walker half way (flaky 1 in 2).
        SectDisciple d = npc(h, new SectGameTests.Yard(h.absoluteVec(new Vec3(20.5, 2, 12.5)), 0.05D), "porter_jang", 11.5, 12.5);
        d.setNoAi(true);
        h.runAfterDelay(100, () -> {
            h.assertTrue(h.getLevel().getBlockState(low).getValue(net.minecraft.world.level.block.DoorBlock.OPEN), "door closed on the person in front of it");
            h.succeed();
        });
    }

    /** Holes review npc-8: a door open at load time (not in the registry) beside a sect person is shut as well. */
    @GameTest(template = "sect_yard", timeoutTicks = 400, batch = "sect_doors_adopt")
    public static void houseDoorOpenAtLoadIsNeverAdopted(GameTestHelper h) {
        BlockPos low = h.absolutePos(new BlockPos(12, 2, 12));
        h.setBlock(new BlockPos(12, 2, 12), oakDoor(false));
        h.setBlock(new BlockPos(12, 3, 12), oakDoor(true));
        var state = h.getLevel().getBlockState(low);
        ((net.minecraft.world.level.block.DoorBlock) state.getBlock()).setOpen(null, h.getLevel(), state, low, true);
        h.assertTrue(SectDoors.tracked(h.getLevel()) >= 0, "setup");
        SectDisciple d = npc(h, new SectGameTests.Yard(h.absoluteVec(new Vec3(20.5, 2, 12.5)), 0.05D), "porter_jang", 9.5, 12.5);
        d.setNoAi(true);
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        h.runAfterDelay(120, () -> {
            Vec3 passed = h.absoluteVec(new Vec3(18.5, 2, 12.5));
            d.setPos(passed.x, passed.y, passed.z);
            d.walking().walk(h.absoluteVec(new Vec3(21.5, 2, 12.5)), 0.4D);
        });
        h.runAfterDelay(350, () -> {
            h.assertTrue(h.getLevel().getBlockState(low).getValue(net.minecraft.world.level.block.DoorBlock.OPEN),
                    "ordinary house door was adopted after load");
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ doors left open (author 21.10)

    private static net.minecraft.world.level.block.state.BlockState hallDoor(boolean upper) {
        return io.github.verycooltimo.murim.decor.HallJoinery.HALL_DOOR.get().defaultBlockState()
                .setValue(net.minecraft.world.level.block.DoorBlock.FACING, net.minecraft.core.Direction.EAST)
                .setValue(net.minecraft.world.level.block.DoorBlock.HALF, upper
                        ? net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER
                        : net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
    }

    /** A person walks through the mod's hall door; it is shut again after he has passed. */
    @GameTest(template = "sect_yard", timeoutTicks = 900, batch = "sect_doors_shut")
    public static void hallDoorIsClosedBehindTheWalker(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.NIGHT);
        var layout = new SectGameTests.Yard(h.absoluteVec(new Vec3(20.5, 2, 12.5)), 0.05D);
        for (int z = 1; z <= 23; z++) {
            for (int y = 2; y <= 4; y++) {
                h.setBlock(new BlockPos(12, y, z), Blocks.OAK_PLANKS);
            }
        }
        h.setBlock(new BlockPos(12, 2, 12), hallDoor(false));
        h.setBlock(new BlockPos(12, 3, 12), hallDoor(true));
        SectDisciple d = npc(h, layout, "porter_oh", 4.5, 12.5);
        boolean[] passed = {false};
        h.succeedWhen(() -> {
            if (d.getX() > h.absolutePos(new BlockPos(16, 2, 12)).getX()) {
                passed[0] = true;
            }
            h.assertTrue(passed[0], "the walker has not come through yet: at " + d.position());
            h.assertFalse(h.getBlockState(new BlockPos(12, 2, 12)).getValue(net.minecraft.world.level.block.DoorBlock.OPEN),
                    "the hall door stayed open behind the walker");
        });
    }

    private static BlockPos placeGrand(GameTestHelper h) {
        var item = io.github.verycooltimo.murim.decor.HallGrandDoors.HALL_GRAND_DOOR_ITEM.get();
        BlockPos click = h.absolutePos(new BlockPos(6, 2, 6));
        var hit = new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(click), net.minecraft.core.Direction.SOUTH, click, false);
        item.place(new net.minecraft.world.item.context.BlockPlaceContext(h.getLevel(), null, net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.item.ItemStack(item), hit));
        return h.absolutePos(new BlockPos(5, 2, 6));
    }

    private static boolean grandAllOpen(GameTestHelper h, BlockPos origin, boolean want) {
        var grand = io.github.verycooltimo.murim.decor.HallGrandDoorBlock.class;
        var f = h.getLevel().getBlockState(origin).getValue(io.github.verycooltimo.murim.decor.HallGrandDoorBlock.FACING);
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 6; r++) {
                var s = h.getLevel().getBlockState(io.github.verycooltimo.murim.decor.HallGrandDoorBlock.partPos(origin, f, c, r));
                if (!grand.isInstance(s.getBlock()) || s.getValue(io.github.verycooltimo.murim.decor.HallGrandDoorBlock.OPEN) != want) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The grand door sets OPEN only at the end of its swing; the sweep used to drop it during the swing and never shut it. */
    @GameTest(template = "sect_yard", timeoutTicks = 200, batch = "sect_doors_shut")
    public static void grandDoorOpenedByAPersonIsShutOnBothLeaves(GameTestHelper h) {
        BlockPos origin = placeGrand(h);
        var grand = (io.github.verycooltimo.murim.decor.HallGrandDoorBlock) h.getLevel().getBlockState(origin).getBlock();
        h.assertTrue(grand.toggle(h.getLevel(), origin, null), "setup: swing started");
        SectDisciple keeper = npc(h, new SectGameTests.Yard(h.absoluteVec(new Vec3(12.5, 2, 6.5)), 0.05D),
                "seo_mu", 12.5, 6.5);
        keeper.setNoAi(true);
        SectDoors.mark(keeper, origin, h.getLevel().getGameTime());
        boolean[] wasOpen = {false};
        h.succeedWhen(() -> {
            wasOpen[0] |= grandAllOpen(h, origin, true);
            h.assertTrue(wasOpen[0], "the door never stood open");
            h.assertTrue(grandAllOpen(h, origin, false), "the grand door stands open");
        });
    }

    /** Ten empty seconds make a door eligible; an actual passer shuts it, rather than an invisible timer. */
    @GameTest(template = "sect_yard", timeoutTicks = 400, batch = "sect_doors_unattended")
    public static void unattendedHouseDoorStaysOpenBesidePasser(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        BlockPos low = h.absolutePos(new BlockPos(12, 2, 12));
        h.setBlock(new BlockPos(12, 2, 12), hallDoor(false));
        h.setBlock(new BlockPos(12, 3, 12), hallDoor(true));
        var state = h.getLevel().getBlockState(low);
        ((net.minecraft.world.level.block.DoorBlock) state.getBlock()).setOpen(null, h.getLevel(), state, low, true);
        SectDoors.markUnattended(h.getLevel(), low, h.getLevel().getGameTime());
        h.runAfterDelay(120, () -> h.assertTrue(h.getLevel().getBlockState(low).getValue(net.minecraft.world.level.block.DoorBlock.OPEN),
                "shut before the ten seconds were over"));
        h.runAfterDelay(240, () -> {
            h.assertTrue(h.getLevel().getBlockState(low).getValue(net.minecraft.world.level.block.DoorBlock.OPEN),
                    "unattended door closed without a passer");
            SectDisciple passer = npc(h, new SectGameTests.Yard(h.absoluteVec(new Vec3(22.5, 2, 12.5)), 0.05D),
                    "porter_oh", 18.5, 12.5);
            passer.setNoAi(true);
            passer.walking().walk(h.absoluteVec(new Vec3(21.5, 2, 12.5)), 0.4D);
            SectDoors.sweep(h.getLevel());
            h.assertTrue(h.getLevel().getBlockState(low).getValue(net.minecraft.world.level.block.DoorBlock.OPEN),
                    "a passer shut a house door he did not open");
            h.succeed();
        });
    }

    /** A player standing in the doorway is never shut on, not even by the safety net. */
    @GameTest(template = "sect_yard", timeoutTicks = 330, batch = "sect_doors_shut")
    public static void playerInTheDoorwayKeepsItOpen(GameTestHelper h) {
        BlockPos low = h.absolutePos(new BlockPos(12, 2, 12));
        h.setBlock(new BlockPos(12, 2, 12), hallDoor(false));
        h.setBlock(new BlockPos(12, 3, 12), hallDoor(true));
        var state = h.getLevel().getBlockState(low);
        ((net.minecraft.world.level.block.DoorBlock) state.getBlock()).setOpen(null, h.getLevel(), state, low, true);
        SectDoors.markUnattended(h.getLevel(), low, h.getLevel().getGameTime());
        var player = h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        Vec3 at = h.absoluteVec(new Vec3(12.5, 2, 12.5));
        player.setPos(at.x, at.y, at.z);
        h.getLevel().addFreshEntity(player);
        h.runAfterDelay(300, () -> {
            h.assertTrue(h.getLevel().getBlockState(low).getValue(net.minecraft.world.level.block.DoorBlock.OPEN), "closed on a player in the doorway");
            h.succeed();
        });
    }

    /** Somebody (a mob) standing in the doorway keeps a door the safety net found open from being shut. */
    @GameTest(template = "sect_yard", timeoutTicks = 330, batch = "sect_doors_shut")
    public static void personInTheDoorwayKeepsItOpen(GameTestHelper h) {
        BlockPos low = h.absolutePos(new BlockPos(12, 2, 12));
        h.setBlock(new BlockPos(12, 2, 12), hallDoor(false));
        h.setBlock(new BlockPos(12, 3, 12), hallDoor(true));
        var state = h.getLevel().getBlockState(low);
        ((net.minecraft.world.level.block.DoorBlock) state.getBlock()).setOpen(null, h.getLevel(), state, low, true);
        SectDoors.markUnattended(h.getLevel(), low, h.getLevel().getGameTime());
        Vec3 at = h.absoluteVec(new Vec3(12.5, 2, 12.5));
        var mob = new net.minecraft.world.entity.animal.Cat(net.minecraft.world.entity.EntityType.CAT, h.getLevel());
        mob.setNoAi(true);
        mob.setPos(at.x, at.y, at.z);
        h.getLevel().addFreshEntity(mob);
        h.runAfterDelay(300, () -> {
            h.assertTrue(h.getLevel().getBlockState(low).getValue(net.minecraft.world.level.block.DoorBlock.OPEN), "closed on a person in the doorway");
            h.succeed();
        });
    }

    /** Player proximity wins over night cleanup, including a stale NPC-opened registration. */
    @GameTest(template = "sect_yard", timeoutTicks = 330, batch = "sect_doors_player_night")
    public static void nearbyPlayerKeepsReopenedDoorAtNight(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.NIGHT);
        BlockPos low = h.absolutePos(new BlockPos(12, 2, 12));
        h.setBlock(new BlockPos(12, 2, 12), hallDoor(false));
        h.setBlock(new BlockPos(12, 3, 12), hallDoor(true));
        var player = h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        Vec3 at = h.absoluteVec(new Vec3(15.5, 2, 12.5));
        player.setPos(at.x, at.y, at.z);
        h.getLevel().addFreshEntity(player);
        SectDoors.mark(h.getLevel(), low, h.getLevel().getGameTime());
        var state = h.getLevel().getBlockState(low);
        ((net.minecraft.world.level.block.DoorBlock) state.getBlock()).setOpen(player, h.getLevel(), state, low, true);
        h.runAfterDelay(260, () -> {
            h.assertTrue(h.getLevel().getBlockState(low).getValue(net.minecraft.world.level.block.DoorBlock.OPEN),
                    "night cleanup closed the player's door while he stood nearby");
            Vec3 away = h.absoluteVec(new Vec3(22.5, 2, 12.5));
            player.setPos(away.x, away.y, away.z);
        });
        h.runAfterDelay(310, () -> {
            h.assertTrue(h.getLevel().getBlockState(low).getValue(net.minecraft.world.level.block.DoorBlock.OPEN),
                    "player-opened house door closed after the player left");
            h.succeed();
        });
    }

    /** An adopted door waits ten seconds after the last nearby mob leaves, even outside the narrow doorway. */
    @GameTest(template = "sect_yard", timeoutTicks = 330, batch = "sect_doors_mob_radius")
    public static void unownedHouseDoorStaysOpenAfterNearbyMobLeaves(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        BlockPos low = h.absolutePos(new BlockPos(12, 2, 12));
        h.setBlock(new BlockPos(12, 2, 12), hallDoor(false));
        h.setBlock(new BlockPos(12, 3, 12), hallDoor(true));
        var state = h.getLevel().getBlockState(low);
        ((net.minecraft.world.level.block.DoorBlock) state.getBlock()).setOpen(null, h.getLevel(), state, low, true);
        SectDoors.markUnattended(h.getLevel(), low, h.getLevel().getGameTime() - 240);
        var mob = new net.minecraft.world.entity.animal.Cat(net.minecraft.world.entity.EntityType.CAT, h.getLevel());
        mob.setNoAi(true);
        Vec3 at = h.absoluteVec(new Vec3(15.5, 2, 12.5));
        mob.setPos(at.x, at.y, at.z);
        h.getLevel().addFreshEntity(mob);
        SectDoors.sweep(h.getLevel());
        mob.discard();
        SectDisciple passer = npc(h, new SectGameTests.Yard(h.absoluteVec(new Vec3(22.5, 2, 12.5)), 0.05D),
                "porter_oh", 18.5, 12.5);
        passer.setNoAi(true);
        passer.walking().walk(h.absoluteVec(new Vec3(21.5, 2, 12.5)), 0.4D);
        h.runAfterDelay(120, () -> h.assertTrue(h.getLevel().getBlockState(low).getValue(net.minecraft.world.level.block.DoorBlock.OPEN),
                "mob proximity did not reset the ten-second timer"));
        h.runAfterDelay(300, () -> {
            h.assertTrue(h.getLevel().getBlockState(low).getValue(net.minecraft.world.level.block.DoorBlock.OPEN),
                    "unowned house door closed after the mob left");
            h.succeed();
        });
    }

    /** All parts, including the far leaf of a grand door, share the player protection area. */
    @GameTest(template = "sect_yard", timeoutTicks = 130, batch = "sect_doors_grand_player")
    public static void grandDoorFarLeafNeverClosesOnPlayer(GameTestHelper h) {
        BlockPos origin = placeGrand(h);
        var grand = (io.github.verycooltimo.murim.decor.HallGrandDoorBlock) h.getLevel().getBlockState(origin).getBlock();
        grand.setOpen(h.getLevel(), origin, true);
        SectDoors.mark(h.getLevel(), origin, h.getLevel().getGameTime());
        var player = h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        var facing = h.getLevel().getBlockState(origin).getValue(io.github.verycooltimo.murim.decor.HallGrandDoorBlock.FACING);
        Vec3 at = Vec3.atBottomCenterOf(io.github.verycooltimo.murim.decor.HallGrandDoorBlock.partPos(origin, facing, 3, 0));
        player.setPos(at.x, at.y, at.z);
        h.getLevel().addFreshEntity(player);
        h.runAfterDelay(100, () -> {
            h.assertTrue(grandAllOpen(h, origin, true), "grand door closed on player at the far leaf");
            h.succeed();
        });
    }

    /** Discovery must leave an untracked main gate under the gatekeeper's control. */
    @GameTest(template = "sect_yard", timeoutTicks = 330, batch = "sect_doors_gate_untracked")
    public static void untrackedGateIsNotAdopted(GameTestHelper h) {
        var item = io.github.verycooltimo.murim.decor.GateDoors.GATE_DOOR_ITEM.get();
        BlockPos click = h.absolutePos(new BlockPos(6, 2, 6));
        var hit = new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(click), net.minecraft.core.Direction.SOUTH, click, false);
        item.place(new net.minecraft.world.item.context.BlockPlaceContext(h.getLevel(), null, net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.item.ItemStack(item), hit));
        BlockPos origin = h.absolutePos(new BlockPos(5, 2, 6));
        var gate = (io.github.verycooltimo.murim.decor.GateDoorBlock) h.getLevel().getBlockState(origin).getBlock();
        gate.setOpen(h.getLevel(), origin, true);
        SectDisciple passer = npc(h, new SectGameTests.Yard(h.absoluteVec(new Vec3(18.5, 2, 6.5)), 0.05D),
                "porter_oh", 12.5, 6.5);
        passer.setNoAi(true);
        h.runAfterDelay(280, () -> {
            h.assertTrue(h.getLevel().getBlockState(origin).getValue(io.github.verycooltimo.murim.decor.GateDoorBlock.OPEN),
                    "discovery took over an untracked gate from the gatekeeper");
            // Once an NPC actually opens it, ordinary close-behind tracking still applies.
            SectDoors.mark(passer, origin, h.getLevel().getGameTime() - 40);
            SectDoors.sweep(h.getLevel());
            h.assertFalse(h.getLevel().getBlockState(origin).getValue(io.github.verycooltimo.murim.decor.GateDoorBlock.OPEN),
                    "tracked NPC gate was not shut");
            h.succeed();
        });
    }

    /** A following walker holds the door open before reaching its narrow safety area. */
    @GameTest(template = "sect_yard", timeoutTicks = 130, batch = "sect_doors_follower")
    public static void followingWalkerKeepsDoorOpen(GameTestHelper h) {
        BlockPos low = h.absolutePos(new BlockPos(12, 2, 12));
        h.setBlock(new BlockPos(12, 2, 12), oakDoor(false));
        h.setBlock(new BlockPos(12, 3, 12), oakDoor(true));
        var state = h.getLevel().getBlockState(low);
        ((net.minecraft.world.level.block.DoorBlock) state.getBlock()).setOpen(null, h.getLevel(), state, low, true);
        SectDisciple follower = npc(h, new SectGameTests.Yard(h.absoluteVec(new Vec3(20.5, 2, 12.5)), 0.05D),
                "porter_oh", 9.5, 12.5);
        follower.setNoAi(true);
        // Frozen actors do not settle under gravity; ground navigation needs this flag.
        // Same setup as SectRoutingGameTests (setNoAi + setOnGround).
        follower.setOnGround(true);
        h.runAfterDelay(10, () -> {
            follower.walking().walk(h.absoluteVec(new Vec3(18.5, 2, 12.5)), 0.4D);
            h.assertFalse(follower.getNavigation().isDone(), "setup: follower has no path");
            SectDoors.mark(follower, low, h.getLevel().getGameTime() - 40);
            SectDoors.sweep(h.getLevel());
            h.assertTrue(h.getLevel().getBlockState(low).getValue(net.minecraft.world.level.block.DoorBlock.OPEN),
                    "closed just ahead of a following walker");
            follower.walking().stop();
            Vec3 away = h.absoluteVec(new Vec3(20.5, 2, 12.5));
            follower.setPos(away.x, away.y, away.z);
        });
        h.runAfterDelay(60, () -> {
            h.assertFalse(h.getLevel().getBlockState(low).getValue(net.minecraft.world.level.block.DoorBlock.OPEN),
                    "door never shut after the follower passed");
            h.succeed();
        });
    }

    /** The large gate must also survive discovery sweeps during its opening animation and close all sixteen parts. */
    @GameTest(template = "sect_yard", timeoutTicks = 200, batch = "sect_doors_gate_swing")
    public static void gateClosesAfterOpeningSwing(GameTestHelper h) {
        var item = io.github.verycooltimo.murim.decor.GateDoors.GATE_DOOR_ITEM.get();
        BlockPos click = h.absolutePos(new BlockPos(6, 2, 6));
        var hit = new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(click), net.minecraft.core.Direction.SOUTH, click, false);
        item.place(new net.minecraft.world.item.context.BlockPlaceContext(h.getLevel(), null, net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.item.ItemStack(item), hit));
        BlockPos origin = h.absolutePos(new BlockPos(5, 2, 6));
        var gate = (io.github.verycooltimo.murim.decor.GateDoorBlock) h.getLevel().getBlockState(origin).getBlock();
        h.assertTrue(gate.toggle(h.getLevel(), origin, null), "setup: gate swing did not start");
        SectDisciple keeper = npc(h, new SectGameTests.Yard(h.absoluteVec(new Vec3(12.5, 2, 6.5)), 0.05D),
                "gatekeeper", 12.5, 6.5);
        keeper.setNoAi(true);
        SectDoors.mark(keeper, origin, h.getLevel().getGameTime());
        boolean[] opened = {false};
        h.succeedWhen(() -> {
            var state = h.getLevel().getBlockState(origin);
            opened[0] |= state.getValue(io.github.verycooltimo.murim.decor.GateDoorBlock.OPEN);
            h.assertTrue(opened[0], "gate never finished opening");
            var facing = state.getValue(io.github.verycooltimo.murim.decor.GateDoorBlock.FACING);
            for (int col = 0; col < 4; col++) {
                for (int row = 0; row < 4; row++) {
                    BlockPos part = io.github.verycooltimo.murim.decor.GateDoorBlock.partPos(origin, facing, col, row);
                    h.assertFalse(h.getLevel().getBlockState(part).getValue(io.github.verycooltimo.murim.decor.GateDoorBlock.OPEN),
                            "gate part stayed open: " + col + "," + row);
                }
            }
        });
    }
}
