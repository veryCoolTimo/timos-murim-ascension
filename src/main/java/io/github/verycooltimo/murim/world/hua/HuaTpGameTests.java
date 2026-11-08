package io.github.verycooltimo.murim.world.hua;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.sect.SectLayoutFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * {@code /murim dev hua tp <place>} must never put the player inside a block (smoke pass 22.10: {@code tp main_hall} dropped the
 * player into the wall of the hall and he suffocated in 40 s; the plan point of the zone lies under the raised floor of the baked hall).
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class HuaTpGameTests {

    private static final int PRELOAD_TICKS = 300;

    private HuaTpGameTests() {
    }

    /** A target in the middle of a solid block moves to the nearest free floor; a free target is left where it is. */
    @GameTest(template = "sect_yard", batch = "hua_tp_free", timeoutTicks = 60)
    public static void aTargetInsideSolidRockMovesToFreeSpace(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        for (int x = 4; x <= 12; x++) {
            for (int y = 2; y <= 9; y++) {
                for (int z = 4; z <= 12; z++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                }
            }
        }
        BlockPos inside = h.absolutePos(new BlockPos(8, 4, 8));
        double[] target = {inside.getX() + 0.5, inside.getY(), inside.getZ() + 0.5};
        MountHuaSite site = new MountHuaSite(inside.getX() + 500, inside.getZ() + 500, 66, 0, 1L, false);
        h.assertTrue(!level.noCollision(new net.minecraft.world.phys.AABB(inside)), "setup: the target cell is not solid");
        MountHuaCommand.placeInsideFreeSpace(level, site, "main_hall", target);
        BlockPos moved = BlockPos.containing(target[0], target[1], target[2]);
        h.assertTrue(MountHuaCommand.fits(level, moved), "the player was put at " + moved.toShortString() + ", where a body does not fit");
        h.assertTrue(moved.distSqr(inside) <= 10 * 10, "moved too far: " + moved.toShortString());
        BlockPos air = h.absolutePos(new BlockPos(18, 2, 18));
        double[] free = {air.getX() + 0.5, air.getY(), air.getZ() + 0.5};
        MountHuaCommand.placeInsideFreeSpace(level, site, "main_hall", free);
        h.assertTrue(free[0] == air.getX() + 0.5 && free[1] == air.getY() && free[2] == air.getZ() + 0.5, "a free target was moved");
        h.succeed();
    }

    /** The real hall, stamped at each rotation: the plan point of the zone is no place for a body, the place the command picks is. */
    @GameTest(template = "sect_yard", batch = "hua_tp_hall", timeoutTicks = 6000)
    public static void tpMainHallStandsInTheHallAtEveryRotation(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        HuaBaked.Index index = HuaBaked.index(server);
        h.assertTrue(index != null, "baked index missing");
        net.minecraft.Util.backgroundExecutor().execute(() -> HuaBaked.preload(server));
        BlockPos corner = h.absolutePos(BlockPos.ZERO);
        var cells = HuaBaked.cells(index);
        int tick = PRELOAD_TICKS;
        for (int rot = 0; rot < 4; rot++) {
            final int r = rot;
            MountHuaSite site = new MountHuaSite(corner.getX() + 3000 + rot * 600, corner.getZ() + 12000, 66, rot, 1L, true);
            SectLayoutFile layout = SectLayoutFile.anchored(site);
            h.assertTrue(layout != null, "layout missing");
            SectLayoutFile.Room hall = layout.rooms().stream().filter(x -> x.building().equals("main_hall") && x.floor() == 0
                    && !"corridor".equals(x.function())).max(java.util.Comparator.comparingInt(SectLayoutFile.Room::walkable)).orElseThrow();
            BoundingBox near = new BoundingBox(hall.bounds().x0() - 24, hall.y() - 40, hall.bounds().z0() - 24, hall.bounds().x1() + 24, hall.y() + 40,
                    hall.bounds().z1() + 24);
            List<Map.Entry<HuaBaked.Group, HuaBaked.Cell>> mine = new ArrayList<>();
            for (var e : cells) {
                if (HuaBaked.cellBox(index, e.getKey(), e.getValue(), site).intersects(near)) {
                    mine.add(e);
                }
            }
            h.assertTrue(!mine.isEmpty(), "no baked cell near the main hall");
            for (int i = 0; i < mine.size(); i++) {
                var e = mine.get(i);
                h.runAtTickTime(tick + i, () -> h.assertTrue(HuaBaked.placeCell(server, level, site, index, e.getKey(), e.getValue(), null,
                        level.getRandom(), 2), "template missing: " + e.getValue().file()));
            }
            int check = tick + mine.size() + 10;
            h.runAtTickTime(check, () -> {
                double[] target = MountHuaCommand.viewpoint(site, "main_hall");
                MountHuaCommand.placeInsideFreeSpace(level, site, "main_hall", target);
                BlockPos stand = BlockPos.containing(target[0], target[1], target[2]);
                h.assertTrue(MountHuaCommand.fits(level, stand), "rotation " + r + ": tp main_hall puts the player at " + stand.toShortString()
                        + " (" + level.getBlockState(stand) + " / " + level.getBlockState(stand.above()) + ")");
                h.assertTrue(hall.bounds().contains(stand.getX(), stand.getZ()) && Math.abs(stand.getY() - hall.y()) <= 1,
                        "rotation " + r + ": tp main_hall puts the player at " + stand.toShortString() + ", not in the hall (" + hall.bounds() + " y " + hall.y() + ")");
            });
            tick = check + 2;
        }
        h.runAtTickTime(tick, h::succeed);
    }
}
