package io.github.verycooltimo.murim.client.vfx.fx;

import io.github.verycooltimo.murim.client.vfx.bt.BtCtx;
import io.github.verycooltimo.murim.client.vfx.bt.BtGlow;
import io.github.verycooltimo.murim.client.vfx.bt.BtMath;
import net.minecraft.world.phys.Vec3;

/**
 * Bench element «essence»: the flat violet-blue flame field of the author's panels 13 and 14 round the seated body, to be filmed
 * beside the reference layers (docs/design/reference/vfx-author-1113/plates/13-violet-layer.png, 14-violet-layer.png).
 * Two beats: the violet flame of panel 13 (the main field behind the body, a few patches in front), then the
 * indigo membrane and dark smears of panel 14. Each beat grows out of the body and closes at its end; the field itself flows
 * upward and outward the whole time (fx_essence.fsh).
 * /murim dev vfx bench essence; tools/capture/bench_film.sh essence.
 */
public final class FxEssenceBench {
    public static final double SECONDS = 13.5;
    private FxEssenceBench() {}

    public static void render(BtCtx c) {
        if (!FxEssence.available()) return;
        double t = c.labSeconds();
        var body = c.body();
        Vec3 feet = new Vec3(body.axis().x, body.ground(), body.axis().z);
        BtGlow.prepare(c.mc(), 1F);
        // the first second is empty: the body is still sitting down
        if (t < 1) return;
        Vec3 centre = feet.add(0, .6, 0);
        // the quads face the camera: n points at it, r along the screen's x, u along its y
        Vec3 n = c.camera().subtract(centre).normalize();
        Vec3 r = new Vec3(0, 1, 0).cross(n).normalize();
        Vec3 u = n.cross(r);
        Frame f = new Frame(feet, n, r);
        if (t < 7.5) violet(c, f, u, t - 1, 6.5);
        else dusk(c, f, u, t - 7.5, 6);
    }

    /** Panel 13. */
    private static void violet(BtCtx c, Frame f, Vec3 u, double t, double length) {
        double grow = BtMath.smooth(0, 1.6, t) * (1 - BtMath.smooth(length - 1.3, length, t));
        draw(c, FxEssenceLook.FLAME_13, f.quad(0, 2.3, -.6, 2.0, 2.7, 1.0, grow, 1F, u), t);
        draw(c, FxEssenceLook.FLAME_13, f.quad(-1.13, 1.65, .8, .97, 1.92, 4.0, grow * .75, .95F, u), t);
        draw(c, FxEssenceLook.FLAME_13, f.quad(1.27, 1.75, .9, .97, 1.98, 6.0, grow * .7, .95F, u), t);
    }

    /** Panel 14. */
    private static void dusk(BtCtx c, Frame f, Vec3 u, double t, double length) {
        double grow = BtMath.smooth(0, 1.6, t) * (1 - BtMath.smooth(length - 1.3, length, t));
        draw(c, FxEssenceLook.SMEAR_14, f.quad(0, 2.2, -1.0, 2.6, 2.6, 7.0, grow, .9F, u), t);
        draw(c, FxEssenceLook.DUSK_14, f.quad(.2, 2.0, -.5, 2.0, 2.6, 2.0, grow, 1F, u), t);
        draw(c, FxEssenceLook.DUSK_14, f.quad(1.3, 1.1, .9, .8, 1.3, 5.0, grow * .7, .95F, u), t);
    }

    private static void draw(BtCtx c, FxEssenceLook look, FxEssence.Quad quad, double t) {
        FxEssence.draw(look, quad, c.pose(), c.buffers(), t);
    }

    /** The seated figure: centre height and half axes of the place the field never covers (critic 14.11: nothing on head and face). */
    private static final double BODY_Y = .65, BODY_HALF_W = .5, BODY_HALF_H = .85;

    private record Frame(Vec3 feet, Vec3 n, Vec3 r) {
        /** A quad {@code x} to the screen's right of the body, {@code y} above the feet, {@code z} towards the camera. */
        FxEssence.Quad quad(double x, double y, double z, double halfW, double halfH, double seed, double growth, float alpha, Vec3 u) {
            Vec3 centre = feet.add(r.scale(x)).add(0, y, 0).add(n.scale(z));
            // the head and body of the seated figure stay clean: a closed place round it, in this quad's plane
            Vec3 d = feet.add(0, BODY_Y, 0).subtract(centre);
            return new FxEssence.Quad(centre, r, u, halfW, halfH, seed, growth, alpha, d.dot(r), d.dot(u), BODY_HALF_W, BODY_HALF_H);
        }
    }
}
