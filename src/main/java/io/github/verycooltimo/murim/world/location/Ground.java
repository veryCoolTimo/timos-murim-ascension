package io.github.verycooltimo.murim.world.location;

import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Ground heights for placing a location: from the noise generator while structures are planned
 * ({@link #of(Structure.GenerationContext)}) or from the blocks of a live world ({@link #live(ServerLevel)} —
 * the showcase world and tests, where the terrain may already be the Mount Hua feature's, which the noise
 * generator does not know about).
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/chunk/ChunkGenerator.java#getBaseHeight,
 * reference/minecraft-src/net/minecraft/world/level/Level.java#getHeight.
 */
public interface Ground {

    /** y of the top solid block of the column (lake and river beds count; see {@link #dry}). */
    int floor(int x, int z);

    /** No water over the column. */
    boolean dry(int x, int z);

    static Ground of(Structure.GenerationContext context) {
        return new Ground() {
            @Override
            public int floor(int x, int z) {
                return context.chunkGenerator().getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, context.heightAccessor(),
                        context.randomState()) - 1;
            }

            @Override
            public boolean dry(int x, int z) {
                int surface = context.chunkGenerator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG,
                        context.heightAccessor(), context.randomState());
                return surface == floor(x, z) + 1;
            }
        };
    }

    static Ground live(ServerLevel level) {
        return new Ground() {
            @Override
            public int floor(int x, int z) {
                level.getChunk(x >> 4, z >> 4);
                BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x,
                        level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
                while (m.getY() > level.getMinBuildHeight()) {
                    var s = level.getBlockState(m);
                    if (!s.isAir() && !s.is(Blocks.BARRIER) && s.getFluidState().isEmpty() && !s.canBeReplaced()
                            && !s.is(net.minecraft.tags.BlockTags.LOGS)) {
                        break;
                    }
                    m.move(0, -1, 0);
                }
                return m.getY();
            }

            @Override
            public boolean dry(int x, int z) {
                level.getChunk(x >> 4, z >> 4);
                return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) == level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
            }
        };
    }

    /** Where a captured location goes: origin and rotation of the templates. */
    record Placement(BlockPos origin, Rotation rotation) {
    }

    /**
     * Places {@code loc} with its anchor over (ax, az), turned by {@code rotation}; the captured ground level lands on
     * the median ground under nine points of the turned footprint.
     */
    static Placement place(CapturedLocation loc, int ax, int az, Rotation rotation, Ground ground) {
        BlockPos o = CapturedLocation.originFor(new BlockPos(ax, 0, az), rotation, new BlockPos(loc.anchor().getX(), 0, loc.anchor().getZ()));
        int sx = loc.size().getX() - 1;
        int sz = loc.size().getZ() - 1;
        int[][] pts = {{0, 0}, {sx, 0}, {0, sz}, {sx, sz}, {sx / 2, sz / 2}, {sx / 2, 0}, {sx / 2, sz}, {0, sz / 2}, {sx, sz / 2}};
        int[] h = new int[pts.length];
        for (int i = 0; i < pts.length; i++) {
            BlockPos w = CapturedLocation.world(o, rotation, new BlockPos(pts[i][0], 0, pts[i][1]));
            h[i] = ground.floor(w.getX(), w.getZ());
        }
        Arrays.sort(h);
        int g = h[h.length / 2];
        return new Placement(new BlockPos(o.getX(), g - loc.groundY(), o.getZ()), rotation);
    }
}
