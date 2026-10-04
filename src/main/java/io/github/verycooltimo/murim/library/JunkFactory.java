package io.github.verycooltimo.murim.library;

import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;

/**
 * The junk-book factory (docs/design/25-ruined-library.md §2): a weighted roll of a kind and a seed, and the item
 * stack that carries it. Shelves of the ruined library and the {@code murim:junk_book} loot function both come here.
 *
 * <p>Clones (author 04.10): every junk manual has the same cover, only its colour varies (beige most, blue and red
 * like the stacked manuals of the Heavenly Demon Archives) — and the colour follows the seed, not the kind, so the
 * cover tells nothing about what is written inside.
 */
public final class JunkFactory {

    /** Cover colours of the clone manual: beige 60 %, blue 25 %, red 15 %. */
    public enum Cover {
        BEIGE, BLUE, RED;

        public static Cover of(long seed) {
            int roll = (int) Math.floorMod(seed >>> 7, 20L);
            return roll < 12 ? BEIGE : roll < 17 ? BLUE : RED;
        }
    }

    /** A random junk book by the shelf weights of {@link JunkKind}. */
    public static JunkBook roll(RandomSource random) {
        JunkKind kind = JunkKind.pick(JunkKind.values(), random.nextInt(JunkKind.totalWeight()));
        return new JunkBook(kind, random.nextLong());
    }

    /** A random junk book that fits the entry's item: letters for the bundle, any other kind for a manual. */
    public static JunkBook rollFor(boolean letters, RandomSource random) {
        JunkKind[] kinds = Arrays.stream(JunkKind.values()).filter(k -> k.letters() == letters).toArray(JunkKind[]::new);
        int total = Arrays.stream(kinds).mapToInt(JunkKind::weight).sum();
        return new JunkBook(JunkKind.pick(kinds, random.nextInt(total)), random.nextLong());
    }

    /** The item for {@code book}: the letter bundle or the clone manual in the seed's colour. */
    public static Item item(JunkBook book) {
        if (book.kind().letters()) {
            return ModLibrary.JUNK_LETTERS.get();
        }
        return switch (Cover.of(book.seed())) {
            case BLUE -> ModLibrary.JUNK_MANUAL_BLUE.get();
            case RED -> ModLibrary.JUNK_MANUAL_RED.get();
            default -> ModLibrary.JUNK_MANUAL.get();
        };
    }

    /** The item stack for {@code book} with its text component. */
    public static ItemStack stack(JunkBook book) {
        ItemStack stack = new ItemStack(item(book));
        stack.set(ModLibrary.JUNK.get(), book);
        return stack;
    }

    private JunkFactory() {
    }
}
