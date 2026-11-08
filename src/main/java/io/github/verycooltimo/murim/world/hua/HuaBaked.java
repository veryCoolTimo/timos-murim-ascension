package io.github.verycooltimo.murim.world.hua;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.verycooltimo.murim.MurimMod;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * The author's hand-built Mount Hua sect, baked into the mod (docs/adr/0004-baked-hua-sect.md): terrain, buildings,
 * interiors, block entities. {@code tools/hua_bake/bake_hua.py} cuts a finished world into one vanilla structure
 * template per chunk of that world; each template's min corner is stored as a block offset (u, v) from the site
 * centre in the mountain's local frame (rotation 0), its y relative to the bake world's foot height.
 *
 * <p><b>Placement.</b> Mount Hua's cleanup pass ({@link MountHuaFeature}) stamps every cell that meets the chunk,
 * clipped to the chunk, after the procedural mountain and the vanilla features have run. A cell is turned about the
 * site centre block by the site's quarter turns with the same integer rule as
 * {@code SectLayoutFile.Anchor#map} (block (u, v) goes to {@code centre + site.rotateDir(u, v)}), so the sect AI
 * layout lands on the same blocks. Heights: mode {@code base} shifts by {@code site.baseY() - bakeBaseY} (the layout
 * does the same); mode {@code nominal} (peak pavilions) follows the plan height {@link MountHuaSite#worldY} of its
 * reference level, because the summit does not move with the foot.
 *
 * <p><b>Seams.</b> The baked terrain equals the procedural terrain of the same shape seed
 * ({@link #CANONICAL_SEED}, used by every site chosen after this feature; see {@link MountHuaSiteData}), so the
 * mountain meets the stamp without a step except for the foot-height drift of the plan.
 *
 * <p>Thread safety: runs on chunk-generation threads. The index is an immutable record cached per server instance;
 * templates are read-only after loading and kept in a small concurrent cache.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate.java#placeInWorld
 * (block position = offset + rotation about (0, 0, 0); clip by the settings' box; block entities loaded from the nbt),
 * StructureTemplateManager.java#readStructure (data fixer), reference/minecraft-src/net/minecraft/nbt/NbtIo.java#readCompressed.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class HuaBaked {

    /**
     * Shape seed of every site chosen after the baked sect existed: the hand-built sect was made on this seed's
     * mountain, so only this mountain fits it. Older worlds keep their world seed ({@link MountHuaSiteData}).
     */
    public static final long CANONICAL_SEED = -7243345755280614511L;

    /**
     * Foot height of the world the sect was baked in ({@code frame.base_y} of the index). A canonical mountain keeps the scale of that
     * foot and is never put higher than it: the baked columns are shifted by the foot difference as a rigid piece, so on a higher
     * foot the procedural mountain around them (scaled to the summit) would no longer meet them, and the part of the sect above
     * the build height would be cut off (docs/adr/0018).
     */
    public static final int BAKE_BASE_Y = 67;
    /** Lowest foot height a new site may get (the sect shifts down with it). */
    public static final int MIN_BASE_Y = 63;

    static final String INDEX = "hua_baked/index.json";
    private static final Gson GSON = new Gson();
    private static final int TEMPLATE_CACHE = 48;

    /** One baked chunk: template file, the local (u, v) of its min corner, bake-world y of its plane y = 0 and its height. */
    public record Cell(String file, int u, int v, int y, int h) {
    }

    /** A rigid piece: all cells share one height rule ({@code base} or {@code nominal} at plan height {@code ref}). */
    public record Group(String id, String mode, int ref, List<Cell> cells) {
    }

    public record Index(int bakeBaseY, int dataVersion, List<Group> groups) {
    }

    private record Cache(MinecraftServer server, Optional<Index> index, Map<String, StructureTemplate> templates,
            java.util.concurrent.atomic.AtomicInteger limit, Map<String, Grid> grids) {
    }

    private static volatile Cache cache;

    private HuaBaked() {
    }

    /** The baked index of the running server's resources, or null if the mod ships none. */
    public static Index index(MinecraftServer server) {
        return cache(server).index().orElse(null);
    }

    private static Cache cache(MinecraftServer server) {
        Cache c = cache;
        if (c == null || c.server() != server) {
            c = new Cache(server, Optional.ofNullable(load(server)), new ConcurrentHashMap<>(),
                    new java.util.concurrent.atomic.AtomicInteger(TEMPLATE_CACHE), new ConcurrentHashMap<>());
            cache = c;
        }
        return c;
    }

    private static Index load(MinecraftServer server) {
        ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, INDEX);
        try {
            Optional<Resource> res = server.getResourceManager().getResource(rl);
            if (res.isEmpty()) {
                return null;
            }
            try (Reader r = res.get().openAsReader()) {
                return parse(GSON.fromJson(r, JsonObject.class));
            }
        } catch (IOException | RuntimeException e) {
            MurimMod.LOGGER.error("Baked Mount Hua sect: broken index", e);
            return null;
        }
    }

    static Index parse(JsonObject o) {
        List<Group> groups = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("groups")) {
            JsonObject g = e.getAsJsonObject();
            List<Cell> cells = new ArrayList<>();
            JsonArray arr = g.getAsJsonArray("cells");
            for (JsonElement c : arr) {
                JsonObject j = c.getAsJsonObject();
                cells.add(new Cell(j.get("file").getAsString(), j.get("u").getAsInt(), j.get("v").getAsInt(),
                        j.get("y").getAsInt(), j.get("h").getAsInt()));
            }
            groups.add(new Group(g.get("id").getAsString(), g.get("mode").getAsString(), g.get("ref").getAsInt(),
                    List.copyOf(cells)));
        }
        JsonObject frame = o.getAsJsonObject("frame");
        if (frame.get("rotation").getAsInt() != 0) {
            throw new IllegalStateException("baked sect must be cut from a rotation-0 world");
        }
        if (frame.get("base_y").getAsInt() != BAKE_BASE_Y) {
            throw new IllegalStateException("baked sect foot height changed: update HuaBaked.BAKE_BASE_Y");
        }
        return new Index(frame.get("base_y").getAsInt(), o.get("data_version").getAsInt(), List.copyOf(groups));
    }

    /** True if worldgen should stamp the baked sect on this site: the files exist and the mountain is the canonical one. */
    public static boolean applies(MinecraftServer server, MountHuaSite site) {
        return site.canonical() && server != null && index(server) != null;
    }

    /**
     * Height shift of a group: world y = bake-world y + shift. {@code base}: the foot-height difference, as
     * {@code SectLayoutFile.Anchor#map} does for the layout. {@code nominal}: the plan height of the group's reference
     * level, so a peak pavilion follows the summit that does not move with the foot.
     */
    public static int shift(Index index, Group group, MountHuaSite site) {
        if (group.mode().equals("nominal")) {
            // the bake world scaled its plan heights by its own foot height too
            int bake = (int) Math.round(MountHuaSite.placement(0, 0, index.bakeBaseY(), 0).worldY(group.ref()));
            return (int) Math.round(site.worldY(group.ref())) - bake;
        }
        return site.baseY() - index.bakeBaseY();
    }

    /** World position of a cell's local (0, 0, 0) block; the template turns about it. */
    public static BlockPos cellOrigin(Index index, Group group, Cell cell, MountHuaSite site) {
        int[] d = site.rotateDir(cell.u(), cell.v());
        return new BlockPos(site.centerX() + d[0], cell.y() + shift(index, group, site), site.centerZ() + d[1]);
    }

    /** World box of one cell. */
    public static BoundingBox cellBox(Index index, Group group, Cell cell, MountHuaSite site) {
        BlockPos o = cellOrigin(index, group, cell, site);
        int[] far = site.rotateDir(15, 15);
        return BoundingBox.fromCorners(o, new BlockPos(o.getX() + far[0], o.getY() + cell.h() - 1, o.getZ() + far[1]));
    }

    /**
     * Worldgen entry (cleanup pass of the mountain feature): this chunk's share of the baked sect.
     *
     * @return false if the site is not a canonical one or the mod ships no baked files (the caller falls back)
     */
    static boolean apply(WorldGenLevel level, ChunkPos chunk, MountHuaSite site) {
        MinecraftServer server = level.getServer();
        if (!applies(server, site)) {
            return false;
        }
        // The 3 x 3 chunks around this one, not this chunk alone: the vanilla features of this chunk (trees, ores, vines, lakes) write up
        // to one chunk to every side, after the neighbours were stamped, and neighbour updates they cause break the stamped blocks that need
        // support (climbing moss, handholds, blossoms next to a feature's block). This pass runs after all of them, so it puts the baked
        // blocks back; the last pass over a block is that of the last chunk whose features can reach it (docs/adr/0018).
        BoundingBox clip = new BoundingBox(chunk.getMinBlockX() - 16, level.getMinBuildHeight(), chunk.getMinBlockZ() - 16,
                chunk.getMaxBlockX() + 16, level.getMaxBuildHeight() - 1, chunk.getMaxBlockZ() + 16);
        // flags 2: client update only, no neighbour updates during generation (as TemplateStructurePiece does)
        place(server, level, site, clip, level.getRandom(), 2);
        return true;
    }

    /**
     * Loads every template and keeps all of them (a few hundred MB of block infos): for tests and tools that stamp the whole
     * sect and must not read files on the server thread. Worldgen never calls this; it keeps {@link #TEMPLATE_CACHE} templates.
     */
    public static void preload(MinecraftServer server) {
        Cache c = cache(server);
        Index index = c.index().orElse(null);
        if (index == null) {
            return;
        }
        c.limit().set(Integer.MAX_VALUE);
        for (Map.Entry<Group, Cell> e : cells(index)) {
            template(c, e.getValue().file());
        }
    }

    /** All cells of the index with their group, in file order (for callers that stamp them one by one). */
    public static List<Map.Entry<Group, Cell>> cells(Index index) {
        List<Map.Entry<Group, Cell>> out = new ArrayList<>();
        for (Group group : index.groups()) {
            for (Cell cell : group.cells()) {
                out.add(Map.entry(group, cell));
            }
        }
        return out;
    }

    /**
     * Stamps every cell that meets {@code clip} (null: everything, for a live world).
     *
     * @return the number of cells placed
     */
    public static int place(MinecraftServer server, ServerLevelAccessor level, MountHuaSite site, BoundingBox clip,
            RandomSource random, int flags) {
        return place(server, level, site, clip, random, flags, null);
    }

    static int place(MinecraftServer server, ServerLevelAccessor level, MountHuaSite site, BoundingBox clip,
            RandomSource random, int flags, HuaStampBlocks.Work work) {
        Index index = index(server);
        if (index == null) {
            return 0;
        }
        int placed = 0;
        for (Map.Entry<Group, Cell> e : cells(index)) {
            if (placeCell(server, level, site, index, e.getKey(), e.getValue(), clip, random, flags, work)) {
                placed++;
            }
        }
        return placed;
    }

    /** One cell, clipped to {@code clip} (null: whole); false if it does not meet the clip or its template is missing. */
    public static boolean placeCell(MinecraftServer server, ServerLevelAccessor level, MountHuaSite site, Index index,
            Group group, Cell cell, BoundingBox clip, RandomSource random, int flags) {
        return placeCell(server, level, site, index, group, cell, clip, random, flags, null);
    }

    private static boolean placeCell(MinecraftServer server, ServerLevelAccessor level, MountHuaSite site, Index index,
            Group group, Cell cell, BoundingBox clip, RandomSource random, int flags, HuaStampBlocks.Work work) {
        if (clip != null && !cellBox(index, group, cell, site).intersects(clip)) {
            return false;
        }
        StructureTemplate template = template(cache(server), cell.file());
        if (template == null) {
            return false;
        }
        BlockPos at = cellOrigin(index, group, cell, site);
        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(Rotation.values()[site.rotation()])
                .setKnownShape(true);
        if (clip != null) {
            settings.setBoundingBox(clip);
        }
        HuaStampBlocks.place(template, level, at, settings, random, flags, work);
        // ADR 0023: both side lanes of the treasurer's desk are empty at every rotation.
        BoundingBox box = cellBox(index, group, cell, site);
        for (int u : new int[] {48, 52}) for (int v = -1; v <= 1; v++) for (int y = 223; y <= 224; y++) {
            int[] d = site.rotateDir(u, v);
            BlockPos lane = new BlockPos(site.centerX() + d[0], y + site.baseY() - BAKE_BASE_Y, site.centerZ() + d[1]);
            if (box.isInside(lane) && (clip == null || clip.isInside(lane)))
                HuaStampBlocks.set(level, lane, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), flags);
        }
        return true;
    }

    /** The loaded template of a baked file (read on the calling thread if it is not cached yet); null if it is missing. For tests. */
    static StructureTemplate templateFor(MinecraftServer server, String file) {
        return template(cache(server), file);
    }

    private static StructureTemplate template(Cache c, String file) {
        StructureTemplate t = c.templates().get(file);
        if (t != null) {
            return t;
        }
        ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "hua_baked/" + file);
        try {
            Optional<Resource> res = c.server().getResourceManager().getResource(rl);
            if (res.isEmpty()) {
                MurimMod.LOGGER.warn("Baked Mount Hua sect: {} is missing", rl);
                return null;
            }
            try (InputStream in = res.get().open()) {
                CompoundTag tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
                t = c.server().getStructureManager().readStructure(tag);
            }
        } catch (IOException | RuntimeException e) {
            MurimMod.LOGGER.error("Baked Mount Hua sect: cannot read {}", rl, e);
            return null;
        }
        if (c.templates().size() >= c.limit().get()) {
            c.templates().clear();
        }
        c.templates().put(file, t);
        return t;
    }

    // ------------------------------------------------------------------ the expected block (sect repairs, doc 39)

    /** Grids kept at once (~25 KB each): the repair crew works in one or two cells at a time. */
    private static final int GRID_CACHE = 40;

    /** The block the baked sect holds at a position, and whether the template stores block entity DATA there (an nbt tag). */
    public record Expected(BlockState state, boolean blockEntity) {
    }

    /** One template as a dense array of palette indexes plus one (0: the template says nothing there). */
    private static final class Grid {
        private final int sx;
        private final int sy;
        private final int sz;
        private final short[] cells;
        private final BlockState[] palette;
        private final java.util.BitSet entities;

        private Grid(int sx, int sy, int sz, short[] cells, BlockState[] palette, java.util.BitSet entities) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.cells = cells;
            this.palette = palette;
            this.entities = entities;
        }

        static Grid parse(CompoundTag tag) {
            ListTag size = tag.getList("size", Tag.TAG_INT);
            int sx = size.getInt(0);
            int sy = size.getInt(1);
            int sz = size.getInt(2);
            // API: reference/minecraft-src/net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate.java#load
            ListTag paletteTag = tag.contains("palettes", Tag.TAG_LIST) ? tag.getList("palettes", Tag.TAG_LIST).getList(0)
                    : tag.getList("palette", Tag.TAG_COMPOUND);
            BlockState[] palette = new BlockState[paletteTag.size()];
            for (int i = 0; i < palette.length; i++) {
                palette[i] = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), paletteTag.getCompound(i));
            }
            short[] cells = new short[sx * sy * sz];
            java.util.BitSet entities = new java.util.BitSet();
            for (Tag t : tag.getList("blocks", Tag.TAG_COMPOUND)) {
                CompoundTag b = (CompoundTag) t;
                ListTag pos = b.getList("pos", Tag.TAG_INT);
                int x = pos.getInt(0);
                int y = pos.getInt(1);
                int z = pos.getInt(2);
                int state = b.getInt("state");
                if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz || state < 0 || state >= palette.length) {
                    continue;
                }
                int at = (y * sz + z) * sx + x;
                cells[at] = (short) (state + 1);
                if (b.contains("nbt")) {
                    entities.set(at);
                }
            }
            return new Grid(sx, sy, sz, cells, palette, entities);
        }

        Expected at(int x, int y, int z, Rotation rotation) {
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) {
                return null;
            }
            int at = (y * sz + z) * sx + x;
            int idx = cells[at];
            if (idx == 0) {
                return null;
            }
            BlockState state = palette[idx - 1].rotate(rotation);
            return new Expected(state, entities.get(at));
        }
    }

    /**
     * What the baked sect says should stand at a world position, turned by the site's quarter turns exactly as
     * {@link #placeCell} turns the template; null where no template covers the position (or the mountain is not the
     * canonical one the sect was baked on). A later group overrides an earlier one, as the stamps do. Reads (and keeps)
     * the template of the cell on the calling thread: call it from the server thread for a few positions, not for a scan.
     */
    public static Expected expectedAt(MinecraftServer server, MountHuaSite site, BlockPos pos) {
        Cache c = cache(server);
        Index index = c.index().orElse(null);
        if (index == null || !site.canonical()) {
            return null;
        }
        int[] local = site.stampBlock(pos.getX(), pos.getZ());
        int u = local[0];
        int v = local[1];
        int bakeY = pos.getY() - site.baseY() + BAKE_BASE_Y;
        if ((u == 48 || u == 52) && v >= -1 && v <= 1 && bakeY >= 223 && bakeY <= 224)
            return new Expected(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), false);
        Rotation rotation = Rotation.values()[site.rotation()];
        Expected found = null;
        for (Group group : index.groups()) {
            int shift = shift(index, group, site);
            for (Cell cell : group.cells()) {
                if (u < cell.u() || u >= cell.u() + 16 || v < cell.v() || v >= cell.v() + 16) {
                    continue;
                }
                int y = pos.getY() - (cell.y() + shift);
                if (y < 0 || y >= cell.h()) {
                    continue;
                }
                Grid grid = grid(c, cell.file());
                Expected e = grid == null ? null : grid.at(u - cell.u(), y, v - cell.v(), rotation);
                if (e != null) {
                    found = e;
                }
            }
        }
        return found;
    }

    private static Grid grid(Cache c, String file) {
        Grid g = c.grids().get(file);
        if (g != null) {
            return g;
        }
        ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "hua_baked/" + file);
        try {
            Optional<Resource> res = c.server().getResourceManager().getResource(rl);
            if (res.isEmpty()) {
                return null;
            }
            CompoundTag tag;
            try (InputStream in = res.get().open()) {
                tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
            }
            // API: reference/minecraft-src/net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager.java#readStructure
            tag = DataFixTypes.STRUCTURE.updateToCurrentVersion(c.server().getFixerUpper(), tag, NbtUtils.getDataVersion(tag, 500));
            g = Grid.parse(tag);
        } catch (IOException | RuntimeException e) {
            MurimMod.LOGGER.error("Baked Mount Hua sect: cannot read {} for the repair table", rl, e);
            return null;
        }
        if (c.grids().size() >= GRID_CACHE) {
            c.grids().clear();
        }
        c.grids().put(file, g);
        return g;
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        Cache c = cache;
        if (c != null && c.server() == event.getServer()) {
            cache = null;
        }
    }
}
