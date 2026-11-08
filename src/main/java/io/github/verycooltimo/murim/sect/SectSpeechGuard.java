package io.github.verycooltimo.murim.sect;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Transient, per speaker speech budget. No save fields or client state. */
public final class SectSpeechGuard {
    public static final long REPEAT_TICKS = 120 * 20L;
    public static final long GREETING_TICKS = 60 * 20L;
    public static final long DEED_TICKS = 60 * 20L;
    public static final Set<String> RETIRED = Set.of(
            "murim.bubble.wander_tip.1",
            "murim.bubble.wander_tip.2",
            "murim.bubble.wander_tip.3",
            "murim.bubble.wander_tip.4",
            "murim.bubble.wander_tip.5",
            "murim.bubble.wander_tip.6",
            "murim.bubble.alarm_servant.2",
            "murim.bubble.alarm_servant.3",
            "murim.bubble.alarm_servant.4",
            "murim.bubble.alarm_servant.5",
            "murim.bubble.alarm_servant.6",
            "murim.bubble.busy.3",
            "murim.bubble.busy.4",
            "murim.bubble.busy.5",
            "murim.bubble.busy.6",
            "murim.bubble.council_busy.2",
            "murim.bubble.council_busy.3",
            "murim.bubble.council_busy.4",
            "murim.bubble.council_busy.5",
            "murim.bubble.council_busy.6",
            "murim.bubble.guard_cover.2",
            "murim.bubble.guard_cover.3",
            "murim.bubble.guard_cover.4",
            "murim.bubble.guard_cover.6",
            "murim.bubble.guide_stuck.2",
            "murim.bubble.guide_stuck.3",
            "murim.bubble.guide_stuck.5",
            "murim.bubble.guide_stuck.6",
            "murim.bubble.guide_wait.2",
            "murim.bubble.guide_wait.3",
            "murim.bubble.guide_wait.4",
            "murim.bubble.bow_winner.3",
            "murim.bubble.bow_winner.4",
            "murim.bubble.bow_winner.5",
            "murim.bubble.bow_winner.6",
            "murim.bubble.social.acknowledge.2",
            "murim.bubble.social.acknowledge.5",
            "murim.bubble.social.acknowledge.6",
            "murim.bubble.social.first_peer.4",
            "murim.bubble.social.first_peer.5",
            "murim.bubble.social.first_peer.6",
            "murim.bubble.social.art_peer.1",
            "murim.bubble.social.art_peer.5",
            "murim.bubble.social.art_peer.6",
            "murim.bubble.social.elder_peer.3",
            "murim.bubble.social.elder_peer.5",
            "murim.bubble.watch_trait.glutton.5",
            "murim.bubble.watch_trait.glutton.6",
            "murim.bubble.watch_trait.eager.6",
            "murim.bubble.watch.5"
    );
    private final Map<String, Long> heard = new HashMap<>();
    private record Witness(long at, String event) {}
    private final Map<String, Witness> witnessed = new HashMap<>();
    private final Map<String, Long> answered = new HashMap<>();
    private long greetingAt = Long.MIN_VALUE / 2;

    public static boolean active(String key) {
        return !key.isEmpty() && !RETIRED.contains(key) && !ungrounded(key);
    }

    /** Title-only praise and generic congratulations have no witnessed deed producer. */
    private static boolean ungrounded(String key) {
        return (key.startsWith("murim.bubble.") || key.startsWith("murim.approach.bubble."))
                && !key.startsWith("murim.approach.bubble.congrats.")
                && !key.startsWith("murim.approach.bubble.office.congrats.")
                && (key.contains("congrats") || key.contains("praise") || key.contains("merit"));
    }

    public static boolean greeting(String key) {
        return key.contains("greet") || key.startsWith("murim.bubble.gate_nod.")
                || key.startsWith("murim.bubble.guard_bow_head.")
                || key.startsWith("murim.bubble.social.respect.")
                || key.startsWith("murim.bubble.wander_recruit.")
                || key.startsWith("murim.bubble.bow_senior.")
                || key.startsWith("murim.bubble.social.first_")
                || key.startsWith("murim.bubble.social.elder_");
    }

    /** A family without an event producer has no permission and stays silent. */
    public static String deed(String key) {
        if (key.startsWith("murim.bubble.lesson.")) return "lesson";
        if (key.startsWith("murim.bubble.lesson_ack.")) return "progress";
        if (key.startsWith("murim.bubble.contract.done.")) return "contract";
        if (key.startsWith("murim.react.realm_up.")) return "realm_up";
        if (key.startsWith("murim.react.star_up.")) return "star_up";
        if (key.startsWith("murim.react.bout_won.")) return "bout_won";
        if (key.startsWith("murim.react.contract.")) return "contract";
        if (key.startsWith("murim.bubble.bow_winner.") || key.startsWith("murim.bubble.council_review.")) return "review";
        if (key.startsWith("murim.bubble.social.acknowledge.")) return "realm_up";
        if (key.startsWith("murim.approach.bubble.congrats.") || key.startsWith("murim.approach.bubble.office.congrats.")) return "bout_won";
        if (key.contains("merit") || key.contains("praise") || key.contains("congrats")) return "praise";
        return "";
    }

    public void witness(String deed, long now) {
        witness(deed, now, deed + ":" + now);
    }

    public void witness(String deed, long now, String event) {
        witnessed.entrySet().removeIf(e -> now - e.getValue().at() > DEED_TICKS);
        answered.entrySet().removeIf(e -> now - e.getValue() > DEED_TICKS);
        witnessed.put(deed, new Witness(now, event));
    }

    public boolean witnessed(String deed, long now) {
        Witness receipt = witnessed.get(deed);
        return receipt != null && now >= receipt.at() && now - receipt.at() <= DEED_TICKS
                && !answered.containsKey(receipt.event());
    }

    public boolean allow(String key, long now) {
        if (!active(key)) return false;
        // Quest rumours own a finite, per-listener wall-clock repeat schedule in Overheard.
        if (key.startsWith("murim.bubble.quest.")) return true;
        Long last = heard.get(key);
        if (last != null && now - last < REPEAT_TICKS) return false;
        if (greeting(key) && now - greetingAt < GREETING_TICKS) return false;
        String deed = deed(key);
        if (!deed.isEmpty() && !witnessed(deed, now)) return false;
        // Keep memory bounded by the current two-minute window, including rare scripted barks.
        heard.entrySet().removeIf(e -> now - e.getValue() >= REPEAT_TICKS);
        heard.put(key, now);
        if (greeting(key)) greetingAt = now;
        if (!deed.isEmpty()) answered.put(witnessed.get(deed).event(), now);
        return true;
    }
}
