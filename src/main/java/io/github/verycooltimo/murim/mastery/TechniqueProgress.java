package io.github.verycooltimo.murim.mastery;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

/**
 * Mastery of an art. {@code cap} is the manuscript limit, not the art's execution scale.
 *
 * <p>{@code progress} is experience inside the current star, in the units of the table of
 * {@link MasteryRules#need(int, int)}. {@code unprocessed} is the per-art RESERVE (the 20 % of earned practice that is not
 * counted at once): it never burns, only the Echo of that art or a night in a bed turns it into progress. {@code help}
 * is the shared help pool of the current transition: what pages, lessons, readings, guidance and the Apex Rush already
 * gave towards the next star (never more than {@link MasteryRules#HELP_CAP} of it). {@code day} is no longer read:
 * the daily cooling it served is gone; the field stays so that saves keep their shape.
 */
public record TechniqueProgress(int stars, double progress, double unprocessed, long day, int cap, int version,
                                double help) {
    public static final int VERSION = 6;

    /** Existing Java callers create current star data; only decoding can create legacy data. */
    public TechniqueProgress(int stars, double progress, double unprocessed, long day, int cap) {
        this(stars, progress, unprocessed, day, cap, VERSION, 0.0D);
    }

    /** Same, with an explicit save version (tests and decoding of old saves). */
    public TechniqueProgress(int stars, double progress, double unprocessed, long day, int cap, int version) {
        this(stars, progress, unprocessed, day, cap, version, 0.0D);
    }

    private record Saved(int version, int stars, int layer, double progress, double unprocessed, long day, int cap,
                         double help) {
        static final Codec<Saved> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.optionalFieldOf("version", 0).forGetter(Saved::version),
                Codec.INT.optionalFieldOf("stars", -1).forGetter(Saved::stars),
                Codec.INT.optionalFieldOf("layer", 0).forGetter(Saved::layer),
                Codec.DOUBLE.fieldOf("progress").forGetter(Saved::progress),
                Codec.DOUBLE.optionalFieldOf("unprocessed", 0.0D).forGetter(Saved::unprocessed),
                Codec.LONG.optionalFieldOf("day", 0L).forGetter(Saved::day),
                Codec.INT.fieldOf("cap").forGetter(Saved::cap),
                // Version 5: the help already given inside the current transition. Older saves have none.
                Codec.DOUBLE.optionalFieldOf("help", 0.0D).forGetter(Saved::help)
        ).apply(i, Saved::new));
    }

    public static final Codec<TechniqueProgress> CODEC = Saved.CODEC.xmap(
            s -> new TechniqueProgress(s.stars() < 0 ? s.layer() : s.stars(), s.progress(),
                    s.unprocessed(), s.day(), s.cap(), s.stars() < 0 ? 0 : Math.max(1, s.version()), s.help()),
            p -> new Saved(p.version(), p.version() == 0 ? -1 : p.stars(),
                    p.version() == 0 ? p.stars() : 0, p.progress(), p.unprocessed(), p.day(), p.cap(), p.help()));

    public TechniqueProgress {
        if (stars < 0 || cap < 0 || version < 0) throw new IllegalArgumentException("Отрицательное освоение");
        if (!Double.isFinite(progress) || progress < 0 || !Double.isFinite(unprocessed) || unprocessed < 0
                || !Double.isFinite(help) || help < 0) {
            throw new IllegalArgumentException("Освоение должно быть конечным и неотрицательным");
        }
    }

    /**
     * Layers migrate directly to the art cap; version 1/2 stars use the former full star scale.
     * Version 3 used twelve stars for every art, even footwork.
     * The manuscript limit never supplies a known art's full denominator.
     *
     * <p>Version 5 replaced the exponential cost {@code 17 × 1.6^star} by the table of
     * {@link MasteryRules#need(int, int)}: the star count of every art is kept and the progress inside the star (and
     * the reserve) keeps its FRACTION of the star, recomputed in the new units. Older versions go through the same
     * single step, so a save is converted exactly once and then written as the current version. Version 6 changes only method thresholds;
     * technique units stay identical. Progress, reserve and help all keep their fractions.
     */
    public TechniqueProgress migrate(ResourceLocation form) {
        if (version >= VERSION) return this;
        int maximum = Arts.cap(form);
        int converted = stars;
        int limit = Math.min(maximum, Math.max(cap, stars));
        if (version < 4) {
            int oldMaximum = version == 0 ? Arts.legacySaveMaximum(form, cap, stars)
                    : version == 3 ? Arts.MAX_STARS : Arts.previousStarMaximum(form, cap, stars);
            if (oldMaximum != maximum) {
                converted = Math.min(maximum, (int) Math.round(stars / (double) oldMaximum * maximum));
                limit = Math.min(maximum, Math.max(converted, (int) Math.round(cap / (double) oldMaximum * maximum)));
            }
        }
        // The saved experience was counted in units of the exponential curve of the star it was gained on.
        double oldNeed = version >= 5 ? MasteryRules.need(stars, maximum) : MasteryRules.legacyNeed(stars);
        double scale = MethodMastery.need(form, converted) / oldNeed;
        return new TechniqueProgress(converted, progress * scale, unprocessed * scale, day, limit, VERSION, help * scale);
    }

    public static TechniqueProgress learned(int stars, int cap) {
        return new TechniqueProgress(Math.min(stars, cap), 0.0D, 0.0D, 0L, cap);
    }

    public boolean atCap() { return stars >= cap; }

    /** The same mastery with another manuscript cap; reserve, help and progress stay. */
    public TechniqueProgress withCap(int newCap) {
        return new TechniqueProgress(stars, progress, unprocessed, day, newCap, version, help);
    }

    /** The same mastery with another amount of help already given in the current transition. */
    public TechniqueProgress withHelp(double given) {
        return new TechniqueProgress(stars, progress, unprocessed, day, cap, version, Math.max(0.0D, given));
    }

    /** The same mastery with another reserve. */
    public TechniqueProgress withUnprocessed(double reserve) {
        return new TechniqueProgress(stars, progress, Math.max(0.0D, reserve), day, cap, version, help);
    }
}
