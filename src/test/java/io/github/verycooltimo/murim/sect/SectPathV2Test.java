package io.github.verycooltimo.murim.sect;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Doc 39 items 1, 6 and 7 without a server: the serving counter, the meal ids, the approach plans and the lang files. */
class SectPathV2Test {

    private static final SectLayoutFile FILE = SectLayoutFile.shipped();

    @Test
    @DisplayName("The cook serves from the counter of the dining hall; the helpers stand in the same table row")
    void cookStandsAtTheCounter() {
        SectRoster cook = SectRoster.of("cook_kim").orElseThrow();
        long breakfast = 24000L * 3 + SectSchedule.Period.BREAKFAST.start() + 400;
        long dinner = 24000L * 3 + SectSchedule.Period.DINNER.start() + 400;
        for (long time : List.of(breakfast, dinner)) {
            SectSchedule.Task t = SectSchedule.task(cook, time);
            assertEquals(SectSchedule.Kind.SERVE, t.kind());
            SectPlaces.Pick pick = SectPlaces.pick(FILE, cook, t, time, new SectSpots(), time, null);
            assertNotNull(pick);
            assertEquals(BlockPos.containing(-1115.5D, 211, 4250.5D), pick.cell());
        }
        List<List<SectLayoutFile.Spot>> line = SectPlaces.servingLine(FILE);
        assertEquals(1, line.get(0).size());
        assertEquals("counter", line.get(0).get(0).kind());
        assertTrue(line.get(1).stream().allMatch(s -> s.kind().equals("table") && s.pos().getZ() == 4250));
        // a second server stands beside the cook, not on the same cell
        SectSpots spots = new SectSpots();
        SectPlaces.Pick first = SectPlaces.pick(FILE, cook, SectSchedule.task(cook, breakfast), breakfast, spots, breakfast, null);
        SectRoster water = SectRoster.of("water_gu").orElseThrow();
        assertEquals(SectSchedule.Kind.SERVE, SectSchedule.task(water, breakfast).kind());
        SectPlaces.Pick second = SectPlaces.pick(FILE, water, SectSchedule.task(water, breakfast), breakfast, spots, breakfast, null);
        assertNotNull(second);
        assertFalse(first.cell().equals(second.cell()));
    }

    @Test
    @DisplayName("Only the counter of the dining hall counts as the counter, not other benches or the alchemy counters")
    void counterDetection() {
        assertTrue(SectMeals.isCounter(FILE, new BlockPos(-1116, 211, 4250)));
        assertTrue(SectMeals.isCounter(FILE, new BlockPos(-1116, 212, 4249)));
        assertFalse(SectMeals.isCounter(FILE, new BlockPos(-1104, 220, 4273)));
        assertFalse(SectMeals.isCounter(FILE, new BlockPos(-1121, 211, 4258)));
        assertFalse(SectMeals.isCounter(null, new BlockPos(0, 0, 0)));
    }

    @Test
    @DisplayName("Two meals per sect day, each with its own id; none outside the meals")
    void mealIds() {
        long day = 24000L * 5;
        long b = SectMeals.mealId(day + 1200);
        long d = SectMeals.mealId(day + 9500);
        assertTrue(b >= 0 && d == b + 1);
        assertEquals(b, SectMeals.mealId(day + 1900));
        assertEquals(-1L, SectMeals.mealId(day + 5000));
        assertEquals(-1L, SectMeals.mealId(day + 15000));
        assertEquals(b + 2, SectMeals.mealId(day + 24000L + 1200));
    }

    @Test
    @DisplayName("New texts exist in en_us and ru_ru")
    void langKeysInBothLanguages() throws IOException {
        JsonObject en = lang("en_us");
        JsonObject ru = lang("ru_ru");
        List<String> prefixes = List.of("murim.approach.", "murim.sect.meal.", "murim.sect.journey.hud", "item.murim.meal_bowl",
                "murim.sect.journey.goal.rank_three", "murim.sect.journey.goal.peak", "dialogue.murim.mentor.opt.hud");
        int seen = 0;
        for (String key : en.keySet()) {
            if (prefixes.stream().anyMatch(key::startsWith)) {
                assertTrue(ru.has(key), "ru_ru lacks " + key);
                seen++;
            }
        }
        for (String key : ru.keySet()) {
            if (prefixes.stream().anyMatch(key::startsWith)) {
                assertTrue(en.has(key), "en_us lacks " + key);
            }
        }
        assertTrue(seen > 30, "too few keys found: " + seen);
        for (String id : SectRequests.IDS) {
            assertTrue(en.has("murim.approach.help." + id) && ru.has("murim.approach.help." + id), id);
        }
        for (SectApproach.Kind kind : SectApproach.Kind.values()) {
            for (int i = 1; i <= SectApproach.VARIANTS; i++) {
                if (kind == SectApproach.Kind.TEACHER) {
                    String key = "murim.bubble.lesson_ack." + i;
                    assertTrue(en.has(key) && ru.has(key), key);
                    continue;
                }
                if (kind == SectApproach.Kind.INVITE) {
                    // one pool per event of the office (SectLinePoolsTest), and the words he says when you turn to him
                    for (SectOffices.Event e : SectOffices.events()) {
                        String bubble = "murim.invite.bubble." + e.id() + "." + i;
                        assertTrue(en.has(bubble) && ru.has(bubble), bubble);
                        assertTrue(en.has("murim.invite.line." + e.id()) && ru.has("murim.invite.line." + e.id()), e.id());
                    }
                    continue;
                }
                String key = "murim.approach.bubble." + kind.id() + "." + i;
                assertTrue(en.has(key) && ru.has(key), key);
            }
        }
    }

    private static JsonObject lang(String name) throws IOException {
        try (var in = SectPathV2Test.class.getResourceAsStream("/assets/murim/lang/" + name + ".json")) {
            assertNotNull(in);
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
