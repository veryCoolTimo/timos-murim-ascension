package io.github.verycooltimo.murim.client.vfx.fx;

import io.github.verycooltimo.murim.client.vfx.bt.BtCtx;
import io.github.verycooltimo.murim.client.vfx.bt.BtGlow;
import io.github.verycooltimo.murim.client.vfx.bt.BtMath;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Bench element «stroke»: the brush stroke alone, next to the seated body for scale, to be filmed beside the reference crops
 * (docs/design/reference/vfx-author-1113/atlas). Three beats: gold arcs that wipe in and out (A1), one steel slash with black
 * streaks and a glow (A4), ink that flows out of the body (A6).
 * /murim dev vfx bench stroke; tools/capture/bench_film.sh stroke.
 */
public final class FxStrokeBench {
    private FxStrokeBench() {}

    public static void render(BtCtx c) {
        if (!FxStroke.available()) return;
        double t = c.labSeconds();
        var body = c.body();
        Frame f = new Frame(new Vec3(body.axis().x, body.ground(), body.axis().z), body.right(), body.near());
        BtGlow.prepare(c.mc(), 1F);
        // the first second is empty: the body is still sitting down, and a wipe that starts under it is never seen (film r11)
        if (t < 4) gold(c, f, t - 1);
        else if (t < 6.5) steel(c, f, t - 4);
        else ink(c, f, t - 6.5);
    }

    /**
     * A1: a dominant arc, a medium and a small one. A fight: each wipes in along its length in a few ticks, holds, and wipes out the
     * same way, tail first, while the noise tears what is left (the author, 14.11: in like a wipe, out faster).
     */
    private static void gold(BtCtx c, Frame f, double t) {
        var arc = FxStrokeMath.bezier(List.of(f.at(-3.4, .35, .9), f.at(-1.2, .55, 1.0), f.at(1.0, 1.5, .9), f.at(3.2, 3.3, .6)), 56);
        var medium = FxStrokeMath.bezier(List.of(f.at(-2.6, .15, 1.1), f.at(-.6, .3, 1.2), f.at(1.4, .95, 1.1)), 30);
        var small = FxStrokeMath.bezier(List.of(f.at(.6, 1.2, 1.0), f.at(.9, .7, 1.0), f.at(1.5, .1, 1.0)), 20);
        FxStroke.draw(FxLook.GOLD, List.of(wipe(arc, .17, .31, t, .3, .5, 1.6, .7), wipe(medium, .085, .12, t, .42, .4, 1.5, .6),
                wipe(small, .05, .77, t, .6, .3, 1.4, .5)), c.camera(), c.pose(), c.buffers(), t);
    }

    /**
     * A4: one slash, a little thicker, with a bright knot where it lands and a glow; thin black streaks run out of it along the cut
     * (the author, 14.11: «это всё ещё должен быть просто слэш… из него чёрные полоски исходят. Есть глоу»).
     */
    private static void steel(BtCtx c, Frame f, double t) {
        var cut = FxStrokeMath.bezier(List.of(f.at(-4.2, .98, .7), f.at(0, 1.08, 1.0), f.at(4.2, 1.0, .7)), 60);
        // numbers from the reference plate (tools/vfx-lab/scenes/a4-slash-on-plate.json): 111 px of that panel are one block
        var halo = wipe(cut, .62, .2, t, .2, .14, 1.1, .6); // wide and faint: the light of the slash is soft in the reference
        FxStroke.draw(FxLook.SLASH_HALO, List.of(new FxStroke.Stroke(halo.path(), halo.half(), halo.seed(), halo.from(), halo.drawn(),
                halo.erode(), .34F)), c.camera(), c.pose(), c.buffers(), t);
        FxStroke.draw(FxLook.STEEL, List.of(wipe(cut, .095, .52, t, .2, .14, 1.1, .6)), c.camera(), c.pose(), c.buffers(), t);
        // Round the knot the slash is a spindle of fine lines, dark and white, that leave it along the cut and spread a little
        // (the text-free reference close up; the author, 14.11: some thicker, some thinner, some longer, some shorter).
        Random random = new Random(33);
        List<FxStroke.Stroke> dark = new ArrayList<>(), white = new ArrayList<>();
        fan(dark, f, t, random, 16, .009, .015, .54, 2.25, .23, 1.35);
        fan(dark, f, t, random, 5, .018, .027, .36, 1.17, .2, .8);
        fan(dark, f, t, random, 2, .029, .038, .32, .72, .15, .55);
        fan(white, f, t, random, 9, .009, .018, .63, 2.5, .27, 1.25);
        FxStroke.draw(FxLook.SLASH_INK, dark, c.camera(), c.pose(), c.buffers(), t);
        // small slivers along both edges of the band, most of them close to the figure (the author, 14.11: «не хватает маленьких
        // ещё по середине»); in the reference they lie on the edges of the light, the middle of the band stays clean white
        List<FxStroke.Stroke> slivers = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            double side = random.nextBoolean() ? 1 : -1, edge = random.nextBoolean() ? 1 : -1;
            double offset = edge * (.072 + random.nextDouble() * .081), x0 = side * random.nextDouble() * (i < 20 ? 1.08 : 2.07);
            double reach = .16 + random.nextDouble() * (i < 20 ? .5 : .72), bow = edge * (.009 + random.nextDouble() * .027);
            var line = FxStrokeMath.bezier(List.of(f.at(x0, 1.07 + offset, 1.02), f.at(x0 + side * reach * .5, 1.07 + offset + bow, 1.0),
                    f.at(x0 + side * reach, 1.07 + offset + edge * random.nextDouble() * .045, .97)), 10);
            slivers.add(wipe(line, .008 + random.nextDouble() * .008, random.nextDouble(), t, .22 + random.nextDouble() * .14,
                    .08 + random.nextDouble() * .08, 1.0 + random.nextDouble() * .12, .5));
        }
        FxStroke.draw(FxLook.SLASH_SLIVER, slivers, c.camera(), c.pose(), c.buffers(), t);
        FxStroke.draw(FxLook.STEEL_LINE, white, c.camera(), c.pose(), c.buffers(), t);
    }

    /** Lines that leave the knot of the slash along the cut: {@code n} of them, half-width and length in the given ranges (blocks). */
    private static void fan(List<FxStroke.Stroke> out, Frame f, double t, Random random, int n, double halfMin, double halfMax,
                            double lengthMin, double lengthMax, double spread, double near) {
        for (int i = 0; i < n; i++) {
            double side = random.nextBoolean() ? 1 : -1, offset = (random.nextDouble() - .5) * spread;
            double x0 = side * (.04 + random.nextDouble() * near), reach = lengthMin + random.nextDouble() * (lengthMax - lengthMin);
            double end = offset * (1 + random.nextDouble() * 1.3) + (random.nextDouble() - .5) * .054;
            var line = FxStrokeMath.bezier(List.of(f.at(x0, 1.07 + offset * .6, 1.02), f.at(x0 + side * reach * .5, 1.07 + (offset * .6 + end) / 2, .97),
                    f.at(x0 + side * reach, 1.07 + end, .9)), 14);
            out.add(wipe(line, halfMin + random.nextDouble() * (halfMax - halfMin), random.nextDouble(), t,
                    .22 + random.nextDouble() * .16, .1 + random.nextDouble() * .1, 1.0 + random.nextDouble() * .12, .5));
        }
    }

    /** A stroke that wipes in from {@code start} over {@code in} seconds, and from {@code out} wipes away tail first while it tears. */
    private static FxStroke.Stroke wipe(List<Vec3> path, double half, double seed, double t, double start, double in, double out, double gone) {
        double head = BtMath.smooth(start, start + in, t) * 1.08, leave = linear(out, out + gone, t);
        return new FxStroke.Stroke(path, half, seed, leave * leave * .85, head, leave, 1F);
    }

    /**
     * Reference 13: cultivation. Energy flows out of the seated body the whole time. Far behind stand big blue shapes; behind the
     * body and also in front of it rise violet-blue licks with a pink middle; a black clot sits at the root, and ink comes out of
     * it and out of the floor round it: thin angular lines, lines that swell from thin to thick, some that curl at the tip. Clots
     * run out along everything and a slow turbulence bends it; a violet light lies on the ground under it. Every root holds two
     * lines half a life apart, so the stream renews itself and nothing vanishes at once.
     */
    private static void ink(BtCtx c, Frame f, double t) {
        // the light first: a soft violet band on the ground and one standing behind the body
        double lit = BtMath.smooth(0, 1.2, t);
        var ground = FxStrokeMath.bezier(List.of(f.at(-2.6, .06, .2), f.at(0, .06, .5), f.at(2.6, .06, .2)), 12);
        var rise = FxStrokeMath.bezier(List.of(f.at(0, .1, -.7), f.at(.1, 1.4, -.7), f.at(-.1, 2.9, -.7)), 12);
        FxStroke.draw(FxLook.ESSENCE_HALO, List.of(new FxStroke.Stroke(ground, .7, .4, 1, 0, (float) (.3 * lit)),
                new FxStroke.Stroke(rise, 1.1, .7, 1, 0, (float) (.22 * lit))), c.camera(), c.pose(), c.buffers(), t);
        List<FxStroke.Stroke> blue = new ArrayList<>(), back = new ArrayList<>(), mass = new ArrayList<>(), thin = new ArrayList<>(),
                swell = new ArrayList<>(), front = new ArrayList<>();
        stream(blue, f, t, 101, 4, 5.5, 2.2, 3.6, .3, .45, -1.7, -1.5, 1.3, 2, .9, 0, false);
        stream(back, f, t, 202, 5, 4.6, 1.2, 2.3, .14, .24, -.6, -.4, .6, 2, .8, 0, false);
        stream(mass, f, t, 303, 6, 5.0, .45, .95, .24, .4, -.25, .1, .5, 2, .5, 0, false);
        stream(thin, f, t, 404, 12, 4.6, 1.8, 4.2, .045, .1, -.45, .45, .5, 4, .6, .3, false);
        stream(thin, f, t, 505, 7, 5.2, 1.2, 3.0, .04, .08, -.6, .6, 2.6, 3, .6, .3, true);
        stream(swell, f, t, 606, 7, 5.0, 1.6, 3.4, .12, .2, -.4, .4, .5, 3, .55, .35, false);
        stream(swell, f, t, 707, 4, 5.4, 1.2, 2.4, .1, .17, -.5, .5, 2.4, 3, .55, .35, true);
        stream(front, f, t, 808, 3, 4.8, .6, 1.2, .07, .12, .5, .7, 1.4, 2, .8, 0, true);
        FxStroke.draw(FxLook.FLAME_BLUE, blue, c.camera(), c.pose(), c.buffers(), t);
        FxStroke.draw(FxLook.FLAME, back, c.camera(), c.pose(), c.buffers(), t);
        FxStroke.draw(FxLook.INK_MASS, mass, c.camera(), c.pose(), c.buffers(), t);
        FxStroke.draw(FxLook.INK, thin, c.camera(), c.pose(), c.buffers(), t);
        FxStroke.draw(FxLook.INK_SWELL, swell, c.camera(), c.pose(), c.buffers(), t);
        FxStroke.draw(FxLook.FLAME, front, c.camera(), c.pose(), c.buffers(), t);
    }

    /**
     * One family of flowing angular lines. {@code spreadX} is how far from the body the roots stand ({@code floor}: they stand on
     * the floor round it, not under it), {@code kink} the size of the corners, {@code curl} the share of lines whose tip hooks back.
     */
    private static void stream(List<FxStroke.Stroke> out, Frame f, double t, long family, int count, double life, double lengthMin,
                               double lengthMax, double halfMin, double halfMax, double depthMin, double depthMax, double spreadX,
                               int runs, double kink, double curl, boolean floor) {
        for (int i = 0; i < count; i++) {
            Random base = new Random(family * 131 + 23L * i + 7);
            double period = life + base.nextDouble() * 2.2, offset = base.nextDouble() * period;
            double rootAngle = Math.PI * (.1 + .8 * base.nextDouble()), sign = base.nextBoolean() ? 1 : -1;
            double x0 = floor ? sign * (.7 + base.nextDouble() * (spreadX - .7)) : (base.nextDouble() - .5) * 2 * spreadX;
            double depth = depthMin + base.nextDouble() * (depthMax - depthMin);
            // roots on the floor away from the body lean outwards and up, not across it
            if (floor) rootAngle = sign > 0 ? Math.PI * (.12 + .33 * base.nextDouble()) : Math.PI * (.55 + .33 * base.nextDouble());
            for (int twin = 0; twin < 2; twin++) {
                double age = (t + offset + twin * period * .5) % period;
                if (age > t) continue; // the beat has only just begun: nothing is older than the beat
                Random random = new Random(family * 977 + 53L * i + 31 * twin + 5);
                double angle = rootAngle + (random.nextDouble() - .5) * .35, length = lengthMin + random.nextDouble() * (lengthMax - lengthMin);
                double half = halfMin + random.nextDouble() * (halfMax - halfMin), seed = random.nextDouble();
                int parts = runs + (runs > 2 ? random.nextInt(2) : 0);
                List<Vec3> corners = new ArrayList<>();
                double x = x0, y = floor ? .03 : .1, direction = angle, done = 0;
                corners.add(f.at(x, y, depth));
                for (int k = 0; k < parts; k++) {
                    double part = length * (k == parts - 1 ? 1 - done : (.22 + random.nextDouble() * .2));
                    done += part / length;
                    x += Math.cos(direction) * part;
                    y += Math.sin(direction) * part;
                    // slow turbulence: a wave that runs outward, stronger towards the tip
                    double far = Math.min(1, done), wave = t * .55 - far * 2.2 + seed * 6.3;
                    double dx = (Math.sin(wave) + .5 * Math.sin(wave * 2.3 + 1.7)) * .22 * far, dy = Math.cos(wave * 1.3 + .6) * .14 * far;
                    corners.add(f.at(x + dx, Math.max(.05, y + dy), depth));
                    // the corner: a sharp turn, alternating sides, never a gentle bend
                    direction += (k % 2 == 0 ? 1 : -1) * (random.nextBoolean() ? 1 : -1) * (kink * (.7 + random.nextDouble()));
                    direction = Math.clamp(direction, .12, Math.PI - .12);
                }
                if (random.nextDouble() < curl) {
                    // the tip hooks round on itself
                    Vec3 tip = corners.get(corners.size() - 1), run = tip.subtract(corners.get(corners.size() - 2));
                    double hook = (random.nextBoolean() ? 1 : -1) * (1.6 + random.nextDouble() * .8), reach = length * (.1 + .1 * random.nextDouble());
                    Vec3 across = run.cross(f.near).normalize().scale(Math.sin(hook)), along = run.normalize().scale(Math.cos(hook));
                    corners.add(tip.add(across.add(along).scale(reach)));
                }
                // grows for a third of its life; over the last half its root lets go and it slides out along itself, thinning to nothing
                double grow = BtMath.smooth(0, period * .35, age) * 1.05, leave = BtMath.smooth(period * .5, period, age);
                float alpha = (float) (1 - BtMath.smooth(period * .82, period, age));
                out.add(new FxStroke.Stroke(FxStrokeMath.angular(corners, 10, runs <= 2 ? .04 : .07, f.near), half, seed,
                        Math.pow(leave, 1.3) * .97, grow, 0, alpha));
            }
        }
    }

    private static double linear(double a, double b, double t) { return Math.clamp((t - a) / (b - a), 0, 1); }

    private record Frame(Vec3 feet, Vec3 right, Vec3 near) {
        Vec3 at(double x, double y, double z) { return feet.add(right.scale(x)).add(0, y, 0).add(near.scale(z)); }
    }
}
