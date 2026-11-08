package io.github.verycooltimo.murim.item;

import io.github.verycooltimo.murim.mastery.Arts;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Cover index of a technique manual. A manual teaches an ART, and the forms of one art share it
 * ({@link Arts#masteryId}: seven_plum_* -> seven_plum, tang_* -> tang_daggers, ...), so there is ONE cover
 * texture per art ({@code textures/item/technique_manual_<art>.png}), not one per form.
 * The index feeds the client item property {@code murim:book_cover}; {@code models/item/technique_manual.json}
 * has one override per index. The order must match {@code tools/art/items/cover_specs.py} ART_IDS
 * (a unit test checks the model file). Index 0 means "no dedicated cover": the tier covers of the base model apply.
 */
public final class TechniqueCovers {
    public static final List<String> IDS = List.of(
            "seven_plum",
            "twenty_four_plum",
            "falling_petal_sword",
            "six_harmonies",
            "dark_fragrance",
            "wind_god",
            "tang_daggers",
            "crescent_sweep",
            "shadow_step",
            "burst_breath",
            "bandit_rush",
            "wedge_fan",
            "demon_palm",
            "ceremonial_draw",
            "green_forest_axe",
            "village_brawler_fist",
            "sand_in_eyes",
            "ten_thousand_dragons",
            "beggars_crushing_tap",
            "heavenly_dragon_strike",
            "thousand_cranes_dance",
            "seven_stars",
            "true_six_elements_sword",
            "taiyi_sword",
            "bamboo_leaf_hand");

    /** 1-based cover index of the art the technique belongs to, or 0 when it has no dedicated cover. */
    public static int index(ResourceLocation technique) {
        if (technique == null || !technique.getNamespace().equals("murim")) {
            return 0;
        }
        int i = IDS.indexOf(Arts.masteryId(technique).getPath());
        return i < 0 ? 0 : i + 1;
    }

    private TechniqueCovers() {
    }
}
