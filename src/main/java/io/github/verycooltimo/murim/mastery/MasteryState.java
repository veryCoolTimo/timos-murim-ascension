package io.github.verycooltimo.murim.mastery;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Что игрок знает и насколько освоил (docs/design/19 §3г). Сохраняется и переживает смерть:
 * выученное — результат игры, а не расходник.
 *
 * @param techniques   выученные техники и их освоение
 * @param wisdom       мудрость; игрок её числом не видит (автор 30.09: «косвенно»)
 * @param comprehended крутые техники, уже давшие мудрость постижением — второй раз не даёт
 */
public record MasteryState(Map<ResourceLocation, TechniqueProgress> techniques, double wisdom,
                           Set<ResourceLocation> comprehended) {

    public static final MasteryState EMPTY = new MasteryState(Map.of(), 0.0D, Set.of());

    public static final Codec<MasteryState> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(ResourceLocation.CODEC, TechniqueProgress.CODEC)
                    .optionalFieldOf("techniques", Map.of()).forGetter(MasteryState::techniques),
            Codec.DOUBLE.optionalFieldOf("wisdom", 0.0D).forGetter(MasteryState::wisdom),
            ResourceLocation.CODEC.listOf().xmap(Set::copyOf, List::copyOf)
                    .optionalFieldOf("comprehended", Set.of()).forGetter(MasteryState::comprehended)
    ).apply(i, MasteryState::new));

    public MasteryState {
        techniques = Map.copyOf(techniques);
        comprehended = Set.copyOf(comprehended);
    }

    public boolean knows(ResourceLocation technique) {
        return techniques.containsKey(technique);
    }

    /** Слои выученных техник — для проверки требований. */
    public Map<ResourceLocation, Integer> layers() {
        Map<ResourceLocation, Integer> layers = new HashMap<>();
        techniques.forEach((id, progress) -> layers.put(id, progress.layer()));
        return layers;
    }

    public MasteryState with(ResourceLocation technique, TechniqueProgress progress) {
        Map<ResourceLocation, TechniqueProgress> next = new HashMap<>(techniques);
        next.put(technique, progress);
        return new MasteryState(next, wisdom, comprehended);
    }

    public MasteryState withWisdom(double value) {
        return new MasteryState(techniques, Math.max(0.0D, value), comprehended);
    }

    public MasteryState comprehend(ResourceLocation technique) {
        Set<ResourceLocation> next = new HashSet<>(comprehended);
        next.add(technique);
        return new MasteryState(techniques, wisdom, next);
    }
}
