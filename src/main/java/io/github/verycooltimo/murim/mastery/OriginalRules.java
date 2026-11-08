package io.github.verycooltimo.murim.mastery;

/** A vault original gives one whole star, independently of practice/insight walls. */
public final class OriginalRules {
    private OriginalRules() {}

    public static TechniqueProgress read(TechniqueProgress current, boolean read) {
        if (read || current.stars() >= Arts.MAX_STARS) return current;
        int stars = current.stars() + 1;
        return new TechniqueProgress(stars, current.progress(), current.unprocessed(), current.day(),
                Math.max(current.cap(), stars), current.version(), 0.0D);
    }
}
