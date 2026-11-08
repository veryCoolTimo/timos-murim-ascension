package io.github.verycooltimo.murim.quest;

import io.github.verycooltimo.murim.quest.world.HuaDryStreamCave;
import io.github.verycooltimo.murim.quest.world.HuaStele;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Set;

/**
 * Named places in the world that quest data refers to ({@code reach} steps, dialogue notes). A marker is resolved against the
 * live site, so it is right at every rotation. New structures add their marker here: this is the only Java a place needs.
 */
public final class QuestMarkers {

    public static final String CACHE_CAVE_MOUTH = "dry_stream_cave", STELE_LEDGE = "stele_ledge", CACHE_CAMP = "dry_stream_camp";
    /** Quest 2: the hurt disciple on the ledge, the shelter at the foot of its steps. */
    public static final String EAST_SCENE = "east_scene", EAST_SHELTER = "east_shelter";
    public static final String GATE_CART = "gate_cart", GATE_STALL = "gate_stall", DEBT_YARD = "debt_yard",
            STORE_SHELF = "store_shelf", PARCEL_SHED = "parcel_shed";
    public static final Set<String> NAMES = Set.of(CACHE_CAVE_MOUTH, STELE_LEDGE, CACHE_CAMP, EAST_SCENE, EAST_SHELTER, GATE_CART,
            GATE_STALL, DEBT_YARD, STORE_SHELF, PARCEL_SHED);

    private QuestMarkers() {
    }

    /** Slack for the free-cell search around an anchor (it looks up to 4 blocks aside). */
    private static final int SLACK = 8;

    /** The marker's geometric anchor: pure arithmetic, nothing is scanned or loaded. Null when unknown. */
    public static BlockPos anchor(ServerLevel level, String marker) {
        switch (marker) {
            case GATE_CART, GATE_STALL, DEBT_YARD, STORE_SHELF, PARCEL_SHED -> {
                return QuestSites.anchor(level, marker);
            }
            case EAST_SCENE -> {
                var site = MountHuaSites.get(level.getServer());
                var f = site == null ? null : HuaStele.Frame.of(site);
                return f == null ? null : f.pos(5, 3, 0);
            }
            default -> {
                return resolve(level, marker);
            }
        }
    }

    /** True when the marker is loaded and {@code p} is within {@code range} (plus search slack) of it; scans nothing otherwise. */
    public static boolean within(ServerLevel level, String marker, net.minecraft.world.entity.player.Player p, double range) {
        BlockPos a = anchor(level, marker);
        if (a == null || !level.hasChunkAt(a)) return false;
        double dx = p.getX() - (a.getX() + 0.5), dz = p.getZ() - (a.getZ() + 0.5), r = range + SLACK;
        return dx * dx + dz * dz <= r * r;
    }

    public static BlockPos resolve(ServerLevel level, String marker) {
        if (marker.contains("|")) {
            for (String candidate : marker.split("\\|")) {
                BlockPos at = resolve(level, candidate);
                if (at != null) return at;
            }
            return null;
        }
        // Built doorway sides are already anchored/rotated. The offset stays beside the entrance,
        // rather than blocking the walking lane or relying on a guessed building centre.
        if (marker.startsWith("door:")) {
            String[] parts = marker.split(":");
            var layout = io.github.verycooltimo.murim.sect.SectLife.layout(level);
            var file = layout == null ? null : layout.file();
            var door = parts.length == 3 && file != null ? file.door(parts[1]) : null;
            if (door == null || door.sideA().isEmpty() || door.sideB().isEmpty()) return null;
            try {
                boolean bOutside = door.roomB() == null;
                BlockPos outside = (bOutside ? door.sideB() : door.sideA()).get(0);
                BlockPos inside = (bOutside ? door.sideA() : door.sideB()).get(0);
                int dx = Integer.signum(outside.getX() - inside.getX());
                int dz = Integer.signum(outside.getZ() - inside.getZ());
                int side = Integer.parseInt(parts[2]);
                return outside.offset(dz * side, 0, -dx * side);
            } catch (NumberFormatException ignored) { return null; }
        }
        if (marker.startsWith("zone:")) {
            String[] parts = marker.split(":");
            if (parts.length != 4) return null;
            var layout = io.github.verycooltimo.murim.sect.SectLife.layout(level);
            if (layout == null) return null;
            try {
                var at = layout.at(parts[1], Double.parseDouble(parts[2]), Double.parseDouble(parts[3]));
                if (at == null) return null;
                var file = layout.file();
                var zone = file == null ? null : file.zone(parts[1]);
                if (zone != null) {
                    var centre = layout.at(parts[1], 0, 0);
                    var rect = zone.rect();
                    return BlockPos.containing((rect.x0() + rect.x1()) / 2.0 + at.x - centre.x,
                            zone.y(), (rect.z0() + rect.z1()) / 2.0 + at.z - centre.z);
                }
                return BlockPos.containing(at);
            } catch (NumberFormatException ignored) { return null; }
        }
        if (!HuaDryStreamCave.ENABLED && (CACHE_CAVE_MOUTH.equals(marker) || CACHE_CAMP.equals(marker))) return null;
        var site = MountHuaSites.get(level.getServer());
        if (site == null) return null;
        switch (marker) {
            case EAST_SCENE -> {
                var f = HuaStele.Frame.of(site);
                return f == null ? null : ClueSites.freeFloor(level, f.pos(5, 3, 0), 3);
            }
            case EAST_SHELTER -> {
                var f = HuaStele.Frame.of(site);
                return f == null ? null : f.pos(0, HuaStele.HALF + HuaStele.STEPS - 2, 0);
            }
            case GATE_CART, GATE_STALL, DEBT_YARD, STORE_SHELF, PARCEL_SHED -> {
                return QuestSites.resolve(level, marker);
            }
            case CACHE_CAVE_MOUTH -> {
                var f = HuaDryStreamCave.Frame.of(site);
                return f == null ? null : f.pos(2, 0, 0);
            }
            case CACHE_CAMP -> {
                var f = HuaDryStreamCave.Frame.of(site);
                return f == null ? null : f.pos(64, 2, 4);
            }
            case STELE_LEDGE -> {
                var f = HuaStele.Frame.of(site);
                return f == null ? null : f.pos(0, 0, 0);
            }
            default -> {
                return null;
            }
        }
    }
}
