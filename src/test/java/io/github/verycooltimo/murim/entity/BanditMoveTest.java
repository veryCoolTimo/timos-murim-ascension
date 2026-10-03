package io.github.verycooltimo.murim.entity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Удары бандита-мечника: договор таймингов с анимациями и геометрия попадания. */
class BanditMoveTest {

    private static JsonObject clips() throws IOException {
        Path p = Path.of("src/main/resources/assets/murim/bedrock/bandit.animation.json");
        if (!Files.exists(p)) {
            p = Path.of("../../src/main/resources/assets/murim/bedrock/bandit.animation.json");
        }
        return JsonParser.parseString(Files.readString(p)).getAsJsonObject().getAsJsonObject("animations");
    }

    private static int ticks(JsonObject clips, String name) {
        return Math.round(clips.getAsJsonObject("animation.bandit." + name).get("animation_length").getAsFloat() * 20.0F);
    }

    @Test
    @DisplayName("Длина клипов замаха, удара и отката равна таймингам удара")
    void clipsMatchTimings() throws IOException {
        JsonObject c = clips();
        for (BanditMove m : BanditMove.ALL) {
            assertEquals(m.windup(), ticks(c, "windup" + m.clip()), "windup" + m.clip());
            assertEquals(m.strike(), ticks(c, "attack" + m.clip()), "attack" + m.clip());
            assertEquals(m.recover(), ticks(c, "recover" + m.clip()), "recover" + m.clip());
            assertTrue(m.hitTick() < m.strike());
        }
        assertEquals(BanditMove.STAGGER_TICKS, ticks(c, "stagger"));
    }

    @Test
    @DisplayName("Рубящий срывает технику с порогом 6, горизонтальный — нет (сложность «Нормально»)")
    void interruptThreshold() {
        assertTrue(BanditMove.CHOP.damage() >= 6.0F);
        assertTrue(BanditMove.SWEEP.damage() < 6.0F);
    }

    @Test
    @DisplayName("Удар попадает только спереди, в пределах дальности и высоты")
    void reaches() {
        // Бандит смотрит на юг (+Z, yaw 0).
        assertTrue(BanditMove.CHOP.reaches(0.0D, 0.0D, 2.0D, 0.0F));
        assertFalse(BanditMove.CHOP.reaches(0.0D, 0.0D, -2.0D, 0.0F), "за спиной");
        assertFalse(BanditMove.CHOP.reaches(0.0D, 0.0D, 3.5D, 0.0F), "далеко");
        assertFalse(BanditMove.CHOP.reaches(0.0D, 2.5D, 1.5D, 0.0F), "высоко");
        assertTrue(BanditMove.SWEEP.reaches(2.0D, 0.0D, 0.6D, 0.0F), "широкий захватывает бок");
        assertFalse(BanditMove.THRUST.reaches(2.0D, 0.0D, 0.6D, 0.0F), "укол узкий");
        assertTrue(BanditMove.THRUST.reaches(0.0D, 0.0D, 3.2D, 0.0F), "укол дальний");
    }

    @Test
    @DisplayName("Выбор удара покрывает все три")
    void pick() {
        assertEquals(BanditMove.CHOP, BanditMove.pick(0.1F));
        assertEquals(BanditMove.SWEEP, BanditMove.pick(0.5F));
        assertEquals(BanditMove.THRUST, BanditMove.pick(0.9F));
    }
}
