package io.github.verycooltimo.murim.library;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The junk-book factory's text generator (docs/design/25-ruined-library.md §2): (kind, seed) → title and
 * paragraphs as translatable components built from word lists and templates in the lang files. Pure: no world,
 * no client — the same book reads the same on the server (item name) and the client (book screen), in either
 * language, and the unit test checks determinism and that every key exists in ru_ru and en_us.
 *
 * <p>Russian agreement is solved by data, not by inflection: nouns sit in gender pools and adjectives have
 * three forms ({@code t.adj.N.m|f|n}); every list is written in the case its slot needs.
 */
public final class JunkText {

    /** How a paragraph is drawn in the book. */
    public enum Style {
        /** Title of the book or letter. */
        HEADING,
        /** Plain ink. */
        BODY,
        /** Margin notes, signatures, the copyist's remarks: faded ink. */
        NOTE,
        /** The forged seal line: red. */
        SEAL,
        /** Water damage: most words washed out ({@link #smudge}). */
        SMUDGED
    }

    public record Para(Component text, Style style) {
    }

    /** Everything a book shows: the name on the item and the paragraphs inside. */
    public record Text(Component title, List<Para> paras) {
    }

    private static final String P = JunkLexicon.PREFIX;

    /** The keeper's note names shelves 12..20 by ordinal word ({@code nth.0} = twelfth). */
    public static final int FIRST_ORDINAL = 12;

    public static Component title(JunkBook book) {
        return generate(book).title();
    }

    public static List<Para> paras(JunkBook book) {
        return generate(book).paras();
    }

    public static Text generate(JunkBook book) {
        // java.util.Random: its sequence is fixed by the Java spec, so a seed reads the same everywhere.
        Gen g = new Gen(new Random(book.seed() * 31L + book.kind().ordinal()));
        return switch (book.kind()) {
            case FAKE_GRAND -> fake(g);
            case HERETICAL -> heretical(g);
            case COOK -> cook(g);
            case BEGGAR -> beggar(g);
            case MUSINGS -> musings(g);
            case LEDGER -> ledger(g);
            case CLUE -> clue(g, book.hint());
            case LOVE -> love(g);
            case TORN -> torn(g);
            case WATER -> water(g);
        };
    }

    private static Text fake(Gen g) {
        Component title = g.grandTitle();
        Component author = g.author();
        List<Para> out = new ArrayList<>();
        out.add(new Para(title, Style.HEADING));
        out.add(new Para(Component.translatable(P + "author", author), Style.NOTE));
        out.add(new Para(Component.translatable(g.key("fake.intro"), title, author), Style.BODY));
        lessons(g, out, "fake.step");
        out.add(new Para(Component.translatable(g.key("fake.end")), Style.BODY));
        out.add(new Para(Component.translatable(g.key("seal"), g.sectGen()), Style.SEAL));
        return new Text(title, out);
    }

    private static Text heretical(Gen g) {
        Component title = Component.translatable(g.key("heretic.title"));
        List<Para> out = new ArrayList<>();
        out.add(new Para(title, Style.HEADING));
        out.add(new Para(Component.translatable(g.key("heretic.intro")), Style.BODY));
        lessons(g, out, "heretic.step");
        out.add(new Para(Component.translatable(g.key("heretic.margin")), Style.NOTE));
        return new Text(title, out);
    }

    private static Text cook(Gen g) {
        Component title = Component.translatable(g.key("cook.title"));
        List<Para> out = new ArrayList<>();
        out.add(new Para(title, Style.HEADING));
        out.add(new Para(Component.translatable(g.key("cook.intro")), Style.BODY));
        int[] ingr = g.distinct("ingr", 3);
        out.add(new Para(Component.translatable(P + "cook.take", tr("ingr", ingr[0]), tr("ingr", ingr[1]), tr("ingr", ingr[2])), Style.BODY));
        for (int i : g.distinct("cook.step", 2)) {
            out.add(new Para(tr("cook.step", i), Style.BODY));
        }
        out.add(new Para(Component.translatable(g.key("cook.end")), Style.NOTE));
        return new Text(title, out);
    }

    private static Text beggar(Gen g) {
        Component title = Component.translatable(g.key("beggar.head"));
        List<Para> out = new ArrayList<>();
        out.add(new Para(title, Style.HEADING));
        for (int i : g.distinct("beggar.line", 3)) {
            out.add(new Para(Component.translatable(P + "beggar.line." + i, g.place()), Style.BODY));
        }
        out.add(new Para(Component.translatable(g.key("beggar.dog")), Style.BODY));
        return new Text(title, out);
    }

    private static Text musings(Gen g) {
        Component title = Component.translatable(g.key("muse.head"));
        List<Para> out = new ArrayList<>();
        out.add(new Para(title, Style.HEADING));
        for (int i : g.distinct("muse.line", 3)) {
            out.add(new Para(tr("muse.line", i), Style.BODY));
        }
        return new Text(title, out);
    }

    private static Text ledger(Gen g) {
        Component title = Component.translatable(g.key("ledger.head"), g.place());
        Component grand = g.grandTitle();
        List<Para> out = new ArrayList<>();
        out.add(new Para(title, Style.HEADING));
        for (int i : g.distinct("ledger.entry", 4)) {
            Component amount = Component.literal(Integer.toString(3 + g.r.nextInt(48)));
            out.add(new Para(Component.translatable(P + "ledger.entry." + i, amount, g.sectGen(), grand), Style.BODY));
        }
        out.add(new Para(Component.translatable(g.key("ledger.end")), Style.NOTE));
        return new Text(title, out);
    }

    private static Text clue(Gen g, int hint) {
        Component title = Component.translatable(g.key("clue.head"));
        List<Para> out = new ArrayList<>();
        out.add(new Para(title, Style.HEADING));
        int nth = hint - FIRST_ORDINAL;
        if (nth >= 0 && nth < JunkLexicon.size("nth")) {
            out.add(new Para(Component.translatable(g.key("clue.body"), tr("nth", nth)), Style.BODY));
        } else {
            out.add(new Para(Component.translatable(g.key("clue.vague")), Style.BODY));
        }
        out.add(new Para(Component.translatable(g.key("clue.sign")), Style.NOTE));
        return new Text(title, out);
    }

    private static Text love(Gen g) {
        Component title = Component.translatable(P + "name.love");
        List<Para> out = new ArrayList<>();
        out.add(new Para(Component.translatable(g.key("love.head")), Style.HEADING));
        int[] lines = g.distinct("love.line", 4);
        int[] opens = g.distinct("love.open", 2);
        for (int letter = 0; letter < 2; letter++) {
            out.add(new Para(tr("love.open", opens[letter]), Style.BODY));
            out.add(new Para(tr("love.line", lines[letter * 2]), Style.BODY));
            out.add(new Para(tr("love.line", lines[letter * 2 + 1]), Style.BODY));
            out.add(new Para(Component.translatable(g.key("love.close")), Style.BODY));
            out.add(new Para(Component.translatable(g.key("love.sign")), Style.NOTE));
        }
        return new Text(title, out);
    }

    private static Text torn(Gen g) {
        Component grand = g.grandTitle();
        List<Para> out = new ArrayList<>();
        out.add(new Para(grand, Style.HEADING));
        int[] texts = g.distinct("torn.text", 2);
        int[] notes = g.distinct("torn.note", 2);
        for (int i = 0; i < 2; i++) {
            out.add(new Para(tr("torn.text", texts[i]), Style.BODY));
            out.add(new Para(tr("torn.note", notes[i]), Style.NOTE));
        }
        return new Text(Component.translatable(P + "name.torn", grand), out);
    }

    private static Text water(Gen g) {
        Component grand = g.grandTitle();
        List<Para> out = new ArrayList<>();
        out.add(new Para(Component.translatable(P + "water.note"), Style.NOTE));
        out.add(new Para(grand, Style.SMUDGED));
        out.add(new Para(Component.translatable(g.key("fake.intro"), grand, g.author()), Style.SMUDGED));
        out.add(new Para(Component.translatable(g.key("torn.text")), Style.SMUDGED));
        out.add(new Para(Component.translatable(g.key("fake.step")), Style.SMUDGED));
        return new Text(Component.translatable(P + "name.water"), out);
    }

    /** Three numbered lessons from {@code list}, all different. */
    private static void lessons(Gen g, List<Para> out, String list) {
        int[] steps = g.distinct(list, 3);
        for (int i = 0; i < 3; i++) {
            out.add(new Para(Component.translatable(P + "fake.stepline", tr("ord", i), tr(list, steps[i])), Style.BODY));
        }
    }

    private static Component tr(String list, int index) {
        return Component.translatable(P + list + "." + index);
    }

    /**
     * Water damage on the resolved text: about two words in three are washed into tildes and blanks, the rest
     * stay readable. Deterministic per {@code seed}, so the same page smudges the same way on every open.
     */
    public static String smudge(String text, long seed) {
        Random r = new Random(seed);
        StringBuilder out = new StringBuilder(text.length());
        boolean keep = r.nextInt(3) == 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                keep = r.nextInt(3) == 0;
                out.append(c);
            } else if (keep) {
                out.append(c);
            } else {
                out.append(r.nextInt(4) == 0 ? ' ' : '~');
            }
        }
        return out.toString();
    }

    /** The seeded picker over the lexicon. */
    private static final class Gen {
        private final Random r;

        Gen(Random r) {
            this.r = r;
        }

        int index(String list) {
            return r.nextInt(JunkLexicon.size(list));
        }

        String key(String list) {
            return P + list + "." + index(list);
        }

        /** {@code n} different indices from {@code list} (fewer if the list is shorter). */
        int[] distinct(String list, int n) {
            int size = JunkLexicon.size(list);
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                all.add(i);
            }
            int k = Math.min(n, size);
            int[] out = new int[n];
            for (int i = 0; i < n; i++) {
                out[i] = i < k ? all.remove(r.nextInt(all.size())) : out[i % Math.max(1, k)];
            }
            return out;
        }

        /** «Непобедимый Меч Десяти Тысяч Драконов»: the adjective agrees with the noun's gender pool. */
        Component grandTitle() {
            int m = JunkLexicon.size("t.noun.m");
            int f = JunkLexicon.size("t.noun.f");
            int n = JunkLexicon.size("t.noun.n");
            int pick = r.nextInt(m + f + n);
            String gender = pick < m ? "m" : pick < m + f ? "f" : "n";
            int noun = pick < m ? pick : pick < m + f ? pick - m : pick - m - f;
            Component adj = Component.translatable(P + "t.adj." + index("t.adj") + "." + gender);
            Component nounC = Component.translatable(P + "t.noun." + gender + "." + noun);
            Component num = Component.translatable(key("t.num"));
            Component thing = Component.translatable(key("t.thing"));
            return Component.translatable(key("t.form"), adj, nounC, num, thing);
        }

        Component author() {
            return Component.translatable(key("a.form"), Component.translatable(key("a.title")), Component.translatable(key("a.name")));
        }

        Component sectGen() {
            return Component.translatable(P + "sect." + index("sect") + ".gen");
        }

        Component place() {
            return Component.translatable(key("place"));
        }
    }

    private JunkText() {
    }
}
