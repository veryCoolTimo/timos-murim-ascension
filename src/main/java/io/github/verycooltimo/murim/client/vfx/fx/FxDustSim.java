package io.github.verycooltimo.murim.client.vfx.fx;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Block B of the effect engine, the simulation behind the cel dust (fx_dust.fsh draws it). Pure maths, free of the client, so it is
 * unit-tested and its output is dumped into the shader lab (tools/vfx-lab/java/FxDustDump.java) to be set next to the reference.
 * <p>
 * A puff is emitted with a velocity, then slows down (drag), is lifted (buoyancy), pushed about by a turbulence field of its own, swells,
 * rolls, and as it ages the shader tears pieces out of it (erosion). The state at any time is integrated from the emission in fixed steps,
 * so the same call gives the same puffs at any frame and a film can be cut anywhere. Axes: x right, y up, z towards the viewer.
 * The author's words (14.11, panels 8, 11, 15, 2): «Сейчас это обычные PNG-шки, они стоят и повторяются… Может, симуляцию сделать».
 * Sizes are in blocks; the lab and the bench draw panel 8 at about 150 px per block, panel 2 at 110, panels 11 and 15 at 150 and 170.
 */
public final class FxDustSim {
    /** The uses of the bench; COLUMN_DARK is the black spatter of panel 15 and DARK_PALE the pale dust that flows round it. */
    public enum Use { LANDING, WAKE, COLUMN, COLUMN_DARK, DARK_PALE }

    /** Puff kinds: what the shader draws. */
    public static final int CLUMP = 0, CHIP = 1, TONGUE = 2, ARC = 3, STREAK = 4, DROP = 5, BANK = 6;

    /**
     * One drawn puff. {@code velocity} is world velocity (blocks per second); {@code size} is the half-size of the quad in blocks;
     * {@code heading} is the direction the quad's long axis points in the sim plane (radians from +x, counter-clockwise), NaN = along the
     * velocity; {@code tint} multiplies the colour (a darker bank, a paler clump).
     */
    public record Puff(double x, double y, double z, double vx, double vy, double vz, double size, double seed, double erode, double roll,
                       int kind, double aspect, double taper, double alpha, int layer, double heading, double tint) {}

    /** What one emitted particle is made of; the world state is integrated from it. Fluent so that every emission line says what it sets. */
    private static final class Spec {
        double birth, x, y, z, vx, vy, vz, size = .3, grow, growTime = .5, life = 1, drag = 2, lift, swirl, gravity, spin, seed, aspect = 1,
                taper, floorY = .05, heading = Double.NaN, headRate, tint = 1;
        int kind, layer;
        Spec at(double x, double y, double z) { this.x = x; this.y = y; this.z = z; return this; }
        Spec vel(double vx, double vy, double vz) { this.vx = vx; this.vy = vy; this.vz = vz; return this; }
        /** Velocity from a heading in the sim plane (radians from +x) and a speed. */
        Spec fly(double heading, double speed, double vz) { return vel(Math.cos(heading) * speed, Math.sin(heading) * speed, vz); }
        Spec size(double s, double grow, double growTime) { this.size = s; this.grow = grow; this.growTime = growTime; return this; }
        Spec life(double l) { this.life = l; return this; }
        Spec phys(double drag, double lift, double swirl, double gravity) { this.drag = drag; this.lift = lift; this.swirl = swirl; this.gravity = gravity; return this; }
        Spec kind(int k, int layer, double aspect) { this.kind = k; this.layer = layer; this.aspect = aspect; return this; }
        Spec head(double heading, double rate) { this.heading = heading; this.headRate = rate; return this; }
        Spec born(double b) { this.birth = b; return this; }
        /** For a clump: how much of a big billowing mass it is (0 an ordinary puff, 1 a mass of many lobes lit as one body). For a tongue: how pointed its tail is. */
        Spec taper(double t) { this.taper = t; return this; }
        Spec tint(double t) { this.tint = t; return this; }
        Spec spin(double s) { this.spin = s; return this; }
        Spec floor(double f) { this.floorY = f; return this; }
        Spec seed(Random r) { this.seed = r.nextDouble(); return this; }
    }

    /** Length of the bench element «dust» (FxDustBench), here so the bench's pure selection can read it without client classes. */
    public static final double BENCH_SECONDS = 18.0;

    private static final double STEP = 1.0 / 60.0;

    private FxDustSim() {}

    /** Length of one use in seconds: the last puff has eroded. */
    public static double duration(Use use) {
        return switch (use) { case LANDING -> 3.2; case WAKE -> 2.2; case COLUMN -> 4.4; case COLUMN_DARK -> 3.0; case DARK_PALE -> 2.6; };
    }

    /** The puffs alive at {@code t} seconds after the use started (the impact, the cut, the first clot). */
    public static List<Puff> at(Use use, double t) {
        List<Puff> out = new ArrayList<>();
        for (Spec s : emit(use)) {
            double age = t - s.birth;
            if (age < 0 || age >= s.life) continue;
            out.add(state(s, age));
        }
        return out;
    }

    private static Puff state(Spec s, double age) {
        double x = s.x, y = s.y, z = s.z, vx = s.vx, vy = s.vy, vz = s.vz;
        for (double a = 0; a < age - 1e-9; a += STEP) {
            double dt = Math.min(STEP, age - a), time = a + s.birth;
            // a turbulence field of its own: slow waves along all three axes, phased by the seed; it grows with the puff's age
            double w = s.swirl * (.35 + a);
            double ax = w * Math.sin(time * 2.1 + s.seed * 31 + y * 1.7), ay = w * .6 * Math.sin(time * 1.7 + s.seed * 17 + x * 1.3);
            double az = w * Math.sin(time * 1.9 + s.seed * 23 + x * 1.1);
            vx += ax * dt; vy += (ay + s.lift - s.gravity) * dt; vz += az * dt;
            double k = Math.exp(-s.drag * dt);
            vx *= k; vy *= k; vz *= k;
            x += vx * dt; y += vy * dt; z += vz * dt;
            if (y < s.floorY) { y = s.floorY; vy = Math.max(vy, 0) * .2; vx *= .96; vz *= .96; }
        }
        double life = age / s.life;
        // swells fast at first and then slowly; thrown matter does not swell
        double size = s.size * (1 + s.grow * (1 - Math.exp(-age / Math.max(s.growTime, 1e-3))));
        // erosion starts when a third of the life is gone and reaches 1 at its end
        double erode = Math.clamp((life - .3) / .7, 0, 1);
        double roll = s.seed * 6.28 + s.spin * age;
        return new Puff(x, y, z, vx, vy, vz, size, s.seed, erode, roll, s.kind, s.aspect, s.taper, 1, s.layer, s.heading + s.headRate * age, s.tint);
    }

    private static List<Spec> emit(Use use) {
        return switch (use) {
            case LANDING -> landing(); case WAKE -> wake(); case COLUMN -> column(); case COLUMN_DARK -> spatter(); case DARK_PALE -> palePlume();
        };
    }

    /**
     * Panel 8 (about 150 px per block): a body lands. ONE mass: a dark grey bank made of torn, layered grey tongues lying along the ground (it is
     * there from the first frame, widest at the middle, with a few low round clumps in it), and out of it grow a few BIG flame-shaped white wedges
     * with jagged edges (seven or eight a side, longest at the outer ends, the middle low: a crater) with some small teeth between them. Every wedge
     * leans outward from the impact and has its grey base inside the bank. Wedges settle outward and melt back into the bank. A few black chips fly.
     */
    private static List<Spec> landing() {
        Random r = new Random(81);
        List<Spec> out = new ArrayList<>();
        // the dark mass: layered grey tongues lying along the ground, outward on both sides, overlapping; the ones in the middle are shortest
        for (int side = -1; side <= 1; side += 2) {
            for (int i = 0; i < 9; i++) {
                double u = (i + r.nextDouble() * .8) / 9, aspect = 1.7 + r.nextDouble() * 1.0, length = (.7 + .8 * u) * (.7 + .5 * r.nextDouble());
                double elev = .25 + .5 * r.nextDouble(), heading = side > 0 ? elev : Math.PI - elev;
                // the root end of the quad must stay on the ground: a tongue that leans up has its centre lifted by its own rise (no tails under the ground line)
                double yc = Math.max(.12 + r.nextDouble() * .55, Math.sin(elev) * length * .5 + length / (2 * aspect) * .9 + .03);
                out.add(new Spec().at(side * (.05 + u * .85 * (.6 + .4 * r.nextDouble())), yc, (r.nextDouble() - .5) * .3).vel(side * (.25 + r.nextDouble() * .5) * (.4 + u), .15 + r.nextDouble() * .3, 0)
                        .size(length / (2 * aspect), .35, .4).life(2.4 + r.nextDouble() * 1.0).phys(2.6, .12, .3, 0).head(heading, 0)
                        .kind(TONGUE, 0, aspect).born(r.nextDouble() * .08).floor(.0).seed(r).tint(.45 + .25 * r.nextDouble()));
            }
        }
        for (int i = 0; i < 14; i++) {
            double u = (i + r.nextDouble() * .9) / 14 * 2 - 1;
            out.add(new Spec().at(u * .55, .1 + r.nextDouble() * .25, (r.nextDouble() - .5) * .3).vel(u * (1.0 + r.nextDouble() * 1.0), .2 + r.nextDouble() * .4, 0)
                    .size(.26 + r.nextDouble() * .14, .5, .3).life(2.3 + r.nextDouble() * .9).phys(2.4, .2, .5, 0).kind(CLUMP, 0, 1.1 + r.nextDouble() * .3)
                    .born(r.nextDouble() * .1).spin((r.nextDouble() - .5) * 1.0).tint(.5 + .2 * r.nextDouble()).seed(r));
        }
        // the big wedges: few, wide at the base, jagged, out of the bank, leaning outward more the farther they stand
        for (int side = -1; side <= 1; side += 2) {
            for (int i = 0; i < 8; i++) {
                double far = .15 + .85 * (i + .3 + r.nextDouble() * .5) / 8.0;
                // every wedge has its own lean (a few stand upright or lean in, most fling outward, the outer ones the most) and its own length
                double lean = (.03 + .9 * far * (.3 + 1.0 * r.nextDouble())) * side;
                double heading = Math.PI / 2 - lean;
                double length = (.4 + 1.3 * Math.pow(far, 1.2)) * (.6 + .85 * r.nextDouble());
                double aspect = 1.25 + r.nextDouble() * .5;
                // about two wedges in five end in a hook that curls outward
                double hook = r.nextDouble() < .45 ? -side * (.35 + .35 * r.nextDouble()) : 0;
                out.add(new Spec().at(side * (.08 + far * .72) + Math.cos(heading) * length * .5, .12 + r.nextDouble() * .2 + Math.sin(heading) * length * .5, (r.nextDouble() - .5) * .2).fly(heading, .3 + far * .8 + r.nextDouble() * .3, 0)
                        .size(length / (2 * aspect), .25, .3).life(1.2 + r.nextDouble() * .9).phys(3.4, 0, 0, 0).head(heading, -side * (.05 + .12 * r.nextDouble()))
                        .kind(TONGUE, 1, aspect).taper(hook).born(r.nextDouble() * .15).seed(r));
            }
            // small teeth between the wedges
            for (int i = 0; i < 9; i++) {
                double far = (i + r.nextDouble()) / 9.0, lean = (.1 + .7 * far * (.6 + .6 * r.nextDouble())) * side, heading = Math.PI / 2 - lean;
                double length = .22 + .4 * r.nextDouble() + .2 * far, aspect = 1.4 + r.nextDouble() * .4;
                out.add(new Spec().at(side * (.15 + far * 1.0) + Math.cos(heading) * length * .5, .15 + r.nextDouble() * .2 + Math.sin(heading) * length * .5, (r.nextDouble() - .5) * .2).fly(heading, .3 + far * .7, 0)
                        .size(length / (2 * aspect), .25, .3).life(.9 + r.nextDouble() * .8).phys(3.4, 0, 0, 0).head(heading, -side * (.1 + .2 * r.nextDouble()))
                        .kind(TONGUE, 1, aspect).born(.05 + r.nextDouble() * .2).seed(r));
            }
        }
        // a few small black chips thrown on arcs
        for (int i = 0; i < 12; i++) {
            double ang = Math.PI * (.12 + .76 * r.nextDouble()), speed = 3 + r.nextDouble() * 5;
            out.add(new Spec().at((r.nextDouble() - .5) * .8, .3, 0).fly(ang, speed, 0).size(.015 + r.nextDouble() * .03, 0, 1).life(.8 + r.nextDouble() * .6)
                    .phys(.3, 0, 0, 9).kind(i % 3 == 0 ? CHIP : DROP, 2, 1 + r.nextDouble() * 1.5).born(r.nextDouble() * .15).floor(.02).seed(r));
        }
        return out;
    }

    /**
     * Panel 2 (about 110 px per block): a horizontal cut passes at chest height; under it a LOW BANK of dust lies along the ground and is swept outward.
     * The bank is built of overlapping segments (kind BANK, drawn by fx_dust.fsh): its top edge is a saw-tooth of flame teeth in three sizes that lean
     * up and outward, there are two or three grey tones in layers (the back layer darker), and only the outermost segment runs out into a thin tail.
     * The cut wipes outward, so the farther the segment the later it rises. The segments are short: the smooth length of the bank is the overlap.
     */
    private static List<Spec> wake() {
        Random r = new Random(22);
        List<Spec> out = new ArrayList<>();
        for (int side = -1; side <= 1; side += 2) {
            double heading = side > 0 ? 0 : Math.PI;
            // the back layer (darker, taller, its teeth stand over the front layer) and the front layer (paler, lower, its bottom is on the ground)
            for (int layer = 0; layer < 2; layer++) {
                int n = layer == 0 ? 4 : 5;
                for (int i = 0; i < n; i++) {
                    double along = (i + .15 + r.nextDouble() * .5) / n;
                    double half = (layer == 0 ? .62 : .5) + .2 * r.nextDouble();     // half length in blocks
                    double size = (layer == 0 ? .3 : .22) + .05 * r.nextDouble();     // half height in blocks (the quad is 2.4 times the body)
                    double aspect = half / size;
                    double bx = side * (.4 + along * 1.5);
                    out.add(new Spec().at(bx, size * .8 * 1.1, (layer == 0 ? -.1 : .1) + (r.nextDouble() - .5) * .1)
                            .vel(side * (.9 + r.nextDouble() * .9), 0, 0).size(size, .1, .3).life(.95 + r.nextDouble() * .45).phys(3.5, 0, 0, 0)
                            .head(heading, 0).kind(BANK, layer, aspect).born(along * .22 + layer * .03 + r.nextDouble() * .04).floor(0).seed(r)
                            .tint(layer == 0 ? .72 + .08 * r.nextDouble() : .94 + .06 * r.nextDouble()));
                }
            }
            // the thin tail at the outer end, lying on the ground
            double lenT = 1.5 + .3 * r.nextDouble(), size = .13;
            out.add(new Spec().at(side * (1.6 + lenT * .5), size * .8 * 1.1, .12).vel(side * 1.6, 0, 0).size(size, .1, .3).life(1.1).phys(3.5, 0, 0, 0)
                    .head(heading, 0).kind(BANK, 0, lenT / 2 / size).taper(1).born(.2).floor(0).seed(r).tint(.85));
        }
        return out;
    }

    /**
     * Panel 11 (about 150 px per block): BIG billowing masses fill the frame. Six clumps of different sizes, one very big, each a mass of many lobes lit as
     * one body. They climb from one spot, drift the same way as they rise and so fold over, swell, and as they age big chunks tear out of them
     * (back and below first). The small ones are born later and ride higher, over the big one.
     */
    private static List<Spec> column() {
        Random r = new Random(111);
        List<Spec> out = new ArrayList<>();
        // {birth, x, y, size, vx, vy, life}: the first is the very big one, the next two are big, the rest are small
        double[][] m = {{0, -.55, .5, 1.75, .22, .95, 4.4}, {.25, 1.0, .45, 1.35, .5, 1.25, 4.2}, {.5, -1.5, .5, 1.15, -.15, 1.1, 4.0},
                {.9, .55, 1.4, .85, .55, 1.5, 3.6}, {1.3, -.9, 1.9, .7, .35, 1.6, 3.3}, {.15, -.1, .35, 1.0, .1, .75, 3.8}};
        for (double[] k : m) {
            out.add(new Spec().at(k[1] + (r.nextDouble() - .5) * .15, k[2], (r.nextDouble() - .5) * .4 + (k[3] > 1.5 ? -.2 : 0)).vel(k[4], k[5], 0)
                    .size(k[3], .3, 1.4).life(k[6]).phys(1.2, .5, .5, 0).kind(CLUMP, 0, 1).taper(k[3] > 1.2 ? 1 : k[3] > .9 ? .8 : .55)
                    .born(k[0]).spin((r.nextDouble() - .5) * .3).tint(.96 + .04 * r.nextDouble()).seed(r));
        }
        return out;
    }

    /**
     * Panel 15 (about 170 px per block): the black part is SPATTER, not a cloud: a dense cloud of thrown black streaks, blots and droplets of
     * every size, flung up and to the left in a cone, dense near the source and thin far away, and a few big curved arcs. A small dark clot
     * stays at the source. The pale dust that flows round it is the separate use DARK_PALE.
     */
    private static List<Spec> spatter() {
        Random r = new Random(151);
        List<Spec> out = new ArrayList<>();
        double main = Math.toRadians(122);
        // dense near the source: speed grows with a power of a random number, the cone is wider for the small matter
        for (int i = 0; i < 300; i++) {
            double reach = Math.pow(r.nextDouble(), 1.9), ang = main + (r.nextDouble() - .5) * Math.toRadians(52);
            out.add(new Spec().at((r.nextDouble() - .5) * .3, .35 + r.nextDouble() * .3, 0).fly(ang, 2.5 + reach * 9, 0).size(.014 + Math.pow(r.nextDouble(), 1.6) * .06, 0, 1)
                    .life(.55 + r.nextDouble() * 1.0).phys(.9, 0, 0, 2.5).kind(STREAK, 2, 2 + r.nextDouble() * 3.5).born(r.nextDouble() * .5).floor(-50).seed(r));
        }
        for (int i = 0; i < 520; i++) {
            double reach = Math.pow(r.nextDouble(), 1.6), ang = main + (r.nextDouble() - .5) * Math.toRadians(150);
            out.add(new Spec().at((r.nextDouble() - .5) * .3, .35 + r.nextDouble() * .3, 0).fly(ang, 1.5 + reach * 8, 0).size(.008 + Math.pow(r.nextDouble(), 2.2) * .05, 0, 1)
                    .life(.6 + r.nextDouble() * 1.2).phys(.7, 0, 0, 3.5).kind(DROP, 2, 1 + r.nextDouble() * .6).born(r.nextDouble() * .6).floor(-50).spin(r.nextDouble()).seed(r));
        }
        // big arcs: a few swept strokes, thrown like the rest, wide and long
        for (int i = 0; i < 7; i++) {
            double ang = main + (r.nextDouble() - .5) * Math.toRadians(70);
            out.add(new Spec().at((r.nextDouble() - .5) * .3, .4, 0).fly(ang, 3.5 + r.nextDouble() * 3, 0).size(.1 + r.nextDouble() * .16, .3, .4).life(.8 + r.nextDouble() * .5)
                    .phys(1.6, 0, 0, 0).kind(ARC, 2, 1.3 + r.nextDouble() * .5).born(.05 + r.nextDouble() * .25).floor(-50).head(ang - Math.PI / 2, 0).seed(r));
        }
        // the small dark clot at the source
        for (int i = 0; i < 4; i++) {
            out.add(new Spec().at((r.nextDouble() - .5) * .4, .35, (r.nextDouble() - .5) * .3).vel(-.3 - r.nextDouble() * .5, 1.2 + r.nextDouble() * 1.4, 0).size(.2 + r.nextDouble() * .12, .5, .6)
                    .life(1.5 + r.nextDouble() * .8).phys(1.8, .4, .6, 0).kind(CLUMP, 0, 1).born(i * .1).spin(r.nextDouble() - .5).seed(r));
        }
        return out;
    }

    /** The pale dust of panel 15: long torn tongues streaming the same way as the spatter, white and rose, with a few round clumps at the root. */
    private static List<Spec> palePlume() {
        Random r = new Random(152);
        List<Spec> out = new ArrayList<>();
        double main = Math.toRadians(122);
        for (int i = 0; i < 16; i++) {
            double ang = main + (r.nextDouble() - .5) * Math.toRadians(70), len = .8 + r.nextDouble() * 1.6, aspect = 2.4 + r.nextDouble() * 1.4;
            out.add(new Spec().at((r.nextDouble() - .5) * .5, .3 + r.nextDouble() * .3, (r.nextDouble() - .5) * .3).fly(ang, 3 + r.nextDouble() * 4, 0)
                    .size(len / (2 * aspect), .3, .3).life(1.0 + r.nextDouble() * 1.0).phys(2.2, 0, .4, 0).head(ang, (r.nextDouble() - .5) * .4)
                    .kind(TONGUE, 0, aspect).born(r.nextDouble() * .5).floor(-50).seed(r));
        }
        for (int i = 0; i < 5; i++) {
            out.add(new Spec().at((r.nextDouble() - .5) * .4, .35, 0).vel(-.5 - r.nextDouble() * 1.0, .8 + r.nextDouble() * 1.2, 0).size(.3 + r.nextDouble() * .15, .4, .5)
                    .life(1.4 + r.nextDouble() * .8).phys(1.6, .3, .5, 0).kind(CLUMP, 1, 1).born(i * .08).spin(r.nextDouble() - .5).seed(r));
        }
        return out;
    }
}
