package io.github.verycooltimo.murim.technique;

import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Render offsets on the approved body route; never chooses a target or builds another orbit.
 * API: MC 1.21.1 Vec3#add(double,double,double), public coordinates (local reference sources). */
public final class FootworkPaths {
    public static Vec3[] behindRibbon(List<Vec3> route) {
        Vec3[] result = new Vec3[route.size()];
        for (int i = 0; i < result.length; i++) {
            double u = i / (double) (result.length - 1);
            double height = u < .85 ? .3 + .7 * u / .85 : 1 + .4 * (u - .85) / .15;
            result[i] = route.get(i).add(0, height, 0);
        }
        return result;
    }
    /** Rebase prediction without moving on packet receipt; repay error by the last travel tick.
     * API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/phys/Vec3.java#subtract/add/scale. */
    public static Vec3 dipperPoint(List<Vec3> route, Vec3 clientStart, double acceptedAge, double age) {
        double start = SevenStarsRules.smoothstep(acceptedAge / 3.0);
        double progress = SevenStarsRules.smoothstep(age / 3.0);
        Vec3 error = clientStart.subtract(SevenStarsRules.routePoint(route, start));
        double remaining = start >= 1 ? 0 : Math.clamp((1 - progress) / (1 - start), 0, 1);
        return SevenStarsRules.routePoint(route, progress).add(error.scale(remaining));
    }

    /** Three equal frame increments, including the endpoint, without a threshold snap. */
    public static Vec3 correctionStep(Vec3 current, Vec3 endpoint, int framesLeft) {
        return endpoint.subtract(current).scale(1.0 / framesLeft);
    }

    /** Shortest-angle facing budget also bounds head/body render interpolation at wraparound. */
    public static float facing(float current, float desired) {
        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/util/Mth.java#wrapDegrees(float).
        return current + Math.clamp(net.minecraft.util.Mth.wrapDegrees(desired - current), -18f, 18f);
    }
    private FootworkPaths() { }
}
