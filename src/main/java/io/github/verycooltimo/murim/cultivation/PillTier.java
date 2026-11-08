package io.github.verycooltimo.murim.cultivation;

/**
 * Ступень пилюли: редкость и размах. Every qi pill now has the game «Провести поток» (25.10: fixed qi, the game decides how much of
 * it settles), so the tier no longer decides whether there is a game. It tells the rarity of the item and who may lift the player
 * after a flawless absorption.
 *
 * <ul>
 *   <li>{@link #COMMON} - обычная: 25-50 ци;</li>
 *   <li>{@link #MIDDLE} - средняя: 200-500 ци;</li>
 *   <li>{@link #HIGH} - высшая: 1500-3500 ци; a flawless absorption lifts the player.</li>
 * </ul>
 */
public enum PillTier {
    COMMON,
    MIDDLE,
    HIGH;

    /** Даёт ли безупречное усвоение подлёт (только высшая). */
    public boolean lifts() {
        return this == HIGH;
    }

    /** Высшая из двух ступеней. */
    public PillTier max(PillTier other) {
        return ordinal() >= other.ordinal() ? this : other;
    }
}
