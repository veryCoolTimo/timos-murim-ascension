package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Asks the real pathfinder of a person whether every hop of the waypoint graph can be walked, in both directions
 * (docs/adr/0017). The graph is made from the blocks by a script that has its own idea of what a mob can step over; the walker
 * follows it hop by hop with vanilla pathfinding, so a hop the pathfinder cannot do is a stair that leads nowhere, and a hop it does
 * with a long detour is a person who takes another way (another stair) than the route says. Run in the built world through
 * {@code /murim dev sched audit}; one log line per bad hop and one summary line, all starting with {@code Sect-audit:}.
 *
 * <p>Three kinds of hop are checked: along every edge of the graph (waypoint, the cells the extractor kept in {@code via},
 * waypoint), from every place to the door or stair end the router would start him from, and from there back.
 */
public final class SectRouteAudit {

    /** A path may have this many cells beyond the straight run before it counts as a detour. */
    static final double DETOUR_FACTOR = 1.6D;
    static final int DETOUR_SLACK = 6;

    private SectRouteAudit() {
    }

    /** Result of one audit. */
    public record Report(int hops, int unreachable, int detours, int indoorUnreachable, List<String> lines) {
    }

    /** Audit the built sect of this level; null when there is none. */
    public static Report run(ServerLevel level, SectLayoutFile file) {
        if (file == null) {
            return null;
        }
        SectDisciple probe = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), level);
        probe.setKeepAwake(true);
        List<String> lines = new ArrayList<>();
        int[] count = new int[4];
        for (SectLayoutFile.Edge e : file.edges()) {
            // A big gate is not a door to the pathfinder: the walker opens it with an OPEN leg of its own (SectRouter), so the hops
            // of an edge that crosses one are not asked of the pathfinder.
            if (e.crosses().stream().anyMatch(id -> file.door(id) != null && (file.door(id).gate() || file.door(id).kind().contains("gate") || file.door(id).kind().contains("grand")))) {
                continue;
            }
            List<BlockPos> pts = new ArrayList<>();
            pts.add(file.waypoint(e.a()).pos());
            pts.addAll(e.via());
            pts.add(file.waypoint(e.b()).pos());
            for (int i = 0; i + 1 < pts.size(); i++) {
                String what = "edge " + e.a() + ">" + e.b() + " hop " + (i + 1) + "/" + (pts.size() - 1) + (e.stairs() ? " stairs" : "");
                check(file, level, probe, pts.get(i), pts.get(i + 1), what, count, lines);
                if (!e.oneWay()) {
                    check(file, level, probe, pts.get(i + 1), pts.get(i), what + " back", count, lines);
                }
            }
        }
        for (SectLayoutFile.Spot s : file.spots()) {
            if (!"ok".equals(s.reach())) {
                continue;
            }
            SectLayoutFile.Room room = file.room(s.room());
            if (room == null) {
                continue;
            }
            BlockPos from = s.walkTo();
            String best = null;
            double bestD = Double.MAX_VALUE;
            for (String id : room.portals()) {
                SectLayoutFile.Waypoint w = file.waypoint(id);
                if (w != null && w.pos().distSqr(from) < bestD) {
                    bestD = w.pos().distSqr(from);
                    best = id;
                }
            }
            if (best != null) {
                BlockPos to = file.waypoint(best).pos();
                String what = "spot " + s.id() + " " + s.kind() + " in " + s.room() + " to its nearest portal " + best;
                check(file, level, probe, from, to, what, count, lines);
                check(file, level, probe, to, from, what + " back", count, lines);
            }
        }
        for (SectLayoutFile.Bed b : file.beds()) {
            SectLayoutFile.Room room = file.room(b.room());
            if (room == null) {
                continue;
            }
            String best = null;
            double bestD = Double.MAX_VALUE;
            for (String id : room.portals()) {
                SectLayoutFile.Waypoint w = file.waypoint(id);
                if (w != null && w.pos().distSqr(b.approach()) < bestD) {
                    bestD = w.pos().distSqr(b.approach());
                    best = id;
                }
            }
            if (best != null) {
                BlockPos to = file.waypoint(best).pos();
                String what = "bed " + b.id() + " in " + b.room() + " to its nearest portal " + best;
                check(file, level, probe, b.approach(), to, what, count, lines);
                check(file, level, probe, to, b.approach(), what + " back", count, lines);
            }
        }
        String summary = "Sect-audit: " + count[0] + " hops checked, " + count[1] + " the pathfinder cannot walk, " + count[2] + " walked with a detour";
        lines.add(summary);
        MurimMod.LOGGER.info(summary);
        return new Report(count[0], count[1], count[2], count[3], lines);
    }

    /** What stands beyond the last cell the pathfinder reached, towards the goal: the block that stops him. */
    private static String blockedAt(ServerLevel level, Path path, BlockPos to) {
        var end = path.getEndNode();
        if (end == null) {
            return "no end node";
        }
        BlockPos e = end.asBlockPos();
        BlockPos next = e.offset(Integer.signum(to.getX() - e.getX()), 0, Integer.signum(to.getZ() - e.getZ()));
        StringBuilder sb = new StringBuilder("stops at ").append(e.toShortString()).append(" before");
        for (int dy = -1; dy <= 2; dy++) {
            sb.append(' ').append(dy).append(':').append(level.getBlockState(next.above(dy)).getBlock().builtInRegistryHolder().key().location().getPath());
        }
        return sb.toString();
    }

    private static void check(SectLayoutFile file, ServerLevel level, SectDisciple probe, BlockPos from, BlockPos to, String what, int[] count, List<String> lines) {
        if (from.equals(to)) {
            return;
        }
        level.getChunk(from);      // a stamped site in a test level is not kept loaded: load what the pathfinder will read
        level.getChunk(to);
        if (!level.isLoaded(from) || !level.isLoaded(to)) {
            return;
        }
        count[0]++;
        Vec3 at = SectLife.stand(level, Vec3.atBottomCenterOf(from));
        probe.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        probe.setOnGround(true);        // PathNavigation#canUpdatePath: a probe that was never ticked is not on the ground
        probe.getNavigation().stop();
        if (probe.getNavigation() instanceof net.minecraft.world.entity.ai.navigation.GroundPathNavigation ground) {
            ground.setCanOpenDoors(true);        // as the walker does (SectWalking#walkDirect)
        }
        Vec3 goal = SectLife.stand(level, Vec3.atBottomCenterOf(to));
        Path path = probe.getNavigation().createPath(BlockPos.containing(goal), 1);
        int run = Math.max(Math.abs(to.getX() - from.getX()), Math.abs(to.getZ() - from.getZ()));
        String where = " from " + from.toShortString() + " to " + to.toShortString();
        if (path == null || !path.canReach()) {
            count[1]++;
            if (file.roomAt(from) != null && file.roomAt(to) != null) {
                count[3]++;      // both ends inside a building: the stamped sect is the same as the world's there
            }
            String line = "Sect-audit: UNREACHABLE " + what + where + (path == null ? " no path" : " partial path of " + path.getNodeCount() + " nodes, "
                    + blockedAt(level, path, to));
            lines.add(line);
            MurimMod.LOGGER.warn(line);
        } else if (path.getNodeCount() > run * DETOUR_FACTOR + DETOUR_SLACK) {
            count[2]++;
            String line = "Sect-audit: DETOUR " + what + where + " " + path.getNodeCount() + " nodes for a run of " + run;
            lines.add(line);
            MurimMod.LOGGER.warn(line);
        }
    }
}
