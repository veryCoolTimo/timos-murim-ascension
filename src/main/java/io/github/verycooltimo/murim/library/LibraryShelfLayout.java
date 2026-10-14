package io.github.verycooltimo.murim.library;

/** Shelf layouts and book hit regions for the archive plan. Matches tools/art/library_shelves.py. */
public final class LibraryShelfLayout {
    private LibraryShelfLayout() {}

    /** A stable mix of coordinates; translating or rotating a placed shelf preserves its stored variant. */
    public static int variantAt(int x, int y, int z) {
        int hash = x * 73428767 ^ y * 912931 ^ z * 438289;
        return (hash ^ (hash >>> 16)) & 3;
    }

    public static boolean available(int variant, int slot) {
        return switch (variant) {
            case 1 -> slot == 0;
            case 2 -> slot == 2 || slot == 3;
            case 3 -> slot == 5;
            default -> false;
        };
    }

    /** Exclusive front bounds in model pixels, with v measured downward from the facade's top. */
    public static double[] bookBounds(int variant, int slot) {
        if (!available(variant, slot)) return null;
        return switch (slot) {
            case 0 -> new double[] {1.7, 4.95, 7.0, 5.8};
            case 2 -> new double[] {12.0, 1.6, 13.3, 7.5};
            case 3 -> new double[] {1.7, 12.45, 7.0, 13.3};
            default -> new double[] {9.0, 12.45, 14.3, 13.3};
        };
    }
}
