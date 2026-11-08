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
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Nobody may be put inside a solid block (fresh-sect playtest 19.10: two people stood inside pillars). The whole baked sect is
 * stamped at each of the four rotations; every place an NPC is sent to stand (waypoints, the walking cell of every spot, the cell
 * beside every bed) must leave room for a person (0.6 x 1.6 blocks, the lowest 0.2 left out for rugs and mats) by the COLLISION
 * shapes of the stamped blocks, not merely by the block being air.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/CollisionGetter.java#noCollision.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class HuaBakedStandingGameTests {

    private static final int PRELOAD_TICKS = 400;

    private HuaBakedStandingGameTests() {
    }

    @GameTest(template = "sect_yard", batch = "hua_baked", timeoutTicks = 8000)
    public static void layoutPointsAreNotInsideSolidBlocksAtEveryRotation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        HuaBaked.Index index = HuaBaked.index(server);
        helper.assertTrue(index != null, "baked index missing");
        net.minecraft.Util.backgroundExecutor().execute(() -> HuaBaked.preload(server));
        BlockPos corner = helper.absolutePos(BlockPos.ZERO);
        List<Map.Entry<HuaBaked.Group, HuaBaked.Cell>> cells = HuaBaked.cells(index);
        List<String> problems = new ArrayList<>();
        int[] checked = new int[1];
        int tick = PRELOAD_TICKS;
        for (int rot = 0; rot < 4; rot++) {
            MountHuaSite site = new MountHuaSite(corner.getX() + 3000 + rot * 600, corner.getZ() + 9000, 66, rot, 1L, true);
            for (Map.Entry<HuaBaked.Group, HuaBaked.Cell> e : cells) {
                helper.runAtTickTime(tick++, () -> helper.assertTrue(
                        HuaBaked.placeCell(server, level, site, index, e.getKey(), e.getValue(), null, level.getRandom(), 2),
                        "template missing: " + e.getValue().file()));
            }
            int rotation = rot;
            helper.runAtTickTime(tick++, () -> {
                SectLayoutFile layout = SectLayoutFile.anchored(site);
                helper.assertTrue(layout != null, "layout missing");
                for (SectLayoutFile.Waypoint w : layout.waypoints()) {
                    check(level, "rotation " + rotation + " waypoint " + w.id(), w.pos(), problems, checked);
                }
                for (SectLayoutFile.Spot s : layout.spots()) {
                    check(level, "rotation " + rotation + " spot " + s.id() + " (" + s.kind() + ")", s.walkTo(), problems, checked);
                }
                for (SectLayoutFile.Spot s : layout.spots()) {
                    // the seat or standing cell itself: a person there (sitting 1.0 high, from the seat's own height) must not meet a pillar
                    checkPillars(level, "rotation " + rotation + " spot " + s.id() + " (" + s.kind() + ") cell", s.pos(), problems);
                }
                for (SectLayoutFile.Bed b : layout.beds()) {
                    check(level, "rotation " + rotation + " bed " + b.id() + " approach", b.approach(), problems, checked);
                }
            });
        }
        helper.runAtTickTime(tick + 1, () -> {
            helper.assertTrue(checked[0] > 4 * 700, "too few points checked: " + checked[0]);
            helper.assertTrue(problems.isEmpty(), problems.size() + " layout points inside solid blocks of " + checked[0] + ", e.g.\n"
                    + String.join("\n", problems.subList(0, Math.min(10, problems.size()))));
            helper.succeed();
        });
    }

    private static void checkPillars(ServerLevel level, String what, BlockPos at, List<String> problems) {
        AABB box = new AABB(at.getX() + 0.2, at.getY() + 0.3, at.getZ() + 0.2, at.getX() + 0.8, at.getY() + 1.0, at.getZ() + 0.8);
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-1, 0, -1), at.offset(1, 1, 1))) {
            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(p);
            String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
            if (id.contains("pillar") && !st.getCollisionShape(level, p).move(p.getX(), p.getY(), p.getZ()).isEmpty()
                    && st.getCollisionShape(level, p).move(p.getX(), p.getY(), p.getZ()).toAabbs().stream().anyMatch(box::intersects)) {
                problems.add(what + " at " + at.toShortString() + " meets " + st + " at " + p.toShortString());
            }
        }
    }

    private static void check(ServerLevel level, String what, BlockPos at, List<String> problems, int[] checked) {
        if (at == null) {
            return;
        }
        checked[0]++;
        AABB box = new AABB(at.getX() + 0.2, at.getY() + 0.2, at.getZ() + 0.2, at.getX() + 0.8, at.getY() + 1.6, at.getZ() + 0.8);
        if (!level.noCollision(box)) {
            problems.add(what + " at " + at.toShortString() + ": " + level.getBlockState(at) + " / " + level.getBlockState(at.above()));
        }
    }
}
