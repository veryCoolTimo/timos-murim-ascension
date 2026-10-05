package io.github.verycooltimo.murim.entity.boss;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Хозяин крепости (docs/design/26-boss.md): договор таймингов с анимациями и правила боя. */
class BossMoveTest {

    private static JsonObject clips() throws IOException {
        Path p = Path.of("src/main/resources/assets/murim/bedrock/fortress_master.animation.json");
        if (!Files.exists(p)) {
            p = Path.of("../../src/main/resources/assets/murim/bedrock/fortress_master.animation.json");
        }
        return JsonParser.parseString(Files.readString(p)).getAsJsonObject().getAsJsonObject("animations");
    }

    private static int ticks(JsonObject clips, String name) {
        JsonObject c = clips.getAsJsonObject("animation.fortress_master." + name);
        if (c == null) {
            throw new AssertionError("нет клипа " + name);
        }
        return Math.round(c.get("animation_length").getAsFloat() * 20.0F);
    }

    @Test
    @DisplayName("Длина клипов замаха, удара и отката равна таймингам приёма")
    void clipsMatchTimings() throws IOException {
        JsonObject c = clips();
        for (BossMove m : BossMove.ALL) {
            assertEquals(m.windup(), ticks(c, m.windupClip()), m.windupClip());
            assertEquals(m.strike(), ticks(c, m.strikeClip()), m.strikeClip());
            assertEquals(m.recover(), ticks(c, m.recoverClip()), m.recoverClip());
        }
        assertEquals(BossMove.STAGGER_TICKS, ticks(c, "stagger"));
        assertEquals(BossMove.DOWNED_TICKS, ticks(c, "downed"));
        assertEquals(BossMove.RISE_TICKS, ticks(c, "rise"));
        assertEquals(BossMove.DEATH_TICKS, ticks(c, "death"));
        for (String loop : new String[] {"idle", "walk", "run", "sit", "stun", "hit"}) {
            assertTrue(ticks(c, loop) > 0, loop);
        }
    }

    @Test
    @DisplayName("Окна отката (codex): обычные ≥ 30 т, тяжёлые ≥ 40 т; удары связки внутри удара")
    void windows() {
        for (BossMove m : BossMove.ALL) {
            // Рык — не удар по площади, а волна с давлением: его окно короче.
            assertTrue(m.recover() >= (m.heavy() && m != BossMove.ROAR ? 40 : 20), m.key());
        }
        assertTrue(BossMove.CHAIN.recover() >= 30);
        for (int i = 1; i < BossMove.CHAIN_HITS.length; i++) {
            assertTrue(BossMove.CHAIN_HITS[i] - BossMove.CHAIN_HITS[i - 1] >= 10, "паузы связки ≥ 10 т");
        }
        assertTrue(BossMove.CHAIN_HITS[BossMove.CHAIN_HITS.length - 1] < BossMove.CHAIN.strike());
        // Рык: из радиуса 5 за время вдоха выходит обычный шаг (4,3 бл/с) даже под давлением 0,3.
        double walk = 4.3D * (1.0D - 0.85D * 0.3D);
        assertTrue(walk * BossMove.ROAR.windup() / 20.0D > BossMove.ROAR_RADIUS);
        // Прыжок: из кольца радиусом 3 за время полёта выходит обычный шаг.
        assertTrue(4.3D * BossMove.POUNCE.strike() / 20.0D > BossMove.POUNCE_RADIUS * 1.5D);
    }

    @Test
    @DisplayName("Фазы по здоровью: 60 % и 25 %")
    void phases() {
        assertEquals(1, BossRules.phase(1.0F));
        assertEquals(1, BossRules.phase(0.61F));
        assertEquals(2, BossRules.phase(0.60F));
        assertEquals(2, BossRules.phase(0.26F));
        assertEquals(3, BossRules.phase(0.25F));
        assertEquals(3, BossRules.phase(0.01F));
    }

    @Test
    @DisplayName("Геометрия: дуга спереди, кольцо, полоса, раскол бьёт под фронтом волны")
    void geometry() {
        // yaw 0 — смотрит на юг (+Z).
        assertTrue(BossRules.inArc(0.0D, 0.0D, 2.5D, 0.0D, 3.2D, 90.0D));
        assertFalse(BossRules.inArc(0.0D, 0.0D, -2.5D, 0.0D, 3.2D, 90.0D), "за спиной");
        assertFalse(BossRules.inArc(2.5D, 0.0D, 0.5D, 0.0D, 3.2D, 60.0D), "сбоку вне узкой дуги");
        assertTrue(BossRules.inArc(2.5D, 0.0D, 0.5D, 0.0D, 3.2D, 160.0D), "наотмашь достаёт бок");
        assertTrue(BossRules.inRing(2.0D, 0.0D, 2.0D, 3.0D));
        assertFalse(BossRules.inRing(2.5D, 0.0D, 2.5D, 3.0D));
        assertTrue(BossRules.onLane(0.5D, 0.0D, 5.0D, 0.0D, 0.0D, 7.0D, 1.3D));
        assertFalse(BossRules.onLane(2.0D, 0.0D, 5.0D, 0.0D, 0.0D, 7.0D, 1.3D), "шаг вбок уводит с полосы");
        // Раскол: центральная линия на цель; точка между линиями (15°) безопасна.
        assertTrue(BossRules.splitHits(0.0D, 0.0D, 6.0D, 0.0D, 5, 12));
        double a = Math.toRadians(15.0D);
        assertFalse(BossRules.splitHits(-Math.sin(a) * 6.0D, 0.0D, Math.cos(a) * 6.0D, 0.0D, 5, 12), "между линиями");
        // До фронта волна не достаёт.
        assertFalse(BossRules.splitHits(0.0D, 0.0D, 12.0D, 0.0D, 2, 12));
    }

    @Test
    @DisplayName("Полоса не выходит за плац")
    void laneClamped() {
        double len = BossRules.laneLength(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 12.5D, 1.0D, 30.0D);
        assertEquals(11.5D, len, 1.0E-6);
        assertEquals(0.0D, BossRules.laneLength(0.0D, 11.6D, 0.0D, 0.0D, 0.0D, 12.5D, 1.0D, 30.0D), 1.0E-6);
        assertEquals(14.0D, BossRules.laneLength(0.0D, -11.0D, 0.0D, 0.0D, 0.0D, 12.5D, 1.0D, 14.0D), 1.0E-6);
    }

    @Test
    @DisplayName("Выбор приёма: вблизи связка, издали таран, в фазе 2 раскол и рык, в фазе 3 вихрь")
    void choose() {
        boolean[] all = {true, true, true, true, true, true};
        assertEquals(BossMove.CHAIN, BossRules.choose(2.0D, 1, all, 0.9F));
        assertEquals(BossMove.RAM, BossRules.choose(9.0D, 1, all, 0.9F));
        assertEquals(BossMove.POUNCE, BossRules.choose(6.0D, 1, all, 0.3F));
        assertNull(BossRules.choose(6.0D, 1, all, 0.9F), "средняя дистанция без прыжка — сближаться");
        assertEquals(BossMove.ROAR, BossRules.choose(4.0D, 2, all, 0.9F));
        boolean[] noRoar = {true, true, true, false, true, true};
        assertEquals(BossMove.SPLIT, BossRules.choose(6.0D, 2, noRoar, 0.5F));
        assertEquals(BossMove.WHIRL, BossRules.choose(6.0D, 3, all, 0.2F));
        boolean[] none = {true, false, false, false, false, false};
        assertNull(BossRules.choose(9.0D, 3, none, 0.1F));
    }
}
