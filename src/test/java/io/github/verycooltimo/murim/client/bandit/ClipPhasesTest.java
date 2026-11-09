package io.github.verycooltimo.murim.client.bandit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.verycooltimo.murim.client.bandit.ClipPhases.NEVER;
import static io.github.verycooltimo.murim.client.bandit.ClipPhases.Phase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Время клипа по фазе боя, выбор клипа лука и договор длин клипов с константами. */
class ClipPhasesTest {

    private static JsonObject anims(String file) throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("src/main/resources/assets/murim/bedrock/" + file))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IOException("source tree not found for " + file);
        }
        return JsonParser.parseString(Files.readString(dir.resolve("src/main/resources/assets/murim/bedrock/" + file)))
                .getAsJsonObject().getAsJsonObject("animations");
    }

    private static float length(JsonObject a, String prefix, String name) {
        return a.getAsJsonObject("animation." + prefix + "." + name).get("animation_length").getAsFloat();
    }

    @Test
    @DisplayName("Дуэльное сжатие сохраняет доли клипа внутри фазы: ключ удара остаётся на своей доле")
    void clipTimeKeepsProportions() {
        // Удар 6 тиков клипа, в бою 2 тика, ключ попадания на 3-м тике клипа = 50 % фазы.
        assertEquals(3.0F, ClipPhases.clipTime(1.0F, 6, 2), 1e-5);
        assertEquals(0.0F, ClipPhases.clipTime(0.0F, 6, 2), 1e-5);
        // Замах 10 -> 6: доля 0,6 фазы = 6-й тик клипа.
        assertEquals(6.0F, ClipPhases.clipTime(3.6F, 10, 6), 1e-5);
        // Фаза без сжатия идёт как есть.
        assertEquals(4.5F, ClipPhases.clipTime(4.5F, 12, 12), 1e-5);
        // Сервер задержал смену фазы: клип не уходит за свой конец, назад не идёт.
        assertEquals(6.0F, ClipPhases.clipTime(9.0F, 6, 2), 1e-5);
        assertEquals(0.0F, ClipPhases.clipTime(-1.0F, 6, 2), 1e-5);
    }

    @Test
    @DisplayName("Лучник: натяжение, удержание, выстрел, опускание, покой и оглушение")
    void archerPhases() {
        assertEquals(Phase.DRAW, ClipPhases.pick(false, true, 3, NEVER, NEVER).phase());
        assertEquals("draw", ClipPhases.pick(false, true, 3, NEVER, NEVER).clip());
        ClipPhases.Pose hold = ClipPhases.pick(false, true, 12, NEVER, NEVER);
        assertEquals(Phase.HOLD, hold.phase());
        assertEquals(4.0F, hold.ticks(), 1e-5);
        // Выстрел: конец натяжения и событие выстрела в один тик.
        assertEquals(Phase.SHOOT, ClipPhases.pick(false, false, 0, 1, 1).phase());
        ClipPhases.Pose lower = ClipPhases.pick(false, false, 0, 5, 5);
        assertEquals(Phase.LOWER, lower.phase());
        assertEquals(2.0F, lower.ticks(), 1e-5);
        assertEquals(Phase.NONE, ClipPhases.pick(false, false, 0, 11, 11).phase());
        // Отмена натяжения без выстрела: сразу опускание.
        assertEquals(Phase.LOWER, ClipPhases.pick(false, false, 0, NEVER, 2).phase());
        assertEquals(Phase.NONE, ClipPhases.pick(false, false, 0, NEVER, 9).phase());
        // Старый выстрел не мешает новому натяжению; оглушение гасит лук.
        assertEquals(Phase.DRAW, ClipPhases.pick(false, true, 0, 30, 30).phase());
        assertEquals(Phase.NONE, ClipPhases.pick(true, true, 12, 1, 1).phase());
        assertNull(ClipPhases.pick(false, false, 0, NEVER, NEVER).clip());
    }

    @Test
    @DisplayName("Вес позы лука: 0 -> 1 при натяжении, 1 -> 0 при опускании")
    void archerWeight() {
        assertEquals(0.0F, ClipPhases.pick(false, true, 0, NEVER, NEVER).weight(), 1e-5);
        assertEquals(1.0F, ClipPhases.pick(false, true, 4, NEVER, NEVER).weight(), 1e-5);
        assertEquals(1.0F, ClipPhases.pick(false, false, 0, 1, 1).weight(), 1e-5);
        assertEquals(1.0F, ClipPhases.pick(false, false, 0, 3, 3).weight(), 1e-5);
        assertEquals(0.5F, ClipPhases.pick(false, false, 0, 7, 7).weight(), 1e-5);
    }

    @Test
    @DisplayName("Длины клипов лучника равны константам фаз; у обоих рига есть клип death 0,8 с")
    void clipContract() throws IOException {
        JsonObject a = anims("bandit_archer.animation.json");
        assertEquals(ClipPhases.DRAW_TICKS, Math.round(length(a, "bandit_archer", "draw") * 20.0F));
        assertEquals(ClipPhases.SHOOT_TICKS, Math.round(length(a, "bandit_archer", "shoot") * 20.0F));
        assertEquals(ClipPhases.LOWER_TICKS, Math.round(length(a, "bandit_archer", "lower") * 20.0F));
        assertTrue(a.has("animation.bandit_archer.hold"));
        assertEquals(0.8F, length(a, "bandit_archer", "death"), 1e-5);
        assertEquals(0.8F, length(anims("bandit.animation.json"), "bandit", "death"), 1e-5);
    }

    @Test
    @DisplayName("Замахи мечника: смещение пояса вниз-назад 0,06-0,1 блока (0,96-1,6 px)")
    void anticipationOffset() throws IOException {
        JsonObject b = anims("bandit.animation.json");
        for (String suffix : new String[] {"", "_sweep", "_thrust", "_dash"}) {
            JsonObject waist = b.getAsJsonObject("animation.bandit.windup" + suffix).getAsJsonObject("bones")
                    .getAsJsonObject("waist");
            JsonObject pos = waist.getAsJsonObject("position");
            JsonArray end = null;
            float last = -1;
            for (String k : pos.keySet()) {
                if (Float.parseFloat(k) > last) {
                    last = Float.parseFloat(k);
                    end = pos.getAsJsonArray(k);
                }
            }
            float down = -end.get(1).getAsFloat();
            float back = end.get(2).getAsFloat();
            assertTrue(down >= 0.96F && down <= 1.6F, "down " + suffix + " " + down);
            assertTrue(back >= 0.96F && back <= 1.6F, "back " + suffix + " " + back);
        }
    }
}
