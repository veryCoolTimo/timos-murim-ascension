package io.github.verycooltimo.murim.mastery;

import io.github.verycooltimo.murim.technique.TechniqueLoader;
import net.minecraft.resources.ResourceLocation;

/**
 * Shared assignment/filter rules. Author 21.10: breathing and body protection have their own cells.
 * Source: docs/adr/0020-separate-breathing-and-body-protection-slots.md; TechniqueDefinition.form data.
 */
public final class ArtSlots {
    public static final int FOUNDATION = -1;
    public static final int BREATHING = -2;
    public static final int BODY_PROTECTION = -3;

    public enum Kind { ALL, SWORD, STEPS, FIST_PALM, HIDDEN_WEAPON, BREATHING, BODY_PROTECTION, OTHER }

    public static Kind kind(ResourceLocation id) {
        if (Arts.isMethod(id)) return Kind.BREATHING;
        var definition = TechniqueLoader.get(id);
        return definition == null ? Kind.OTHER : kindOfForm(definition.form());
    }

    /** Uses authored form data, not translated names or id substrings. Sword guards remain sword forms. */
    public static Kind kindOfForm(String form) {
        return switch (form) {
            case "body_protection" -> Kind.BODY_PROTECTION;
            case "step" -> Kind.STEPS;
            case "throw" -> Kind.HIDDEN_WEAPON;
            case "palm", "fist", "punch", "open_palm", "straight_punch", "shoulder_charge" -> Kind.FIST_PALM;
            case "foundation", "slash", "guard", "horizontal_sweep", "vertical_draw" -> Kind.SWORD;
            default -> Kind.OTHER;
        };
    }

    public static boolean accepts(int slot, Kind kind, boolean foundation) {
        if (slot == BREATHING) return kind == Kind.BREATHING;
        if (slot == BODY_PROTECTION) return kind == Kind.BODY_PROTECTION;
        if (slot == FOUNDATION) return foundation && kind != Kind.BREATHING && kind != Kind.BODY_PROTECTION;
        return slot >= 0 && slot < Loadout.MAX_SLOTS && !foundation
                && kind != Kind.BREATHING && kind != Kind.BODY_PROTECTION;
    }

    /** Six Elements Sword, volume 1 (strikes, the sword foundation) and the volume 2 sub-art it opens (Falling Petal). */
    public static final ResourceLocation SIX = ResourceLocation.fromNamespaceAndPath("murim", "six_harmonies");
    public static final ResourceLocation SIX_VOLUME_TWO = ResourceLocation.fromNamespaceAndPath("murim", "falling_petal_sword");

    public static boolean accepts(int slot, ResourceLocation id) {
        // One art in the K screen (author 22.10): dragged on a technique slot, Six Elements Sword is its volume 2 (the server
        // decides whether it is unlocked and puts the sub-art there).
        if (SIX.equals(id) && slot >= 0 && slot < Loadout.MAX_SLOTS) {
            return true;
        }
        var definition = TechniqueLoader.get(id);
        return accepts(slot, kind(id), definition != null && definition.foundation());
    }

    private ArtSlots() {}
}
