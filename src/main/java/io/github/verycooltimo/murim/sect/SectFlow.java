package io.github.verycooltimo.murim.sect;

import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Narrow passages as shared resources (docs/design/38 section 5.4 and 5.5): a one-wide stair carries people in one
 * direction at a time, a door of width one or two lets {@link #DOOR_CAPACITY} people through at once. Whoever cannot
 * enter waits where he stands. Leases run out by themselves ({@link #TTL}), so a walker who dies or unloads never
 * blocks a stair for good.
 */
public final class SectFlow {

    public static final int DOOR_CAPACITY = 2;
    public static final int STAIR_CAPACITY = 3;
    public static final long TTL = 80L;

    /** A resource to pass: id, capacity, and whether directions exclude each other (stairs). */
    public record Need(String id, int capacity, boolean directional) {
    }

    private record Use(String owner, int dir, long until) {
    }

    private final Map<String, List<Use>> uses = new HashMap<>();

    private List<Use> live(String id, long now) {
        List<Use> l = uses.computeIfAbsent(id, k -> new ArrayList<>());
        l.removeIf(u -> u.until() <= now);
        return l;
    }

    private boolean fits(Need n, String owner, int dir, long now) {
        List<Use> l = live(n.id(), now);
        int others = 0;
        for (Use u : l) {
            if (u.owner().equals(owner)) {
                continue;
            }
            others++;
            if (n.directional() && u.dir() != dir) {
                return false;
            }
        }
        return others < n.capacity();
    }

    /** Enter all resources at once or none. A holder renews by calling again. */
    public boolean enter(List<Need> needs, String owner, int dir, long now) {
        for (Need n : needs) {
            if (!fits(n, owner, dir, now)) {
                return false;
            }
        }
        for (Need n : needs) {
            List<Use> l = live(n.id(), now);
            l.removeIf(u -> u.owner().equals(owner));
            l.add(new Use(owner, dir, now + TTL));
        }
        return true;
    }

    public void leave(List<Need> needs, String owner) {
        for (Need n : needs) {
            List<Use> l = uses.get(n.id());
            if (l != null) {
                l.removeIf(u -> u.owner().equals(owner));
            }
        }
    }

    public int occupants(String id, long now) {
        return live(id, now).size();
    }

    private static final Map<Level, SectFlow> PER_LEVEL = new WeakHashMap<>();

    public static SectFlow of(Level level) {
        return PER_LEVEL.computeIfAbsent(level, l -> new SectFlow());
    }
}
