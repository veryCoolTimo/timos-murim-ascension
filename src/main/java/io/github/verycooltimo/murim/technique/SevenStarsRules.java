package io.github.verycooltimo.murim.technique;

/** Basic Hua footwork; authored plateaus at ★1/3/5/8/10/12. No astral effects. */
public final class SevenStarsRules {
    private static final int[] STARS = {1, 3, 5, 8, 10, 12};
    private static final double[] SHIFT = {1.5, 1.7, 2, 2.2, 2.5, 2.8};
    private static final double[] FOLLOW = {2, 2.5, 3, 3.5, 4, 4.5};
    public static final int PREPARATION = 2;
    public static final int COOLDOWN = 20;
    private static int index(int stars) {
        int i = 0;
        while (i + 1 < STARS.length && stars >= STARS[i + 1]) i++;
        return i;
    }
    // API: MC 1.21.1 resources/ResourceLocation.java#getNamespace/getPath.
    public static boolean isForm(net.minecraft.resources.ResourceLocation id) {
        return id != null && id.getNamespace().equals("murim")
                && (id.getPath().equals("seven_stars_shift") || id.getPath().equals("seven_stars_follow") || dipper(id));
    }
    public static boolean dipper(net.minecraft.resources.ResourceLocation id) { return id != null && id.getPath().equals("seven_stars_dipper"); }
    public static boolean follows(net.minecraft.resources.ResourceLocation id) { return id.getPath().equals("seven_stars_follow"); }
    public static double distance(int stars, boolean follow) { return (follow ? FOLLOW : SHIFT)[index(stars)]; }
    public static int movement(boolean follow) { return 4; }
    public static int recovery(boolean follow) { return follow ? 4 : 6; }
    /** Below Lightning Flash (1.4 base qi) at every star, including full mastery. */
    public static double qi(int stars) {
        return 0.5 * io.github.verycooltimo.murim.mastery.MasteryRules.costFactor(Math.max(0, stars), 12);
    }
    /** Inclusive windows; sentinels and future timestamps never qualify. */
    public static boolean within(long now, long start, int ticks) {
        return start != Long.MIN_VALUE && now >= start && now - start <= ticks;
    }
    public static boolean huaSword(net.minecraft.resources.ResourceLocation id) {
        if (id == null) return false;
        return Styles.SIX_HARMONIES.forms().contains(id) || Styles.SEVEN_PLUM.forms().contains(id)
                || id != null && id.getNamespace().equals("murim") && id.getPath().equals("falling_petal_sword");
    }
    public static int linkedOpener(int windup) { return (int) Math.round(windup * .30); }
    /** Ordinary mobs: 1.0–1.5 seconds; Stun.apply owns player/boss caps and immunity. */
    public static int stunTicks(int stars) {
        return 20 + (int) Math.round(10 * (Math.clamp(stars, 1, 12) - 1) / 11.0);
    }
    /** Ordered preferred side, opposite side, then the two routes behind. */
    public static int firstFree(java.util.List<Boolean> free) {
        for (int i = 0; i < free.size(); i++) if (free.get(i)) return i;
        return -1;
    }
    /** API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/phys/Vec3.java#distanceTo/lerp. */
    public static net.minecraft.world.phys.Vec3 routePoint(java.util.List<net.minecraft.world.phys.Vec3> route, double fraction) {
        double total = 0;
        for (int i = 1; i < route.size(); i++) total += route.get(i - 1).distanceTo(route.get(i));
        double remaining = total * fraction;
        for (int i = 1; i < route.size(); i++) {
            double length = route.get(i - 1).distanceTo(route.get(i));
            if (remaining <= length && length > 1e-8) return route.get(i - 1).lerp(route.get(i), remaining / length);
            remaining -= length;
        }
        return route.getLast();
    }

    public static double smoothstep(double x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    private SevenStarsRules() { }
}
