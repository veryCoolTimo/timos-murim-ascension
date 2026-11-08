package io.github.verycooltimo.murim.client.vfx;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.world.phys.Vec3;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Capture-only submitted world vertices, before camera/pose transformation and depth rejection.
 * Vec3 public coordinates/distanceToSqr verified in supplied read-only Minecraft 1.21.1 sources.
 * Each owner/pass samples the first render of each integer cast tick, never every video frame. */
final class PlumLayerCapture {
    private static PlumLayerCapture active;
    private final Map<String, Integer> sampled = new HashMap<>();
    private final Map<String, Stats> layers = new java.util.TreeMap<>();
    private String form, pass, layer;
    private int tick, owner;
    private double ground;
    private List<Vec3> targets = List.of();
    private static final class Stats {
        int points, inside;
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY, sum;
    }
    static boolean enabled() {
        return Boolean.getBoolean("murim.capture") && "1".equals(System.getenv("MURIM_CAPTURE_PLUM_CAMERA"));
    }
    static void layer(String name) {
        if (active != null) {
            active.layer = name;
            active.layers.computeIfAbsent(name, key -> new Stats());
        }
    }
    void begin(String form, int tick, int owner, double ground, List<Vec3> targets, String pass) {
        active = null;
        if (!enabled()) return;
        if (!("RAIN".equals(form) ? tick >= 125 && tick <= 175 : "SEA".equals(form) && tick >= 80 && tick <= 110)) return;
        if (sampled.getOrDefault(pass, -1) == tick) return;
        sampled.put(pass, tick);
        this.form = form; this.tick = tick; this.owner = owner; this.ground = ground;
        this.targets = targets == null ? List.of() : targets; this.pass = pass;
        layers.clear(); active = this; layer(pass);
    }
    static void point(Vec3 at, float alpha) {
        if (active == null || at == null || alpha <= 0) return;
        Stats s = active.layers.get(active.layer);
        double height = at.y - active.ground;
        s.points++; s.min = Math.min(s.min, height); s.max = Math.max(s.max, height); s.sum += height;
        for (Vec3 target : active.targets) if (target != null && at.distanceToSqr(target) <= 2.25) { s.inside++; break; }
    }
    void end() {
        if (active != this) return;
        MurimMod.LOGGER.info("CAPTURE-PLUM-LAYER form={} tick={} owner={} pass={} layers={} groundY={} targets={} (points=submitted nonzero-alpha vertices; no depth/occlusion measurement)",
                form, tick, owner, pass, layers.size(), ground, targets);
        layers.forEach((layer, s) -> MurimMod.LOGGER.info(
                "CAPTURE-PLUM-LAYER form={} tick={} owner={} pass={} layer={} points={} height=[{},{},{}] insideTarget1_5={}",
                form, tick, owner, pass, layer, s.points, s.points == 0 ? 0 : s.min, s.points == 0 ? 0 : s.sum / s.points, s.points == 0 ? 0 : s.max, s.inside));
        active = null;
    }
    static void clear() { active = null; }
}
