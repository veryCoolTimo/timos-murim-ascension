package io.github.verycooltimo.murim.world.hua;

import java.util.List;

/**
 * Fixed plan of Mount Hua in the mountain's local frame: {@code u} grows to the east, {@code v} to
 * the south, heights are "nominal" blocks above the foot (the South Peak summit is {@link #SUMMIT}).
 * The world placement rotates the frame by a multiple of 90° (terraces stay grid-aligned) and
 * scales heights so the South Peak lands at {@link MountHuaSite#SUMMIT_Y}.
 *
 * <p>Layout follows the real Huashan (docs/design/reference/mount-hua/DESCRIPTIONS.md, «Что важно
 * для генератора»): scale ≈ 7.4 m per block both ways (plain 400 m = 0, South Peak 2155 m = 236).
 * South, East and West peaks stand in a tight horseshoe open to the north, South at its head; the
 * small Middle Peak sits inside the horseshoe near the East Peak; the Golden Lock pass is the only
 * gateway into the horseshoe; the knife-edge Canglong ridge runs from it ~270 blocks north to the
 * low North Peak, which stands on the edge of a straight fault scarp that drops to the flat plain
 * with no foothills. To the south the massif dissolves into rows of lesser granite fangs.
 */
public final class MountHuaPlan {

    private MountHuaPlan() {
    }

    /** Nominal height of the South Peak summit above the foot. */
    public static final double SUMMIT = 244.0;

    /** Nominal line of the northern fault scarp (v). */
    public static final double SCARP_V = -372;

    /**
     * Granite dome-wedge ("bullet"): h = top·(1 − (d/R)^power), elongated along {@code angle}
     * (degrees from +u) with half-axes {@code ra} (along) and {@code rb} (across).
     */
    public record Peak(String name, double u, double v, double top, double ra, double rb, double angle,
            double power) {
    }

    /** Polyline crest; {@code steep} = drop in blocks per block away from the crest line. */
    public record Ridge(double[] u, double[] v, double[] h, double steep) {
    }

    /** Polyline gorge cut; floor height along the path, half width of the flat floor. */
    public record Gorge(double[] u, double[] v, double[] floor, double halfWidth, double wallSlope) {
    }

    /**
     * Flat terrace for the author's buildings ({@code cave} = room carved in the rock instead, with a
     * tunnel towards {@code (exitU, exitV)}). {@code y} is nominal; sizes in blocks.
     */
    public record Zone(String id, String title, double u, double v, int width, int depth, double y,
            boolean cave, double exitU, double exitV) {
    }

    /** Trail control point (nominal y). */
    public record TrailPoint(double u, double v, double y) {
    }

    // Domes: high exponents give the manhwa's massive rounded tops on near-vertical sides
    // (author refs 10, 14, 16).
    // Real heights (m): South 2155, East 2096, West 2083, Middle 2038, North 1615 -> nominal.
    public static final List<Peak> PEAKS = List.of(
            // Broad masses rather than needles (codex r2): widths vary more than heights.
            new Peak("south", 5, 125, 244, 80, 60, 10, 4.2),
            new Peak("east", 130, 15, 238, 66, 48, 70, 4.0),
            new Peak("west", -125, 35, 236, 74, 44, 75, 4.4),
            new Peak("middle", 68, 48, 226, 32, 24, 40, 2.6),
            new Peak("north", -10, -335, 163, 46, 34, 0, 3.0),
            // Subsidiary summits and shoulders (asymmetric masses, not five isolated towers).
            new Peak("south_shoulder", 70, 140, 205, 52, 34, 140, 2.4),
            new Peak("west_shoulder", -175, 85, 196, 46, 30, 30, 2.4),
            new Peak("east_shoulder", 175, 60, 200, 46, 30, 120, 2.4),
            new Peak("vault_hill", -32, 116, 200, 24, 18, 0, 2.6));

    /** Pillar forest basin (author ref 03): centre and radius in the local frame. */
    public static final double PILLARS_U = 150;
    public static final double PILLARS_V = 330;
    public static final double PILLARS_R = 150;

    public static boolean inPillarBasin(double u, double v) {
        return Math.hypot(u - PILLARS_U, v - PILLARS_V) < PILLARS_R;
    }

    /** Satellite granite peaks in the foothills, long axis pointing at the massif. */
    public static final List<Peak> SATELLITES = List.of(
            new Peak("sat_nw", -560, -260, 80, 95, 46, 25, 1.6),
            // A broad shoulder rather than a separate dome (codex r2).
            new Peak("sat_e", 560, 150, 92, 150, 62, 15, 1.4),
            new Peak("sat_sw", -430, 560, 66, 90, 44, -50, 1.6),
            new Peak("sat_ne", 430, -520, 58, 85, 40, 50, 1.5));

    /** Ridges joining the satellites to the massif (descending towards the satellite saddle). */
    public static final List<Ridge> BELT_RIDGES = List.of(
            new Ridge(new double[] {-500, -380, -260}, new double[] {-230, -170, -100}, new double[] {70, 55, 80}, 1.1),
            new Ridge(new double[] {490, 380, 260}, new double[] {130, 100, 60}, new double[] {85, 60, 90}, 1.1),
            new Ridge(new double[] {-380, -290, -200}, new double[] {500, 400, 300}, new double[] {60, 50, 75}, 1.1),
            new Ridge(new double[] {390, 300, 200}, new double[] {-470, -360, -250}, new double[] {50, 45, 70}, 1.1));

    /** Main valley: from the gate terrace out to the edge of the foothills (stream bed along it). */
    public static final double[] VALLEY_U = {-104, -120, -135, -150, -120, -90};
    public static final double[] VALLEY_V = {-416, -470, -600, -720, -840, -980};
    public static final double[] VALLEY_FLOOR = {2.5, 2.2, 1.5, 1, 0.5, 0};

    public static final List<Ridge> RIDGES = List.of(
            // Canglong (Blue Dragon) ridge: knife edge climbing from the North Peak to the Golden Lock.
            new Ridge(new double[] {-8, 8, -2, 14, 6, 22},
                    new double[] {-305, -262, -215, -170, -125, -78},
                    new double[] {140, 144, 150, 156, 163, 170}, 2.8),
            // Horseshoe saddles: short, high, sagging.
            new Ridge(new double[] {-110, -55, -10}, new double[] {60, 105, 120},
                    new double[] {196, 182, 200}, 2.4),
            new Ridge(new double[] {20, 75, 120}, new double[] {120, 75, 30},
                    new double[] {200, 184, 196}, 2.4),
            new Ridge(new double[] {22, 60, 110}, new double[] {-78, -40, 5},
                    new double[] {178, 182, 192}, 2.6),
            // Northern rim of the basin: Golden Lock -> West Peak (hides the sect from the north).
            new Ridge(new double[] {22, -40, -100, -125}, new double[] {-78, -62, -22, 10},
                    new double[] {178, 184, 192, 205}, 2.6));

    public static final List<Gorge> GORGES = List.of(
            // Huashan Yu: the gorge of the only trail, cut through the scarp west of the North Peak.
            // Narrow, near-vertical walls: the climb starts as a stair up into this slot
            // (author2/foot-of-mountain.png).
            new Gorge(new double[] {-104, -100, -82, -66},
                    new double[] {-392, -365, -330, -296},
                    new double[] {3, 22, 58, 100}, 3.5, 9.0));

    public static final List<Zone> ZONES = List.of(
            // Gate at the mouth of the gorge, right under the scarp wall (author ref 06: the red gate
            // stands in front of a cliff face).
            new Zone("gate", "Gate terrace", -104, -404, 34, 20, 3, false, 0, 0),
            // Inner sect gate where the trail reaches the main terrace (author refs 06, 12).
            new Zone("sect_gate", "Sect gate", 14, -24, 28, 10, 156, false, 0, 0),
            // Pavilions on summits and a cliff ledge (author refs 04, 11).
            new Zone("pav_north", "North Peak pavilion", -10, -335, 20, 18, 160, false, 0, 0),
            new Zone("pav_west", "West Peak pavilion", -128, 35, 20, 18, 232, false, 0, 0),
            new Zone("pav_south", "South Peak pavilion", 5, 129, 22, 20, 240, false, 0, 0),
            new Zone("pav_east", "East Peak ledge pavilion", 110, -10, 18, 16, 210, false, 0, 0),
            // New pads include two blocks of eaves and three clear walking blocks beyond BOXES.
            // Authored sect_gate and arch retain their original, tighter envelopes.
            // Same-level camp/dorm and training/practice pads are deliberately nested.
            new Zone("training", "Training ground", 6, 11.5, 48, 47, 155, false, 0, 0),
            new Zone("bell", "Bell tower", -28, -4, 17, 17, 152, false, 0, 0),
            new Zone("treasury", "Treasury and household", 49, 5, 28, 22, 146, false, 0, 0),
            new Zone("mentor", "Mentor's dais", -12, 12, 6, 8, 155, false, 0, 0),
            // Practice stays on the main square, south-west of the dais and clear of the axis.
            new Zone("poles", "Plum blossom poles", -10, 27, 12, 10, 155, false, 0, 0),
            // Authored five-deep landing between the fixed ceremonial flights.
            new Zone("arch", "Ceremonial arch", 6, 40.5, 13, 5, 158, false, 0, 0),
            // Dining retains its already sufficient 32 x 18 envelope.
            new Zone("dining", "Dining hall and kitchen", -58, 66, 32, 18, 140, false, 0, 0),
            // Lower service court stays at camp height; ten cells separate it from the high hall.
            new Zone("alchemy", "Alchemist's house", -47, 46, 26, 22, 140, false, 0, 0),
            new Zone("camp", "Disciples' camp", -82, 25, 40, 64, 140, false, 0, 0),
            new Zone("dorm_2nd", "Dormitory, 2nd generation", -87, 9, 30, 30, 140, false, 0, 0),
            new Zone("dorm_3rd", "Dormitory, 3rd generation", -87, 41, 30, 32, 140, false, 0, 0),
            // Full roof clearance starts at the fixed upper flight (v=46); rear edge v=77.
            // Ancestors and the attached climb move four south to keep their intervening bank.
            new Zone("main_hall", "Main hall", 6, 61.5, 60, 31, 161, false, 0, 0),
            new Zone("ancestors", "Ancestors' hall", 6, 93, 44, 22, 172, false, 0, 0),
            new Zone("scriptures", "Hall of Scriptures", 53, 84, 26, 24, 168, false, 0, 0),
            // Elders follow the upper landing south; five clear cells remain behind their houses.
            new Zone("elders", "Elders' residences", -49, 105, 50, 26, 166, false, 0, 0),
            new Zone("grove", "Plum grove", 80, 72, 26, 20, 172, false, 0, 0),
            new Zone("vault", "Secret vault", 4, 62, 14, 12, 151, true, -26, 66),
            new Zone("penance", "Penance cave", -56, 112, 10, 10, 168, true, -40, 96));

    /** Sites marked for the author (lanterns on the corners, a sign); none are ruins now. */
    public static final java.util.Set<String> CORE = java.util.Set.of("gate", "sect_gate", "training",
            "bell", "treasury", "mentor", "arch", "dining", "alchemy", "camp", "dorm_2nd", "dorm_3rd",
            "poles", "main_hall", "ancestors", "scriptures", "elders", "grove");

    /**
     * Stairs between the pads, {u0, v0, y0, u1, v1, y1[, width]}: stepped, default width 4. Over a gap deeper than
     * three blocks narrow paths use a plank bridge (the plum grove); wide flights are solid stone.
     */
    public static final double[][] PATHS = {
        {14, -24, 156, 14, -22, 155},
        // Level door apron; the ascent to training is below, after the legacy routes.
        {-26.5, -4.5, 152, -22.5, -4.5, 152, 3},
        // Treasury stair runs along the gap, instead of dropping nine blocks in six.
        {28.5, -18.5, 155, 32.5, -18.5, 155, 3},
        {6, 35, 155, 6, 38, 158, 9},
        {6, 43, 158, 6, 46, 161, 9},
        // Rear exit -> west landing -> thirteen-long side stair -> ancestors' porch.
        // The elders branch from the upper landing; no low path crosses beneath this stair.
        {6, 72, 161, 6, 74, 161},
        {6, 74, 161, -30, 74, 161},
        {-30, 74, 161, -30, 87, 172},
        {-30, 87, 172, 6, 87, 172},
        {-18, 10, 155, -62, 12, 140},
        // Dining/camp courtyard, scriptures, elders, and lower alchemy approaches.
        {-72.5, 52.5, 140, -72.5, 58.5, 140, 3},
        {34.5, 65.5, 161, 38.5, 65.5, 161, 3},
        {-22.5, 87.5, 172, -22.5, 94.5, 166, 3},
        {-63.5, 12.5, 140, -60.5, 12.5, 140, 3},
        {61, 82, 168, 74, 75, 172},
        // Width-three paths have solid masonry support and three clear walking lanes.
        // Half-cell centres put exactly three cells across each orthogonal run.
        {-22.5, -4.5, 152, -18, -4.5, 155, 3},
        {32.5, -18.5, 155, 44.5, -18.5, 146, 3},
        {44.5, -18.5, 146, 44.5, 1.5, 146, 3},
        {-60.5, 12.5, 140, -60.5, 34.5, 140, 3},
        {-60.5, 34.5, 140, -47.5, 34.5, 140, 3},
        {-47.5, 34.5, 140, -47.5, 40.5, 140, 3},
        {38.5, 65.5, 161, 53.5, 72.5, 168, 3},
        {53.5, 72.5, 168, 53.5, 77.5, 168, 3},
        {-22.5, 94.5, 166, -38.5, 94.5, 166, 3},
        {-38.5, 94.5, 166, -64.5, 94.5, 166, 3},
        {-64.5, 94.5, 166, -64.5, 100.5, 166, 3},
        {-49.5, 94.5, 166, -49.5, 100.5, 166, 3},
        {-34.5, 94.5, 166, -34.5, 100.5, 166, 3},
        // Go around the dining hall's north end; its west door has its own level apron.
        {-72.5, 58.5, 140, -71.5, 58.5, 140, 3},
        {-71.5, 58.5, 140, -71.5, 65.5, 140, 3},
        {-71.5, 65.5, 140, -68.5, 65.5, 140, 3},
        {-71.5, 58.5, 140, -54.5, 58.5, 140, 3},
        {-54.5, 58.5, 140, -54.5, 65.5, 140, 3},
        {-54.5, 65.5, 140, -52.5, 65.5, 140, 3},
        // Courtyard spine outside both dorm verandas, joined to the existing camp stair.
        {-72.5, 10.5, 140, -72.5, 52.5, 140, 3},
        {-72.5, 10.5, 140, -62.5, 10.5, 140, 3},
    };

    /** Default clear walking width; individual PATHS rows can override it. */
    public static final int PATH_WIDTH = 4;

    /** Cell-centre test shared by generation and the regrade vegetation mask. */
    public static boolean insidePad(Zone z, double u, double v) {
        int x = (int) Math.floor(z.u() - z.width() / 2.0);
        int q = (int) Math.floor(z.v() - z.depth() / 2.0);
        return u >= x && u < x + z.width() && v >= q && v < q + z.depth();
    }

    public static boolean clearFootprint(double u, double v) {
        return ZONES.stream().anyMatch(z -> !z.cave() && !z.id().equals("grove") && insidePad(z, u, v));
    }

    public static int pathWidth(double[] path) {
        return path.length > 6 ? (int) path[6] : PATH_WIDTH;
    }

    public static boolean accessPath(double[] path) {
        return pathWidth(path) == 3;
    }

    /** The legacy 0.1 tolerance keeps four-wide diagonal paths on the same grid. */
    public static double pathHalfWidth(double[] path) {
        return pathWidth(path) / 2.0 + (accessPath(path) ? 0 : 0.1);
    }

    public static final int ANCESTORS_PATH_FIRST = 5;
    public static final int ANCESTORS_PATH_COUNT = 4;

    /**
     * Narrow ravines {u0, v0, u1, v1, floor, halfWidth}: the plum grove's ledge is parted from the
     * upper ledge by one (plan: grove on a neighbouring ledge, reached by a bridge).
     */
    public static final double[][] CLEFTS = {
        {57, 60, 61, 100, 140, 2.5},
    };

    /**
     * Forested saddles that part the sect shelf from the neighbouring spires (author 04.10):
     * {u, v, radius, floor} — a gentle wooded hollow instead of bare rock between them.
     */
    public static final double[][] SADDLES = {
        {-90, 30, 14, 136},
        {68, 22, 14, 134},
    };

    /** The one trail: gate, gorge, cleft stair, North Peak, Canglong ridge, Golden Lock, sect. */
    public static final List<TrailPoint> TRAIL = List.of(
            new TrailPoint(-104, -393, 3),
            new TrailPoint(-100, -365, 26),
            new TrailPoint(-86, -335, 52),
            new TrailPoint(-76, -313, 84),
            new TrailPoint(-64, -312, 100),
            // Thousand-foot cleft: steep stair north-east up to the North Peak shoulder.
            new TrailPoint(-50, -318, 122),
            new TrailPoint(-28, -322, 148),
            new TrailPoint(-12, -310, 158),
            new TrailPoint(0, -282, 154),
            new TrailPoint(8, -262, 156),
            new TrailPoint(-2, -215, 160),
            new TrailPoint(14, -170, 166),
            new TrailPoint(6, -125, 172),
            new TrailPoint(22, -80, 178),
            new TrailPoint(24, -45, 170),
            new TrailPoint(22, -25, 156));

    /**
     * One rock ledge of the training climb: rounded top at nominal {@code y}, half sizes {@code ru}
     * (along u) and {@code rv} (along v). {@code kind}: route step, rest (a rock seat), training
     * (wide enough for a group), or side (off the route: greenery and pines only).
     */
    public record Ledge(String id, double u, double v, double y, double ru, double rv, Kind kind) {
        public boolean onRoute() {
            return kind != Kind.SIDE;
        }
    }

    public enum Kind { STEP, REST, TRAINING, SIDE }

    /**
     * The training climb behind the sect (author2/sect-high-on-mountain.png): above the ancestors'
     * hall the South Peak's north face rises as stepped rock lobes with bushes, and disciples climb
     * and train on them towards the summit. The route zigzags across the face in three rows
     * (east → west, west → east, east → west): rises of 3-5 between neighbours, a wide ledge at
     * each turn, rests with a rock seat, and the top ledge under the summit rim. The ids
     * {@code climb_1..climb_16} are the route in climbing order (sites for sect life).
     */
    public static final List<Ledge> CLIMB = List.of(
            // Row 1, east -> west, just above the back edge of the ancestors' terrace (172).
            new Ledge("climb_1", 17, 107.5, 175, 4.5, 2.6, Kind.STEP),
            new Ledge("climb_2", 9.5, 107, 179, 5, 2.4, Kind.STEP),
            new Ledge("climb_3", 0, 107.8, 182, 4.6, 2.5, Kind.STEP),
            new Ledge("climb_4", -9.5, 107.2, 187, 5.2, 2.6, Kind.REST),
            new Ledge("climb_5", -18.5, 108, 190, 4.4, 2.6, Kind.STEP),
            // West turn: a wide ledge spanning two rows.
            new Ledge("climb_6", -26, 110.5, 194, 5.5, 4.2, Kind.TRAINING),
            // Row 2, west -> east.
            new Ledge("climb_7", -16.5, 112.8, 198, 5.4, 2.1, Kind.STEP),
            new Ledge("climb_8", -6, 112.2, 202, 5.8, 2.2, Kind.STEP),
            new Ledge("climb_9", 4.2, 112.9, 205, 5, 2.1, Kind.STEP),
            new Ledge("climb_10", 15.4, 112.1, 210, 6, 2.2, Kind.REST),
            // East turn.
            new Ledge("climb_11", 27.5, 111.5, 214, 6, 4.4, Kind.TRAINING),
            // Row 3, east -> west, under the summit rim.
            new Ledge("climb_12", 18, 116.6, 218, 4.6, 2.0, Kind.STEP),
            new Ledge("climb_13", 8, 116.2, 222, 4.8, 2.0, Kind.STEP),
            new Ledge("climb_14", -2, 116.8, 226, 4.6, 2.0, Kind.STEP),
            new Ledge("climb_15", -12, 116.3, 231, 4.8, 2.1, Kind.STEP),
            // Top: a wide rest ledge looking down on the sect; the summit rim is one step up.
            new Ledge("climb_16", -23, 117.2, 235, 5.5, 2.8, Kind.TRAINING),
            // Side lobes off the route: the face reads as a field of ledges, not one stair.
            new Ledge("side_e1", 41, 104.5, 183, 3.6, 2.2, Kind.SIDE),
            new Ledge("side_e2", 41, 119, 224, 3.8, 2.4, Kind.SIDE),
            new Ledge("side_w1", -32, 104.8, 182, 3.4, 2.0, Kind.SIDE),
            new Ledge("side_w2", -31, 116.2, 226, 3.0, 2.0, Kind.SIDE));

    /**
     * Couloirs (local u) that part the climb face into rock masses — west (turn 6, rows' west
     * ends, top ledge), centre and east (turn 11) — plus the two flanks beside the side lobes.
     * The first is the main one: it opens into a broad hollow under the summit rim.
     */
    public static final double[] CLIMB_GAPS = {-13, 12, -37, 39};

    /** Plank road along the South Peak's eastern wall (Changkong Zhandao analogue), a side branch. */
    public static final List<TrailPoint> PLANK_ROAD = List.of(
            new TrailPoint(58, 112, 212),
            new TrailPoint(60, 128, 212),
            new TrailPoint(54, 148, 212));
}
