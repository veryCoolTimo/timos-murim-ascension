package io.github.verycooltimo.murim.mastery;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Техники, разложенные по слотам, и выбранная (решение автора 01.10: кольцо выбора,
 * экран раскладки, «чем больше прогрессия, тем больше техник одновременно»).
 *
 * @param slots  техники по слотам; пустой слот — {@code Optional.empty()}
 * @param active выбранный слот — его техника применяется клавишей техники
 */
public record Loadout(List<Optional<ResourceLocation>> slots, int active) {

    /** Слотов не бывает больше этого — кольцо с восемью секторами ещё читается. */
    public static final int MAX_SLOTS = 8;

    public static final Loadout EMPTY = new Loadout(List.of(), 0);

    public static final Codec<Loadout> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.optionalFieldOf("technique").codec().listOf()
                    .optionalFieldOf("slots", List.of()).forGetter(Loadout::slots),
            Codec.INT.optionalFieldOf("active", 0).forGetter(Loadout::active)
    ).apply(i, Loadout::new));

    public Loadout {
        slots = List.copyOf(slots);
    }

    /** Сколько слотов открыто при такой мудрости: два с самого начала, дальше растёт. */
    public static int slotsFor(double wisdom) {
        return Math.min(MAX_SLOTS, 2 + (int) Math.floor(Math.max(0.0D, wisdom) / 4.0D));
    }

    public Optional<ResourceLocation> at(int slot) {
        return slot >= 0 && slot < slots.size() ? slots.get(slot) : Optional.empty();
    }

    public Optional<ResourceLocation> activeTechnique() {
        return at(active);
    }

    /** Кладёт технику в слот; та же техника в другом слоте оттуда убирается — одна техника, один слот. */
    public Loadout with(int slot, Optional<ResourceLocation> technique) {
        List<Optional<ResourceLocation>> next = new ArrayList<>(slots);
        while (next.size() <= slot) {
            next.add(Optional.empty());
        }
        technique.ifPresent(id -> {
            for (int i = 0; i < next.size(); i++) {
                if (next.get(i).equals(Optional.of(id))) {
                    next.set(i, Optional.empty());
                }
            }
        });
        next.set(slot, technique);
        return new Loadout(next, active);
    }

    public Loadout select(int slot) {
        return new Loadout(slots, slot);
    }
}
