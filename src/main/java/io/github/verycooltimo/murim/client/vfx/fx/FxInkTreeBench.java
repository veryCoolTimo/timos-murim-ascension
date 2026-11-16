package io.github.verycooltimo.murim.client.vfx.fx;

import io.github.verycooltimo.murim.client.vfx.bt.BtCtx;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;

/**
 * Bench element «inktree»: the black ink of reference panels 13 and 14, TRACED from the author's layers (FxInkTrace, the files in
 * assets/murim/vfx_shapes), grown and renewed by FxInkTraceScene (client-free, the same data and code tools/vfx-lab runs). This class
 * only puts the strokes in the world frame of the body and draws them: the soft violet light first (so black reads on a night scene),
 * then the ink. The cast (which tree, mirror, scale, time shift) is set by the system property murim.inktree.cast (default 0, as traced).
 * /murim dev vfx bench inktree; tools/capture/bench_film.sh inktree.
 */
public final class FxInkTreeBench {
    private static final int CAST = Integer.getInteger("murim.inktree.cast", 0);

    private FxInkTreeBench() {}

    public static void render(BtCtx c) {
        if (!FxStroke.available()) return;
        double t = c.labSeconds();
        var body = c.body();
        Vec3 feet = new Vec3(body.axis().x, body.ground(), body.axis().z), right = body.right(), near = body.near();
        FxInkTraceScene.Cast cast = FxInkTraceScene.Cast.of(CAST);
        FxLook inkLook = cast.tree() == 0 ? FxLook.INK_TRACE : FxLook.INK_TRACE_SHEET;
        List<FxStroke.Stroke> halo = new ArrayList<>(), ink = new ArrayList<>();
        for (FxInkTraceScene.Piece p : FxInkTraceScene.pieces(t, cast, true)) {
            List<Vec3> path = new ArrayList<>(p.points().length);
            for (double[] q : p.points()) path.add(feet.add(right.scale(q[0])).add(0, q[1], 0).add(near.scale(q[2])));
            var stroke = new FxStroke.Stroke(path, p.half(), p.seed(), p.from(), p.drawn(), 0, (float) p.alpha(), p.halves());
            (p.look().equals(FxInkTraceScene.HALO) ? halo : ink).add(stroke);
        }
        FxStroke.draw(FxLook.INK_TREE_HALO, halo, c.camera(), c.pose(), c.buffers(), t);
        FxStroke.draw(inkLook, ink, c.camera(), c.pose(), c.buffers(), t);
    }
}
