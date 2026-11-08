package io.github.verycooltimo.murim.mastery;

import java.util.ArrayList;

/** Meditation thresholds only: 240 minutes at .001 XP/tick, with geometric growth. */
public final class MethodMastery {
    private MethodMastery() {}
    public static final double GROWTH = 1.3D;
    public static double need(int stars) {
        return 288.0D * (GROWTH - 1.0D) / (Math.pow(GROWTH, 12) - 1.0D)
                * Math.pow(GROWTH, Math.max(0, Math.min(11, stars)));
    }
    public static double need(net.minecraft.resources.ResourceLocation art, int stars) {
        return Arts.isMethod(art) ? need(stars) : MasteryRules.need(stars, Arts.cap(art));
    }
    public static MasteryRules.Helped help(TechniqueProgress current, double amount) {
        double room = current.atCap() ? 0.0D : Math.max(0.0D, MasteryRules.HELP_CAP * need(current.stars()) - current.help());
        double grant = Math.min(Math.max(0.0D, amount), room);
        double fill = Math.max(0.0D, need(current.stars()) - current.progress());
        MasteryRules.Gain gain = study(current, grant);
        TechniqueProgress after = gain.progress();
        double used = after.atCap() ? 0.0D : gain.starsReached().isEmpty() ? current.help() + grant
                : Math.min(Math.max(0.0D, grant - fill), MasteryRules.HELP_CAP * need(after.stars()));
        return new MasteryRules.Helped(new MasteryRules.Gain(after.withHelp(used), gain.starsReached()), grant);
    }
    public static MasteryRules.Gain study(TechniqueProgress current, double amount) {
        int stars = current.stars();
        int limit = Math.min(current.cap(), Arts.MAX_STARS);
        double progress = current.progress() + Math.max(0.0D, amount);
        var reached = new ArrayList<Integer>();
        while (stars < limit && progress >= need(stars)) {
            progress -= need(stars++);
            reached.add(stars);
        }
        return new MasteryRules.Gain(new TechniqueProgress(stars, stars >= limit ? 0.0D : progress,
                current.unprocessed(), current.day(), current.cap(), TechniqueProgress.VERSION,
                reached.isEmpty() ? current.help() : 0.0D), java.util.List.copyOf(reached));
    }
}
