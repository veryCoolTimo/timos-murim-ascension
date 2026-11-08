package io.github.verycooltimo.murim.mastery;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The save-format change of the Apex Echo (approved by the author 19.10): the set of paid-out milestones lives in
 * {@link MasteryState#fired()}; saves from before it decode with an empty set and lose nothing.
 */
class MasteryStateEchoTest {
    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath("murim", name);
    }

    private static MasteryState decode(String json) {
        return MasteryState.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    private static JsonObject encode(MasteryState state) {
        return MasteryState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow().getAsJsonObject();
    }

    /** A save written by the build before the Echo: current version, no {@code fired} key. */
    private static final String OLD_SAVE = """
            {"version": 3, "arts": {
               "murim:twenty_four_plum": {"version": 3, "stars": 9, "progress": 4.5, "unprocessed": 2, "day": 7, "cap": 12},
               "murim:village_brawler_fist": {"version": 3, "stars": 3, "progress": 1, "unprocessed": 0, "day": 7, "cap": 12}
             }, "known": ["murim:twenty_four_plum", "murim:village_brawler_fist"],
             "wisdom": 8, "comprehended": ["murim:twenty_four_plum"]}
            """;

    @Test
    @DisplayName("Старое сохранение читается с пустым множеством, остальное не теряется")
    void oldSaveDecodesWithAnEmptyFiredSet() {
        MasteryState state = decode(OLD_SAVE);
        assertEquals(Set.of(), state.fired());
        assertEquals(9, state.progress(id("twenty_four_plum")).stars());
        // The save is of version 3: the star is kept and the fraction of the star is recounted in the table's units.
        assertEquals(4.5D * MasteryRules.need(9, 12) / MasteryRules.legacyNeed(9),
                state.progress(id("twenty_four_plum")).progress(), 1.0E-9D);
        assertEquals(3, state.progress(id("village_brawler_fist")).stars());
        assertEquals(2, state.techniques().size());
        assertEquals(8.0D, state.wisdom(), 1.0E-9D);
        assertEquals(Set.of(id("twenty_four_plum")), state.comprehended());
        // Рубежи, пройденные до Отклика, не выплачиваются задним числом: сработает только будущий ★12.
        assertFalse(state.hasFired(id("twenty_four_plum"), 1));
        assertEquals(List.of(3), Resonance.firing(id("twenty_four_plum"), 12, List.of(10, 11, 12), state.fired()));
    }

    @Test
    @DisplayName("Сработавшие рубежи сохраняются и читаются обратно")
    void firedRoundTrips() {
        MasteryState state = decode(OLD_SAVE).withFired(id("twenty_four_plum"), List.of(2, 1));
        assertTrue(state.hasFired(id("twenty_four_plum"), 1) && state.hasFired(id("twenty_four_plum"), 2));
        assertFalse(state.hasFired(id("twenty_four_plum"), 3));
        JsonObject saved = encode(state);
        assertEquals(List.of("murim:twenty_four_plum#1", "murim:twenty_four_plum#2"),
                saved.getAsJsonArray("fired").asList().stream().map(e -> e.getAsString()).toList(),
                "порядок в файле стабилен");
        MasteryState back = MasteryState.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
        assertEquals(state, back);
        assertEquals(Set.of("murim:twenty_four_plum#1", "murim:twenty_four_plum#2"), back.fired());
        assertEquals(saved, encode(back), "повторная запись не меняет файл");
    }

    @Test
    @DisplayName("Без сработавших рубежей ключа в файле нет: файл не отличается от старого и читается тем же")
    void emptyFiredRoundTrips() {
        MasteryState state = decode(OLD_SAVE);
        JsonObject saved = encode(state);
        assertFalse(saved.has("fired"), "пустое множество не пишется");
        assertEquals(state, MasteryState.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow());
        assertEquals(MasteryState.EMPTY, MasteryState.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{}")).getOrThrow());
    }

    @Test
    @DisplayName("Рубеж помечается за искусством, а не за формой")
    void firedIsKeptPerArt() {
        MasteryState state = MasteryState.EMPTY.withFired(id("wind_god_steps"), List.of(1));
        assertTrue(state.hasFired(id("wind_god"), 1));
        assertTrue(state.hasFired(id("wind_god_shadow"), 1));
        assertFalse(state.hasFired(id("wind_god"), 2));
        assertFalse(state.hasFired(id("twenty_four_plum"), 1));
    }

    @Test
    @DisplayName("Любое изменение состояния несёт сработавшие рубежи с собой")
    void editsKeepTheFiredSet() {
        MasteryState state = MasteryState.EMPTY.withFired(id("twenty_four_plum"), List.of(1));
        state = state.with(id("twenty_four_plum"), TechniqueProgress.learned(6, 12));
        state = state.withWisdom(3.0D);
        state = state.comprehend(id("twenty_four_plum"));
        assertEquals(Set.of("murim:twenty_four_plum#1"), state.fired());
        assertEquals(Map.of(id("twenty_four_plum"), 6), state.stars());
        // Старый трёхаргументный конструктор остаётся для чужого кода и тестов.
        assertEquals(Set.of(), new MasteryState(Map.of(), 0.0D, Set.of()).fired());
    }
}
