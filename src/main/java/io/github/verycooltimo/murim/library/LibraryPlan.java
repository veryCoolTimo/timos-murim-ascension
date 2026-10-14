package io.github.verycooltimo.murim.library;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Plan of one abandoned archive (docs/design/25-ruined-library.md §3), after the author's refs
 * docs/design/reference/library: the Heavenly Demon Archives of Absolute Regression (tall dark shelving packed with
 * near-identical manuals, a precious manual propping up the 19th bookshelf) and the cave archive of lfc-01..07
 * (a rock entrance, a deep shaft with tiers of galleries around a void, lanterns on chains). Pure — no world — so the
 * piece, the GameTest and the loot simulation read the same plan.
 *
 * <p>Archive frame {@code (ax, y, az)}: a 27×27 box, y = 0 the bottom floor. The piece adds the entrance tunnel in
 * front ({@link #TUNNEL} blocks of z) and maps {@code az → z = az + TUNNEL}. State facings in the local frame of
 * StructurePiece: NORTH = +z (deeper in), EAST = +x.
 *
 * <p>Four seven-block tiers, three-high lower racks plus a two-high upper band. Four clear gallery cells
 * separate the wall from the meander railing; the open well is 13×13. Two-wide stairs occupy the outer lane,
 * with a separate two-wide bypass. LibraryPaths describes every reserved three-high walking volume.
 */
public final class LibraryPlan {

    /** Archive box side (with the rock shell). */
    public static final int SIZE = 27;
    /** Length of the entrance tunnel in front of the archive (piece z 0..TUNNEL-1). */
    public static final int TUNNEL = 32;
    /** Flat landing at the bottom of the entrance stairs, before the doorway. */
    public static final int LANDING = 2;
    public static final int WIDTH = SIZE;
    public static final int DEPTH = TUNNEL + SIZE;
    /** Support blocks of the four tiers; the visitor arrives on the top one. */
    public static final int TIER_HEIGHT = 7;
    public static final int[] TIERS = {0, 7, 14, 21};
    public static final int TOP = 21;
    /** Recessed timber ceiling backed by rock. */
    public static final int CEILING = 28;
    /** Natural rock backfill above the timber coffers. */
    public static final int ROCK_TOP = 33;

    public static final int WALL_LO = 1;
    public static final int WALL_HI = 25;
    // Four-deep galleries keep a two-wide stair and a two-wide bypass beside the 13-wide void.
    public static final int RAIL_LO = 6;
    public static final int RAIL_HI = 20;
    public static final int VOID_LO = 7;
    public static final int VOID_HI = 19;

    /** Positions along a wall (1..25): unit columns and the dark posts between them. */
    public static final int[][] UNITS = {{2, 4}, {6, 8}, {10, 12}, {14, 16}, {18, 20}, {22, 24}};
    public static final int[] POSTS = {1, 5, 9, 13, 17, 21, 25};
    /** Corner posts only; no posts through the middle of the view. */
    public static final int[] RAIL_POSTS = {6, 20};

    private static final List<LibraryPaths.Route> SEARCH_ROUTES = LibraryPaths.routes(ROCK_TOP + 10).stream()
            .filter(route -> !route.name().startsWith("gallery-")).toList();

    /** Six treads, two wide: west x 2..3, z 7..12; east x 23..24, z 19..14. */
    public static final int STAIR_WEST_X = 2;
    public static final int STAIR_EAST_X = 23;
    public static final int STAIR_WEST_Z0 = 7;
    public static final int STAIR_EAST_Z0 = 19;

    /** The collapse (codex synthesis: damage by cause, one place): middle tier, south gallery, x 12..16. */
    public static final int COLLAPSE_X0 = 12;
    public static final int COLLAPSE_X1 = 16;
    public static final int COLLAPSE_TIER = 2;

    /** Record barrels in the shelf bases, one per tier: wall side, position p. The top one holds the keeper's note. */
    public static final int[][] BARRELS = {{3, 20}, {1, 7}, {2, 11}, {3, 7}};

    /** Shelf rows above a tier floor that hold takeable volumes; the third row is stacked-manual shelves. */
    public static final int CHISELED_ROWS = 2;
    /** A shelf block is an empty board instead of a shelf (looted, broken). */
    public static final double EMPTY_FRAME = 0.12D;
    /** A lower cell has only textured stacks rather than takeable marked volumes. */
    public static final double PLAIN_SHELF = 0.40D;
    /** A slot of a chiseled shelf still holds a volume (refs: shelving packed with manuals). */
    public static final double SLOT_FILLED = 0.50D;
    /** A volume on the shelves is a genuine torn manual, not junk: shelves are the "98" of 98/2, almost all junk. */
    public static final double SLOT_GENUINE = 0.0012D;

    /** Genuine torn manuals that can sit on the shelves: technique, depth (layers it teaches), weight. */
    public static final List<Genuine> SHELF_GENUINE = List.of(
            new Genuine("murim:six_harmonies", 1, 50),
            new Genuine("murim:falling_petal_sword", 1, 30),
            new Genuine("murim:seven_plum_execution", 1, 20));

    public record Genuine(String technique, int depth, int weight) {
    }

    /** One slot of a chiseled shelf: junk or a genuine torn manual (exactly one is set). */
    public record Slot(JunkBook junk, Genuine genuine) {
        public boolean useful() {
            return genuine != null;
        }
    }

    /** What stands in one shelf-wall cell. */
    public enum Cell { CHISELED, PLAIN, BUNDLE, EMPTY, PROPPED, PROPPED_UPPER, BARREL, GONE }

    /** A chiseled shelf: archive position, facing side (0 W wall, 1 N, 2 E, 3 S), tier, ring unit, six slots. */
    public record Shelf(int ax, int y, int az, int side, int tier, int unit, Slot[] slots) {
        public int count() {
            int n = 0;
            for (Slot s : slots) {
                if (s != null) {
                    n++;
                }
            }
            return n;
        }
    }

    /** A complete lower-band rack, facing the gallery (same side convention as Shelf). */
    public record Bookcase(int ax, int y, int az, int side, int tier, boolean tall) { }
    /** Existing gallery lantern; wall lights are vanilla, ring lights use the decor lanterns. */
    public record GalleryLight(int ax, int y, int az, int side, int p, int tier, boolean wall) { }
    /** Relocation of an existing reward, never a new loot roll. */
    public record RackManual(Shelf source, int sourceSlot, Bookcase target, Genuine genuine) { }

    private final long seed;
    private final int proppedNumber;
    private final List<Shelf> shelves;
    private final Cell[][][] cells;
    private final JunkBook deskClue;
    private final List<Bookcase> bookcases;
    private final List<GalleryLight> galleryLights;
    private final List<RackManual> rackManuals;

    private LibraryPlan(long seed, int proppedNumber, List<Shelf> shelves, Cell[][][] cells, JunkBook deskClue) {
        this.seed = seed;
        this.proppedNumber = proppedNumber;
        this.shelves = shelves;
        this.cells = cells;
        this.galleryLights = planGalleryLights();
        this.bookcases = planBookcases();
        this.rackManuals = planRackManuals();
        int tiers = 0;
        for (RackManual manual : rackManuals) tiers |= 1 << manual.target().tier();
        // Include any original shelf rewards left behind if a future plan exceeds rack capacity.
        for (Shelf shelf : shelves) {
            for (int slot = 0; slot < shelf.slots().length; slot++) {
                if (shelf.slots()[slot] != null && shelf.slots()[slot].useful() && !moved(shelf, slot)) {
                    tiers |= 1 << shelf.tier();
                }
            }
        }
        this.deskClue = new JunkBook(deskClue.kind(), deskClue.seed(), deskClue.hint(), deskClue.read(), tiers);
    }

    /**
     * The archive for {@code seed}: which shelf stands on the book (12..20, 19 most often — the canon number), what
     * stands in every shelf cell and what lies on every chiseled shelf.
     */
    public static LibraryPlan plan(long seed) {
        Random r = new Random(seed);
        int propped = r.nextInt(10) < 4 ? 19 : 12 + r.nextInt(9);
        int[] proppedCell = unitCentre(0, propped);
        // cells[tier][ring position 0..79][row 0..2]
        Cell[][][] cells = new Cell[TIERS.length][4 * 18][3];
        List<Shelf> shelves = new ArrayList<>();
        for (int t = 0; t < TIERS.length; t++) {
            for (int side = 0; side < 4; side++) {
                for (int u = 0; u < UNITS.length; u++) {
                    for (int p = UNITS[u][0]; p <= UNITS[u][1]; p++) {
                        int ring = side * 18 + u * 3 + (p - UNITS[u][0]);
                        int[] a = wallCell(side, p);
                        for (int row = 0; row < 3; row++) {
                            int y = TIERS[t] + 1 + row;
                            Cell c;
                            if (t == 3 && side == 3 && u == 2) {
                                c = Cell.GONE; // the doorway from the tunnel
                            } else if (t == COLLAPSE_TIER && side == 3 && a[0] >= COLLAPSE_X0 && a[0] <= COLLAPSE_X1 && r.nextDouble() < 0.6D) {
                                c = Cell.GONE; // fell with the gallery
                            } else if (t == 0 && row < 2 && a[0] == proppedCell[0] && a[1] == proppedCell[1]) {
                                c = row == 0 ? Cell.PROPPED : Cell.PROPPED_UPPER;
                            } else if (row == 0 && BARRELS[t][0] == side && BARRELS[t][1] == p) {
                                c = Cell.BARREL;
                            } else if (r.nextDouble() < EMPTY_FRAME) {
                                c = Cell.EMPTY;
                            } else if (row >= CHISELED_ROWS) {
                                // Top row: flat decorative stacks with a few variations in their arrangement.
                                c = r.nextDouble() < 0.15D ? Cell.BUNDLE : Cell.PLAIN;
                            } else if (r.nextDouble() < PLAIN_SHELF) {
                                c = Cell.PLAIN;
                            } else {
                                c = Cell.CHISELED;
                            }
                            cells[t][ring][row] = c;
                            if (c == Cell.CHISELED) {
                                Slot[] slots = new Slot[6];
                                for (int i = 0; i < 6; i++) {
                                    if (LibraryShelfLayout.available(LibraryShelfLayout.variantAt(a[0], y, a[1]), i)
                                            && r.nextDouble() < SLOT_FILLED) {
                                        slots[i] = slot(r, propped);
                                    }
                                }
                                shelves.add(new Shelf(a[0], y, a[1], side, t, ringUnit(side, u), slots));
                            }
                        }
                    }
                }
            }
        }
        JunkBook clue = new JunkBook(JunkKind.CLUE, r.nextLong(), propped, false);
        return new LibraryPlan(seed, propped, Collections.unmodifiableList(shelves), cells, clue);
    }

    private static Slot slot(Random r, int propped) {
        if (r.nextDouble() < SLOT_GENUINE) {
            int total = SHELF_GENUINE.stream().mapToInt(Genuine::weight).sum();
            int roll = r.nextInt(total);
            for (Genuine g : SHELF_GENUINE) {
                roll -= g.weight();
                if (roll < 0) {
                    return new Slot(null, g);
                }
            }
        }
        JunkKind kind = JunkKind.pick(JunkKind.values(), r.nextInt(JunkKind.totalWeight()));
        return new Slot(new JunkBook(kind, r.nextLong(), kind == JunkKind.CLUE ? propped : -1, false), null);
    }

    /** Archive (ax, az) of position {@code p} (1..21) along wall {@code side}: 0 west, 1 north, 2 east, 3 south. */
    public static int[] wallCell(int side, int p) {
        return switch (side) {
            case 0 -> new int[]{WALL_LO, p};
            case 1 -> new int[]{p, WALL_HI};
            case 2 -> new int[]{WALL_HI, p};
            default -> new int[]{p, WALL_LO};
        };
    }

    /**
     * Counting order of the units on a tier, as the keeper's note counts them: from the foot of the bottom stairs
     * (west gallery, landing at z 13) along the wall on the left hand — west wall northwards from the unit beside the
     * foot, then the north wall eastwards, the east wall southwards, the south wall westwards, the west wall again.
     *
     * @return 1..24 for unit {@code u} (0..5, by increasing p) of {@code side}
     */
    public static int ringUnit(int side, int u) {
        int order = switch (side) {
            case 0 -> u >= 3 ? u - 3 : u + 21;   // p 14-16 is the first; 2-4, 6-8, 10-12 are the last three
            case 1 -> 3 + u;                     // north wall, west → east
            case 2 -> 9 + (5 - u);               // east wall, north → south
            default -> 15 + (5 - u);             // south wall, east → west
        };
        return order + 1;
    }

    /** Archive (ax, az) of the middle column of unit number {@code n} (1..24) in counting order. */
    public static int[] unitCentre(int tier, int n) {
        for (int side = 0; side < 4; side++) {
            for (int u = 0; u < UNITS.length; u++) {
                if (ringUnit(side, u) == n) {
                    return wallCell(side, UNITS[u][0] + 1);
                }
            }
        }
        throw new IllegalArgumentException("unit " + n);
    }

    /** Side (0 W, 1 N, 2 E, 3 S) of the propped unit. */
    public int proppedSide() {
        for (int side = 0; side < 4; side++) {
            for (int u = 0; u < UNITS.length; u++) {
                if (ringUnit(side, u) == proppedNumber) {
                    return side;
                }
            }
        }
        return 0;
    }

    public long seed() {
        return seed;
    }

    /** Number of the shelf (counted from the bottom stairs) that stands on the genuine manual. */
    public int proppedNumber() {
        return proppedNumber;
    }

    public int[] proppedCell() {
        return unitCentre(0, proppedNumber);
    }

    public List<Shelf> shelves() {
        return shelves;
    }

    /** What stands at wall position {@code p} of {@code side}, tier {@code tier}, shelf row {@code row} (0..2). */
    public Cell cell(int tier, int side, int p, int row) {
        for (int u = 0; u < UNITS.length; u++) {
            if (p >= UNITS[u][0] && p <= UNITS[u][1]) {
                return cells[tier][side * 18 + u * 3 + (p - UNITS[u][0])][row];
            }
        }
        return Cell.GONE;
    }

    /** The keeper's note laid in the top tier's record barrel, by the doorway: the guaranteed way to the propped shelf. */
    public JunkBook deskClue() {
        return deskClue;
    }

    public Shelf shelfAt(int ax, int y, int az) {
        for (Shelf s : shelves) {
            if (s.ax() == ax && s.y() == y && s.az() == az) {
                return s;
            }
        }
        return null;
    }

    public int books() {
        return shelves.stream().mapToInt(Shelf::count).sum();
    }

    public int genuineOnShelves() {
        int n = 0;
        for (Shelf s : shelves) {
            for (Slot slot : s.slots()) {
                if (slot != null && slot.useful()) {
                    n++;
                }
            }
        }
        return n;
    }

    public static boolean decorative(Cell cell) {
        return cell == Cell.PLAIN || cell == Cell.BUNDLE;
    }

    /** Shared by the piece and reward selection, so a lantern over a missing deck is never assumed lit. */
    public boolean hole(int tier, int ax, int az) {
        if (tier > 0) {
            boolean west = (tier & 1) != 0;
            int x = west ? STAIR_WEST_X : STAIR_EAST_X;
            int z0 = west ? STAIR_WEST_Z0 : STAIR_EAST_Z0;
            int along = west ? az - z0 : z0 - az;
            if (ax >= x && ax <= x + 1 && along >= 0 && along < TIER_HEIGHT - 1) return true;
        }
        return collapsed(tier, ax, az);
    }

    public boolean collapsed(int tier, int ax, int az) {
        if (az > RAIL_LO) return false;
        double jag = noise(seed, ax, tier, az);
        if (tier == COLLAPSE_TIER) {
            return ax >= COLLAPSE_X0 && ax <= COLLAPSE_X1 && az >= 2
                    || (ax == COLLAPSE_X0 - 1 || ax == COLLAPSE_X1 + 1) && az >= 3 && jag < 0.5D;
        }
        return tier == COLLAPSE_TIER - 1 && ax >= COLLAPSE_X0 + 1 && ax <= COLLAPSE_X1 - 1
                && az >= 2 && az <= 3 && jag < 0.8D;
    }

    private List<GalleryLight> planGalleryLights() {
        List<GalleryLight> lights = new ArrayList<>();
        for (int tier = 0; tier < TIERS.length; tier++) {
            int above = tier + 1 < TIERS.length ? TIERS[tier + 1] : CEILING;
            for (int side = 0; side < 4; side++) {
                for (int p = RAIL_LO + 2 + (tier & 1); p < RAIL_HI; p += 4) {
                    int ax = side == 0 ? RAIL_LO : side == 2 ? RAIL_HI : p;
                    int az = side == 3 ? RAIL_LO : side == 1 ? RAIL_HI : p;
                    if (tier + 1 < TIERS.length && hole(tier + 1, ax, az)) continue;
                    lights.add(new GalleryLight(ax, TIERS[tier] + 4, az, side, p, tier, false));
                }
                for (int i = 1; i < POSTS.length - 1; i += 2) {
                    int[] w = wallCell(side, POSTS[i]);
                    int ax = w[0] + (side == 0 ? 1 : side == 2 ? -1 : 0);
                    int az = w[1] + (side == 3 ? 1 : side == 1 ? -1 : 0);
                    if (tier + 1 < TIERS.length && hole(tier + 1, ax, az)) continue;
                    lights.add(new GalleryLight(ax, above - 1, az, side, POSTS[i], tier, true));
                }
            }
        }
        return List.copyOf(lights);
    }

    private List<Bookcase> planBookcases() {
        List<Bookcase> racks = new ArrayList<>();
        for (int tier = 0; tier < TIERS.length; tier++) {
            int x = (tier & 1) == 0 ? 8 : 18;
            racks.add(new Bookcase(x, TIERS[tier] + 1, 24, 1, tier, false));
            racks.add(new Bookcase(x + 1, TIERS[tier] + 1, 24, 1, tier, true));
            for (int side = 0; side < 4; side++) {
                for (int[] unit : UNITS) {
                    for (int p = unit[0]; p <= unit[1]; p++) {
                        boolean lo = decorative(cell(tier, side, p, 0));
                        boolean mid = decorative(cell(tier, side, p, 1));
                        boolean hi = decorative(cell(tier, side, p, 2));
                        int start = lo && mid ? 0 : mid && hi ? 1 : -1;
                        if (start < 0) continue;
                        int[] c = wallCell(side, p);
                        racks.add(new Bookcase(c[0], TIERS[tier] + 1 + start, c[1], side, tier,
                                start == 0 && hi));
                    }
                }
            }
        }
        return List.copyOf(racks);
    }

    /** Distance from the lowest shelf's book area to an existing same-tier lantern. */
    public double galleryLightDistance(Bookcase rack) {
        double best = Double.POSITIVE_INFINITY;
        for (GalleryLight light : galleryLights) {
            if (light.tier() != rack.tier() || light.side() != rack.side()) continue;
            double dx = rack.ax() - light.ax();
            double dy = rack.y() + .4D - (light.y() + .25D);
            double dz = rack.az() - light.az();
            best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return best;
    }

    public boolean facesWalkingGallery(Bookcase rack) {
        int ax = rack.ax() + (rack.side() == 0 ? 1 : rack.side() == 2 ? -1 : 0);
        int az = rack.az() + (rack.side() == 3 ? 1 : rack.side() == 1 ? -1 : 0);
        if (hole(rack.tier(), ax, az)) return false;
        // Wall racks directly behind the rear sorting bays cannot be searched from the gallery.
        int bayX = (rack.tier() & 1) == 0 ? 8 : 18;
        if (rack.side() == 1 && rack.az() == WALL_HI && ax >= bayX && ax <= bayX + 1) return false;
        // Keep stair treads, landings and the entrance out of the sight/click lane.
        for (LibraryPaths.Route route : SEARCH_ROUTES) {
            for (LibraryPaths.Tread tread : route.treads()) {
                if (tread.x() == ax && tread.z() == az + TUNNEL
                        && tread.y() > TIERS[rack.tier()] && tread.y() < TIERS[rack.tier()] + TIER_HEIGHT) return false;
            }
        }
        return true;
    }

    private List<RackManual> planRackManuals() {
        List<Bookcase> targets = new ArrayList<>();
        for (Bookcase rack : bookcases) {
            if (facesWalkingGallery(rack) && galleryLightDistance(rack) <= 5.0D) targets.add(rack);
        }
        // Independent RNG: shelf contents, counts, techniques and the keeper's original note seed are unchanged.
        Collections.shuffle(targets, new Random(seed ^ 0x4D414E55414CL));
        List<RackManual> manuals = new ArrayList<>();
        for (Shelf shelf : shelves) {
            for (int slot = 0; slot < shelf.slots().length; slot++) {
                Slot book = shelf.slots()[slot];
                if (book != null && book.useful() && manuals.size() < targets.size()) {
                    manuals.add(new RackManual(shelf, slot, targets.get(manuals.size()), book.genuine()));
                }
            }
        }
        return List.copyOf(manuals);
    }

    private boolean moved(Shelf shelf, int slot) {
        for (RackManual manual : rackManuals) {
            if (manual.source() == shelf && manual.sourceSlot() == slot) return true;
        }
        return false;
    }

    public List<Bookcase> bookcases() { return bookcases; }
    public List<GalleryLight> galleryLights() { return galleryLights; }
    public List<RackManual> rackManuals() { return rackManuals; }

    /** Stable per-block noise in [0, 1) for ruin decisions that must agree across chunk borders. */
    public static double noise(long seed, int x, int y, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L);
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }
}
