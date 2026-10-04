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
    public static final double SUMMIT = 236.0;

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
            new Peak("south", 5, 125, 236, 80, 60, 10, 4.2),
            new Peak("east", 130, 15, 228, 66, 48, 70, 4.0),
            new Peak("west", -125, 35, 226, 74, 44, 75, 4.4),
            new Peak("middle", 68, 48, 220, 32, 24, 40, 2.6),
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
    public static final double[] VALLEY_U = {-140, -135, -135, -150, -120, -90};
    public static final double[] VALLEY_V = {-420, -470, -600, -720, -840, -980};
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
            new Gorge(new double[] {-110, -100, -82, -66},
                    new double[] {-430, -372, -330, -296},
                    new double[] {3, 22, 58, 100}, 5, 4.0));

    public static final List<Zone> ZONES = List.of(
            // Gate at the mouth of the gorge, right under the scarp wall (author ref 06: the red gate
            // stands in front of a cliff face).
            new Zone("gate", "Gate terrace", -140, -404, 40, 26, 3, false, 0, 0),
            // Inner sect gate where the trail reaches the main terrace (author refs 06, 12).
            new Zone("sect_gate", "Sect gate", 14, -24, 28, 10, 160, false, 0, 0),
            // Pavilions on summits and a cliff ledge (author refs 04, 11).
            new Zone("pav_north", "North Peak pavilion", -10, -335, 14, 12, 160, false, 0, 0),
            new Zone("pav_west", "West Peak pavilion", -125, 35, 14, 12, 222, false, 0, 0),
            new Zone("pav_south", "South Peak pavilion", 5, 125, 16, 14, 232, false, 0, 0),
            new Zone("pav_east", "East Peak ledge pavilion", 110, -10, 12, 10, 210, false, 0, 0),
            new Zone("main", "Main terrace", -20, 20, 100, 70, 160, false, 0, 0),
            new Zone("upper", "Temple and leader's residence", -30, 74, 60, 28, 168, false, 0, 0),
            new Zone("vault", "Secret vault", -32, 112, 14, 12, 169, true, -32, 86),
            new Zone("poles", "Plum blossom poles", -88, 62, 30, 26, 156, false, 0, 0),
            new Zone("grove", "Plum grove", 38, 90, 36, 26, 166, false, 0, 0),
            new Zone("penance", "Penance cave", -110, 30, 10, 10, 161, true, -78, 30));

    /** The one trail: gate, gorge, cleft stair, North Peak, Canglong ridge, Golden Lock, sect. */
    public static final List<TrailPoint> TRAIL = List.of(
            new TrailPoint(-120, -399, 3),
            new TrailPoint(-108, -390, 8),
            new TrailPoint(-100, -365, 26),
            new TrailPoint(-86, -335, 52),
            new TrailPoint(-72, -305, 92),
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
            new TrailPoint(22, -14, 160));

    /** Plank road along the South Peak's eastern wall (Changkong Zhandao analogue), a side branch. */
    public static final List<TrailPoint> PLANK_ROAD = List.of(
            new TrailPoint(58, 112, 212),
            new TrailPoint(60, 128, 212),
            new TrailPoint(54, 148, 212));
}
