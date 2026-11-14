package io.github.verycooltimo.murim.client.dev;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VfxTelemetryTest {
    @Test
    void disabledRegistryDoesNotAllocateASampleOrInvokeProducers() {
        for (int frame = 0; frame < 100; frame++) {
            assertNull(VfxTelemetry.sample(false, ignored -> fail("Disabled producer ran")));
        }
    }

    @Test
    void jsonLineKeepsNamesTypesAndEscapesWithoutExtraLines() {
        var sample = VfxTelemetry.sample(true, data -> {
            data.systems.put("PlumStormVfx.VORTEX", 1830);
            data.layers.add("PETALS");
            data.technique.putAll(Map.of("id", "murim:twenty_four_plum_sea", "phase", "holding", "t", 1.35, "star", 12));
        });
        var rendered = Map.<String, Object>of(
                "tick", 3456L, "partial", .5, "screen", new int[]{1280, 720}, "fov", 55.0,
                "camera", Map.of("pos", new double[]{1, 2, 3}, "yaw", 0.0, "pitch", 0.0),
                "bodies", Map.of("player", Map.of("bones", Map.of("RIGHT_HAND", new Object[]{640.0, 360.0, 3.0, true}),
                        "blade", Map.of("tip", new Object[]{640.0, 300.0, 3.0, false}), "bbox", new int[]{600, 200, 700, 600})),
                "systems", sample.systems, "layers", sample.layers, "technique", sample.technique);
        String line = VfxTelemetry.line(rendered, 12, "murim_side_012.png");
        assertTrue(line.endsWith("\n"));
        assertEquals(1, line.chars().filter(c -> c == '\n').count());
        var json = JsonParser.parseString(line).getAsJsonObject();
        assertEquals(Set.of("frame", "tick", "partial", "png", "screen", "fov", "camera", "bodies", "systems", "layers", "technique"), json.keySet());
        assertTrue(json.getAsJsonPrimitive("frame").isNumber());
        assertEquals(12, json.get("frame").getAsInt());
        assertEquals("murim_side_012.png", json.get("png").getAsString());
        assertEquals(2, json.getAsJsonArray("screen").size());
        assertTrue(json.getAsJsonPrimitive("partial").isNumber());
        assertTrue(json.getAsJsonPrimitive("fov").isNumber());
        assertTrue(json.getAsJsonObject("systems").getAsJsonPrimitive("PlumStormVfx.VORTEX").isNumber());
        assertTrue(json.getAsJsonArray("layers").get(0).getAsJsonPrimitive().isString());
        var hand = json.getAsJsonObject("bodies").getAsJsonObject("player").getAsJsonObject("bones").getAsJsonArray("RIGHT_HAND");
        assertEquals(4, hand.size());
        assertTrue(hand.get(3).getAsJsonPrimitive().isBoolean());
        assertTrue(json.getAsJsonObject("technique").getAsJsonPrimitive("phase").isString());
        assertTrue(json.getAsJsonObject("technique").getAsJsonPrimitive("t").isNumber());
        assertFalse(rendered.containsKey("frame"));
        assertEquals("a\nb\".png", JsonParser.parseString(VfxTelemetry.line(rendered, 0, "a\nb\".png")).getAsJsonObject().get("png").getAsString());
    }
}
