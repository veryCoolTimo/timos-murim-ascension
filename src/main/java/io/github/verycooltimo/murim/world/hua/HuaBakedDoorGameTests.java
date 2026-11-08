package io.github.verycooltimo.murim.world.hua;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.HallGrandDoors;
import io.github.verycooltimo.murim.decor.HallJoinery;
import io.github.verycooltimo.murim.decor.LatticeTransomBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Doors and the lattice transoms over them in the baked sect, at all four site rotations (docs/adr/0004; author 19.10 after the
 * first outside playtest: «в куче зданий люки стоят над дверьми неправильно, там дыры»).
 *
 * <p>The cells that hold a {@code hall_door} are stamped at a site of each rotation and every door is checked against its
 * baked source: both halves with the source facing turned by VANILLA's {@link Rotation#rotate(Direction)} (not by the block's own
 * rotate, which would test the block against itself, as the signature test of the sect once did), the same hinge and open state,
 * and a transom above it whose facing and {@code centered} flag are those of the source, turned the same way, and whose panel spans the
 * whole doorway (the long side of the closed leaf). A transom that faces the other axis after a turn leaves a strip of open sky
 * beside the panel, which is the hole the author saw.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate.java#filterBlocks
 * (positions come back turned and offset, states turned by the block's own rotate),
 * reference/minecraft-src/net/minecraft/world/level/block/DoorBlock.java#getShape.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class HuaBakedDoorGameTests {

    /** Ticks the background loading of all templates gets before the first stamp (as in {@link HuaBakedGameTests}). */
    private static final int PRELOAD_TICKS = 400;
    private static final double EPS = 1.0E-6;

    private HuaBakedDoorGameTests() {
    }

    private record DoorCell(HuaBaked.Group group, HuaBaked.Cell cell) {
    }

    @GameTest(template = "sect_yard", batch = "hua_baked", timeoutTicks = 6000)
    public static void transomsLieOverTheDoorLeafAtEveryRotation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        HuaBaked.Index index = HuaBaked.index(server);
        helper.assertTrue(index != null, "baked index missing from the mod resources");
        net.minecraft.Util.backgroundExecutor().execute(() -> HuaBaked.preload(server));
        BlockPos corner = helper.absolutePos(BlockPos.ZERO);
        int[] counts = new int[3]; // doors, transoms, rotations done
        List<String> problems = new ArrayList<>();
        // All callbacks are registered now: GameTestInfo iterates its tick-time map, and adding to it from inside a callback crashes the tick loop.
        List<Map.Entry<HuaBaked.Group, HuaBaked.Cell>> cells = HuaBaked.cells(index);
        int tick = PRELOAD_TICKS;
        for (int rot = 0; rot < 4; rot++) {
            // foot height 66 (the bake world had 67), the same rule as the stamp test
            MountHuaSite site = new MountHuaSite(corner.getX() + 3000 + rot * 600, corner.getZ() + 6000, 66, rot, 1L, true);
            Rotation rotation = Rotation.values()[rot];
            for (Map.Entry<HuaBaked.Group, HuaBaked.Cell> e : cells) {
                helper.runAtTickTime(tick++, () -> {
                    StructureTemplate t = HuaBaked.templateFor(server, e.getValue().file());
                    helper.assertTrue(t != null, "template missing: " + e.getValue().file());
                    if (t.filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(), HallJoinery.HALL_DOOR.get()).isEmpty()) {
                        return;
                    }
                    helper.assertTrue(HuaBaked.placeCell(server, level, site, index, e.getKey(), e.getValue(), null, level.getRandom(), 2),
                            "template missing: " + e.getValue().file());
                    checkCell(level, server, index, site, rotation, new DoorCell(e.getKey(), e.getValue()), counts, problems);
                });
            }
        }
        helper.runAtTickTime(tick + 1, () -> {
            helper.assertTrue(counts[0] >= 100 * 4, "doors checked: " + counts[0] + " (the sect has 113 per rotation)");
            helper.assertTrue(problems.isEmpty(), problems.size() + " door/transom problems of " + counts[0] + " doors, e.g.\n"
                    + String.join("\n", problems.subList(0, Math.min(12, problems.size()))));
            helper.succeed();
        });
    }

    private static void checkCell(ServerLevel level, MinecraftServer server, HuaBaked.Index index, MountHuaSite site,
            Rotation rotation, DoorCell dc, int[] counts, List<String> problems) {
        StructureTemplate template = HuaBaked.templateFor(server, dc.cell().file());
        BlockPos at = HuaBaked.cellOrigin(index, dc.group(), dc.cell(), site);
        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rotation);
        // filterBlocks turns the positions AND the states (with the block's own rotate); the states as baked come from a second call
        // without a turn, in the same palette order, so that the expected facing below is turned by vanilla's Rotation alone
        Map<BlockPos, BlockState> transoms = new HashMap<>();
        List<StructureTemplate.StructureBlockInfo> turnedTransoms = template.filterBlocks(at, settings, HallGrandDoors.LATTICE_TRANSOM.get());
        List<StructureTemplate.StructureBlockInfo> bakedTransoms = template.filterBlocks(at, new StructurePlaceSettings(), HallGrandDoors.LATTICE_TRANSOM.get());
        for (int i = 0; i < turnedTransoms.size(); i++) {
            transoms.put(turnedTransoms.get(i).pos(), bakedTransoms.get(i).state());
        }
        List<StructureTemplate.StructureBlockInfo> turnedDoors = template.filterBlocks(at, settings, HallJoinery.HALL_DOOR.get());
        List<StructureTemplate.StructureBlockInfo> bakedDoors = template.filterBlocks(at, new StructurePlaceSettings(), HallJoinery.HALL_DOOR.get());
        for (int d = 0; d < turnedDoors.size(); d++) {
            StructureTemplate.StructureBlockInfo door = new StructureTemplate.StructureBlockInfo(turnedDoors.get(d).pos(), bakedDoors.get(d).state(), null);
            if (door.state().getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
                continue;
            }
            counts[0]++;
            BlockPos pos = door.pos();
            String where = "rotation " + rotation.ordinal() + " door at " + pos.toShortString();
            Direction facing = rotation.rotate(door.state().getValue(DoorBlock.FACING));
            BlockState lower = level.getBlockState(pos);
            BlockState upper = level.getBlockState(pos.above());
            if (!lower.is(HallJoinery.HALL_DOOR.get()) || lower.getValue(DoorBlock.FACING) != facing
                    || lower.getValue(DoorBlock.HINGE) != door.state().getValue(DoorBlock.HINGE)
                    || lower.getValue(DoorBlock.OPEN) != door.state().getValue(DoorBlock.OPEN)) {
                problems.add(where + ": lower half " + lower + ", expected facing " + facing + " of " + door.state());
                continue;
            }
            if (!upper.is(HallJoinery.HALL_DOOR.get()) || upper.getValue(DoorBlock.HALF) != DoubleBlockHalf.UPPER
                    || upper.getValue(DoorBlock.FACING) != facing) {
                problems.add(where + ": upper half " + upper);
                continue;
            }
            BlockState sourceTop = transoms.get(pos.above(2));
            BlockState top = level.getBlockState(pos.above(2));
            if (sourceTop == null) {
                continue; // no transom was baked over this door (a door under a beam or a ceiling)
            }
            counts[1]++;
            if (!top.is(HallGrandDoors.LATTICE_TRANSOM.get())) {
                problems.add(where + ": no transom above, " + top);
                continue;
            }
            if (top.getValue(LatticeTransomBlock.FACING) != rotation.rotate(sourceTop.getValue(LatticeTransomBlock.FACING))
                    || top.getValue(LatticeTransomBlock.CENTERED) != sourceTop.getValue(LatticeTransomBlock.CENTERED)) {
                problems.add(where + ": transom " + top + " is not the baked " + sourceTop + " turned");
                continue;
            }
            // the panel must span the whole doorway: a transom turned to the other axis than its door leaves a strip of open sky
            // beside it, the hole of the playtest (a centred panel over a leaf at the block edge is the author's own depth step)
            AABB panel = top.getShape(level, pos.above(2)).bounds();
            AABB leaf = lower.setValue(DoorBlock.OPEN, false).getShape(level, pos).bounds();
            boolean spansX = panel.minX <= leaf.minX + EPS && panel.maxX >= leaf.maxX - EPS;
            boolean spansZ = panel.minZ <= leaf.minZ + EPS && panel.maxZ >= leaf.maxZ - EPS;
            if (!spansX || !spansZ) {
                // the leaf is thin on one axis only; the panel must cover the leaf's long side completely
                boolean leafAlongX = leaf.maxX - leaf.minX > leaf.maxZ - leaf.minZ;
                boolean covered = leafAlongX ? spansX : spansZ;
                if (!covered) {
                    problems.add(where + ": transom " + top + " does not span the doorway of " + lower + " (leaf x " + leaf.minX + ".." + leaf.maxX
                            + " z " + leaf.minZ + ".." + leaf.maxZ + ", panel x " + panel.minX + ".." + panel.maxX + " z " + panel.minZ + ".." + panel.maxZ + ")");
                    continue;
                }
            }
        }
    }
}
