package io.github.verycooltimo.murim.mastery;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

/** Mastery of an art. cap is the manuscript limit, not the art's execution scale. */
public record TechniqueProgress(int stars, double progress, double unprocessed, long day, int cap, int version) {
    public static final int VERSION = 3;

    /** Existing Java callers create current star data; only decoding can create legacy data. */
    public TechniqueProgress(int stars, double progress, double unprocessed, long day, int cap) {
        this(stars, progress, unprocessed, day, cap, VERSION);
    }

    private record Saved(int version, int stars, int layer, double progress, double unprocessed, long day, int cap) {
        static final Codec<Saved> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.optionalFieldOf("version", 0).forGetter(Saved::version),
                Codec.INT.optionalFieldOf("stars", -1).forGetter(Saved::stars),
                Codec.INT.optionalFieldOf("layer", 0).forGetter(Saved::layer),
                Codec.DOUBLE.fieldOf("progress").forGetter(Saved::progress),
                Codec.DOUBLE.optionalFieldOf("unprocessed", 0.0D).forGetter(Saved::unprocessed),
                Codec.LONG.optionalFieldOf("day", 0L).forGetter(Saved::day),
                Codec.INT.fieldOf("cap").forGetter(Saved::cap)
        ).apply(i, Saved::new));
    }

    public static final Codec<TechniqueProgress> CODEC = Saved.CODEC.xmap(
            s -> new TechniqueProgress(s.stars() < 0 ? s.layer() : s.stars(), s.progress(),
                    s.unprocessed(), s.day(), s.cap(), s.stars() < 0 ? 0 : Math.max(1, s.version())),
            p -> new Saved(p.version(), p.version() == 0 ? -1 : p.stars(),
                    p.version() == 0 ? p.stars() : 0, p.progress(), p.unprocessed(), p.day(), p.cap()));

    public TechniqueProgress {
        if (stars < 0 || cap < 0 || version < 0) throw new IllegalArgumentException("Отрицательное освоение");
        if (!Double.isFinite(progress) || progress < 0 || !Double.isFinite(unprocessed) || unprocessed < 0) {
            throw new IllegalArgumentException("Освоение должно быть конечным и неотрицательным");
        }
    }

    /** Layers migrate directly to /12; version 1/2 stars use the former full star scale.
     * The manuscript limit never supplies a known art's full denominator.
     * Preserve both the next-star fraction and pending experience, then save as version 3.
     */
    public TechniqueProgress migrate(ResourceLocation form) {
        if (version >= VERSION) return this;
        int oldMaximum = version == 0 ? Arts.legacySaveMaximum(form, cap, stars)
                : Arts.previousStarMaximum(form, cap, stars);
        int maximum = Arts.cap(form);
        int converted = Math.min(maximum, (int) Math.round(stars / (double) oldMaximum * maximum));
        int limit = Math.min(maximum, Math.max(converted, (int) Math.round(cap / (double) oldMaximum * maximum)));
        double scale = MasteryRules.need(converted) / MasteryRules.need(stars);
        return new TechniqueProgress(converted, progress * scale, unprocessed * scale, day, limit);
    }

    public static TechniqueProgress learned(int stars, int cap) {
        return new TechniqueProgress(Math.min(stars, cap), 0.0D, 0.0D, 0L, cap);
    }

    public boolean atCap() { return stars >= cap; }
}
