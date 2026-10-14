package io.github.verycooltimo.murim.combat;

/** Screen-wide punctuation belongs to big technique finales, never foundation/LMB strikes.
 * The closing cut of a basic combo is still FOUNDATION, even when it has a contact flash. */
public enum StrikeKind {
    FOUNDATION(false),
    TECHNIQUE_FINALE(true);

    private final boolean screenImpact;

    StrikeKind(boolean screenImpact) {
        this.screenImpact = screenImpact;
    }

    public boolean allowsImpactFrames() { return screenImpact; }
    public boolean allowsHitStop() { return screenImpact; }
}
