package io.github.verycooltimo.murim.mastery;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

/**
 * Требование для изучения: другая техника на нужном слое — «основы» (автор 30.09).
 *
 * @param technique какая техника нужна
 * @param layer     на каком слое она должна быть освоена
 */
public record TechniqueRequirement(ResourceLocation technique, int layer) {

    public static final Codec<TechniqueRequirement> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.fieldOf("technique").forGetter(TechniqueRequirement::technique),
            Codec.INT.optionalFieldOf("layer", 1).forGetter(TechniqueRequirement::layer)
    ).apply(i, TechniqueRequirement::new));
}
