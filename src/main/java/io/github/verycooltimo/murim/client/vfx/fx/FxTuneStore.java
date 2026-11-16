package io.github.verycooltimo.murim.client.vfx.fx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The pure part of {@link FxTune}: the override table, typed reads with defaults in code, and the book of keys that code asked for.
 * No Minecraft classes, so it is unit-tested. A JSON file is a flat object, {@code {"revision": 3, "flow.channel.width": 0.07, "tint": "#ffcc00"}};
 * keys that start with an underscore are scene controls (see {@link FxTuneScene}), they are never «unknown» and never asked for by effects.
 * A value of the wrong type is ignored (the default stays) and counted in {@link #badValues()}.
 */
public final class FxTuneStore {
    private volatile Map<String, JsonElement> values = Map.of();
    private volatile long revision = -1;
    /** key -> default as text, filled when code asks for a key; shown to the director so that he can discover what is tunable. */
    private final Map<String, String> known = new ConcurrentHashMap<>();
    private final Map<String, String> bad = new ConcurrentHashMap<>();
    private volatile boolean knownChanged;

    /**
     * Replaces the overrides with the content of a JSON text. A malformed text (not an object, a syntax error) changes nothing and the
     * reason is returned; null means the text was applied.
     */
    public String load(String json) {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (root == null || !root.isJsonObject()) return "top level is not a JSON object";
            JsonObject object = root.getAsJsonObject();
            Map<String, JsonElement> next = new TreeMap<>();
            for (var entry : object.entrySet()) next.put(entry.getKey(), entry.getValue());
            long rev = -1;
            JsonElement r = object.get("revision");
            if (r != null && r.isJsonPrimitive() && r.getAsJsonPrimitive().isNumber()) rev = r.getAsLong();
            bad.clear();
            values = Collections.unmodifiableMap(next);
            revision = rev;
            return null;
        } catch (RuntimeException e) {
            String m = String.valueOf(e.getMessage());
            return m.length() > 160 ? m.substring(0, 160) : m;
        }
    }

    public long revision() { return revision; }

    /** Keys of the file that no code asked for yet (a typo, or an effect that has not drawn yet), without controls and the revision. */
    public List<String> unknownKeys() {
        TreeSet<String> out = new TreeSet<>();
        for (String k : values.keySet()) if (!k.startsWith("_") && !k.equals("revision") && !known.containsKey(k)) out.add(k);
        return new ArrayList<>(out);
    }

    /** Every key code asked for, with its default as text. */
    public Map<String, String> knownKeys() { return new TreeMap<>(known); }

    /** Keys whose value had the wrong type, with a reason. */
    public Map<String, String> badValues() { return new TreeMap<>(bad); }

    /** True once after the book of known keys grew, so the ack can be written again. */
    public boolean takeKnownChanged() { boolean c = knownChanged; knownChanged = false; return c; }

    private JsonElement raw(String key, String defaultText) {
        if (known.put(key, defaultText) == null) knownChanged = true;
        return values.get(key);
    }

    public float f(String key, float def) {
        JsonElement e = raw(key, Float.toString(def));
        if (e == null) return def;
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return e.getAsFloat();
        return wrong(key, "expected a number", def);
    }

    /** Like {@link #f} but double: a scene whose numbers are doubles keeps its defaults exactly (a float default would change the picture). */
    public double d(String key, double def) {
        JsonElement e = raw(key, Double.toString(def));
        if (e == null) return def;
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return e.getAsDouble();
        bad.put(key, "expected a number");
        return def;
    }

    public int i(String key, int def) {
        JsonElement e = raw(key, Integer.toString(def));
        if (e == null) return def;
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return Math.round(e.getAsFloat());
        return wrong(key, "expected a number", def);
    }

    public boolean b(String key, boolean def) {
        JsonElement e = raw(key, Boolean.toString(def));
        if (e == null) return def;
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return p.getAsDouble() != 0;
        }
        return wrong(key, "expected true or false", def);
    }

    /** A colour as 0xRRGGBB: the JSON holds "#rrggbb", "0xrrggbb" or a plain integer. */
    public int rgb(String key, int def) {
        JsonElement e = raw(key, hex(def));
        if (e == null) return def;
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isNumber()) return p.getAsInt() & 0xFFFFFF;
            if (p.isString()) {
                Integer parsed = parseHex(p.getAsString());
                if (parsed != null) return parsed;
            }
        }
        return wrong(key, "expected a colour like \"#ffcc00\"", def);
    }

    public float[] fa(String key, float... def) {
        JsonElement e = raw(key, Arrays.toString(def));
        if (e == null) return def;
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            float[] out = new float[a.size()];
            for (int k = 0; k < out.length; k++) {
                JsonElement item = a.get(k);
                if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber()) return wrong(key, "array item " + k + " is not a number", def);
                out[k] = item.getAsFloat();
            }
            return out;
        }
        return wrong(key, "expected an array of numbers", def);
    }

    // ---- scene controls: read without registering a key

    /** A control number, or {@code def} when absent or not a number. */
    public double control(String key, double def) {
        JsonElement e = values.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : def;
    }

    /** A control array of numbers, or null when absent or malformed. */
    public double[] controlArray(String key) {
        JsonElement e = values.get(key);
        if (e == null || !e.isJsonArray()) return null;
        JsonArray a = e.getAsJsonArray();
        double[] out = new double[a.size()];
        for (int k = 0; k < out.length; k++) {
            JsonElement item = a.get(k);
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber()) return null;
            out[k] = item.getAsDouble();
        }
        return out;
    }

    public boolean has(String key) { return values.containsKey(key); }

    private <T> T wrong(String key, String why, T def) { bad.put(key, why); return def; }
    private float wrong(String key, String why, float def) { bad.put(key, why); return def; }
    private int wrong(String key, String why, int def) { bad.put(key, why); return def; }
    private boolean wrong(String key, String why, boolean def) { bad.put(key, why); return def; }

    static String hex(int rgb) { return String.format(Locale.ROOT, "#%06x", rgb & 0xFFFFFF); }

    static Integer parseHex(String s) {
        String t = s.trim();
        if (t.startsWith("#")) t = t.substring(1);
        else if (t.startsWith("0x") || t.startsWith("0X")) t = t.substring(2);
        if (t.length() != 6) return null;
        try { return Integer.parseInt(t, 16); } catch (NumberFormatException e) { return null; }
    }
}
