package io.github.verycooltimo.murim.world.hua;

import io.github.verycooltimo.murim.registry.ModBlocks;
import io.github.verycooltimo.murim.world.herb.HerbPlantBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** Three thousand-year plants on Mount Hua and one black reishi in the east cliff niche.
 * Written only by the owning chunk's final generation pass; harvesting never schedules replacement.
 */
public final class HuaHerbs {
    private static final String[] PADS = {"pav_east", "pav_west", "pav_south"};
    private static final int[][] CORNER = {{1, -1}, {-1, 1}, {1, 1}};

    private HuaHerbs() {
    }

    /** A planned slope plant: its world column and nearby pavilion height. */
    public record Spot(int x, int z, double nominalY) {
    }

    /** The plants of the mountain, in world columns. */
    public static List<Spot> spots(MountHuaSite site) {
        List<Spot> out = new ArrayList<>();
        for (int i = 0; i < PADS.length; i++) {
            for (MountHuaPlan.Zone z : MountHuaPlan.ZONES) {
                if (!PADS[i].equals(z.id())) continue;
                double u = z.u() + CORNER[i][0] * (z.width() / 2.0D + 3.0D);
                double v = z.v() + CORNER[i][1] * (z.depth() / 2.0D + 3.0D);
                int[] w = site.toWorld(u, v);
                out.add(new Spot(w[0], w[1], z.y()));
            }
        }
        return out;
    }

    /**
     * Deterministic niche column on the outer face of the east summit pad; Y is only an envelope hint.
     * Placement and inspection must use the level-aware overload below.
     * Pure site geometry: selection is independent of chunk order and consumes no random stream.
     * The mountain's seed and rotation select the wall; only the owning chunk writes the plant.
     */
    public static BlockPos blackLingzhiSpot(MountHuaSite site) {
        // East pad is centred at (110,-10), 18 by 16. Its outward cliff faces away from the paths.
        // Search the natural cliff outside the pad, selecting the steepest east-facing cell.
        int bestU = 120;
        int bestV = -10;
        double bestDrop = -Double.MAX_VALUE;
        for (int u = 120; u <= 136; u++) {
            for (int v = -24; v <= 4; v++) {
                int[] inside = site.toWorld(u, v);
                int[] outside = site.toWorld(u + 1, v);
                double drop = top(site, inside) - top(site, outside);
                if (drop > bestDrop) {
                    bestDrop = drop;
                    bestU = u;
                    bestV = v;
                }
            }
        }
        int[] w = site.toWorld(bestU, bestV);
        int y = top(site, w) - 1;
        return new BlockPos(w[0], y, w[1]);
    }

    private static int top(MountHuaSite site, int[] w) {
        // Same centre sampling and rounding as MountHuaChunkWriter's terrain envelope.
        return (int) Math.round(site.worldY(site.shape().height(site.centerU(w[0], w[1]), site.centerV(w[0], w[1]))));
    }

    /** Actual blocks, never the envelope or a possibly stale worldgen heightmap.
     * API: existing placement uses LevelAccessor#getBlockState and BlockState#isFaceSturdy.
     */
    public static int realTop(net.minecraft.world.level.LevelAccessor level, int x, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = level.getMaxBuildHeight() - 1; y >= level.getMinBuildHeight(); y--) {
            pos.set(x, y, z);
            if (level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP)) return y;
        }
        throw new IllegalStateException("Mount Hua niche column has no solid surface: " + x + ", " + z);
    }

    public static BlockPos blackLingzhiSpot(net.minecraft.world.level.LevelAccessor level, MountHuaSite site) {
        BlockPos column = blackLingzhiSpot(site);
        return new BlockPos(column.getX(), realTop(level, column.getX(), column.getZ()) - 1, column.getZ());
    }

    public static Direction dropDirection(MountHuaSite site) {
        return switch (site.rotation()) {
            case 1 -> Direction.SOUTH;
            case 2 -> Direction.WEST;
            case 3 -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }

    /** Neighbour stamps must not erase an already completed treasure or its rock shell. */
    static boolean protects(net.minecraft.world.level.LevelAccessor level, BlockPos pos) {
        for (int dy = -1; dy <= 1; dy++) {
            BlockPos at = pos.offset(0, dy, 0);
            BlockState state = level.getBlockState(at);
            if (state.is(ModBlocks.BLACK_LINGZHI_PLANT.get())
                    || state.is(ModBlocks.THOUSAND_GINSENG_PLANT.get())
                    || state.is(ModBlocks.THOUSAND_LINGZHI_PLANT.get())) return true;
        }
        return false;
    }

    public static void apply(WorldGenLevel level, ChunkPos cp, MountHuaSite site) {
        int[] a = site.toWorld(120, -24);
        int[] b = site.toWorld(136, 4);
        boolean nearCliff = cp.getMaxBlockX() >= Math.min(a[0], b[0]) && cp.getMinBlockX() <= Math.max(a[0], b[0])
                && cp.getMaxBlockZ() >= Math.min(a[1], b[1]) && cp.getMinBlockZ() <= Math.max(a[1], b[1]);
        BlockPos column = nearCliff ? blackLingzhiSpot(site) : null;
        if (column != null && new ChunkPos(column).equals(cp)) {
            BlockPos niche = blackLingzhiSpot(level, site);
            // Owner only, at the end of cleanup. Keep the original recessed rock shell.
            HuaStampBlocks.set(level, niche.above(), Blocks.STONE.defaultBlockState(), 2);
            HuaStampBlocks.set(level, niche.below(), Blocks.STONE.defaultBlockState(), 2);
            HuaStampBlocks.set(level, niche,
                    ModBlocks.BLACK_LINGZHI_PLANT.get().defaultBlockState(), 2);
        }
        List<Spot> plants = spots(site);
        for (int i = 0; i < plants.size(); i++) {
            Spot s = plants.get(i);
            if (s.x() >= cp.getMinBlockX() && s.x() <= cp.getMaxBlockX() && s.z() >= cp.getMinBlockZ() && s.z() <= cp.getMaxBlockZ()) {
                placePlant(level, s.x(), s.z(), (int) Math.round(site.worldY(s.nominalY())), i == 2);
            }
        }
    }

    /** Search the exposed slope just outside each pad, staying inside the owning chunk.
     * Two ginseng and one floor lingzhi; a small soil pocket roots them in the rocky mountain.
     */
    public static boolean placePlant(WorldGenLevel level, int x, int z, int padY, boolean lingzhi) {
        ChunkPos owner = new ChunkPos(new BlockPos(x, padY, z));
        for (int radius = 0; radius <= 15; radius++) {
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                int px = x + dx, pz = z + dz;
                if (px < owner.getMinBlockX() || px > owner.getMaxBlockX()
                        || pz < owner.getMinBlockZ() || pz > owner.getMaxBlockZ()) continue;
                for (int y = level.getMaxBuildHeight() - 2; y >= level.getMinBuildHeight(); y--) {
                    BlockPos base = new BlockPos(px, y, pz);
                    BlockState floor = level.getBlockState(base);
                    if (!floor.isFaceSturdy(level, base, Direction.UP)) continue;
                    BlockPos at = base.above();
                    if (!level.getBlockState(at).isAir() || !level.getBlockState(at.above()).isAir()) break;
                    if (!HerbPlantBlock.soil(floor)
                            && !floor.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD)
                            && !floor.is(ModBlocks.HUA_GRANITE.get())
                            && !floor.is(ModBlocks.HUA_GRANITE_PALE.get())
                            && !floor.is(ModBlocks.HUA_GRANITE_STAINED.get())
                            && !floor.is(ModBlocks.HUA_GRANITE_CRACKED.get())
                            && !floor.is(ModBlocks.HUA_GRANITE_DARK.get())
                            && !floor.is(ModBlocks.HUA_GRANITE_MOSSY.get())
                            && !floor.is(ModBlocks.HUA_LITTER.get())) break;
                    if (!HerbPlantBlock.soil(floor))
                        level.setBlock(base, (lingzhi ? Blocks.PODZOL : Blocks.GRASS_BLOCK).defaultBlockState(), 2);
                    BlockState plant = (lingzhi
                            ? ModBlocks.THOUSAND_LINGZHI_PLANT.get()
                            : ModBlocks.THOUSAND_GINSENG_PLANT.get()).defaultBlockState();
                    level.setBlock(at, plant, 2);
                    return true;
                }
            }
        }
        return false;
    }
}
