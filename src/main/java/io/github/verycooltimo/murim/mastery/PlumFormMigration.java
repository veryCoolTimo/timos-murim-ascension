package io.github.verycooltimo.murim.mastery;

import net.minecraft.resources.ResourceLocation;

/**
 * 03.11 Dome was retired into Full Bloom; 10.11 Full Bloom was retired too (author: «она не нужна»). Both ids now leave
 * every part of a save through {@link RemovedArts}: progress, known list, comprehended, slots, running cast. Nothing is
 * refunded and no registry alias exists.
 */
public final class PlumFormMigration {
    public static final ResourceLocation DOME = ResourceLocation.fromNamespaceAndPath("murim", "twenty_four_plum_dome");
    public static final ResourceLocation BLOOM = ResourceLocation.fromNamespaceAndPath("murim", "twenty_four_plum_full_bloom");
    public static boolean retired(ResourceLocation id) { return DOME.equals(id) || BLOOM.equals(id); }
    private PlumFormMigration() {}
}
