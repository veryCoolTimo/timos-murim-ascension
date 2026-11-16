package io.github.verycooltimo.murim.client.vfx.fx;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Motes and sparks of the cultivation aura (round 4, author 14.11: «добавить партиклов»), a small CPU simulation in the body frame (x right,
 * y up, z towards the camera, blocks): every particle is born on or just outside the flaring shell the ink is wrapped on (FxShellWrap), at any
 * azimuth and mostly low, and then moves by its own velocity: it is pulled towards a swirl round the body's axis (most turn one way, a few
 * the other), drifts outward and up along the wrap (faster higher up), feels drag and a slow curl of acceleration so that no two paths are alike,
 * and fades in and out. Motes are soft round lights of different sizes, a few are bright with a pale core; sparks are short bright streaks that
 * fly faster and live under a second. Colours come from the panel 13 plates: violet, blue and pink accents. Nothing is attached to a bone:
 * every particle is free from the moment it is born. The share born per second follows the effect's growth and the quality governor
 * ({@link FxQuality}): old ones die of age, so a lower quality never makes one vanish. Pure and client-free (FxCultivationMotesTest, tools/vfx-lab).
 */
public final class FxCultivationMotes {
    public static final int MAX = 360;
    /** Violet, blue and pink accents, counted from plates/13-violet-layer.png and the panel's flame colours (0xRRGGBB), and their weights. */
    public static final int[] PALETTE = {0x9A5CF4, 0x7A3FD6, 0x5C46E0, 0x4A66FF, 0xE070D0, 0xF4A0E8};
    static final double[] WEIGHT = {.28, .24, .14, .14, .12, .08};
    /** Motes per second at full growth and full quality; sparks per second. */
    public static final double MOTE_RATE = 60, SPARK_RATE = 24;

    /** One particle. {@code spark} ones are drawn as a streak along the velocity. */
    public static final class Mote {
        public double x, y, z, vx, vy, vz, age, life, size, spin;
        public int rgb;
        public float bright;
        public boolean spark;
        /** Brightness over the life: in over a quarter second, out over the last third, a flicker for the bright ones. */
        public double envelope() {
            double in = Math.min(1, age / .25), out = Math.min(1, (life - age) / (life * .35));
            double f = bright > .6F ? .8 + .2 * Math.sin(age * 17 + spin) : 1;
            return Math.max(0, Math.min(in, out)) * f;
        }
    }

    public final List<Mote> motes = new ArrayList<>();
    private final Random rng;
    private double carryMote, carrySpark, clock;

    /** The colours of this run: the aura's violet by default; the cult1 scene gives its own cold ones (and the tints of its sparks). */
    private final int[] palette;
    private final double[] weights;
    private final int[] sparkTints;
    /** Motes born per second and spark share, scaled for the scene. */
    private final double moteRate, sparkRate;

    /** Scale of the soft motes' size and a steady sideways wind (blocks/s along +x); 1 and 0 keep the aura's look. */
    public double sizeScale = 1, wind = 0;

    public FxCultivationMotes(long seed) { this(seed, PALETTE, WEIGHT, new int[]{0xF4A0E8, 0xB8A0FF}, MOTE_RATE, SPARK_RATE); }

    public FxCultivationMotes(long seed, int[] palette, double[] weights, int[] sparkTints, double moteRate, double sparkRate) {
        rng = new Random(seed);
        this.palette = palette; this.weights = weights; this.sparkTints = sparkTints; this.moteRate = moteRate; this.sparkRate = sparkRate;
    }

    private int pick() {
        double r = rng.nextDouble(), a = 0;
        for (int i = 0; i < palette.length; i++) { a += weights[i]; if (r < a) return palette[i]; }
        return palette[0];
    }

    /** The shell's radius at a height (blocks), as the ink's inner shell. */
    static double shellRadius(double y) { return FxShellWrap.R0 + FxShellWrap.K * y; }

    /**
     * Advances the simulation by {@code dt} seconds (call with small steps; larger ones are split). {@code growth} 0..1 is the effect's
     * strength now (FxCultivationScene#glow), {@code quality} the governor's share of particles (1 .. 0.2).
     */
    public void step(double dt, double growth, double quality) {
        double left = Math.min(dt, .25);
        while (left > 1e-9) {
            double h = Math.min(left, 1 / 60.0);
            stepOnce(h, growth, quality);
            left -= h;
        }
    }

    private void stepOnce(double dt, double growth, double quality) {
        clock += dt;
        carryMote += moteRate * growth * quality * dt;
        carrySpark += sparkRate * growth * quality * dt;
        while (carryMote >= 1 && motes.size() < MAX) { carryMote -= 1; motes.add(born(false)); }
        while (carrySpark >= 1 && motes.size() < MAX) { carrySpark -= 1; motes.add(born(true)); }
        carryMote = Math.min(carryMote, 2); carrySpark = Math.min(carrySpark, 2);
        for (int i = motes.size() - 1; i >= 0; i--) {
            Mote m = motes.get(i);
            m.age += dt;
            double r = Math.hypot(m.x, m.z);
            if (m.age >= m.life || m.y > 5.4 || r > 3.4) { motes.set(i, motes.get(motes.size() - 1)); motes.remove(motes.size() - 1); continue; }
            // target velocity: tangent swirl (slower far out), outward and up along the wrap (faster higher up)
            double tx = -m.z / Math.max(r, .2), tz = m.x / Math.max(r, .2), ox = m.x / Math.max(r, .2), oz = m.z / Math.max(r, .2);
            double swirl = m.spin * (1.3 / (.6 + r)) * (m.spark ? 1.6 : 1), out = (.12 + .1 * m.y) * (m.spark ? 2.2 : 1), up = (m.spark ? 1.9 : .55) + .25 * m.y;
            double tvx = tx * swirl + ox * out, tvy = up, tvz = tz * swirl + oz * out;
            double drag = m.spark ? .9 : 1.7;
            m.vx += (tvx - m.vx) * drag * dt; m.vy += (tvy - m.vy) * drag * dt; m.vz += (tvz - m.vz) * drag * dt;
            // a slow curl of acceleration, its own phase per particle: paths differ, nothing repeats
            double ph = m.age * 2.1 + m.spin * 5.3 + m.size * 40;
            m.vx += Math.sin(ph + m.y * 1.9) * .55 * dt; m.vy += Math.sin(ph * .7 + m.x * 2.3) * .35 * dt; m.vz += Math.cos(ph * .9 + m.y * 1.7) * .55 * dt;
            m.x += m.vx * dt; m.y += m.vy * dt; m.z += m.vz * dt;
        }
    }

    private Mote born(boolean spark) {
        Mote m = new Mote();
        m.spark = spark;
        double az = rng.nextDouble() * Math.PI * 2;
        double y = rng.nextDouble() < .7 ? rng.nextDouble() * 1.6 : Math.min(3.4, -Math.log(1 - rng.nextDouble() * .93) * 1.0 + .3);
        double r = shellRadius(y) + (rng.nextDouble() - .35) * .55;
        m.x = Math.sin(az) * r; m.z = Math.cos(az) * r; m.y = .05 + y;
        m.spin = rng.nextDouble() < .75 ? 1 : -1;
        m.spin *= .6 + .8 * rng.nextDouble();
        m.rgb = pick();
        double k = rng.nextDouble();
        if (spark) {
            m.size = .016 + .02 * rng.nextDouble();
            m.life = .45 + .7 * rng.nextDouble();
            m.bright = .75F + .25F * rng.nextFloat();
            if (rng.nextDouble() < .5) m.rgb = sparkTints[rng.nextBoolean() ? 0 : sparkTints.length - 1];
        } else {
            m.size = (.022 + .05 * k * k) * sizeScale;         // many small, a few big
            m.life = 1.6 + 2.6 * rng.nextDouble();
            m.bright = rng.nextDouble() < .2 ? .65F + .35F * rng.nextFloat() : .3F + .3F * rng.nextFloat();
        }
        m.vx = (rng.nextDouble() - .5) * .3 + wind * (.6 + .8 * rng.nextDouble()); m.vy = .2 + .3 * rng.nextDouble(); m.vz = (rng.nextDouble() - .5) * .3;
        return m;
    }

    /** A deterministic run from nothing to bench second {@code t} (for the lab pictures): the same step the game takes, at 1/30 s. */
    public static FxCultivationMotes at(double t, long seed) {
        FxCultivationMotes s = new FxCultivationMotes(seed);
        for (double x = FxCultivationScene.START; x < t; x += 1 / 30.0) s.step(1 / 30.0, FxCultivationScene.glow(x), 1);
        return s;
    }
}
