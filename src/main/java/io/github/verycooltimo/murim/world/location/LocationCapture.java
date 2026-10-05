package io.github.verycooltimo.murim.world.location;

import io.github.verycooltimo.murim.MurimMod;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

/**
 * Cuts a region of the live world into structure templates plus a manifest (docs/design/28-location-capture.md):
 * blocks with their block entities, decorative entities (item frames, displays, armor stands, paintings, carts),
 * no mobs, no players, no dropped items. Marker signs ({@link LocationMarker}) are cut out and become manifest
 * entries; a {@code loot:<table>} sign turns the container it marks into a loot container (contents dropped, table
 * written into its NBT, so every generated copy rolls fresh loot). Structure void blocks are not stored: there the
 * world keeps whatever terrain it has.
 *
 * <p>The template NBT is written here directly in the vanilla structure format (same lists, same order as
 * {@code StructureTemplate#fillFromWorld}: full blocks, then shaped blocks, then blocks with NBT), so the marker
 * cuts and loot rewrites need no second pass over a filled template.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate.java
 * (#fillFromWorld, #addToLists, #save — the format; #load reads "palette", "blocks", "entities", "size"),
 * reference/minecraft-src/net/minecraft/world/RandomizableContainer.java (LOOT_TABLE_TAG),
 * reference/minecraft-src/net/minecraft/nbt/NbtIo.java#writeCompressed.
 */
public final class LocationCapture {

    /** Largest capture, blocks per axis (the sect shelf with its halls fits; a whole mountain does not). */
    public static final int MAX_XZ = 320;
    public static final int MAX_Y = 192;

    /** What the capture did, for the command's answer. */
    public record Result(CapturedLocation location, int blocks, int entities, Path worldDir, Path sourceDir) {
    }

    /** What to remember about the procedural piece that stood there (see {@link CapturedLocation}). */
    public record Anchor(BlockPos world, int rotation, long seed) {
    }

    private LocationCapture() {
    }

    /**
     * Captures {@code box} as location {@code id}.
     *
     * @param anchor      procedural anchor in world coordinates, or null (box centre on the ground)
     * @param hua         Mount Hua frame for the sect overlay, or null
     * @param writeSource also write into the dev checkout's {@code src/main/resources} (the command; never tests)
     */
    public static Result capture(ServerLevel level, String id, BoundingBox box, Anchor anchor,
            CapturedLocation.HuaFrame hua, boolean writeSource) throws IOException {
        MinecraftServer server = level.getServer();
        BlockPos min = new BlockPos(box.minX(), box.minY(), box.minZ());
        Vec3i size = new Vec3i(box.getXSpan(), box.getYSpan(), box.getZSpan());
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                level.getChunk(cx, cz);
            }
        }

        // Markers: signs (block entities) and named armor stands.
        List<LocationMarker> markers = new ArrayList<>();
        Set<BlockPos> cut = new HashSet<>();
        Map<BlockPos, String> loot = new HashMap<>();
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                LevelChunk chunk = level.getChunk(cx, cz);
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!(be instanceof SignBlockEntity sign) || !box.isInside(be.getBlockPos())) {
                        continue;
                    }
                    String[] parsed = markerText(sign);
                    if (parsed == null) {
                        continue;
                    }
                    BlockPos pos = be.getBlockPos();
                    Direction facing = facing(be.getBlockState());
                    BlockPos at = pos;
                    if (parsed[0].equals("loot")) {
                        at = container(level, pos, be.getBlockState(), facing);
                        if (at == null) {
                            MurimMod.LOGGER.warn("Capture {}: loot sign at {} marks no container", id, pos.toShortString());
                            continue;
                        }
                        loot.put(at, lootTable(parsed[1]));
                    }
                    cut.add(pos.immutable());
                    markers.add(new LocationMarker(parsed[0], parsed[1], at.subtract(min), facing));
                }
            }
        }
        List<ArmorStand> standMarkers = new ArrayList<>();
        for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, AABB.of(box))) {
            String[] parsed = stand.hasCustomName() ? LocationMarker.parse(stand.getCustomName().getString()) : null;
            if (parsed != null) {
                standMarkers.add(stand);
                markers.add(new LocationMarker(parsed[0], parsed[1], stand.blockPosition().subtract(min),
                        Direction.fromYRot(stand.getYRot())));
            }
        }
        markers.sort((a, b) -> (a.type() + ":" + a.name()).compareTo(b.type() + ":" + b.name()));

        int groundY = groundY(level, box) - min.getY();
        BlockPos anchorLocal = anchor != null ? anchor.world().subtract(min)
                : new BlockPos(size.getX() / 2, groundY, size.getZ() / 2);
        List<CapturedLocation.Part> parts = CapturedLocation.split(size);
        CapturedLocation loc = new CapturedLocation(id, size, groundY, anchorLocal, anchor == null ? 0 : anchor.rotation(),
                anchor == null ? 0L : anchor.seed(), parts, List.copyOf(markers), hua);

        Path worldDir = LocationTemplates.generatedDir(server, id);
        Path sourceDir = writeSource ? LocationTemplates.sourceDir(server, id) : null;
        clearParts(worldDir);
        if (sourceDir != null) {
            clearParts(sourceDir);
        }
        int blocks = 0;
        int entities = 0;
        for (CapturedLocation.Part part : parts) {
            BlockPos from = min.offset(part.offset());
            CompoundTag tag = new CompoundTag();
            blocks += writeBlocks(level, from, part.size(), cut, loot, tag);
            entities += writeEntities(level, from, part.size(), standMarkers, tag);
            tag.put("size", ints(part.size().getX(), part.size().getY(), part.size().getZ()));
            NbtUtils.addCurrentDataVersion(tag);
            ResourceLocation tid = part.template(id);
            Path file = server.getStructureManager().createAndValidatePathToGeneratedStructure(tid, ".nbt");
            Files.createDirectories(file.getParent());
            NbtIo.writeCompressed(tag, file);
            if (sourceDir != null) {
                Files.createDirectories(sourceDir);
                Files.copy(file, sourceDir.resolve(file.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        CapturedLocation old = LocationTemplates.get(server, id);
        writeManifest(worldDir, loc);
        if (sourceDir != null) {
            writeManifest(sourceDir, loc);
        }
        LocationTemplates.invalidate(server, id, old);
        for (CapturedLocation.Part p : parts) {
            server.getStructureManager().remove(p.template(id));
        }
        MurimMod.LOGGER.info("Captured location {}: box {}..{}, {} parts, {} blocks, {} entities, {} markers{}", id,
                min.toShortString(), new BlockPos(box.maxX(), box.maxY(), box.maxZ()).toShortString(), parts.size(), blocks,
                entities, markers.size(), sourceDir == null ? "" : ", source " + sourceDir);
        return new Result(loc, blocks, entities, worldDir, sourceDir);
    }

    /** Removes the captured location from the world copy (resources stay; git handles those). */
    public static boolean deleteWorldCopy(MinecraftServer server, String id) throws IOException {
        Path dir = LocationTemplates.generatedDir(server, id);
        if (!Files.isDirectory(dir)) {
            return false;
        }
        CapturedLocation old = LocationTemplates.get(server, id);
        clearParts(dir);
        Files.deleteIfExists(dir.resolve(LocationTemplates.MANIFEST));
        LocationTemplates.invalidate(server, id, old);
        return true;
    }

    // ------------------------------------------------------------------ markers

    private static String[] markerText(SignBlockEntity sign) {
        for (boolean front : new boolean[] {true, false}) {
            for (int i = 0; i < 4; i++) {
                String line = sign.getText(front).getMessage(i, false).getString().trim();
                if (!line.isEmpty()) {
                    String[] parsed = LocationMarker.parse(line);
                    if (parsed != null) {
                        return parsed;
                    }
                    break;
                }
            }
        }
        return null;
    }

    /** Sign's front direction: wall/hanging FACING or the standing sign's 16-step rotation. */
    static Direction facing(BlockState state) {
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        }
        if (state.hasProperty(BlockStateProperties.ROTATION_16)) {
            return RotationSegment.convertToDirection(state.getValue(BlockStateProperties.ROTATION_16)).orElse(Direction.NORTH);
        }
        return Direction.NORTH;
    }

    /** The container a loot sign marks: under it, behind it (wall sign), or any neighbour. */
    private static BlockPos container(ServerLevel level, BlockPos sign, BlockState state, Direction facing) {
        List<BlockPos> order = new ArrayList<>(List.of(sign.below()));
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            order.add(sign.relative(facing.getOpposite()));
        }
        for (Direction d : Direction.values()) {
            order.add(sign.relative(d));
        }
        for (BlockPos p : order) {
            if (level.getBlockEntity(p) instanceof Container) {
                return p.immutable();
            }
        }
        return null;
    }

    /**
     * Loot table id of a {@code loot:} marker: a full id as is ({@code minecraft:chests/simple_dungeon}), a path
     * under {@code murim:chests/} otherwise, plus tier aliases for the author's convenience.
     */
    public static String lootTable(String name) {
        String n = switch (name) {
            case "chest_tier1", "tier1", "crate" -> "bandit_camp/crate";
            case "chest_tier2", "tier2", "cart" -> "bandit_camp/cart";
            case "chest_tier3", "tier3", "chief" -> "bandit_camp/chief";
            case "vault" -> "fortress_vault";
            case "desk" -> "ruined_library/desk";
            case "propped" -> "ruined_library/propped";
            default -> name;
        };
        return n.contains(":") ? n : MurimMod.MODID + ":chests/" + n;
    }

    // ------------------------------------------------------------------ ground

    /** Median top terrain block over the box (every second column), world y; box bottom if there is none. */
    static int groundY(ServerLevel level, BoundingBox box) {
        List<Integer> tops = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = box.minX(); x <= box.maxX(); x += 2) {
            for (int z = box.minZ(); z <= box.maxZ(); z += 2) {
                for (int y = box.maxY(); y >= box.minY(); y--) {
                    if (terrain(level.getBlockState(m.set(x, y, z)))) {
                        tops.add(y);
                        break;
                    }
                }
            }
        }
        if (tops.isEmpty()) {
            return box.minY();
        }
        int[] v = tops.stream().mapToInt(Integer::intValue).toArray();
        Arrays.sort(v);
        return v[v.length / 2];
    }

    static boolean terrain(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL)
                || s.is(BlockTags.TERRACOTTA) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.CALCITE) || s.is(Blocks.SMOOTH_BASALT);
    }

    // ------------------------------------------------------------------ NBT

    private static int writeBlocks(ServerLevel level, BlockPos from, Vec3i size, Set<BlockPos> cut,
            Map<BlockPos, String> loot, CompoundTag out) {
        Map<BlockState, Integer> palette = new LinkedHashMap<>();
        List<CompoundTag> full = new ArrayList<>();
        List<CompoundTag> shaped = new ArrayList<>();
        List<CompoundTag> withNbt = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        // Same order as vanilla: y, then x, then z inside each list.
        for (int y = 0; y < size.getY(); y++) {
            for (int x = 0; x < size.getX(); x++) {
                for (int z = 0; z < size.getZ(); z++) {
                    m.set(from.getX() + x, from.getY() + y, from.getZ() + z);
                    BlockState state = level.getBlockState(m);
                    if (state.is(Blocks.STRUCTURE_VOID)) {
                        continue;
                    }
                    CompoundTag nbt = null;
                    if (cut.contains(m)) {
                        state = Blocks.AIR.defaultBlockState();
                    } else {
                        BlockEntity be = level.getBlockEntity(m);
                        if (be != null) {
                            nbt = be.saveWithId(level.registryAccess());
                            String table = loot.get(m);
                            if (table != null) {
                                nbt.remove("Items");
                                nbt.remove("LootTableSeed");
                                nbt.putString("LootTable", table);
                            }
                        }
                    }
                    CompoundTag entry = new CompoundTag();
                    entry.put("pos", ints(x, y, z));
                    entry.putInt("state", palette.computeIfAbsent(state, s -> palette.size()));
                    if (nbt != null) {
                        entry.put("nbt", nbt);
                        withNbt.add(entry);
                    } else if (!state.getBlock().hasDynamicShape()
                            && state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
                        full.add(entry);
                    } else {
                        shaped.add(entry);
                    }
                }
            }
        }
        ListTag blocks = new ListTag();
        blocks.addAll(full);
        blocks.addAll(shaped);
        blocks.addAll(withNbt);
        ListTag pal = new ListTag();
        for (BlockState s : palette.keySet()) {
            pal.add(NbtUtils.writeBlockState(s));
        }
        out.put("blocks", blocks);
        out.put("palette", pal);
        return blocks.size();
    }

    /** Decorative entities only; positions relative to the part's corner. */
    private static int writeEntities(ServerLevel level, BlockPos from, Vec3i size, List<ArmorStand> markerStands, CompoundTag out) {
        BlockPos to = from.offset(size).offset(-1, -1, -1);
        BoundingBox part = BoundingBox.fromCorners(from, to);
        ListTag list = new ListTag();
        for (Entity e : level.getEntitiesOfClass(Entity.class, AABB.encapsulatingFullBlocks(from, to), LocationCapture::keep)) {
            if (markerStands.contains(e)) {
                continue;
            }
            BlockPos block = e instanceof HangingEntity h ? h.getPos() : e.blockPosition();
            if (!part.isInside(block)) {
                continue; // belongs to the neighbouring part
            }
            CompoundTag nbt = new CompoundTag();
            if (!e.save(nbt)) {
                continue;
            }
            CompoundTag info = new CompoundTag();
            ListTag pos = new ListTag();
            pos.add(DoubleTag.valueOf(e.getX() - from.getX()));
            pos.add(DoubleTag.valueOf(e.getY() - from.getY()));
            pos.add(DoubleTag.valueOf(e.getZ() - from.getZ()));
            info.put("pos", pos);
            BlockPos rel = block.subtract(from);
            info.put("blockPos", ints(rel.getX(), rel.getY(), rel.getZ()));
            info.put("nbt", nbt);
            list.add(info);
        }
        out.put("entities", list);
        return list.size();
    }

    /** Item frames, paintings, leash knots, displays, armor stands, carts and boats — never mobs or drops. */
    static boolean keep(Entity e) {
        return e instanceof HangingEntity || e instanceof net.minecraft.world.entity.Display || e instanceof ArmorStand
                || e instanceof AbstractMinecart || e instanceof Boat;
    }

    private static ListTag ints(int x, int y, int z) {
        ListTag t = new ListTag();
        t.add(IntTag.valueOf(x));
        t.add(IntTag.valueOf(y));
        t.add(IntTag.valueOf(z));
        return t;
    }

    private static void clearParts(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "part_*.nbt")) {
            for (Path f : files) {
                Files.delete(f);
            }
        }
    }

    private static void writeManifest(Path dir, CapturedLocation loc) throws IOException {
        Files.createDirectories(dir);
        try (Writer w = Files.newBufferedWriter(dir.resolve(LocationTemplates.MANIFEST), StandardCharsets.UTF_8)) {
            LocationTemplates.GSON.toJson(loc.toJson(), w);
            w.write("\n");
        }
    }

    /** Chunk span of a box (for loading before a capture). */
    static List<ChunkPos> chunks(BoundingBox box) {
        List<ChunkPos> out = new ArrayList<>();
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                out.add(new ChunkPos(cx, cz));
            }
        }
        return out;
    }
}
