package io.github.verycooltimo.murim.technique;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.combat.TechniquePhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверяет сами файлы датапака, а не их копию в коде.
 *
 * <p>Без этого теста шкала, продублированная в юнит-тестах, могла бы разойтись с JSON,
 * и тесты остались бы зелёными при сломанной технике в игре. Здесь читаются те же файлы,
 * которые попадают в jar.
 */
class TechniqueDataTest {

    private static final String ROOT = "/data/murim/murim_techniques/";

    private static TechniqueDefinition load(String name) {
        try (InputStream stream = TechniqueDataTest.class.getResourceAsStream(ROOT + name + ".json")) {
            assertNotNull(stream, "Файл техники не найден: " + name);
            JsonElement json = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            return TechniqueDefinition.CODEC.parse(JsonOps.INSTANCE, json)
                    .getOrThrow(error -> new AssertionError(name + ": " + error));
        } catch (java.io.IOException exception) {
            throw new AssertionError("Не прочитан файл техники " + name, exception);
        }
    }

    @Test
    @DisplayName("Все техники датапака читаются схемой без ошибок")
    void allTechniquesParse() {
        for (String name : List.of("ceremonial_draw", "wedge_fan", "shadow_step")) {
            TechniqueDefinition definition = load(name);
            assertEquals("murim:" + name, definition.id().toString(),
                    "Идентификатор внутри файла обязан совпадать с именем файла");
        }
    }

    @Test
    @DisplayName("Церемониальный выхват в датапаке имеет ту же шкалу, что и юнит-тесты")
    void ceremonialDrawTimelineMatches() {
        TechniqueDefinition draw = load("ceremonial_draw");
        assertEquals(60, draw.ticksOf(TechniquePhase.RITUAL));
        assertEquals(14, draw.ticksOf(TechniquePhase.WINDUP));
        assertEquals(92, draw.totalTicks());
        assertEquals(74, draw.startTickOf(TechniquePhase.IMPACT));
    }

    @Test
    @DisplayName("У каждой техники кулдаун не короче её собственной длительности")
    void cooldownCoversTechnique() {
        for (String name : List.of("ceremonial_draw", "wedge_fan", "shadow_step")) {
            TechniqueDefinition definition = load(name);
            assertTrue(definition.cooldownTicks() >= definition.totalTicks(),
                    name + ": кулдаун короче техники, второй запуск затрёт первый");
        }
    }

    @Test
    @DisplayName("Три техники используют три разных типа воздействия")
    void behavioursDiffer() {
        // Смысл этапа 1 — проверить, что схема тянет разные виды техник, а не три копии
        // одного взмаха с другими числами.
        assertEquals(TechniqueBehavior.MELEE_ARC, load("ceremonial_draw").behavior().type());
        assertEquals(TechniqueBehavior.PROJECTILE_FAN, load("wedge_fan").behavior().type());
        assertEquals(TechniqueBehavior.DASH, load("shadow_step").behavior().type());
    }

    @Test
    @DisplayName("Формула из книги заполнена у каждой техники: форма, намерение, дыхание")
    void compositionFilled() {
        for (String name : List.of("ceremonial_draw", "wedge_fan", "shadow_step")) {
            TechniqueDefinition definition = load(name);
            assertTrue(!definition.form().isBlank(), name + ": пустая форма");
            assertTrue(!definition.intent().isBlank(), name + ": пустое намерение");
            assertTrue(!definition.breath().isBlank(), name + ": пустое дыхание");
        }
    }
}
