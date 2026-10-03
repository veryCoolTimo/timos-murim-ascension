package io.github.verycooltimo.murim.world.hua;

import io.github.verycooltimo.murim.world.hua.MountHuaPlan.Zone;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SnowyDirtBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

/**
 * Writes one chunk of Mount Hua. Called from {@link MountHuaFeature} during the first decoration
 * step ({@code raw_generation}) of every overworld chunk that touches the mountain.
 *
 * <p>Order inside the chunk: heights for a 22×22 grid (chunk + 3-block border, for slopes and
 * ledges) → rock columns written straight into the proto-chunk (bulk, chunk-local) → surface skin
 * → decorations through the {@link WorldGenLevel} (trail, terrace markers, caves, pines, plums,
 * waterfalls; trees may spill one chunk over, inside the 3×3 write area of FEATURES) → heightmaps
 * re-primed so later vanilla features (trees, grass, ores) see the new ground.
 *
 * <p>Every decision is a pure function of (seed, site, x, z): chunks can be generated in any order.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/chunk/ProtoChunk.java#setBlockState,
 * reference/minecraft-src/net/minecraft/world/level/levelgen/Heightmap.java#primeHeightmaps,
 * reference/minecraft-src/net/minecraft/world/level/chunk/ChunkAccess.java#setBlockEntityNbt,
 * reference/minecraft-src/net/minecraft/world/level/LevelAccessor.java#scheduleTick
 */
final class MountHuaChunkWriter {

    private static final int B = 3;
    private static final int N = 16 + 2 * B;

    /** World y above which flat ground gets snow (author: «снег на вершинах выше ~260»). */
    private static final int SNOW_Y = 262;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();

    private final WorldGenLevel level;
    private final ChunkAccess chunk;
    private final MountHuaSite site;
    private final MountHuaShape shape;
    private final HuaNoise noise;
    private final long seed;
    private final int x0;
    private final int z0;
    private final int maxY;

    /** Final top y per grid cell (cell (i, j) = world (x0 - B + i, z0 - B + j)). */
    private final int[] top = new int[N * N];
    private final double[] weight = new double[N * N];
    /** Original top solid y (in-chunk cells only). */
    private final int[] orig = new int[256];
    private final BlockState[] origTop = new BlockState[256];

    MountHuaChunkWriter(WorldGenLevel level, ChunkAccess chunk, MountHuaSite site) {
        this.level = level;
        this.chunk = chunk;
        this.site = site;
        this.shape = site.shape();
        this.seed = level.getSeed();
        this.noise = new HuaNoise(seed ^ 0x4875615368616EL);
        this.x0 = chunk.getPos().getMinBlockX();
        this.z0 = chunk.getPos().getMinBlockZ();
        this.maxY = chunk.getMaxBuildHeight() - 14;
    }

    private static int idx(int i, int j) {
        return j * N + i;
    }

    private int topAt(int lx, int lz) {
        return top[idx(lx + B, lz + B)];
    }

    /** Returns false if nothing of the mountain falls into this chunk. */
    boolean write() {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int floor = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, lx, lz) - 1;
                orig[lz * 16 + lx] = floor;
                origTop[lz * 16 + lx] = chunk.getBlockState(pos.set(x0 + lx, floor, z0 + lz));
            }
        }
        boolean any = false;
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                int wx = x0 - B + i;
                int wz = z0 - B + j;
                double u = site.localU(wx + 0.5, wz + 0.5);
                double v = site.localV(wx + 0.5, wz + 0.5);
                double w = shape.blend(u, v);
                int lx = Math.max(0, Math.min(15, i - B));
                int lz = Math.max(0, Math.min(15, j - B));
                int o = orig[lz * 16 + lx];
                weight[idx(i, j)] = w;
                if (w <= 0) {
                    top[idx(i, j)] = o;
                    continue;
                }
                double mount = site.worldY(shape.height(u, v));
                int t = (int) Math.round(o + w * (mount - o));
                top[idx(i, j)] = Math.min(maxY, t);
                if (i >= B && i < B + 16 && j >= B && j < B + 16) {
                    any = true;
                }
            }
        }
        if (!any) {
            return false;
        }
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                if (weight[idx(lx + B, lz + B)] > 0) {
                    column(lx, lz, pos);
                }
            }
        }
        Heightmap.primeHeightmaps(chunk, EnumSet.allOf(Heightmap.Types.class));
        decorate(pos);
        Heightmap.primeHeightmaps(chunk, EnumSet.allOf(Heightmap.Types.class));
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // Rock columns

    private void column(int lx, int lz, BlockPos.MutableBlockPos pos) {
        int wx = x0 + lx;
        int wz = z0 + lz;
        int t = topAt(lx, lz);
        int o = orig[lz * 16 + lx];
        double w = weight[idx(lx + B, lz + B)];
        int lowest = t;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                lowest = Math.min(lowest, topAt(lx + dx, lz + dz));
            }
        }
        int drop = t - lowest;
        // Cut: remove what stands above the new top (vanilla hills inside gorges/terraces).
        int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, lx, lz) - 1;
        int sea = level.getSeaLevel();
        for (int y = surface; y > t; y--) {
            pos.set(wx, y, wz);
            if (y < sea && !chunk.getBlockState(pos).getFluidState().isEmpty()) {
                continue;
            }
            chunk.setBlockState(pos, AIR, false);
        }
        // Fill: rock from just below the old surface (buries grass/sand) up to the new top.
        int from = Math.min(o - 2, t - 4);
        boolean masonry = retainingWall(wx, wz, t);
        for (int y = from; y <= t; y++) {
            pos.set(wx, y, wz);
            boolean exposed = y > lowest - 2 || t - y < 4;
            chunk.setBlockState(pos, !exposed ? STONE : masonry ? masonry(wx, y, wz) : rock(wx, y, wz), false);
        }
        skin(lx, lz, t, drop, w, pos);
    }

    /**
     * True for the filled buttress right outside a terrace: its face is built as a dressed stone
     * retaining wall (temple platforms on Huashan stand on masonry), not as natural rock.
     */
    private boolean retainingWall(int wx, int wz, int t) {
        double u = site.localU(wx + 0.5, wz + 0.5);
        double v = site.localV(wx + 0.5, wz + 0.5);
        for (Zone z : MountHuaPlan.ZONES) {
            if (z.cave()) {
                continue;
            }
            double dx = Math.max(0, Math.abs(u - z.u()) - z.width() / 2.0);
            double dz = Math.max(0, Math.abs(v - z.v()) - z.depth() / 2.0);
            double d = Math.hypot(dx, dz);
            if (d > 0 && d <= 14 && t <= Math.round(site.worldY(z.y()))) {
                return true;
            }
        }
        return false;
    }

    private BlockState masonry(int x, int y, int z) {
        long h = mix(x * 31 + y, z, 13);
        int r = (int) (h & 15);
        if (r < 2) {
            return Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
        }
        if (r < 4) {
            return Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
        }
        return Blocks.STONE_BRICKS.defaultBlockState();
    }

    /**
     * Granite palette (DESCRIPTIONS.md «Общая палитра гранита»): pale cream-grey (diorite,
     * calcite) with grey patches (andesite, stone), a pink tint (granite), and vertical dark
     * streaks (tuff) that run straight down the walls — a 2D noise in (x, z) is constant along y.
     */
    private BlockState rock(int x, int y, int z) {
        // Dark streaks: thin vertical lines (2D noise, constant along y) that run for tens of blocks.
        double streak = noise.noise(x / 1.7, z / 1.7, 51.0);
        if (streak > 0.6 && noise.noise(x / 7.0, y / 40.0, z / 7.0) > 0.0) {
            return Blocks.TUFF.defaultBlockState();
        }
        // Vertical joints: narrow andesite lines splitting the wall into columns.
        double joint = Math.abs(noise.noise(x / 9.0, z / 9.0, 77.0));
        if (joint < 0.03) {
            return Blocks.ANDESITE.defaultBlockState();
        }
        // Large coherent patches (tens of blocks), slightly stretched vertically; tiny dither so the
        // patch borders are not contour lines.
        double b = noise.noise(x / 22.0, y / 30.0, z / 22.0) + 0.12 * noise.noise(x / 2.5, y / 2.5, z / 2.5);
        if (b > 0.28) {
            return Blocks.CALCITE.defaultBlockState();
        }
        if (b > -0.22) {
            return Blocks.DIORITE.defaultBlockState();
        }
        if (b > -0.42) {
            return Blocks.ANDESITE.defaultBlockState();
        }
        double warm = noise.noise(x / 40.0, y / 40.0, z / 40.0 + 9.0);
        return warm > 0.55 ? Blocks.GRANITE.defaultBlockState() : STONE;
    }

    /** Surface: «flat is green, steep is bare» (DESCRIPTIONS.md p.8), snow on high flats. */
    private void skin(int lx, int lz, int t, int drop, double w, BlockPos.MutableBlockPos pos) {
        int wx = x0 + lx;
        int wz = z0 + lz;
        BlockState was = origTop[lz * 16 + lx];
        boolean soilWas = was.is(Blocks.GRASS_BLOCK) || was.is(Blocks.DIRT) || was.is(Blocks.PODZOL)
                || was.is(Blocks.SAND) || was.is(Blocks.RED_SAND) || was.is(Blocks.COARSE_DIRT)
                || was.is(Blocks.MYCELIUM) || was.is(Blocks.SNOW_BLOCK) || was.is(Blocks.MUD);
        if (drop <= 2 && w < 0.999 && soilWas) {
            // Apron: keep the biome's own ground (sand stays sand, grass stays grass).
            BlockState under = was.is(Blocks.SAND) ? Blocks.SANDSTONE.defaultBlockState()
                    : was.is(Blocks.RED_SAND) ? Blocks.RED_SANDSTONE.defaultBlockState() : DIRT;
            chunk.setBlockState(pos.set(wx, t, wz), was, false);
            chunk.setBlockState(pos.set(wx, t - 1, wz), under, false);
            chunk.setBlockState(pos.set(wx, t - 2, wz), under, false);
            return;
        }
        double n = noise.noise(wx / 7.0, wz / 7.0, 91.0);
        if (drop <= 1 || (drop == 2 && n > 0.1)) {
            BlockState ground;
            if (t >= SNOW_Y + (int) (6 * n)) {
                ground = GRASS.setValue(SnowyDirtBlock.SNOWY, true);
            } else if (n > 0.45) {
                ground = Blocks.PODZOL.defaultBlockState();
            } else if (n < -0.5) {
                ground = Blocks.COARSE_DIRT.defaultBlockState();
            } else if (n < -0.35 && t > site.baseY() + 40) {
                ground = Blocks.MOSS_BLOCK.defaultBlockState();
            } else {
                ground = GRASS;
            }
            chunk.setBlockState(pos.set(wx, t, wz), ground, false);
            chunk.setBlockState(pos.set(wx, t - 1, wz), DIRT, false);
            if (drop <= 1) {
                chunk.setBlockState(pos.set(wx, t - 2, wz), DIRT, false);
            }
            if (ground.is(Blocks.GRASS_BLOCK) && ground.getValue(SnowyDirtBlock.SNOWY)) {
                int layers = t >= SNOW_Y + 22 ? 2 : 1;
                chunk.setBlockState(pos.set(wx, t + 1, wz),
                        Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers), false);
            }
        } else if (drop <= 3 && n > 0.35 && t < SNOW_Y) {
            chunk.setBlockState(pos.set(wx, t, wz), Blocks.MOSS_BLOCK.defaultBlockState(), false);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Decorations

    private void decorate(BlockPos.MutableBlockPos pos) {
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                if (weight[idx(lx + B, lz + B)] > 0) {
                    trailColumn(lx, lz, pos);
                }
            }
        }
        for (Zone zone : MountHuaPlan.ZONES) {
            if (zone.cave()) {
                cave(zone, pos);
            } else {
                terraceMarkers(zone, pos);
            }
        }
        vegetation(pos);
        waterfalls(pos);
    }

    private void trailColumn(int lx, int lz, BlockPos.MutableBlockPos pos) {
        int wx = x0 + lx;
        int wz = z0 + lz;
        double u = site.localU(wx + 0.5, wz + 0.5);
        double v = site.localV(wx + 0.5, wz + 0.5);
        double[] tr = shape.trailAt(u, v);
        if (tr == null || tr[0] > 1.6) {
            return;
        }
        int index = (int) tr[2];
        int t = topAt(lx, lz);
        if (tr[0] <= 1.6) {
            int here = (int) Math.round(site.worldY(shape.trailY(index)));
            int ahead = (int) Math.round(site.worldY(shape.trailY(index + 1)));
            int behind = (int) Math.round(site.worldY(shape.trailY(index - 1)));
            double[] dir = shape.trailDir(index);
            BlockState block;
            if (ahead > here) {
                block = stairs(dir[0], dir[1]);
            } else if (behind > here) {
                block = stairs(-dir[0], -dir[1]);
            } else {
                long h = mix(wx, wz, 3);
                block = (h & 7) == 0 ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState()
                        : (h & 7) == 1 ? Blocks.CRACKED_STONE_BRICKS.defaultBlockState()
                        : Blocks.STONE_BRICKS.defaultBlockState();
            }
            level.setBlock(pos.set(wx, t, wz), block, 2);
            for (int y = t + 1; y <= t + 4; y++) {
                level.setBlock(pos.set(wx, y, wz), AIR, 2);
            }
            if (tr[0] > 0.9 && t - lowestAround(lx, lz) >= 4) {
                // Outer column over a drop: Huashan's iron chain railing on stone posts.
                if (index % 4 == 0) {
                    level.setBlock(pos.set(wx, t + 1, wz), Blocks.STONE_BRICK_WALL.defaultBlockState(), 2);
                } else {
                    int[] wd = site.rotateDir((int) Math.signum(Math.round(dir[0])), (int) Math.signum(Math.round(dir[1])));
                    Direction.Axis axis = Math.abs(wd[0]) >= Math.abs(wd[1]) ? Direction.Axis.X : Direction.Axis.Z;
                    level.setBlock(pos.set(wx, t + 1, wz),
                            Blocks.CHAIN.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis), 2);
                }
            }
            return;
        }
    }

    private int lowestAround(int lx, int lz) {
        int lowest = Integer.MAX_VALUE;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                lowest = Math.min(lowest, topAt(lx + dx, lz + dz));
            }
        }
        return lowest;
    }

    private BlockState stairs(double du, double dv) {
        int[] wd = site.rotateDir(Math.abs(du) >= Math.abs(dv) ? (int) Math.signum(du) : 0,
                Math.abs(du) >= Math.abs(dv) ? 0 : (int) Math.signum(dv));
        Direction facing = wd[0] > 0 ? Direction.EAST : wd[0] < 0 ? Direction.WEST
                : wd[1] > 0 ? Direction.SOUTH : Direction.NORTH;
        return Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing);
    }

    /** World-space rectangle of a zone: {minX, minZ, maxX, maxZ}. */
    private int[] zoneRect(Zone z) {
        int[] a = site.toWorld(z.u() - z.width() / 2.0, z.v() - z.depth() / 2.0);
        int[] b = site.toWorld(z.u() + z.width() / 2.0 - 1, z.v() + z.depth() / 2.0 - 1);
        return new int[] {Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1])};
    }

    private boolean inChunk(int x, int z) {
        return x >= x0 && x < x0 + 16 && z >= z0 && z < z0 + 16;
    }

    /**
     * Placeholder markers for the author's buildings: a polished andesite border on the terrace,
     * a lantern post on every corner, a sign with the name and size on the first corner.
     */
    private void terraceMarkers(Zone z, BlockPos.MutableBlockPos pos) {
        int[] r = zoneRect(z);
        if (r[2] < x0 || r[0] > x0 + 15 || r[3] < z0 || r[1] > z0 + 15) {
            return;
        }
        int y = (int) Math.round(site.worldY(z.y()));
        for (int x = Math.max(r[0], x0); x <= Math.min(r[2], x0 + 15); x++) {
            for (int zz = Math.max(r[1], z0); zz <= Math.min(r[3], z0 + 15); zz++) {
                boolean edge = x == r[0] || x == r[2] || zz == r[1] || zz == r[3];
                if (edge) {
                    level.setBlock(pos.set(x, y, zz), Blocks.POLISHED_ANDESITE.defaultBlockState(), 2);
                }
            }
        }
        int[][] corners = {{r[0], r[1]}, {r[2], r[1]}, {r[0], r[3]}, {r[2], r[3]}};
        for (int c = 0; c < 4; c++) {
            int cx = corners[c][0];
            int cz = corners[c][1];
            if (!inChunk(cx, cz)) {
                continue;
            }
            for (int k = 1; k <= 3; k++) {
                level.setBlock(pos.set(cx, y + k, cz), Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState(), 2);
            }
            level.setBlock(pos.set(cx, y + 4, cz), Blocks.LANTERN.defaultBlockState(), 2);
            if (c == 0) {
                sign(cx + 1, y + 1, cz + 1, z, pos);
            }
        }
    }

    private void sign(int x, int y, int z, Zone zone, BlockPos.MutableBlockPos pos) {
        if (!inChunk(x, z)) {
            return;
        }
        BlockPos at = new BlockPos(x, y, z);
        level.setBlock(at, Blocks.SPRUCE_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 0), 2);
        // SignBlockEntity#setText would mark the block for a client update (needs a live Level);
        // during generation the text goes in as pending block-entity NBT, loaded with the chunk.
        SignText text = new SignText()
                .setMessage(0, Component.translatable("sign.murim.hua." + zone.id()))
                .setMessage(1, Component.literal(zone.width() + " x " + zone.depth()))
                .setMessage(2, Component.translatable("sign.murim.hua.placeholder"));
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:sign");
        tag.putInt("x", x);
        tag.putInt("y", y);
        tag.putInt("z", z);
        SignText.DIRECT_CODEC.encodeStart(NbtOps.INSTANCE, text).result().ifPresent(t -> tag.put("front_text", t));
        chunk.setBlockEntityNbt(tag);
    }

    /** Room carved in the rock at the zone, with a tunnel to (exitU, exitV); 5 blocks high. */
    private void cave(Zone z, BlockPos.MutableBlockPos pos) {
        int y = (int) Math.round(site.worldY(z.y()));
        int[] r = zoneRect(z);
        for (int x = Math.max(r[0], x0); x <= Math.min(r[2], x0 + 15); x++) {
            for (int zz = Math.max(r[1], z0); zz <= Math.min(r[3], z0 + 15); zz++) {
                carve(x, y, zz, 5, pos);
                boolean edge = x == r[0] || x == r[2] || zz == r[1] || zz == r[3];
                level.setBlock(pos.set(x, y, zz), edge ? Blocks.POLISHED_ANDESITE.defaultBlockState()
                        : Blocks.STONE_BRICKS.defaultBlockState(), 2);
            }
        }
        // Tunnel: 3 wide, 4 high, straight from the room centre to the exit point.
        double su = z.u();
        double sv = z.v();
        double len = Math.hypot(z.exitU() - su, z.exitV() - sv);
        for (double s = 0; s <= len; s += 0.5) {
            double u = su + (z.exitU() - su) * s / len;
            double v = sv + (z.exitV() - sv) * s / len;
            for (int k = -1; k <= 1; k++) {
                double pu = u + k * (z.exitV() - sv) / len;
                double pv = v - k * (z.exitU() - su) / len;
                int[] wxz = site.toWorld(pu, pv);
                if (inChunk(wxz[0], wxz[1])) {
                    carve(wxz[0], y, wxz[1], 4, pos);
                    level.setBlock(pos.set(wxz[0], y, wxz[1]), Blocks.STONE_BRICKS.defaultBlockState(), 2);
                }
            }
        }
        if (z.id().equals("penance") || z.id().equals("vault")) {
            int[] c = site.toWorld(z.u(), z.v());
            if (inChunk(c[0], c[1])) {
                level.setBlock(pos.set(c[0], y + 1, c[1]), Blocks.LANTERN.defaultBlockState(), 2);
                sign(c[0] + 1, y + 1, c[1], z, pos);
            }
        }
    }

    private void carve(int x, int y, int z, int height, BlockPos.MutableBlockPos pos) {
        for (int k = 1; k <= height; k++) {
            level.setBlock(pos.set(x, y + k, z), AIR, 2);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Pines, plums, waterfalls

    private void vegetation(BlockPos.MutableBlockPos pos) {
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                double w = weight[idx(lx + B, lz + B)];
                if (w < 0.6) {
                    continue;
                }
                int wx = x0 + lx;
                int wz = z0 + lz;
                int t = topAt(lx, lz);
                if (t < site.baseY() + 12 || t > 296) {
                    continue;
                }
                BlockState ground = chunk.getBlockState(pos.set(wx, t, wz));
                double u = site.localU(wx + 0.5, wz + 0.5);
                double v = site.localV(wx + 0.5, wz + 0.5);
                Zone zone = zoneAt(u, v, 4);
                if (zone != null) {
                    if (zone.id().equals("grove") && zoneAt(u, v, -3) == zone) {
                        long h = mix(wx, wz, 11);
                        if (h % 37 == 0) {
                            plum(wx, t + 1, wz, h, pos);
                        }
                    }
                    continue;
                }
                double[] tr = shape.trailAt(u, v);
                if (tr != null && tr[0] < 4) {
                    continue;
                }
                int drop = 0;
                int deep = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        drop = Math.max(drop, t - topAt(lx + dx, lz + dz));
                    }
                }
                for (int dz = -3; dz <= 3; dz += 3) {
                    for (int dx = -3; dx <= 3; dx += 3) {
                        deep = Math.max(deep, t - topAt(lx + dx, lz + dz));
                    }
                }
                boolean soil = ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.PODZOL)
                        || ground.is(Blocks.MOSS_BLOCK) || ground.is(Blocks.COARSE_DIRT);
                long h = mix(wx, wz, 7);
                int roll = (int) Math.floorMod(h, 1000L);
                // Pines on ledges and rims (a big drop right next to a flat spot), fewer inland.
                int chance = drop <= 2 && deep >= 7 ? 55 : (soil && drop <= 2 ? 16 : (drop <= 4 ? 4 : 0));
                if (roll < chance) {
                    pine(wx, t + 1, wz, h, lx, lz, pos);
                }
            }
        }
    }

    private Zone zoneAt(double u, double v, double margin) {
        for (Zone z : MountHuaPlan.ZONES) {
            if (Math.abs(u - z.u()) <= z.width() / 2.0 + margin && Math.abs(v - z.v()) <= z.depth() / 2.0 + margin) {
                return z;
            }
        }
        return null;
    }

    /**
     * Huashan pine (Pinus armandii on the cliffs): a bent trunk leaning over the drop, and flat
     * horizontal pads of needles in two or three tiers with a flat crown — the umbrella silhouette
     * from the reference photos (05, 04).
     */
    private void pine(int x, int y, int z, long h, int lx, int lz, BlockPos.MutableBlockPos pos) {
        int height = 5 + (int) Math.floorMod(h >>> 8, 6L);
        // Lean towards the lowest neighbour (over the edge).
        int bestDx = 0;
        int bestDz = 0;
        int lowest = Integer.MAX_VALUE;
        for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int tt = topAt(lx + d[0] * 2, lz + d[1] * 2);
            if (tt < lowest) {
                lowest = tt;
                bestDx = d[0];
                bestDz = d[1];
            }
        }
        int cx = x;
        int cz = z;
        BlockState log = Blocks.SPRUCE_LOG.defaultBlockState();
        for (int k = 0; k < height; k++) {
            if (k == height / 2 && ((h >>> 20) & 1) == 0) {
                cx += bestDx;
                cz += bestDz;
            }
            if (!level.getBlockState(pos.set(cx, y + k, cz)).isAir()) {
                return;
            }
            level.setBlock(pos, log, 2);
        }
        BlockState leaves = Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        int tiers = 2 + (int) Math.floorMod(h >>> 24, 2L);
        for (int tier = 0; tier < tiers; tier++) {
            int py = y + height - 1 - tier * 2;
            int radius = tier == 0 ? 2 : 3;
            int ox = cx + (tier == 0 ? 0 : (int) Math.floorMod(h >>> (28 + tier), 3L) - 1);
            int oz = cz + (tier == 0 ? 0 : (int) Math.floorMod(h >>> (32 + tier), 3L) - 1);
            if (tier > 0) {
                // A bare branch out to the pad.
                level.setBlock(pos.set(ox, py, oz), log, 2);
            }
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int d2 = dx * dx + dz * dz;
                    if (d2 > radius * radius + 1 || (d2 == radius * radius + 1 && ((h >>> (dx + 3 + dz * 7)) & 1) == 0)) {
                        continue;
                    }
                    placeLeaf(ox + dx, py + 1, oz + dz, leaves, pos);
                    if (d2 <= 2 && tier == 0) {
                        placeLeaf(ox + dx, py + 2, oz + dz, leaves, pos);
                    }
                }
            }
        }
    }

    private void placeLeaf(int x, int y, int z, BlockState leaves, BlockPos.MutableBlockPos pos) {
        if (level.getBlockState(pos.set(x, y, z)).isAir()) {
            level.setBlock(pos, leaves, 2);
        }
    }

    /** Small blossoming plum (cherry log and blossom leaves) for the grove. */
    private void plum(int x, int y, int z, long h, BlockPos.MutableBlockPos pos) {
        BlockState log = Blocks.CHERRY_LOG.defaultBlockState();
        BlockState leaves = Blocks.CHERRY_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        int height = 3 + (int) Math.floorMod(h >>> 9, 2L);
        for (int k = 0; k < height; k++) {
            level.setBlock(pos.set(x, y + k, z), log, 2);
        }
        int cy = y + height;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int d2 = dx * dx + dz * dz + dy * dy * 2;
                    if (d2 <= 5 || (d2 <= 7 && ((h >>> ((dx + 2) * 5 + dz + 2)) & 1) == 1)) {
                        placeLeaf(x + dx, cy + dy, z + dz, leaves, pos);
                    }
                }
            }
        }
    }

    /**
     * Waterfalls: for each plan point owned by this chunk, the column with the biggest edge drop
     * nearby gets a spring at its lip; the water falls down the wall (fluid tick scheduled like
     * vanilla springs).
     */
    private void waterfalls(BlockPos.MutableBlockPos pos) {
        for (double[] p : WATERFALLS) {
            int[] w = site.toWorld(p[0], p[1]);
            if (!inChunk(w[0], w[1])) {
                continue;
            }
            int bestDrop = 0;
            int bx = -1;
            int bz = -1;
            int[] bd = null;
            for (int lz = 1; lz < 15; lz++) {
                for (int lx = 1; lx < 15; lx++) {
                    int t = topAt(lx, lz);
                    for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int dropHere = t - topAt(lx + d[0], lz + d[1]);
                        if (dropHere > bestDrop) {
                            bestDrop = dropHere;
                            bx = lx;
                            bz = lz;
                            bd = d;
                        }
                    }
                }
            }
            if (bestDrop < 10 || bd == null) {
                continue;
            }
            int t = topAt(bx, bz);
            int sx = x0 + bx;
            int sz = z0 + bz;
            // Dam the three other sides so the source spills only over the lip.
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                if (d != bd) {
                    level.setBlock(pos.set(sx + d[0], t, sz + d[1]), rock(sx + d[0], t, sz + d[1]), 2);
                    level.setBlock(pos.set(sx + d[0], t + 1, sz + d[1]), rock(sx + d[0], t + 1, sz + d[1]), 2);
                }
            }
            BlockPos src = new BlockPos(sx, t, sz);
            level.setBlock(src, Blocks.WATER.defaultBlockState(), 2);
            level.scheduleTick(src, Fluids.WATER, 0);
        }
    }

    /** Waterfall spots (local u, v): scarp east of the gorge, gorge head, basin walls. */
    private static final double[][] WATERFALLS = {
            {-80, -366}, {-58, -300}, {95, -20}, {-150, 70}, {150, 90}};

    private long mix(int x, int z, int salt) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL) ^ (salt * 0x165667B19E3779F9L);
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return h & Long.MAX_VALUE;
    }
}
