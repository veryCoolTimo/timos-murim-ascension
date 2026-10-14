package io.github.verycooltimo.murim.mastery;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The runtime form view preserves learned forms; saves contain ONE progress entry per art. */
public record MasteryState(Map<ResourceLocation, TechniqueProgress> techniques, double wisdom,
                          Set<ResourceLocation> comprehended) {
    public static final MasteryState EMPTY = new MasteryState(Map.of(), 0.0D, Set.of());

    private record Saved(int version, Map<ResourceLocation, TechniqueProgress> techniques,
                         Map<ResourceLocation, TechniqueProgress> arts, List<ResourceLocation> known,
                         double wisdom, List<ResourceLocation> comprehended) {
        static final Codec<Saved> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.optionalFieldOf("version", 0).forGetter(Saved::version),
                Codec.unboundedMap(ResourceLocation.CODEC, TechniqueProgress.CODEC)
                        .optionalFieldOf("techniques", Map.of()).forGetter(Saved::techniques),
                Codec.unboundedMap(ResourceLocation.CODEC, TechniqueProgress.CODEC)
                        .optionalFieldOf("arts", Map.of()).forGetter(Saved::arts),
                ResourceLocation.CODEC.listOf().optionalFieldOf("known", List.of()).forGetter(Saved::known),
                Codec.DOUBLE.optionalFieldOf("wisdom", 0.0D).forGetter(Saved::wisdom),
                ResourceLocation.CODEC.listOf().optionalFieldOf("comprehended", List.of()).forGetter(Saved::comprehended)
        ).apply(i, Saved::new));

        MasteryState decode() {
            Map<ResourceLocation, TechniqueProgress> forms = new HashMap<>(techniques);
            for (ResourceLocation form : known) {
                TechniqueProgress p = arts.get(masteryId(form));
                if (p != null) forms.put(form, p);
            }
            return new MasteryState(forms, wisdom, Set.copyOf(comprehended));
        }
    }

    public static final Codec<MasteryState> CODEC = Saved.CODEC.xmap(Saved::decode,
            s -> new Saved(TechniqueProgress.VERSION, Map.of(), s.arts(),
                    s.techniques().keySet().stream().sorted(java.util.Comparator.comparing(ResourceLocation::toString)).toList(),
                    s.wisdom(), List.copyOf(s.comprehended())));

    public MasteryState {
        Map<ResourceLocation, TechniqueProgress> best = new HashMap<>();
        Map<ResourceLocation, Integer> limits = new HashMap<>();
        // Never add a form while migrating. Select the best achievement, without summing aliases.
        techniques.forEach((form, raw) -> {
            ResourceLocation art = masteryId(form);
            TechniqueProgress p = raw.migrate(form);
            int maximum = Arts.MAX_STARS;
            p = new TechniqueProgress(Math.min(p.stars(), maximum), p.progress(), p.unprocessed(), p.day(),
                    Math.min(maximum, Math.max(p.cap(), p.stars())));
            limits.merge(art, Math.max(p.cap(), p.stars()), Math::max);
            best.merge(art, p, MasteryState::better);
        });
        Map<ResourceLocation, TechniqueProgress> normalized = new HashMap<>();
        techniques.keySet().forEach(form -> {
            ResourceLocation art = masteryId(form);
            TechniqueProgress p = best.get(art);
            normalized.put(form, new TechniqueProgress(p.stars(), p.progress(), p.unprocessed(), p.day(), limits.get(art)));
        });
        techniques = Map.copyOf(normalized);
        comprehended = comprehended.stream().map(MasteryState::masteryId).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static TechniqueProgress better(TechniqueProgress a, TechniqueProgress b) {
        if (a.stars() != b.stars()) return a.stars() > b.stars() ? a : b;
        if (a.progress() != b.progress()) return a.progress() > b.progress() ? a : b;
        if (a.day() != b.day()) return a.day() > b.day() ? a : b;
        return a.unprocessed() >= b.unprocessed() ? a : b;
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

    /** Rank gates keep their old relative requirements, independently of art size. */
    public Map<ResourceLocation, Integer> executionTiers() {
        Map<ResourceLocation, Integer> out = new HashMap<>();
        techniques.forEach((id, p) -> {
            if (!Arts.isMethod(id)) out.put(id, Arts.executionTier(id, p.stars()));
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
        return new MasteryState(next, wisdom, comprehended);
    }

    public MasteryState withWisdom(double value) { return new MasteryState(techniques, Math.max(0.0D, value), comprehended); }
    public MasteryState comprehend(ResourceLocation id) {
        Set<ResourceLocation> next = new HashSet<>(comprehended);
        next.add(masteryId(id));
        return new MasteryState(techniques, wisdom, next);
    }
}
