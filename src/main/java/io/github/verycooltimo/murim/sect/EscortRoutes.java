package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.world.hua.MountHuaPlan;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The three routes of the escort contract (Track C, docs/design/research/hua-main-1810.md section 8: «сопровождение всегда идёт
 * одним маршрутом»): the merchant is walked from the sect gate to one of three places, one of them for each sect day in turn.
 * Every route is a list of waypoints in world coordinates, made from data the mountain already has, never from numbers
 * of its own:
 *
 * <ul>
 *   <li>{@link #TREASURY}: inside the built sect, the plan of the walkers' own router ({@link SectRouter#plan}) over the
 *       layout graph from the inner side of the gate to the treasury yard: the short walk of the day;</li>
 *   <li>{@link #NORTH}: down the one trail ({@link MountHuaPlan#TRAIL}, the points the trail sprint is run on) as far as the
 *       shoulder of the North Peak: the middle one;</li>
 *   <li>{@link #FOOT}: the whole trail to the stall at the foot of the mountain: the long one, the contract as it was.</li>
 * </ul>
 *
 * <p>The trail routes use the very points of {@link io.github.verycooltimo.murim.training.Routes#trail} (the route tool that is
 * walked and checked on the real mountain); the inner route is the walkers' graph, checked by {@code verify_layout.py}. A
 * unit test ({@code EscortRoutesTest}) holds each route to its geometry. Nothing here changes the terrain.
 */
public final class EscortRoutes {

    public static final String TREASURY = "treasury";
    public static final String NORTH = "north";
    public static final String FOOT = "foot";
    public static final List<String> IDS = List.of(TREASURY, NORTH, FOOT);

    /** The index of the trail point where the North Peak route ends (the shoulder under the pavilion, {@code (-12, -310)}). */
    public static final int NORTH_END = 7;

    /** The merchant talks at a waypoint at least this far (blocks, flat) from the last one he talked at. */
    public static final double TALK_SPACING = 45.0D;

    /** A route: the waypoints in walking order, the gate first and the destination last. */
    public record Route(String id, List<Vec3> points) {

        public Vec3 end() {
            return points.get(points.size() - 1);
        }

        public BlockPos endCell() {
            return BlockPos.containing(end());
        }

        /**
         * Where the merchant says a word on the way: every waypoint at least {@link #TALK_SPACING} blocks on from the one before
         * (the gate itself is the start line, the destination has its own words). Order of the walk.
         */
        public List<Vec3> talkingPoints() {
            List<Vec3> out = new ArrayList<>();
            Vec3 last = points.get(0);
            for (int i = 1; i < points.size() - 1; i++) {
                Vec3 p = points.get(i);
                if (flat(last, p) >= TALK_SPACING && flat(p, end()) >= TALK_SPACING * 0.5D) {
                    out.add(p);
                    last = p;
                }
            }
            return out;
        }

        /** Length of the walk along the waypoints, flat. */
        public double length() {
            double sum = 0.0D;
            for (int i = 1; i < points.size(); i++) {
                sum += flat(points.get(i - 1), points.get(i));
            }
            return sum;
        }
    }

    /** The mentor and newcomer meet the head together, just in front of his seat. */
    static Vec3 headIntroduction(SectLayout layout) {
        return layout == null ? null : layout.at("main_hall", 0.0D, 0.5D);
    }

    private EscortRoutes() {
    }

    static double flat(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    /**
     * The route of this id on this mountain, or null when it cannot be made (no built sect for the inner route, no layout
     * route between the gate and the yard).
     */
    public static Route build(String id, MountHuaSite site, SectLayoutFile file, BlockPos stall) {
        return switch (id) {
            case TREASURY -> file == null ? null : treasury(file);
            case NORTH -> trail(NORTH, site, NORTH_END, null);
            case FOOT -> trail(FOOT, site, 0, stall);
            default -> null;
        };
    }

    /** Every route that can be made here, in the order of {@link #IDS}. */
    public static List<Route> all(MountHuaSite site, SectLayoutFile file, BlockPos stall) {
        List<Route> out = new ArrayList<>();
        for (String id : IDS) {
            Route r = site == null ? null : build(id, site, file, stall);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    /** The trail from its sect end down to point {@code lastIndex}, then {@code finish} (the stall) when given. */
    static Route trail(String id, MountHuaSite site, int lastIndex, BlockPos finish) {
        List<Vec3> points = new ArrayList<>();
        List<MountHuaPlan.TrailPoint> trail = MountHuaPlan.TRAIL;
        for (int i = trail.size() - 1; i >= lastIndex; i--) {
            MountHuaPlan.TrailPoint t = trail.get(i);
            int[] w = site.toWorld(t.u(), t.v());
            points.add(new Vec3(w[0] + 0.5D, site.worldY(site.shape().height(t.u(), t.v())) + 1.0D, w[1] + 0.5D));
        }
        if (finish != null) {
            points.add(new Vec3(finish.getX() + 0.5D, finish.getY(), finish.getZ() + 0.5D));
        }
        return new Route(id, List.copyOf(points));
    }

    /** From the inner side of the gate to the treasury yard over the walkers' graph. */
    static Route treasury(SectLayoutFile file) {
        SectLayoutFile.Waypoint gate = file.anchor("gate_inside").orElse(null);
        SectLayoutFile.Zone yard = file.zone("treasury_yard");
        if (gate == null || yard == null) {
            return null;
        }
        SectLayoutFile.Rect r = yard.rect();
        BlockPos to = new BlockPos((r.x0() + r.x1()) / 2, yard.y(), (r.z0() + r.z1()) / 2);
        SectRouter.Plan plan = SectRouter.plan(file, gate.pos(), to, Set.of());
        if (plan == null || plan.legs().size() < 2) {
            return null;
        }
        List<Vec3> points = new ArrayList<>();
        // The router starts from whichever waypoint near the gate gives the cheapest way, and the completed graph has corridor waypoints
        // a dozen blocks on: the escort begins at the inner side of the gate whatever the first leg is.
        points.add(new Vec3(gate.pos().getX() + 0.5D, gate.pos().getY(), gate.pos().getZ() + 0.5D));
        for (SectRouter.Leg leg : plan.legs()) {
            Vec3 p = new Vec3(leg.x, leg.y, leg.z);
            if (points.isEmpty() || flat(points.get(points.size() - 1), p) > 0.5D) {
                points.add(p);
            }
        }
        points.add(new Vec3(to.getX() + 0.5D, to.getY(), to.getZ() + 0.5D));
        return new Route(TREASURY, List.copyOf(points));
    }
}
