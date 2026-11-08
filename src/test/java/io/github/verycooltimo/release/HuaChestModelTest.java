package io.github.verycooltimo.release;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Resource-only contract: no Minecraft bootstrap, renderer or registry needed. */
class HuaChestModelTest {
    private final Path assets = ReleaseTestPaths.projectRoot().resolve("src/main/resources/assets");

    @Test void bothModelsHaveBoundedElementsAndExistingTextures() throws Exception {
        for (String state : new String[]{"closed", "open"}) {
            JsonObject model = read("models/block/hua_chest_" + state + ".json");
            // CompositeModel.Loader reads inline BlockModels under children. Inspect those too,
            // otherwise an empty root would make bounds/texture checks pass vacuously.
            int count = checkPart(model, state);
            assertTrue(count > 0 && count < 60, state + " element count: " + count);
        }
    }

    private int checkPart(JsonObject model, String label) {
        JsonObject textures = model.getAsJsonObject("textures");
        assertNotNull(textures, label + " textures");
        for (var entry : textures.entrySet()) {
            String texture = resolveTexture(textures, entry.getValue().getAsString());
            String[] id = texture.split(":", 2);
            assertEquals(2, id.length, label + " namespaced texture " + texture);
            assertTrue(Files.isRegularFile(assets.resolve(id[0]).resolve("textures/" + id[1] + ".png")),
                    label + " missing texture " + texture);
        }
        int count = 0;
        if (model.has("elements")) {
            for (JsonElement value : model.getAsJsonArray("elements")) {
                JsonObject element = value.getAsJsonObject();
                var from = element.getAsJsonArray("from");
                var to = element.getAsJsonArray("to");
                assertEquals(3, from.size(), label);
                assertEquals(3, to.size(), label);
                for (int axis = 0; axis < 3; axis++) {
                    double lo = from.get(axis).getAsDouble(), hi = to.get(axis).getAsDouble();
                    assertTrue(Double.isFinite(lo) && Double.isFinite(hi)
                                    && lo >= 0 && hi <= 16 && lo < hi,
                            label + " invalid cuboid: " + element);
                }
                for (var face : element.getAsJsonObject("faces").entrySet()) {
                    JsonObject definition = face.getValue().getAsJsonObject();
                    resolveTexture(textures, definition.get("texture").getAsString());
                    var uv = definition.getAsJsonArray("uv");
                    assertEquals(4, uv.size(), label);
                    for (JsonElement coordinate : uv) {
                        double n = coordinate.getAsDouble();
                        assertTrue(n >= 0 && n <= 16 && n * 32 == Math.rint(n * 32),
                                label + " UV must land on a 512x512 atlas texel: " + uv);
                    }
                }
                count++;
            }
        }
        if (model.has("children")) {
            for (var child : model.getAsJsonObject("children").entrySet()) {
                int childCount = checkPart(child.getValue().getAsJsonObject(), label + "/" + child.getKey());
                assertTrue(childCount > 0, label + " empty child " + child.getKey());
                count += childCount;
            }
        }
        return count;
    }

    private String resolveTexture(JsonObject textures, String reference) {
        Set<String> visited = new HashSet<>();
        while (reference.startsWith("#")) {
            assertTrue(visited.add(reference), "Cyclic texture reference " + reference);
            String key = reference.substring(1);
            assertTrue(textures.has(key), "Missing texture reference " + reference);
            reference = textures.get(key).getAsString();
        }
        return reference;
    }

    @Test void vaultWiringRetainsEveryFacingAndOpenState() throws Exception {
        JsonObject variants = read("blockstates/vault_chest.json").getAsJsonObject("variants");
        assertEquals(8, variants.size());
        String[] facings = {"north", "east", "south", "west"};
        for (boolean open : new boolean[]{false, true}) {
            for (int i = 0; i < facings.length; i++) {
                JsonObject variant = variants.getAsJsonObject("facing=" + facings[i] + ",open=" + open);
                assertNotNull(variant);
                assertEquals("murim:block/hua_chest_" + (open ? "open" : "closed"),
                        variant.get("model").getAsString());
                assertEquals(i * 90, variant.has("y") ? variant.get("y").getAsInt() : 0);
            }
        }
        assertEquals("murim:block/hua_chest_closed",
                read("models/item/vault_chest.json").get("parent").getAsString());
        // API: NeoForge 21.1 ClientNeoForgeMod#onRegisterGeometryLoaders registers
        // neoforge:composite and neoforge:elements; CompositeModel.Loader reads children.
        JsonObject openModel = read("models/block/hua_chest_open.json");
        assertEquals("neoforge:composite", openModel.get("loader").getAsString());
        assertEquals(Set.of("body", "lid"), openModel.getAsJsonObject("children").keySet());
        JsonObject lid = openModel
                .getAsJsonObject("children").getAsJsonObject("lid");
        assertEquals("neoforge:elements", lid.get("loader").getAsString());
        assertEquals(100, lid.getAsJsonObject("transform").getAsJsonObject("rotation").get("x").getAsInt());
    }

    @Test void simpleGeometryRetainsProportionsAndRearHinge() throws Exception {
        JsonObject closed = read("models/block/hua_chest_closed.json");
        JsonObject children = read("models/block/hua_chest_open.json").getAsJsonObject("children");
        JsonObject lid = named(closed, "lid");
        assertEquals(JsonParser.parseString("[0,7.5,3]"), lid.get("from"));
        assertEquals(JsonParser.parseString("[16,11,14]"), lid.get("to"));
        assertEquals(JsonParser.parseString("[0.5,0.46875,0.875]"), children
                .getAsJsonObject("lid").getAsJsonObject("transform").get("origin"));
        var assembled = new com.google.gson.JsonArray();
        assembled.addAll(children.getAsJsonObject("body").getAsJsonArray("elements"));
        assembled.addAll(children.getAsJsonObject("lid").getAsJsonArray("elements"));
        assertEquals(closed.get("elements"), assembled);
        var counts = new java.util.HashMap<String, Integer>();
        for (JsonElement value : assembled) {
            String name = value.getAsJsonObject().get("name").getAsString();
            counts.merge(name, 1, Integer::sum);
        }
        assertEquals(Set.of("floor", "front", "back", "left", "right", "silk bed",
                "square foot", "body corner cap", "lid", "lid corner cap", "jade clasp petal",
                "jade clasp centre", "tassel cord", "tassel knot", "tassel fringe"), counts.keySet());
        assertEquals(4, counts.get("square foot").intValue());
        assertEquals(8, counts.get("body corner cap").intValue()); // two arms per L
        assertEquals(8, counts.get("lid corner cap").intValue());
        assertEquals(5, counts.get("jade clasp petal").intValue());
        assertEquals(3, counts.get("tassel fringe").intValue());
        assertEquals(1, counts.get("silk bed").intValue());
    }

    private JsonObject named(JsonObject part, String name) {
        for (JsonElement value : part.getAsJsonArray("elements")) {
            if (name.equals(value.getAsJsonObject().get("name").getAsString())) return value.getAsJsonObject();
        }
        fail("Missing element " + name);
        return null;
    }

    @Test void generatedFacesAreCopiedLosslesslyAndMappedToNamedFaces() throws Exception {
        var atlas = ImageIO.read(assets.resolve("murim/textures/block/hua_chest.png").toFile());
        assertEquals(512, atlas.getWidth());
        assertEquals(512, atlas.getHeight());
        JsonObject model = read("models/block/hua_chest_closed.json");
        // Source width/height in atlas texels. Lid sides use an exact 176x64 crop.
        for (String[] mapping : new String[][]{
                {"front", "north", "front", "0", "0", "240", "112"},
                {"back", "south", "front", "0", "0", "240", "112"},
                {"left", "west", "side", "240", "0", "128", "112"},
                {"right", "east", "side", "240", "0", "128", "112"},
                {"lid", "up", "top", "0", "112", "256", "176"},
                {"lid", "north", "lidfront", "256", "112", "256", "64"},
                {"lid", "south", "lidfront", "256", "112", "256", "64"},
                {"lid", "east", "lidfront", "256", "176", "176", "64"},
                {"lid", "west", "lidfront", "256", "176", "176", "64"},
                {"silk bed", "up", "silk", "0", "288", "208", "128"}}) {
            int x = Integer.parseInt(mapping[3]), y = Integer.parseInt(mapping[4]);
            int width = Integer.parseInt(mapping[5]), height = Integer.parseInt(mapping[6]);
            var uv = named(model, mapping[0]).getAsJsonObject("faces")
                    .getAsJsonObject(mapping[1]).getAsJsonArray("uv");
            assertEquals(JsonParser.parseString("[" + x/32.0 + "," + y/32.0 + ","
                    + (x+width)/32.0 + "," + (y+height)/32.0 + "]"), uv);
            var source = ImageIO.read(ReleaseTestPaths.projectRoot()
                    .resolve("tools/art/hua_chest_faces/" + mapping[2] + ".png").toFile());
            for (int row = 0; row < height; row++) {
                for (int column = 0; column < width; column++) {
                    assertEquals(source.getRGB(column, row) & 0xFFFFFF,
                            atlas.getRGB(x+column, y+row) & 0xFFFFFF,
                            mapping[0] + "/" + mapping[1] + " texel " + column + "," + row);
                }
            }
        }
    }

    private JsonObject read(String path) throws Exception {
        try (var reader = Files.newBufferedReader(assets.resolve("murim").resolve(path))) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
