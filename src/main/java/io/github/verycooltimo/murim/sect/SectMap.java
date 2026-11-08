package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the tablet's sect map from the real world (doc 39): per block column the {@link MapColor} of the highest block
 * that has one, shaded by the height step to the column north of it like a vanilla map, one pixel per block, in world
 * axes with north up. The picture lives in {@link SectMapData} (saved with the world) and is kept up to date
 * incrementally: chunks are sampled once they are loaded, a block change marks its chunk dirty, a few chunks are
 * processed per run, and a slow rotating rescan catches changes nobody announced.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/item/MapItem.java#update (the sampling and shading rules),
 * reference/neoforge-src/net/neoforged/neoforge/event/level/BlockEvent.java#NeighborNotifyEvent.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectMap {
    /** The map is at least this big (blocks); a larger layout widens it. 180 x 136 fits the tablet's panel at 5:4. */
    public static final int MIN_W = 180;
    public static final int MIN_H = 136;
    /** Room around the buildings, in blocks (both sides together). */
    public static final int MARGIN = 12;
    /** Never bigger than this: the wire format and the texture stay small. */
    public static final int MAX = 256;
    /** Chunks sampled per run (a run is every {@link #PERIOD} ticks). */
    static final int PER_RUN = 3;
    static final int PERIOD = 4;
    /** A new version is published at most this often, so that digging does not flood the watchers. */
    static final int PUBLISH_EVERY = 40;
    /** One chunk of the area is sampled again every this many runs, whether or not anything announced a change. */
    static final int RESCAN_EVERY = 10;

    private SectMap() { }

    /** The map's box in world blocks. */
    public record Area(int x0, int z0, int w, int h) {
        public boolean contains(int x, int z) {
            return x >= x0 && x < x0 + w && z >= z0 && z < z0 + h;
        }
    }

    /** A named building on the map: the key of its name and its footprint in world blocks (inclusive). */
    public record Label(String key, int x0, int z0, int x1, int z1, List<SectLayoutFile.Rect> parts) {
        public float centreX() {
            return (x0 + x1 + 1) / 2.0F;
        }

        public float centreZ() {
            return (z0 + z1 + 1) / 2.0F;
        }
    }

    static int chunk(int block) {
        return Math.floorDiv(block, 16);
    }

    // ------------------------------------------------------------------ the layout

    /** The map's box: the buildings' union plus the margin, centred, never smaller than the minimum. Null: no buildings. */
    public static Area areaFor(SectLayoutFile file) {
        if (file == null) return null;
        int x0 = Integer.MAX_VALUE;
        int z0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE;
        int z1 = Integer.MIN_VALUE;
        for (SectLayoutFile.Building b : file.buildings().values()) {
            for (SectLayoutFile.Rect r : b.boxes()) {
                x0 = Math.min(x0, r.x0());
                z0 = Math.min(z0, r.z0());
                x1 = Math.max(x1, r.x1());
                z1 = Math.max(z1, r.z1());
            }
        }
        if (x0 > x1) return null;
        int w = Math.min(MAX, Math.max(MIN_W, x1 - x0 + 1 + MARGIN));
        int h = Math.min(MAX, Math.max(MIN_H, z1 - z0 + 1 + MARGIN));
        int cx = (x0 + x1 + 1) / 2;
        int cz = (z0 + z1 + 1) / 2;
        return new Area(cx - w / 2, cz - h / 2, w, h);
    }

    /**
     * The buildings that get a name plaque, from the layout file: one label per building, the three elders' houses as one.
     * The key is {@code murim.tablet.place.<key>}; the client skips a key it has no text for.
     */
    public static List<Label> labels(SectLayoutFile file) {
        Map<String, int[]> out = new LinkedHashMap<>();
        Map<String, List<SectLayoutFile.Rect>> parts = new LinkedHashMap<>();
        if (file == null) return List.of();
        for (SectLayoutFile.Building b : file.buildings().values()) {
            String key = b.id().matches("elder_\\d+") ? "elders" : b.id();
            for (SectLayoutFile.Rect r : b.boxes()) {
                int[] u = out.computeIfAbsent(key, k -> new int[] {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE});
                u[0] = Math.min(u[0], r.x0());
                u[1] = Math.min(u[1], r.z0());
                u[2] = Math.max(u[2], r.x1());
                u[3] = Math.max(u[3], r.z1());
                parts.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
            }
        }
        List<Label> labels = new ArrayList<>();
        out.forEach((key, u) -> labels.add(new Label(key, u[0], u[1], u[2], u[3], List.copyOf(parts.get(key)))));
        return labels;
    }

    /**
     * The layout file the map, the board and the tablet are drawn from. A world from before the canonical bake has NO built sect
     * ({@link SectLayout#file()} is null on purpose), so there is no fallback to the baked coordinates: they would put a board, a
     * map and building names over empty terrain.
     */
    public static SectLayoutFile fileOf(SectLayout layout) {
        return layout == null ? null : layout.file();
    }

    // ------------------------------------------------------------------ data access

    /** The map's saved data; the sect lives in the overworld only. */
    public static SectMapData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(SectMapData.FACTORY, SectMapData.NAME);
    }

    // ------------------------------------------------------------------ sampling

    /** What one block column looks like from above. */
    record Column(int id, int height, int depth) { }

    /** The vanilla map's sampling of one column (scale 1): the highest block that has a colour, water by its depth. */
    static Column sample(ServerLevel level, LevelChunk chunk, int x, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, 0, z);
        int min = level.getMinBuildHeight();
        int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1;
        BlockState state;
        int depth = 0;
        if (y <= min + 1) {
            state = Blocks.BEDROCK.defaultBlockState();
        } else {
            do {
                pos.setY(--y);
                state = chunk.getBlockState(pos);
            } while (state.getMapColor(level, pos) == MapColor.NONE && y > min);
            if (y > min && !state.getFluidState().isEmpty()) {
                int below = y - 1;
                BlockPos.MutableBlockPos under = new BlockPos.MutableBlockPos(x, 0, z);
                BlockState lower;
                do {
                    under.setY(below--);
                    lower = chunk.getBlockState(under);
                    depth++;
                } while (below > min && !lower.getFluidState().isEmpty());
                // a fluid over a solid face shows the block, otherwise the fluid itself
                if (!state.isFaceSturdy(level, pos, Direction.UP)) state = state.getFluidState().createLegacyBlock();
            }
        }
        return new Column(state.getMapColor(level, pos).id, y, Math.min(127, depth));
    }

    /** The shade of a column from its height step to the column north of it (vanilla: scale 1 divides by 5). */
    static MapColor.Brightness brightness(int x, int z, int id, int height, int northHeight, int depth) {
        boolean odd = ((x + z) & 1) != 0;
        if (MapColor.byId(id) == MapColor.WATER) {
            double d = depth * 0.1 + (odd ? 0.2 : 0.0);
            return d < 0.5 ? MapColor.Brightness.HIGH : d > 0.9 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
        }
        double d = (height - northHeight) * 4.0 / 5.0 + ((odd ? 1 : 0) - 0.5) * 0.4;
        return d > 0.6 ? MapColor.Brightness.HIGH : d < -0.6 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
    }

    /** Derives the shaded colour of one column from the saved ids and heights. */
    static byte shade(SectMapData d, int ix, int iz) {
        int i = iz * d.w + ix;
        int id = d.ids[i] & 0xFF;
        if (id == 0) return 0;
        int north = iz > 0 && (d.ids[i - d.w] & 0xFF) != 0 ? d.heights[i - d.w] : d.heights[i];
        return MapColor.byId(id).getPackedId(brightness(d.x0 + ix, d.z0 + iz, id, d.heights[i], north, d.depth[i]));
    }

    static void shadeAll(SectMapData d) {
        for (int z = 0; z < d.h; z++) {
            for (int x = 0; x < d.w; x++) d.colours[z * d.w + x] = shade(d, x, z);
        }
    }

    // ------------------------------------------------------------------ chunks

    private static int chunkIndex(SectMapData d, int cx, int cz) {
        int cw = chunk(d.x0 + d.w - 1) - chunk(d.x0) + 1;
        return (cz - chunk(d.z0)) * cw + (cx - chunk(d.x0));
    }

    /** Samples the area's columns of one loaded chunk. Returns false when the chunk is not loaded. */
    static boolean rebuildChunk(ServerLevel level, SectMapData d, int cx, int cz) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) return false;
        int xa = Math.max(d.x0, cx * 16);
        int xb = Math.min(d.x0 + d.w - 1, cx * 16 + 15);
        int za = Math.max(d.z0, cz * 16);
        int zb = Math.min(d.z0 + d.h - 1, cz * 16 + 15);
        boolean any = false;
        for (int z = za; z <= zb; z++) {
            for (int x = xa; x <= xb; x++) {
                Column c = sample(level, chunk, x, z);
                int i = (z - d.z0) * d.w + (x - d.x0);
                byte id = (byte) c.id();
                if (d.ids[i] != id || d.heights[i] != (short) c.height() || d.depth[i] != (byte) c.depth()) {
                    d.ids[i] = id;
                    d.heights[i] = (short) c.height();
                    d.depth[i] = (byte) c.depth();
                    any = true;
                }
            }
        }
        // the row south of the chunk is shaded against this one's last row
        int zEnd = Math.min(d.z0 + d.h - 1, zb + 1);
        for (int z = za; z <= zEnd; z++) {
            for (int x = xa; x <= xb; x++) {
                int i = (z - d.z0) * d.w + (x - d.x0);
                byte c = shade(d, x - d.x0, z - d.z0);
                if (d.colours[i] != c) {
                    d.colours[i] = c;
                    any = true;
                }
            }
        }
        d.built[chunkIndex(d, cx, cz)] = true;
        if (any) d.changed = true;
        return true;
    }

    /** A block changed at {@code x, z}: its chunk is sampled again soon. */
    static void markDirty(SectMapData d, int x, int z) {
        if (d.hasArea() && d.area().contains(x, z)) d.dirty.add(ChunkPos.asLong(chunk(x), chunk(z)));
    }

    // ------------------------------------------------------------------ the run

    /**
     * One run: samples up to {@link #PER_RUN} chunks (changed ones first, then the ones never sampled, now and then one
     * for the slow rescan) and publishes a new version when something changed. Returns how many chunks were sampled.
     */
    static int step(ServerLevel level, SectMapData d, int tick, int runIndex) {
        if (!d.hasArea()) return 0;
        int done = 0;
        for (Iterator<Long> it = d.dirty.iterator(); it.hasNext() && done < PER_RUN; ) {
            long key = it.next();
            if (rebuildChunk(level, d, ChunkPos.getX(key), ChunkPos.getZ(key))) {
                it.remove();
                done++;
            }
        }
        int cx0 = chunk(d.x0);
        int cz0 = chunk(d.z0);
        int cw = chunk(d.x0 + d.w - 1) - cx0 + 1;
        int ch = chunk(d.z0 + d.h - 1) - cz0 + 1;
        for (int i = 0; i < d.built.length && done < PER_RUN; i++) {
            if (!d.built[i] && rebuildChunk(level, d, cx0 + i % cw, cz0 + i / cw)) done++;
        }
        if (done == 0 && runIndex % RESCAN_EVERY == 0) {
            d.rescan = (d.rescan + 1) % (cw * ch);
            if (rebuildChunk(level, d, cx0 + d.rescan % cw, cz0 + d.rescan / cw)) done++;
        }
        if (d.changed && tick - d.lastPublishTick >= PUBLISH_EVERY) d.publish(tick);
        return done;
    }

    /** Samples every loaded chunk of the area at once and publishes (tests, the admin command). */
    public static int rebuildAll(ServerLevel level, SectMapData d, int tick) {
        int n = 0;
        int cx0 = chunk(d.x0);
        int cz0 = chunk(d.z0);
        int cw = chunk(d.x0 + d.w - 1) - cx0 + 1;
        int ch = chunk(d.z0 + d.h - 1) - cz0 + 1;
        for (int i = 0; i < cw * ch; i++) {
            if (rebuildChunk(level, d, cx0 + i % cw, cz0 + i / cw)) n++;
        }
        if (d.changed) d.publish(tick);
        return n;
    }

    // ------------------------------------------------------------------ events

    @SubscribeEvent
    static void serverTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % PERIOD != 0) return;
        ServerLevel level = server.overworld();
        Area area = areaFor(fileOf(SectLife.layout(level)));
        if (area == null) return;
        SectMapData d = data(server);
        if (!area.equals(d.area())) {
            MurimMod.LOGGER.info("Sect map: area {}x{} from x={} z={}", area.w(), area.h(), area.x0(), area.z0());
            d.configure(area);
        }
        step(level, d, server.getTickCount(), server.getTickCount() / PERIOD);
    }

    @SubscribeEvent
    static void neighbourNotified(BlockEvent.NeighborNotifyEvent event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            // a lookup in the world's data storage; only when the map has an area at all
            SectMapData d = level.getDataStorage().get(SectMapData.FACTORY, SectMapData.NAME);
            if (d != null) markDirty(d, event.getPos().getX(), event.getPos().getZ());
        }
    }
}
