package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import net.minecraft.world.phys.Vec3;

/**
 * Day-simulation probe (tools/build/sect/sim/meal_sim.py): one log line the first time a person reaches the place of a
 * task, with when the task was handed to him and how far he came. Off unless the JVM runs with
 * {@code -Dmurim.sect.timings=true}, so a normal log stays quiet.
 */
public final class SectTimings {

    public static final boolean ON = Boolean.getBoolean("murim.sect.timings");

    private SectTimings() {
    }

    /**
     * @param assignedAt world day time when the task (kind and place) was handed to him
     * @param from       where he stood then
     * @param to         the place of the task
     * @param cells      length of the route the walker planned for it, -1 when it walked plain
     * @param waited     ticks he stood at a passage queue or a gate on the way; {@code repaths}: stuck re-plans on the way
     */
    public static void arrived(SectDisciple npc, SectSchedule.Kind kind, long assignedAt, Vec3 from, Vec3 to, double cells, long waited, int repaths) {
        long now = npc.level().getDayTime();
        int wave = SectRoster.of(npc.memberKey()).map(m -> SectPlaces.secondWave(m) ? 2 : 1).orElse(1);
        MurimMod.LOGGER.info("Sect-timing: member={} wave={} kind={} assigned={} arrived={} walk={} cells={} waited={} repaths={} from={},{},{} to={},{},{}",
                npc.memberKey(), wave, kind, Math.floorMod(assignedAt, (long) SectSchedule.DAY), Math.floorMod(now, (long) SectSchedule.DAY),
                now - assignedAt, Math.round(cells), waited, repaths, Math.round(from.x), Math.round(from.y), Math.round(from.z), Math.round(to.x),
                Math.round(to.y), Math.round(to.z));
    }

    /**
     * Stair probe: a person who stands on a stair end (bottom or top waypoint of a flight) that his planned route does not
     * use took the wrong stairs. One line per person and flight, logged again only after he has left the flight.
     */
    public static void stairWatch(SectDisciple npc, SectLayoutFile file, boolean routed, java.util.function.Predicate<net.minecraft.core.BlockPos> onRoute) {
        if (npc.tickCount % 10 != 0) {
            return;
        }
        // Only a person who is walking somewhere counts; one standing at the end of a flight because his place is there does not.
        var goal = npc.walking().goal();
        if (goal == null || npc.getDeltaMovement().horizontalDistanceSqr() < 4.0E-4D || goal.distanceToSqr(npc.position()) < 9.0D) {
            return;
        }
        // A person who was moved (relocated by the stuck rescue, put in a bed) did not walk onto the flight.
        net.minecraft.world.phys.Vec3 before = LAST_POS.put(npc.getUUID(), npc.position());
        if (before != null && before.distanceToSqr(npc.position()) > 9.0D) {
            LAST_STAIR.remove(npc.getUUID());
            return;
        }
        String at = null;
        net.minecraft.core.BlockPos end = null;
        for (SectLayoutFile.Waypoint w : file.waypoints()) {
            if (w.kind().startsWith("stair") && w.ref() != null && Math.abs(w.pos().getY() - npc.getY()) < 1.5D
                    && Math.hypot(w.pos().getX() + 0.5D - npc.getX(), w.pos().getZ() + 0.5D - npc.getZ()) < 1.3D) {
                at = w.ref();
                end = w.pos();
                break;
            }
        }
        String last = LAST_STAIR.get(npc.getUUID());
        if (at == null) {
            if (last != null) {
                LAST_STAIR.remove(npc.getUUID());
            }
            return;
        }
        if (at.equals(last)) {
            return;
        }
        LAST_STAIR.put(npc.getUUID(), at);
        if (routed && !onRoute.test(end)) {
            MurimMod.LOGGER.info("Sect-stair: member={} flight={} planned=route-misses at={},{},{} doing={}", npc.memberKey(), at,
                    Math.round(npc.getX()), Math.round(npc.getY()), Math.round(npc.getZ()), npc.doingKind());
        }
    }

    private static final java.util.Map<java.util.UUID, net.minecraft.world.phys.Vec3> LAST_POS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, String> LAST_STAIR = new java.util.concurrent.ConcurrentHashMap<>();
}
