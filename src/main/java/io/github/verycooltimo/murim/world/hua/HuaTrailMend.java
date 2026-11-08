package io.github.verycooltimo.murim.world.hua;

import io.github.verycooltimo.murim.registry.ModBlocks;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Keeps every step of the trail to one block. The terrain pass does it for the procedural mountain
 * ({@link MountHuaShape#TRAIL_STEP_LIMIT}); the baked sect carries the trail as an older profile generated it, a block or two off the plan
 * here and there, so two columns side by side could stand 2 or 3 blocks apart, a rise a player cannot walk up (smoke pass 22.10).
 * After the stamp this reads what the bake holds (not the plan) for the trail columns around the chunk, lowers each column to the
 * lowest height the Lipschitz-1 rule leaves it ({@code min over d (top(d) + steps along the trail)}, cut only) and lays the tread again.
 * The result of a column depends only on the bake and the plan, so chunks agree on their borders.
 */
public final class HuaTrailMend {
    /** Steps along the trail within which a column can hold another down (a rise of 1 per column leaves the trail alone, so only close kinks matter). */
    private static final int REACH = 12;

    /** The baked trail stands within this many blocks of the plan; anything higher is a tree or a roof, not the trail. */
    static final int SCAN = 3;

    private HuaTrailMend() {
    }

    /**
     * Mends the trail columns in the 3 x 3 chunks around the chunk; returns how many it changed. The baked pass of a chunk stamps that same
     * 3 x 3 area ({@link HuaBaked#apply}), so it puts the old trail back over columns a neighbour already mended; the last stamp over a
     * column is the last chunk's whose area holds it, and this must mend the same area after it. Mending the one chunk left drops of up
     * to 6 blocks along chunk borders (author's world 23.10).
     */
    public static int apply(ServerLevelAccessor level, ChunkPos chunk, MountHuaSite site) {
        if (!site.touchesChunk(chunk.x, chunk.z) || !site.canonical()) {
            return 0;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return 0;
        }
        Columns cols = new Columns(level, server, site);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int mended = 0;
        for (int x = chunk.getMinBlockX() - 16; x <= chunk.getMaxBlockX() + 16; x++) {
            for (int z = chunk.getMinBlockZ() - 16; z <= chunk.getMaxBlockZ() + 16; z++) {
                if (!cols.onTrail(x, z)) {
                    continue;
                }
                int now = cols.stamped(x, z);
                int top = cols.limited(x, z);
                if (top >= now) {
                    continue;
                }
                double u = site.centerU(x, z);
                double v = site.centerV(x, z);
                double[] tr = site.shape().trailAt(u, v);
                for (int y = top + 1; y <= now + 5; y++) {
                    HuaStampBlocks.set(level, pos.set(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
                for (int y = top - 1; y >= top - 4 && level.getBlockState(pos.set(x, y, z)).isAir(); y--) {
                    HuaStampBlocks.set(level, pos, ModBlocks.POLISHED_HUA_GRANITE.get().defaultBlockState(), 2);
                }
                HuaStampBlocks.set(level, pos.set(x, top, z), tread(cols, site, tr, x, z, top), 2);
                if (tr[2] < 160 && tr[0] > 1.5 && ((int) tr[2]) % 4 == 0) {
                    HuaStampBlocks.set(level, pos.set(x, top + 1, z), Blocks.SPRUCE_FENCE.defaultBlockState(), 2);
                    HuaStampBlocks.set(level, pos.set(x, top + 2, z), Blocks.SPRUCE_FENCE.defaultBlockState(), 2);
                }
                mended++;
            }
        }
        return mended;
    }

    /** Top of the procedural mountain at a world column (what the terrain pass writes). */
    static int topOf(MountHuaSite site, MountHuaShape shape, double u, double v) {
        return (int) Math.round(site.worldY(shape.height(u, v)));
    }

    /** True for a block a person walks on (a curb slab, a lantern, a post, a carpet is not a floor of the trail). */
    static boolean isFloor(BlockState st) {
        if (st.isAir() || st.is(net.minecraft.tags.BlockTags.LEAVES) || st.is(net.minecraft.tags.BlockTags.LOGS)
                || st.is(net.minecraft.tags.BlockTags.REPLACEABLE_BY_TREES) || st.getBlock() instanceof net.minecraft.world.level.block.FenceBlock
                || st.getBlock() instanceof net.minecraft.world.level.block.WallBlock
                || st.getBlock() instanceof net.minecraft.world.level.block.ChainBlock) {
            return false;
        }
        var shape = st.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        return !shape.isEmpty() && (st.getBlock() instanceof StairBlock || shape.max(Direction.Axis.Y) >= 0.99);
    }

    /** Column heights as the world holds them after the stamp, and the limited heights of the trail. */
    static final class Columns {
        private final ServerLevelAccessor level;
        private final MinecraftServer server;
        private final MountHuaSite site;
        private final Map<Long, Integer> stamped = new HashMap<>();
        private final Map<Long, Integer> limited = new HashMap<>();
        private final Map<Long, Boolean> onTrail = new HashMap<>();

        Columns(ServerLevelAccessor level, MinecraftServer server, MountHuaSite site) {
            this.level = level;
            this.server = server;
            this.site = site;
        }

        boolean onTrail(int x, int z) {
            return onTrail.computeIfAbsent(BlockPos.asLong(x, 0, z), k -> {
                double[] t = site.shape().trailAt(site.centerU(x, z), site.centerV(x, z));
                return t != null && t[0] <= 2.1;
            });
        }

        /** Highest floor block of the column once the baked cells are stamped over the procedural terrain. */
        int stamped(int x, int z) {
            return stamped.computeIfAbsent(BlockPos.asLong(x, 0, z), k -> {
                double u = site.centerU(x, z);
                double v = site.centerV(x, z);
                int procedural = topOf(site, site.shape(), u, v);
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                for (int y = procedural + SCAN; y >= procedural - SCAN; y--) {
                    HuaBaked.Expected e = HuaBaked.expectedAt(server, site, pos.set(x, y, z));
                    if (e != null) {
                        if (isFloor(e.state())) {
                            return y;
                        }
                    } else if (y <= procedural) {
                        return y;
                    }
                }
                return procedural;
            });
        }

        /**
         * The height the column keeps: lowest of top(d) + steps over the trail columns d that can be walked to along the trail
         * (the walk stays on trail columns, so a gap in the trail does not tie two stretches together).
         */
        int limited(int x, int z) {
            return limited.computeIfAbsent(BlockPos.asLong(x, 0, z), k -> {
                int best = stamped(x, z);
                java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
                java.util.HashSet<Long> seen = new java.util.HashSet<>();
                queue.add(new int[] {x, z, 0});
                seen.add(BlockPos.asLong(x, 0, z));
                int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                while (!queue.isEmpty()) {
                    int[] c = queue.poll();
                    if (c[2] > 0) {
                        best = Math.min(best, stamped(c[0], c[1]) + c[2]);
                    }
                    if (c[2] >= REACH) {
                        continue;
                    }
                    for (int[] d : nb) {
                        int nx = c[0] + d[0];
                        int nz = c[1] + d[1];
                        if (onTrail(nx, nz) && seen.add(BlockPos.asLong(nx, 0, nz))) {
                            queue.add(new int[] {nx, nz, c[2] + 1});
                        }
                    }
                }
                return best;
            });
        }

        int height(int x, int z) {
            return onTrail(x, z) ? limited(x, z) : topOf(site, site.shape(), site.centerU(x, z), site.centerV(x, z));
        }
    }

    /** A stair where the next column up the flight stands exactly one block higher, else a plain block (as the terrain pass lays it). */
    private static BlockState tread(Columns cols, MountHuaSite site, double[] tr, int x, int z, int top) {
        MountHuaShape shape = site.shape();
        int index = (int) tr[2];
        int here = (int) Math.round(site.worldY(shape.trailY(index)));
        int ahead = (int) Math.round(site.worldY(shape.trailY(index + 1)));
        int behind = (int) Math.round(site.worldY(shape.trailY(index - 1)));
        double[] dir = shape.trailDir(index);
        if (ahead > here) {
            BlockState s = stairIfStep(cols, site, x, z, top, dir[0], dir[1]);
            if (s != null) {
                return s;
            }
        }
        if (behind > here) {
            BlockState s = stairIfStep(cols, site, x, z, top, -dir[0], -dir[1]);
            if (s != null) {
                return s;
            }
        }
        return ModBlocks.POLISHED_HUA_GRANITE.get().defaultBlockState();
    }

    private static BlockState stairIfStep(Columns cols, MountHuaSite site, int x, int z, int top, double du, double dv) {
        boolean alongU = Math.abs(du) >= Math.abs(dv);
        int[] wd = site.rotateDir(alongU ? (int) Math.signum(du) : 0, alongU ? 0 : (int) Math.signum(dv));
        if (!MountHuaChunkWriter.isRealStep(top, cols.height(x + wd[0], z + wd[1]))) {
            return null;
        }
        Direction facing = wd[0] > 0 ? Direction.EAST : wd[0] < 0 ? Direction.WEST : wd[1] > 0 ? Direction.SOUTH : Direction.NORTH;
        return ModBlocks.POLISHED_HUA_GRANITE_STAIRS.get().defaultBlockState().setValue(StairBlock.FACING, facing);
    }
}
