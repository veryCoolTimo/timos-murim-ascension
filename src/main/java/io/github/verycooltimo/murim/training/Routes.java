package io.github.verycooltimo.murim.training;

import io.github.verycooltimo.murim.world.hua.MountHuaPlan;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;

import java.util.ArrayList;
import java.util.List;

/**
 * The two training routes of Mount Hua in world coordinates: the South Peak climb (ledges {@code climb_1..climb_16}
 * of {@link MountHuaPlan#CLIMB}, rest ledges are the restart points) and the trail ({@link MountHuaPlan#TRAIL}: lower
 * gate → sect gate). Read-only on the mountain's plan; nothing here changes terrain.
 */
public final class Routes {

    private Routes() {
    }

    /** South Peak climb checkpoints; empty without the mountain. */
    public static List<RouteRun.Point> climb(MountHuaSite site) {
        List<RouteRun.Point> out = new ArrayList<>();
        if (site == null) {
            return out;
        }
        for (MountHuaSites.ClimbSite c : MountHuaSites.climbSites(site)) {
            boolean wide = c.kind() != MountHuaPlan.Kind.STEP;
            out.add(new RouteRun.Point(c.feet().getX() + 0.5D, c.feet().getY(), c.feet().getZ() + 0.5D, wide ? 3.5D : 2.8D,
                    c.kind() == MountHuaPlan.Kind.REST));
        }
        return out;
    }

    /** Trail sprint checkpoints: every trail point, a wide radius (stairs wind), generous height band. */
    public static List<RouteRun.Point> trail(MountHuaSite site) {
        List<RouteRun.Point> out = new ArrayList<>();
        if (site == null) {
            return out;
        }
        for (MountHuaPlan.TrailPoint t : MountHuaPlan.TRAIL) {
            int[] w = site.toWorld(t.u(), t.v());
            double y = site.worldY(site.shape().height(t.u(), t.v())) + 1.0D;
            out.add(new RouteRun.Point(w[0] + 0.5D, y, w[1] + 0.5D, 7.0D, false, 6.0D, 8.0D));
        }
        return out;
    }
}
