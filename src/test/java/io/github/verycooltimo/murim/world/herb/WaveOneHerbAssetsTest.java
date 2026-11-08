package io.github.verycooltimo.murim.world.herb;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Source-resource gate: new herbs may never fall back to vanilla art. */
class WaveOneHerbAssetsTest {
    private static final List<String> IDS = List.of("hundred_ginseng", "purple_ginseng", "purple_wood_herb", "ice_crystal");

    private static Path resources() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("src/main/resources/assets/murim/models"))) root = root.getParent();
        assertNotNull(root, "source resources not found above working directory");
        return root.resolve("src/main/resources");
    }

    private static JsonObject read(Path path) throws Exception {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    @Test
    void newModelsUseOnlyOwnTexturesAndAllReferencesExist() throws Exception {
        Path assets = resources().resolve("assets/murim");
        for (String id : IDS) {
            assertTrue(Files.isRegularFile(assets.resolve("textures/item/" + id + ".png")), id);
            assertTrue(Files.isRegularFile(assets.resolve("models/item/" + id + ".json")), id);
            if (!id.equals("ice_crystal")) {
                var states = read(assets.resolve("blockstates/" + id + "_plant.json")).getAsJsonObject("variants");
                for (String state : List.of("cut=false", "cut=true")) {
                    String model = states.getAsJsonObject(state).get("model").getAsString();
                    assertTrue(model.startsWith("murim:"), model);
                    assertTrue(Files.isRegularFile(assets.resolve("models/" + model.substring(6) + ".json")), model);
                }
            }
        }
        int checked = 0;
        try (var files = Files.walk(assets.resolve("models"))) {
            for (Path path : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                if (IDS.stream().noneMatch(id -> path.getFileName().toString().startsWith(id))) continue;
                var model = read(path);
                assertTrue(model.has("textures"), "no authored textures: " + path);
                for (var entry : model.getAsJsonObject("textures").entrySet()) {
                    String texture = entry.getValue().getAsString();
                    assertFalse(texture.startsWith("minecraft:block/") || texture.startsWith("minecraft:item/"), path + ": " + texture);
                    if (texture.startsWith("murim:")) assertTrue(Files.isRegularFile(
                            assets.resolve("textures/" + texture.substring(6) + ".png")), path + ": " + texture);
                }
                if (model.has("parent")) assertFalse(model.get("parent").getAsString().startsWith("minecraft:block/"), path.toString());
                if (model.has("model")) {
                    String obj = model.get("model").getAsString();
                    assertTrue(Files.isRegularFile(assets.resolve(obj.substring(6))), "missing mesh: " + obj);
                }
                checked++;
            }
        }
        assertEquals(10, checked, "four item models and six plant/cut models");
    }

    @Test
    void bookMetadataAndAllAddedLanguageKeysExistInBothLocales() throws Exception {
        Path r = resources();
        var en = read(r.resolve("assets/murim/lang/en_us.json"));
        var ru = read(r.resolve("assets/murim/lang/ru_ru.json"));
        for (String id : IDS) {
            for (String key : List.of("item.murim." + id, "murim.herb.raw." + id)) {
                assertTrue(en.has(key) && ru.has(key), "missing translation: " + key);
            }
        }
        for (var step : read(r.resolve("data/murim/murim_quests/wild_herbs.json")).getAsJsonArray("steps")) {
            var herb = step.getAsJsonObject();
            for (String field : List.of("origin", "raw_effect")) {
                String key = herb.get(field).getAsString();
                assertTrue(en.has(key) && ru.has(key), key);
            }
            String habitat = "murim.contract.habitat." + herb.get("habitat").getAsString();
            assertTrue(en.has(habitat) && ru.has(habitat), habitat);
            assertFalse(herb.get("dangerous").getAsBoolean());
        }
    }
}
