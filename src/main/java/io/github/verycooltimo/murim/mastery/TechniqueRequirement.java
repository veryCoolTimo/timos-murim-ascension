package io.github.verycooltimo.murim.mastery;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

/**
 * Требование для изучения: другая техника на нужной звезде — «основы» (автор 30.09).
 *
 * @param technique какая техника нужна
 * @param star     на какой звезде она должна быть освоена
 */
public record TechniqueRequirement(ResourceLocation technique, int star) {

    public static final Codec<TechniqueRequirement> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.fieldOf("technique").forGetter(TechniqueRequirement::technique),
            Codec.INT.optionalFieldOf("star", 1).forGetter(TechniqueRequirement::star)
    ).apply(i, TechniqueRequirement::new));
}
