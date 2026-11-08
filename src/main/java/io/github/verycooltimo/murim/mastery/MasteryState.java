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
 * The runtime form view preserves learned forms; saves contain ONE progress entry per art.
 *
 * <p>{@code fired} holds the Apex Echo milestones that already paid out ({@link Resonance#key}); an old save has none,
 * so nothing is lost and past milestones simply never fire (author 19.10: format change approved).
 */
public record MasteryState(Map<ResourceLocation, TechniqueProgress> techniques, double wisdom,
                          Set<ResourceLocation> comprehended, Set<String> fired) {
    public static final MasteryState EMPTY = new MasteryState(Map.of(), 0.0D, Set.of(), Set.of());

    public MasteryState(Map<ResourceLocation, TechniqueProgress> techniques, double wisdom,
                        Set<ResourceLocation> comprehended) {
        this(techniques, wisdom, comprehended, Set.of());
    }

    private record Saved(int version, Map<ResourceLocation, TechniqueProgress> techniques,
                         Map<ResourceLocation, TechniqueProgress> arts, List<ResourceLocation> known,
                         double wisdom, List<ResourceLocation> comprehended, List<String> fired) {
        static final Codec<Saved> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.optionalFieldOf("version", 0).forGetter(Saved::version),
                Codec.unboundedMap(ResourceLocation.CODEC, TechniqueProgress.CODEC)
                        .optionalFieldOf("techniques", Map.of()).forGetter(Saved::techniques),
                Codec.unboundedMap(ResourceLocation.CODEC, TechniqueProgress.CODEC)
                        .optionalFieldOf("arts", Map.of()).forGetter(Saved::arts),
                ResourceLocation.CODEC.listOf().optionalFieldOf("known", List.of()).forGetter(Saved::known),
                Codec.DOUBLE.optionalFieldOf("wisdom", 0.0D).forGetter(Saved::wisdom),
                ResourceLocation.CODEC.listOf().optionalFieldOf("comprehended", List.of()).forGetter(Saved::comprehended),
                // Apex Echo milestones already paid out; saves from before the Echo have none.
                Codec.STRING.listOf().optionalFieldOf("fired", List.of()).forGetter(Saved::fired)
        ).apply(i, Saved::new));

        MasteryState decode() {
            // Save migration 19.10: the removed Heavenly Demon arts leave every part of an old save (RemovedArts).
            Map<ResourceLocation, TechniqueProgress> forms = new HashMap<>(RemovedArts.scrub(techniques));
            for (ResourceLocation form : RemovedArts.scrub(known)) {
                TechniqueProgress p = arts.get(masteryId(PlumFormMigration.replace(form)));
                if (p != null) forms.put(form, p);
            }
            return new MasteryState(forms, wisdom, Set.copyOf(RemovedArts.scrub(comprehended)),
                    RemovedArts.scrubFired(Set.copyOf(fired)));
        }
    }

    public static final Codec<MasteryState> CODEC = Saved.CODEC.xmap(Saved::decode,
            s -> new Saved(TechniqueProgress.VERSION, Map.of(), s.arts(),
                    s.techniques().keySet().stream().sorted(java.util.Comparator.comparing(ResourceLocation::toString)).toList(),
                    s.wisdom(), List.copyOf(s.comprehended()), s.fired().stream().sorted().toList()));

    public MasteryState {
        Map<ResourceLocation, TechniqueProgress> migrated = new HashMap<>();
        techniques.forEach((form, progress) -> {
            ResourceLocation replacement = PlumFormMigration.replace(form);
            migrated.merge(replacement, progress.migrate(replacement), MasteryState::better);
        });
        techniques = migrated;
        Map<ResourceLocation, TechniqueProgress> best = new HashMap<>();
        Map<ResourceLocation, Integer> limits = new HashMap<>();
        // Never add a form while migrating. Select the best achievement, without summing aliases.
        techniques.forEach((form, raw) -> {
            ResourceLocation art = masteryId(form);
            TechniqueProgress p = raw.migrate(form);
            int maximum = Arts.cap(form);
            p = new TechniqueProgress(Math.min(p.stars(), maximum), p.progress(), p.unprocessed(), p.day(),
                    Math.min(maximum, Math.max(p.cap(), p.stars())), p.version(), p.help());
            limits.merge(art, Math.max(p.cap(), p.stars()), Math::max);
            best.merge(art, p, MasteryState::better);
        });
        Map<ResourceLocation, TechniqueProgress> normalized = new HashMap<>();
        techniques.keySet().forEach(form -> {
            ResourceLocation art = masteryId(form);
            TechniqueProgress p = best.get(art);
            normalized.put(form, p.withCap(limits.get(art)));
        });
        techniques = Map.copyOf(normalized);
        comprehended = comprehended.stream().map(PlumFormMigration::replace).map(MasteryState::masteryId).collect(java.util.stream.Collectors.toUnmodifiableSet());
        fired = Set.copyOf(fired);
    }

    private static TechniqueProgress better(TechniqueProgress a, TechniqueProgress b) {
        if (a.stars() != b.stars()) return a.stars() > b.stars() ? a : b;
        if (a.progress() != b.progress()) return a.progress() > b.progress() ? a : b;
        if (a.unprocessed() != b.unprocessed()) return a.unprocessed() > b.unprocessed() ? a : b;
        return a.help() >= b.help() ? a : b;
    }

    public static ResourceLocation masteryId(ResourceLocation form) { return Arts.masteryId(form); }
    public boolean knows(ResourceLocation form) { return techniques.containsKey(form); }

    public Map<ResourceLocation, TechniqueProgress> arts() {
        Map<ResourceLocation, TechniqueProgress> out = new HashMap<>();
        techniques.forEach((form, p) -> out.put(masteryId(form), p));
        return Map.copyOf(out);
    }

    public TechniqueProgress progress(ResourceLocation formOrArt) { return arts().get(masteryId(formOrArt)); }

    public Map<ResourceLocation, Integer> stars() {
        Map<ResourceLocation, Integer> out = new HashMap<>();
        techniques.forEach((id, p) -> out.put(id, p.stars()));
        return out;
    }

    /**
     * Stars of every learned COMBAT form, keyed by the form id (cultivation methods are not combat arts). The rank
     * requirements of {@link io.github.verycooltimo.murim.cultivation.RankRequirements} read this map.
     */
    public Map<ResourceLocation, Integer> combatStars() {
        Map<ResourceLocation, Integer> out = new HashMap<>();
        techniques.forEach((id, p) -> {
            if (!Arts.isMethod(id)) out.put(id, p.stars());
        });
        return out;
    }

    /** Learn a form or replace the shared progress of all already-known forms of its art. */
    public MasteryState with(ResourceLocation formOrArt, TechniqueProgress progress) {
        Map<ResourceLocation, TechniqueProgress> next = new HashMap<>(techniques);
        ResourceLocation art = masteryId(formOrArt);
        next.replaceAll((form, p) -> masteryId(form).equals(art) ? progress : p);
        if (!formOrArt.equals(art) || !next.keySet().stream().anyMatch(f -> masteryId(f).equals(art))) {
            next.put(formOrArt, progress);
        }
        return new MasteryState(next, wisdom, comprehended, fired);
    }

    public MasteryState withWisdom(double value) { return new MasteryState(techniques, Math.max(0.0D, value), comprehended, fired); }
    public MasteryState comprehend(ResourceLocation id) {
        Set<ResourceLocation> next = new HashSet<>(comprehended);
        next.add(masteryId(id));
        return new MasteryState(techniques, wisdom, next, fired);
    }

    /** Has this Apex Echo milestone (1..3) of the art already paid out? */
    public boolean hasFired(ResourceLocation art, int milestone) {
        return fired.contains(Resonance.key(masteryId(art), milestone));
    }

    /** Marks Apex Echo milestones of the art as paid out. */
    public MasteryState withFired(ResourceLocation art, java.util.Collection<Integer> milestones) {
        Set<String> next = new HashSet<>(fired);
        for (int milestone : milestones) {
            next.add(Resonance.key(masteryId(art), milestone));
        }
        return new MasteryState(techniques, wisdom, comprehended, next);
    }
}
