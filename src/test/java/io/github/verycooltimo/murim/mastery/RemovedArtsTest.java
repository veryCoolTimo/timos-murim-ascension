package io.github.verycooltimo.murim.mastery;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.combat.TechniqueState;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Save migration of 19.10: the six Heavenly Demon cult arts were deleted from the code (author: «потом сами переделаем всё
 * нормально»). A save written by an older build still names them in the mastery, the loadout, the cooldowns and the
 * technique state; none of that may crash a load or leave a phantom entry behind. The ids below are the OLD save format,
 * which is why they appear here at all.
 */
class RemovedArtsTest {
    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath("murim", name);
    }

    private static final String[] GONE = {"murim:mountain_thunder_fist", "murim:rift_palm", "murim:clear_sky_sword",
            "murim:high_cloud_steps", "murim:quiet_heart_guard", "murim:four_horizons"};

    /** A profile of the previous build: removed arts in every field of the mastery, beside two arts that stay. */
    private static final String OLD_MASTERY = """
            {"version": 4,
             "techniques": {"murim:high_cloud_steps": {"version": 4, "stars": 5, "progress": 1.0, "unprocessed": 0, "day": 3, "cap": 10}},
             "arts": {
               "murim:rift_palm": {"version": 4, "stars": 9, "progress": 4.5, "unprocessed": 2, "day": 7, "cap": 12},
               "murim:quiet_heart_guard": {"version": 4, "stars": 12, "progress": 0, "unprocessed": 0, "day": 7, "cap": 12},
               "murim:village_brawler_fist": {"version": 4, "stars": 3, "progress": 1, "unprocessed": 0, "day": 7, "cap": 12},
               "murim:wind_god": {"version": 4, "stars": 6, "progress": 2, "unprocessed": 0, "day": 7, "cap": 10}
             },
             "known": ["murim:rift_palm", "murim:quiet_heart_guard", "murim:four_horizons", "murim:village_brawler_fist",
                       "murim:wind_god_steps"],
             "wisdom": 8,
             "comprehended": ["murim:quiet_heart_guard", "murim:village_brawler_fist"],
             "fired": ["murim:quiet_heart_guard#1", "murim:quiet_heart_guard#2", "murim:twenty_four_plum#1"]}
            """;

    private static MasteryState decode(String json) {
        return MasteryState.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    @Test
    @DisplayName("Профиль со всеми шестью следами удалённых искусств читается без ошибки, остальное цело")
    void oldMasteryDecodesWithoutTheRemovedArts() {
        MasteryState state = decode(OLD_MASTERY);
        for (String gone : GONE) {
            ResourceLocation art = ResourceLocation.parse(gone);
            assertFalse(state.knows(art), gone);
            assertEquals(null, state.progress(art), gone);
            assertFalse(state.comprehended().contains(art), gone);
        }
        assertEquals(Set.of(id("village_brawler_fist"), id("wind_god_steps")), state.techniques().keySet());
        assertEquals(3, state.progress(id("village_brawler_fist")).stars());
        assertEquals(6, state.progress(id("wind_god_steps")).stars(), "the Wind God Steps keep their progress");
        assertEquals(8.0D, state.wisdom(), 1.0E-9D);
        assertEquals(Set.of(id("village_brawler_fist")), state.comprehended());
        assertEquals(Set.of("murim:twenty_four_plum#1"), state.fired(), "milestones of the removed arts are dropped, the others stay");
    }

    @Test
    @DisplayName("После чтения и записи в файле не остаётся ни одного удалённого id, повторное чтение даёт то же")
    void theNextSaveIsClean() {
        MasteryState state = decode(OLD_MASTERY);
        JsonObject saved = MasteryState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow().getAsJsonObject();
        String text = saved.toString();
        for (String gone : GONE) {
            assertFalse(text.contains(gone.substring("murim:".length())), "the new save still names " + gone);
        }
        assertEquals(state, MasteryState.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow());
    }

    @Test
    @DisplayName("Сохранение без удалённых искусств не меняется: те же объекты, те же значения")
    void aCleanSaveIsUntouched() {
        Map<ResourceLocation, Integer> clean = new HashMap<>(Map.of(id("wind_god_steps"), 1));
        assertSame(clean, RemovedArts.scrub(clean));
        List<ResourceLocation> list = List.of(id("seven_plum"));
        assertSame(list, RemovedArts.scrub(list));
        Set<String> fired = Set.of("murim:twenty_four_plum#1");
        assertSame(fired, RemovedArts.scrubFired(fired));
        MasteryState state = MasteryState.EMPTY.with(id("village_brawler_fist"), TechniqueProgress.learned(3, 12));
        assertEquals(state, MasteryState.CODEC.parse(JsonOps.INSTANCE,
                MasteryState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow()).getOrThrow());
    }

    @Test
    @DisplayName("Раскладка: слот и основа с удалённым искусством пусты, активный слот не мёртвый, хвост обрезан")
    void loadoutDropsTheRemovedArtsWithoutBreakingTheWheel() {
        String old = """
                {"slots": [{"technique": "murim:rift_palm"}, {"technique": "murim:seven_plum_blossoms"},
                           {"technique": "murim:high_cloud_steps"}, {}, {"technique": "murim:four_horizons"}],
                 "active": 0, "foundation": "murim:clear_sky_sword"}
                """;
        Loadout loadout = Loadout.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(old)).getOrThrow();
        assertEquals(List.of(Optional.empty(), Optional.of(id("seven_plum_blossoms"))), loadout.slots(),
                "the emptied slot stays a hole the wheel already supports; the empty tail is cut");
        assertEquals(1, loadout.active(), "the active slot held a removed art: the first real technique is chosen");
        assertEquals(Optional.of(id("seven_plum_blossoms")), loadout.activeTechnique());
        assertEquals(Optional.empty(), loadout.foundation());
        assertSame(loadout, loadout.withoutRemovedArts(), "idempotent");

        Loadout onlyRemoved = Loadout.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"slots\": [{\"technique\": \"murim:rift_palm\"}, {\"technique\": \"murim:quiet_heart_guard\"}], \"active\": 1}"))
                .getOrThrow();
        assertEquals(List.of(), onlyRemoved.slots());
        assertEquals(0, onlyRemoved.active());
        assertEquals(Optional.empty(), onlyRemoved.activeTechnique());

        Loadout kept = Loadout.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"slots\": [{\"technique\": \"murim:wind_god_steps\"}, {}], \"active\": 1, \"foundation\": \"murim:seven_plum_basic\"}"))
                .getOrThrow();
        assertEquals(List.of(Optional.of(id("wind_god_steps")), Optional.empty()), kept.slots(), "a clean loadout keeps even its holes");
        assertEquals(1, kept.active());
        assertEquals(Optional.of(id("seven_plum_basic")), kept.foundation());
    }

    @Test
    @DisplayName("Запущенная техника удалённого искусства в сохранении кончена, время запуска цело")
    void aRunningRemovedArtIsOver() {
        TechniqueState running = TechniqueState.started(id("four_horizons"), 1234L).advanced();
        JsonObject saved = TechniqueState.CODEC.encodeStart(JsonOps.INSTANCE, running).getOrThrow().getAsJsonObject();
        TechniqueState back = TechniqueState.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
        assertFalse(back.isActive());
        assertEquals(1234L, back.lastStartGameTime(), "the cooldown stamp survives");
        TechniqueState kept = TechniqueState.started(id("seven_plum_blossoms"), 99L).advanced();
        assertEquals(kept, TechniqueState.CODEC.parse(JsonOps.INSTANCE,
                TechniqueState.CODEC.encodeStart(JsonOps.INSTANCE, kept).getOrThrow()).getOrThrow());
    }

    @Test
    @DisplayName("Перезарядки удалённых искусств выбрасываются, чужие остаются")
    void cooldownsDropTheRemovedArts() {
        Map<ResourceLocation, Long> cooldowns = new HashMap<>();
        cooldowns.put(id("rift_palm"), 100L);
        cooldowns.put(id("clear_sky_sword"), 200L);
        cooldowns.put(id("seven_plum_blossoms"), 300L);
        assertEquals(Map.of(id("seven_plum_blossoms"), 300L), RemovedArts.scrub(cooldowns));
    }

    @Test
    @DisplayName("Узнаются только шесть искусств, их стиль и варианты анимации; Шаги Бога Ветров и Ладонь остаются")
    void onlyTheSixAreRecognised() {
        for (String gone : GONE) {
            assertTrue(RemovedArts.is(ResourceLocation.parse(gone)), gone);
            assertTrue(RemovedArts.isFiredKey(gone + "#2"), gone + "#2");
        }
        assertTrue(RemovedArts.is(id("clear_sky_sword_1")) && RemovedArts.is(id("clear_sky_sword_2")) && RemovedArts.is(id("heavenly_demon")));
        for (String stays : List.of("wind_god_steps", "wind_god", "demon_palm", "twenty_four_plum_rainfall", "seven_plum_blossoms")) {
            assertFalse(RemovedArts.is(id(stays)), stays);
            assertFalse(RemovedArts.isFiredKey("murim:" + stays + "#1"), stays);
        }
        assertFalse(RemovedArts.is(ResourceLocation.fromNamespaceAndPath("other", "rift_palm")), "another namespace is another mod");
        assertFalse(RemovedArts.is(null));
        assertEquals(Set.of("mountain_thunder_fist", "rift_palm", "clear_sky_sword", "high_cloud_steps", "quiet_heart_guard", "four_horizons"),
                RemovedArts.ARTS);
    }
}
