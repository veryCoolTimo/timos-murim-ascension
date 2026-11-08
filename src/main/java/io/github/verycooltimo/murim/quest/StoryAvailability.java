package io.github.verycooltimo.murim.quest;

import java.util.Map;

/** Release offer policy, independent of saved quest progress. ADR 0022; MVP wave 1 R5.
 * Fragments story stays parked; surviving fragment sources still award the hidden advancement.
 */
public final class StoryAvailability {
    public static final Map<String, Boolean> ISSUED = Map.ofEntries(
            Map.entry("changed_breath", true), Map.entry("red_vein", true),
            Map.entry("second_strike", true), Map.entry("trials", true), Map.entry("vault", true),
            // Herb definitions only (no story, no offer): the wild herbs live in the world from the start.
            Map.entry("wild_herbs", true),
            Map.entry("fragments", false),
            Map.entry("cache", false), Map.entry("debt", false), Map.entry("impostor", false),
            Map.entry("missing", false), Map.entry("strike", false), Map.entry("thief", false));

    public static boolean issued(String id) { return ISSUED.getOrDefault(id, false); }

    /** Dialogue node ids use the story id followed by an underscore. */
    public static boolean nodeAllowed(String node) {
        return ISSUED.entrySet().stream().noneMatch(e -> !e.getValue()
                && (node.equals(e.getKey()) || node.startsWith(e.getKey() + "_")));
    }
    private StoryAvailability() { }
}
