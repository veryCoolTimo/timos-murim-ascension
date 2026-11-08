package io.github.verycooltimo.murim.mastery;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The order in which the double of the Echo shows the forms, so that it looks like a kata and not one loop of one move
 * (author 22.10: "animations are monotonous"). A kata opens with the foundation stance, then goes through the recorded
 * moves of the last fight and the other forms the player knows in the art, in a seeded order that never repeats a form
 * twice in a row. {@link #gap} gives the pause after each move: chained moves are close, the end of a phrase breathes.
 */
public final class EchoKata {

    /** Longest kata, in moves. */
    public static final int MAX = 7;

    private EchoKata() {
    }

    /**
     * @param recorded the moves of the last fight, in the order they happened
     * @param known    the other forms of the same art the player has learned
     * @param opening  the foundation form of the art, or {@code null}
     */
    public static List<ResourceLocation> build(List<ResourceLocation> recorded, List<ResourceLocation> known,
                                               ResourceLocation opening, long seed) {
        Set<ResourceLocation> pool = new LinkedHashSet<>();
        pool.addAll(recorded);
        pool.addAll(known);
        pool.remove(opening);
        List<ResourceLocation> forms = new ArrayList<>(pool);
        Random random = new Random(seed);
        java.util.Collections.shuffle(forms, random);
        // The recorded moves keep their fight order among themselves: the double replays the fight, with extra forms woven in.
        List<ResourceLocation> kata = new ArrayList<>();
        if (opening != null) {
            kata.add(opening);
        }
        List<ResourceLocation> fight = new ArrayList<>(new LinkedHashSet<>(recorded));
        fight.remove(opening);
        List<ResourceLocation> extra = new ArrayList<>(forms);
        extra.removeAll(fight);
        int fightIndex = 0;
        int extraIndex = 0;
        while (kata.size() < MAX && (fightIndex < fight.size() || extraIndex < extra.size())) {
            boolean takeFight = fightIndex < fight.size() && (extraIndex >= extra.size() || random.nextInt(3) != 0);
            ResourceLocation next = takeFight ? fight.get(fightIndex++) : extra.get(extraIndex++);
            if (kata.isEmpty() || !kata.get(kata.size() - 1).equals(next)) {
                kata.add(next);
            }
        }
        // A fight repeats a form on purpose: let it come back once, apart from itself, so a one-form art is not a single note.
        if (kata.size() < 4 && !kata.isEmpty()) {
            List<ResourceLocation> base = new ArrayList<>(kata);
            int i = 0;
            while (kata.size() < 4 && base.size() > 1) {
                ResourceLocation again = base.get(i++ % base.size());
                if (!kata.get(kata.size() - 1).equals(again)) {
                    kata.add(again);
                }
            }
        }
        return List.copyOf(kata);
    }

    /** Ticks between the end of move {@code index} and the start of the next: a quick chain, with a breath every third move. */
    public static int gap(int index, long seed) {
        if (index % 3 == 2) {
            return 24;
        }
        return 5 + (int) ((seed + index * 7L) & 7L);
    }
}
