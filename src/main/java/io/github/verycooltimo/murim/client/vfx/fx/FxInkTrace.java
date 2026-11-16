package io.github.verycooltimo.murim.client.vfx.fx;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A branching ink tree traced from the author's reference layer (tools/vfx-lab/trace_tree.py: skeleton + distance transform of
 * plates/13-black-layer.png and 14-black-layer.png, written to assets/murim/vfx_shapes/inktree_13.json and inktree_14.json).
 * The file is geometry, not a picture: every branch is an ordered point list with a half-width per point, oriented away from the root,
 * with its parent (the branch whose END is the fork it grows from). This class loads it and holds the pure maths of the cultivation
 * schedule (growth order and timing, renewal groups). Client-free: tools/vfx-lab runs the same code.
 * Coordinates: blocks, x right, y up, root at (0, 0). {@code pxPerBlock} says how many pixels of the layer were one block.
 */
public final class FxInkTrace {
    /** One branch. {@code start}: distance along the tree from the root to the first point; {@code gap}: >0 when the branch begins an island the energy jumps to. */
    public static final class Branch {
        public final int id, parent, depth;
        public final boolean loop;
        public final double start, gap, length, maxHalf;
        public final double[] x, y, h, along;

        Branch(int id, int parent, int depth, boolean loop, double start, double gap, double[] x, double[] y, double[] h) {
            this.id = id; this.parent = parent; this.depth = depth; this.loop = loop; this.start = start; this.gap = gap;
            this.x = x; this.y = y; this.h = h;
            along = new double[x.length];
            for (int i = 1; i < x.length; i++) along[i] = along[i - 1] + Math.hypot(x[i] - x[i - 1], y[i] - y[i - 1]);
            length = along[x.length - 1];
            double m = 0;
            for (double v : h) m = Math.max(m, v);
            maxHalf = m;
        }

        public int size() { return x.length; }
    }

    public final double pxPerBlock;
    public final List<Branch> branches;

    public FxInkTrace(double pxPerBlock, List<Branch> branches) {
        this.pxPerBlock = pxPerBlock;
        this.branches = branches;
    }

    /** Loads a shape from the mod's resources (assets/murim/vfx_shapes/<name>.json). */
    public static FxInkTrace load(String name) {
        try (InputStream in = FxInkTrace.class.getResourceAsStream("/assets/murim/vfx_shapes/" + name + ".json")) {
            if (in == null) throw new IllegalStateException("missing vfx shape " + name);
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static FxInkTrace parse(String json) {
        Map<String, Object> doc = (Map<String, Object>) new Json(json).value();
        List<Branch> out = new ArrayList<>();
        for (Object o : (List<Object>) doc.get("branches")) {
            Map<String, Object> b = (Map<String, Object>) o;
            out.add(new Branch(num(b.get("id")).intValue(), num(b.get("parent")).intValue(), num(b.get("depth")).intValue(),
                    Boolean.TRUE.equals(b.get("loop")), num(b.get("start")), b.get("gap") == null ? 0 : num(b.get("gap")),
                    arr(b.get("x")), arr(b.get("y")), arr(b.get("h"))));
        }
        return new FxInkTrace(num(doc.get("pxPerBlock")), out);
    }

    private static Double num(Object o) { return ((Number) o).doubleValue(); }

    @SuppressWarnings("unchecked")
    private static double[] arr(Object o) {
        List<Object> l = (List<Object>) o;
        double[] r = new double[l.size()];
        for (int i = 0; i < r.length; i++) r[i] = num(l.get(i));
        return r;
    }

    /** Per-branch growth speed: the base speed with a stable spread so the heads of neighbours do not run in step. */
    public double[] speeds(double base, long seed) {
        double[] s = new double[branches.size()];
        for (Branch b : branches) s[b.id] = base * (.85 + .3 * FxInkTree.hash(seed * 131L + b.id * 7L));
        return s;
    }

    /**
     * When each branch starts to grow, seconds after the tree begins: the root's branches at once, every other branch when the
     * head of its parent reaches the fork (the parent's end), an island after the gap has been crossed at the same speed.
     * Branch ids are ordered so that a parent always has the smaller id.
     */
    public double[] startTimes(double[] speed) {
        double[] ts = new double[branches.size()];
        for (Branch b : branches) {
            double t = 0;
            if (b.parent >= 0) t = ts[b.parent] + branches.get(b.parent).length / speed[b.parent];
            ts[b.id] = t + b.gap / speed[b.id];
        }
        return ts;
    }

    /**
     * Renewal groups: a branch that starts further than {@code ring} blocks from the root begins a group, together with everything
     * that grows out of it. The branches nearer the root (group -1) are never renewed. Returns the group root of each branch, or -1.
     */
    public int[] groups(double ring) {
        int[] g = new int[branches.size()];
        for (Branch b : branches) {
            int pg = b.parent >= 0 ? g[b.parent] : -1;
            g[b.id] = pg >= 0 ? pg : (b.start >= ring ? b.id : -1);
        }
        return g;
    }

    /** Tiny JSON reader (objects, arrays, numbers, strings, booleans, null): the lab runs this class without any library. */
    private static final class Json {
        private final String s;
        private int i;

        Json(String s) { this.s = s; }

        Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                i++; ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws(); String k = (String) value(); ws(); i++; // ':'
                    m.put(k, value()); ws();
                    if (s.charAt(i++) == '}') return m;
                }
            }
            if (c == '[') {
                List<Object> l = new ArrayList<>();
                i++; ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    l.add(value()); ws();
                    if (s.charAt(i++) == ']') return l;
                }
            }
            if (c == '"') {
                int e = s.indexOf('"', i + 1);
                String r = s.substring(i + 1, e);
                i = e + 1;
                return r;
            }
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int e = i;
            while (e < s.length() && "+-0123456789.eE".indexOf(s.charAt(e)) >= 0) e++;
            double d = Double.parseDouble(s.substring(i, e));
            i = e;
            return d;
        }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    }
}
