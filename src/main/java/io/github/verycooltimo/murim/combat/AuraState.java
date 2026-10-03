package io.github.verycooltimo.murim.combat;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Аура существа: его ранг для давления и «стиль» ци (docs/design/19 §3ж, 01 «Давление ци»).
 *
 * <p>Ранг шкалы противника шире игрока: 0–4 совпадают с {@link io.github.verycooltimo.murim.cultivation.Realm}
 * (нет, третий, второй, первый, Пик), 5–6 — ступени выше Пика (Безграничный, Мистический) для
 * мастеров и боссов. Игрок их пока не достигает, но давление от них должно отличаться.
 *
 * @param rank     ранг ауры, 0 — ауры нет
 * @param demonic  демоническая ци: красное пламя вместо бело-серого
 */
public record AuraState(int rank, boolean demonic) {

    public static final int MAX_RANK = 6;

    public static final AuraState NONE = new AuraState(0, false);

    public static final Codec<AuraState> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.intRange(0, MAX_RANK).optionalFieldOf("rank", 0).forGetter(AuraState::rank),
            Codec.BOOL.optionalFieldOf("demonic", false).forGetter(AuraState::demonic)
    ).apply(i, AuraState::new));

    public AuraState {
        if (rank < 0 || rank > MAX_RANK) {
            throw new IllegalArgumentException("Ранг ауры вне 0.." + MAX_RANK + ": " + rank);
        }
    }

    public boolean present() {
        return rank > 0;
    }
}
