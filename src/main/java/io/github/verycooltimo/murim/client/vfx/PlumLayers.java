package io.github.verycooltimo.murim.client.vfx;

import io.github.verycooltimo.murim.technique.*;
import java.util.EnumSet;
import java.util.Set;

/** Presentation ladder: accepted four-form policy plus the 10 Nov Crush spec «Слои».
 * Every entry names a material/shape family, not a phase or a server mechanic.
 */
final class PlumLayers {
    enum Form { RAIN, RIVER, SCATTER, SEA, CRUSH }
    enum Kind { DUST, WIND, GHOST, PETALS, AURA, FLOWER, THREADS, COLUMN, ILLUSION,
        STREAM, RESONANCE, RAYS, SPIRAL, EYES, WEDGE, CLONES, LOOPS, FIRE, MASTER_CUT,
        SCATTER, BLADE_STROKE, WRAP, TRAIL, CAPTION, BOW_WAVE, SLOT_BUDS, LIFT,
        HEART, ROOTS, BRANCH, CROWN, VORTEX, CRACK, BURST, SMOKE, FULL }
    /** Exact mastery stars, never execution tiers. Earlier rows remain present. */
    static Set<Kind> kinds(Form form, int stars) {
        var set = EnumSet.noneOf(Kind.class);
        Set<Kind> available = switch (form) {
            case RAIN -> Set.of(Kind.DUST, Kind.WIND, Kind.GHOST, Kind.PETALS, Kind.AURA, Kind.FLOWER, Kind.TRAIL, Kind.THREADS, Kind.COLUMN, Kind.ILLUSION, Kind.CAPTION);
            case RIVER -> Set.of(Kind.DUST, Kind.WIND, Kind.STREAM, Kind.GHOST, Kind.RESONANCE, Kind.RAYS, Kind.PETALS, Kind.SPIRAL, Kind.CAPTION, Kind.EYES, Kind.WEDGE, Kind.TRAIL);
            case SCATTER -> Set.of(Kind.DUST, Kind.WIND, Kind.CLONES, Kind.GHOST, Kind.LOOPS, Kind.PETALS, Kind.FIRE, Kind.MASTER_CUT, Kind.SCATTER, Kind.TRAIL, Kind.CAPTION);
            case SEA -> Set.of(Kind.BLADE_STROKE, Kind.DUST, Kind.GHOST, Kind.PETALS, Kind.FLOWER, Kind.WRAP, Kind.TRAIL, Kind.CAPTION, Kind.AURA, Kind.BOW_WAVE, Kind.SLOT_BUDS, Kind.LIFT, Kind.HEART);
            case CRUSH -> Set.of(Kind.DUST, Kind.WIND, Kind.TRAIL, Kind.GHOST, Kind.AURA, Kind.RESONANCE, Kind.PETALS, Kind.VORTEX, Kind.CRACK, Kind.BURST, Kind.CAPTION, Kind.SMOKE, Kind.FULL);
        };
        for (Kind kind : available) if (ArStarLadder.has(stars, ArStarLadder.stage(firstStar(kind)))) set.add(kind);
        return set;
    }
    static int firstStar(Kind kind) {
        return switch (kind) {
            case DUST, WIND -> 5;
            case TRAIL, BLADE_STROKE -> 6;
            case GHOST -> 7;
            case AURA, RESONANCE, EYES, FIRE -> 8; // FIRE is source qi, not detached flame.
            case PETALS -> 9;
            case FLOWER, ILLUSION, SPIRAL, STREAM, RAYS, BRANCH, ROOTS, WRAP,
                 BOW_WAVE, CLONES, SCATTER, MASTER_CUT, SLOT_BUDS, LIFT, CAPTION, VORTEX, CRACK, BURST -> 10;
                 // CAPTION accompanies the first full technique volume/impact frame.
            case THREADS, COLUMN, LOOPS, CROWN, HEART, SMOKE -> 11;
            case FULL, WEDGE -> 12;
        };
    }
    static boolean has(Form form, int stars, Kind kind) { return kinds(form, stars).contains(kind); }
    /** Crush draw ledger; see CrushVfx's component sums. Pure so policy tests need no client bootstrap. */
    static int crushQuadCeiling(int stars) {
        // v6 (14.11): the mote cap went from 80 to 120 (+80 quads from ★5); from ★10 the torn burst and its needles, drawn
        // twice (crisp, then faint into the glow target): 2 x (8 rays x 24 x 2 + 9 x 2 x 2) = 840.
        return stars < 5 ? 0 : stars == 5 ? 248 : stars == 6 ? 250 : stars == 7 ? 394
                : stars == 8 ? 421 : stars == 9 ? 601 : 1703;
    }
    static float light(int stars) {
        return stars < 8 ? 0 : stars == 8 ? .12F : stars == 9 ? .2F : stars == 10 ? .5F : stars == 11 ? .7F : 1;
    }
    static double skyTint(int stars) { return stars >= 12 ? 1 : 0; }
    static double scale(Form form, int stars) {
        // Physical/source layers keep their geometry; the large silhouette is absent before VOLUME.
        return stars < 1 ? 0 : stars < 10 ? 1 : .5 + .5 * (Math.min(12, stars) - 10) / 2.;
    }
    static double density(int stars, Kind kind) {
        int first = firstStar(kind);
        return stars < first ? 0 : first == 12 ? 1 : .5 + .5 * (Math.min(12, stars) - first) / (12. - first);
    }
    static double density(Form form, int stars) { return density(stars, Kind.PETALS); }
    static boolean petalSlot(Form form, int stars, int slot, int count) {
        return has(form, stars, Kind.PETALS);
    }
    static int petalCount(Form form, int stars, int apex) { return (int) (apex * density(form, stars)); }
    // Flower height/offset do not inherit the illusion's tier scale.
    static double rainFlowerHeight(int tier) { return 15; }
    /** White axial burst only; COMPLETE retains the original geometry and alpha. */
    static double riverBurstScale(int stars) {
        return stars < 10 ? 0 : stars == 10 ? .55 : stars == 11 ? .8 : 1;
    }
    private PlumLayers() {}
}
