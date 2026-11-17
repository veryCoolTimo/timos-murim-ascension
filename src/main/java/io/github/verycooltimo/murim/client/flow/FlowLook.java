package io.github.verycooltimo.murim.client.flow;

import io.github.verycooltimo.murim.client.vfx.fx.FxTune;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every look number of the high pill game picture «Провести поток» in one place (director's brief 15.11, stage 2). The draw code reads these
 * names and holds no magic numbers; a live-tuning mechanism (FxTune) is wired to these names later, so every field is a plain static (not
 * final-folded into a constant expression on purpose: they are {@code static final} here and become tunable by one change of the keyword).
 * Units: blocks, seconds, degrees, 0..1 shares, alphas 0..1, colours as 0..1 floats. Pixel sizes in the comments are at 1080p, 1 block about
 * 340 px at the shot's distance.
 */
public final class FlowLook {
    private FlowLook() { }

    // ====================================================================== body: the lit silhouette (ref «absorbing pill»)
    /** Share of the model's own colour pulled to the deep tone while the game runs: start and at the end of the run. */
    static float TINT_START = .55F, TINT_END = .75F, TINT_LEG_SHARE = .5F;
    /** Value of the deep tone as a share of the pill colour (no pale wash). */
    static float DEEP_VALUE = .3F;
    /** Seconds the lamp eases in at the start. */
    static double LAMP_IN = 1.0D;
    /** Rim ribbons on the silhouette edges, camera-facing, centred on the edge (blocks, half width): the wide pill-colour one and the narrow hot one; lift towards the camera. */
    static float RIM_OUTER_HALF = .014F, RIM_INNER_HALF = .006F, RIM_LIFT = .006F;
    /** The legs take a weaker rim (their boxes would read as a wire cage). */
    /** Also light the edge between a face-on face and a grazing top face (off: it drew bars across the lap). */
    static boolean RIM_TOP_EDGES = false;
    static float RIM_LEG_SHARE = 0F;
    static float RIM_OUTER_ALPHA = .45F, RIM_INNER_ALPHA = .75F, RIM_INNER_WHITE = .72F;
    /** Rim gain at the start and at the end of the run (a multiplier of both layers). */
    static float RIM_GAIN_START = 0F, RIM_GAIN_END = 0F;
    /** The soft inner glow of torso and arms (additive, small: the body must stay a dark-toned silhouette). */
    static float INNER_START = .05F, INNER_END = .20F;
    /** Fresnel surface rim on the model (additive, the old layer): gain. */
    static float SURFACE_RIM = .35F;
    /** A face counts as looking at the camera from this cosine on (0 grazing .. 1 face-on). */
    static float RIM_FACING = .1F;
    /** The lamp dips by this share on a mistake and returns over LAMP_DIP_BACK seconds (dip comes in over LAMP_DIP_IN). */
    static double LAMP_DIP = .45D, LAMP_DIP_IN = .12D, LAMP_DIP_BACK = .7D;

    /** Halo of the pill colour behind torso and head: radius at the start and end, height of its centre above the floor, push back from the camera, alpha. */
    static double HALO_R0 = .9D, HALO_R1 = 1.4D, HALO_Y = 1.0D, HALO_BACK = .6D;
    static float HALO_ALPHA = .5F;

    // ====================================================================== intro (before the game): the pill dissolves, the flame rises, the vortex is born
    /** Seconds: the pill dissolves into the body, then the flame (aura, halo, vortex) rises; the game starts after both. Dissolve motes, essence travel time. */
    static double INTRO_LEAD = 1.0D, INTRO_DISSOLVE = 1.0D, INTRO_FLAME = 1.4D, INTRO_TRAVEL = .6D;
    static int INTRO_MOTES = 36, INTRO_RIBBONS = 6;
    /** The pill held before the chest: radius, height above the dantian, out from the body, seconds it is held whole; essence ribbon half width; the arrival flash; the low fire ring (radius, start height). */
    static double INTRO_PILL_R = .09D, INTRO_PILL_UP = .55D, INTRO_PILL_OUT = .35D, INTRO_PILL_HOLD = .5D, INTRO_RIBBON_HALF = .035D, INTRO_FLASH_R = .5D, INTRO_RING_R = .6D, INTRO_RING_H0 = .2D;
    /** Finale: the body's lift (the server's own) starts this long after the burst. */
    static double LIFT_AFTER_BURST = .5D;
    /** The belly's inner light of the intro (radius at the end, torso height it climbs to) and the soft body glow that follows it. */
    static double BELLY_R = .32D, BELLY_CLIMB = .5D;
    /** Dark flakes of the aura (refs «cultivating it», 3): count on screen, size range (blocks), rise time (s). */
    static int FLAKES = 64;
    static double FLAKE_MIN = .02D, FLAKE_MAX = .06D, FLAKE_LIFE = 6D;
    /** The board goes out when the game ends (s); the body lamp spreads over this many seconds and holds, then goes out limbs first. */
    static double BOARD_OUT = .4D, LAMP_SPREAD = .8D, LAMP_REACH = 1.7D;
    /** Finale: the burst happens in the air, this long after the end; the body's lift starts when the core has grown. */
    static double FIN_BURST_AFTER = 2.3D;
    /** The column of pill-colour light under the lifted figure (radius, alpha) and the low soft dust band of the burst (height, width, alpha). */
    static double LIFT_COLUMN_R = .5D, BAND_H = .2D, BAND_W = .45D;
    static float LIFT_COLUMN_ALPHA = .3F, BAND_ALPHA = .4F;
    /** The finale waits for the body's lift (and ends 2 s after it lands) only when the lift is this high (blocks); a low ceiling gives a hand-high lift nobody sees. */
    static float LIFT_VISIBLE = .5F;
    /** Essence of the finale: motes streaming out of the black core (count, speed range, life), and motes round the rising figure per second. */
    static int ESSENCE = 220, PETALS = 90, BEAMS = 16, STREAKS = 6;
    static double ESSENCE_SPEED_MIN = .4D, ESSENCE_SPEED_MAX = 3D, ESSENCE_LIFE = 3.6D, LIFT_MOTE_RATE = 40D, LIFT_MOTE_SIZE = .035D;

    // ====================================================================== calm absorption: common [0] and middle [1] pills (no mini-game, no board)
    /** Vortex rim radius at the start and at the end, arms, curtains (count, height, radius scale), arm width, spin, share of dark flakes / dots / embers, body lamp strength. */
    static int[] CALM_FIRE_SHEETS = {4, 6};
    static double[] CALM_FIRE_H = {1.3, 2.1}, CALM_FIRE_R = {.7, .95};
    static double[] CALM_R0 = {.8D, 1.4D}, CALM_R1 = {1.2D, 2.5D};
    static int[] CALM_ARMS = {3, 5}, CALM_CURTAINS = {2, 4};
    /** Calm only: curtain and arm strength (denser layered fire, a more visible vortex), flake size scale, and how far the camera eases back while the picture settles (blocks). */
    static double[] CALM_CURTAIN_ALPHA = {1.3D, 1.3D}, CALM_ARM_ALPHA = {1.5D, 1.25D}, CALM_FLAKE_SIZE = {.7D, .7D};
    static double CALM_CAM_EASE = .5D;
    static double[] CALM_CURTAIN_H = {1.1D, 1.8D}, CALM_CURTAIN_R = {.7D, .9D}, CALM_WIDTH = {.62D, .75D}, CALM_SPIN = {.6D, .9D};
    static double[] CALM_FLAKES = {.4D, .7D}, CALM_DOTS = {.45D, .65D}, CALM_EMBERS = {.4D, .8D}, CALM_LAMP = {1.0D, 1.5D};
    /** Seconds of the whole picture (the middle one is cut by the server's sitting), the calm settle at the end, the middle pill's small pulse. */
    static double[] CALM_RUN = {5.8D, 9.5D};
    static double CALM_SETTLE = 2.0D, CALM_PULSE_R = .9D;
    /** The camera: front, slightly above, distance and pitch (degrees) per grade. */
    static double[] CALM_CAM_DIST = {2.7D, 3.2D}, CALM_CAM_PITCH = {20D, 24D};

    // ====================================================================== the channel (meridian)
    /** Half width of the channel (block): 0.058 wide = 20 px. The white-hot core is one third of it. */
    static double CHANNEL_HALF = .0125D, GLOW_REACH = 1.2D;
    static double CORE_SHARE = 1D / 3D;
    /** Alpha of the not-yet-passed channel (dimmer than before: it competes with the lit part), its colour is the pale tone. */
    static float PALE_ALPHA = .28F, PALE_DEEP = .6F;
    /** Alpha of the lit body, base and heat part; the soft surface glow beside it (alpha at the glow's middle, 3.2 halves wide). */
    static float LIT_ALPHA = .60F, LIT_HEAT_ALPHA = .38F;
    static float GLOW_BASE = .13F, GLOW_HEAT = .22F, GLOW_SIDE = .10F, GLOW_PRESSURE = .08F;
    /** Rings: half width of the ring line (block), ring cap on an arm (diameter fits the arm), radius of the ripple cap. */
    static double RING_THICK = .007D, RING_ARM_MAX = .055D, RING_RIPPLE_ARM_MAX = .065D, CLOT_R = .035D;
    static float RING_ALPHA = .95F;
    /** Hot orange-red of a shut node. */
    static float[] KNOT = {1F, .36F, .16F};
    /** A passed node dims to this share while the game runs. */
    static float NODE_PASSED_DIM = .55F;

    // ====================================================================== camera (brief C, 28 % rule wins over the distances of the first draft)
    /** Distance (blocks) of the front shot: start, end of the run, the finale pull-back, and the pitch (degrees) of the run and of the pull-back. */
    static float CAM_START = 3.3F, CAM_END = 3.0F, CAM_PULL = 4.7F, CAM_PITCH_RUN = 26F, CAM_PITCH_PULL = 6F;
    /** Seconds of the finale contraction in which the camera pulls back, and of the burst nudge (damped; blocks of focus height). */
    static double CAM_PULL_TIME = 1.8D, CAM_FOCUS_LIFT = -.32D, CAM_NUDGE = .05D, CAM_NUDGE_TIME = .6D;
    /** Ticks of the smooth return to the normal view. */
    /** Field of view multiplier of the game's shot (not below 0.8 of the base FOV). */
    public static float CAM_FOV = .8F;
    /** How much of the body's lift the camera follows (the rest shows as a rise in the frame). */
    public static float CAM_LIFT_SHARE = .35F;
    public static int CAM_RETURN_TICKS = 26;
    /** Camera distance (blocks) below which the vortex and sheets fade out (they pass the lens). */
    static double NEAR_FADE_FROM = .7D, NEAR_FADE_TO = 1.8D;

    // ====================================================================== vortex (ref «absorbing2»)
    /** Rim radius of the vortex from the start to the end of the run, inner radius, cone: base height at the inner radius and rise per block of radius. */
    static double VORTEX_R0 = 1.0D, VORTEX_R1 = 4.5D, VORTEX_RIN = .3D, VORTEX_Y0 = .035D, VORTEX_RISE = .15D;
    /** Number of arms: at the start, with every right fork one more, the most. */
    static int ARMS_START = 6, ARMS_MAX = 7;
    /** Arm rim width range (blocks, 0.4 .. 0.9), the tip's share of the length, the core end's width share. */
    static double ARM_WIDTH_MIN = .6D, ARM_WIDTH_MAX = 1.0D, ARM_CORE_WIDTH = .14D;
    /** Rotation, degrees per second, at the start and at the end of the run. */
    static double SPIN_START = 8D, SPIN_END = 20D;
    /** Alpha of an arm at the start of the run and at the end; the glare of the one crisp edge. */
    static float ARM_ALPHA_START = .5F, ARM_ALPHA_END = .7F, ARM_EDGE_ALPHA = .95F;
    /** Texture flow along the arm toward the centre: bands per arm length, speed in blocks per second, depth of the bands. */
    static double FLOW_WAVELENGTH = 1.3D, FLOW_SPEED = .7D, FLOW_DEPTH = .5D;
    /** The floor fade of the arms: fully faded at FLOOR_FADE_FROM above the floor, full at FLOOR_FADE_TO. */
    static double FLOOR_FADE_FROM = .0D, FLOOR_FADE_TO = .03D, ARM_BODY_FADE = .5D, CORE_R0 = .5D, CORE_R1 = .9D, CORE_ALPHA = .8F, WASH_SHARE = .85D, WASH_ALPHA = .2F;
    /** Samples along an arm per quality tier, cross columns. */
    static int[] ARM_SAMPLES = {56, 44, 34, 26};
    /** Dots riding the arms inward: count per tier, radius of the three classes (blocks), speed share of the arm per second. */
    static int[] DOT_COUNT = {260, 190, 120, 70};
    static double[] DOT_SIZE = {.02D, .04D, .07D};
    static double DOT_SPEED_MIN = .07D, DOT_SPEED_MAX = .15D;
    static float DOT_ALPHA = .85F;

    // ====================================================================== cloth aura (ref «cultivating it»)
    /** Number of big sheets: at the start and at the end (more with progress); height range (blocks) with progress and pressure. */
    static int SHEETS_START = 2, SHEETS_MAX = 4, LOW_SHEETS = 2;
    static double SHEET_H0 = 1.6D, SHEET_H1 = 2.8D, SHEET_R0_MIN = 1.1D, SHEET_R0_MAX = 1.7D, SHEET_FLARE = .1D, SHEET_BULGE = .14D;
    /** Sheets fade out when their middle is nearer to the lens than the second number (blocks) and are gone at the first. */
    static double SHEET_NEAR_FROM = 1.2D, SHEET_NEAR_TO = 1.8D;
    /** Sheet alpha, growth time (s), drift in degrees per second (with the vortex wind), the segments of a strip. */
    static float SHEET_ALPHA = 1.0F, SHEET_BASE_FADE = .3F;
    static double SHEET_LOW_H = .6D;
    static double SHEET_GROW = 1.8D, SHEET_DRIFT = 5.5D;
    static int SHEET_SEGMENTS = 10;
    /** Embers: motes born per second at full growth and the sparks (chips). */
    static double EMBER_RATE = 40D, EMBER_SPARKS = 8D;

    // ====================================================================== light
    /** Floor disc radius from the start to the end (the engine's ground light), intensity; a light on the clot and on a bright arm. */
    static float FLOOR_R0 = 1.0F, FLOOR_R1 = 3.0F, FLOOR_STRENGTH = .34F, CLOT_LIGHT_R = .8F, CLOT_LIGHT_I = .16F, FLOOR_LIGHT_LIFT = .06F, FIN_LIGHT_GAIN = .5F, ARM_LIGHT_R = 1.4F, ARM_LIGHT_I = .3F;

    // ====================================================================== events
    /** Node passed: the ripple runs along an arm outward in this time; the arm brightens for ARM_BRIGHTEN; the ripple's width in arm length. */
    static double NODE_RIPPLE = 1.3D, ARM_BRIGHTEN = .6D, RIPPLE_SIGMA = .16D, RIPPLE_GAIN = .55D;
    /** Dust ring of a node: start and end radius, puffs, life. */
    static double NODE_DUST_R0 = 1.0D, NODE_DUST_R1 = 2.1D, NODE_DUST_LIFE = 1.4D, NODE_DUST_SIZE = .27D;
    static int NODE_DUST_PUFFS = 11;
    /** Mistake: the vortex stalls (rotation down to this share) and shudders once: depth of the radius shudder, damping, Hz (< 2). */
    static double STALL_SHARE = .1D, STALL_TIME = 1.0D, SHUDDER_DEPTH = .05D, SHUDDER_DAMP = 3.6D, SHUDDER_HZ = 1.6D;
    /** A torn sheet: seconds to close, chips thrown. */
    static double TEAR_TIME = .7D;
    static int TEAR_CHIPS = 26;
    /** Right fork: the flare of the arm, seconds. */
    static double FORK_FLARE = 1.3D, ARM_GROW = 1.2D;

    // ====================================================================== burst (pressure 100)
    /** The black ball: seconds, radius (blocks) at its end, corona radius share. */
    static double BURST_BALL = .4D, BALL_R = .13D, CORONA_R = 2.6D;
    /** The black core: corona ring radius and thickness, hard shards, the floor disc under it, blocks. */
    static double CORE_RING_R = .22D, CORE_RING_W = .014D, CORE_FLOOR_R = 1.2D;
    static float CORE_FLOOR_ALPHA = .8F;
    /** After the ball: the arms and sheets are thrown outward over BURST_THROW seconds to this radius multiplier, ash settles for BURST_ASH more. */
    static double BURST_THROW = 2.5D, BURST_ASH = 1.5D, BURST_RADIUS_GAIN = 2.2D;
    static int BURST_CHIPS = 90, ASH_FLAKES = 70;
    /** Where the effect ends after the game's end: clean finale, burst, other ends (seconds). */
    static double END_DONE = 7.8D, END_BURST = 4.8D, END_OTHER = 1.8D;

    // ====================================================================== finale (clean)
    /** Contraction (arms wind in), the black ball, the burst moment, the settle after it (seconds). */
    static double FIN_CONTRACT = 1.2D, FIN_BALL = .8D, FIN_SETTLE = 2.2D;
    /** Radial burst: rays, length range, width range (blocks), outward speed, life, the pale brightening (alpha, seconds). */
    static int RAYS = 220;
    static double RAY_START = .35D, BRIGHT_R = 9D, DUST_ALPHA = .3D;
    static double RAY_LEN_MIN = 3D, RAY_LEN_MAX = 9D, RAY_W_MIN = .3D, RAY_W_MAX = .9D, RAY_SPEED = 14D, RAY_GROW = .25D, RAY_HOLD = .3D, RAY_FADE = .6D, DISC_R = 1.5D, DISC_TIME = .9D;
    static float RAY_ALPHA = .8F, BRIGHT_ALPHA = .58F;
    static double BRIGHT_TIME = 1.2D, BRIGHT_UP = .12D;
    /** Dust ring of the finale: radius at the end, puffs, life. */
    static double FIN_DUST_R = 5D, FIN_DUST_LIFE = 1.8D;
    static int FIN_DUST_PUFFS = 30, FIN_MOTES = 90;
    /** The lit channels fade from the hands inward: starts at the burst, lasts. */
    static double FADE_HANDS = 2.2D;

    // ====================================================================== live tuning (dev only)
    /** Fields whose size or identity is baked into arrays elsewhere: not live. */
    private static final java.util.Set<String> NOT_LIVE = java.util.Set.of("ARMS_MAX", "SHEETS_MAX", "LOW_SHEETS");
    private static Map<Field, Object> defaults;

    /** Key of a field: FUN_NAME_X -> flow.fun.name.x. */
    static String key(String field) { return "flow." + field.toLowerCase(java.util.Locale.ROOT).replace('_', '.'); }

    /**
     * Once a frame while tuning is on ({@link FxTune#ON}): every number in this class is read through {@code FxTune} under {@code flow.<name>};
     * the default is the value in code, so a key that is not overridden keeps the code's number to the last bit. Not called (and not compiled
     * into any cost) in normal play.
     */
    public static void refresh() {
        if (!FxTune.ON) return;
        try {
            if (defaults == null) {
                defaults = new LinkedHashMap<>();
                for (Field f : FlowLook.class.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) || f.isSynthetic() || NOT_LIVE.contains(f.getName())) continue;
                    Class<?> t = f.getType();
                    if (t == float.class || t == double.class || t == int.class || t == boolean.class || t == float[].class || t == double[].class || t == int[].class) {
                        f.setAccessible(true);
                        defaults.put(f, f.get(null));
                    }
                }
            }
            for (Map.Entry<Field, Object> e : defaults.entrySet()) {
                Field f = e.getKey();
                Object d = e.getValue();
                String k = key(f.getName());
                Class<?> t = f.getType();
                if (t == float.class) f.setFloat(null, FxTune.f(k, (Float) d));
                else if (t == double.class) f.setDouble(null, FxTune.d(k, (Double) d));
                else if (t == int.class) f.setInt(null, FxTune.i(k, (Integer) d));
                else if (t == boolean.class) f.setBoolean(null, FxTune.b(k, (Boolean) d));
                else if (t == float[].class) { float[] got = FxTune.fa(k, ((float[]) d).clone()), cur = (float[]) f.get(null); System.arraycopy(got, 0, cur, 0, Math.min(got.length, cur.length)); }
                else if (t == double[].class) {
                    double[] def = (double[]) d;
                    float[] fd = new float[def.length];
                    for (int i = 0; i < def.length; i++) fd[i] = (float) def[i];
                    float[] got = FxTune.fa(k, fd);
                    double[] cur = (double[]) f.get(null);
                    for (int i = 0; i < def.length && i < got.length; i++) cur[i] = got[i] == fd[i] ? def[i] : got[i];
                } else if (t == int[].class) {
                    int[] def = (int[]) d;
                    float[] fd = new float[def.length];
                    for (int i = 0; i < def.length; i++) fd[i] = def[i];
                    float[] got = FxTune.fa(k, fd);
                    int[] cur = (int[]) f.get(null);
                    for (int i = 0; i < def.length && i < got.length; i++) cur[i] = got[i] == fd[i] ? def[i] : Math.round(got[i]);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            io.github.verycooltimo.murim.MurimMod.LOGGER.warn("FlowLook.refresh: {}", ex.toString());
        }
    }
}
