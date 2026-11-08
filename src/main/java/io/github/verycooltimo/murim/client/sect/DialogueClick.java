package io.github.verycooltimo.murim.client.sect;

/** The click guard of the dialogue scroll, free of any client class so that it can be unit-tested. */
final class DialogueClick {
    /** Ticks a line must have been open before the click that skipped its printing may also pick the answer. */
    static final int SKIP_GUARD = 3;

    private DialogueClick() { }

    /**
     * May this click act? A click made right after a line opened (the click that chose the previous answer, a double click) is
     * swallowed for {@code guardUntil} ticks. The click that itself skipped the printing is already a deliberate one: it only waits
     * out the first {@link #SKIP_GUARD} ticks, so that the answer is picked by that same click (author 22.10: one click on an
     * answer did nothing, only the second worked).
     */
    static boolean live(boolean skippedNow, int lineTicks, int guardUntil) {
        return lineTicks >= (skippedNow ? Math.min(guardUntil, SKIP_GUARD) : guardUntil);
    }
}
