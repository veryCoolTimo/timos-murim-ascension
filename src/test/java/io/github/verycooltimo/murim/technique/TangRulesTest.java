package io.github.verycooltimo.murim.technique;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.combat.TechniquePhase;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Скрытое Оружие Клана Тан: шкалы совпадают с JSON и анимациями, правила форм — со спецификацией. */
class TangRulesTest {

    private static JsonObject json(String path) throws Exception {
        try (InputStream in = TangRulesTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    void timelinesMatchDataAndAnimations() throws Exception {
        for (int form = 0; form < 7; form++) {
            String id = TangRules.technique(form).getPath();
            TechniqueDefinition def = TechniqueDefinition.CODEC.parse(JsonOps.INSTANCE,
                    json("/data/murim/murim_techniques/" + id + ".json")).getOrThrow(AssertionError::new);
            assertEquals(form, TangRules.form(def.id()));
            // IMPACT — выпуск кинжалов: начало совпадает с подготовкой формы.
            assertEquals(TangRules.windup(form), def.startTickOf(TechniquePhase.IMPACT), id);
            double seconds = json("/assets/murim/player_animations/" + id + ".json").getAsJsonObject("animations")
                    .getAsJsonObject(id).get("animation_length").getAsDouble();
            assertEquals(def.totalTicks(), (int) Math.round(seconds * 20.0D), id);
            // Удар моба в подготовке не сбивает; кулдауны 8–20 с.
            assertTrue(def.interruption().damageThreshold() >= 6.0F, id);
            assertTrue(def.cooldownTicks() >= 160 && def.cooldownTicks() <= 400, id);
        }
    }

    @Test
    void fiveLeaveAlmostAtOnceFromDifferentSides() {
        // Автор 03.10: «почти одновременно… немного из разных сторон».
        for (int layer = 0; layer <= 8; layer++) {
            assertTrue(TangRules.fiveRelease(TangRules.fiveCount(layer) - 1, layer) <= 4);
        }
        assertEquals(1, TangRules.fiveCount(0));
        assertEquals(5, TangRules.fiveCount(7));
        for (int i = 0; i < 5; i++) {
            for (int j = i + 1; j < 5; j++) {
                double[] a = TangRules.FIVE_FAN[i];
                double[] b = TangRules.FIVE_FAN[j];
                assertTrue(Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) >= 10.0D, "веер: разные стороны");
            }
        }
    }

    @Test
    void starsLeaveTheFrontOpen() {
        // Гл. 195: отступить — смерть, шаг к мастеру — уйти. Ни одна звезда не стоит между целью и мастером.
        Vec3 back = new Vec3(0.0D, 0.0D, 1.0D);
        for (int k = 0; k < 7; k++) {
            Vec3 o = TangRules.starOffset(k, back, false);
            Vec3 flat = new Vec3(o.x, 0.0D, o.z).normalize();
            assertTrue(flat.dot(back) > Math.cos(Math.toRadians(125.0D)), "звезда " + k + " перекрывает путь к мастеру");
            assertEquals(TangRules.STAR_RADIUS, Math.hypot(o.x, o.z), 1.0E-6D);
        }
        // Телеграф до схождения и показ полной сети.
        assertTrue(TangRules.STAR_STRIKE - TangRules.STAR_FLARE >= 6);
        assertTrue(TangRules.STAR_FLARE - TangRules.STAR_SET >= 16);
    }

    @Test
    void darkBurstTelegraphsAndRecallIsWeaker() {
        assertTrue(TangRules.carpAmplitude(TangRules.CARP_TICKS) > 4.0D * TangRules.carpAmplitude(0));
        assertTrue(TangRules.CARP_MIN >= 16);
        // Намеренный промах ради отзыва невыгоден: отзыв слабее прямого попадания со взрывом.
        assertTrue(TangRules.RECALL_DMG < TangRules.BURST_DMG + TangRules.SPLASH_DMG);
    }

    @Test
    void layerZeroHasNoEffectsAndPowerGrows() {
        assertEquals(0.0D, TangRules.density(0));
        assertTrue(TangRules.power(8) > TangRules.power(7) && TangRules.power(7) == 1.0D);
        assertEquals(1.0D, TangRules.falloff(10.0D));
        assertEquals(0.6D, TangRules.falloff(40.0D));
    }
}
