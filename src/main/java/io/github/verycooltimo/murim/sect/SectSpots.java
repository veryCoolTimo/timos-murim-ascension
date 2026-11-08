package io.github.verycooltimo.murim.sect;

import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Work spots as resources (docs/design/38, section 5.5): one person per spot, first come first served. A claim is
 * a lease that the owner renews every time the routine is resolved; a person who walks away, unloads or dies loses
 * the spot after {@link #TTL} ticks. An owner holds one spot at a time: claiming another frees the first.
 *
 * <p>Pure bookkeeping (no Minecraft types except the per-level holder), so it is unit tested.
 */
public final class SectSpots {

    /** Lease length in ticks; the routine is resolved about once a second. */
    public static final long TTL = 120L;

    private record Claim(String owner, long until) {
    }

    private record MealSeat(String spot, long until) { }
    private final Map<String, MealSeat> mealSeats = new HashMap<>();

    /** A meal reservation survives ordinary lease expiry and temporary interruptions. */
    public String mealSeat(String owner, long now) {
        MealSeat seat = mealSeats.get(owner);
        return seat != null && seat.until() > now ? seat.spot() : null;
    }

    public void reserveMeal(String owner, String spot, long until) {
        mealSeats.put(owner, new MealSeat(spot, until));
    }

    private final Map<String, Claim> bySpot = new HashMap<>();
    private final Map<String, String> byOwner = new HashMap<>();

    /** Who holds the spot now, or null. */
    public String holder(String spot, long now) {
        Claim c = bySpot.get(spot);
        return c == null || c.until() <= now ? null : c.owner();
    }

    /** The spot is free for {@code owner}: nobody holds it, or the owner does. */
    public boolean freeFor(String spot, String owner, long now) {
        for (var entry : mealSeats.entrySet()) {
            if (!entry.getKey().equals(owner) && entry.getValue().until() > now && entry.getValue().spot().equals(spot)) return false;
        }
        String h = holder(spot, now);
        return h == null || h.equals(owner);
    }

    /** The spot the owner holds now, or null. */
    public String heldBy(String owner, long now) {
        String spot = byOwner.get(owner);
        return spot != null && owner.equals(holder(spot, now)) ? spot : null;
    }

    /**
     * Claim or renew. False when somebody else holds the spot. A successful claim frees the owner's previous spot.
     */
    public boolean claim(String spot, String owner, long now) {
        if (!freeFor(spot, owner, now)) {
            return false;
        }
        String prev = byOwner.get(owner);
        if (prev != null && !prev.equals(spot)) {
            Claim c = bySpot.get(prev);
            if (c != null && c.owner().equals(owner)) {
                bySpot.remove(prev);
            }
        }
        bySpot.put(spot, new Claim(owner, now + TTL));
        byOwner.put(owner, spot);
        return true;
    }

    public void release(String owner) {
        mealSeats.remove(owner);
        String spot = byOwner.remove(owner);
        if (spot != null) {
            Claim c = bySpot.get(spot);
            if (c != null && c.owner().equals(owner)) {
                bySpot.remove(spot);
            }
        }
    }

    /** Number of live claims (tests, debug output). */
    public int live(long now) {
        int n = 0;
        for (Claim c : bySpot.values()) {
            if (c.until() > now) {
                n++;
            }
        }
        return n;
    }

    private static final Map<Level, SectSpots> PER_LEVEL = new WeakHashMap<>();

    /** The claims of one level. Server thread only. */
    public static SectSpots of(Level level) {
        return PER_LEVEL.computeIfAbsent(level, l -> new SectSpots());
    }
}
