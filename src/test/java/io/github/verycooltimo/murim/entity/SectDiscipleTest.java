package io.github.verycooltimo.murim.entity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Старший ученик Хуашань: спарринг без смерти и договор с файлами анимаций. */
class SectDiscipleTest {

    private static Path anim(String name) {
        Path p = Path.of("src/main/resources/assets/murim/player_animations/" + name + ".json");
        return Files.exists(p) ? p : Path.of("../../src/main/resources/assets/murim/player_animations/" + name + ".json");
    }

    @Test
    @DisplayName("Урон в спарринге не опускает здоровье ниже порога")
    void clampKeepsFloor() {
        assertEquals(3.0F, SparEvents.clamp(3.0F, 20.0F, 10.0F), 1e-6);
        assertEquals(2.0F, SparEvents.clamp(8.0F, 12.0F, 10.0F), 1e-6);
        assertEquals(0.0F, SparEvents.clamp(5.0F, 10.0F, 10.0F), 1e-6);
        assertEquals(0.0F, SparEvents.clamp(5.0F, 9.0F, 10.0F), 1e-6);
    }

    @Test
    @DisplayName("Поклон и формы основы есть в player_animations (их играет и NPC)")
    void animationsExist() throws IOException {
        String[] names = {"spar_bow", "six_form_1", "six_form_6", "seven_plum_blossoms", "seven_plum_rush"};
        for (String n : names) {
            Path p = anim(n);
            JsonObject root = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            assertTrue(root.getAsJsonObject("animations").has(n), n);
        }
        JsonObject bow = JsonParser.parseString(Files.readString(anim("spar_bow"))).getAsJsonObject()
                .getAsJsonObject("animations").getAsJsonObject("spar_bow");
        // Длина клипа поклона = BOW_TICKS сервера.
        assertEquals(SectDisciple.BOW_TICKS / 20.0F, bow.get("animation_length").getAsFloat(), 1e-6);
    }

    private static java.util.Map<String, JsonObject> bones(String geo) throws IOException {
        Path p = Path.of("src/main/resources/assets/murim/bedrock/" + geo + ".geo.json");
        if (!Files.exists(p)) {
            p = Path.of("../../src/main/resources/assets/murim/bedrock/" + geo + ".geo.json");
        }
        java.util.Map<String, JsonObject> out = new java.util.HashMap<>();
        for (var e : JsonParser.parseString(Files.readString(p)).getAsJsonObject().getAsJsonArray("minecraft:geometry")
                .get(0).getAsJsonObject().getAsJsonArray("bones")) {
            out.put(e.getAsJsonObject().get("name").getAsString(), e.getAsJsonObject());
        }
        return out;
    }

    @Test
    @DisplayName("Модель ученика: скелет как у бандита (клипы поз и техник), рукоять в ножнах")
    void discipleSkeletonMatchesBandit() throws IOException {
        var disciple = bones("sect_disciple");
        var bandit = bones("bandit");
        // Кости, которые двигают клипы (PalClips.BONES, npc_animations, клипы бандита): те же родители и pivot.
        for (String b : new String[] {"root", "waist", "torso", "head", "arm_r", "arm_l", "leg_r", "leg_l", "skirt_front", "skirt_back"}) {
            assertTrue(disciple.containsKey(b), b);
            assertEquals(String.valueOf(bandit.get(b).get("parent")), String.valueOf(disciple.get(b).get("parent")), b + " parent");
            assertEquals(bandit.get(b).get("pivot"), disciple.get(b).get("pivot"), b + " pivot");
        }
        assertTrue(disciple.containsKey("handle"), "рукоять меча в ножнах");
        assertTrue(!disciple.containsKey("weapon"), "меч в руке — предмет, не кость");
    }
}
