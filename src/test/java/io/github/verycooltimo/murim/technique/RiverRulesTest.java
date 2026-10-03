package io.github.verycooltimo.murim.technique;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.verycooltimo.murim.combat.TechniquePhase;
import com.mojang.serialization.JsonOps;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Шкала и правила «Опадающих Лепестков, Перекрывающих Реку»: тайминги совпадают с JSON и анимацией. */
class RiverRulesTest {

    private static JsonObject json(String path) throws Exception {
        try (InputStream in = RiverRulesTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    void timelineMatchesDataAndAnimation() throws Exception {
        TechniqueDefinition def = TechniqueDefinition.CODEC.parse(JsonOps.INSTANCE,
                json("/data/murim/murim_techniques/twenty_four_plum_river.json")).getOrThrow(AssertionError::new);
        // Прицел фиксируется в начале IMPACT; длина техники = длина анимации.
        assertEquals(RiverRules.AIM, def.startTickOf(TechniquePhase.IMPACT));
        assertEquals(RiverRules.END, def.totalTicks());
        assertTrue(def.cooldownTicks() >= def.totalTicks());
        double seconds = json("/assets/murim/player_animations/twenty_four_plum_river.json").getAsJsonObject("animations")
                .getAsJsonObject("twenty_four_plum_river").get("animation_length").getAsDouble();
        assertEquals(RiverRules.END, (int) Math.round(seconds * 20.0D));
        assertTrue(RiverRules.AIM < RiverRules.RELEASE && RiverRules.CONTACT_MAX < RiverRules.BURST);
        // Последняя пауза перед взрывом — настоящая.
        assertTrue(RiverRules.BURST - RiverRules.CONTACT_MAX >= 4);
    }

    @Test
    void contactNeverLaterThanFarthest() {
        assertEquals(RiverRules.RELEASE + 3, RiverRules.contact(0.0D));
        assertEquals(RiverRules.CONTACT_MAX, RiverRules.contact(RiverRules.RANGE));
        for (double d = 0.0D; d <= 40.0D; d += 0.5D) {
            assertTrue(RiverRules.contact(d) <= RiverRules.CONTACT_MAX);
        }
    }

    @Test
    void petalsAppearLoneFirstThenMany() {
        // Первая проводка: только одиночные лепестки, «множество» — со второй.
        assertEquals(0.0D, RiverRules.born(RiverRules.STROKES[0][1]));
        assertTrue(RiverRules.born(RiverRules.STROKES[1][1]) > 0.25D);
        assertEquals(1.0D, RiverRules.born(RiverRules.GATHER));
        double prev = 0.0D;
        for (int t = 0; t <= RiverRules.GATHER; t++) {
            assertTrue(RiverRules.born(t) >= prev);
            prev = RiverRules.born(t);
        }
        assertEquals(900, RiverRules.petals(7));
        assertEquals(0, RiverRules.petals(2));
    }

    @Test
    void areaDamageFallsToHalf() {
        assertEquals(3.0D, RiverRules.areaShare(0.0D), 1.0E-9D);
        assertEquals(1.5D, RiverRules.areaShare(RiverRules.BURST_RADIUS), 1.0E-9D);
        assertTrue(RiverRules.DMG_PRIMARY > RiverRules.DMG_AREA);
    }

    @Test
    void pushIsCappedAndResisted() {
        Vec3 v = RiverRules.push(new Vec3(1.0D, 0.0D, 0.0D), 5.0D, 0.0D);
        assertTrue(v.length() <= RiverRules.PUSH_MAX + 1.0E-9D);
        assertTrue(v.y > 0.0D, "горизонтальный удар подбрасывает");
        assertEquals(0.0D, RiverRules.push(new Vec3(1.0D, 0.0D, 0.0D), 1.0D, 1.0D).length(), 1.0E-9D);
    }

    @Test
    void layersGrow() {
        assertTrue(RiverRules.strands(7) > RiverRules.strands(3));
        assertTrue(RiverRules.rays(7) == 8 && RiverRules.rays(1) == 0);
        assertTrue(RiverRules.power(8) > RiverRules.power(7));
        assertTrue(RiverRules.caption(3) && !RiverRules.caption(2));
    }
}
