package io.github.verycooltimo.murim.technique;

import net.minecraft.resources.ResourceLocation;

/** Author-approved six forms, 03.11; stars are the art's direct twelve-star scale. */
public final class BambooRules {
    public static final int[] UNLOCK = {1, 2, 3, 5, 7, 9};
    public static final double RUSH_SPEED = .22;
    public static int form(ResourceLocation id) { return id == null ? -1 : Styles.BAMBOO_HAND.forms().indexOf(id); }
    public static boolean is(ResourceLocation id) { return id != null && form(id) >= 0; }
    public static boolean canPractice(ResourceLocation id, int stars) {
        int form = form(id);
        return form >= 0 && stars >= (form == 0 ? 0 : UNLOCK[form]);
    }
    public static int window(int stars) { return 4 + 4 * Math.clamp(stars - 1, 0, 11) / 11; }
    public static int wallStun(int stars) { return 20 + 12 * Math.clamp(stars - 2, 0, 10) / 10; }
    public static int catches(int stars) { return stars >= 12 ? 3 : stars >= 10 ? 2 : 1; }
    private BambooRules() { }
}
