package io.github.verycooltimo.murim.sect;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The way from the sect court to a disciple's own room, computed from the layout file (docs/design/39-player-path-v2.md,
 * author 19.10: «как игрок поймёт, где эта кровать, там все комнаты одинаковые»): which house, which entrance door
 * (left or right as seen from outside), which floor, and «the Nth door on the left» counted along the corridor from the
 * stair the route arrives by. The player can repeat it on foot; the tour shows it in four shots (house, inside, the door
 * with the name tablet, the bed) and the mentor names each step.
 *
 * <p>Pure data work on the anchored {@link SectLayoutFile}: no world access, so it works at every rotation.
 */
public final class SectDormPath {

    /** How the entrance door sits in the facade seen from outside. */
    public enum Side {
        LEFT, RIGHT, ONLY
    }

    /**
     * @param building     building id ({@code dorm_3rd})
     * @param floor        floor of the room counted from the entrance floor, 1 = the entrance floor
     * @param entranceSide the entrance door as seen from the court
     * @param ordinal      the room door is the Nth door on its side of the corridor, counted from the stair the route arrives by
     * @param doorLeft     that side is the left one when walking from the stair
     * @param entrance     the exterior door to use
     * @param roomDoor     the door of the bed's room
     * @param room         the bed's room
     * @param corridor     the corridor in front of the door (null if the room opens onto a hall)
     * @param toCorridor   direction from the room door out to the corridor
     * @param corridorCells standing cells in front of the room door
     * @param route        waypoint ids from outside the entrance to the corridor side of the room door
     * @param arrival      where the route enters the corridor level
     */
    public record Path(String building, int floor, Side entranceSide, int ordinal, boolean doorLeft, SectLayoutFile.Door entrance,
                       SectLayoutFile.Door roomDoor, SectLayoutFile.Room room, SectLayoutFile.Room corridor, Direction toCorridor,
                       List<BlockPos> corridorCells, List<String> route, BlockPos arrival) {
    }

    private SectDormPath() {
    }

    /** The path to the bed whose head block is {@code head}, or empty (not a layout bed, no door, no route). */
    public static Optional<Path> of(SectLayoutFile file, BlockPos head) {
        SectLayoutFile.Bed bed = file == null ? null : file.bedAt(head);
        SectLayoutFile.Room room = bed == null ? null : file.room(bed.room());
        if (room == null) {
            return Optional.empty();
        }
        // the room's door to a corridor or hall
        SectLayoutFile.Door roomDoor = null;
        SectLayoutFile.Room corridor = null;
        for (String id : room.doors()) {
            SectLayoutFile.Door d = file.door(id);
            SectLayoutFile.Room other = d == null ? null : file.room(room.id().equals(d.roomA()) ? d.roomB() : d.roomA());
            if (other != null && (roomDoor == null || "corridor".equals(other.function()) && !"corridor".equals(corridor.function()))) {
                roomDoor = d;
                corridor = other;
            }
        }
        if (roomDoor == null) {
            return Optional.empty();
        }
        boolean roomIsA = room.id().equals(roomDoor.roomA());
        List<BlockPos> inRoom = roomIsA ? roomDoor.sideA() : roomDoor.sideB();
        List<BlockPos> outside = roomIsA ? roomDoor.sideB() : roomDoor.sideA();
        if (inRoom.isEmpty() || outside.isEmpty()) {
            return Optional.empty();
        }
        Direction toCorridor = toward(inRoom.get(0), outside.get(0));
        String goal = roomDoor.id() + (roomIsA ? ".b" : ".a");
        // the exterior doors of the building and the cheapest one to this room
        SectLayoutFile.Building building = file.building(bed.building());
        List<SectLayoutFile.Door> exits = new ArrayList<>();
        if (building != null) {
            for (String id : building.doors()) {
                SectLayoutFile.Door d = file.door(id);
                if (d != null && (d.roomA() == null) != (d.roomB() == null) && !"opening".equals(d.kind())) {
                    exits.add(d);
                }
            }
        }
        SectLayoutFile.Door entrance = null;
        SectLayoutFile.Route best = null;
        for (SectLayoutFile.Door d : exits) {
            SectLayoutFile.Route r = file.route(d.id() + (d.roomB() == null ? ".b" : ".a"), goal);
            if (r != null && (best == null || r.cost() < best.cost())) {
                best = r;
                entrance = d;
            }
        }
        if (entrance == null || best == null) {
            return Optional.empty();
        }
        SectLayoutFile.Room inside = file.room(entrance.roomA() != null ? entrance.roomA() : entrance.roomB());
        int floor = inside == null ? 1 : room.floor() - inside.floor() + 1;
        // the facade as seen from outside
        boolean entranceIsB = entrance.roomB() != null;
        List<BlockPos> outCells = entranceIsB ? entrance.sideA() : entrance.sideB();
        List<BlockPos> inCells = entranceIsB ? entrance.sideB() : entrance.sideA();
        Side side = Side.ONLY;
        if (!outCells.isEmpty() && !inCells.isEmpty() && building != null && !building.boxes().isEmpty()) {
            Direction out = toward(inCells.get(0), outCells.get(0));
            SectLayoutFile.Rect box = building.boxes().get(0);
            double bx = (box.x0() + box.x1() + 1) / 2.0D;
            double bz = (box.z0() + box.z1() + 1) / 2.0D;
            Vec3 c = center(entrance);
            // looking at the building the view direction is -out; the right hand of a view (dx, dz) is (-dz, dx)
            double vx = -out.getStepX();
            double vz = -out.getStepZ();
            double across = (c.x - bx) * -vz + (c.z - bz) * vx;
            side = exits.size() > 1 && Math.abs(across) > 0.5D ? (across > 0 ? Side.RIGHT : Side.LEFT) : Side.ONLY;
        }
        // the corridor leg: where the route arrives on the corridor level and which door of the wall it is
        BlockPos arrival = null;
        for (String node : best.nodes()) {
            SectLayoutFile.Waypoint w = file.waypoint(node);
            if (w != null && corridor.id().equals(w.room())) {
                arrival = w.pos();
                break;
            }
        }
        int ordinal = 1;
        boolean left = false;
        if (arrival != null) {
            SectLayoutFile.Rect rb = corridor.bounds();
            boolean alongX = rb.x1() - rb.x0() >= rb.z1() - rb.z0();
            Vec3 target = center(roomDoor);
            double ta = alongX ? arrival.getX() + 0.5D : arrival.getZ() + 0.5D;
            double tt = alongX ? target.x : target.z;
            int sign = tt >= ta ? 1 : -1;
            Direction walk = alongX ? (sign > 0 ? Direction.EAST : Direction.WEST) : (sign > 0 ? Direction.SOUTH : Direction.NORTH);
            // the room door is on the side opposite to toCorridor: its offset from the corridor's axis is -toCorridor
            double rightX = -walk.getStepZ();
            double rightZ = walk.getStepX();
            left = -toCorridor.getStepX() * rightX + -toCorridor.getStepZ() * rightZ < 0;
            ordinal = 0;
            for (String id : corridor.doors()) {
                SectLayoutFile.Door d = file.door(id);
                if (d == null) {
                    continue;
                }
                boolean dIsA = corridor.id().equals(d.roomA());
                List<BlockPos> corridorSide = dIsA ? d.sideA() : d.sideB();
                List<BlockPos> otherSide = dIsA ? d.sideB() : d.sideA();
                if (corridorSide.isEmpty() || otherSide.isEmpty() || toward(otherSide.get(0), corridorSide.get(0)) != toCorridor) {
                    continue;
                }
                Vec3 c = center(d);
                double t = alongX ? c.x : c.z;
                if ((t - ta) * sign >= -0.6D && (t - tt) * sign <= 0.6D) {
                    ordinal++;
                }
            }
            ordinal = Math.max(1, ordinal);
        }
        return Optional.of(new Path(bed.building(), Math.max(1, floor), side, ordinal, left, entrance, roomDoor, room, corridor, toCorridor,
                List.copyOf(outside), List.copyOf(best.nodes()), arrival));
    }

    /** Horizontal direction from one cell towards a neighbouring one. */
    static Direction toward(BlockPos from, BlockPos to) {
        return Direction.getNearest(to.getX() - from.getX(), 0, to.getZ() - from.getZ());
    }

    /** Middle of a door's cells, at the floor of its lower half. */
    static Vec3 center(SectLayoutFile.Door d) {
        return new Vec3((d.min().getX() + d.max().getX() + 1) / 2.0D, d.min().getY(), (d.min().getZ() + d.max().getZ() + 1) / 2.0D);
    }

    // ------------------------------------------------------------------ words

    /** The house, entrance, floor and door of the mentor's lines (dialogue arguments {@code tour_*}). */
    public static Component arg(Optional<Path> path, String key) {
        if (path.isEmpty()) {
            return Component.translatable("murim.sect.tour.any." + key.substring("tour_".length()));
        }
        Path p = path.get();
        return switch (key) {
            case "tour_dorm" -> Component.translatable("murim.sect.tour.dorm." + p.building());
            case "tour_floor" -> Component.translatable("murim.sect.tour.floor." + Math.min(p.floor(), 6));
            default -> Component.empty();
        };
    }

    /** Standing cell in front of the room door, middle of the span. */
    public static BlockPos front(Path p) {
        return p.corridorCells().get(p.corridorCells().size() / 2);
    }

    /** Cells ordered along the wall for the tablet candidates: left end, right end (as seen from the corridor) beyond the door. */
    public static List<BlockPos> sorted(List<BlockPos> cells, Direction toCorridor) {
        List<BlockPos> out = new ArrayList<>(cells);
        Direction lateral = toCorridor.getClockWise();
        out.sort(Comparator.comparingInt(c -> c.getX() * lateral.getStepX() + c.getZ() * lateral.getStepZ()));
        return out;
    }
}
