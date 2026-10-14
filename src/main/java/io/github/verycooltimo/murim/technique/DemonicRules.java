package io.github.verycooltimo.murim.technique;

import net.minecraft.resources.ResourceLocation;
import java.util.List;

/** Layer tables from ar-demonic-arts.md. Qi upkeep is already a final price, not multiplied twice. */
public final class DemonicRules {
    public static final List<String> ARTS = List.of("mountain_thunder_fist", "rift_palm", "clear_sky_sword",
            "high_cloud_steps", "quiet_heart_guard", "four_horizons");
    public static ResourceLocation id(String art) { return ResourceLocation.fromNamespaceAndPath("murim", art); }
    public static boolean isArt(ResourceLocation id) { return id != null && id.getNamespace().equals("murim") && ARTS.contains(id.getPath()); }
    public static boolean sword(String art) { return art.equals("clear_sky_sword") || art.equals("four_horizons"); }
    public static boolean sustained(String art) { return art.equals("high_cloud_steps") || art.equals("quiet_heart_guard"); }
    public static int group(int executionTier) { return executionTier < 2 ? 0 : executionTier < 4 ? 1 : executionTier < 7 ? 2 : 3; }
    public static int visual(int executionTier) { return executionTier <= 0 ? 0 : executionTier < 4 ? 1 : executionTier < 7 ? 2 : 3; }
    public static double cost(String art) { return switch (art) {
        case "mountain_thunder_fist" -> 2.5; case "rift_palm" -> 2.8; case "clear_sky_sword" -> 2.1;
        case "high_cloud_steps" -> .4; case "quiet_heart_guard" -> 2; case "four_horizons" -> 4;
        default -> throw new IllegalArgumentException(art);
    }; }
    public static double reach(String art, int executionTier) {
        int g = group(executionTier);
        return switch (art) {
            case "mountain_thunder_fist" -> new double[]{1.8, 1.85, 1.9, 2.0}[g];
            case "rift_palm" -> new double[]{2.2, 3, 4, 5}[g];
            case "clear_sky_sword" -> new double[]{1.8, 1.9, 2.0, 2.0}[g];
            case "four_horizons" -> new double[]{3, 4, 5, 6}[g];
            default -> 0;
        };
    }
    public static double coefficient(String art, int executionTier) {
        int g = group(executionTier);
        return switch (art) {
            case "mountain_thunder_fist", "clear_sky_sword" -> new double[]{1, 1, 1.1, 1.2}[g];
            case "rift_palm" -> g < 2 ? .9 : 1;
            case "four_horizons" -> new double[]{1, 1.2, 1.5, 1.8}[g];
            default -> 0;
        };
    }
    public static int contacts(String art, int executionTier) {
        return art.equals("four_horizons") ? group(executionTier) + 1 : art.equals("clear_sky_sword") ? new int[]{1,2,2,3}[group(executionTier)] : 1;
    }
    public static int contactTick(String art, int executionTier, int index) { return index < contacts(art, executionTier) ? index * (art.equals("clear_sky_sword") ? 2 : 1) : -1; }
    public static double share(String art, int executionTier, int index) {
        int count = contacts(art, executionTier);
        return art.equals("clear_sky_sword") && count == 3 ? (index == 2 ? .30 : .35) : 1.0 / count;
    }
    public static double push(String art, int executionTier) { return (art.equals("rift_palm") ? .15 : .2) + .05 * group(executionTier); }
    public static int stun(int executionTier) { return new int[]{0,4,6,8}[group(executionTier)]; }
    public static int duration(String art, int executionTier) { return (art.equals("high_cloud_steps") ? new int[]{60,80,100,140} : new int[]{60,80,100,120})[group(executionTier)]; }
    public static double upkeep(String art, int executionTier) { return (art.equals("high_cloud_steps") ? new double[]{.40,.36,.30,.24} : new double[]{.35,.32,.28,.24})[group(executionTier)] / 20; }
    public static double speed(int executionTier) { return new double[]{8,10,12,14}[group(executionTier)]; }
    public static double pressure(int executionTier) { return new double[]{.9,.8,.7,.6}[group(executionTier)]; }
    public static double reduction(int executionTier) { return new double[]{.05,.10,.15,.20}[group(executionTier)]; }
    private DemonicRules() {}
}
