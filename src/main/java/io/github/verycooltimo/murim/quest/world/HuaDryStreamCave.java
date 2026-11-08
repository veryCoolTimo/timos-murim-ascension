package io.github.verycooltimo.murim.quest.world;

import io.github.verycooltimo.murim.quest.ClueBlock;
import io.github.verycooltimo.murim.registry.ModBlocks;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Dry Stream Cave, the cave of Chung Myung's cache (quest 1; design side-quests-v3): a stamped mound of Hua rock beyond the
 * saddle of the South Peak, with the cave of {@link DryStreamCaveShape} carved in it, safe (no hostile spawns, see
 * {@link QuestSafeZones}) and the same at every site rotation: the cave is laid out in the mountain's own local frame, and
 * the rotation only moves the world coordinates. Runs in the mountain's cleanup pass, chunk by chunk; every column is
 * decided by its own coordinates, so chunk borders never show.
 */
public final class HuaDryStreamCave {

    // Side quests stopped by the author on 24.10; the author builds the cave by hand.
    public static final boolean ENABLED = false;

    /** Where the cave's origin (the entrance hall's gully) is, in the mountain's local block frame. */
    public static final int CU = 132, CV = 118;

    private HuaDryStreamCave() {
    }

    /** The cave's frame in one world. */
    public record Frame(MountHuaSite site, int baseY) {
        public static Frame of(MountHuaSite site) {
            if (site == null || site.shape() == null) return null;
            int base = (int) Math.round(site.worldY(site.shape().height(CU + 8.5, CV + 0.5)));
            return new Frame(site, base);
        }

        /** World x and z of a cave-frame column. */
        public int[] world(int d, int w) {
            int[] r = site.rotateDir(CU + d, CV + w);
            return new int[] {site.centerX() + r[0], site.centerZ() + r[1]};
        }

        public BlockPos pos(int d, int w, int y) {
            int[] xz = world(d, w);
            return new BlockPos(xz[0], baseY + y, xz[1]);
        }

        /** Cave-frame {d, w} of a world column. */
        public int[] local(int x, int z) {
            int[] uv = site.stampBlock(x, z);
            return new int[] {uv[0] - CU, uv[1] - CV};
        }

        /** World box of everything the stamp touches. */
        public BoundingBox box() {
            int[] b = DryStreamCaveShape.bounds();
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (int d : new int[] {b[0], b[1]}) {
                for (int w : new int[] {b[2], b[3]}) {
                    int[] xz = world(d, w);
                    minX = Math.min(minX, xz[0]);
                    maxX = Math.max(maxX, xz[0]);
                    minZ = Math.min(minZ, xz[1]);
                    maxZ = Math.max(maxZ, xz[1]);
                }
            }
            return new BoundingBox(minX, baseY + b[4], minZ, maxX, baseY + b[5], maxZ);
        }
    }

    /** Stamps this chunk's share of the cave (cleanup pass). */
    public static void apply(WorldGenLevel level, ChunkPos cp, MountHuaSite site) {
        if (!ENABLED) return;
        Frame f = Frame.of(site);
        if (f == null) return;
        BoundingBox box = f.box();
        if (box.maxX() < cp.getMinBlockX() || box.minX() > cp.getMaxBlockX() || box.maxZ() < cp.getMinBlockZ() || box.minZ() > cp.getMaxBlockZ()) {
            return;
        }
        ChunkAccess chunk = level.getChunk(cp.x, cp.z);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 2;
        for (int x = Math.max(cp.getMinBlockX(), box.minX()); x <= Math.min(cp.getMaxBlockX(), box.maxX()); x++) {
            for (int z = Math.max(cp.getMinBlockZ(), box.minZ()); z <= Math.min(cp.getMaxBlockZ(), box.maxZ()); z++) {
                int[] l = f.local(x, z);
                int d = l[0], w = l[1];
                if (!DryStreamCaveShape.inFootprint(d, w)) continue;
                int ground = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x & 15, z & 15);
                int top = DryStreamCaveShape.top(d, w);
                int rockTop = top >= 0 ? f.baseY() + top : Integer.MIN_VALUE;
                int from = Math.max(minY, Math.min(f.baseY() + DryStreamCaveShape.DEEPEST - 5, ground - 1));
                int to = Math.min(maxY, Math.max(rockTop, ground) + 1);
                for (int y = from; y <= to; y++) {
                    int rel = y - f.baseY();
                    pos.set(x, y, z);
                    if (DryStreamCaveShape.open(d, w, rel)) {
                        if (DryStreamCaveShape.water(d, w, rel)) {
                            level.setBlock(pos, Blocks.WATER.defaultBlockState(), 2);
                        } else if (!level.getBlockState(pos).isAir()) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                        }
                    } else if (DryStreamCaveShape.rubble(d, w, rel)) {
                        level.setBlock(pos, rubble(d, w, rel), 2);
                    } else if (top >= 0 && rel <= top) {
                        level.setBlock(pos, rock(d, w, rel, top), 2);
                    }
                }
                dress(level, f, chunk, x, z, d, w, ground);
            }
        }
        decor(level, cp, f);
    }

    static BlockState rock(int d, int w, int rel, int top) {
        double n = (DryStreamCaveShape.noise(d, rel, w) + 1) / 2;
        boolean skin = rel >= top - 1;
        if (skin && n < 0.25) return Blocks.MOSS_BLOCK.defaultBlockState();
        Block b;
        if (n < 0.5) b = ModBlocks.HUA_GRANITE.get();
        else if (n < 0.68) b = ModBlocks.HUA_GRANITE_DARK.get();
        else if (n < 0.82) b = ModBlocks.HUA_GRANITE_CRACKED.get();
        else if (n < 0.92) b = Blocks.STONE;
        else b = skin ? ModBlocks.HUA_GRANITE_MOSSY.get() : Blocks.ANDESITE;
        return b.defaultBlockState();
    }

    static BlockState rubble(int d, int w, int rel) {
        double n = (DryStreamCaveShape.noise(d * 3, rel, w * 5) + 1) / 2;
        Block b = n < 0.35 ? Blocks.COBBLESTONE : n < 0.6 ? Blocks.GRAVEL : n < 0.8 ? Blocks.ANDESITE : Blocks.MOSSY_COBBLESTONE;
        return b.defaultBlockState();
    }

    /** Floor dressing: the dry stream bed in the gully, wet moss by the pool. */
    private static void dress(WorldGenLevel level, Frame f, ChunkAccess chunk, int x, int z, int d, int w, int ground) {
        if (d < DryStreamCaveShape.GULLY_FROM - 12 || d > DryStreamCaveShape.MOUTH + 6) return;
        double bed = 2.4 * Math.sin(d / 5.0);
        if (Math.abs(w - bed) <= 1.2 + 0.6 * DryStreamCaveShape.noise(d, 0, 9)) {
            BlockPos p = new BlockPos(x, d < DryStreamCaveShape.MOUTH ? Math.min(ground, f.baseY() + 1) - 1 : f.baseY() - 1, z);
            if (d < DryStreamCaveShape.GULLY_FROM || level.getBlockState(p).isAir()) return;
            level.setBlock(p, (DryStreamCaveShape.noise(d, w, 5) > 0 ? Blocks.GRAVEL : Blocks.COARSE_DIRT).defaultBlockState(), 2);
        }
    }

    private static boolean in(ChunkPos cp, BlockPos p) {
        return p.getX() >= cp.getMinBlockX() && p.getX() <= cp.getMaxBlockX() && p.getZ() >= cp.getMinBlockZ() && p.getZ() <= cp.getMaxBlockZ();
    }

    /** The story pieces: box, wall, belt, campfire, soot, lanterns, the cup and the pale forked pine. */
    private static void decor(WorldGenLevel level, ChunkPos cp, Frame f) {
        int[] b = DryStreamCaveShape.BOX;
        BlockPos box = f.pos(b[0], b[1], b[2]);
        if (in(cp, box)) ClueBlock.place(level, box, "cache_box");
        int[] wl = DryStreamCaveShape.WALL;
        for (int dw = 0; dw < 3; dw++) {
            for (int dy = 0; dy < 2; dy++) {
                BlockPos p = f.pos(wl[0], wl[1] + dw - 1, wl[2] + dy);
                if (in(cp, p) && DryStreamCaveShape.open(wl[0], wl[1] + dw - 1, wl[2] + dy)) ClueBlock.place(level, p, "cave_wall");
            }
        }
        int[] bt = DryStreamCaveShape.BELT;
        BlockPos belt = f.pos(bt[0], bt[1], bt[2]);
        if (in(cp, belt)) ClueBlock.place(level, belt, "belt_note");
        int[] cf = DryStreamCaveShape.CAMPFIRE;
        BlockPos fire = f.pos(cf[0], cf[1], cf[2]);
        if (in(cp, fire)) level.setBlock(fire, Blocks.CAMPFIRE.defaultBlockState().setValue(net.minecraft.world.level.block.CampfireBlock.LIT, false), 2);
        for (int[] s : DryStreamCaveShape.SOOT) {
            for (int dd = -1; dd <= 1; dd++) {
                for (int dw = -1; dw <= 1; dw++) {
                    int d = s[0] + dd, w = s[1] + dw;
                    int fl = DryStreamCaveShape.floor(d, w);
                    if (fl == Integer.MIN_VALUE || (dd + dw + d) % 2 != 0) continue;
                    BlockPos p = f.pos(d, w, fl - 1);
                    if (in(cp, p)) level.setBlock(p, (dd == 0 && dw == 0 ? Blocks.COAL_BLOCK : Blocks.SMOOTH_BASALT).defaultBlockState(), 2);
                }
            }
        }
        for (int[] l : LANTERNS) {
            int ce = DryStreamCaveShape.ceiling(l[0], l[1]);
            if (ce == Integer.MIN_VALUE || ce == Integer.MAX_VALUE) continue;
            BlockPos p = f.pos(l[0], l[1], ce - 1);
            if (in(cp, p)) level.setBlock(p, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true), 2);
        }
        for (int[] l : FLOOR_LANTERNS) {
            int fl = DryStreamCaveShape.floor(l[0], l[1]);
            if (fl == Integer.MIN_VALUE || !DryStreamCaveShape.open(l[0], l[1], fl)) continue;
            BlockPos p = f.pos(l[0], l[1], fl);
            if (in(cp, p) && level.getBlockState(p).isAir()) level.setBlock(p, Blocks.LANTERN.defaultBlockState(), 2);
        }
        int[] cup = DryStreamCaveShape.CUP;
        BlockPos cupPos = f.pos(cup[0], cup[1], cup[2]);
        if (in(cp, cupPos)) level.setBlock(cupPos, Blocks.FLOWER_POT.defaultBlockState(), 2);
        int[] pine = DryStreamCaveShape.PINE;
        BlockPos pp = f.pos(pine[0], pine[1], 0);
        if (in(cp, pp)) pine(level, cp, pp.getX(), pp.getZ(), f);
    }

    /** Lanterns left standing on the floor by the old seeker's trail and at his camp, in cave frame {d, w}. */
    private static final int[][] FLOOR_LANTERNS = {{14, 4}, {32, -4}, {44, 2}, {38, 11}, {50, 15}, {60, -3}, {65, 6}, {70, 3}};

    /** Where the old lanterns hang, in cave frame {d, w}. */
    private static final int[][] LANTERNS = {{6, 5}, {10, -5}, {38, -2}, {33, -5}, {63, 6}, {68, -3}};

    /** The pale forked pine at the mouth: a white trunk that splits in two at the height of three men. */
    private static void pine(WorldGenLevel level, ChunkPos cp, int x, int z, Frame f) {
        int g = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
        BlockState log = Blocks.STRIPPED_BIRCH_LOG.defaultBlockState();
        BlockState leaves = Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
        int[] side = f.site().rotateDir(0, 1);
        for (int i = 0; i < 5; i++) level.setBlock(new BlockPos(x, g + i, z), log, 2);
        for (int branch = -1; branch <= 1; branch += 2) {
            int bx = x + side[0] * branch, bz = z + side[1] * branch;
            for (int i = 0; i < 5; i++) {
                BlockPos p = new BlockPos(bx + side[0] * branch * (i / 2), g + 5 + i, bz + side[1] * branch * (i / 2));
                if (level.ensureCanWrite(p)) level.setBlock(p, log, 2);
            }
            int cx = bx + side[0] * branch * 2, cz = bz + side[1] * branch * 2, cy = g + 9;
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -1; dy <= 2; dy++) {
                if (Math.abs(dx) + Math.abs(dz) + Math.max(0, dy) > 3) continue;
                BlockPos p = new BlockPos(cx + dx, cy + dy, cz + dz);
                if (level.ensureCanWrite(p) && level.getBlockState(p).isAir()) level.setBlock(p, leaves, 2);
            }
        }
    }
}
