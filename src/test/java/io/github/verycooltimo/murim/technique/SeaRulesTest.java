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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** «Море Цветущей Сливы»: шкала совпадает с JSON и анимацией, сектор, захват, слои. */
class SeaRulesTest {

    private static JsonObject json(String path) throws Exception {
        try (InputStream in = SeaRulesTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    void timelineMatchesDataAndAnimation() throws Exception {
        TechniqueDefinition def = TechniqueDefinition.CODEC.parse(JsonOps.INSTANCE,
                json("/data/murim/murim_techniques/twenty_four_plum_sea.json")).getOrThrow(AssertionError::new);
        assertEquals(SeaRules.RELEASE, def.startTickOf(TechniquePhase.IMPACT));
        // JSON вмещает самое длинное Море: окно + продление + мираж.
        assertEquals(SeaRules.END, def.totalTicks());
        assertTrue(def.cooldownTicks() >= def.totalTicks());
        assertTrue(def.interruption().damageThreshold() >= 8.0F);
        // Основная анимация держит позу до последнего возможного миража, конец — 1 с.
        double main = json("/assets/murim/player_animations/twenty_four_plum_sea.json").getAsJsonObject("animations")
                .getAsJsonObject("twenty_four_plum_sea").get("animation_length").getAsDouble();
        assertEquals(SeaRules.HOLD_END + SeaRules.EXTEND_MAX, (int) Math.round(main * 20.0D));
        double end = json("/assets/murim/player_animations/twenty_four_plum_sea_end.json").getAsJsonObject("animations")
                .getAsJsonObject("twenty_four_plum_sea_end").get("animation_length").getAsDouble();
        assertEquals(SeaRules.MELT, (int) Math.round(end * 20.0D));
        assertTrue(SeaRules.BUD + SeaRules.BUD_OPEN <= SeaRules.RELEASE && SeaRules.SWEEP_END < SeaRules.TIDE_END);
    }

    @Test
    void seaGrowsFromNothingToFull() {
        assertEquals(0.0D, SeaRules.grown(SeaRules.RELEASE - 1));
        assertEquals(1.0D, SeaRules.grown(SeaRules.TIDE_END), 1.0E-9D);
        double prev = 0.0D;
        for (int t = SeaRules.RELEASE; t <= SeaRules.TIDE_END; t++) {
            assertTrue(SeaRules.grown(t) >= prev);
            prev = SeaRules.grown(t);
        }
    }

    @Test
    void axisTurnsNoFasterThanLimit() {
        Vec3 axis = new Vec3(0.0D, 0.0D, 1.0D);
        Vec3 next = SeaRules.turn(axis, new Vec3(1.0D, 0.0D, 0.0D));
        double deg = Math.toDegrees(Math.acos(next.dot(axis)));
        assertEquals(SeaRules.TURN, deg, 1.0E-6D);
        Vec3 near = new Vec3(Math.sin(Math.toRadians(2.0D)), 0.0D, Math.cos(Math.toRadians(2.0D)));
        assertEquals(1.0D, SeaRules.turn(axis, near).dot(near), 1.0E-9D);
    }

    @Test
    void capturesOnlyIncomingThreatsInsideSector() {
        Vec3 chest = new Vec3(0.0D, 1.2D, 0.0D);
        Vec3 axis = new Vec3(0.0D, 0.0D, 1.0D);
        // Стрела в лоб с 5 блоков — ловится.
        assertTrue(SeaRules.entering(7, chest, axis, new Vec3(0.0D, 1.3D, 5.0D), new Vec3(0.0D, 0.0D, -2.0D), 1.0D));
        // Улетает от мастера — нет.
        assertFalse(SeaRules.entering(7, chest, axis, new Vec3(0.0D, 1.3D, 5.0D), new Vec3(0.0D, 0.0D, 2.0D), 1.0D));
        // Пролёт мимо в 4 блоках сбоку — нет.
        assertFalse(SeaRules.entering(7, chest, axis, new Vec3(4.0D, 1.3D, 5.0D), new Vec3(0.0D, 0.0D, -2.0D), 1.0D));
        // Сзади — нет; за радиусом — нет; поле ещё не выросло — нет.
        assertFalse(SeaRules.entering(7, chest, axis, new Vec3(0.0D, 1.3D, -5.0D), new Vec3(0.0D, 0.0D, 2.0D), 1.0D));
        assertFalse(SeaRules.entering(7, chest, axis, new Vec3(0.0D, 1.3D, 12.0D), new Vec3(0.0D, 0.0D, -2.0D), 1.0D));
        assertFalse(SeaRules.entering(7, chest, axis, new Vec3(0.0D, 1.3D, 5.0D), new Vec3(0.0D, 0.0D, -2.0D), 0.2D));
        // Быстрая стрела, перескочившая край за тик, ловится по отрезку пути.
        assertTrue(SeaRules.entering(7, chest, axis, new Vec3(0.0D, 1.3D, 7.6D), new Vec3(0.0D, 0.0D, -3.0D), 1.0D));
        // Сверху (цель на крыше) — 3D-конус.
        Vec3 up = new Vec3(0.0D, 0.6D, 0.8D).normalize();
        assertTrue(SeaRules.entering(7, chest, up, new Vec3(0.0D, 5.0D, 4.0D), new Vec3(0.0D, -1.0D, -1.0D), 1.0D));
    }

    @Test
    void capturedProjectileHangsThenFalls() {
        Vec3 v = new Vec3(0.0D, 0.0D, -2.5D);
        Vec3 pos = Vec3.ZERO;
        for (int age = 0; age < SeaRules.HANG; age++) {
            v = SeaRules.brake(v, age);
            pos = pos.add(v);
        }
        // Висит: за 3 тика торможения проходит меньше 2 блоков и почти не падает.
        assertTrue(pos.length() < 2.0D && Math.abs(pos.y) < 1.0E-9D);
        for (int age = SeaRules.HANG; age < SeaRules.HANG + 6; age++) {
            v = SeaRules.brake(v, age);
            pos = pos.add(v);
        }
        // Потом падает: 6 тиков — больше блока вниз.
        assertTrue(pos.y < -1.2D);
    }

    @Test
    void layersGrowAndZeroIsTraining() {
        assertEquals(0.0D, SeaRules.density(0));
        assertEquals(1, SeaRules.captures(0));
        assertFalse(SeaRules.petals(0) || SeaRules.petals(2));
        assertTrue(SeaRules.petals(3));
        assertFalse(SeaRules.extendable(5));
        assertTrue(SeaRules.extendable(6));
        assertEquals(4, SeaRules.captures(7));
        for (int l = 1; l < 8; l++) {
            assertTrue(SeaRules.radius(l + 1) >= SeaRules.radius(l));
            assertTrue(SeaRules.captures(l + 1) >= SeaRules.captures(l));
        }
    }
}
