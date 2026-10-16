package io.github.verycooltimo.murim.mastery;

import io.github.verycooltimo.murim.registry.ModDataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Read legacy loot and saved books proportionally without rewriting the inventory. */
public final class ManualDepth {
    public static int stars(ItemStack book, ResourceLocation technique) {
        Integer stars = book.get(ModDataComponents.MANUAL_STARS.get());
        if (stars != null) {
            int version = book.getOrDefault(ModDataComponents.MANUAL_STAR_VERSION.get(), 2);
            if (stars <= 0) return 0; // complete book
            int maximum = version < 3 ? Arts.previousStarMaximum(technique, stars, 0)
                    : version == 3 ? Arts.MAX_STARS : Arts.cap(technique);
            return Math.max(1, Math.min(Arts.cap(technique),
                    (int) Math.round(stars / (double) maximum * Arts.cap(technique))));
        }
        Integer old = book.get(ModDataComponents.MANUAL_DEPTH.get());
        if (old == null || old <= 0) return 0; // complete book
        return Math.max(1, Math.min(Arts.cap(technique),
                (int) Math.round(old / (double) Arts.legacySteps(technique) * Arts.cap(technique))));
    }

    private ManualDepth() {}
}
