package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.entity.SectDisciple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Shared, incremental scans of the author's zones. Never load chunks or scan a whole hall per NPC. */
public final class SectBuildings {
    private static final net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> SEATS = net.minecraft.tags.TagKey.create(
            net.minecraft.core.registries.Registries.BLOCK, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("murim", "sect_seats"));
    private static final net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> TABLES = net.minecraft.tags.TagKey.create(
            net.minecraft.core.registries.Registries.BLOCK, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("murim", "sect_tables"));
    public enum Kind { BED, SEAT, TABLE, LECTERN, STOVE, DOOR, LADDER }
    private static final int BUDGET = 512;
    private static final int CACHE_TICKS = 1200;
    private static final Map<Level, Cache> CACHES = new WeakHashMap<>();
    private record Zone(String name, BlockPos low, BlockPos high) { }
    private record Claim(UUID owner, long until) { }
    private static final class Cache {
        final Map<Zone, Scan> zones = new java.util.LinkedHashMap<>();
        final Map<BlockPos, Claim> claims = new HashMap<>();
        long tick = Long.MIN_VALUE;
        int left;
    }
    private static final class Scan {
        final EnumMap<Kind, List<BlockPos>> found = new EnumMap<>(Kind.class);
        long accessed;
        long finished = Long.MAX_VALUE;
        int cursor;
        final int width, depth, height, volume;
        Scan(Zone zone, long now) {
            accessed = now;
            width = zone.high().getX() - zone.low().getX() + 1;
            depth = zone.high().getZ() - zone.low().getZ() + 1;
            height = zone.high().getY() - zone.low().getY() + 1;
            volume = width * depth * height;
            for (Kind kind : Kind.values()) found.put(kind, new ArrayList<>());
        }
    }
    private SectBuildings() { }

    private static Kind kind(BlockState s) {
        if (s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.HEAD) return Kind.BED;
        if (s.is(Blocks.LECTERN)) return Kind.LECTERN;
        if (s.getBlock() instanceof DoorBlock || s.getBlock() instanceof io.github.verycooltimo.murim.decor.GateDoorBlock
                || s.getBlock() instanceof io.github.verycooltimo.murim.decor.HallGrandDoorBlock) return Kind.DOOR;
        if (s.is(Blocks.LADDER)) return Kind.LADDER;
        if (s.is(Blocks.FURNACE) || s.is(Blocks.SMOKER) || s.is(Blocks.BLAST_FURNACE) || s.is(Blocks.CAMPFIRE)
                || s.is(Blocks.SOUL_CAMPFIRE) || s.is(Blocks.BREWING_STAND) || s.getBlock() instanceof AbstractCauldronBlock) return Kind.STOVE;
        if (s.is(SEATS)) return Kind.SEAT;
        if (s.is(TABLES)) return Kind.TABLE;
        var id = BuiltInRegistries.BLOCK.getKey(s.getBlock());
        if (!"murim".equals(id.getNamespace())) return null;
        String name = id.getPath();
        if (name.contains("chair") || name.contains("bench") || name.contains("stool") || name.equals("meditation_cushion")) return Kind.SEAT;
        if (name.contains("table") || name.equals("writing_desk")) return Kind.TABLE;
        return null;
    }

    private static Scan scan(Level level, SectLayout layout, String name) {
        double[] half = layout == null ? null : layout.half(name);
        Vec3 center = layout == null ? null : layout.at(name, 0, 0);
        if (center == null || half == null) return null;
        double hx = Math.min(half[0], 40), hz = Math.min(half[1], 40);
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (double u : new double[] {-hx, hx}) for (double v : new double[] {-hz, hz}) {
            BlockPos p = BlockPos.containing(layout.at(name, u, v));
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        Zone zone = new Zone(name, new BlockPos(minX, Math.max(level.getMinBuildHeight(), (int) center.y - 3), minZ),
                new BlockPos(maxX, Math.min(level.getMaxBuildHeight() - 1, (int) center.y + 15), maxZ));
        long now = level.getGameTime();
        Cache cache = CACHES.computeIfAbsent(level, l -> new Cache());
        if (cache.tick != now) {
            cache.tick = now; cache.left = BUDGET;
            cache.claims.entrySet().removeIf(e -> e.getValue().until() <= now);
            cache.zones.entrySet().removeIf(e -> now - e.getValue().accessed > CACHE_TICKS * 3L);
        }
        Scan scan = cache.zones.get(zone);
        if (scan == null || scan.cursor == scan.volume && now - scan.finished >= CACHE_TICKS) {
            scan = new Scan(zone, now); cache.zones.put(zone, scan);
        }
        scan.accessed = now;
        // Round robin between active zones: one shared server-level budget, including late floors.
        for (var entry : cache.zones.entrySet()) {
            Scan part = entry.getValue();
            int allowance = Math.min(cache.left, Math.max(1, BUDGET / cache.zones.size()));
            while (part.cursor < part.volume && allowance-- > 0 && cache.left > 0) {
                int i = part.cursor++; cache.left--;
                BlockPos p = entry.getKey().low().offset(i % part.width, i / (part.width * part.depth), i / part.width % part.depth);
                if (!level.isLoaded(p)) continue;
                Kind kind = kind(level.getBlockState(p));
                if (kind != null) part.found.get(kind).add(p);
            }
            if (part.cursor == part.volume && part.finished == Long.MAX_VALUE) part.finished = now;
        }
        return scan;
    }

    public static BlockPos furniture(SectDisciple npc, String zone, Kind wanted) {
        Scan scan = scan(npc.level(), npc.layout(), zone);
        if (scan == null) return null;
        Cache cache = CACHES.get(npc.level());
        long now = npc.level().getGameTime();
        BlockPos best = null;
        double score = Double.MAX_VALUE;
        for (BlockPos p : scan.found.get(wanted)) {
            if (!npc.level().isLoaded(p)) continue;
            BlockState state = npc.level().getBlockState(p);
            if (kind(state) != wanted || wanted == Kind.BED && state.getValue(BedBlock.OCCUPIED)) continue;
            Claim claim = cache.claims.get(p);
            boolean reserve = wanted == Kind.BED || wanted == Kind.SEAT;
            if (wanted == Kind.BED && npc.level() instanceof net.minecraft.server.level.ServerLevel server
                    && !SectRooms.available(server, p, SectRooms.owner(npc))) continue;
            if (reserve && claim != null && claim.until() > now && !claim.owner().equals(npc.getUUID())) continue;
            Vec3 approach = approach(npc, p);
            if (wanted != Kind.DOOR && wanted != Kind.LADDER && approach == null) continue;
            double distance = p.distToCenterSqr(npc.position());
            if (reserve && claim != null && claim.owner().equals(npc.getUUID())) distance = -1;
            if (distance < score) { best = p; score = distance; }
        }
        if (best != null && wanted == Kind.BED && npc.level() instanceof net.minecraft.server.level.ServerLevel server)
            SectRooms.claim(server, best, SectRooms.owner(npc));
        if (best != null && (wanted == Kind.BED || wanted == Kind.SEAT)) cache.claims.put(best, new Claim(npc.getUUID(), now + 240));
        return best;
    }

    /** Accessible floor immediately beside furniture, respecting slabs and stair height. */
    public static Vec3 approach(SectDisciple npc, BlockPos furniture) {
        return approach(npc.level(), npc.position(), furniture);
    }

    public static Vec3 approach(Level level, Vec3 from, BlockPos furniture) {
        Vec3 best = null;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos p = furniture.relative(d);
            for (int dy = 0; dy >= -1; dy--) {
                BlockPos feet = p.offset(0, dy, 0);
                if (!level.isLoaded(feet) || !SectLife.standable(level, feet)) continue;
                var shape = level.getBlockState(feet.below()).getCollisionShape(level, feet.below());
                Vec3 at = new Vec3(feet.getX() + 0.5D, feet.getY() - 1 + shape.max(Direction.Axis.Y), feet.getZ() + 0.5D);
                if (best == null || from.distanceToSqr(at) < from.distanceToSqr(best)) best = at;
            }
        }
        return best;
    }

    public static List<BlockPos> beds(Level level, SectLayout layout, String zone) {
        Scan scan = scan(level, layout, zone);
        if (scan == null) return List.of();
        return scan.found.get(Kind.BED).stream().filter(p -> level.isLoaded(p)
                && kind(level.getBlockState(p)) == Kind.BED).toList();
    }

    public record Ladder(BlockPos bottom, Vec3 exit) { }

    /** A continuous built ladder with a solid, clear landing; used only after a ground path stalls. */
    public static Ladder ladder(SectDisciple npc, Vec3 target) {
        SectLayout layout = npc.layout();
        if (layout == null || target.y < npc.getY() + 1.5D) return null;
        Ladder best = null;
        double distance = Double.MAX_VALUE;
        for (String zone : SectLayout.SECT_ZONES) {
            if (!layout.inside(zone, npc.position(), 2) && !layout.inside(zone, target, 2)) continue;
            Scan scan = scan(npc.level(), layout, zone);
            if (scan == null) continue;
            for (BlockPos bottom : scan.found.get(Kind.LADDER)) {
                if (Math.abs(bottom.getY() - npc.getY()) > 1 || !npc.level().isLoaded(bottom)
                        || !npc.level().getBlockState(bottom).is(Blocks.LADDER)) continue;
                BlockPos top = bottom;
                for (int i = 0; i < 16 && npc.level().isLoaded(top.above())
                        && npc.level().getBlockState(top.above()).is(Blocks.LADDER); i++) top = top.above();
                if (top.getY() < target.y - 1.5D) continue;
                Vec3 exit = null;
                for (Direction d : Direction.Plane.HORIZONTAL) for (int dy : new int[] {0, 1}) {
                    BlockPos p = top.relative(d).above(dy);
                    if (!npc.level().isLoaded(p) || !SectLife.standable(npc.level(), p)) continue;
                    Vec3 at = SectLife.stand(npc.level(), Vec3.atBottomCenterOf(p));
                    if (Math.abs(at.y - target.y) <= 1.5D && (exit == null || at.distanceToSqr(target) < exit.distanceToSqr(target))) exit = at;
                }
                if (exit == null) continue;
                double cost = bottom.distToCenterSqr(npc.position()) + exit.distanceToSqr(target);
                if (cost < distance) { best = new Ladder(bottom, exit); distance = cost; }
            }
        }
        return best;
    }

    public static Vec3 routine(SectDisciple npc, SectSchedule.Task task, Vec3 fallback) {
        Kind wanted = switch (task.kind()) {
            case EAT, MEDITATE, REST, COUNCIL -> Kind.SEAT;
            case COUNT, RECEIVE -> Kind.TABLE;
            case READ, LECTURE -> Kind.LECTERN;
            case SLEEP -> Kind.BED;
            default -> null;
        };
        if (wanted == null || npc.isSleeping()) return fallback;
        BlockPos furniture = furniture(npc, task.zone(), wanted);
        if (furniture == null && wanted == Kind.SEAT && task.kind() == SectSchedule.Kind.EAT) furniture = furniture(npc, task.zone(), Kind.TABLE);
        if (furniture == null) {
            Vec3 center = npc.layout().at(task.zone(), 0, 0);
            return center == null ? fallback : SectLife.stand(npc.level(), center);
        }
        if (wanted == Kind.SEAT && kind(npc.level().getBlockState(furniture)) == Kind.SEAT) {
            var seat = npc.level().getBlockState(furniture).getCollisionShape(npc.level(), furniture);
            double height = seat.isEmpty() ? 0 : seat.max(Direction.Axis.Y);
            Vec3 top = Vec3.atBottomCenterOf(furniture).add(0, height, 0);
            if (npc.level().noCollision(npc, npc.getBoundingBox().move(top.subtract(npc.position())))) return top;
        }
        Vec3 at = approach(npc, furniture);
        return at == null ? fallback : at;
    }
}
