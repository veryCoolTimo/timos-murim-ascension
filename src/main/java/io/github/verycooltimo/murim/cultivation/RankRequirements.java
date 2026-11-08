package io.github.verycooltimo.murim.cultivation;

import net.minecraft.resources.ResourceLocation;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What a breakthrough asks of the player's art, as ONE table of stars (author 18.10, D-4, hua-main-1810 §10): it replaces
 * the old tiers derived from {@code legacySteps}. The numbers are for an art of twelve stars; an art with fewer stars
 * (footwork has ten) takes the same share of its own stars, rounded: breakthrough 3 ★6 → ★5, the Peak ★8 → ★7.
 *
 * <pre>
 *   gate       breakthrough            12-star art   10-star art
 *   THIRD      1  (into the third rank)     ★1            ★1
 *   —          2  (the fortress boss)       —             —
 *   FIRST      3  (into the first rank)     ★6            ★5
 *   PEAK       4  (into the Peak)           ★8            ★7
 *   SETTLED    Peak stage 2                 ★9            ★8
 *   SUMMIT     Peak stage 3                 ★11           ★9
 * </pre>
 *
 * Author 24.10 (art ladder): from the first rank on the art is not "any art" but the main art of the rank, Seven Plum
 * Blossoms Sword (the third rank has Six Harmonies, the second is the sect skills gate of {@link Realm#secondArtsMet}).
 * Footwork, Falling Petal and the 24 Plum no longer open FIRST, PEAK or the Peak stages.
 *
 * The player never sees these numbers outside a breakthrough: the tablet, the mentors and the wall hints say it in words
 * ({@code murim.mastery.requirement.*}).
 */
public final class RankRequirements {

    /** A requirement of the ladder; {@code stars12} is its value for an art of twelve stars. */
    public enum Gate {
        THIRD(1), FIRST(6), PEAK(8), SETTLED(9), SUMMIT(11);

        private final int stars12;

        Gate(int stars12) {
            this.stars12 = stars12;
        }

        /** The value in the table for a twelve-star art. */
        public int stars12() {
            return stars12;
        }

        /** The art whose stars the gate counts; empty means any combat art (the third rank only). */
        public Optional<ResourceLocation> art() {
            return this == THIRD ? Optional.empty() : Optional.of(SEVEN_PLUM);
        }

        /** Key of the words that say the requirement ({@code murim.mastery.requirement.third} and so on). */
        public String wordKey() {
            return "murim.mastery.requirement." + name().toLowerCase(Locale.ROOT);
        }
    }

    /** The main art of the first rank and above: Seven Plum Blossoms Sword. */
    public static final ResourceLocation SEVEN_PLUM = ResourceLocation.fromNamespaceAndPath("murim", "seven_plum");

    private RankRequirements() {
    }

    /** The requirement of the breakthrough INTO {@code targetRank}; empty for the separate sect skills gate (rank 2). */
    public static Optional<Gate> gate(int targetRank) {
        return switch (targetRank) {
            case Realm.THIRD -> Optional.of(Gate.THIRD);
            case Realm.SECOND -> Optional.empty();
            case Realm.FIRST -> Optional.of(Gate.FIRST);
            default -> Optional.of(Gate.PEAK);
        };
    }

    /** The requirement of the Peak stage {@code targetStage} (1 settled, 2 summit). */
    public static Gate stageGate(int targetStage) {
        return targetStage <= 1 ? Gate.SETTLED : Gate.SUMMIT;
    }

    /** Stars of an art with {@code artCap} stars that satisfy the gate. */
    public static int stars(Gate gate, int artCap) {
        int cap = artCap <= 0 ? 12 : artCap;
        return Math.max(1, Math.min(cap, (int) Math.round(gate.stars12() * cap / 12.0D)));
    }

    /** Does a form with these stars, of an art with {@code artCap} stars, satisfy the gate? */
    public static boolean met(Gate gate, int stars, int artCap) {
        return stars >= stars(gate, artCap);
    }

    /** Does any learned combat form satisfy the gate? Keys are form ids; the cap comes from the art of the form. */
    public static boolean met(Gate gate, Map<ResourceLocation, Integer> combatStars) {
        for (Map.Entry<ResourceLocation, Integer> e : combatStars.entrySet()) {
            if (gate.art().isPresent()
                    && !io.github.verycooltimo.murim.mastery.Arts.masteryId(e.getKey()).equals(gate.art().get())) {
                continue;
            }
            if (met(gate, e.getValue(), io.github.verycooltimo.murim.mastery.Arts.cap(e.getKey()))) {
                return true;
            }
        }
        return false;
    }
}
