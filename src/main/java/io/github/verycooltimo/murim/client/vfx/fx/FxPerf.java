package io.github.verycooltimo.murim.client.vfx.fx;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Dev-only frame cost of a bench element: counters the effect engine's draw code adds to (strokes, stroke vertices, draw calls, the
 * summed screen coverage of field quads in pixels, CPU time spent building meshes), averaged and logged once per second by
 * {@link #frameEnd}. Client-free. One line: {@code Bench perf <element> t=<s> frame=<ms> build=<ms> strokes=<n> verts=<n> draws=<n>
 * fieldPx=<n> lightPx=<n> cpu=<ms>}: frame = mean time between two bench frames (1000 / fps), build = mean CPU time of the mesh code
 * (ink and field and light meshes), cpu = mean time inside the bench's render call, the counts are per frame, fieldPx and lightPx are
 * estimates of the covered pixels (a quad's area projected onto the view, times the pixels per block at its distance; one full
 * screen of 1920x1080 is 2.07 million).
 */
public final class FxPerf {
    private static long strokes, verts, draws, buildNanos;
    private static double fieldPx, lightPx;
    private static long frames, sumStrokes, sumVerts, sumDraws, sumBuild, sumFrameNanos, sumCpu;
    private static double sumField, sumLight;
    private static long lastFrame = -1, lastLog = -1, frameStartNanos;
    private static String phase = "";
    private static double lastFrameMs = 16;

    private FxPerf() {}

    public static void stroke(int quads) { strokes++; verts += 4L * quads; }
    public static void vertices(int n) { verts += n; }
    public static void draw() { draws++; }
    public static void build(long nanos) { buildNanos += nanos; }
    public static void field(double px) { fieldPx += px; }
    public static void light(double px) { lightPx += px; }

    /** The time between the last two bench frames in milliseconds: what the quality governor (FxQuality) is fed with. */
    public static double lastFrameMs() { return lastFrameMs; }

    /** Call at the start of the bench's render. */
    public static void frameStart() { frameStartNanos = System.nanoTime(); }

    /** Pixels covered by a quad a-b-c-d (world points) seen from {@code camera}; {@code k} = screen height in pixels / (2 tan(fov/2)). */
    public static double quadPixels(double[] a, double[] b, double[] c, double[] d, double[] camera, double k) {
        double[] centre = {(a[0] + b[0] + c[0] + d[0]) / 4, (a[1] + b[1] + c[1] + d[1]) / 4, (a[2] + b[2] + c[2] + d[2]) / 4};
        double vx = camera[0] - centre[0], vy = camera[1] - centre[1], vz = camera[2] - centre[2];
        double dist = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (dist < 1e-6) return 0;
        // area = half the cross product of the diagonals; projected area = area * |cos| between its normal and the view
        double ux = c[0] - a[0], uy = c[1] - a[1], uz = c[2] - a[2], wx = d[0] - b[0], wy = d[1] - b[1], wz = d[2] - b[2];
        double nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
        double area = .5 * Math.sqrt(nx * nx + ny * ny + nz * nz);
        double cos = area < 1e-9 ? 0 : Math.abs(nx * vx + ny * vy + nz * vz) / (2 * area * dist);
        double ppb = k / dist;
        return area * cos * ppb * ppb;
    }

    /**
     * Call at the end of the bench's render; logs the averages once per second through {@code log}, and at once when the phase changes
     * ("empty": the bench beat before the effect starts, "fx": the effect; with MURIM_BENCH_PERF=1 the frame limit is off, so the two
     * phases' fps differ by what the effect itself costs).
     */
    public static void frameEnd(String element, double seconds, String phaseNow, Consumer<String> log) {
        long now = System.nanoTime();
        if (!phaseNow.equals(phase)) { flush(element, seconds, log); phase = phaseNow; lastLog = now; }
        frames++; sumStrokes += strokes; sumVerts += verts; sumDraws += draws; sumBuild += buildNanos; sumField += fieldPx; sumLight += lightPx;
        sumCpu += now - frameStartNanos;
        if (lastFrame >= 0) { sumFrameNanos += now - lastFrame; lastFrameMs = (now - lastFrame) / 1e6; }
        lastFrame = now;
        strokes = verts = draws = buildNanos = 0; fieldPx = lightPx = 0;
        if (lastLog < 0) lastLog = now;
        if (now - lastLog >= 1_000_000_000L) { flush(element, seconds, log); lastLog = now; }
    }

    private static void flush(String element, double seconds, Consumer<String> log) {
        if (frames > 1) {
            double n = frames, frameMs = sumFrameNanos / (n - 1) / 1e6;
            log.accept(String.format(Locale.ROOT, "Bench perf %s phase=%s t=%.1f frame=%.2f fps=%.1f build=%.2f strokes=%d verts=%d draws=%d fieldPx=%d lightPx=%d cpu=%.2f",
                    element, phase, seconds, frameMs, 1000 / Math.max(frameMs, 1e-3), sumBuild / n / 1e6, Math.round(sumStrokes / n), Math.round(sumVerts / n),
                    Math.round(sumDraws / n), Math.round(sumField / n), Math.round(sumLight / n), sumCpu / n / 1e6));
        }
        frames = sumStrokes = sumVerts = sumDraws = sumBuild = sumFrameNanos = sumCpu = 0; sumField = sumLight = 0;
    }
}
