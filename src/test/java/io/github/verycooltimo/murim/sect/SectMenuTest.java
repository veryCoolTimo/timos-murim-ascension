package io.github.verycooltimo.murim.sect;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Mount Hua kitchen without a server: what each meal serves, the plain food values, and the assets of every dish. */
class SectMenuTest {

    private static Path res(String path) {
        Path p = Path.of("src/main/resources/assets/murim/" + path);
        return Files.exists(p) ? p : Path.of("../../src/main/resources/assets/murim/" + path);
    }

    private static long meal(long day, boolean dinner) {
        return day * 2L + (dinner ? 1L : 0L);
    }

    @Test
    @DisplayName("Breakfast is the porridge or a bun; dinner rotates rice and soup, soup and a bun, rice and tea; the review day is no exception")
    void menuByMeal() {
        assertEquals(List.of(SectMenu.Dish.MILLET_PORRIDGE), SectMenu.menu(meal(0, false)));
        assertEquals(List.of(SectMenu.Dish.STEAMED_BUN), SectMenu.menu(meal(1, false)));
        assertEquals(List.of(SectMenu.Dish.MILLET_PORRIDGE), SectMenu.menu(meal(2, false)));
        assertEquals(List.of(SectMenu.Dish.RICE_AND_PICKLES, SectMenu.Dish.MOUNTAIN_SOUP), SectMenu.menu(meal(0, true)));
        assertEquals(List.of(SectMenu.Dish.MOUNTAIN_SOUP, SectMenu.Dish.STEAMED_BUN), SectMenu.menu(meal(1, true)));
        assertEquals(List.of(SectMenu.Dish.RICE_AND_PICKLES, SectMenu.Dish.PLUM_TEA), SectMenu.menu(meal(2, true)));
        // Day 6 is the review day (day % 7 == 6): no feast (author 19.10), the normal rotation (6 % 3 == 0: rice and soup).
        assertTrue(SectReview.reviewDay(6));
        assertEquals(List.of(SectMenu.Dish.RICE_AND_PICKLES, SectMenu.Dish.MOUNTAIN_SOUP), SectMenu.menu(meal(6, true)));
        assertEquals(List.of(SectMenu.Dish.MILLET_PORRIDGE), SectMenu.menu(meal(6, false)));
        // Day 13 is the next review day (13 % 3 == 1: soup and a bun).
        assertTrue(SectReview.reviewDay(13));
        assertEquals(List.of(SectMenu.Dish.MOUNTAIN_SOUP, SectMenu.Dish.STEAMED_BUN), SectMenu.menu(meal(13, true)));
        // Every review day eats exactly like the same day of the three-day rotation without a review.
        for (long day = 0; day < 70; day++) {
            if (SectReview.reviewDay(day)) {
                assertEquals(SectMenu.menu(meal(day - 3, true)), SectMenu.menu(meal(day, true)), "review day " + day + " dinner");
                assertEquals(SectMenu.menu(meal(day - 2, false)), SectMenu.menu(meal(day, false)), "review day " + day + " breakfast");
            }
        }
        // Negative days (a world that starts before day zero) still resolve.
        assertFalse(SectMenu.menu(meal(-3, true)).isEmpty());
    }

    @Test
    @DisplayName("The meat bowl is never served; every other dish shows up in a fortnight; one or two items a meal")
    void rotation() {
        Set<SectMenu.Dish> seen = EnumSet.noneOf(SectMenu.Dish.class);
        for (long day = 0; day < 14; day++) {
            for (boolean dinner : new boolean[] {false, true}) {
                List<SectMenu.Dish> menu = SectMenu.menu(meal(day, dinner));
                assertTrue(menu.size() >= 1 && menu.size() <= 2, "menu " + menu);
                assertFalse(menu.contains(SectMenu.Dish.MEAT_BOWL), "meat on day " + day);
                seen.addAll(menu);
            }
        }
        Set<SectMenu.Dish> served = EnumSet.allOf(SectMenu.Dish.class);
        served.remove(SectMenu.Dish.MEAT_BOWL);
        assertEquals(served, seen);
        // Nothing, over a long stretch of meals (negative ids too), carries the meat.
        for (long meal = -200; meal < 400; meal++) {
            assertFalse(SectMenu.menu(meal).contains(SectMenu.Dish.MEAT_BOWL), "meat at meal " + meal);
        }
    }

    @Test
    @DisplayName("Food values are small and vanilla-like; the meat bowl is the richest, the tea is a sip, cold portions feed less")
    void values() {
        Set<String> ids = new HashSet<>();
        for (SectMenu.Dish d : SectMenu.Dish.values()) {
            assertTrue(ids.add(d.id()), "duplicate id " + d.id());
            assertTrue(d.nutrition() >= 1 && d.nutrition() <= 8, d.id() + " nutrition " + d.nutrition());
            assertTrue(d.saturation() > 0.0F && d.saturation() <= 0.8F, d.id() + " saturation " + d.saturation());
            assertTrue(d.coldNutrition() <= d.nutrition() && d.coldNutrition() >= 1 && d.coldSaturation() < d.saturation());
            if (d != SectMenu.Dish.MEAT_BOWL) {
                assertTrue(d.nutrition() < SectMenu.Dish.MEAT_BOWL.nutrition(), d.id() + " is as rich as the meat bowl");
            }
        }
        assertEquals(1, SectMenu.Dish.PLUM_TEA.nutrition());
        assertTrue(SectMenu.Dish.MILLET_PORRIDGE.drink() && SectMenu.Dish.PLUM_TEA.drink() && SectMenu.Dish.MOUNTAIN_SOUP.drink());
        assertFalse(SectMenu.Dish.STEAMED_BUN.drink() || SectMenu.Dish.MEAT_BOWL.drink() || SectMenu.Dish.RICE_AND_PICKLES.drink());
    }

    @Test
    @DisplayName("Every dish has a 16x16 texture, a model and names in en_us and ru_ru (the tea also its description)")
    void assets() throws IOException {
        JsonObject en = lang("en_us");
        JsonObject ru = lang("ru_ru");
        for (SectMenu.Dish d : SectMenu.Dish.values()) {
            var png = res("textures/item/" + d.id() + ".png");
            assertTrue(Files.exists(png), "texture " + d.id());
            var img = ImageIO.read(png.toFile());
            assertEquals(16, img.getWidth(), d.id());
            assertEquals(16, img.getHeight(), d.id());
            int opaque = 0;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    if ((img.getRGB(x, y) >>> 24) != 0) opaque++;
                }
            }
            assertTrue(opaque > 80 && opaque < 256, d.id() + " covers " + opaque + " pixels");
            var model = JsonParser.parseString(Files.readString(res("models/item/" + d.id() + ".json"))).getAsJsonObject();
            assertEquals("murim:item/" + d.id(), model.getAsJsonObject("textures").get("layer0").getAsString());
            assertTrue(en.has("item.murim." + d.id()) && ru.has("item.murim." + d.id()), "name of " + d.id());
        }
        assertTrue(en.has("item.murim.plum_tea.desc") && ru.has("item.murim.plum_tea.desc"));
        for (String key : List.of("murim.tea.focus", "murim.sect.meal.cold_tip", "murim.sect.meal.served", "murim.sect.meal.served_cold")) {
            assertTrue(en.has(key) && ru.has(key), key);
        }
        // The served messages take the list of dishes.
        assertTrue(en.get("murim.sect.meal.served").getAsString().contains("%s") && ru.get("murim.sect.meal.served").getAsString().contains("%s"));
    }

    private static JsonObject lang(String name) throws IOException {
        Path p = res("lang/" + name + ".json");
        assertNotNull(p);
        return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
