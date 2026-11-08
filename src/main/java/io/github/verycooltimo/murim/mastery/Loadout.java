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
 * @param bodyProtection отдельное искусство защиты тела; не занимает боевой слот
 * @param foundation основа меча — стиль обычной атаки (Меч Шести Равновесий); пусто — ЛКМ ванильная
 */
public record Loadout(List<Optional<ResourceLocation>> slots, int active, Optional<ResourceLocation> foundation,
                      Optional<ResourceLocation> bodyProtection) {

    /** Save schema marker. Future fields must be optional with defaults (ADR 0084). */
    public static final int VERSION = 1;

    public int version() { return VERSION; }

    public Loadout(List<Optional<ResourceLocation>> slots, int active, Optional<ResourceLocation> foundation) {
        this(slots, active, foundation, Optional.empty());
    }

    /** Слотов не бывает больше этого — кольцо с восемью секторами ещё читается. */
    public static final int MAX_SLOTS = 8;

    public static final Loadout EMPTY = new Loadout(List.of(), 0, Optional.empty());

    public static final Codec<Loadout> CODEC = RecordCodecBuilder.<Loadout>create(i -> i.group(
            ResourceLocation.CODEC.optionalFieldOf("technique").codec().listOf()
                    .optionalFieldOf("slots", List.of()).forGetter(Loadout::slots),
            Codec.INT.optionalFieldOf("active", 0).forGetter(Loadout::active),
            ResourceLocation.CODEC.optionalFieldOf("foundation").forGetter(Loadout::foundation),
            ResourceLocation.CODEC.optionalFieldOf("body_protection").forGetter(Loadout::bodyProtection),
            // Always write the marker; an omitted legacy marker still defaults to the current schema.
            Codec.INT.optionalFieldOf("version").xmap(v -> v.orElse(VERSION), java.util.Optional::of).forGetter(Loadout::version)
    ).apply(i, (slots, active, foundation, bodyProtection, version) -> new Loadout(slots, active, foundation, bodyProtection))).xmap(Loadout::withoutRemovedArts, loadout -> loadout);

    public Loadout {
        slots = slots.stream().map(slot -> slot.map(PlumFormMigration::replace)).toList();
        foundation = foundation.map(PlumFormMigration::replace);
        bodyProtection = bodyProtection.map(PlumFormMigration::replace);
    }

    /**
     * Save migration 19.10: a slot or the foundation that held a removed art (RemovedArts) becomes empty, trailing empty
     * slots are cut, and an active slot left empty moves to the first slot that still holds a technique. The wheel never
     * meets an id it cannot resolve. Idempotent; returns this when the loadout holds no removed art.
     */
    public Loadout withoutRemovedArts() {
        if (slots.stream().noneMatch(slot -> slot.isPresent() && RemovedArts.is(slot.get())) && !(foundation.isPresent()
                && RemovedArts.is(foundation.get())) && !(bodyProtection.isPresent() && RemovedArts.is(bodyProtection.get()))) {
            return this;
        }
        List<Optional<ResourceLocation>> next = new ArrayList<>(slots.stream().map(RemovedArts::scrub).toList());
        boolean activeLost = active >= 0 && active < slots.size() && slots.get(active).isPresent()
                && RemovedArts.is(slots.get(active).get());
        while (!next.isEmpty() && next.get(next.size() - 1).isEmpty()) {
            next.remove(next.size() - 1);
        }
        int nextActive = active;
        if (activeLost) {
            nextActive = 0;
            for (int i = 0; i < next.size(); i++) {
                if (next.get(i).isPresent()) {
                    nextActive = i;
                    break;
                }
            }
        }
        return new Loadout(next, nextActive, RemovedArts.scrub(foundation), RemovedArts.scrub(bodyProtection));
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
        // Одна техника — один слот; стиль (все его формы) — тоже один слот.
        technique.ifPresent(id -> {
            for (int i = 0; i < next.size(); i++) {
                Optional<ResourceLocation> other = next.get(i);
                if (other.isPresent() && (other.get().equals(id)
                        || io.github.verycooltimo.murim.technique.Styles.sameStyle(other.get(), id))) {
                    next.set(i, Optional.empty());
                }
            }
        });
        next.set(slot, technique);
        return new Loadout(next, active, foundation, bodyProtection);
    }

    /**
     * Нормализация старых раскладок (формы стиля в разных слотах — до введения стилей): форма
     * стиля остаётся в одном слоте — активном, если он из этого стиля, иначе в самом левом.
     * Идемпотентна.
     */
    public Loadout normalized() {
        List<Optional<ResourceLocation>> next = new ArrayList<>(slots);
        boolean changed = false;
        for (int i = 0; i < next.size(); i++) {
            Optional<ResourceLocation> a = next.get(i);
            if (a.isEmpty() || io.github.verycooltimo.murim.technique.Styles.of(a.get()).isEmpty()) {
                continue;
            }
            for (int j = i + 1; j < next.size(); j++) {
                Optional<ResourceLocation> b = next.get(j);
                if (b.isPresent() && io.github.verycooltimo.murim.technique.Styles.sameStyle(a.get(), b.get())) {
                    if (j == active) {
                        next.set(i, Optional.empty());
                        a = b;
                        changed = true;
                        break;
                    }
                    next.set(j, Optional.empty());
                    changed = true;
                }
            }
        }
        return changed ? new Loadout(next, active, foundation, bodyProtection) : this;
    }

    public Loadout withBodyProtection(Optional<ResourceLocation> art) {
        return new Loadout(slots, active, foundation, art);
    }

    public Loadout select(int slot) {
        return new Loadout(slots, slot, foundation, bodyProtection);
    }

    public Loadout withFoundation(Optional<ResourceLocation> technique) {
        return new Loadout(slots, active, technique, bodyProtection);
    }
}
