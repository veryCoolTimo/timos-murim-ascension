package io.github.verycooltimo.murim.world.hua;

/**
 * Seeded gradient (Perlin) noise for the Mount Hua height function.
 *
 * <p>Own implementation on purpose: the shape class must stay free of Minecraft types so that
 * the whole mountain can be rendered as a preview image in a plain JVM (fast iterations without a
 * game client), and so that the result is bit-identical on every worldgen thread. Immutable after
 * construction, therefore thread-safe.
 *
 * <p>Algorithm: K. Perlin, "Improving Noise" (SIGGRAPH 2002) — permutation table + quintic fade.
 */
public final class HuaNoise {

    private final int[] perm = new int[512];

    public HuaNoise(long seed) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) {
            p[i] = i;
        }
        long s = seed ^ 0x5DEECE66DL;
        for (int i = 255; i > 0; i--) {
            s = s * 6364136223846793005L + 1442695040888963407L;
            int j = (int) ((s >>> 33) % (i + 1));
            int t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        for (int i = 0; i < 512; i++) {
            perm[i] = p[i & 255];
        }
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static double grad(int hash, double x, double y, double z) {
        int h = hash & 15;
        double u = h < 8 ? x : y;
        double v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }

    /** 3D noise, roughly in [-1, 1]. */
    public double noise(double x, double y, double z) {
        int xi = (int) Math.floor(x);
        int yi = (int) Math.floor(y);
        int zi = (int) Math.floor(z);
        x -= xi;
        y -= yi;
        z -= zi;
        xi &= 255;
        yi &= 255;
        zi &= 255;
        double u = fade(x);
        double v = fade(y);
        double w = fade(z);
        int a = perm[xi] + yi;
        int aa = perm[a] + zi;
        int ab = perm[a + 1] + zi;
        int b = perm[xi + 1] + yi;
        int ba = perm[b] + zi;
        int bb = perm[b + 1] + zi;
        return lerp(w,
                lerp(v, lerp(u, grad(perm[aa], x, y, z), grad(perm[ba], x - 1, y, z)),
                        lerp(u, grad(perm[ab], x, y - 1, z), grad(perm[bb], x - 1, y - 1, z))),
                lerp(v, lerp(u, grad(perm[aa + 1], x, y, z - 1), grad(perm[ba + 1], x - 1, y, z - 1)),
                        lerp(u, grad(perm[ab + 1], x, y - 1, z - 1), grad(perm[bb + 1], x - 1, y - 1, z - 1))));
    }

    /** 2D noise (slice z = 0.5 so the lattice zero plane is avoided), roughly in [-1, 1]. */
    public double noise(double x, double y) {
        return noise(x, y, 0.5);
    }

    /** Fractal sum, normalised to about [-1, 1]. */
    public double fbm(double x, double y, int octaves) {
        double sum = 0;
        double amp = 1;
        double norm = 0;
        for (int i = 0; i < octaves; i++) {
            sum += amp * noise(x, y, 0.5 + i * 7.31);
            norm += amp;
            amp *= 0.5;
            x *= 2.03;
            y *= 2.03;
        }
        return sum / norm;
    }

    /** Ridged fractal in [0, 1]: sharp crests where the base noise crosses zero. */
    public double ridged(double x, double y, int octaves) {
        double sum = 0;
        double amp = 1;
        double norm = 0;
        for (int i = 0; i < octaves; i++) {
            double n = 1 - Math.abs(noise(x, y, 3.7 + i * 5.13));
            sum += amp * n * n;
            norm += amp;
            amp *= 0.5;
            x *= 2.07;
            y *= 2.07;
        }
        return sum / norm;
    }
}
