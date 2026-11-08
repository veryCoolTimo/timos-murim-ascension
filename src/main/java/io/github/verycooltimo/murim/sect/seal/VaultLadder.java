package io.github.verycooltimo.murim.sect.seal;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.technique.Styles;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * The cold-iron door accepts any one form of the Twenty-Four Plum Blossom Sword (author 26.10).
 * The five remembered forms are alternatives, with no required order.
 */
public final class VaultLadder {

    /**
     * One rung: a technique, or a foundation form ({@code form} >= 0 — the foundation swing index; none of the door's rungs is one now, the
     * type keeps the trial able to ask for a Six Harmonies form again).
     */
    public record Step(ResourceLocation technique, int form) {
        public static Step technique(ResourceLocation id) {
            return new Step(id, -1);
        }

        public boolean foundation() {
            return form >= 0;
        }
    }

    public static final ResourceLocation SIX = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "six_harmonies");
    public static final ResourceLocation PETAL = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "falling_petal_sword");

    /** The five alternative forms accepted by the door. */
    public static final List<Step> LADDER = build();

    private static List<Step> build() {
        List<Step> out = new ArrayList<>();
        for (ResourceLocation form : Styles.TWENTY_FOUR_PLUM.forms()) {
            out.add(Step.technique(form));
        }
        return List.copyOf(out);
    }

    private VaultLadder() {
    }

    /** Any accepted form completes the trial; {@code ladder.size()} denotes completion. */
    public static int advance(List<Step> ladder, int step, Step performed) {
        return ladder.contains(performed) ? ladder.size() : 0;
    }

    /**
     * Does this move count at all: footwork and the plain strikes of a style (its left-click basic) are ignored —
     * one walks and swings between the forms; any other technique is a move, right or wrong.
     */
    public static boolean relevant(List<Step> ladder, Step performed) {
        if (performed.foundation()) {
            return ladder.stream().anyMatch(s -> s.technique().equals(performed.technique()));
        }
        if (Styles.ofBasic(performed.technique()).isPresent()) {
            return false;
        }
        return Styles.of(performed.technique()).map(st -> !Styles.FOOTWORK.contains(st)).orElse(true);
    }
}
