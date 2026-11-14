package io.github.verycooltimo.murim.client.dev;

import com.google.gson.Gson;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Capture-owned registry: no producers, maps, callbacks or JSON work exist when disabled. */
public final class VfxTelemetry {
    public static final boolean ENABLED = "1".equals(System.getenv("MURIM_CAPTURE_TELEMETRY"));
    private static final class Json { private static final Gson INSTANCE = new Gson(); }

    public static final class Sample {
        public final Map<String, Integer> systems = new LinkedHashMap<>();
        public final Set<String> layers = new LinkedHashSet<>();
        public final Map<String, Object> technique = new LinkedHashMap<>();
    }

    public static Sample sample(boolean enabled, Consumer<Sample> producers) {
        if (!enabled) return null;
        Sample sample = new Sample();
        producers.accept(sample);
        return sample;
    }

    public static String line(Map<String, Object> frame) {
        return Json.INSTANCE.toJson(frame) + "\n";
    }

    public static String line(Map<String, Object> rendered, int frame, String png) {
        Map<String, Object> row = new LinkedHashMap<>(rendered);
        row.put("frame", frame);
        row.put("png", png);
        return line(row);
    }

    private VfxTelemetry() { }
}
