package io.github.verycooltimo.murim.world.hua;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.MangrovePropaguleBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SporeBlossomBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Takes out what the vanilla features left hanging in the air over the reshaped mountain (author 26.10: «на горе летающий бамбук, рандомные блоки
 * в воздухе»). The terrain pass of a chunk lowers or raises the ground AFTER the features of an earlier neighbour chunk have placed bamboo, vines,
 * trees and flowers on the old ground (a bamboo patch reaches 16 blocks across a chunk border), and a tree of the old ground that loses its trunk
 * keeps its crown. The cleanup pass of each chunk runs after its own terrain pass, so it sees the final ground of its columns; this runs at its end
 * and, like {@link HuaBakedExtra#pruneHangingLeaves}, takes out blocks up to {@link #REACH} blocks from the chunk and reads {@link #LOOK} blocks
 * (the 3 x 3 window a pass may use), because the trunk of a tree can be cut by the NEXT neighbour's pass, which then sweeps the part that lies in this chunk.
 *
 * <p>What goes, over the columns of the Mount Hua biome zones only and only blocks of vanilla (nothing of this mod, no persistent leaf, no pine,
 * no beam of a baked building):
 * <ul>
 *   <li>a plant, vine, bamboo, carpet or snow layer with nothing under it (air), or a vine or lichen with nothing to hold, again and again from
 *       the bottom of a stack up until none is left (bamboo of 16 blocks, a tall flower);</li>
 *   <li>a tree whose logs touch no ground: every vanilla log of the component (26-neighbourhood) that touches no solid block under it, when
 *       leaves hang on it; a log without leaves is a beam, not a tree, and stays;</li>
 *   <li>a non-persistent leaf with no grounded log within the decay distance ({@link LeavesBlock#DECAY_DISTANCE}), the vanilla rule, done now.</li>
 * </ul>
 * Cost: a column is read from the top of the highest section of its chunk (no heightmap: that of a chunk still being made can lag) down to the first block that is neither air nor such a block, a few blocks.
 * API: reference/minecraft-src/net/minecraft/world/level/chunk/ChunkAccess.java#getHighestFilledSectionIndex, LevelReader#getChunk, LevelAccessor#setBlock, block classes as imported.
 */
final class HuaFloaters {

    /** Blocks from the chunk in which blocks are taken out; the support of a leaf is looked for {@link #LOOK} blocks from the chunk. */
    static final int REACH = 10;
    static final int LOOK = REACH + LeavesBlock.DECAY_DISTANCE - 1;
    private static final int DECAY = LeavesBlock.DECAY_DISTANCE;
    private static final int SCAN_DEPTH = 96;

    /** Which columns may be swept. */
    interface Columns {
        boolean has(int x, int z);
    }

    private HuaFloaters() {
    }

    /** The pass of one chunk: sweeps the Hua columns round it. */
    static int sweep(WorldGenLevel level, ChunkPos chunk, MountHuaSite site) {
        Map<Long, Boolean> quarts = new HashMap<>();
        Columns hua = (x, z) -> quarts.computeIfAbsent(BlockPos.asLong(x >> 2, 0, z >> 2),
                k -> HuaBiomes.zoneAt(site, ((x >> 2) << 2) + 2, ((z >> 2) << 2) + 2) != HuaBiomes.Zone.NONE);
        boolean any = false;
        for (int x = chunk.getMinBlockX() - REACH; x <= chunk.getMaxBlockX() + REACH && !any; x += 4) {
            for (int z = chunk.getMinBlockZ() - REACH; z <= chunk.getMaxBlockZ() + REACH; z += 4) {
                if (hua.has(x, z)) {
                    any = true;
                    break;
                }
            }
        }
        if (!any) {
            return 0;
        }
        return sweep(level, chunk.getMinBlockX() - REACH, chunk.getMinBlockZ() - REACH, chunk.getMaxBlockX() + REACH, chunk.getMaxBlockZ() + REACH,
                chunk.getMinBlockX() - LOOK, chunk.getMinBlockZ() - LOOK, chunk.getMaxBlockX() + LOOK, chunk.getMaxBlockZ() + LOOK, hua);
    }

    // ------------------------------------------------------------------ classification

    private static boolean vanilla(BlockState state) {
        return "minecraft".equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace());
    }

    /** A vanilla tree log: not stripped (a beam of a baked house), not the spruce WOOD block of the mountain's own pines. */
    static boolean treeLog(BlockState state) {
        if (!vanilla(state) || !state.is(BlockTags.LOGS) || state.is(Blocks.SPRUCE_WOOD)) {
            return false;
        }
        return !BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().startsWith("stripped_");
    }

    /** A non-persistent vanilla leaf: what a vanilla tree is made of; the mountain's and the author's leaves are persistent. */
    static boolean treeLeaf(BlockState state) {
        return state.getBlock() instanceof LeavesBlock && vanilla(state) && !state.getValue(LeavesBlock.PERSISTENT);
    }

    /** A plant-like vanilla block that needs something under it or beside it. */
    static boolean plant(BlockState state) {
        Block b = state.getBlock();
        if (!vanilla(state) || b instanceof LiquidBlockContainer || b instanceof SporeBlossomBlock || b instanceof MangrovePropaguleBlock
                || (state.hasProperty(BlockStateProperties.HANGING) && state.getValue(BlockStateProperties.HANGING))) {
            return false;       // these hang from above: air under them is right
        }
        return b instanceof BushBlock || b instanceof VineBlock || b instanceof BambooStalkBlock || b instanceof BambooSaplingBlock
                || b instanceof SnowLayerBlock || b instanceof CarpetBlock || b instanceof MultifaceBlock || b instanceof SugarCaneBlock
                || b instanceof CactusBlock;
    }

    private static boolean floatable(BlockState state) {
        return treeLeaf(state) || treeLog(state) || plant(state);
    }

    /** A block that holds leaves in vanilla (any log, tag logs) or passes the distance on (any other leaf): it is read, never taken out here. */
    private static boolean passable(BlockState state) {
        return state.is(BlockTags.LOGS) || state.getBlock() instanceof LeavesBlock;
    }

    // ------------------------------------------------------------------ the sweep

    /**
     * Sweeps the columns of {@code inside} in the write rectangle; blocks are read in the (larger) read rectangle.
     *
     * @return the number of blocks taken out
     */
    static int sweep(LevelAccessor level, int wx0, int wz0, int wx1, int wz1, int rx0, int rz0, int rx1, int rz1, Columns inside) {
        Long2ObjectOpenHashMap<BlockState> found = new Long2ObjectOpenHashMap<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight();
        Map<Long, Integer> tops = new HashMap<>();
        for (int x = rx0; x <= rx1; x++) {
            for (int z = rz0; z <= rz1; z++) {
                // From the top of the highest section of the column's chunk (a heightmap of a chunk that is still being made can lag behind
                // what the features put there), down through air, vanilla vegetation and whatever holds or passes leaves, to the first block
                // that is none of them: the ground. Cheap: the highest section is rarely more than a few blocks above the surface.
                final int cx = x >> 4;
                final int cz = z >> 4;
                int top = tops.computeIfAbsent(BlockPos.asLong(cx, 0, cz), k -> {
                    ChunkAccess chunk = level.getChunk(cx, cz);
                    int index = chunk.getHighestFilledSectionIndex();
                    return index < 0 ? Integer.MIN_VALUE : (chunk.getSectionYFromSectionIndex(index) << 4) + 15;
                });
                if (top == Integer.MIN_VALUE) {
                    continue;
                }
                int y = top;
                for (int d = 0; d < SCAN_DEPTH && y >= minY; d++, y--) {
                    BlockState state = level.getBlockState(p.set(x, y, z));
                    if (state.isAir()) {
                        continue;
                    }
                    if (floatable(state) || passable(state)) {
                        found.put(p.asLong(), state);
                        continue;
                    }
                    break;
                }
            }
        }
        if (found.isEmpty()) {
            return 0;
        }
        LongOpenHashSet remove = new LongOpenHashSet();
        trees(level, found, remove, rx0, rz0, rx1, rz1, wx0, wz0, wx1, wz1, inside);
        int removed = 0;
        for (long key : remove) {
            removed += take(level, BlockPos.of(key), found.get(key));
        }
        removed += plants(level, found, wx0, wz0, wx1, wz1, inside);
        return removed;
    }

    private static int take(LevelAccessor level, BlockPos at, BlockState state) {
        level.setBlock(at, state.getFluidState().isEmpty() ? Blocks.AIR.defaultBlockState() : Blocks.WATER.defaultBlockState(), 2);
        return 1;
    }

    private static final int[][] NEAR = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /**
     * Trees: floating logs with leaves on them, then leaves with no log in reach. Fills {@code remove}, and only with blocks that
     * {@link #sweep} will really take out (inside the write rectangle and the swept columns), so the distance of a leaf never counts on a
     * removal that does not happen. Everything read stays inside the read rectangle: a log on its edge may belong to a tree that is rooted outside
     * it and counts as rooted.
     */
    private static void trees(LevelAccessor level, Long2ObjectOpenHashMap<BlockState> found, LongOpenHashSet remove, int rx0, int rz0, int rx1, int rz1,
            int wx0, int wz0, int wx1, int wz1, Columns inside) {
        LongOpenHashSet logs = new LongOpenHashSet();       // vanilla logs of trees: may be taken out
        LongOpenHashSet leaves = new LongOpenHashSet();     // non-persistent vanilla leaves: may be taken out
        LongOpenHashSet holders = new LongOpenHashSet();    // every log of any kind: holds a leaf next to it
        LongOpenHashSet conduits = new LongOpenHashSet();   // leaves that stay (persistent, of other mods): pass the distance on
        for (var e : found.long2ObjectEntrySet()) {
            BlockState st = e.getValue();
            if (treeLog(st)) {
                logs.add(e.getLongKey());
            } else if (treeLeaf(st)) {
                leaves.add(e.getLongKey());
            } else if (st.getBlock() instanceof LeavesBlock) {
                conduits.add(e.getLongKey());
            }
            if (st.is(BlockTags.LOGS)) {
                holders.add(e.getLongKey());
            }
        }
        // logs standing on something, or on the edge of what is read, are the roots of a tree; whatever touches them (branches go diagonally) is the tree
        LongOpenHashSet grounded = new LongOpenHashSet();
        LongArrayList queue = new LongArrayList();
        for (long k : logs) {
            BlockPos at = BlockPos.of(k);
            boolean edge = at.getX() <= rx0 || at.getX() >= rx1 || at.getZ() <= rz0 || at.getZ() >= rz1;
            BlockState below = level.getBlockState(at.below());
            // standing on a leaf (the persistent crown of one of our pines or plums that was put over the trunk's old ground) is not standing on ground
            if (edge || !below.isAir() && !floatable(below) && !(below.getBlock() instanceof LeavesBlock)) {
                grounded.add(k);
                queue.add(k);
            }
        }
        spread(logs, grounded, queue);
        // the rest: a floating tree when leaves hang on the component, else a beam (no leaves on it: a house's log)
        LongOpenHashSet seen = new LongOpenHashSet();
        for (long start : logs) {
            if (grounded.contains(start) || seen.contains(start)) {
                continue;
            }
            LongOpenHashSet part = new LongOpenHashSet();
            LongArrayList q = new LongArrayList();
            part.add(start);
            q.add(start);
            spread(logs, part, q);
            seen.addAll(part);
            boolean leafy = false;
            for (long k : part) {
                BlockPos at = BlockPos.of(k);
                for (int[] d : NEAR) {
                    if (leaves.contains(at.offset(d[0], d[1], d[2]).asLong())) {
                        leafy = true;
                    }
                }
            }
            if (leafy) {
                for (long k : part) {
                    BlockPos at = BlockPos.of(k);
                    if (at.getX() >= wx0 && at.getX() <= wx1 && at.getZ() >= wz0 && at.getZ() <= wz1 && inside.has(at.getX(), at.getZ())) {
                        remove.add(k);
                    }
                }
            }
        }
        // leaves: vanilla distance from the logs that stay, over leaves of every kind; 1 next to a log, 7 decays
        Long2ByteOpenHashMap dist = new Long2ByteOpenHashMap();
        LongArrayList wave = new LongArrayList();
        LongOpenHashSet[] kinds = {leaves, conduits};
        for (LongOpenHashSet kind : kinds) {
            for (long k : kind) {
                BlockPos at = BlockPos.of(k);
                for (int[] d : NEAR) {
                    long nk = at.offset(d[0], d[1], d[2]).asLong();
                    if (holders.contains(nk) && !remove.contains(nk)) {
                        dist.put(k, (byte) 1);
                        wave.add(k);
                        break;
                    }
                }
            }
        }
        for (int i = 0; i < wave.size(); i++) {
            long k = wave.getLong(i);
            int v = dist.get(k);
            if (v >= DECAY - 1) {
                continue;
            }
            BlockPos at = BlockPos.of(k);
            for (int[] d : NEAR) {
                long nk = at.offset(d[0], d[1], d[2]).asLong();
                if ((leaves.contains(nk) || conduits.contains(nk)) && !dist.containsKey(nk)) {
                    dist.put(nk, (byte) (v + 1));
                    wave.add(nk);
                }
            }
        }
        for (long k : leaves) {
            BlockPos at = BlockPos.of(k);
            if (!dist.containsKey(k) && at.getX() >= wx0 && at.getX() <= wx1 && at.getZ() >= wz0 && at.getZ() <= wz1 && inside.has(at.getX(), at.getZ())) {
                remove.add(k);
            }
        }
    }

    /** Grows {@code set} from {@code queue} through the logs of {@code logs} (26-neighbourhood). */
    private static void spread(LongOpenHashSet logs, LongOpenHashSet set, LongArrayList queue) {
        for (int i = 0; i < queue.size(); i++) {
            BlockPos at = BlockPos.of(queue.getLong(i));
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long nk = at.offset(dx, dy, dz).asLong();
                        if ((dx != 0 || dy != 0 || dz != 0) && logs.contains(nk) && set.add(nk)) {
                            queue.add(nk);
                        }
                    }
                }
            }
        }
    }

    /**
     * Plants, from the bottom up and again until nothing changes: one with air under it goes (and the next one of a bamboo stack or a tall
     * flower loses its ground by that), a vine or lichen goes when it can no longer hold ({@code canSurvive} of the block itself).
     */
    private static int plants(LevelAccessor level, Long2ObjectOpenHashMap<BlockState> found, int wx0, int wz0, int wx1, int wz1, Columns inside) {
        List<BlockPos> list = new ArrayList<>();
        for (long key : found.keySet()) {
            BlockPos at = BlockPos.of(key);
            if (plant(found.get(key)) && at.getX() >= wx0 && at.getX() <= wx1 && at.getZ() >= wz0 && at.getZ() <= wz1 && inside.has(at.getX(), at.getZ())) {
                list.add(at);
            }
        }
        list.sort((a, b) -> Integer.compare(a.getY(), b.getY()));
        int removed = 0;
        for (int pass = 0; pass < 64; pass++) {
            int changed = 0;
            for (int i = pass % 2 == 0 ? 0 : list.size() - 1; i >= 0 && i < list.size(); i += pass % 2 == 0 ? 1 : -1) {
                BlockPos at = list.get(i);
                BlockState state = level.getBlockState(at);
                if (!plant(state)) {
                    continue;
                }
                boolean holds;
                if (state.getBlock() instanceof VineBlock || state.getBlock() instanceof MultifaceBlock) {
                    holds = state.canSurvive(level, at);
                } else {
                    holds = !level.getBlockState(at.below()).isAir();
                }
                if (!holds) {
                    removed += take(level, at, state);
                    changed++;
                }
            }
            if (changed == 0) {
                break;
            }
        }
        return removed;
    }
}
