package io.github.verycooltimo.murim.mastery;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Apex Echo («Отклик вершины»): what a high art gives to the other arts of the player.
 * Pure rules, no world, player or packets (docs/design/research/synergy-and-fun-1910.md part A4, with the author's
 * decisions of 19.10).
 *
 * <p>An <b>apex</b> is one of the secret arts, or an art flagged {@code apex} in {@code tools/art/art_lore.json}.
 * It has three milestones at 50 / 75 / 100 % of its stars (★6 / ★9 / ★12 of 12). Canon, Absolute
 * Regression: the hero's growth in the Nine Calamities and Heavenly Time arts changed how he understood even the
 * unrelated Thunder Asura Fist, so the Echo reaches EVERY other art (the author's correction), not only the related ones.
 *
 * <ul>
 *   <li><b>Lightness</b>: a permanent multiplier of practice, {@code L(link) × M(milestone)}; kin 0.20, same school
 *       0.12, every other art 0.06 at the last milestone (so at least ×1.06 / ×1.12 / ×1.20), milestones weigh
 *       0.4 / 0.7 / 1.0. Several apexes: the largest share plus a quarter of the rest, never above {@link #ECHO_CAP}.</li>
 *   <li><b>Rush</b>: once per milestone, a part of the next star (10 / 15 / 25 %) to kin and half of it to every
 *       other learned art, inside the shared {@link MasteryRules#HELP_CAP help cap}; never over the insight wall.</li>
 *   <li><b>Start from height</b>: an art learned later starts with stars when an apex is already standing.</li>
 * </ul>
 */
public final class Resonance {

    /** How an apex relates to another art. */
    public enum Link {
        KIN, SCHOOL, OTHER
    }

    /** What the rules need to know about arts; the real answers are in {@link ArtLore}. */
    public interface Lore {
        /** 0 simple, 1 advanced, 2 apex. */
        int grade(ResourceLocation art);

        /** School id or an empty string. */
        String school(ResourceLocation art);

        /** Two-way kinship. */
        boolean kin(ResourceLocation a, ResourceLocation b);

        /** Stars the art has in total. */
        int cap(ResourceLocation art);
    }

    /** Grade of an apex. */
    public static final int APEX = 2;

    /** Ceiling of the Lightness bonus; with breadth +0.10 the whole practice factor is at most ×1.30. */
    public static final double ECHO_CAP = 0.20D;

    /** Milestones as parts of the art's stars: 50 / 75 / 100 %. */
    private static final double[] MILESTONE_SHARE = {0.5D, 0.75D, 1.0D};
    /** M: how much of the Lightness a milestone gives. */
    private static final double[] MILESTONE_WEIGHT = {0.4D, 0.7D, 1.0D};
    /** Rush shares of the next star, for kin. */
    private static final double[] RUSH_SHARE = {0.10D, 0.15D, 0.25D};
    /** L: Lightness at the last milestone. */
    static final double LIGHT_KIN = 0.20D;
    static final double LIGHT_SCHOOL = 0.12D;
    static final double LIGHT_OTHER = 0.06D;
    /** Everyone who is not kin gets half of the Rush (author 19.10). */
    static final double RUSH_NOT_KIN = 0.5D;
    /** The Rush only reaches arts below this part of the apex's stars (★12 → below ★9). */
    static final double RUSH_REACH = 0.75D;
    /** Start from height: kin ⌊stars / 4⌋ up to ★3, school ⌊stars / 6⌋ up to ★2. */
    static final int START_KIN_DIVISOR = 4;
    static final int START_KIN_MAX = 3;
    static final int START_SCHOOL_DIVISOR = 6;
    static final int START_SCHOOL_MAX = 2;

    /** Number of milestones. */
    public static final int MILESTONES = 3;

    /** Star of the apex that opens milestone {@code milestone} (1..3). */
    public static int threshold(int cap, int milestone) {
        double exact = cap * MILESTONE_SHARE[milestone - 1];
        return Math.max(1, Math.min(cap, (int) Math.ceil(exact - 1.0E-9D)));
    }

    /** How many milestones these stars have passed (0..3). */
    public static int milestone(int stars, int cap) {
        int reached = 0;
        for (int m = 1; m <= MILESTONES; m++) {
            if (stars >= threshold(cap, m)) {
                reached = m;
            }
        }
        return reached;
    }

    /** Save key of one fired milestone. */
    public static String key(ResourceLocation art, int milestone) {
        return art + "#" + milestone;
    }

    /**
     * Milestones that these newly reached stars open and that have not fired yet. A milestone fires once per art for the
     * whole life of the save, however the stars are crossed again.
     */
    public static List<Integer> firing(ResourceLocation art, int cap, List<Integer> starsReached, Set<String> fired) {
        List<Integer> out = new ArrayList<>();
        for (int m = 1; m <= MILESTONES; m++) {
            if (starsReached.contains(threshold(cap, m)) && !fired.contains(key(art, m))) {
                out.add(m);
            }
        }
        return out;
    }

    /** True for an art that is neither a cultivation method nor the one given. */
    private static boolean isOtherArt(ResourceLocation art, ResourceLocation except) {
        return !Arts.isMethod(art) && !art.equals(except);
    }

    /** Relation of an apex to the target art. */
    public static Link link(Lore lore, ResourceLocation apex, ResourceLocation target) {
        if (lore.kin(apex, target)) {
            return Link.KIN;
        }
        String school = lore.school(apex);
        return !school.isEmpty() && school.equals(lore.school(target)) ? Link.SCHOOL : Link.OTHER;
    }

    /** {@code L(link) × M(milestone)}: the Lightness share of one apex; milestone 0 gives nothing. */
    public static double lightness(Link link, int milestone) {
        if (milestone <= 0) {
            return 0.0D;
        }
        double base = switch (link) {
            case KIN -> LIGHT_KIN;
            case SCHOOL -> LIGHT_SCHOOL;
            case OTHER -> LIGHT_OTHER;
        };
        return base * MILESTONE_WEIGHT[Math.min(milestone, MILESTONES) - 1];
    }

    /**
     * Lightness of the target art from every apex the player stands on (arts keyed by the art id).
     * The largest share plus a quarter of the others, never above {@link #ECHO_CAP}.
     */
    public static double echo(Lore lore, ResourceLocation target, Map<ResourceLocation, Integer> starsByArt) {
        if (Arts.isMethod(target)) {
            return 0.0D;
        }
        double best = 0.0D;
        double sum = 0.0D;
        for (Map.Entry<ResourceLocation, Integer> e : starsByArt.entrySet()) {
            ResourceLocation apex = e.getKey();
            if (!isOtherArt(apex, target) || lore.grade(apex) < APEX) {
                continue;
            }
            double share = lightness(link(lore, apex, target), milestone(e.getValue(), lore.cap(apex)));
            sum += share;
            best = Math.max(best, share);
        }
        return Math.min(ECHO_CAP, best + 0.25D * (sum - best));
    }

    /** Part of the next star handed over at a milestone, already inside the shared help cap. */
    public static double rushShare(Link link, int milestone) {
        double share = RUSH_SHARE[Math.min(milestone, MILESTONES) - 1] * (link == Link.KIN ? 1.0D : RUSH_NOT_KIN);
        return Math.min(share, MasteryRules.HELP_CAP);
    }

    /**
     * May the Rush reach a target? Below ⌊0.75 × the apex's stars⌋ (at ★12: below ★9) and below the insight wall,
     * and the target must still have stars to gain.
     */
    public static boolean rushReaches(int targetStars, int targetCap, int apexStars) {
        return targetStars < Math.floor(RUSH_REACH * apexStars)
                && targetStars < MasteryRules.insightStar(targetCap)
                && targetStars < targetCap;
    }

    /** One art receiving the Rush. */
    public record Rush(ResourceLocation art, Link link, double amount) {
    }

    /** Rush of one fired milestone to every learned art it reaches. */
    public static List<Rush> rush(Lore lore, ResourceLocation apex, int milestone, int apexStars,
                                  Map<ResourceLocation, TechniqueProgress> arts) {
        List<Rush> out = new ArrayList<>();
        for (Map.Entry<ResourceLocation, TechniqueProgress> e : arts.entrySet()) {
            ResourceLocation target = e.getKey();
            TechniqueProgress p = e.getValue();
            if (!isOtherArt(target, apex) || p.atCap() || !rushReaches(p.stars(), lore.cap(target), apexStars)) {
                continue;
            }
            Link link = link(lore, apex, target);
            out.add(new Rush(target, link, rushShare(link, milestone) * MasteryRules.need(p.stars(), lore.cap(target))));
        }
        return List.copyOf(out);
    }

    /** Stars a newly learned art gets from the highest apex that already stands (kin first, then school). */
    public static int startHeight(Lore lore, ResourceLocation art, Map<ResourceLocation, Integer> starsByArt) {
        int best = 0;
        for (Map.Entry<ResourceLocation, Integer> e : starsByArt.entrySet()) {
            ResourceLocation apex = e.getKey();
            int stars = e.getValue();
            if (!isOtherArt(apex, art) || lore.grade(apex) < APEX || milestone(stars, lore.cap(apex)) == 0) {
                continue;
            }
            int height = switch (link(lore, apex, art)) {
                case KIN -> Math.min(START_KIN_MAX, stars / START_KIN_DIVISOR);
                case SCHOOL -> Math.min(START_SCHOOL_MAX, stars / START_SCHOOL_DIVISOR);
                case OTHER -> 0;
            };
            best = Math.max(best, height);
        }
        return best;
    }

    /** Learned arts that are kin of the apex: the ones the scene names. */
    public static List<ResourceLocation> kinAmong(Lore lore, ResourceLocation apex, Collection<ResourceLocation> known) {
        List<ResourceLocation> out = new ArrayList<>();
        for (ResourceLocation art : known) {
            if (isOtherArt(art, apex) && lore.kin(apex, art) && !out.contains(art)) {
                out.add(art);
            }
        }
        out.sort(java.util.Comparator.comparing(ResourceLocation::toString));
        return List.copyOf(out);
    }

    private Resonance() {
    }
}
