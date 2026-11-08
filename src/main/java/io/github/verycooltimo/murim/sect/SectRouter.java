package io.github.verycooltimo.murim.sect;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Turns a path over the waypoint graph into legs the walker follows one by one (docs/design/38 section 5.4):
 * vanilla pathfinding only ever covers one short leg, so a person cannot lose his way between buildings. Closed
 * big gates (not vanilla doors for the pathfinder) become explicit OPEN legs. Pure: no level access.
 */
public final class SectRouter {

    public enum Type {
        WALK, OPEN
    }

    /** One step of a plan. {@code edge}: index into the plan's edges, -1 for the approach and the final target. */
    public static final class Leg {
        public final Type type;
        public final double x;
        public final int y;
        public final double z;
        public final String door;
        public final int edge;
        public boolean edgeStart;
        public boolean edgeEnd;
        /**
         * Set on the first leg of an edge: +1 when the edge climbs in the direction walked, -1 when it descends, 0 level
         * (from the heights of its two waypoints, so it holds for an edge with no intermediate cells too).
         */
        public int dir;

        Leg(Type type, BlockPos at, String door, int edge) {
            this.type = type;
            this.x = at.getX() + 0.5D;
            this.y = at.getY();
            this.z = at.getZ() + 0.5D;
            this.door = door;
            this.edge = edge;
        }
    }

    public record Plan(List<Leg> legs, List<SectLayoutFile.Edge> edges, double cost) {
        /** Length still to walk from {@code (x, z)} when the walker is at leg {@code from}, then to the target. */
        public double remaining(double x, double z, int from, double tx, double tz) {
            double sum = 0.0D;
            double px = x;
            double pz = z;
            for (int i = from; i < legs.size(); i++) {
                Leg l = legs.get(i);
                sum += Math.hypot(l.x - px, l.z - pz);
                px = l.x;
                pz = l.z;
            }
            return sum + Math.hypot(tx - px, tz - pz);
        }
    }

    private SectRouter() {
    }

    /** Plan from a cell to a cell; null when the graph has no route (the walker then uses plain pathfinding). */
    public static Plan plan(SectLayoutFile f, BlockPos from, BlockPos to, Set<Integer> banned) {
        return plan(f, from, to, banned, null);
    }

    /** As {@link #plan(SectLayoutFile, BlockPos, BlockPos, Set)}; {@code clear} tells whether two cells see each other. */
    public static Plan plan(SectLayoutFile f, BlockPos from, BlockPos to, Set<Integer> banned,
                            java.util.function.BiPredicate<BlockPos, BlockPos> clear) {
        List<SectLayoutFile.Link> starts = f.linksFor(from, null, clear);
        List<SectLayoutFile.Link> goals = f.linksFor(to, null, clear);
        if (starts.isEmpty() || goals.isEmpty()) {
            return null;
        }
        SectLayoutFile.Route route = f.route(starts, goals, banned);
        if (route == null) {
            return null;
        }
        List<Leg> legs = new ArrayList<>();
        legs.add(new Leg(Type.WALK, f.waypoint(route.nodes().get(0)).pos(), null, -1));
        List<SectLayoutFile.Edge> edges = new ArrayList<>();
        for (int i = 0; i < route.edges().size(); i++) {
            SectLayoutFile.Edge e = route.edges().get(i);
            String fromNode = route.nodes().get(i);
            boolean forward = e.a().equals(fromNode);
            BlockPos pa = f.waypoint(fromNode).pos();
            BlockPos pb = f.waypoint(route.nodes().get(i + 1)).pos();
            int index = edges.size();
            edges.add(e);
            int first = legs.size();
            List<String> crosses = new ArrayList<>(e.crosses());
            List<BlockPos> via = new ArrayList<>(e.via());
            if (!forward) {
                Collections.reverse(crosses);
                Collections.reverse(via);
            }
            for (String id : crosses) {
                SectLayoutFile.Door d = f.door(id);
                if (d != null && d.gate()) {
                    BlockPos near = closest(d, pa, true);
                    BlockPos far = closest(d, pa, false);
                    legs.add(new Leg(Type.WALK, near, null, index));
                    legs.add(new Leg(Type.OPEN, near, id, index));
                    legs.add(new Leg(Type.WALK, far, null, index));
                }
            }
            for (BlockPos v : via) {
                legs.add(new Leg(Type.WALK, v, null, index));
            }
            legs.add(new Leg(Type.WALK, pb, null, index));
            legs.get(first).edgeStart = true;
            legs.get(first).dir = direction(f, e, forward);
            legs.get(legs.size() - 1).edgeEnd = true;
        }
        return new Plan(legs, edges, route.cost());
    }

    /** The side cell of a door nearest to {@code ref} ({@code near}) or the nearest cell of the opposite side. */
    private static BlockPos closest(SectLayoutFile.Door d, BlockPos ref, boolean near) {
        BlockPos bestA = best(d.sideA(), ref);
        BlockPos bestB = best(d.sideB(), ref);
        if (bestA == null || bestB == null) {
            return bestA != null ? bestA : bestB != null ? bestB : d.min();
        }
        boolean aNear = bestA.distSqr(ref) <= bestB.distSqr(ref);
        return near == aNear ? bestA : bestB;
    }

    private static BlockPos best(List<BlockPos> list, BlockPos ref) {
        BlockPos b = null;
        for (BlockPos p : list) {
            if (b == null || p.distSqr(ref) < b.distSqr(ref)) {
                b = p;
            }
        }
        return b;
    }

    /**
     * The passages an edge occupies: doors of width one or two and one-wide stairs. Gates and wide doors are free.
     * {@code dir} for stairs: +1 up, -1 down.
     */
    public static List<SectFlow.Need> needs(SectLayoutFile f, SectLayoutFile.Edge e) {
        List<SectFlow.Need> out = new ArrayList<>();
        for (String id : e.crosses()) {
            SectLayoutFile.Door d = f.door(id);
            if (d != null && !d.gate() && d.width() <= 2) {
                out.add(new SectFlow.Need(id, SectFlow.DOOR_CAPACITY, false));
            }
        }
        SectLayoutFile.Waypoint a = f.waypoint(e.a());
        SectLayoutFile.Waypoint b = f.waypoint(e.b());
        if (a != null && b != null && a.kind().startsWith("stair") && b.kind().startsWith("stair") && a.ref() != null && a.ref().equals(b.ref())) {
            SectLayoutFile.Stair s = f.stair(a.ref());
            if (s != null && s.width() <= 1) {
                out.add(new SectFlow.Need(s.id(), SectFlow.STAIR_CAPACITY, true));
            }
        }
        return out;
    }

    /** +1 when the edge climbs in the direction walked, -1 when it descends, 0 level. */
    public static int direction(SectLayoutFile f, SectLayoutFile.Edge e, boolean forward) {
        SectLayoutFile.Waypoint a = f.waypoint(forward ? e.a() : e.b());
        SectLayoutFile.Waypoint b = f.waypoint(forward ? e.b() : e.a());
        return Integer.signum(b.pos().getY() - a.pos().getY());
    }
}
