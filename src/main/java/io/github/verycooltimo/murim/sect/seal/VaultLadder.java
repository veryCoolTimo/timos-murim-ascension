package io.github.verycooltimo.murim.sect.seal;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.FoundationForms;
import io.github.verycooltimo.murim.technique.Styles;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * The technique ladder engraved on the vault door (docs/design/23-mount-hua-sect.md §0 p.9; canon ch. 25: the door
 * of Thousand-Year Steel carries Six Harmonies, the Bamboo Leaf and the 24 Movements and opens to them). The 24
 * Movements lie inside, so our door asks the ladder up to them: the six forms of Six Harmonies in order, Falling
 * Petal, then every form of Seven Plum Blossoms in the canon order. Pure rules: the server ({@link VaultTrial}) feeds
 * it what the player performed.
 */
public final class VaultLadder {

    /**
     * One rung: a technique, or a Six Harmonies form ({@code form} ≥ 0 — the foundation swing index).
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

    /** The engraved ladder. Tune here: the order is the order of the rungs. */
    public static final List<Step> LADDER = build();

    private static List<Step> build() {
        List<Step> out = new ArrayList<>();
        for (int i = 0; i < FoundationForms.Form.values().length; i++) {
            out.add(new Step(SIX, i));
        }
        out.add(Step.technique(PETAL));
        for (ResourceLocation form : Styles.SEVEN_PLUM.forms()) {
            out.add(Step.technique(form));
        }
        return List.copyOf(out);
    }

    private VaultLadder() {
    }

    /**
     * Where the trial stands after {@code performed}, when it stood at {@code step}: the next rung if it matched;
     * a wrong move breaks the ladder — back to the start, or to rung 1 if the wrong move is itself the first rung.
     *
     * @return the new step; {@code LADDER.size()} — the ladder is complete
     */
    public static int advance(List<Step> ladder, int step, Step performed) {
        if (step >= 0 && step < ladder.size() && ladder.get(step).equals(performed)) {
            return step + 1;
        }
        return ladder.get(0).equals(performed) ? 1 : 0;
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
