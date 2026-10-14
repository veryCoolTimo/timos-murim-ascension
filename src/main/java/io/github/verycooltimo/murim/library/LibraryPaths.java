package io.github.verycooltimo.murim.library;

import java.util.ArrayList;
import java.util.List;

/** Shared circulation geometry. Coordinates are piece-local, including the tunnel offset.
 * Each tread reserves three whole air blocks above its support, for both columns of a flight.
 * No world access; the offline Python review mirrors these routes, while GameTests inspect the built world.
 */
public final class LibraryPaths {
    public record Tread(int x, int y, int z, boolean stair, int dz) { }
    public record Route(String name, List<Tread> treads) { }

    private LibraryPaths() { }

    public static int mouthZ(int mouthY) {
        return mouthZ(mouthY, LibraryPlan.TUNNEL);
    }

    public static int mouthZ(int mouthY, int tunnel) {
        return Math.max(1, tunnel - LibraryPlan.LANDING - Math.max(0, mouthY - LibraryPlan.TOP));
    }

    public static int entranceFloor(int mouthY, int z) {
        return entranceFloor(mouthY, z, LibraryPlan.TUNNEL);
    }

    public static int entranceFloor(int mouthY, int z, int tunnel) {
        return Math.min(mouthY, LibraryPlan.TOP + Math.max(0, tunnel - LibraryPlan.LANDING - z));
    }

    public static List<Route> routes(int mouthY) {
        return routes(mouthY, LibraryPlan.TUNNEL);
    }

    public static List<Route> routes(int mouthY, int tunnel) {
        List<Route> routes = new ArrayList<>();
        List<Tread> entrance = new ArrayList<>();
        for (int z = Math.max(0, mouthZ(mouthY, tunnel) - 2); z <= tunnel + 3; z++) {
            int floor = entranceFloor(mouthY, z, tunnel);
            for (int x = 10; x <= 12; x++) {
                entrance.add(new Tread(x, floor, z, floor > LibraryPlan.TOP && floor < mouthY, 1));
            }
        }
        routes.add(new Route("entrance", List.copyOf(entrance)));
        for (int tier = 1; tier < LibraryPlan.TIERS.length; tier++) {
            boolean west = (tier & 1) != 0;
            int x = west ? LibraryPlan.STAIR_WEST_X : LibraryPlan.STAIR_EAST_X;
            int z0 = west ? LibraryPlan.STAIR_WEST_Z0 : LibraryPlan.STAIR_EAST_Z0;
            int dir = west ? 1 : -1;
            int top = LibraryPlan.TIERS[tier];
            List<Tread> flight = new ArrayList<>();
            for (int i = -1; i <= LibraryPlan.TIER_HEIGHT - 1; i++) {
                int y = i < 0 ? top : top - 1 - i;
                for (int dx = 0; dx < 2; dx++) {
                    flight.add(new Tread(x + dx, y, z0 + dir * i + tunnel,
                            i >= 0 && i < LibraryPlan.TIER_HEIGHT - 1, dir));
                }
            }
            routes.add(new Route("flight-" + tier, List.copyOf(flight)));
        }
        // Two-wide bypass beside the outer stair lane. The damaged south span is bypassed to the north.
        for (int tier = 0; tier < LibraryPlan.TIERS.length; tier++) {
            List<Tread> gallery = new ArrayList<>();
            int y = LibraryPlan.TIERS[tier];
            for (int p = 4; p <= 22; p++) {
                for (int lane = 4; lane <= 5; lane++) {
                    gallery.add(new Tread(lane, y, p + tunnel, false, 0));
                    gallery.add(new Tread(26 - lane, y, p + tunnel, false, 0));
                    gallery.add(new Tread(p, y, 26 - lane + tunnel, false, 0));
                    if (tier != LibraryPlan.COLLAPSE_TIER) {
                        gallery.add(new Tread(p, y, lane + tunnel, false, 0));
                    }
                }
            }
            routes.add(new Route("gallery-" + tier, List.copyOf(gallery)));
        }
        return List.copyOf(routes);
    }
}
