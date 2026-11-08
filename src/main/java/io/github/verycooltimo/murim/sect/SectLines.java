package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Picking a line the player has not heard lately (docs/design/39-player-path-v2.md, «Реплики: объём и частота»).
 *
 * <p>Every pool of lines the sect says on its own (walk-up approaches, bubbles, reactions) is a lang stem with keys
 * {@code stem.1 .. stem.N}, N at least {@link #MIN_VARIANTS} (data test {@code SectLinesTest}). {@link #pick} chooses among
 * the lines this player did not hear <em>today</em>; when the pool is used up it avoids the most recent half, and in the
 * worst case only the last line. What was heard is kept in the player's {@link SectMemory} attachment, so it survives a
 * restart and is per player (two players on one server never spoil each other's variety).
 */
public final class SectLines {
    /** Every pool the player hears more than about once per sect day has at least this many lines. */
    public static final int MIN_VARIANTS = 6;

    private SectLines() {
    }

    /** Keys {@code stem.1 .. stem.count}. */
    public static List<String> keys(String stem, int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> stem + "." + i).filter(SectSpeechGuard::active).toList();
    }

    /** {@code murim.bubble.meal.3} gives {@code murim.bubble.meal}; a key without a number is its own pool. */
    public static String poolOf(String key) {
        int dot = key.lastIndexOf('.');
        if (dot > 0 && dot < key.length() - 1 && key.substring(dot + 1).chars().allMatch(Character::isDigit)) {
            return key.substring(0, dot);
        }
        return key;
    }

    /** The common stem of the keys; keys of different pools (a mix of rumours) share one bookkeeping pool. */
    public static String commonPool(List<String> keys) {
        if (keys.isEmpty()) {
            return "";
        }
        String first = poolOf(keys.get(0));
        for (String k : keys) {
            if (!poolOf(k).equals(first)) {
                return "mixed";
            }
        }
        return first;
    }

    /**
     * The lines that may be said now: the ones not heard on {@code day}; if every line was, the ones outside the most recent
     * half of the pool; if that leaves nothing, everything but the very last line. Pure: tests run it on plain lists.
     */
    public static List<String> allowed(List<String> keys, List<Long> memory, long day) {
        if (keys.size() <= 1 || memory.isEmpty()) {
            return keys;
        }
        Set<Integer> today = new HashSet<>();
        for (long packed : memory) {
            if (SectMemory.dayOf(packed) == day) {
                today.add(SectMemory.hashOf(packed));
            }
        }
        List<String> fresh = keys.stream().filter(k -> !today.contains(k.hashCode())).toList();
        if (!fresh.isEmpty()) {
            return fresh;
        }
        int window = Math.min(keys.size() - 1, Math.max(1, keys.size() / 2));
        Set<Integer> recent = new HashSet<>();
        for (int i = Math.max(0, memory.size() - window); i < memory.size(); i++) {
            recent.add(SectMemory.hashOf(memory.get(i)));
        }
        List<String> rested = keys.stream().filter(k -> !recent.contains(k.hashCode())).toList();
        if (!rested.isEmpty()) {
            return rested;
        }
        int last = SectMemory.hashOf(memory.get(memory.size() - 1));
        List<String> other = keys.stream().filter(k -> k.hashCode() != last).toList();
        return other.isEmpty() ? keys : other;
    }

    /** One of {@code keys} not heard lately by this player; the choice among the allowed ones follows {@code roll}. The pick is remembered. */
    public static String pick(ServerPlayer p, String pool, List<String> keys, int roll) {
        if (keys.isEmpty()) {
            return "";
        }
        long day = day(p);
        List<String> ok = allowed(keys, p.getData(ModAttachments.SECT_MEMORY).heard(pool), day);
        String key = ok.get(Math.floorMod(roll, ok.size()));
        hear(p, pool, key);
        return key;
    }

    /** The line {@code stem.1 .. stem.count} for this player. */
    public static String pick(ServerPlayer p, String stem, int count, int roll) {
        return pick(p, stem, keys(stem, count), roll);
    }

    public static String pick(ServerPlayer p, String stem, int count, RandomSource random) {
        return pick(p, stem, count, random.nextInt(1 << 16));
    }

    /** Same, for a list of keys; the pool is their common stem. */
    public static String pick(ServerPlayer p, List<String> keys, int roll) {
        return pick(p, commonPool(keys), keys, roll);
    }

    /** The player heard this line (said aloud near him, or addressed to him). */
    public static void hear(ServerPlayer p, String pool, String key) {
        SectMemory m = p.getData(ModAttachments.SECT_MEMORY);
        SectMemory next = m.withHeard(pool, day(p), key);
        if (next != m) {
            p.setData(ModAttachments.SECT_MEMORY, next);
        }
    }

    /** A bubble reached this player: remember it under the pool of its key (keys without a number are not pools). */
    public static void hear(ServerPlayer p, String key) {
        if (poolOf(key).equals(key)) {
            return;
        }
        hear(p, poolOf(key), key);
    }

    /** Lines of {@code keys} that this player heard on the current sect day (tests and the doc table). */
    public static List<String> heardToday(ServerPlayer p, String pool, List<String> keys) {
        List<String> out = new ArrayList<>();
        long day = day(p);
        for (long packed : p.getData(ModAttachments.SECT_MEMORY).heard(pool)) {
            if (SectMemory.dayOf(packed) == day) {
                for (String k : keys) {
                    if (k.hashCode() == SectMemory.hashOf(packed) && !out.contains(k)) {
                        out.add(k);
                    }
                }
            }
        }
        return out;
    }

    static long day(ServerPlayer p) {
        return SectSchedule.day(p.level().getDayTime());
    }
}
