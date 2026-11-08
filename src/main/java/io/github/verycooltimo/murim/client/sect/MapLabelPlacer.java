package io.github.verycooltimo.murim.client.sect;

import java.util.List;

/** The search for a map plaque's spot, with no Minecraft types so that it can be tested and timed alone. */
final class MapLabelPlacer {
    /** Candidate spots examined since start (a debug counter for tests). */
    static long candidates;

    private MapLabelPlacer() {
    }

    static int overlapArea(int[] a, int[] b) {
        return overlapArea(a[0], a[1], a[2], a[3], b[0], b[1], b[2], b[3]);
    }

    static int overlapArea(int ax, int ay, int aw, int ah, int bx, int by, int bw, int bh) {
        int w = Math.min(ax + aw, bx + bw) - Math.max(ax, bx);
        int h = Math.min(ay + ah, by + bh) - Math.max(ay, by);
        return w > 0 && h > 0 ? w * h : 0;
    }

    /**
     * The nearby spot for a plaque of w x h whose building dot is at (ax, ay): fewest building pixels covered, nothing
     * else touched, leader line clear. Null when no spot fits inside the bounds.
     */
    static int[] place(int ax, int ay, int w, int h, int minX, int minY, int maxX, int maxY, List<int[]> taken, List<int[]> footprints) {
        int bestScore = Integer.MAX_VALUE;
        int[] best = null;
        for (int oy = -52; oy <= 52; oy += 3) {
            for (int ox = -96; ox <= 96; ox += 3) {
                int x = ax - w / 2 + ox;
                int y = ay - h / 2 + oy;
                if (x < minX + 3 || y < minY + 3 || x + w > maxX - 3 || y + h > maxY - 3) {
                    continue;
                }
                candidates++;
                int clash = 0;
                for (int[] r : taken) {
                    clash += overlapArea(x, y, w, h, r[0] - 1, r[1] - 1, r[2] + 2, r[3] + 2);
                }
                int onBuildings = 0;
                for (int[] f : footprints) {
                    onBuildings += overlapArea(x, y, w, h, f[0], f[1], f[2], f[3]);
                }
                // the leader from the building's dot to this spot must not run through another plaque or marker
                int cx = Math.max(x, Math.min(x + w - 1, ax));
                int cy = Math.max(y, Math.min(y + h - 1, ay));
                int lhx = Math.min(ax, cx);
                int lhw = Math.abs(ax - cx) + 1;
                int lvy = Math.min(ay, cy);
                int lvh = Math.abs(ay - cy) + 1;
                for (int[] r : taken) {
                    clash += overlapArea(lhx, ay, lhw, 1, r[0], r[1], r[2], r[3]) + overlapArea(cx, lvy, 1, lvh, r[0], r[1], r[2], r[3]);
                }
                int score = clash * 40 + onBuildings * 6 + Math.abs(ox) + Math.abs(oy) * 2;
                if (score < bestScore) {
                    bestScore = score;
                    best = new int[] {x, y, w, h};
                }
            }
        }
        return best;
    }
}
