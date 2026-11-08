package io.github.verycooltimo.murim.client.camera;

import java.util.List;

/**
 * A camera shot: keyframed poses over the shot's own time 0..1, smoothed with a monotone cubic (no overshoot, so a
 * "pull in" never dips closer than its keys), then an overall easing. Pure maths, no Minecraft classes.
 */
public final class ShotTrack {

    public enum Ease {
        LINEAR, IN_OUT, IN, OUT;

        float apply(float x) {
            return switch (this) {
                case LINEAR -> x;
                case IN_OUT -> x * x * (3.0F - 2.0F * x);
                case IN -> x * x;
                case OUT -> 1.0F - (1.0F - x) * (1.0F - x);
            };
        }
    }

    /** A keyframe at time {@code t} (0..1 over the shot). */
    public record Key(float t, ShotPose pose) {
    }

    private final String name;
    private final int id;
    private final Ease ease;
    private final float rest;
    private final boolean mirror;
    private final float[] times;
    private final float[][] values;
    private final float[][] tangents;

    public ShotTrack(String name, int id, Ease ease, float rest, boolean mirror, List<Key> keys) {
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("shot " + name + " has no keys");
        }
        int n = keys.size();
        this.name = name;
        this.id = id;
        this.ease = ease;
        this.rest = Math.max(0.0F, Math.min(1.0F, rest));
        this.mirror = mirror;
        this.times = new float[n];
        this.values = new float[n][];
        for (int i = 0; i < n; i++) {
            Key k = keys.get(i);
            if (i > 0 && k.t() <= keys.get(i - 1).t()) {
                throw new IllegalArgumentException("shot " + name + ": key times must increase");
            }
            times[i] = k.t();
            values[i] = k.pose().channels();
        }
        this.tangents = new float[n][6];
        for (int c = 0; c < 6; c++) {
            for (int i = 0; i < n; i++) {
                tangents[i][c] = tangent(i, c);
            }
        }
    }

    /** Fritsch-Butland tangent: zero at a turning point, so the curve never overshoots its keys. */
    private float tangent(int i, int c) {
        int n = times.length;
        if (n == 1) {
            return 0.0F;
        }
        if (i == 0) {
            return (values[1][c] - values[0][c]) / (times[1] - times[0]);
        }
        if (i == n - 1) {
            return (values[i][c] - values[i - 1][c]) / (times[i] - times[i - 1]);
        }
        float h0 = times[i] - times[i - 1];
        float h1 = times[i + 1] - times[i];
        float d0 = (values[i][c] - values[i - 1][c]) / h0;
        float d1 = (values[i + 1][c] - values[i][c]) / h1;
        if (d0 * d1 <= 0.0F) {
            return 0.0F;
        }
        float w1 = 2.0F * h1 + h0;
        float w2 = h1 + 2.0F * h0;
        return (w1 + w2) / (w1 / d0 + w2 / d1);
    }

    public String name() {
        return name;
    }

    public Ease ease() { return ease; }

    public int id() {
        return id;
    }

    /** Whether a runtime side choice may mirror this shot (orbit direction, which shoulder). */
    public boolean mirror() {
        return mirror;
    }

    /** The pose at shot time {@code t} (0..1), after the overall easing. */
    public ShotPose at(float t) {
        float x = ease.apply(Math.max(0.0F, Math.min(1.0F, t)));
        int n = times.length;
        if (n == 1 || x <= times[0]) {
            return ShotPose.of(values[0]);
        }
        if (x >= times[n - 1]) {
            return ShotPose.of(values[n - 1]);
        }
        int i = 0;
        while (x > times[i + 1]) {
            i++;
        }
        float h = times[i + 1] - times[i];
        float s = (x - times[i]) / h;
        float s2 = s * s;
        float s3 = s2 * s;
        float h00 = 2.0F * s3 - 3.0F * s2 + 1.0F;
        float h10 = s3 - 2.0F * s2 + s;
        float h01 = -2.0F * s3 + 3.0F * s2;
        float h11 = s3 - s2;
        float[] out = new float[6];
        for (int c = 0; c < 6; c++) {
            out[c] = h00 * values[i][c] + h10 * h * tangents[i][c] + h01 * values[i + 1][c] + h11 * h * tangents[i + 1][c];
        }
        return ShotPose.of(out);
    }

    /** The pose the shot holds when the camera must not move (reduced shake). */
    public ShotPose restPose() {
        return at(rest);
    }
}
