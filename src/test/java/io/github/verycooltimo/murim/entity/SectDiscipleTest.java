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
}
