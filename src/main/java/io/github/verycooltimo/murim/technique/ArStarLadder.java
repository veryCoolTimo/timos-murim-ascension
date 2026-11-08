package io.github.verycooltimo.murim.technique;

/** Presentation only. Source: docs/design/research/ar-star-ladder-1107.md. */
public final class ArStarLadder {
    public enum Stage { BODY, CLOTH, GLINT, AIR, TRAIL, SMEAR, QI, DETACH, VOLUME, ORDER, COMPLETE }
    public static Stage stage(int stars) {
        return Stage.values()[Math.max(0, Math.min(12, stars) - 2)];
    }
    public static boolean has(int stars, Stage stage) {
        return stars >= 1 && stage(stars).ordinal() >= stage.ordinal();
    }
    private ArStarLadder() {}
}
