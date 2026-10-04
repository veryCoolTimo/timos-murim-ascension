package io.github.verycooltimo.murim.world.hua;

import io.github.verycooltimo.murim.world.hua.MountHuaPlan.Zone;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import io.github.verycooltimo.murim.registry.ModBlocks;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.state.properties.BambooLeaves;
import net.minecraft.world.level.chunk.LevelChunkSection;
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
        if (!computeGrid(pos)) {
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

    /** Original ground and planned heights for the chunk + border; false if the mountain misses it. */
    private boolean computeGrid(BlockPos.MutableBlockPos pos) {
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
        return any;
    }

    /**
     * Last decoration step ({@code top_layer_modification}), after every vanilla feature:
     * <ul>
     *   <li>lava from vanilla springs and lakes inside the massif is turned back into rock (no
     *       lavafalls on Huashan);</li>
     *   <li>vanilla trees (oak, birch, dark oak…) inside the footprint are removed — the forest
     *       around granite must read Chinese: pines, bamboo, plums (author 03.10);</li>
     *   <li>the freed foothill ground gets our pines, bamboo clumps and a few plum trees.</li>
     * </ul>
     * Our own trees survive: their trunks are spruce/dark-oak WOOD blocks and their foliage is
     * persistent, vanilla trees use logs and non-persistent leaves.
     */
    static void cleanup(WorldGenLevel level, ChunkAccess chunk, MountHuaSite site) {
        MountHuaChunkWriter w = new MountHuaChunkWriter(level, chunk, site);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        if (w.computeGrid(pos)) {
            w.cleanupPass(pos);
        }
    }

    private void cleanupPass(BlockPos.MutableBlockPos pos) {
        int sea = level.getSeaLevel();
        // Lava: only sections whose palette may contain it are scanned.
        LevelChunkSection[] sections = chunk.getSections();
        for (int si = 0; si < sections.length; si++) {
            LevelChunkSection section = sections[si];
            if (section.hasOnlyAir() || !section.maybeHas(st -> st.getFluidState().is(FluidTags.LAVA))) {
                continue;
            }
            int y0 = chunk.getSectionYFromSectionIndex(si) << 4;
            for (int y = 0; y < 16; y++) {
                if (y0 + y <= sea) {
                    continue;
                }
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        if (weight[idx(lx + B, lz + B)] <= 0) {
                            continue;
                        }
                        if (section.getBlockState(lx, y, lz).getFluidState().is(FluidTags.LAVA)) {
                            int wx = x0 + lx;
                            int wz = z0 + lz;
                            chunk.setBlockState(pos.set(wx, y0 + y, wz), rock(wx, y0 + y, wz), false);
                        }
                    }
                }
            }
        }
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                if (weight[idx(lx + B, lz + B)] < 0.95) {
                    continue; // outer ring: vanilla forest stays, the belt fades into it
                }
                int wx = x0 + lx;
                int wz = z0 + lz;
                int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz) - 1;
                int ground = Integer.MIN_VALUE;
                for (int y = surface; y > surface - 40; y--) {
                    BlockState state = chunk.getBlockState(pos.set(wx, y, wz));
                    if (state.isAir()) {
                        continue;
                    }
                    boolean vanillaLeaf = state.getBlock() instanceof LeavesBlock && !state.getValue(LeavesBlock.PERSISTENT);
                    boolean vanillaLog = state.is(BlockTags.LOGS) && !state.is(Blocks.SPRUCE_WOOD)
                            && !state.is(Blocks.DARK_OAK_WOOD);
                    if (vanillaLeaf || vanillaLog || state.is(Blocks.VINE) || state.is(Blocks.BEE_NEST)
                            || state.is(Blocks.RED_MUSHROOM_BLOCK) || state.is(Blocks.BROWN_MUSHROOM_BLOCK)
                            || state.is(Blocks.MUSHROOM_STEM)) {
                        level.setBlock(pos, AIR, 2);
                    } else if (state.blocksMotion()) {
                        ground = y;
                        break;
                    }
                }
                if (ground == Integer.MIN_VALUE || ground > site.baseY() + 90) {
                    continue;
                }
                BlockState g = chunk.getBlockState(pos.set(wx, ground, wz));
                if (!(g.is(Blocks.GRASS_BLOCK) || g.is(Blocks.PODZOL) || g.is(Blocks.DIRT) || g.is(Blocks.COARSE_DIRT))) {
                    continue;
                }
                if (!chunk.getBlockState(pos.set(wx, ground + 1, wz)).isAir()
                        && !chunk.getBlockState(pos).canBeReplaced()) {
                    continue;
                }
                double u = site.localU(wx + 0.5, wz + 0.5);
                double v = site.localV(wx + 0.5, wz + 0.5);
                if (zoneAt(u, v, 12) != null || MountHuaPlan.inPillarBasin(u, v)) {
                    continue;
                }
                double[] tr = shape.trailAt(u, v);
                if (tr != null && tr[0] < 9) {
                    continue; // crowns must not close over the stair
                }
                if (shape.streamDistance(u, v) < 4) {
                    continue;
                }
                long h = mix(wx, wz, 23);
                int roll = (int) Math.floorMod(h, 1000L);
                // Dense pine forest near the massif, thinner and mixed towards the vanilla edge.
                double e = shape.beltDistance(u, v);
                double density = 1.6 - smooth(0.35, 0.9, e);
                int pines = (int) (38 * density);
                int bamboo = pines + (int) (10 * density);
                int plums = bamboo + 5;
                if (roll < pines) {
                    pine(wx, ground + 1, wz, h, lx, lz, pos);
                } else if (roll < bamboo) {
                    bamboo(wx, ground + 1, wz, h, pos);
                } else if (roll < plums) {
                    plumTree(wx, ground + 1, wz, h, pos);
                }
            }
        }
        clearTerraces(pos);
        Heightmap.primeHeightmaps(chunk, EnumSet.allOf(Heightmap.Types.class));
    }

    /**
     * Structures planned on the old ground (a village house stood on the gate terrace, stand 03.10)
     * are removed from the building sites: everything above a terrace that is not ours goes.
     */
    private void clearTerraces(BlockPos.MutableBlockPos pos) {
        // Also the eight neighbours (write radius 1): trees of a neighbour decorated after this
        // chunk's own cleanup spill over the site otherwise (stand 04.10: leaves on the gate yard).
        for (int lz = -16; lz < 32; lz++) {
            for (int lx = -16; lx < 32; lx++) {
                int wx = x0 + lx;
                int wz = z0 + lz;
                double cu = site.localU(wx + 0.5, wz + 0.5);
                double cv = site.localV(wx + 0.5, wz + 0.5);
                Zone z = zoneAt(cu, cv, 1);
                if (z == null) {
                    Zone around = zoneAt(cu, cv, 8);
                    if (around != null && !around.cave()) {
                        // Margin: vanilla trees leaning over the site go, our plums and pines stay.
                        int ay = (int) Math.round(site.worldY(around.y()));
                        for (int y = ay + 1; y <= ay + 30; y++) {
                            BlockState st = level.getBlockState(pos.set(wx, y, wz));
                            if ((st.getBlock() instanceof LeavesBlock && !st.getValue(LeavesBlock.PERSISTENT))
                                    || (st.is(BlockTags.LOGS) && !st.is(Blocks.DARK_OAK_WOOD) && !st.is(Blocks.SPRUCE_WOOD))) {
                                level.setBlock(pos, AIR, 2);
                            }
                        }
                    }
                    continue;
                }
                if (z.cave()) {
                    continue;
                }
                int y0 = (int) Math.round(site.worldY(z.y()));
                for (int y = y0 + 1; y <= y0 + 30; y++) {
                    BlockState st = level.getBlockState(pos.set(wx, y, wz));
                    if (st.isAir() || ours(st)) {
                        continue;
                    }
                    level.setBlock(pos, AIR, 2);
                }
                BlockState top = level.getBlockState(pos.set(wx, y0, wz));
                if (!ours(top) && !top.is(Blocks.GRASS_BLOCK) && !top.is(Blocks.DIRT_PATH) && !top.is(Blocks.COARSE_DIRT)
                        && !top.is(Blocks.MOSSY_COBBLESTONE)) {
                    level.setBlock(pos, GRASS, 2);
                }
            }
        }
    }

    private static boolean ours(BlockState st) {
        return st.is(Blocks.STRIPPED_SPRUCE_LOG) || st.is(Blocks.LANTERN) || st.is(Blocks.SPRUCE_SIGN)
                || st.is(Blocks.DARK_OAK_WOOD) || st.is(Blocks.SPRUCE_WOOD)
                || (st.getBlock() instanceof LeavesBlock && st.getValue(LeavesBlock.PERSISTENT))
                || st.is(ModBlocks.POLISHED_HUA_GRANITE.get()) || st.is(ModBlocks.POLISHED_HUA_GRANITE_STAIRS.get())
                || st.is(ModBlocks.POLISHED_HUA_GRANITE_SLAB.get()) || st.is(ModBlocks.POLISHED_HUA_GRANITE_WALL.get())
                || st.is(Blocks.CHAIN) || st.is(ModBlocks.HUA_GRANITE_MOSSY.get()) || st.is(ModBlocks.HUA_GRANITE_CRACKED.get());
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
        // The worldgen heightmap can lag the real top by a block or more (seen on the stand: a single
        // grass block left floating 33 blocks over the gate terrace), so scan from above it.
        int surface = Math.min(maxY + 13, Math.max(chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, lx, lz), o) + 8);
        int sea = level.getSeaLevel();
        for (int y = surface; y > t; y--) {
            pos.set(wx, y, wz);
            BlockState there = chunk.getBlockState(pos);
            if (there.isAir() || (y < sea && !there.getFluidState().isEmpty())) {
                continue;
            }
            chunk.setBlockState(pos, AIR, false);
        }
        // Fill: rock from just below the old surface (buries grass/sand) up to the new top.
        int from = Math.min(o - 2, t - 4);
        boolean masonry = retainingWall(wx, wz, t) || stairSupport(wx, wz, t);
        for (int y = from; y <= t; y++) {
            pos.set(wx, y, wz);
            boolean exposed = y > lowest - 2 || t - y < 4;
            chunk.setBlockState(pos, !exposed ? STONE : masonry ? masonry(wx, y, wz) : granite(lx, lz, wx, y, wz, t, drop), false);
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

    /** The stair is carried on dressed stone where it runs above the natural rock (author ref 10). */
    private boolean stairSupport(int wx, int wz, int t) {
        double u = site.localU(wx + 0.5, wz + 0.5);
        double v = site.localV(wx + 0.5, wz + 0.5);
        double[] tr = shape.trailAt(u, v);
        return tr != null && tr[0] <= 3.6 && site.worldY(shape.ground(u, v)) < t - 2;
    }

    private BlockState masonry(int x, int y, int z) {
        long h = mix(x * 31 + y, z, 13);
        int r = (int) (h & 15);
        if (r < 2) {
            return ModBlocks.HUA_GRANITE_MOSSY.get().defaultBlockState();
        }
        if (r < 4) {
            return ModBlocks.HUA_GRANITE_CRACKED.get().defaultBlockState();
        }
        return ModBlocks.POLISHED_HUA_GRANITE.get().defaultBlockState();
    }

    /**
     * Huashan granite on an exposed face, chosen by where the block sits (author 03.10 — our own
     * block set): dark in narrow clefts, water-stained in streaks that hang from the lips, mossy on
     * north faces, cracked at rims, paler and greyer high up, warmer low down.
     */
    private BlockState granite(int lx, int lz, int x, int y, int z, int t, int drop) {
        // Narrow cleft: rock rises on both sides of this face.
        boolean cleftX = topAt(lx - 1, lz) > y + 1 && topAt(lx + 1, lz) > y + 1;
        boolean cleftZ = topAt(lx, lz - 1) > y + 1 && topAt(lx, lz + 1) > y + 1;
        if ((cleftX || cleftZ) && t - y > 2) {
            return ModBlocks.HUA_GRANITE_DARK.get().defaultBlockState();
        }
        // Water stains: vertical streak lines (2D noise is constant along y) hanging from the lip.
        // Bands a few blocks wide and tens of blocks long (codex r3: organised runoff, not flecks).
        double streak = noise.noise(x / 4.5, z / 4.5, 51.0);
        if (streak > 0.5 && t - y < 18 + 30 * (0.5 + 0.5 * noise.noise(x / 9.0, z / 9.0, 3.0))) {
            return ModBlocks.HUA_GRANITE_STAINED.get().defaultBlockState();
        }
        // Rims: frost-shattered blocks just below an edge.
        if (drop >= 6 && t - y <= 2 && noise.noise(x / 3.0, y / 3.0, z / 3.0) > -0.1) {
            return ModBlocks.HUA_GRANITE_CRACKED.get().defaultBlockState();
        }
        // North faces stay damp: moss and lichen (world north = -z).
        boolean northFace = topAt(lx, lz - 1) < y;
        if (northFace && y < SNOW_Y && noise.noise(x / 12.0, y / 12.0, z / 12.0) > 0.35) {
            return ModBlocks.HUA_GRANITE_MOSSY.get().defaultBlockState();
        }
        // Altitude banding: warm granite low, pale grey high (blended by noise, never a contour).
        double frac = (y - site.baseY()) / (double) (MountHuaSite.SUMMIT_Y - site.baseY());
        double pale = frac - 0.55 + 0.25 * noise.noise(x / 30.0, y / 20.0, z / 30.0);
        return (pale > 0 ? ModBlocks.HUA_GRANITE_PALE : ModBlocks.HUA_GRANITE).get().defaultBlockState();
    }

    /**
     * Granite palette (DESCRIPTIONS.md «Общая палитра гранита»): pale cream-grey (diorite,
     * calcite) with grey patches (andesite, stone), a pink tint (granite), and vertical dark
     * streaks (tuff) that run straight down the walls — a 2D noise in (x, z) is constant along y.
     */
    private BlockState rock(int x, int y, int z) {
        return (noise.noise(x / 9.0, y / 9.0, z / 9.0) > 0.3 ? ModBlocks.HUA_GRANITE_STAINED : ModBlocks.HUA_GRANITE)
                .get().defaultBlockState();
    }

    /** Surface: «flat is green, steep is bare» (DESCRIPTIONS.md p.8), snow on high flats. */
    private void skin(int lx, int lz, int t, int drop, double w, BlockPos.MutableBlockPos pos) {
        int wx = x0 + lx;
        int wz = z0 + lz;
        Zone terrace = zoneAt(site.localU(wx + 0.5, wz + 0.5), site.localV(wx + 0.5, wz + 0.5), 0);
        if (terrace != null && !terrace.cave() && !terrace.id().equals("grove")) {
            // Vanilla trees that root here are removed by the cleanup pass.
            // The sect is poor and run-down (author's answer 3): an overgrown yard with worn paths.
            long r = mix(wx, wz, 17) & 15;
            double path = noise.noise(wx / 9.0, wz / 9.0, 71.0);
            BlockState ground = path > 0.55 ? Blocks.DIRT_PATH.defaultBlockState()
                    : r < 2 ? Blocks.COARSE_DIRT.defaultBlockState() : r < 3 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
                    : GRASS;
            chunk.setBlockState(pos.set(wx, t, wz), ground, false);
            chunk.setBlockState(pos.set(wx, t - 1, wz), DIRT, false);
            return;
        }
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
        // Soil collects in pockets, gullies and on shelves; big flats high up stay bare granite
        // with moss (codex r1: green contour bands). Low ground (the forested foot) keeps its soil.
        double soil = noise.noise(wx / 23.0, wz / 23.0, 47.0) + (t < site.baseY() + 60 ? 0.6 : 0.0)
                + (t > site.worldY(150) ? 0.55 : 0.0)
                + (drop >= 2 ? 0.25 : 0.0);
        // Steps of a steep wall are not shelves: within two blocks the rock falls away by 5+,
        // so no soil there (stand 03.10: brown dots marching diagonally across the faces).
        int fall = 0;
        for (int dz = -3; dz <= 3; dz += 3) {
            for (int dx = -3; dx <= 3; dx += 3) {
                fall = Math.max(fall, t - topAt(lx + dx, lz + dz));
            }
        }
        // Stepped walls (a 45°+ staircase of single blocks) stay bare: no grass stripes across faces.
        if ((soil < -0.15 && t > site.baseY() + 30) || (fall >= 3 && t > site.baseY() + 1)) {
            if (n > 0.35 && t < SNOW_Y) {
                chunk.setBlockState(pos.set(wx, t, wz), ModBlocks.HUA_GRANITE_MOSSY.get().defaultBlockState(), false);
            }
            return;
        }
        if (drop <= 1 || (drop == 2 && n > 0.1)) {
            BlockState ground;
            if (t >= SNOW_Y + (int) (6 * n) && noise.noise(wx / 11.0, wz / 11.0, 63.0) > -0.1) {
                ground = GRASS.setValue(SnowyDirtBlock.SNOWY, true);
            } else if (n > 0.3 && drop == 0 && t > site.baseY() + 50) {
                // Needles and grit on the high shelves (custom block, author 03.10).
                ground = ModBlocks.HUA_LITTER.get().defaultBlockState();
            } else if (n > 0.45 && drop == 0) {
                ground = Blocks.PODZOL.defaultBlockState();
            } else if (n < -0.5 && drop == 0) {
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
        stream(pos);
        bridge(pos);
        baseDetail(pos);
    }

    private void trailColumn(int lx, int lz, BlockPos.MutableBlockPos pos) {
        int wx = x0 + lx;
        int wz = z0 + lz;
        double u = site.localU(wx + 0.5, wz + 0.5);
        double v = site.localV(wx + 0.5, wz + 0.5);
        double[] tr = shape.trailAt(u, v);
        if (tr == null || tr[0] > 2.1) {
            return;
        }
        int index = (int) tr[2];
        int t = topAt(lx, lz);
        if (tr[0] <= 2.1) {
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
                // The lowest part of the trail is the oldest: worn, cracked, mossy, half cobble.
                int worn = index < 120 ? 4 : 1;
                int r = (int) (h & 15);
                block = r < worn ? ModBlocks.HUA_GRANITE_MOSSY.get().defaultBlockState()
                        : r < worn * 2 ? ModBlocks.HUA_GRANITE_CRACKED.get().defaultBlockState()
                        : (index < 120 && r < worn * 2 + 3) ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
                        : ModBlocks.POLISHED_HUA_GRANITE.get().defaultBlockState();
            }
            level.setBlock(pos.set(wx, t, wz), block, 2);
            for (int y = t + 1; y <= t + 4; y++) {
                level.setBlock(pos.set(wx, y, wz), AIR, 2);
            }
            if (index < 160 && tr[0] > 1.5) {
                // The climb starts in the gorge with wooden posts along the stair
                // (author2/foot-of-mountain.png): spruce posts two blocks high every few steps.
                if (index % 4 == 0) {
                    level.setBlock(pos.set(wx, t + 1, wz), Blocks.SPRUCE_FENCE.defaultBlockState(), 2);
                    level.setBlock(pos.set(wx, t + 2, wz), Blocks.SPRUCE_FENCE.defaultBlockState(), 2);
                }
            } else if (tr[0] > 1.5 && t - lowestAround(lx, lz) >= 3) {
                // Outer edge over a drop: a low stone curb with posts (author ref 10), chains only
                // where the fall is deep.
                BlockState curb = index % 5 == 0 ? ModBlocks.POLISHED_HUA_GRANITE_WALL.get().defaultBlockState()
                        : ModBlocks.POLISHED_HUA_GRANITE_SLAB.get().defaultBlockState();
                if (index % 5 != 0 && t - lowestAround(lx, lz) >= 12 && index % 5 == 2) {
                    int[] wd = site.rotateDir((int) Math.signum(Math.round(dir[0])), (int) Math.signum(Math.round(dir[1])));
                    Direction.Axis axis = Math.abs(wd[0]) >= Math.abs(wd[1]) ? Direction.Axis.X : Direction.Axis.Z;
                    curb = Blocks.CHAIN.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis);
                }
                level.setBlock(pos.set(wx, t + 1, wz), curb, 2);
            }
            return;
        }
    }

    private static double smooth(double e0, double e1, double x) {
        double t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
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
        return ModBlocks.POLISHED_HUA_GRANITE_STAIRS.get().defaultBlockState().setValue(StairBlock.FACING, facing);
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
        boolean sect = z.v() > -60 && z.v() < 130 && Math.abs(z.u()) < 120 && !z.id().startsWith("pav");
        if (sect && !MountHuaPlan.CORE.contains(z.id()) && z.id().matches(RUINS)) {
            ruin(z, r, y, pos);
            return;
        }
        for (int x = Math.max(r[0], x0); x <= Math.min(r[2], x0 + 15); x++) {
            for (int zz = Math.max(r[1], z0); zz <= Math.min(r[3], z0 + 15); zz++) {
                boolean edge = x == r[0] || x == r[2] || zz == r[1] || zz == r[3];
                if (edge) {
                    level.setBlock(pos.set(x, y, zz), ModBlocks.POLISHED_HUA_GRANITE.get().defaultBlockState(), 2);
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

    /** Sites shown as ruined foundations (none on the old shelf yet; outlines elsewhere). */
    private static final String RUINS = "ancestors|elders|treasury|scriptures|alchemy|knoll";

    /**
     * A site for later: the sect starts poor (canon), so here stands only a ruined foundation —
     * a broken stone footing with column stubs and moss; the author rebuilds it later.
     */
    private void ruin(Zone z, int[] r, int y, BlockPos.MutableBlockPos pos) {
        for (int x = Math.max(r[0], x0); x <= Math.min(r[2], x0 + 15); x++) {
            for (int zz = Math.max(r[1], z0); zz <= Math.min(r[3], z0 + 15); zz++) {
                boolean edge = x == r[0] || x == r[2] || zz == r[1] || zz == r[3];
                long h = mix(x, zz, 61);
                if (edge && (h & 7) != 0) {
                    level.setBlock(pos.set(x, y, zz), (h & 3) == 0 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
                            : ModBlocks.HUA_GRANITE_CRACKED.get().defaultBlockState(), 2);
                    // Column stubs every 6 blocks along the footing, broken at different heights.
                    if (((x - r[0]) % 6 == 0 || x == r[2]) && ((zz - r[1]) % 6 == 0 || zz == r[3])) {
                        int k = 1 + (int) ((h >>> 8) % 3);
                        for (int j = 1; j <= k; j++) {
                            level.setBlock(pos.set(x, y + j, zz), j == k && ((h >>> 12) & 1) == 0
                                    ? Blocks.MOSSY_STONE_BRICK_WALL.defaultBlockState()
                                    : Blocks.STONE_BRICK_WALL.defaultBlockState(), 2);
                        }
                    }
                } else if (!edge && (h % 23) == 0) {
                    level.setBlock(pos.set(x, y, zz), Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 2);
                }
            }
        }
        if (inChunk(r[0] + 1, r[1] + 1)) {
            sign(r[0] + 1, y + 1, r[1] + 1, z, pos);
        }
    }

    /**
     * Stairs between the sect platforms (polished granite steps); where a path runs over lower
     * ground it becomes a small wooden bridge with rails.
     */
    private void bridge(BlockPos.MutableBlockPos pos) {
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int wx = x0 + lx;
                int wz = z0 + lz;
                double u = site.localU(wx + 0.5, wz + 0.5);
                double v = site.localV(wx + 0.5, wz + 0.5);
                for (double[] p : MountHuaPlan.PATHS) {
                    double[] q = MountHuaShape.pathAt(p, u, v);
                    if (q[0] > 2.1) {
                        continue;
                    }
                    int y = (int) Math.round(site.worldY(q[1]));
                    int natural = (int) Math.round(site.worldY(shape.ground(u, v)));
                    if (natural < y - 3) {
                        level.setBlock(pos.set(wx, y, wz), Blocks.SPRUCE_PLANKS.defaultBlockState(), 2);
                        if (q[0] > 1.5) {
                            level.setBlock(pos.set(wx, y + 1, wz), Blocks.SPRUCE_FENCE.defaultBlockState(), 2);
                        }
                    } else {
                        double ahead = p[2] + (p[5] - p[2]) * Math.min(1, q[2] + 0.05);
                        boolean rising = Math.round(site.worldY(ahead)) != y;
                        double du = p[3] - p[0];
                        double dv = p[4] - p[1];
                        if (p[5] < p[2]) {
                            du = -du;
                            dv = -dv;
                        }
                        level.setBlock(pos.set(wx, y, wz), rising ? stairs(du, dv)
                                : ModBlocks.POLISHED_HUA_GRANITE.get().defaultBlockState(), 2);
                    }
                    for (int k = 1; k <= 4; k++) {
                        if (!level.getBlockState(pos.set(wx, y + k, wz)).is(Blocks.SPRUCE_FENCE)) {
                            level.setBlock(pos, AIR, 2);
                        }
                    }
                    break;
                }
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
                .setMessage(2, Component.translatable(MountHuaPlan.CORE.contains(zone.id()) || !zone.id().matches(RUINS) ? "sign.murim.hua.placeholder"
                        : "sign.murim.hua.ruin"));
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
                level.setBlock(pos.set(x, y, zz), edge ? ModBlocks.POLISHED_HUA_GRANITE.get().defaultBlockState()
                        : ModBlocks.HUA_GRANITE_DARK.get().defaultBlockState(), 2);
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
                    level.setBlock(pos.set(wxz[0], y, wxz[1]), ModBlocks.POLISHED_HUA_GRANITE.get().defaultBlockState(), 2);
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
                Zone near = zoneAt(u, v, 7);
                if (near != null && zoneAt(u, v, 2) == null && !near.cave()
                        && (near.id().equals("grove") || near.id().equals("upper") || near.id().equals("ancestors") || near.id().equals("elders") || near.id().equals("scriptures") || near.id().startsWith("pav"))) {
                    // A ring of plums just outside the terrace edge (the yard itself stays free).
                    long h = mix(wx, wz, 29);
                    if (Math.floorMod(h, 1000L) < 30) {
                        plumTree(wx, t + 1, wz, h, pos);
                    }
                    continue;
                }
                Zone zone = zoneAt(u, v, 9);
                if (zone != null) {
                    if (zone.id().equals("grove") && zoneAt(u, v, -3) == zone) {
                        long h = mix(wx, wz, 11);
                        if (h % 31 == 0) {
                            plumTree(wx, t + 1, wz, h, pos);
                        }
                    }
                    continue;
                }
                double[] tr = shape.trailAt(u, v);
                if (tr != null && tr[0] < 8) {
                    continue;
                }
                if (tr != null && tr[0] < 8 && Math.floorMod(mix(wx, wz, 31), 1000L) < 14) {
                    plumTree(wx, t + 1, wz, mix(wx, wz, 31), pos);
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
                boolean soil = ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.PODZOL) || ground.is(ModBlocks.HUA_LITTER.get())
                        || ground.is(Blocks.MOSS_BLOCK) || ground.is(Blocks.COARSE_DIRT);
                long h = mix(wx, wz, 7);
                int roll = (int) Math.floorMod(h, 1000L);
                // Pines on ledges and rims (a big drop right next to a flat spot), fewer inland.
                int chance = drop <= 2 && deep >= 7 ? 25 : (soil && drop <= 2 ? 6 : 0);
                // Dark-green shrub caps on tops of towers and patches clinging in folds and on
                // shelves (author refs 03, 06, 16: «шапками на вершинах, карманами на полках»).
                double patch = noise.noise(wx / 7.0, wz / 7.0, 57.0);
                // Upper faces carry more green (author 04.10, refs 03, 14): caps nearly closed,
                // shelves and folds clothed high up; the walls themselves stay rock.
                boolean high = t > site.worldY(140);
                boolean cap = deep >= 10 && drop <= 2;
                boolean fold = drop >= 2 && drop <= 5 && patch > (high ? 0.0 : 0.35) && t < SNOW_Y;
                boolean shelf = high && drop <= 1 && patch > -0.3;
                if ((cap && patch > -0.5) || (fold && roll % (high ? 2 : 4) == 0) || (shelf && roll % 3 == 0)) {
                    shrub(wx, t + 1, wz, h, pos);
                    continue;
                }
                if (roll < chance) {
                    pine(wx, t + 1, wz, h, lx, lz, pos);
                } else if (soil && drop <= 2 && t < site.baseY() + 130 && roll > 996) {
                    plumTree(wx, t + 1, wz, h, pos);
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
        // Size, lean and crown vary per tree (codex r1: the pines looked standardised).
        int height = 4 + (int) Math.floorMod(h >>> 8, 10L);
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
        // Lean over the drop: 0–3 sideways steps spread along the trunk.
        int leanSteps = (int) Math.floorMod(h >>> 16, 4L);
        int cx = x;
        int cz = z;
        BlockState log = Blocks.SPRUCE_WOOD.defaultBlockState();
        for (int k = 0; k < height; k++) {
            if (leanSteps > 0 && k > 1 && k % Math.max(2, height / (leanSteps + 1)) == 0) {
                cx += bestDx;
                cz += bestDz;
                leanSteps--;
                level.setBlock(pos.set(cx - bestDx, y + k, cz - bestDz), log, 2);
            }
            if (!level.getBlockState(pos.set(cx, y + k, cz)).isAir()) {
                return;
            }
            level.setBlock(pos, log, 2);
        }
        BlockState leaves = Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        int tiers = 1 + (int) Math.floorMod(h >>> 24, 3L);
        if (height < 6) {
            tiers = 1;
        }
        for (int tier = 0; tier < tiers; tier++) {
            int py = y + height - 1 - tier * (2 + (int) Math.floorMod(h >>> (40 + tier), 2L));
            int radius = tier == 0 ? 2 + (int) Math.floorMod(h >>> 36, 2L) : 3 + (int) Math.floorMod(h >>> (44 + tier), 2L);
            // Pads reach out over the drop more than back over the rock.
            int reach = tier == 0 ? 0 : 1 + (int) Math.floorMod(h >>> (28 + tier), 2L);
            int ox = cx + bestDx * reach;
            int oz = cz + bestDz * reach;
            if (tier > 0) {
                for (int r = 1; r <= reach; r++) {
                    level.setBlock(pos.set(cx + bestDx * r, py, cz + bestDz * r), log, 2);
                }
            }
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int d2 = dx * dx + dz * dz;
                    if (d2 > radius * radius + 1 || (d2 >= radius * radius && ((h >>> ((dx + 4) * 5 + dz + 4)) & 1) == 0)) {
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

    /** Low dark shrub: 1-2 blocks of persistent spruce/azalea foliage hugging the rock. */
    private void shrub(int x, int y, int z, long h, BlockPos.MutableBlockPos pos) {
        BlockState leaves = ((h >>> 5) & 3) == 0
                ? Blocks.AZALEA_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true)
                : Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        placeLeaf(x, y, z, leaves, pos);
        if (((h >>> 9) & 1) == 0) {
            placeLeaf(x, y + 1, z, leaves, pos);
        }
        int[][] side = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int k = 0; k < 4; k++) {
            if (((h >>> (12 + k)) & 1) == 0) {
                int sx = x + side[k][0];
                int sz = z + side[k][1];
                if (!level.getBlockState(pos.set(sx, y - 1, sz)).isAir()) {
                    placeLeaf(sx, y, sz, leaves, pos);
                }
            }
        }
    }

    private void placeLeaf(int x, int y, int z, BlockState leaves, BlockPos.MutableBlockPos pos) {
        if (level.getBlockState(pos.set(x, y, z)).isAir()) {
            level.setBlock(pos, leaves, 2);
        }
    }

    /**
     * Plum tree (梅): a short dark gnarled trunk and a few thick crooked branches with blossoms
     * straight on the bare wood — no green crown, so it never reads as a sakura (author 03.10).
     * Colour per tree: pink (60%), pale/white (25%), deep red (15%).
     */
    private void plumTree(int x, int y, int z, long h, BlockPos.MutableBlockPos pos) {
        BlockState wood = Blocks.DARK_OAK_WOOD.defaultBlockState();
        int colour = (int) Math.floorMod(h >>> 3, 20L);
        BlockState blossom = (colour < 12 ? ModBlocks.PLUM_BLOSSOM.get() : colour < 17 ? ModBlocks.PLUM_BLOSSOM_PALE.get()
                : ModBlocks.PLUM_BLOSSOM_RED.get()).defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        int trunk = 2 + (int) Math.floorMod(h >>> 7, 3L);
        int cx = x;
        int cz = z;
        for (int k = 0; k < trunk; k++) {
            if (k == trunk - 1 && ((h >>> 11) & 1) == 0) {
                cx += ((h >>> 12) & 1) == 0 ? 1 : -1;
            }
            if (!level.getBlockState(pos.set(cx, y + k, cz)).canBeReplaced()) {
                return;
            }
            level.setBlock(pos, wood, 2);
        }
        int branches = 2 + (int) Math.floorMod(h >>> 14, 3L);
        int[][] dirs = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
        int start = (int) Math.floorMod(h >>> 17, 8L);
        for (int b = 0; b < branches; b++) {
            int[] d = dirs[(start + b * (8 / branches) + (int) Math.floorMod(h >>> (20 + b), 2L)) % 8];
            int bx = cx;
            int by = y + trunk - 1 - (b % 2);
            int bz = cz;
            int len = 3 + (int) Math.floorMod(h >>> (24 + b * 2), 4L);
            for (int s = 0; s < len; s++) {
                bx += d[0];
                bz += d[1];
                if (s % 2 == 1 || ((h >>> (30 + s + b)) & 1) == 1) {
                    by++;
                }
                // Crooked: an occasional sideways kink.
                if (((h >>> (40 + s + b)) & 3) == 0) {
                    bx += d[1];
                    bz -= d[0];
                }
                if (!level.getBlockState(pos.set(bx, by, bz)).canBeReplaced()) {
                    break;
                }
                level.setBlock(pos, wood, 2);
                if (s >= 1 && ((h >>> (8 + s * 3 + b)) & 3) == 0) {
                    placeLeaf(bx, by + 1, bz, blossom, pos);
                }
                if (s >= 2 && ((h >>> (12 + s * 2 + b)) & 3) == 0) {
                    placeLeaf(bx + d[1], by, bz - d[0], blossom, pos);
                }
            }
            // A small, gappy cluster at the tip (codex r2: blossom blocks overpowered the branches).
            int[][] tip = {{0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {d[0], 1, d[1]}};
            for (int k = 0; k < tip.length; k++) {
                if (k == 0 || ((h >>> (k * 5 + b)) & 1) == 0) {
                    placeLeaf(bx + tip[k][0], by + tip[k][1], bz + tip[k][2], blossom, pos);
                }
            }
        }
    }

    /** A clump of thick bamboo (Qinling foothills), 6–12 high, leafy tops. */
    private void bamboo(int x, int y, int z, long h, BlockPos.MutableBlockPos pos) {
        int stalks = 4 + (int) Math.floorMod(h >>> 5, 6L);
        for (int i = 0; i < stalks; i++) {
            int sx = x + (int) Math.floorMod(h >>> (9 + i * 3), 5L) - 2;
            int sz = z + (int) Math.floorMod(h >>> (11 + i * 3), 5L) - 2;
            int ground = y - 1;
            BlockState below = level.getBlockState(pos.set(sx, ground, sz));
            if (!below.is(BlockTags.BAMBOO_PLANTABLE_ON) || !level.getBlockState(pos.set(sx, y, sz)).isAir()) {
                continue;
            }
            int height = 6 + (int) Math.floorMod(h >>> (20 + i), 7L);
            for (int k = 0; k < height; k++) {
                BambooLeaves leaves = k >= height - 2 ? BambooLeaves.LARGE : k == height - 3 ? BambooLeaves.SMALL : BambooLeaves.NONE;
                if (!level.getBlockState(pos.set(sx, y + k, sz)).isAir()) {
                    break;
                }
                level.setBlock(pos, Blocks.BAMBOO.defaultBlockState().setValue(BambooStalkBlock.AGE, 1)
                        .setValue(BambooStalkBlock.LEAVES, leaves), 2);
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

    /** The stream down the main valley: still water in the carved bed (its floor steps down). */
    private void stream(BlockPos.MutableBlockPos pos) {
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                if (weight[idx(lx + B, lz + B)] <= 0) {
                    continue;
                }
                int wx = x0 + lx;
                int wz = z0 + lz;
                double d = shape.streamDistance(site.localU(wx + 0.5, wz + 0.5), site.localV(wx + 0.5, wz + 0.5));
                if (d > 3.5) {
                    continue;
                }
                int t = topAt(lx, lz);
                long h = mix(wx, wz, 41);
                if (d > 1.5) {
                    // Banks: rounded stones, gravel and moss along the water.
                    int r = (int) (h & 15);
                    if (r < 6) {
                        BlockState bank = r < 2 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
                                : r < 4 ? Blocks.GRAVEL.defaultBlockState() : ModBlocks.HUA_GRANITE_MOSSY.get().defaultBlockState();
                        level.setBlock(pos.set(wx, t, wz), bank, 2);
                    }
                    continue;
                }
                level.setBlock(pos.set(wx, t, wz), (h & 3) == 0 ? Blocks.COBBLESTONE.defaultBlockState()
                        : Blocks.GRAVEL.defaultBlockState(), 2);
                // Now and then a stone breaks the surface.
                level.setBlock(pos.set(wx, t + 1, wz), (h & 31) == 0 ? ModBlocks.HUA_GRANITE_MOSSY.get().defaultBlockState()
                        : Blocks.WATER.defaultBlockState(), 2);
            }
        }
    }

    /**
     * Detail on the lower slopes and at the foot of the walls (author 04.10: «снизу можно
     * подетальнее»): scree aprons of gravel and cobble, boulders of mixed sizes, fallen blocks,
     * shrubs and roots at the wall foot, moss carpets. Only below the middle of the massif.
     */
    private void baseDetail(BlockPos.MutableBlockPos pos) {
        int ceiling = (int) Math.round(site.worldY(110));
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                if (weight[idx(lx + B, lz + B)] < 0.5) {
                    continue;
                }
                int t = topAt(lx, lz);
                if (t > ceiling) {
                    continue;
                }
                int wx = x0 + lx;
                int wz = z0 + lz;
                double u = site.localU(wx + 0.5, wz + 0.5);
                double v = site.localV(wx + 0.5, wz + 0.5);
                if (zoneAt(u, v, 2) != null) {
                    continue;
                }
                double[] tr = shape.trailAt(u, v);
                if (tr != null && tr[0] < 4) {
                    continue;
                }
                int drop = t - lowestAround(lx, lz);
                if (drop > 2) {
                    continue; // only on walkable ground, the walls keep their faces
                }
                int rise = 0;
                for (int dz = -3; dz <= 3; dz += 3) {
                    for (int dx = -3; dx <= 3; dx += 3) {
                        rise = Math.max(rise, topAt(lx + dx, lz + dz) - t);
                    }
                }
                boolean foot = rise >= 8;
                long h = mix(wx, wz, 53);
                int roll = (int) Math.floorMod(h, 1000L);
                BlockState top = chunk.getBlockState(pos.set(wx, t, wz));
                if (!top.isSolidRender(chunk, pos)) {
                    continue;
                }
                if (foot) {
                    // Scree apron: fallen material piles up at the foot of every wall.
                    if (roll < 22) {
                        boulder(wx, t, wz, 1 + (int) Math.floorMod(h >>> 10, 3L), h, pos);
                    } else if (roll < 300) {
                        int r = (int) ((h >>> 12) & 7);
                        BlockState scree = r < 3 ? Blocks.GRAVEL.defaultBlockState()
                                : r < 5 ? Blocks.COBBLESTONE.defaultBlockState()
                                : r < 6 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
                                : ModBlocks.HUA_GRANITE_CRACKED.get().defaultBlockState();
                        level.setBlock(pos.set(wx, t, wz), scree, 2);
                        if (r == 7 && roll < 120) {
                            level.setBlock(pos.set(wx, t + 1, wz), ModBlocks.HUA_GRANITE_CRACKED.get().defaultBlockState(), 2);
                        }
                    } else if (roll < 360) {
                        level.setBlock(pos.set(wx, t, wz), Blocks.ROOTED_DIRT.defaultBlockState(), 2);
                        shrub(wx, t + 1, wz, h, pos);
                    } else if (roll < 420 && t < SNOW_Y) {
                        level.setBlock(pos.set(wx, t + 1, wz), Blocks.MOSS_CARPET.defaultBlockState(), 2);
                    }
                } else if (roll < 3) {
                    // Lone boulders of mixed size in the foothills.
                    boulder(wx, t, wz, 1 + (int) Math.floorMod(h >>> 10, 4L), h, pos);
                } else if (roll < 9) {
                    level.setBlock(pos.set(wx, t, wz), Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 2);
                    level.setBlock(pos.set(wx, t + 1, wz), ModBlocks.HUA_GRANITE.get().defaultBlockState(), 2);
                }
            }
        }
    }

    /** A rounded granite boulder sunk one block into the ground; moss on top, stains on its sides. */
    private void boulder(int x, int ground, int z, int r, long h, BlockPos.MutableBlockPos pos) {
        double ry = r * (0.6 + 0.3 * ((h >>> 20) & 3) / 3.0);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -1; dy <= (int) Math.ceil(ry); dy++) {
                    double q = (dx * dx + dz * dz) / (double) (r * r + 0.5) + (dy * dy) / (ry * ry + 0.5);
                    if (q > 1.0 + 0.25 * (((h >>> (dx + dz * 3 + 30 + dy)) & 1))) {
                        continue;
                    }
                    BlockState here = level.getBlockState(pos.set(x + dx, ground + dy, z + dz));
                    if (!here.isAir() && !here.canBeReplaced() && dy > 0) {
                        continue;
                    }
                    boolean upper = dy >= ry - 0.5;
                    BlockState st = upper && ((h >>> (dx + 7)) & 1) == 0 ? ModBlocks.HUA_GRANITE_MOSSY.get().defaultBlockState()
                            : ((h >>> (dz + 13)) & 3) == 0 ? ModBlocks.HUA_GRANITE_CRACKED.get().defaultBlockState()
                            : ((h >>> (dy + 17)) & 3) == 0 ? ModBlocks.HUA_GRANITE_STAINED.get().defaultBlockState()
                            : ModBlocks.HUA_GRANITE.get().defaultBlockState();
                    level.setBlock(pos, st, 2);
                }
            }
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
