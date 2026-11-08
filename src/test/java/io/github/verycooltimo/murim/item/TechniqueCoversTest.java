package io.github.verycooltimo.murim.item;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TechniqueCoversTest {
    /** Project root: the test working directory may be a run folder, so walk up to the folder holding src/main. */
    private static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("src/main/resources/data/murim"))) {
            p = p.getParent();
        }
        assertNotNull(p, "project root not found");
        return p;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("murim", path);
    }

    @Test
    void formsOfOneArtShareACoverAndDifferentArtsDiffer() {
        Set<Integer> seen = new HashSet<>();
        for (String art : TechniqueCovers.IDS) {
            int index = TechniqueCovers.index(id(art));
            assertTrue(index > 0, art);
            assertTrue(seen.add(index), "duplicate cover index for art " + art);
        }
        assertEquals(TechniqueCovers.index(id("seven_plum_basic")), TechniqueCovers.index(id("seven_plum_shower")));
        assertEquals(TechniqueCovers.index(id("seven_plum_blossoms")), TechniqueCovers.index(id("seven_plum")));
        assertEquals(TechniqueCovers.index(id("tang_five_thunders")), TechniqueCovers.index(id("tang_twelve_daggers")));
        assertEquals(TechniqueCovers.index(id("wind_god_steps")), TechniqueCovers.index(id("wind_god_lightning")));
        assertEquals(TechniqueCovers.index(id("twenty_four_plum_sea")), TechniqueCovers.index(id("twenty_four_plum_full_bloom")));
        assertEquals(TechniqueCovers.index(id("dark_fragrance_step")), TechniqueCovers.index(id("dark_fragrance_behind")));
        assertNotEquals(TechniqueCovers.index(id("wind_god_steps")), TechniqueCovers.index(id("six_harmonies")));
        assertNotEquals(TechniqueCovers.index(id("seven_plum_basic")), TechniqueCovers.index(id("twenty_four_plum_rainfall")));
        assertNotEquals(TechniqueCovers.index(id("wind_god_steps")), TechniqueCovers.index(id("dark_fragrance_step")));
        assertEquals(0, TechniqueCovers.index(id("no_such_technique")));
        assertEquals(0, TechniqueCovers.index(null));
        assertEquals(0, TechniqueCovers.index(ResourceLocation.fromNamespaceAndPath("other", "wind_god_steps")));
    }

    @Test
    void everyTechniqueWithoutOwnManualItemHasACover() {
        File[] files = root().resolve("src/main/resources/data/murim/murim_techniques").toFile().listFiles((d, n) -> n.endsWith(".json"));
        assertNotNull(files);
        for (File f : files) {
            String name = f.getName().replace(".json", "");
            assertTrue(TechniqueCovers.index(id(name)) > 0, "technique without a manual cover: " + name);
        }
    }

    @Test
    void junkCoversStayAfterPlainCoversAndBeforeOriginalOverrides() throws Exception {
        var model = com.google.gson.JsonParser.parseString(Files.readString(root().resolve(
                "src/main/resources/assets/murim/models/item/technique_manual.json"))).getAsJsonObject();
        var overrides = model.getAsJsonArray("overrides");
        assertEquals(23, TechniqueCovers.index(id("true_six_elements_sword")));
        assertEquals(24, TechniqueCovers.index(id("taiyi_sword")));
        assertEquals(25, TechniqueCovers.index(id("bamboo_leaf_hand")));
        boolean originalsStarted = false;
        int plainCovers = 0;
        Set<Integer> originals = new HashSet<>();
        for (var entry : overrides) {
            var predicate = entry.getAsJsonObject().getAsJsonObject("predicate");
            if (!predicate.has("murim:book_cover")) continue;
            int cover = predicate.get("murim:book_cover").getAsInt();
            if (predicate.has("murim:original")) {
                originalsStarted = true;
                assertTrue(originals.add(cover), "duplicate original cover");
                if (cover == 25) assertEquals("murim:item/technique_manual_bamboo_leaf_hand_original",
                        entry.getAsJsonObject().get("model").getAsString());
                assertEquals(TechniqueCovers.IDS.size(), plainCovers, "original override precedes plain covers");
                assertTrue(cover <= 22 || cover == 25, "junk art has an original cover");
            } else {
                assertFalse(originalsStarted, "plain cover masks original override");
                assertEquals(++plainCovers, cover);
            }
        }
        assertTrue(originalsStarted, "original covers missing");
        assertTrue(originals.contains(25), "Bamboo original cover missing");
    }

    @Test
    void modelOverridesAndTexturesMatchTheIndex() throws Exception {
        String model = Files.readString(root().resolve("src/main/resources/assets/murim/models/item/technique_manual.json"));
        int lastAt = -1;
        for (int i = 0; i < TechniqueCovers.IDS.size(); i++) {
            String name = TechniqueCovers.IDS.get(i);
            String entry = "\"murim:book_cover\": " + (i + 1) + ".0";
            int at = model.indexOf(entry);
            assertTrue(at > lastAt, "override for " + name + " missing or out of order");
            lastAt = at;
            String next = model.substring(at, Math.min(model.length(), at + 200));
            assertTrue(next.contains("\"murim:item/technique_manual_" + name + "\""), "wrong model for " + name);
            assertTrue(Files.exists(root().resolve("src/main/resources/assets/murim/models/item/technique_manual_" + name + ".json")), name);
        }
    }
}
