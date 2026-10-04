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
 * <pre>
 *  az 26 shell (rock)                      tiers: floors y 0, 5, 10, 15; rock from y 20
 *  az 25 ▤▤▤|▤▤▤|▤▤▤|▤▤▤|▤▤▤|▤▤▤   shelf wall ring: 6 units of 3 per side, dark posts between (24 units a tier)
 *  az 22..24  gallery, 3 clear (codex frame review 04.10: shallower galleries, the void dominates)
 *  az 21      railing line: fence + dark oak panel lattice, posts through all tiers, lanterns under the deck
 *  az 6..20   void 15×15, open to the rock; at the bottom the keeper's reading spot
 *  az 5       railing line
 *  az 2..4    gallery; stairs (2 wide, inner half): T3→T2 and T1→T0 west, T2→T1 east
 *  az 1       shelf wall; on the top tier its unit p 10-12 is the doorway from the tunnel
 * </pre>
 */
public final class LibraryPlan {

    /** Archive box side (with the rock shell). */
    public static final int SIZE = 27;
    /** Length of the entrance tunnel in front of the archive (piece z 0..TUNNEL-1). */
    public static final int TUNNEL = 24;
    /** Flat landing at the bottom of the entrance stairs, before the doorway. */
    public static final int LANDING = 4;
    public static final int WIDTH = SIZE;
    public static final int DEPTH = TUNNEL + SIZE;
    /** Floors of the four tiers; the visitor arrives on the top one. */
    public static final int[] TIERS = {0, 5, 10, 15};
    public static final int TOP = 15;
    /** Rock ceiling (air below it up to y 19). */
    public static final int CEILING = 20;

    public static final int WALL_LO = 1;
    public static final int WALL_HI = 25;
    // Codex frame review 04.10: galleries 3 deep, the void 15 wide — the void must dominate the frame.
    public static final int RAIL_LO = 5;
    public static final int RAIL_HI = 21;
    public static final int VOID_LO = 6;
    public static final int VOID_HI = 20;

    /** Positions along a wall (1..25): unit columns and the dark posts between them. */
    public static final int[][] UNITS = {{2, 4}, {6, 8}, {10, 12}, {14, 16}, {18, 20}, {22, 24}};
    public static final int[] POSTS = {1, 5, 9, 13, 17, 21, 25};
    /** Posts on the railing line, running through all tiers (about 4-block centres). */
    // Corners and the middle only (codex frame review: posts every 4 blocks hid the stacked galleries).
    public static final int[] RAIL_POSTS = {5, 13, 21};

    /** Stairs, 2 wide on the inner half of a gallery: west x 3..4 steps z 8..11 (T3→T2, T1→T0); east x 22..23 steps z 18..15 (T2→T1). */
    public static final int STAIR_WEST_X = 3;
    public static final int STAIR_EAST_X = 22;
    public static final int STAIR_WEST_Z0 = 8;
    public static final int STAIR_EAST_Z0 = 18;

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
    /** A lower cell is a stacked-manuals shelf (decor, breaks into junk) rather than a chiseled one. */
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
    public enum Cell { CHISELED, PLAIN, BUNDLE, EMPTY, PROPPED, BARREL, GONE }

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

    private final long seed;
    private final int proppedNumber;
    private final List<Shelf> shelves;
    private final Cell[][][] cells;
    private final JunkBook deskClue;

    private LibraryPlan(long seed, int proppedNumber, List<Shelf> shelves, Cell[][][] cells, JunkBook deskClue) {
        this.seed = seed;
        this.proppedNumber = proppedNumber;
        this.shelves = shelves;
        this.cells = cells;
        this.deskClue = deskClue;
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
                            } else if (t == 0 && row == 0 && a[0] == proppedCell[0] && a[1] == proppedCell[1]) {
                                c = Cell.PROPPED;
                            } else if (row == 0 && BARRELS[t][0] == side && BARRELS[t][1] == p) {
                                c = Cell.BARREL;
                            } else if (r.nextDouble() < EMPTY_FRAME) {
                                c = Cell.EMPTY;
                            } else if (row >= CHISELED_ROWS) {
                                // Top row: stacked-manual shelves, now and then a loose pale bundle (birch slab).
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
                                    if (r.nextDouble() < SLOT_FILLED) {
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
     * (west gallery, landing at z 12) along the wall on the left hand — west wall northwards from the unit beside the
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
