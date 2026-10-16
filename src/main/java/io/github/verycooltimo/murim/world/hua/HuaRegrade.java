package io.github.verycooltimo.murim.world.hua;

import io.github.verycooltimo.murim.registry.ModBlocks;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** A read-only planning transaction; the exact same transaction is previewed and applied. */
public final class HuaRegrade implements MountHuaChunkWriter.RegradeAccess {
    public static final int MARGIN = HuaTerraceGrade.REACH;
    private record Column(int oldTop, int newTop, boolean keepSurface) { }
    private record Floor(PavingPattern pattern, int offset) { }
    public record Move(BlockPos from, BlockPos to, int blocks, boolean extension) { }
    public record FloorMove(BlockPos from, BlockPos to) { }

    private final ServerLevel world;
    private final HuaVegetation vegetation;
    private final Map<String, Floor> floors = new HashMap<>();
    private final MountHuaSite site;
    private final List<MountHuaPlan.Zone> zones;
    private final java.util.function.ToIntBiFunction<Integer, Integer> desiredHeight;
    private final Map<Long, Column> columns = new HashMap<>();
    private final Map<BlockPos, BlockState> edits = new LinkedHashMap<>();
    private final Map<BlockPos, BlockState> original = new HashMap<>();
    private final java.util.Set<Long> protectedColumns = new java.util.HashSet<>();
    private final Map<BlockPos, BlockState> floorMoves = new LinkedHashMap<>();
    private final Map<BlockPos, BlockPos> floorTransfers = new LinkedHashMap<>();
    private final Map<BlockPos, BlockState> walls = new LinkedHashMap<>();
    private final Map<BlockPos, BlockState> campMarkers = new LinkedHashMap<>();
    private final List<Move> moves = new ArrayList<>();
    private final List<BlockPos> pinned = new ArrayList<>();
    private final BoundingBox bounds;
    private final LevelAccessor bufferedLevel;
    private int protectedBlocks;
    private int blockedColumns;

    private HuaRegrade(ServerLevel world, MountHuaSite site, List<MountHuaPlan.Zone> zones,
            java.util.function.ToIntBiFunction<Integer, Integer> desiredHeight) {
        this.world = world;
        this.vegetation = new HuaVegetation(world::getBlockState);
        this.site = site;
        this.zones = List.copyOf(zones);
        this.desiredHeight = desiredHeight;
        int minU = Integer.MAX_VALUE, minV = Integer.MAX_VALUE;
        int maxU = Integer.MIN_VALUE, maxV = Integer.MIN_VALUE;
        for (var z : zones) {
            int[] r = rectangle(z);
            minU = Math.min(minU, r[0] - MARGIN);
            minV = Math.min(minV, r[1] - MARGIN);
            maxU = Math.max(maxU, r[2] + MARGIN);
            maxV = Math.max(maxV, r[3] + MARGIN);
        }
        int[] a = site.toWorld(minU, minV), b = site.toWorld(maxU, maxV);
        bounds = BoundingBox.fromCorners(new BlockPos(a[0], world.getMinBuildHeight(), a[1]),
                new BlockPos(b[0], world.getMaxBuildHeight() - 1, b[1]));
        // Never request generation or load chunks as a side effect of preview/apply.
        for (int cx = bounds.minX() >> 4; cx <= bounds.maxX() >> 4; cx++) {
            for (int cz = bounds.minZ() >> 4; cz <= bounds.maxZ() >> 4; cz++) {
                if (world.getChunkSource().getChunkNow(cx, cz) == null) {
                    throw new IllegalArgumentException("Unloaded chunk " + cx + " " + cz);
                }
            }
        }
        // PlumTree already accepts LevelAccessor; intercept its reads/writes as well as the writer's.
        bufferedLevel = (LevelAccessor) Proxy.newProxyInstance(LevelAccessor.class.getClassLoader(),
                new Class<?>[] {LevelAccessor.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getBlockState")) return get((BlockPos) args[0]);
                    if (method.getName().equals("setBlock")) return set((BlockPos) args[0], (BlockState) args[1]);
                    try {
                        return method.invoke(world, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    public static HuaRegrade plan(ServerLevel world, MountHuaSite site, List<MountHuaPlan.Zone> zones) {
        return plan(world, site, zones, (x, z) -> (int) Math.round(site.worldY(site.shape().height(
                site.localU(x + 0.5, z + 0.5), site.localV(x + 0.5, z + 0.5)))));
    }

    /** Test seam for a small synthetic pad; every preservation/write code path is shared. */
    static HuaRegrade plan(ServerLevel world, MountHuaSite site, List<MountHuaPlan.Zone> zones,
            java.util.function.ToIntBiFunction<Integer, Integer> desiredHeight) {
        if (zones.isEmpty() || zones.stream().anyMatch(MountHuaPlan.Zone::cave)) {
            throw new IllegalArgumentException("Select at least one surface terrace (caves are excluded)");
        }
        HuaRegrade plan = new HuaRegrade(world, site, zones, desiredHeight);
        plan.prepare();
        return plan;
    }

    private static long key(int x, int z) { return BlockPos.asLong(x, 0, z); }
    private static int[] rectangle(MountHuaPlan.Zone z) {
        int u = (int) Math.floor(z.u() - z.width() / 2.0);
        int v = (int) Math.floor(z.v() - z.depth() / 2.0);
        return new int[] {u, v, u + z.width() - 1, v + z.depth() - 1};
    }

    /** Only migration-source footprints: they never drive new generation. */
    private static int[] oldRectangle(MountHuaPlan.Zone z) {
        return switch (z.id()) {
            case "training" -> new int[] {-18, -12, 29, 19};
            case "arch" -> new int[] {-8, 22, 19, 41}; // original 28 x 20 sparring pad
            case "main_hall" -> new int[] {-20, 51, 31, 72};
            case "camp" -> new int[] {-81, 10, -44, 57};
            case "alchemy" -> new int[] {-40, 54, -25, 65};
            case "elders" -> new int[] {-62, 76, -25, 95};
            case "dining" -> new int[] {-67, 60, -46, 71};
            default -> rectangle(z);
        };
    }

    private int nominalTop(MountHuaPlan.Zone z) { return (int) Math.round(site.worldY(z.y())); }
    private int oldPadTop(MountHuaPlan.Zone z) {
        return (int) Math.round(site.worldY(z.id().equals("arch") ? 157 : z.y()));
    }
    private BlockPos pos(int u, int y, int v) {
        // Transform centres, so cell identity round-trips for every quarter turn.
        int[] w = site.toWorld(u + 0.5, v + 0.5);
        return new BlockPos(w[0], y, w[1]);
    }
    private static boolean inside(int u, int v, int[] r) {
        return u >= r[0] && v >= r[1] && u <= r[2] && v <= r[3];
    }
    private double distance(double u, double v) {
        double d = Double.POSITIVE_INFINITY;
        for (var zone : zones) {
            d = Math.min(d, Math.hypot(Math.max(0, Math.abs(u - zone.u()) - zone.width() / 2.0),
                    Math.max(0, Math.abs(v - zone.v()) - zone.depth() / 2.0)));
        }
        return d;
    }
    private MountHuaPlan.Zone selectedPad(int x, int z) {
        int u = (int) Math.floor(site.localU(x + 0.5, z + 0.5));
        int v = (int) Math.floor(site.localV(x + 0.5, z + 0.5));
        return zones.stream().filter(p -> inside(u, v, rectangle(p))).findFirst().orElse(null);
    }
    private int ground(int x, int z) {
        if (!bounds.isInside(new BlockPos(x, bounds.minY(), z))) return world.getMinBuildHeight();
        int top = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        MountHuaPlan.Zone pad = selectedPad(x, z);
        for (int y = top; y >= world.getMinBuildHeight(); y--) {
            BlockState state = world.getBlockState(new BlockPos(x, y, z));
            if (isGround(state)) return y;
            Floor floor = pad == null ? null : floors.get(pad.id());
            if (floor != null && y == oldPadTop(pad) + floor.offset && floorBlock(state)
                    && world.getBlockState(new BlockPos(x, y - 1, z)).blocksMotion()) return y - floor.offset;
        }
        return world.getMinBuildHeight();
    }

    private void prepare() {
        for (var zone : zones) {
            Floor floor = sampleFloor(zone);
            if (floor != null) floors.put(zone.id(), floor);
            if (zone.id().equals("camp")) cleanCampMarkers(zone, floor);
            if (zone.id().equals("training")) relocateTrainingWalls(zone);
        }
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                double u = site.localU(x + 0.5, z + 0.5), v = site.localV(x + 0.5, z + 0.5);
                double d = distance(u, v);
                if (d >= MARGIN) continue;
                // Neighbouring terraces keep their own elevation and authored blocks.
                var pad = selectedPad(x, z);
                var otherPad = pad == null ? MountHuaPlan.ZONES.stream().filter(p -> !p.cave()
                        && zones.stream().noneMatch(selected -> selected.id().equals(p.id()))
                        && MountHuaPlan.insidePad(p, u, v)).findFirst().orElse(null) : null;
                int target = desiredHeight.applyAsInt(x, z);
                // A neighbouring pad at another level is outside this transaction. At the same
                // level its natural bank must be graded too: skipping it leaves an old cut/fill
                // directly against the selected pad. Authored blocks are still protected below.
                if (otherPad != null && nominalTop(otherPad) != target) continue;
                int old = ground(x, z);
                if (pad != null && inside((int) Math.floor(u), (int) Math.floor(v), oldRectangle(pad))) {
                    // Paving placed one block above the pad does not raise its terrain datum.
                    int expected = oldPadTop(pad);
                    if (world.getBlockState(new BlockPos(x, expected, z)).blocksMotion()) old = expected;
                }
                Floor sampled = pad == null ? null : floors.get(pad.id());
                if (sampled != null && sampled.offset == 1 && old == nominalTop(pad) + 1
                        && floorBlock(world.getBlockState(new BlockPos(x, old, z)))) old--;
                // MountHuaShape already grades the banks. Blending against today's old height
                // moves the margin again on every apply; use the same fixed datum as worldgen.
                target = Math.max(world.getMinBuildHeight() + 4, Math.min(world.getMaxBuildHeight() - 8, target));
                boolean keep = old == target;
                // Scan and record every protected state before writing anything. Inventories remain in place.
                int surface = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                boolean obstruction = false;
                for (int y = world.getMinBuildHeight(); y < surface; y++) {
                    BlockPos at = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(at);
                    // Barrier/structure-void cells are editor and test-structure shells, never player builds.
                    if (state.is(Blocks.BARRIER) || state.is(Blocks.STRUCTURE_VOID)) continue;
                    if (!natural(state) && !vegetation.treeLog(at) && !walls.containsKey(at) && !campMarkers.containsKey(at)) {
                        original.put(at, state);
                        protectedColumns.add(key(x, z));
                        protectedBlocks++;
                        Floor padFloor = pad == null ? null : floors.get(pad.id());
                        // Only a sampled, continuous paving layer may move. A solitary plaster
                        // block or wooden footing one above soil is part of a building.
                        boolean surfaceFloor = padFloor != null && floorBlock(state)
                                && y == old + padFloor.offset
                                && state.equals(padFloor.pattern.at((int) Math.floor(u), (int) Math.floor(v)));
                        if (!keep && surfaceFloor) {
                            floorTransfers.put(at, new BlockPos(x, target + y - old, z));
                        } else if (!keep && (y >= old - 2 || y <= target && y >= target - 4)) obstruction = true;
                    }
                }
                if (obstruction) {
                    // Do not bury a building or excavate beneath an inventory. Report the pinned column.
                    target = old;
                    keep = true;
                    blockedColumns++;
                    pinned.add(new BlockPos(x, old, z));
                    final int columnX = x, columnZ = z;
                    floorTransfers.keySet().removeIf(p -> p.getX() == columnX && p.getZ() == columnZ);
                }
                // Refresh generated stair/kerb surfaces even when their elevation already matches.
                // Access headroom may clear foliage above a protected floor; exact authored states stay protected.
                if (keep && !obstruction && (accessPathAt(u, v) || !protectedColumns.contains(key(x, z)) && refreshSurface(u, v, pad))) {
                    keep = false;
                }
                columns.put(key(x, z), new Column(old, target, keep));
            }
        }
        // Clear all source cells before placing targets, including stacked layers that overlap after a lift.
        floorTransfers.forEach((from, to) -> floorMoves.put(from, Blocks.AIR.defaultBlockState()));
        floorTransfers.forEach((from, to) -> floorMoves.put(to, original.get(from)));
        forEachChunk(false);
        forEachChunk(true);
        for (var e : floorMoves.entrySet()) {
            // A source floor below the new grade becomes part of its foundation, not an air pocket.
            BlockState state = e.getValue();
            if (state.isAir() && e.getKey().getY() <= newTop(e.getKey().getX(), e.getKey().getZ())) {
                state = Blocks.STONE.defaultBlockState();
            }
            edits.put(e.getKey(), state);
        }
        // Continue the full tile, with its phase in local coordinates and its original vertical offset.
        for (var entry : columns.entrySet()) {
            BlockPos column = BlockPos.of(entry.getKey());
            int x = column.getX(), z = column.getZ();
            var pad = selectedPad(x, z);
            int u = (int) Math.floor(site.localU(x + 0.5, z + 0.5));
            int v = (int) Math.floor(site.localV(x + 0.5, z + 0.5));
            if (pad == null) continue;
            Floor floor = floors.get(pad.id());
            if (floor == null) continue;
            Column c = entry.getValue();
            if (c.newTop != nominalTop(pad) || c.keepSurface) continue;
            BlockPos at = new BlockPos(x, c.newTop + floor.offset, z);
            if (!original.containsKey(at) || floorMoves.containsKey(at)) {
                BlockState copied = floor.pattern.at(u, v);
                if (inside(u, v, oldRectangle(pad))) {
                    BlockState source = world.getBlockState(pos(u, oldPadTop(pad) + floor.offset, v));
                    if (floorBlock(source) && !(pad.id().equals("camp") && campMarker(source))) copied = source;
                }
                edits.put(at, copied);
            }
        }
        // The exception to unchanged-column preservation is explicit removal/relocation of interior walls.
        for (var e : walls.entrySet()) edits.put(e.getKey(), e.getValue());
        // Only the explicitly obsolete concrete floor marks are exempt from build protection.
        edits.putAll(campMarkers);
        // Unchanged terrain and protected floors still need clear building footprints. Persistent
        // crowns and identified natural trunks count too; construction timber stays protected.
        for (var entry : columns.entrySet()) {
            BlockPos column = BlockPos.of(entry.getKey());
            var pad = selectedPad(column.getX(), column.getZ());
            if (pad == null || pad.id().equals("grove")) continue;
            int floor = Math.max(entry.getValue().newTop, nominalTop(pad));
            for (int y = floor + 1; y < world.getMaxBuildHeight(); y++) {
                BlockPos at = new BlockPos(column.getX(), y, column.getZ());
                if ((foliage(get(at)) || vegetation.treeLog(at)) && !original.containsKey(at)) {
                    edits.put(at, Blocks.AIR.defaultBlockState());
                }
            }
        }
        edits.entrySet().removeIf(e -> e.getValue().equals(world.getBlockState(e.getKey())));
    }

    private static boolean accessPathAt(double u, double v) {
        for (double[] p : MountHuaPlan.PATHS) {
            if (MountHuaPlan.accessPath(p) && MountHuaShape.pathAt(p, u, v)[0] <= MountHuaPlan.pathHalfWidth(p)) return true;
        }
        return false;
    }

    private static boolean campMarker(BlockState state) {
        return state.is(Blocks.WHITE_CONCRETE) || state.is(Blocks.LIGHT_GRAY_CONCRETE);
    }

    private void cleanCampMarkers(MountHuaPlan.Zone zone, Floor floor) {
        int[] r = rectangle(zone);
        int top = nominalTop(zone);
        for (int u = r[0]; u <= r[2]; u++) for (int v = r[1]; v <= r[3]; v++) {
            for (int offset : new int[] {0, 1}) {
                BlockPos at = pos(u, top + offset, v);
                if (!campMarker(world.getBlockState(at))) continue;
                // A wall rising from a concrete footing is a build, not an old floor outline.
                if (!natural(world.getBlockState(at.above())) || !world.getBlockState(at.below()).blocksMotion()) continue;
                BlockState replacement = offset == 1 && (floor == null || floor.offset == 0)
                        ? Blocks.AIR.defaultBlockState()
                        : floor != null ? floor.pattern.at(u, v)
                        : HuaPaving.surface("camp", Math.min(Math.min(u - r[0], r[2] - u),
                                Math.min(v - r[1], r[3] - v)) + 0.5, 1, 0);
                campMarkers.put(at, replacement);
            }
        }
    }

    private static boolean refreshSurface(double u, double v, MountHuaPlan.Zone pad) {
        if (pad != null && !pad.id().equals("grove")) {
            double edge = Math.min(pad.width() / 2.0 - Math.abs(u - pad.u()),
                    pad.depth() / 2.0 - Math.abs(v - pad.v()));
            if (edge < 2) return true;
        }
        for (double[] p : MountHuaPlan.PATHS) {
            if (MountHuaPlan.pathWidth(p) > MountHuaPlan.PATH_WIDTH
                    && (MountHuaShape.pathAt(p, u, v)[0] <= MountHuaPlan.pathHalfWidth(p)
                    || MountHuaShape.pathCheek(p, u, v))) return true;
        }
        return false;
    }

    private void forEachChunk(boolean decorate) {
        for (int cx = bounds.minX() >> 4; cx <= bounds.maxX() >> 4; cx++) {
            for (int cz = bounds.minZ() >> 4; cz <= bounds.maxZ() >> 4; cz++) {
                MountHuaChunkWriter.regrade(world, world.getChunkSource().getChunkNow(cx, cz), site, this, decorate);
            }
        }
    }

    private Floor sampleFloor(MountHuaPlan.Zone zone) {
        int[] r = oldRectangle(zone);
        int top = oldPadTop(zone);
        for (int offset : new int[] {1, 0}) {
            Map<Long, BlockState> sample = new HashMap<>();
            for (int u = r[0] + 3; u <= r[2] - 3; u++) {
                for (int v = r[1] + 3; v <= r[3] - 3; v++) {
                    BlockPos at = pos(u, top + offset, v);
                    if (!bounds.isInside(at)) continue;
                    BlockState s = world.getBlockState(at);
                    if (floorBlock(s) && !(zone.id().equals("camp") && campMarker(s))) sample.put(key(u, v), s);
                }
            }
            int interiorArea = Math.max(0, r[2] - r[0] - 5) * Math.max(0, r[3] - r[1] - 5);
            if (sample.size() >= Math.max(16, interiorArea / 2)) {
                if (zone.id().equals("camp")) {
                    // A clean yard continues its predominant paving, never a sampled outline motif.
                    Map<BlockState, Integer> counts = new LinkedHashMap<>();
                    sample.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> counts.merge(e.getValue(), 1, Integer::sum));
                    BlockState main = counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
                    return new Floor(PavingPattern.from(Map.of(key(0, 0), main)), offset);
                }
                return new Floor(PavingPattern.from(sample), offset);
            }
        }
        return null;
    }

    private static boolean floorBlock(BlockState s) {
        return !s.isAir() && !s.hasBlockEntity() && !wallBlock(s) && !s.is(BlockTags.LOGS) && !PlumTree.isPlumWood(s)
                && (HuaPaving.isPaving(s) || !natural(s) && s.blocksMotion());
    }
    private static boolean wallBlock(BlockState s) {
        String name = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        return name.startsWith("thin_wall_") || s.is(BlockTags.WALLS) || s.is(BlockTags.FENCES);
    }
    private boolean wallColumn(int u, int v, int base) {
        for (int y = base + 1; y <= base + 8; y++) {
            if (wallBlock(world.getBlockState(pos(u, y, v)))) return true;
        }
        return false;
    }

    private void relocateTrainingWalls(MountHuaPlan.Zone zone) {
        int[] old = oldRectangle(zone), now = rectangle(zone);
        int base = oldPadTop(zone), rear = old[3], most = 0;
        // Include the destination: a rear wall already there (including one moved by the
        // author) wins over the two side-wall tips left inside the old search interval.
        for (int v = old[3] - 3; v <= now[3]; v++) {
            int count = 0;
            for (int u = old[0]; u <= old[2]; u++) if (wallColumn(u, v, base)) count++;
            if (count > 0 && count >= most) { most = count; rear = v; }
        }
        if (most == 0 || rear == now[3]) return;
        for (int u = old[0]; u <= old[2]; u++) {
            if (wallColumn(u, rear, base)) copyWallColumn(u, rear, u, now[3], base, true);
        }
        // Side walls keep their existing run and extend using its periodic column composition.
        for (int edge : new int[] {old[0], old[2]}) {
            int side = edge, best = 0;
            for (int u = edge - 2; u <= edge + 2; u++) {
                int count = 0;
                for (int v = old[1] + 2; v < rear; v++) if (wallColumn(u, v, base)) count++;
                if (count > best) { best = count; side = u; }
            }
            if (best == 0) continue;
            List<Integer> source = new ArrayList<>();
            for (int v = Math.max(old[1] + 2, rear - 8); v < rear; v++) {
                if (wallColumn(side, v, base)) source.add(v);
            }
            if (source.isEmpty()) continue;
            for (int v = rear; v < now[3]; v++) {
                int from = source.get(Math.floorMod(v - source.getFirst(), source.size()));
                copyWallColumn(side, from, side, v, base, false);
            }
        }
    }

    private void copyWallColumn(int u, int v, int nu, int nv, int base, boolean remove) {
        Map<BlockPos, BlockState> additions = new LinkedHashMap<>();
        List<BlockPos> removals = new ArrayList<>();
        boolean sameColumn = true;
        for (int y = base + 1; y <= Math.min(base + 32, world.getMaxBuildHeight() - 1); y++) {
            BlockPos from = pos(u, y, v), to = pos(nu, y, nv);
            BlockState state = world.getBlockState(from);
            if (state.isAir() || foliage(state) && !state.is(BlockTags.LOGS)) continue;
            if (state.hasBlockEntity()) {
                throw new IllegalArgumentException("Wall column contains a block entity at " + from.toShortString());
            }
            BlockState there = world.getBlockState(to);
            sameColumn &= there.equals(state);
            BlockState pending = walls.get(to);
            // A column the rear wall vacates (pending removal) is free for the side run.
            if (!natural(there) && !there.equals(state) && !(pending != null && pending.isAir())) {
                throw new IllegalArgumentException("Wall destination occupied at " + to.toShortString());
            }
            additions.put(to, state);
            if (remove) removals.add(from);
        }
        // An identical column is already complete; never clear it or report another move.
        if (sameColumn) return;
        for (BlockPos p : removals) walls.put(p, Blocks.AIR.defaultBlockState());
        walls.putAll(additions);
        if (!additions.isEmpty()) moves.add(new Move(pos(u, base + 1, v), pos(nu, base + 1, nv), additions.size(), !remove));
    }

    /** A small repeating rectangle copied verbatim; detects checkerboards and stripe phases. */
    static final class PavingPattern {
        private final int u0, v0, width, depth;
        private final Map<Long, BlockState> tile;
        private PavingPattern(int u0, int v0, int width, int depth, Map<Long, BlockState> tile) {
            this.u0 = u0; this.v0 = v0; this.width = width; this.depth = depth; this.tile = tile;
        }
        BlockState at(int u, int v) { return tile.get(key(u0 + Math.floorMod(u - u0, width), v0 + Math.floorMod(v - v0, depth))); }
        static PavingPattern from(Map<Long, BlockState> sample) {
            List<BlockPos> origins = sample.keySet().stream().map(BlockPos::of)
                    .sorted(Comparator.comparingInt((BlockPos p) -> p.getZ()).thenComparingInt(p -> p.getX())).toList();
            // Smallest consistent complete period; larger tiles preserve non-periodic hand-laid motifs.
            for (int size = 1; size <= 256; size++) {
                for (int width = 1; width <= 16; width++) {
                    if (size % width != 0 || size / width > 16) continue;
                    int depth = size / width;
                    for (BlockPos origin : origins) {
                        int u0 = origin.getX(), v0 = origin.getZ();
                        Map<Long, BlockState> tile = new HashMap<>();
                        for (int du = 0; du < width; du++) for (int dv = 0; dv < depth; dv++) {
                            BlockState s = sample.get(key(u0 + du, v0 + dv));
                            if (s != null) tile.put(key(u0 + du, v0 + dv), s);
                        }
                        if (tile.size() != size) continue;
                        PavingPattern candidate = new PavingPattern(u0, v0, width, depth, tile);
                        if (sample.entrySet().stream().allMatch(e -> {
                            BlockPos p = BlockPos.of(e.getKey());
                            return e.getValue().equals(candidate.at(p.getX(), p.getZ()));
                        })) return candidate;
                        break; // A different origin cannot repair an inconsistent global period.
                    }
                }
            }
            // No global period: retain a complete source patch rather than flattening it to one material.
            for (int size = 16; size >= 2; size--) for (BlockPos origin : origins) {
                Map<Long, BlockState> tile = new HashMap<>();
                for (int du = 0; du < size; du++) for (int dv = 0; dv < size; dv++) {
                    long k = key(origin.getX() + du, origin.getZ() + dv);
                    if (sample.containsKey(k)) tile.put(k, sample.get(k));
                }
                if (tile.size() == size * size) return new PavingPattern(origin.getX(), origin.getZ(), size, size, tile);
            }
            throw new IllegalArgumentException("Paving has no complete 2 x 2 source tile");
        }
    }

    private static boolean foliage(BlockState s) {
        return HuaVegetation.foliage(s);
    }
    private static boolean isGround(BlockState s) {
        return !s.isAir() && !foliage(s) && (natural(s) && s.blocksMotion() || HuaPaving.isPaving(s)
                || s.is(ModBlocks.POLISHED_HUA_GRANITE_STAIRS.get()));
    }
    /** Explicit generation palette: never classify all murim blocks as disposable. */
    private static final java.util.Set<String> NATURAL_MOD = java.util.Set.of(
            "murim:hua_granite", "murim:hua_granite_pale", "murim:hua_granite_dark",
            "murim:hua_granite_stained", "murim:hua_granite_mossy", "murim:hua_granite_cracked",
            "murim:hua_litter", "murim:hua_scree", "murim:hua_packed_earth", "murim:polished_hua_granite");

    public static boolean natural(BlockState s) {
        if (s.hasBlockEntity()) return false;
        if (s.isAir() || foliage(s)) return true;
        if (s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Blocks.BEDROCK)
                || s.is(BlockTags.COAL_ORES) || s.is(BlockTags.IRON_ORES) || s.is(BlockTags.COPPER_ORES)
                || s.is(BlockTags.GOLD_ORES) || s.is(BlockTags.REDSTONE_ORES) || s.is(BlockTags.LAPIS_ORES)
                || s.is(BlockTags.DIAMOND_ORES) || s.is(BlockTags.EMERALD_ORES)
                || s.is(Blocks.STONE) || s.is(Blocks.DIRT) || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.PODZOL)
                || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.ROOTED_DIRT) || s.is(Blocks.MOSS_BLOCK)
                || s.is(Blocks.DIRT_PATH) || s.is(Blocks.GRAVEL) || s.is(Blocks.SAND)
                || s.is(Blocks.ANDESITE) || s.is(Blocks.DIORITE) || s.is(Blocks.GRANITE)
                || s.is(Blocks.TUFF) || s.is(Blocks.CALCITE) || s.is(Blocks.COBBLESTONE)
                || s.is(Blocks.MOSSY_COBBLESTONE) || s.is(Blocks.WATER) || s.is(Blocks.SNOW)
                || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.MUD)
                || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE) || s.is(Blocks.RED_SAND)
                || s.is(Blocks.MYCELIUM)) return true;
        String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
        return NATURAL_MOD.contains(id);
    }

    @Override public BlockState get(BlockPos p) {
        if (!bounds.isInside(p)) return Blocks.AIR.defaultBlockState();
        return edits.getOrDefault(p, world.getBlockState(p));
    }
    @Override public boolean set(BlockPos p, BlockState state) {
        Column c = columns.get(key(p.getX(), p.getZ()));
        if (c == null || !bounds.isInside(p) || original.containsKey(p) && !floorMoves.containsKey(p) || walls.containsKey(p)) return false;
        var pad = selectedPad(p.getX(), p.getZ());
        if (pad != null && !pad.id().equals("grove") && p.getY() > nominalTop(pad)
                && (foliage(state) || state.is(BlockTags.LOGS) || PlumTree.isPlumWood(state))) return false;
        if (c.keepSurface && !(selectedPad(p.getX(), p.getZ()) == null && p.getY() > c.oldTop
                && foliage(state) && get(p).isAir())) return false;
        edits.put(p.immutable(), state);
        return true;
    }
    @Override public int oldTop(int x, int z) {
        Column c = columns.get(key(x, z)); return c == null ? ground(x, z) : c.oldTop;
    }
    @Override public int newTop(int x, int z) {
        Column c = columns.get(key(x, z)); return c == null ? ground(x, z) : c.newTop;
    }
    @Override public boolean changes(int x, int z) {
        Column c = columns.get(key(x, z)); return c != null && !c.keepSurface;
    }
    @Override public boolean treeFits(int x, int z, int radius) {
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            if (MountHuaPlan.clearFootprint(site.localU(x + dx + 0.5, z + dz + 0.5),
                    site.localV(x + dx + 0.5, z + dz + 0.5))) return false;
            long k = key(x + dx, z + dz);
            Column c = columns.get(k);
            if (c == null || protectedColumns.contains(k)
                    || c.keepSurface && selectedPad(x + dx, z + dz) != null) return false;
        }
        return true;
    }
    @Override public LevelAccessor level() { return bufferedLevel; }
    public BoundingBox bounds() { return bounds; }
    public BoundingBox backupBounds() {
        int minY = edits.keySet().stream().mapToInt(BlockPos::getY).min().orElse(bounds.minY());
        int maxY = edits.keySet().stream().mapToInt(BlockPos::getY).max().orElse(minY);
        return new BoundingBox(bounds.minX(), minY, bounds.minZ(), bounds.maxX(), maxY, bounds.maxZ());
    }
    public List<Move> moves() { return List.copyOf(moves); }
    public List<FloorMove> floorRelocations() {
        return floorTransfers.entrySet().stream().map(e -> new FloorMove(e.getKey(), e.getValue())).toList();
    }
    public List<BlockPos> pinnedColumns() { return List.copyOf(pinned); }
    public int changedBlocks() { return edits.size(); }
    public int protectedBlocks() { return protectedBlocks; }
    public int blockedColumns() { return blockedColumns; }
    public int cleanedCampMarkers() { return campMarkers.size(); }
    public Map<BlockPos, BlockState> edits() { return Map.copyOf(edits); }
    public ServerLevel world() { return world; }
    public MountHuaSite site() { return site; }

    /** Call only after a successful region save; use ServerLevel so lighting/heightmaps/client packets update. */
    public void apply() {
        for (var e : edits.entrySet()) world.setBlock(e.getKey(), e.getValue(), 2 | 16);
    }
}
