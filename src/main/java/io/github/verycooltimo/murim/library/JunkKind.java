package io.github.verycooltimo.murim.library;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

import java.util.Locale;

/**
 * Kinds of junk from the ruined library's shelves (docs/design/25-ruined-library.md §2). Each kind has its own
 * text script in {@link JunkText} and a weight on the shelves. They are CLONES on the outside (author 04.10): one
 * mass-produced manual cover in three colours whatever is written inside; only the love letters are a bundle.
 *
 * <p>The weights are the "98" of the 98/2 library (docs/design/18-mvp-v1.md §4.2): grand fakes dominate,
 * then scraps and wet scrolls; the keeper's clue is rare on the shelves and guaranteed in the pavilion chest.
 */
public enum JunkKind implements StringRepresentable {
    /** «Непобедимый Меч Десяти Тысяч Драконов»: grand title, nonsense lessons, forged sect seal. */
    FAKE_GRAND(26),
    /** A breathing method that harms the one who tries it (qi deviation, 走火入魔). */
    HERETICAL(6),
    /** A cook's recipes under a martial title, so looters would take it. */
    COOK(8),
    /** A beggar's notes: inns, scraps, rumours, two forms of the Dog-Beating Staff. */
    BEGGAR(8),
    /** An old swordsman's sayings; the first reading settles a little wisdom (comprehension). */
    MUSINGS(6),
    /** A merchant's ledger: forged manuals for sale, a radish for the seal. */
    LEDGER(8),
    /** The archive keeper's note: where the true scrolls were walled up (hint to the sealed room). */
    CLUE(4),
    /** A disciple's love letters that were never sent. */
    LOVE(8),
    /** Torn pages: plausible technique text that stops mid-sentence. */
    TORN(14),
    /** A water-damaged scroll: only some words survive. */
    WATER(12);

    public static final Codec<JunkKind> CODEC = StringRepresentable.fromEnum(JunkKind::values);

    private final int weight;

    JunkKind(int weight) {
        this.weight = weight;
    }

    /** Bound as a bundle of letters, not as a manual. */
    public boolean letters() {
        return this == LOVE;
    }

    /** Weight on the shelves (out of {@link #totalWeight()}). */
    public int weight() {
        return weight;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    @Override
    public String getSerializedName() {
        return id();
    }

    public static int totalWeight() {
        int sum = 0;
        for (JunkKind k : values()) {
            sum += k.weight;
        }
        return sum;
    }

    /** Kind by a roll in [0, total weight) among {@code kinds}. */
    public static JunkKind pick(JunkKind[] kinds, int roll) {
        for (JunkKind k : kinds) {
            roll -= k.weight;
            if (roll < 0) {
                return k;
            }
        }
        return kinds[kinds.length - 1];
    }
}
