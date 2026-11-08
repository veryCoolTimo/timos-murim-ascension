package io.github.verycooltimo.murim.sect;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the people of the sect have already said to one player (docs/design/39-player-path-v2.md, «Реплики: объём и
 * частота»). Player attachment, saved with the player and kept through death; never synced to the client.
 *
 * <ul>
 *   <li>{@code heard}: per line pool the last few lines this player heard, each packed as {@code sect day << 32 | hash of
 *       the key}. {@link SectLines} reads it, so the same line is not repeated within a day.</li>
 *   <li>{@code events}: per event id the game time (or the episode id of a world event) of its last occurrence.</li>
 *   <li>{@code reacted}: per event id a bit mask over {@link SectRoster#index()} of the people who already reacted to the
 *       last occurrence; recording the event again clears it.</li>
 * </ul>
 * Bounded by construction: {@link #KEEP} lines per pool, {@link #MAX_POOLS} pools, the roster size for the masks.
 */
public record SectMemory(Map<String, List<Long>> heard, Map<String, Long> events, Map<String, Long> reacted) {

    public static final SectMemory EMPTY = new SectMemory(Map.of(), Map.of(), Map.of());

    public static final Codec<SectMemory> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(Codec.STRING, Codec.LONG.listOf()).optionalFieldOf("heard", Map.of()).forGetter(SectMemory::heard),
            Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("events", Map.of()).forGetter(SectMemory::events),
            Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("reacted", Map.of()).forGetter(SectMemory::reacted)
    ).apply(i, SectMemory::new));

    /** Lines remembered per pool (the biggest pool has eight lines). */
    public static final int KEEP = 12;
    /** Pools remembered; the oldest are forgotten first. */
    public static final int MAX_POOLS = 192;

    public SectMemory {
        heard = Map.copyOf(heard);
        events = Map.copyOf(events);
        reacted = Map.copyOf(reacted);
    }

    public static long pack(long day, String key) {
        return (day << 32) | (key.hashCode() & 0xFFFFFFFFL);
    }

    public static long dayOf(long packed) {
        return packed >>> 32;
    }

    public static int hashOf(long packed) {
        return (int) packed;
    }

    public List<Long> heard(String pool) {
        return heard.getOrDefault(pool, List.of());
    }

    /** The player heard {@code key} from {@code pool} on sect day {@code day}. */
    public SectMemory withHeard(String pool, long day, String key) {
        List<Long> list = new ArrayList<>(heard.getOrDefault(pool, List.of()));
        long packed = pack(day, key);
        if (!list.isEmpty() && list.get(list.size() - 1) == packed) {
            return this;
        }
        list.add(packed);
        while (list.size() > KEEP) {
            list.remove(0);
        }
        Map<String, List<Long>> next = new HashMap<>(heard);
        next.put(pool, List.copyOf(list));
        while (next.size() > MAX_POOLS) {
            String oldest = null;
            long oldestDay = Long.MAX_VALUE;
            for (Map.Entry<String, List<Long>> e : next.entrySet()) {
                long newest = e.getValue().isEmpty() ? -1L : dayOf(e.getValue().get(e.getValue().size() - 1));
                if (!e.getKey().equals(pool) && newest < oldestDay) {
                    oldestDay = newest;
                    oldest = e.getKey();
                }
            }
            next.remove(oldest);
        }
        return new SectMemory(next, events, reacted);
    }

    public long event(String id) {
        return events.getOrDefault(id, Long.MIN_VALUE);
    }

    /** The event happened (again) at {@code stamp}: nobody has reacted to this occurrence yet. */
    public SectMemory withEvent(String id, long stamp) {
        Map<String, Long> e = new HashMap<>(events);
        e.put(id, stamp);
        Map<String, Long> r = new HashMap<>(reacted);
        r.remove(id);
        return new SectMemory(heard, e, r);
    }

    /** A bookkeeping stamp that is not an event (no mask to reset). */
    public SectMemory withStamp(String id, long stamp) {
        Map<String, Long> e = new HashMap<>(events);
        e.put(id, stamp);
        return new SectMemory(heard, e, reacted);
    }

    public long reacted(String id) {
        return reacted.getOrDefault(id, 0L);
    }

    public SectMemory withReacted(String id, long mask) {
        Map<String, Long> r = new HashMap<>(reacted);
        r.put(id, mask);
        return new SectMemory(heard, events, r);
    }
}
