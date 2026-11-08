package io.github.verycooltimo.murim.client.vfx;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

/** No game/GPU dependencies: shared by the registry, terrain sampler and unit fixtures. */
public final class TechniqueLightMath {
    public static final int CAP = 8;
    private TechniqueLightMath() {}
    /** Peak intensity: 1 is strong technique lighting; .3 is a subtle accent. */
    public static float groundAlpha(float power, double distance, float radius) {
        return .70F * power * falloff(distance, radius);
    }
    /** Albedo/colour multiplier before the shader applies Lambert and quadratic falloff. */
    public static float bodyGain(float power) { return power; }

    public static float falloff(double distance, float radius) {
        if (!(radius > 0) || distance >= radius) return 0;
        double k = 1 - Math.max(0, distance) / radius;
        return (float) (k * k);
    }
    public static float envelope(float age, int attack, int hold, int release) {
        if (age < 0 || age >= attack + hold + release) return 0;
        if (attack > 0 && age < attack) return age / attack;
        if (age < attack + hold) return 1;
        return release > 0 ? 1 - (age - attack - hold) / release : 0;
    }
    public static <T> List<T> nearest(List<T> values, ToDoubleFunction<T> distance, int cap) {
        return values.stream().sorted(Comparator.comparingDouble(distance)).limit(cap).toList();
    }
    /** Mutates the bounded registry using exactly the same admission rule as the runtime. */
    public static <T> boolean admit(List<T> values, T value, ToDoubleFunction<T> distance) {
        values.add(value);
        values.retainAll(nearest(values, distance, CAP));
        return values.contains(value);
    }
    public record Patch(double x0, double z0, double x1, double z1, double y) {}
    @FunctionalInterface public interface Top { double height(double x, double z); }
    /** Each half-cell owns its own height. Never interpolate a quad across a step. */
    public static List<Patch> cells(double x, double z, float radius, Top top) {
        List<Patch> result = new ArrayList<>();
        double minX = Math.floor((x - radius) * 2) / 2, minZ = Math.floor((z - radius) * 2) / 2;
        for (double a = minX; a < x + radius; a += .5) {
            for (double b = minZ; b < z + radius; b += .5) {
                if (Math.hypot(a + .25 - x, b + .25 - z) > radius + .36) continue;
                double y = top.height(a + .25, b + .25);
                if (Double.isFinite(y)) result.add(new Patch(a, b, a + .5, b + .5, y));
            }
        }
        return result;
    }
}
