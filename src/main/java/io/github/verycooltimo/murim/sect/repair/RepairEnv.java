package io.github.verycooltimo.murim.sect.repair;

import io.github.verycooltimo.murim.Config;
import io.github.verycooltimo.murim.sect.SectLayoutFile;
import io.github.verycooltimo.murim.sect.SectRooms;
import io.github.verycooltimo.murim.world.hua.HuaBaked;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * What the repair rules ask of the world, as plain questions, so the same rules run in the sect and in a test yard:
 * the settings, where the sect grounds are, which block the baked sect expects at a position, and which rooms belong to
 * players. {@link #real} reads the live server; a GameTest builds its own with {@link #of}.
 */
public record RepairEnv(Settings settings, Scope scope, Source expected, Protection protection, RepairCosts costs) {

    /** The toggles. {@code graceTicks}: how long a hole stays before the crew takes it up (a quarter of an in-game day since 19.10). */
    public record Settings(boolean enabled, long graceTicks) {
        /** One in-game day. */
        public static final long DAY = 24000L;
        /** Default grace: a quarter of a day (playtest 19.10: a whole day was longer than the tester stayed to watch). */
        public static final long GRACE = DAY / 4L;
    }

    /** The sect grounds: {@code repairable} is where a hole is mended; {@code tracked} is where player building is noted. */
    public interface Scope {
        boolean repairable(BlockPos pos);

        boolean tracked(BlockPos pos);

        /** A scope of one box (tests); tracked is the box grown by {@code margin}. */
        static Scope box(BoundingBox box, int margin) {
            BoundingBox wide = new BoundingBox(box.minX() - margin, box.minY() - margin, box.minZ() - margin,
                    box.maxX() + margin, box.maxY() + margin, box.maxZ() + margin);
            return new Scope() {
                @Override
                public boolean repairable(BlockPos pos) {
                    return box.isInside(pos);
                }

                @Override
                public boolean tracked(BlockPos pos) {
                    return wide.isInside(pos);
                }
            };
        }
    }

    /** The block the baked sect holds at a position, null where it says nothing. */
    public interface Source {
        HuaBaked.Expected at(BlockPos pos);
    }

    /** Rooms players live in: a position there is never touched. */
    public interface Protection {
        boolean room(BlockPos pos);

        Protection NONE = pos -> false;
    }

    public static RepairEnv of(Settings settings, Scope scope, Source expected, Protection protection) {
        return new RepairEnv(settings, scope, expected, protection, RepairCosts.shipped());
    }

    /** The settings of the config file. */
    public static Settings configured() {
        return new Settings(Config.SECT_REPAIRS.get(), Config.SECT_REPAIR_GRACE.get());
    }

    // ------------------------------------------------------------------ the live sect

    /**
     * The grounds of the built sect: its buildings (the wall boxes grown by six blocks, so that the crater of a blast at the wall is inside; from the lowest floor minus six to
     * the highest plus twenty-four, which holds the roofs) and its yards (the zone rectangles, six below to sixteen above
     * the paving). The mountain body round them is terrain, not the sect, and nobody mends it. The penance cave is
     * sealed and left out.
     */
    static Scope grounds(SectLayoutFile file) {
        List<BoundingBox> boxes = new ArrayList<>();
        for (SectLayoutFile.Building b : file.buildings().values()) {
            int minY = Integer.MAX_VALUE;
            int maxY = Integer.MIN_VALUE;
            for (String id : b.rooms()) {
                SectLayoutFile.Room r = file.room(id);
                if (r != null) {
                    minY = Math.min(minY, r.y());
                    maxY = Math.max(maxY, r.y());
                }
            }
            for (SectLayoutFile.Rect rect : b.boxes()) {
                int lo = minY;
                int hi = maxY;
                if (lo == Integer.MAX_VALUE) {
                    // a building without rooms (the sect gate): the floors its walking points stand on
                    for (SectLayoutFile.Waypoint w : file.waypoints()) {
                        if (rect.contains(w.pos().getX(), w.pos().getZ())) {
                            lo = Math.min(lo, w.pos().getY());
                            hi = Math.max(hi, w.pos().getY());
                        }
                    }
                }
                if (lo != Integer.MAX_VALUE) {
                    boxes.add(new BoundingBox(rect.x0() - 6, lo - 6, rect.z0() - 6, rect.x1() + 6, hi + 24, rect.z1() + 6));
                }
            }
        }
        for (SectLayoutFile.Zone z : file.zones()) {
            if (!"penance".equals(z.id())) {
                boxes.add(new BoundingBox(z.rect().x0(), z.y() - 6, z.rect().z0(), z.rect().x1(), z.y() + 16, z.rect().z1()));
            }
        }
        List<BoundingBox> wide = new ArrayList<>();
        for (BoundingBox b : boxes) {
            wide.add(new BoundingBox(b.minX() - 3, b.minY() - 3, b.minZ() - 3, b.maxX() + 3, b.maxY() + 3, b.maxZ() + 3));
        }
        return new Scope() {
            @Override
            public boolean repairable(BlockPos pos) {
                for (BoundingBox b : boxes) {
                    if (b.isInside(pos)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public boolean tracked(BlockPos pos) {
                for (BoundingBox b : wide) {
                    if (b.isInside(pos)) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    /**
     * The rooms of players: the room that holds a player's bed (its floor rectangle grown by one block for the walls,
     * two below and eight above), or six blocks round a bed the layout file does not know. A player who sleeps on a bedroll
     * has no room of his own yet: the dormitories he may be given a bed in stay untouched.
     */
    static Protection rooms(ServerLevel level, SectLayoutFile file) {
        // worked out on the first question only: most calls (an event) never ask
        return new Protection() {
            private List<BoundingBox> boxes;

            @Override
            public boolean room(BlockPos pos) {
                if (boxes == null) {
                    boxes = roomBoxes(level, file);
                }
                for (BoundingBox b : boxes) {
                    if (b.isInside(pos)) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    private static List<BoundingBox> roomBoxes(ServerLevel level, SectLayoutFile file) {
        List<BoundingBox> boxes = new ArrayList<>();
        for (BlockPos bed : SectRooms.playerBeds(level)) {
            SectLayoutFile.Bed record = file == null ? null : file.bedAt(bed);
            SectLayoutFile.Room room = record != null && record.room() != null ? file.room(record.room()) : file == null ? null : file.roomAt(bed);
            if (room != null) {
                SectLayoutFile.Rect r = room.bounds();
                boxes.add(new BoundingBox(r.x0() - 1, room.y() - 2, r.z0() - 1, r.x1() + 1, room.y() + 8, r.z1() + 1));
            } else {
                boxes.add(new BoundingBox(bed.getX() - 6, bed.getY() - 3, bed.getZ() - 6, bed.getX() + 6, bed.getY() + 6, bed.getZ() + 6));
            }
        }
        if (file != null && SectRooms.playerMats(level)) {
            for (String dorm : List.of("dorm_3rd", "dorm_2nd")) {
                SectLayoutFile.Building b = file.building(dorm);
                if (b != null) {
                    for (SectLayoutFile.Rect r : b.boxes()) {
                        boxes.add(new BoundingBox(r.x0() - 1, Integer.MIN_VALUE / 2, r.z0() - 1, r.x1() + 1, Integer.MAX_VALUE / 2, r.z1() + 1));
                    }
                }
            }
        }
        return boxes;
    }

    /**
     * The environment of the running server, or null when this world has no baked sect to compare with (no mountain, an old
     * world on the world seed, the files missing). The grounds are cached in the data by the site frame.
     */
    static RepairEnv real(ServerLevel level, RepairData data) {
        MinecraftServer server = level.getServer();
        MountHuaSite site = io.github.verycooltimo.murim.world.hua.MountHuaSites.get(server);
        if (site == null || !HuaBaked.applies(server, site)) {
            return null;
        }
        SectLayoutFile file = SectLayoutFile.anchored(site);
        if (file == null) {
            return null;
        }
        String key = site.centerX() + "/" + site.centerZ() + "/" + site.baseY() + "/" + site.rotation();
        RepairEnv cached = data.env;
        if (cached == null || !key.equals(data.envKey)) {
            cached = new RepairEnv(configured(), grounds(file), pos -> HuaBaked.expectedAt(server, site, pos), Protection.NONE,
                    RepairCosts.shipped());
            data.env = cached;
            data.envKey = key;
        }
        return new RepairEnv(configured(), cached.scope(), cached.expected(), rooms(level, file), cached.costs());
    }
}
