package io.github.verycooltimo.murim.sect;

import java.util.List;

/**
 * What the cook of Mount Hua serves (author 18.10: «свою еду в Хуа сделать»). Plain Taoist sect food of a poor mountain
 * sect, after Return of the Mount Hua Sect only: grain, steamed buns, pickles, mountain greens, a cup of plum-blossom
 * tea; meat is not on the menu at all (Chung Myung's love of meat and wine is a joke of the story, not a menu). Pure data,
 * no Minecraft types: the menu of a meal is a function of its id ({@link SectMeals#mealId}), so it is unit tested.
 *
 * <p>Two hot meals a sect day (breakfast and dinner): breakfast is the porridge or a bun; dinner is rice with pickles
 * and soup, soup and a bun, or rice with tea. There is no feast: the review day eats like any other (author 19.10:
 * «праздник не надо»). Food values are small and vanilla-like (bread 5, cooked porkchop 8); the only effect is the tea's
 * short focus for meditation ({@link io.github.verycooltimo.murim.cultivation.TeaFocus}; author 19.10: «эффект чая можно»).
 */
public final class SectMenu {

    /**
     * A dish: item id, hunger points, saturation modifier (vanilla scale: saturation = 2 * hunger * modifier) and whether it is
     * sipped (drink animation and sound) or eaten.
     */
    public enum Dish {
        MILLET_PORRIDGE("millet_porridge", 5, 0.5F, true),
        STEAMED_BUN("steamed_bun", 4, 0.5F, false),
        RICE_AND_PICKLES("rice_and_pickles", 6, 0.5F, false),
        MOUNTAIN_SOUP("mountain_soup", 4, 0.4F, true),
        PLUM_TEA("plum_tea", 1, 0.1F, true),
        /** Not served by the cook (no feast, author 19.10); kept registered so old saves and the creative tab still work. */
        MEAT_BOWL("meat_bowl", 8, 0.8F, false);

        private final String id;
        private final int nutrition;
        private final float saturation;
        private final boolean drink;

        Dish(String id, int nutrition, float saturation, boolean drink) {
            this.id = id;
            this.nutrition = nutrition;
            this.saturation = saturation;
            this.drink = drink;
        }

        /** Item id in the mod namespace. */
        public String id() {
            return id;
        }

        public int nutrition() {
            return nutrition;
        }

        public float saturation() {
            return saturation;
        }

        public boolean drink() {
            return drink;
        }

        /** The cooled portion kept for a latecomer: half the food, less saturation. */
        public int coldNutrition() {
            return Math.max(1, nutrition / 2);
        }

        public float coldSaturation() {
            return saturation * 0.6F;
        }
    }

    private SectMenu() {
    }

    /**
     * The dishes of a meal: {@code mealId = day * 2 + (0 breakfast | 1 dinner)}. Breakfast: porridge on even days, a bun on odd
     * ones. Dinner by the cycle of three days: rice with pickles and soup / soup and a bun / rice with tea; the review day
     * is no exception. The meat bowl is never on the menu.
     */
    public static List<Dish> menu(long mealId) {
        long day = Math.floorDiv(mealId, 2L);
        boolean dinner = Math.floorMod(mealId, 2L) == 1L;
        if (!dinner) {
            return List.of(Math.floorMod(day, 2L) == 0L ? Dish.MILLET_PORRIDGE : Dish.STEAMED_BUN);
        }
        return switch ((int) Math.floorMod(day, 3L)) {
            case 0 -> List.of(Dish.RICE_AND_PICKLES, Dish.MOUNTAIN_SOUP);
            case 1 -> List.of(Dish.MOUNTAIN_SOUP, Dish.STEAMED_BUN);
            default -> List.of(Dish.RICE_AND_PICKLES, Dish.PLUM_TEA);
        };
    }
}
