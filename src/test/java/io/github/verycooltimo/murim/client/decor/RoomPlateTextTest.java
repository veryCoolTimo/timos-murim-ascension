package io.github.verycooltimo.murim.client.decor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomPlateTextTest {
    @Test
    void plaquesStayEnglishWithRussianClientLanguage() throws Exception {
        JsonObject russian;
        // Load packaged resources like SectPathV2Test; the test working directory can vary.
        try (var stream = RoomPlateTextTest.class.getResourceAsStream("/assets/murim/lang/ru_ru.json")) {
            assertNotNull(stream, "Russian language resource must be on the test classpath");
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                russian = JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
        Language previous = Language.getInstance();
        // Install the actual ru_ru translations in the same global language used by Components.
        Language.inject(new Language() {
            @Override public String getOrDefault(String key, String fallback) {
                return russian.has(key) ? russian.get(key).getAsString() : fallback;
            }
            @Override public boolean has(String key) { return russian.has(key); }
            @Override public boolean isDefaultRightToLeft() { return false; }
            @Override public FormattedCharSequence getVisualOrder(FormattedText text) { return FormattedCharSequence.EMPTY; }
        });
        try {
            assertNotEquals("Room 201", Component.translatable("murim.sect.way.room", "201").getString());
            var room = RoomPlateText.resolve(true, "murim.sect.way.dorm3|murim.sect.way.room|201");
            assertEquals("Room 201", room.title());
            assertEquals("3rd Generation", room.name());
            assertEquals("Room 201", Component.literal(room.title()).getString());
            var stairs = RoomPlateText.resolve(true, "murim.sect.way.dorm2|murim.sect.way.floor_stairs|2");
            assertEquals(new RoomPlateText.Lines("Floor 2 - Stairs", "2nd Generation"), stairs);
            assertEquals(new RoomPlateText.Lines("Disciple's Room", "Tester"), RoomPlateText.resolve(false, "Tester"));
        } finally {
            Language.inject(previous);
        }
    }

    @Test
    void everySectRoomAndGenerationFitsTheFixedTablet() throws Exception {
        JsonObject layout = resource("/data/murim/sect/layout.json");
        JsonObject model = resource("/assets/murim/models/block/sect_name_plate.json");
        double minX = 16, maxX = 0;
        // Include rotated cord corners, not just the unrotated element bounds.
        for (var entry : model.getAsJsonArray("elements")) {
            var element = entry.getAsJsonObject();
            var from = element.getAsJsonArray("from");
            var to = element.getAsJsonArray("to");
            for (int corner = 0; corner < 4; corner++) {
                double x = (corner % 2 == 0 ? from : to).get(0).getAsDouble();
                double y = (corner < 2 ? from : to).get(1).getAsDouble();
                if (element.has("rotation")) {
                    var rotation = element.getAsJsonObject("rotation");
                    assertEquals("z", rotation.get("axis").getAsString());
                    double ox = rotation.getAsJsonArray("origin").get(0).getAsDouble();
                    double oy = rotation.getAsJsonArray("origin").get(1).getAsDouble();
                    double angle = Math.toRadians(rotation.get("angle").getAsDouble());
                    x = ox + (x - ox) * Math.cos(angle) - (y - oy) * Math.sin(angle);
                }
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
            }
        }
        assertEquals(14.0, maxX - minX, 0.0001, "Tablet must remain 14 pixels wide");
        assertTrue(minX >= 0 && maxX <= 16, "Model must stay inside its own cell");
        int checked = 0;
        // All room records, including future rooms, plus every supported generation title.
        for (var entry : layout.getAsJsonArray("rooms")) {
            var room = entry.getAsJsonObject();
            String id = room.get("id").getAsString();
            int roomIndex = Integer.parseInt(id.substring(id.lastIndexOf(".r") + 2));
            int floor = room.get("floor").getAsInt() + 1;
            String number = Integer.toString(floor * 100 + roomIndex);
            checked++;
            for (String generation : new String[]{"dorm1", "dorm2", "dorm3", "elders"}) {
                var text = RoomPlateText.resolve(true, "murim.sect.way." + generation + "|murim.sect.way.room|" + number);
                assertEquals("Room " + number, text.title());
                assertTrue((maxX - minX) / 16.0 <= 1.0, text.toString());
                // Conservative six-pixel glyph advances avoid needing a running client/font atlas.
                assertTextFits(text.title(), true);
                assertTextFits(text.name(), false);
                var stairs = RoomPlateText.resolve(true, "murim.sect.way." + generation + "|murim.sect.way.floor_stairs|" + floor);
                assertTextFits(stairs.title(), true);
            }
        }
        assertTrue(checked > 0, "Sect room data must actually be exercised");
        assertEquals("1st Generation", RoomPlateText.resolve(true, "murim.sect.way.dorm1").name());
        assertEquals("Elders", RoomPlateText.resolve(true, "murim.sect.way.elders").name());
    }

    private static void assertTextFits(String text, boolean main) {
        float width = text.length() * 6.0F;
        assertTrue(width * SectNamePlateRenderer.textScale(width, main) <= 11.0F / 16.0F + 0.00001F,
                "Ink must fit inside the frame: " + text);
    }

    private static JsonObject resource(String path) throws Exception {
        try (var stream = RoomPlateTextTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, path);
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }
}
