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
        // live tuning (dev only, MURIM_TUNE): the defaults below are the approved picture; keys are stroke.gold.<arc|medium|small>.<what>
        double reach = FxTune.d("stroke.gold.arc.reach", 3.3);
        var arc = FxStrokeMath.bezier(List.of(f.at(-3.4, .35, .9), f.at(-1.2, .55, 1.0), f.at(1.0, 1.5, .9), f.at(3.2, reach, .6)), 56);
        var medium = FxStrokeMath.bezier(List.of(f.at(-2.6, .15, 1.1), f.at(-.6, .3, 1.2), f.at(1.4, .95, 1.1)), 30);
        var small = FxStrokeMath.bezier(List.of(f.at(.6, 1.2, 1.0), f.at(.9, .7, 1.0), f.at(1.5, .1, 1.0)), 20);
        FxStroke.draw(goldLook(), List.of(
                wipe(arc, FxTune.d("stroke.gold.arc.half", .17), .31, t, FxTune.d("stroke.gold.arc.start", .3), FxTune.d("stroke.gold.arc.in", .5), FxTune.d("stroke.gold.arc.out", 1.6), .7),
                wipe(medium, FxTune.d("stroke.gold.medium.half", .085), .12, t, FxTune.d("stroke.gold.medium.start", .42), .4, FxTune.d("stroke.gold.medium.out", 1.5), .6),
                wipe(small, FxTune.d("stroke.gold.small.half", .05), .77, t, FxTune.d("stroke.gold.small.start", .6), .3, FxTune.d("stroke.gold.small.out", 1.4), .5)),
                c.camera(), c.pose(), c.buffers(), t);
    }

    /** The gold look with its colours, glow and brush numbers tunable live; the shared constant itself when tuning is off. */
    private static FxLook goldLook() {
        if (!FxTune.ON) return FxLook.GOLD;
        FxLook g = FxLook.GOLD;
        return new FxLook(FxTune.rgb("stroke.gold.look.core", g.core()), FxTune.rgb("stroke.gold.look.body", g.body()),
                FxTune.rgb("stroke.gold.look.rim", g.rim()), FxTune.rgb("stroke.gold.look.ink", g.ink()), g.inkAlpha(),
                g.coreStop(), g.bodyStop(), g.rimStop(), FxTune.f("stroke.gold.look.ragged", g.ragged()), FxTune.f("stroke.gold.look.dry", g.dry()),
                g.strands(), g.wobble(), g.flow(), g.tail(), g.head(), g.mainShare(), FxTune.f("stroke.gold.look.glow", g.glow()), g.contourBreak(),
                g.belly(), g.softBands(), g.boil(), g.stepFps(), g.clots(), g.clotSpeed(), g.clotSpacing(), g.shimmer(), g.halo(), g.strandGap(),
                g.slide(), g.thorn());
    }

    /**
     * A4: one slash, a little thicker, with a bright knot where it lands and a glow; thin black streaks run out of it along the cut
     * (the author, 14.11: «это всё ещё должен быть просто слэш… из него чёрные полоски исходят. Есть глоу»).
     */
    private static void steel(BtCtx c, Frame f, double t) {
        var cut = FxStrokeMath.bezier(List.of(f.at(-4.2, .98, .7), f.at(0, 1.08, 1.0), f.at(4.2, 1.0, .7)), 60);
        // numbers from the reference plate (tools/vfx-lab/scenes/a4-slash-on-plate.json): 111 px of that panel are one block
        var halo = wipe(cut, .62, .2, t, .2, .14, 1.1, .6); // wide and faint: the light of the slash is soft in the reference
        // Two lights: a wide faint one and a tighter strong one. On the grey reference plate a faint halo was enough; on a night
        // scene the dark streaks need real light under them, or they read as black lines from nowhere (the author, 14.11).
        var inner = wipe(cut, .3, .6, t, .2, .14, 1.1, .6);
        FxStroke.draw(FxLook.SLASH_HALO, List.of(new FxStroke.Stroke(halo.path(), halo.half(), halo.seed(), halo.from(), halo.drawn(),
                halo.erode(), .4F), new FxStroke.Stroke(inner.path(), inner.half(), inner.seed(), inner.from(), inner.drawn(),
                inner.erode(), .45F)), c.camera(), c.pose(), c.buffers(), t);
        FxStroke.draw(FxLook.STEEL, List.of(wipe(cut, .12, .52, t, .2, .14, 1.1, .6)), c.camera(), c.pose(), c.buffers(), t);
        // The dark marks are part of the slash, not things beside it (the author, 14.11: «белый цвет и чёрные элементы выглядят как
        // два разных элемента… должно быть одним целым»). Every mark is a piece of the cut's own path, shifted across it by a share
        // of the band's width at that place: on the band's edge, or inside the white. So they follow the band's taper and bend.
        Random random = new Random(33);
        List<FxStroke.Stroke> dark = new ArrayList<>(), white = new ArrayList<>();
        for (int i = 0; i < 26; i++) {
            boolean inside = i >= 14;            // 14 on the two edges, 12 inside the white
            double side = random.nextBoolean() ? 1 : -1, edge = random.nextBoolean() ? 1 : -1;
            double start = .5 + side * random.nextDouble() * (inside ? .16 : .3), length = (inside ? .07 : .08) + random.nextDouble() * (inside ? .16 : .24);
            double share = edge * (inside ? .15 + random.nextDouble() * .5 : .8 + random.nextDouble() * .3);
            // hairlines: in the reference the dark marks are long and thin, they converge on the knot; short fat ones read as blots
            double half = inside ? .004 + random.nextDouble() * .004 : .006 + random.nextDouble() * (i < 3 ? .012 : .006);
            dark.add(onBand(cut, .12, start, start + side * length, share, half, random.nextDouble(), t,
                    .22 + random.nextDouble() * .12, .1 + random.nextDouble() * .08, 1.0 + random.nextDouble() * .1, .5));
        }
        for (int i = 0; i < 8; i++) {
            // fine white lines just outside the band, the light that leaves it along the cut
            double side = random.nextBoolean() ? 1 : -1, edge = random.nextBoolean() ? 1 : -1;
            double start = .5 + side * random.nextDouble() * .25, length = .1 + random.nextDouble() * .25;
            white.add(onBand(cut, .12, start, start + side * length, edge * (1.15 + random.nextDouble() * .5), .008 + random.nextDouble() * .008,
                    random.nextDouble(), t, .22 + random.nextDouble() * .12, .1 + random.nextDouble() * .08, 1.0 + random.nextDouble() * .1, .5));
        }
        FxStroke.draw(FxLook.SLASH_INK, dark, c.camera(), c.pose(), c.buffers(), t);
        FxStroke.draw(FxLook.STEEL_LINE, white, c.camera(), c.pose(), c.buffers(), t);
    }

    /**
     * A mark that lies on a band: the piece of {@code path} between {@code u0} and {@code u1} (0..1 along it), shifted up or down by
     * {@code share} of the band's half-width there. The band is widest in its middle and thins to both tips, as fx_stroke draws it.
     */
    private static FxStroke.Stroke onBand(List<Vec3> path, double bandHalf, double u0, double u1, double share, double half, double seed,
                                          double t, double start, double in, double out, double gone) {
        List<Vec3> piece = new ArrayList<>();
        int n = path.size() - 1, steps = 12;
        for (int k = 0; k <= steps; k++) {
            double u = Math.clamp(u0 + (u1 - u0) * k / steps, 0, 1), at = u * n;
            int i = (int) Math.min(n - 1, Math.floor(at));
            Vec3 p = path.get(i).lerp(path.get(i + 1), at - i);
            double width = bandHalf * .5 * Math.pow(Math.sin(Math.PI * u), .7) * (1 + .55 * Math.exp(-Math.pow((u - .5) / .09, 2)));
            piece.add(p.add(0, share * width, 0));
        }
        return wipe(piece, half, seed, t, start, in, out, gone);
    }

    /** Lines that leave the knot of the slash along the cut: {@code n} of them, half-width and length in the given ranges (blocks). */
    private static void fan(List<FxStroke.Stroke> out, Frame f, double t, Random random, int n, double halfMin, double halfMax,
                            double lengthMin, double lengthMax, double spread, double near) {
        for (int i = 0; i < n; i++) {
            double side = random.nextBoolean() ? 1 : -1, offset = (random.nextDouble() - .5) * spread;
            double x0 = side * (.04 + random.nextDouble() * near), reach = lengthMin + random.nextDouble() * (lengthMax - lengthMin);
            double end = offset * (1 + random.nextDouble() * .5) + (random.nextDouble() - .5) * .03;
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
     * A6: cultivation, the version of film r12 that the author called the best so far (14.11: «3 уже гораздо лучше… лучший
     * результат»). Thin angular ink lines rise from the body, each on its own slow clock; clots run out along them and a slow
     * turbulence bends them; thin violet licks behind. Restored after the layered version (blue shapes, swelling lines, clot mass)
     * read as fat smeared strokes: «какой-то слоп». Add to it one thing at a time, and film each addition.
     */
    private static void ink(BtCtx c, Frame f, double t) {
        List<FxStroke.Stroke> violet = new ArrayList<>(), ink = new ArrayList<>();
        Random random = new Random(23);
        for (int i = 0; i < 26; i++) {
            boolean back = i < 9;
            double period = 3.6 + random.nextDouble() * 2.2, age = (t + random.nextDouble() * period) % period;
            // the first seconds of the beat: everything is still growing out of the body
            age = Math.min(age, t + .0001);
            double angle = Math.PI * (.1 + .8 * random.nextDouble()), length = back ? 1.3 + random.nextDouble() * 1.5 : 1.8 + random.nextDouble() * 2.6;
            double depth = back ? -.5 : (random.nextDouble() - .5) * .9, x0 = (random.nextDouble() - .5) * .9;
            double half = back ? .1 + random.nextDouble() * .07 : i < 13 ? .1 + random.nextDouble() * .05 : .05 + random.nextDouble() * .035;
            double seed = random.nextDouble();
            int runs = back ? 2 : 3 + random.nextInt(2);
            List<Vec3> corners = new ArrayList<>();
            double x = x0, y = .1, direction = angle, done = 0;
            corners.add(f.at(x, y, depth * .2));
            for (int k = 0; k < runs; k++) {
                double part = length * (k == runs - 1 ? 1 - done : (.22 + random.nextDouble() * .2));
                done += part / length;
                x += Math.cos(direction) * part;
                y += Math.sin(direction) * part;
                // slow turbulence: a wave that runs outward, stronger towards the tip
                double far = Math.min(1, done), wave = t * .55 - far * 2.2 + seed * 6.3;
                double dx = (Math.sin(wave) + .5 * Math.sin(wave * 2.3 + 1.7)) * .22 * far, dy = (Math.cos(wave * 1.3 + .6)) * .14 * far;
                corners.add(f.at(x + dx, Math.max(.05, y + dy), depth * (.2 + .8 * far)));
                // the corner: a sharp turn, alternating sides, never a gentle bend
                direction += (k % 2 == 0 ? 1 : -1) * (random.nextBoolean() ? 1 : -1) * (.45 + random.nextDouble() * .7);
                direction = Math.clamp(direction, .12, Math.PI - .12);
            }
            double grow = BtMath.smooth(0, .9, age) * 1.05, wither = linear(period - 1.1, period, age);
            (back ? violet : ink).add(new FxStroke.Stroke(FxStrokeMath.angular(corners, 10, .07, f.near), half, seed,
                    wither * wither * .7, grow, wither * .9, 1F));
        }
        FxStroke.draw(FxLook.VIOLET, violet, c.camera(), c.pose(), c.buffers(), t);
        FxStroke.draw(FxLook.INK, ink, c.camera(), c.pose(), c.buffers(), t);
    }


    private static double linear(double a, double b, double t) { return Math.clamp((t - a) / (b - a), 0, 1); }

    private record Frame(Vec3 feet, Vec3 right, Vec3 near) {
        Vec3 at(double x, double y, double z) { return feet.add(right.scale(x)).add(0, y, 0).add(near.scale(z)); }
    }
}
