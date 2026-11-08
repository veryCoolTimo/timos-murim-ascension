package io.github.verycooltimo.murim.world.hua;

import io.github.verycooltimo.murim.MurimMod;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * No rise of 2 blocks or more between two neighbouring blocks of the trail from the mountain foot to the sect (smoke pass 22.10: 1-2
 * rises of 2-3 blocks in every world, which the walk audit could not pass). The trail height is a function of the column, so it is
 * checked for every foot height, seed and all four rotations; the baked sect carries the old trail, so the stamped world is checked too.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class HuaTrailStepGameTests {
    private static final int PRELOAD_TICKS = 400;

    private HuaTrailStepGameTests() {
    }

    /** World columns of the trail corridor (a few blocks wider than the path on both sides). */
    private static Set<Long> corridor(MountHuaSite site) {
        MountHuaShape shape = site.shape();
        Set<Long> out = new HashSet<>();
        for (int i = 0; i < shape.trailLength(); i++) {
            double[] p = shape.trailPoint(i);
            int[] w = site.toWorld(p[0], p[1]);
            for (int dx = -5; dx <= 5; dx++) {
                for (int dz = -5; dz <= 5; dz++) {
                    out.add(BlockPos.asLong(w[0] + dx, 0, w[1] + dz));
                }
            }
        }
        return out;
    }

    private static boolean onTrail(MountHuaSite site, int x, int z) {
        double[] t = site.shape().trailAt(site.centerU(x, z), site.centerV(x, z));
        return t != null && t[0] <= 2.1;
    }

    @GameTest(template = "camp_floor", timeoutTicks = 200)
    public static void trailStepsAreOneBlockAtEveryRotationAndFoot(GameTestHelper helper) {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (long seed : new long[] {HuaBaked.CANONICAL_SEED, 1915L, 424242L}) {
            for (int base : new int[] {63, 67, 80}) {
                for (int rot = 0; rot < 4; rot++) {
                    MountHuaSite site = new MountHuaSite(0, 0, base, rot, seed, seed == HuaBaked.CANONICAL_SEED);
                    for (long key : corridor(site)) {
                        int x = BlockPos.getX(key);
                        int z = BlockPos.getZ(key);
                        if (!onTrail(site, x, z)) {
                            continue;
                        }
                        int top = topOf(site, x, z);
                        for (int[] d : new int[][] {{1, 0}, {0, 1}}) {
                            if (!onTrail(site, x + d[0], z + d[1])) {
                                continue;
                            }
                            checked++;
                            int rise = Math.abs(topOf(site, x + d[0], z + d[1]) - top);
                            if (rise >= 2 && problems.size() < 12) {
                                problems.add("seed " + seed + " base " + base + " rotation " + rot + " at " + x + "," + z + ": rise " + rise);
                            }
                        }
                    }
                }
            }
        }
        helper.assertTrue(checked > 30000, "too few trail neighbours checked: " + checked);
        helper.assertTrue(problems.isEmpty(), "trail rises of 2 blocks or more, e.g.\n" + String.join("\n", problems));
        helper.succeed();
    }

    private static int topOf(MountHuaSite site, int x, int z) {
        return HuaTrailMend.topOf(site, site.shape(), site.centerU(x, z), site.centerV(x, z));
    }

    /** The baked sect is stamped, the trail mended as the terrain pass does, and the blocks themselves are measured. */
    @GameTest(template = "sect_yard", batch = "hua_baked", timeoutTicks = 8000)
    public static void stampedTrailHasNoRiseOfTwoBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        HuaBaked.Index index = HuaBaked.index(server);
        helper.assertTrue(index != null, "baked index missing");
        net.minecraft.Util.backgroundExecutor().execute(() -> HuaBaked.preload(server));
        BlockPos corner = helper.absolutePos(BlockPos.ZERO);
        List<Map.Entry<HuaBaked.Group, HuaBaked.Cell>> cells = HuaBaked.cells(index);
        List<String> problems = new ArrayList<>();
        int[] counted = new int[3];
        int tick = PRELOAD_TICKS;
        for (int rot = 0; rot < 4; rot++) {
            MountHuaSite site = new MountHuaSite(corner.getX() + 3000 + rot * 600, corner.getZ() + 30000, 67, rot, HuaBaked.CANONICAL_SEED, true);
            helper.runAtTickTime(tick++, () -> {
                // The procedural rock the baked cells are stamped over (the test world has none): solid to the planned trail level.
                BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
                for (long key : corridor(site)) {
                    int x = BlockPos.getX(key);
                    int z = BlockPos.getZ(key);
                    int plan = topOf(site, x, z);
                    for (int y = plan - 10; y <= plan + 12; y++) {
                        level.setBlock(at.set(x, y, z), y <= plan ? net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()
                                : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            });
            for (Map.Entry<HuaBaked.Group, HuaBaked.Cell> e : cells) {
                helper.runAtTickTime(tick++, () -> HuaBaked.placeCell(server, level, site, index, e.getKey(), e.getValue(), null,
                        level.getRandom(), 2));
            }
            int rotation = rot;
            helper.runAtTickTime(tick++, () -> counted[0] += rises(level, site, rotation, new ArrayList<>(), new int[3]));
            // As worldgen runs it: per chunk the baked stamp over the 3 x 3 chunks around it, then the mend, chunk after chunk. A mend
            // that covers the chunk alone is undone along the chunk borders by the stamp of the next chunk (author's world 23.10: a drop of 6).
            helper.runAtTickTime(tick++, () -> {
                BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
                for (long key : corridor(site)) {
                    int x = BlockPos.getX(key);
                    int z = BlockPos.getZ(key);
                    int plan = topOf(site, x, z);
                    for (int y = plan - 10; y <= plan + 12; y++) {
                        level.setBlock(at.set(x, y, z), y <= plan ? net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()
                                : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            });
            List<ChunkPos> order = new ArrayList<>();
            Set<ChunkPos> chunks = new HashSet<>();
            for (long key : corridor(site)) {
                chunks.add(new ChunkPos(BlockPos.getX(key) >> 4, BlockPos.getZ(key) >> 4));
            }
            order.addAll(chunks);
            order.sort(java.util.Comparator.<ChunkPos>comparingInt(c -> c.x).thenComparingInt(c -> c.z));
            for (ChunkPos c : order) {
                helper.runAtTickTime(tick++, () -> {
                    net.minecraft.world.level.levelgen.structure.BoundingBox clip = new net.minecraft.world.level.levelgen.structure.BoundingBox(
                            c.getMinBlockX() - 16, level.getMinBuildHeight(), c.getMinBlockZ() - 16, c.getMaxBlockX() + 16,
                            level.getMaxBuildHeight() - 1, c.getMaxBlockZ() + 16);
                    HuaBaked.place(server, level, site, clip, level.getRandom(), 2);
                    HuaTrailMend.apply(level, c, site);
                });
            }
            helper.runAtTickTime(tick++, () -> rises(level, site, rotation, problems, counted));
        }
        helper.runAtTickTime(tick + 1, () -> {
            helper.assertTrue(counted[1] > 3000, "too few stamped trail neighbours checked: " + counted[1]);
            helper.assertTrue(counted[0] > 0, "no rise in the stamped trail before the mend: the check measures nothing");
            helper.assertTrue(problems.isEmpty(), problems.size() + " rises of 2 blocks or more after the mend, e.g.\n" + String.join("\n", problems));
            helper.succeed();
        });
    }

    /** Counts (and collects, labelled) the 2-block rises between stamped trail blocks; returns how many there are. */
    private static int rises(ServerLevel level, MountHuaSite site, int rot, List<String> out, int[] counted) {
        int n = 0;
        for (long key : corridor(site)) {
            int x = BlockPos.getX(key);
            int z = BlockPos.getZ(key);
            if (!onTrail(site, x, z)) {
                continue;
            }
            int top = surface(level, site, x, z);
            for (int[] d : new int[][] {{1, 0}, {0, 1}}) {
                if (!onTrail(site, x + d[0], z + d[1])) {
                    continue;
                }
                int other = surface(level, site, x + d[0], z + d[1]);
                if (top == Integer.MIN_VALUE || other == Integer.MIN_VALUE) {
                    continue;
                }
                counted[1]++;
                if (Math.abs(other - top) >= 2) {
                    n++;
                    double[] tr = site.shape().trailAt(site.centerU(x, z), site.centerV(x, z));
                    out.add("after rotation " + rot + " idx " + (int) tr[2] + " local " + (int) site.centerU(x, z) + "," + (int) site.centerV(x, z)
                            + ": " + top + " -> " + other + " " + level.getBlockState(new BlockPos(x, top, z)).getBlock().getDescriptionId()
                            + " / " + level.getBlockState(new BlockPos(x + d[0], other, z + d[1])).getBlock().getDescriptionId());
                }
            }
        }
        return n;
    }

    /** Highest floor block of a stamped column near the planned trail level, posts, curbs and lanterns left out; MIN_VALUE where nothing is stamped. */
    private static int surface(ServerLevel level, MountHuaSite site, int x, int z) {
        int plan = topOf(site, x, z);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = plan + HuaTrailMend.SCAN; y >= plan - HuaTrailMend.SCAN; y--) {
            if (HuaTrailMend.isFloor(level.getBlockState(pos.set(x, y, z)))) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }
}
