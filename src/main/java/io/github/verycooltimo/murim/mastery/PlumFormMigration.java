package io.github.verycooltimo.murim.mastery;

import net.minecraft.resources.ResourceLocation;

/** 03.11: Dome is retired; progress and equipped slots become Full Bloom. No registry alias. */
public final class PlumFormMigration {
    public static final ResourceLocation DOME = ResourceLocation.fromNamespaceAndPath("murim", "twenty_four_plum_dome");
    public static final ResourceLocation BLOOM = ResourceLocation.fromNamespaceAndPath("murim", "twenty_four_plum_full_bloom");
    public static ResourceLocation replace(ResourceLocation id) { return DOME.equals(id) ? BLOOM : id; }
    private PlumFormMigration() {}
}
