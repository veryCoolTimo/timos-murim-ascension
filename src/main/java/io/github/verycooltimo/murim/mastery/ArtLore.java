package io.github.verycooltimo.murim.mastery;

import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import net.minecraft.resources.ResourceLocation;

/**
 * Real answers for {@link Resonance.Lore}: grade from the loaded techniques (a secret form makes its art an apex, and
 * the {@code apex} data flag of {@code tools/art/art_lore.json} raises more), school and authored kin from the
 * generated {@link Arts}, and the edges of {@code requires} as extra kin. Server-side: the technique table is only
 * complete there.
 */
public final class ArtLore implements Resonance.Lore {
    public static final ArtLore DATA = new ArtLore();

    @Override
    public int grade(ResourceLocation art) {
        if (Arts.isMethod(art)) {
            return 0;
        }
        int grade = Arts.apexFlag(art) ? Resonance.APEX : 0;
        for (TechniqueDefinition definition : TechniqueLoader.all().values()) {
            if (!Arts.masteryId(definition.id()).equals(art)) {
                continue;
            }
            grade = Math.max(grade, switch (definition.tier()) {
                case SECRET -> Resonance.APEX;
                case ADVANCED -> 1;
                case BASIC -> 0;
            });
        }
        return grade;
    }

    @Override
    public String school(ResourceLocation art) {
        return Arts.school(art);
    }

    @Override
    public boolean kin(ResourceLocation a, ResourceLocation b) {
        if (Arts.kin(a, b)) {
            return true;
        }
        ResourceLocation artA = Arts.masteryId(a);
        ResourceLocation artB = Arts.masteryId(b);
        if (artA.equals(artB)) {
            return false;
        }
        // A requirement is kinship in either direction: «Four Horizons needs the Quiet Heart Guard at ★6».
        for (TechniqueDefinition definition : TechniqueLoader.all().values()) {
            ResourceLocation owner = Arts.masteryId(definition.id());
            boolean ownerIsA = owner.equals(artA);
            if (!ownerIsA && !owner.equals(artB)) {
                continue;
            }
            ResourceLocation other = ownerIsA ? artB : artA;
            for (TechniqueRequirement requirement : definition.requires()) {
                if (Arts.masteryId(requirement.technique()).equals(other)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public int cap(ResourceLocation art) {
        return Arts.cap(art);
    }

    private ArtLore() {
    }
}
