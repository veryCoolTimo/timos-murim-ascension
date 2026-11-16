package io.github.verycooltimo.murim.client.vfx.fx;

/**
 * The baked fields of the flat flame (fx_flame.fsh), made once by code: a small tiling RGBA texture, so the shader needs a few lookups
 * instead of loops of procedural noise. R = the distance to the nearest of two grids of drifting-looking discs (the web between discs is
 * where it is positive: scalloped bites, sharp cusps, thin wisps where two discs nearly touch), encoded 0.5 = the disc's edge; G and B = two
 * smooth noise fields (the warp and the tone bands); A = a blob field (the pink accents and the blue areas). Every field tiles, so the shader
 * scrolls and warps it at will. Client-free: the game uploads it as a DynamicTexture, the lab loads the same pixels.
 */
public final class FxFlameTexture {
    public static final int SIZE = 256;
    /** Disc cells across the texture: a big grid and a finer one that bites the wide webs into wisps. */
    private static final int BIG = 8, FINE = 16;

    private FxFlameTexture() {}

    private static double hash(int x, int y, int seed) {
        long h = x * 374761393L + y * 668265263L + seed * 2147483647L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }

    private static int wrap(int i, int n) { return ((i % n) + n) % n; }

    /** Distance (in cells) from the point to the nearest disc surface of one tiling grid (negative inside a disc). */
    private static double discs(double u, double v, int cells, int seed, double radius, double keep) {
        double gx = u * cells, gy = v * cells, best = 9;
        int cx = (int) Math.floor(gx), cy = (int) Math.floor(gy);
        for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
                int ix = wrap(cx + i, cells), iy = wrap(cy + j, cells);
                if (hash(ix, iy, seed + 9) >= keep) continue;
                double px = cx + i + .5 + .7 * (hash(ix, iy, seed) - .5), py = cy + j + .5 + .7 * (hash(ix, iy, seed + 1) - .5);
                double r = radius * (.7 + .6 * hash(ix, iy, seed + 2)), turn = hash(ix, iy, seed + 3) * Math.PI;
                double dx = gx - px, dy = gy - py, c = Math.cos(turn), s = Math.sin(turn);
                double rx = c * dx - s * dy, ry = s * dx + c * dy;
                double ax = Math.abs(rx), ay = Math.abs(ry);
                double oct = Math.max(Math.max(ax, ay), (ax + ay) * .7071) * .93;   // a little angular
                double d = (Math.hypot(rx, ry) * .8 + oct * .2) - r;
                best = Math.min(best, d);
            }
        }
        return best;
    }

    private static double noise(double u, double v, int freq, int seed) {
        double gx = u * freq, gy = v * freq;
        int x0 = (int) Math.floor(gx), y0 = (int) Math.floor(gy);
        double fx = gx - x0, fy = gy - y0;
        fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy);
        double a = hash(wrap(x0, freq), wrap(y0, freq), seed), b = hash(wrap(x0 + 1, freq), wrap(y0, freq), seed);
        double c = hash(wrap(x0, freq), wrap(y0 + 1, freq), seed), d = hash(wrap(x0 + 1, freq), wrap(y0 + 1, freq), seed);
        return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fy;
    }

    private static double fbm(double u, double v, int freq, int seed) {
        return .55 * noise(u, v, freq, seed) + .3 * noise(u, v, freq * 2, seed + 1) + .15 * noise(u, v, freq * 4, seed + 2);
    }

    private static int byteOf(double v) { return (int) Math.round(Math.clamp(v, 0, 1) * 255); }

    /** The pixels as 0xAABBGGRR ints (NativeImage's RGBA layout), row by row. */
    public static int[] pixels() {
        int[] out = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                double u = (x + .5) / SIZE, v = (y + .5) / SIZE;
                double d = Math.min(discs(u, v, BIG, 11, .34, 1.0), discs(u + .31 / BIG, v + .17 / BIG, FINE, 41, .3, .34) + .03);
                int r = byteOf(d * .9 + .5);
                int g = byteOf(fbm(u, v, 4, 101));
                int b = byteOf(fbm(u, v, 6, 211));
                int a = byteOf(fbm(u, v, 5, 307));
                out[y * SIZE + x] = a << 24 | b << 16 | g << 8 | r;
            }
        }
        return out;
    }
}
