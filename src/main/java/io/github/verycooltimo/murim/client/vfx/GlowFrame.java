package io.github.verycooltimo.murim.client.vfx;

/** Pure frame arbitration; legacy scenes and techniques share the same maximum and final flush. */
public final class GlowFrame {
    private boolean collecting, prepared, composited;
    private float strength;
    public void begin() { collecting = true; prepared = false; composited = false; strength = 0; }
    public boolean collecting() { return collecting; }
    public boolean request(float value) {
        if (!collecting || composited || !Float.isFinite(value) || value <= 0) return false;
        strength = Math.max(strength, value);
        boolean first = !prepared;
        prepared = true;
        return first;
    }
    public float strength() { return strength; }
    public boolean finish() {
        collecting = false;
        if (composited) return false;
        composited = true;
        return prepared;
    }
}
