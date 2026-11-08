package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.entity.SectDisciple;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Meal traffic, server only and transient. Source: docs/adr/0023-sect-playtest-2510.md. */
public final class SectDining {
    private record Departure(long ready, SectLife.Resolved meal) { }
    private static final Map<Level, Map<SectDisciple, Departure>> LEAVING = new WeakHashMap<>();
    private final SectDisciple npc;
    private Vec3 exit;
    private boolean rose;

    public SectDining(SectDisciple npc) { this.npc = npc; }

    private static Map<SectDisciple, Departure> departures(Level level) {
        var people = LEAVING.computeIfAbsent(level, l -> new WeakHashMap<>());
        people.keySet().removeIf(d -> !d.isAlive() || d.isRemoved());
        return people;
    }

    public boolean leaving() { return departures(npc.level()).containsKey(npc); }

    public boolean begin(SectLife.Resolved meal) {
        var spot = SectLife.spotOf(npc, meal);
        if (spot == null || !"dining_hall".equals(spot.building())) return false;
        int order = Math.max(0, SectRoster.ALL.indexOf(npc.member().orElseThrow()));
        departures(npc.level()).put(npc, new Departure(npc.level().getGameTime() + order * 8L, meal));
        SectSpots.of(npc.level()).claim(meal.spotId(), npc.memberKey(), npc.level().getGameTime());
        npc.walking().stop();
        exit = null;
        rose = false;
        return true;
    }

    public void cancel() {
        departures(npc.level()).remove(npc);
        exit = null;
        rose = false;
    }

    /** One leaver uses the aisle at a time; the others stay in their seats, renewing their leases. */
    public boolean tick() {
        var people = departures(npc.level());
        Departure own = people.get(npc);
        if (own == null) return true;
        long now = npc.level().getGameTime();
        SectSpots.of(npc.level()).claim(own.meal().spotId(), npc.memberKey(), now);
        var first = people.entrySet().stream().min(Comparator
                .comparingLong((Map.Entry<SectDisciple, Departure> e) -> e.getValue().ready())
                .thenComparing(e -> e.getKey().memberKey())).orElseThrow();
        if (first.getKey() != npc || now < own.ready()) return false;
        if (!rose) {
            npc.standUp();
            rose = true;
            return false;
        }
        // The seat exit glide must finish before pathfinding: supported endpoints alone are insufficient (CLAUDE.md).
        if (npc.isNoGravity() || !npc.onGround()) return false;
        if (exit == null) exit = reachableExit(npc, false);
        if (exit == null) return false;
        if (!npc.walking().walk(exit, 0.65D)) return false;
        SectSpots.of(npc.level()).release(npc.memberKey());
        cancel();
        return true;
    }

    /** Latecomers yield outside the doorway, away from the outgoing lane. */
    public static boolean waitForAisle(SectDisciple npc) {
        if (npc.sitting() || departures(npc.level()).isEmpty()) return false;
        if (npc.onGround() && !npc.isNoGravity()) {
            Vec3 waiting = reachableExit(npc, true);
            if (waiting != null) npc.walking().walk(waiting, 0.5D);
            else npc.walking().stop();
        }
        return true;
    }

    /** Find an exterior door, then a clear supported point with a COMPLETE path from the grounded walker. */
    private static Vec3 reachableExit(SectDisciple npc, boolean waiting) {
        SectLayoutFile f = SectLife.file(npc);
        if (f == null) return null;
        List<BlockPos> candidates = new ArrayList<>();
        for (var door : f.doors()) {
            var a = door.roomA() == null ? null : f.room(door.roomA());
            var b = door.roomB() == null ? null : f.room(door.roomB());
            boolean hallA = a != null && "dining_hall".equals(a.building());
            boolean hallB = b != null && "dining_hall".equals(b.building());
            if (hallA == hallB) continue;
            var out = hallA ? door.sideB() : door.sideA();
            var in = hallA ? door.sideA() : door.sideB();
            if (in.isEmpty()) continue;
            for (var cell : out) {
                int dx = Integer.signum(cell.getX() - in.get(0).getX());
                int dz = Integer.signum(cell.getZ() - in.get(0).getZ());
                // The door normal is its thin dimension, even for a double leaf.
                if (door.min().getX() == door.max().getX()) dz = 0;
                else dx = 0;
                BlockPos away = cell.offset(dx * 2, 0, dz * 2);
                if (waiting) {
                    candidates.add(away.offset(-dz * 3, 0, dx * 3));
                    candidates.add(away.offset(dz * 3, 0, -dx * 3));
                } else {
                    // Finished diners may have no next destination. Spread them beyond the door
                    // instead of parking the whole queue on the same cell in the outgoing lane.
                    for (int forward = -1; forward <= 3; forward++) {
                        for (int across = -10; across <= 10; across++) {
                            candidates.add(away.offset(dx * forward - dz * across, 0, dz * forward + dx * across));
                        }
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(p -> Vec3.atBottomCenterOf(p).distanceToSqr(npc.position())));
        for (BlockPos cell : candidates) {
            if (!npc.level().isLoaded(cell) || !SectLife.standable(npc.level(), cell)) continue;
            Vec3 at = Vec3.atBottomCenterOf(cell);
            if (!npc.level().noCollision(npc, npc.getBoundingBox().move(at.subtract(npc.position())))) continue;
            // noCollision checks block/vehicle collision; ordinary NPC bodies also need their own space.
            if (!npc.level().getEntitiesOfClass(SectDisciple.class, npc.getBoundingBox().move(at.subtract(npc.position())).inflate(0.3D),
                    other -> other != npc && other.isAlive()).isEmpty()) continue;
            // API: SectAlarm#safePoint and SectStuckGameTests (createPath/canReach).
            var path = npc.getNavigation().createPath(cell, 0);
            if (path != null && path.canReach()) return at;
        }
        return null;
    }
}
