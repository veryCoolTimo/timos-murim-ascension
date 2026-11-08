package io.github.verycooltimo.murim.client.vfx.preset;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Resource-only DTO. No Minecraft classes: the same JSON is usable in ordinary JUnit. */
public record EssencePreset(List<Layer> layers) {
    public record Point(double x, double y) { }
    public record Fold(List<Point> path, double width, double gain) { }
    public record Veil(List<Point> a, List<Point> b, double gain) { }
    public record Layer(Map<String, Double> params, Map<String, String> colors,
                        List<Point> path, List<Double> widthProfile, List<Fold> folds,
                        List<Veil> veils, double seed) {
        public double p(String name) { return params.getOrDefault(name, 0.0); }
    }
    public double duration() {
        return layers.stream().mapToDouble(l -> l.p("start") + l.p("dur")).max().orElse(0);
    }
    public static EssencePreset read(Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        if (!"essence_ribbon".equals(root.get("id").getAsString()) || root.get("version").getAsInt() != 4)
            throw new IllegalArgumentException("Expected essence_ribbon version 4");
        JsonArray array = root.getAsJsonArray("layers");
        if (array.size() == 0 || array.size() > 16) throw new IllegalArgumentException("Expected 1..16 layers");
        List<Layer> layers = new ArrayList<>();
        for (JsonElement value : array) {
            JsonObject o = value.getAsJsonObject();
            if (o.has("type") && !o.get("type").getAsString().equals("ribbon"))
                throw new IllegalArgumentException("Only ribbon layers are supported in stage 4");
            Map<String, Double> params = new HashMap<>(PresetDefaults.PARAMS);
            if (o.has("params")) o.getAsJsonObject("params").entrySet().forEach(e -> params.put(e.getKey(), number(e.getValue())));
            for (String key : List.of("dur", "inDur", "outDur", "pLife", "lumaSoft", "head", "tail"))
                if (params.get(key) <= 0) throw new IllegalArgumentException(key + " must be positive");
            if (params.get("start") < 0 || params.get("dur") + params.get("start") > 300)
                throw new IllegalArgumentException("Timeline must fit in 300 seconds");
            for (String key : List.of("sheets", "threads", "wisps", "pCount", "pOut", "width")) {
                double max = switch (key) { case "sheets" -> 5; case "threads" -> 10; case "wisps" -> 24;
                    case "pCount" -> 2000; case "pOut" -> 300; default -> 2; };
                if (params.get(key) < 0 || params.get(key) > max) throw new IllegalArgumentException("Budget: " + key);
            }
            Map<String, String> colors = new HashMap<>(PresetDefaults.COLORS);
            if (o.has("colors")) o.getAsJsonObject("colors").entrySet().forEach(e -> {
                String c = e.getValue().getAsString();
                if (!c.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Invalid color " + e.getKey());
                colors.put(e.getKey(), c);
            });
            List<Point> path = o.has("path") ? points(o.get("path")) : List.of(
                    new Point(-.51, -.23), new Point(-.8, .6), new Point(.86, .06), new Point(.46, 1.23));
            List<Double> profile = new ArrayList<>();
            if (o.has("widthProfile") && !o.get("widthProfile").isJsonNull()) {
                for (JsonElement v : o.getAsJsonArray("widthProfile")) {
                    double n = number(v);
                    if (n < 0 || n > 20) throw new IllegalArgumentException("Invalid width profile");
                    profile.add(n);
                }
                if (profile.size() < 2 || profile.size() > 256) throw new IllegalArgumentException("Invalid profile length");
            }
            List<Fold> folds = new ArrayList<>();
            if (o.has("folds")) for (JsonElement v : o.getAsJsonArray("folds")) {
                JsonObject f = v.getAsJsonObject();
                double w = number(f.get("width")), g = number(f.get("gain"));
                if (w < 0 || w > 100 || g < 0 || g > 20) throw new IllegalArgumentException("Invalid fold");
                folds.add(new Fold(points(f.get("path")), w, g));
            }
            List<Veil> veils = new ArrayList<>();
            if (o.has("veils")) for (JsonElement v : o.getAsJsonArray("veils")) {
                JsonObject f = v.getAsJsonObject();
                double g = number(f.get("gain"));
                if (g < 0 || g > 20) throw new IllegalArgumentException("Invalid veil");
                veils.add(new Veil(points(f.get("a")), points(f.get("b")), g));
            }
            if (folds.size() > 64 || veils.size() > 32) throw new IllegalArgumentException("Too many folds/veils");
            layers.add(new Layer(Map.copyOf(params), Map.copyOf(colors), path, List.copyOf(profile),
                    List.copyOf(folds), List.copyOf(veils), params.getOrDefault("noiseSeed", (double) layers.size())));
        }
        return new EssencePreset(List.copyOf(layers));
    }
    private static double number(JsonElement v) {
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected number");
        double n = v.getAsDouble();
        if (!Double.isFinite(n) || Math.abs(n) > 100000) throw new IllegalArgumentException("Non-finite or excessive number");
        return n;
    }
    private static List<Point> points(JsonElement v) {
        JsonArray a = v.getAsJsonArray();
        if (a.size() < 4 || a.size() > 256) throw new IllegalArgumentException("Expected 4..256 path points");
        List<Point> result = new ArrayList<>();
        for (JsonElement e : a) {
            JsonArray q = e.getAsJsonArray();
            if (q.size() != 2) throw new IllegalArgumentException("Expected [x,y]");
            result.add(new Point(number(q.get(0)), number(q.get(1))));
        }
        return List.copyOf(result);
    }
}
