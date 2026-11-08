package io.github.verycooltimo.murim.world.hua;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.verycooltimo.murim.MurimMod;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Sparse pieces of the baked mountain that sit beside the sect templates ({@link HuaBaked}, docs/adr/0021-baked-trail-and-foot-lake.md):
 * <ul>
 *   <li>{@code trail}: what the author built between the foot of the mountain and the sect approach and the lake that replaced the gate
 *       terrace (25.10), as the difference to the generated mountain: only the changed blocks, air included where he took something away;</li>
 *   <li>{@code dorm_stair}: the spruce staircase between the dorms and the training square, which the sect templates lost in the props
 *       patch of 22.10.</li>
 * </ul>
 * The index is {@code hua_baked/extra/index.json}, written by {@code tools/hua_bake/bake_extra.py}; the cells have the shape of the sect's
 * ones (relative to the site centre in the mountain's frame, turned with the site, base-mode height shift), so {@link HuaBaked#placeCell}
 * stamps them. They are NOT part of the sect index: {@link HuaBaked#expectedAt} and {@link HuaTrailMend} keep reading the sect alone, and
 * the pieces go in AFTER every other pass of the chunk (mend, vault, stele, herbs), so the last word over a block is the author's.
 * Same 3 x 3 chunk window as the sect stamp (the features of a neighbour chunk write into this one after it was stamped).
 *
 * <p><b>Hanging crowns.</b> A vanilla tree of a neighbour chunk can drop its crown next to a piece whose trunk the mountain cut: leaves with
 * no log in reach hang in the air until the first random ticks of the world (the lake of the foot showed it). After the stamp,
 * {@link #pruneHangingLeaves} takes out, round the pieces, every vanilla leaf that is not persistent and has no log within the decay
 * distance of {@link LeavesBlock#DECAY_DISTANCE}, with the vines that hung on them: what vanilla would do in a few minutes, done in generation.
 * Persistent leaves (the mountain's pines and azaleas, the author's plum trees) are never touched, nor is any block of another mod.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class HuaBakedExtra {

    static final String INDEX = "hua_baked/extra/index.json";
    private static final Gson GSON = new Gson();

    private record Cache(MinecraftServer server, Optional<HuaBaked.Index> index) {
    }

    private static volatile Cache cache;

    private HuaBakedExtra() {
    }

    /** The extra index of the running server's resources, or null if the mod ships none. */
    public static HuaBaked.Index index(MinecraftServer server) {
        Cache c = cache;
        if (c == null || c.server() != server) {
            c = new Cache(server, Optional.ofNullable(load(server)));
            cache = c;
        }
        return c.index().orElse(null);
    }

    private static HuaBaked.Index load(MinecraftServer server) {
        ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, INDEX);
        try {
            Optional<Resource> res = server.getResourceManager().getResource(rl);
            if (res.isEmpty()) {
                return null;
            }
            try (Reader r = res.get().openAsReader()) {
                return HuaBaked.parse(GSON.fromJson(r, JsonObject.class));
            }
        } catch (IOException | RuntimeException e) {
            MurimMod.LOGGER.error("Baked Mount Hua extras: broken index", e);
            return null;
        }
    }

    /**
     * Worldgen entry (cleanup pass of the mountain feature, after every block pass): this chunk's 3 x 3 window of the extra pieces.
     *
     * @return false if the site is not a canonical one or the mod ships no extras
     */
    public static boolean apply(WorldGenLevel level, ChunkPos chunk, MountHuaSite site) {
        MinecraftServer server = level.getServer();
        if (server == null || !site.canonical()) {
            return false;
        }
        HuaBaked.Index index = index(server);
        if (index == null) {
            return false;
        }
        BoundingBox clip = new BoundingBox(chunk.getMinBlockX() - 16, level.getMinBuildHeight(), chunk.getMinBlockZ() - 16,
                chunk.getMaxBlockX() + 16, level.getMaxBuildHeight() - 1, chunk.getMaxBlockZ() + 16);
        // flags 2: client update only, no neighbour updates during generation (as the sect stamp does)
        place(server, level, site, index, clip, level.getRandom(), 2);
        pruneHangingLeaves(level, chunk, site, index);
        return true;
    }

    /** Blocks round a piece's cells in which hanging leaves are taken out. */
    static final int MARGIN = 12;
    /** How far from the chunk a pass takes leaves out: the support of a leaf is looked for {@link #DECAY} blocks further, inside the 3 x 3 window. */
    static final int REACH = 10;
    static final int LOOK = REACH + LeavesBlock.DECAY_DISTANCE - 1;
    private static final int DECAY = LeavesBlock.DECAY_DISTANCE;

    /**
     * The last step of this chunk's pass: hanging vanilla crowns round the pieces go. Takes leaves out up to {@link #REACH} blocks from this
     * chunk (every block lies that close to the chunk of its own pass, and the leaves of a tree of that chunk spill about eight blocks), and
     * reads logs up to {@link #LOOK} blocks away, which is inside the 3 x 3 chunk window the pass may use.
     *
     * @return the number of blocks taken out
     */
    static int pruneHangingLeaves(WorldGenLevel level, ChunkPos chunk, MountHuaSite site, HuaBaked.Index index) {
        int wx0 = chunk.getMinBlockX() - REACH;
        int wx1 = chunk.getMaxBlockX() + REACH;
        int wz0 = chunk.getMinBlockZ() - REACH;
        int wz1 = chunk.getMaxBlockZ() + REACH;
        List<int[]> foot = new ArrayList<>();
        int ylo = Integer.MAX_VALUE;
        int yhi = Integer.MIN_VALUE;
        for (var e : HuaBaked.cells(index)) {
            BoundingBox b = HuaBaked.cellBox(index, e.getKey(), e.getValue(), site);
            int[] r = {b.minX() - MARGIN, b.minZ() - MARGIN, b.maxX() + MARGIN, b.maxZ() + MARGIN};
            if (r[2] < wx0 || r[0] > wx1 || r[3] < wz0 || r[1] > wz1) {
                continue;
            }
            foot.add(r);
            ylo = Math.min(ylo, b.minY());
            yhi = Math.max(yhi, b.maxY());
        }
        if (foot.isEmpty()) {
            return 0;
        }
        ylo = Math.max(level.getMinBuildHeight(), ylo - 12);
        yhi = Math.min(level.getMaxBuildHeight() - 1, yhi + 70);
        BoundingBox write = new BoundingBox(wx0, ylo, wz0, wx1, yhi, wz1);
        BoundingBox read = new BoundingBox(chunk.getMinBlockX() - LOOK, ylo, chunk.getMinBlockZ() - LOOK, chunk.getMaxBlockX() + LOOK, yhi,
                chunk.getMaxBlockZ() + LOOK);
        return prune(level, write, read, foot);
    }

    private static boolean inFoot(List<int[]> foot, int x, int z) {
        for (int[] r : foot) {
            if (x >= r[0] && x <= r[2] && z >= r[1] && z <= r[3]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Takes out, inside {@code write} and the {@code foot} rectangles (minX, minZ, maxX, maxZ), every vanilla leaf that is not persistent and has no
     * log within the decay distance (logs and leaves are read in {@code read}, which must hold {@code write} and 6 blocks round it where the
     * level has chunks), then the vines that no longer have a wall or a vine to hang on. Distance as vanilla: a log is 0, a leaf next to a
     * leaf of distance d is d + 1, 7 decays ({@code LeavesBlock#getDistanceAt}).
     * API: reference/minecraft-src/net/minecraft/world/level/block/LeavesBlock.java#getDistanceAt, #decaying.
     */
    static int prune(LevelAccessor level, BoundingBox write, BoundingBox read, List<int[]> foot) {
        int sx = read.getXSpan();
        int sy = read.getYSpan();
        int sz = read.getZSpan();
        BlockState[] states = new BlockState[sx * sy * sz];
        byte[] dist = new byte[states.length];
        int[] queue = new int[states.length];
        int head = 0;
        int tail = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int i = (y * sz + z) * sx + x;
                    BlockState state = level.getBlockState(pos.set(read.minX() + x, read.minY() + y, read.minZ() + z));
                    states[i] = state;
                    if (state.is(BlockTags.LOGS)) {
                        dist[i] = 1;          // stored value = distance + 1; 0 means not reached
                        queue[tail++] = i;
                    }
                }
            }
        }
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (head < tail) {
            int i = queue[head++];
            int v = dist[i];
            if (v >= DECAY) {
                continue;
            }
            int x = i % sx;
            int z = (i / sx) % sz;
            int y = i / (sx * sz);
            for (int[] d : around) {
                int nx = x + d[0];
                int ny = y + d[1];
                int nz = z + d[2];
                if (nx < 0 || ny < 0 || nz < 0 || nx >= sx || ny >= sy || nz >= sz) {
                    continue;
                }
                int j = (ny * sz + nz) * sx + nx;
                if (dist[j] == 0 && states[j].getBlock() instanceof LeavesBlock) {
                    dist[j] = (byte) (v + 1);
                    queue[tail++] = j;
                }
            }
        }
        int removed = 0;
        List<BlockPos> vines = new ArrayList<>();
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int wxp = read.minX() + x;
                    int wyp = read.minY() + y;
                    int wzp = read.minZ() + z;
                    if (!write.isInside(wxp, wyp, wzp) || !inFoot(foot, wxp, wzp)) {
                        continue;
                    }
                    int i = (y * sz + z) * sx + x;
                    BlockState state = states[i];
                    if (state.is(Blocks.VINE)) {
                        vines.add(new BlockPos(wxp, wyp, wzp));
                    } else if (state.getBlock() instanceof LeavesBlock && dist[i] == 0 && !state.getValue(LeavesBlock.PERSISTENT)
                            && "minecraft".equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace())) {
                        BlockPos at = new BlockPos(wxp, wyp, wzp);
                        HuaStampBlocks.set(level, at, state.getValue(LeavesBlock.WATERLOGGED) ? Blocks.WATER.defaultBlockState()
                                : Blocks.AIR.defaultBlockState(), 2);
                        removed++;
                    }
                }
            }
        }
        // vines hang from walls and from vines above them: from the top down, until none is left without a hold
        vines.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
        for (int pass = 0; pass < 16; pass++) {
            int changed = 0;
            for (BlockPos at : vines) {
                BlockState vine = level.getBlockState(at);
                if (vine.is(Blocks.VINE) && !vine.canSurvive(level, at)) {
                    HuaStampBlocks.set(level, at, Blocks.AIR.defaultBlockState(), 2);
                    changed++;
                }
            }
            removed += changed;
            if (changed == 0) {
                break;
            }
        }
        return removed;
    }

    /** Every cell that meets {@code clip} (null: all), for the worldgen pass and for tests. Returns the number of cells placed. */
    public static int place(MinecraftServer server, net.minecraft.world.level.ServerLevelAccessor level, MountHuaSite site,
            HuaBaked.Index index, BoundingBox clip, net.minecraft.util.RandomSource random, int flags) {
        int placed = 0;
        for (var e : HuaBaked.cells(index)) {
            if (HuaBaked.placeCell(server, level, site, index, e.getKey(), e.getValue(), clip, random, flags)) {
                placed++;
            }
        }
        return placed;
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        Cache c = cache;
        if (c != null && c.server() == event.getServer()) {
            cache = null;
        }
    }
}
