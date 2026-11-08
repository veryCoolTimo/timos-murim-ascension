package io.github.verycooltimo.murim.mastery;

import io.github.verycooltimo.murim.combat.FoundationForms;
import io.github.verycooltimo.murim.technique.Styles;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Rules of the training yard's dummies: a monotony count for the fatigue. No world, no player: the numbers are checked by unit tests,
 * the server glue is {@link YardPractice}. (A weak spot and a clean hit were here until 21.10: the author asked for a plain dummy.)
 *
 * <ul>
 *   <li><b>Monotony.</b> The floor of the daily fatigue is {@link #FLOOR_PLAIN} while the last hits were one or two forms,
 *       and {@link #FLOOR_VARIED} when at least {@link #VARIETY_NEED} different forms are among the last
 *       {@link #VARIETY_WINDOW} hits: varied forms in a row are rewarded, farming one form is not.</li>
 * </ul>
 */
public final class YardRules {

    /** The place of the body a dummy lights. The ordinal is the synchronised value. */
    public enum Spot {
        NONE, HEAD, BODY, LEGS;

        public static Spot of(int ordinal) {
            return ordinal >= 0 && ordinal < values().length ? values()[ordinal] : NONE;
        }
    }

    /** How long a spot glows: one second. */
    public static final int SPOT_TICKS = 20;
    /** Pause between two spots of one dummy: a base and a random extra, ticks. */
    public static final int SPOT_GAP = 50;
    public static final int SPOT_GAP_EXTRA = 40;
    /** A dummy lights a spot only for somebody this near, blocks. */
    public static final double SPOT_RANGE = 8.0D;
    /** The struck spot stays lit (white) this many ticks after a clean hit. */
    public static final int STRUCK_TICKS = 8;

    /** Practice of a clean hit against a plain hit. */
    public static final double CLEAN_FACTOR = 2.0D;

    /** The lowest share of the daily fatigue: farming a form, and varied forms. */
    public static final double FLOOR_PLAIN = 0.10D;
    public static final double FLOOR_VARIED = 0.25D;
    /** How many of the last hits are looked at, and how many different forms among them count as varied. */
    public static final int VARIETY_WINDOW = 4;
    public static final int VARIETY_NEED = 3;
    /** A pause longer than this (ticks) starts a new run of hits: the variety is "in a row". */
    public static final int RUN_BREAK_TICKS = 400;

    private YardRules() {
    }

    /** The spot a foundation form belongs to: from above the head, through the body, from below the legs. */
    public static Spot spotOf(FoundationForms.Form form) {
        return switch (form) {
            case DOWN_SLASH, OVERHEAD -> Spot.HEAD;
            case THRUST, SIDE -> Spot.BODY;
            case RISING, BLOCK -> Spot.LEGS;
        };
    }

    /**
     * The spot a technique belongs to: its place among the forms of its style, round the three spots. A technique outside every
     * style has no spot of its own and is accepted by any.
     */
    public static Spot spotOf(ResourceLocation technique) {
        return Styles.of(technique).map(style -> {
            int at = style.forms().indexOf(technique);
            return at < 0 ? Spot.NONE : Spot.of(1 + at % 3);
        }).orElse(Spot.NONE);
    }

    /** Height of a spot over the dummy's feet, blocks (the model is as tall as a man): the glow is drawn there. */
    public static double height(Spot spot) {
        return switch (spot) {
            case HEAD -> 1.62D;
            case BODY -> 1.1D;
            case LEGS -> 0.45D;
            case NONE -> 0.0D;
        };
    }

    /** Form key of a foundation form (as the hit is reported): {@code f:<index>}. */
    public static String formKey(int foundationForm) {
        return "f:" + foundationForm;
    }

    /** Form key of a technique: {@code t:<id>}. */
    public static String formKey(ResourceLocation technique) {
        return "t:" + technique;
    }

    /** The spot of a form key; {@code NONE} for a key that has none of its own (any spot takes it). */
    public static Spot spotOfKey(String key) {
        if (key == null) {
            return Spot.NONE;
        }
        if (key.startsWith("f:")) {
            try {
                int i = Integer.parseInt(key.substring(2));
                FoundationForms.Form[] forms = FoundationForms.Form.values();
                return i >= 0 && i < forms.length ? spotOf(forms[i]) : Spot.NONE;
            } catch (NumberFormatException e) {
                return Spot.NONE;
            }
        }
        if (key.startsWith("t:")) {
            ResourceLocation id = ResourceLocation.tryParse(key.substring(2));
            return id == null ? Spot.NONE : spotOf(id);
        }
        return Spot.NONE;
    }

    /** Is a hit of this form on a lit spot a clean hit: the form belongs to the spot, or has none of its own. */
    public static boolean clean(Spot lit, String formKey) {
        if (lit == null || lit == Spot.NONE) {
            return false;
        }
        Spot own = spotOfKey(formKey);
        return own == Spot.NONE || own == lit;
    }

    /** The last hits (oldest first, newest last) show enough variety. */
    public static boolean varied(List<String> recent) {
        int from = Math.max(0, recent.size() - VARIETY_WINDOW);
        return recent.subList(from, recent.size()).stream().distinct().count() >= VARIETY_NEED;
    }

    /** The recent forms after one more hit: the window is kept, a long pause starts over. */
    public static List<String> remember(List<String> recent, String form, long gapTicks) {
        java.util.ArrayList<String> next = new java.util.ArrayList<>(gapTicks > RUN_BREAK_TICKS ? List.of() : recent);
        next.add(form);
        while (next.size() > VARIETY_WINDOW) {
            next.remove(0);
        }
        return List.copyOf(next);
    }

    /** The floor of the fatigue for these hits. */
    public static double floor(boolean varied) {
        return varied ? FLOOR_VARIED : FLOOR_PLAIN;
    }

    /**
     * Share of the practice of a dummy hit after {@code hitsToday} earlier hits: the first hits are whole, then it falls
     * towards the floor, which is higher when the forms are varied. Same curve as the plain training fatigue
     * ({@link MasteryPacing#fatigue(MasteryPacing.Kind, int, double)}), only the floor differs.
     */
    public static double fatigue(int hitsToday, boolean varied) {
        return MasteryPacing.fatigue(MasteryPacing.Kind.TRAINING, hitsToday, floor(varied));
    }
}
