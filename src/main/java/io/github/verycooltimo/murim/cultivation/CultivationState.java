package io.github.verycooltimo.murim.cultivation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * Путь игрока до даньтяня: какой метод он практикует и сколько тактов создания прошёл.
 *
 * <p>Сохраняется и переживает смерть: выученный метод и пройденная практика — результат
 * игры, а не временное состояние. Сам даньтянь после семени живёт в профиле
 * ({@link io.github.verycooltimo.murim.profile.DantianProfile}).
 *
 * @param method метод, который практикует игрок; пусто — метод ещё не найден
 * @param beats  пройденные такты создания даньтяня: 0 — ничего, 1 — первое ощущение,
 *               2 — кольцо удержано, 3 — семя родилось
 */
public record CultivationState(Optional<ResourceLocation> method, int beats) {

    /** Такт, после которого родилось семя. */
    public static final int SEEDED = 3;

    public static final CultivationState NONE = new CultivationState(Optional.empty(), 0);

    public static final Codec<CultivationState> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.optionalFieldOf("method").forGetter(CultivationState::method),
            Codec.INT.optionalFieldOf("beats", 0).forGetter(CultivationState::beats)
    ).apply(i, CultivationState::new));

    public CultivationState {
        java.util.Objects.requireNonNull(method, "method");
        if (beats < 0 || beats > SEEDED) {
            throw new IllegalArgumentException("Такт вне 0.." + SEEDED + ": " + beats);
        }
    }

    public boolean seeded() {
        return beats >= SEEDED;
    }

    public CultivationState withMethod(ResourceLocation id) {
        return new CultivationState(Optional.of(id), beats);
    }

    public CultivationState withBeats(int value) {
        return new CultivationState(method, value);
    }
}
