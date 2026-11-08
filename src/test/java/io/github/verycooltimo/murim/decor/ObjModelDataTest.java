package io.github.verycooltimo.murim.decor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Data checks of the OBJ block/item models ({@code "loader": "neoforge:obj"}), the class of bug behind the purple bits
 * on the round window (19.10): the block atlas does not wrap, so a uv outside 0..1, or a whole face sitting exactly on
 * the sprite edge, samples the NEIGHBOUR sprite of the atlas (purple for the missing-texture sprite and others).
 * <ul>
 *   <li>every uv lies inside the sprite (0..1, a rounding slack of 0.1 texel);</li>
 *   <li>no triangle of a face (a quad is drawn as two) has all its u, or all its v, exactly 0 or exactly 1: a
 *       constant edge column is read from the next sprite by the nearest sampler (roof kit models are exempt, they
 *       were hand-made and accepted);</li>
 *   <li>every {@code usemtl} has a {@code newmtl} with {@code map_Kd #key}, the key is in the model's
 *       {@code textures} and a {@code murim:} texture exists as a png.</li>
 * </ul>
 * Reads src/main/resources directly, no game needed.
 */
class ObjModelDataTest {

    private static final double SLACK = 0.1 / 16;

    private static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("src/main/resources/assets/murim"))) {
            p = p.getParent();
        }
        assertNotNull(p, "project root not found");
        return p;
    }

    /** Every OBJ model json: model json path -> parsed json. */
    private static Map<Path, JsonObject> objModels(Path assets) throws IOException {
        Map<Path, JsonObject> out = new HashMap<>();
        try (Stream<Path> files = Files.walk(assets.resolve("murim/models"))) {
            for (Path f : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".json"))::iterator) {
                JsonObject json = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
                if (json.has("loader") && "neoforge:obj".equals(json.get("loader").getAsString())) {
                    out.put(f, json);
                }
            }
        }
        return out;
    }

    private static Path objPath(Path assets, String location) {
        int colon = location.indexOf(':');
        String ns = colon < 0 ? "minecraft" : location.substring(0, colon);
        String path = colon < 0 ? location : location.substring(colon + 1);
        return assets.resolve(ns).resolve(path);
    }

    @Test
    void everyObjUvStaysInsideItsSprite() throws IOException {
        Path assets = root().resolve("src/main/resources/assets");
        List<String> bad = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (Map.Entry<Path, JsonObject> e : objModels(assets).entrySet()) {
            Path obj = objPath(assets, e.getValue().get("model").getAsString());
            assertTrue(Files.exists(obj), e.getKey() + ": model file missing " + obj);
            if (!seen.add(obj)) {
                continue;
            }
            List<double[]> vts = new ArrayList<>();
            String mat = "?";
            int line = 0;
            for (String l : Files.readAllLines(obj)) {
                line++;
                String[] t = l.trim().split("\\s+");
                if (t[0].equals("vt")) {
                    vts.add(new double[] {Double.parseDouble(t[1]), Double.parseDouble(t[2])});
                } else if (t[0].equals("usemtl")) {
                    mat = t[1];
                } else if (t[0].equals("f")) {
                    List<double[]> uv = new ArrayList<>();
                    for (int i = 1; i < t.length; i++) {
                        String[] idx = t[i].split("/");
                        uv.add(vts.get(Integer.parseInt(idx[1]) - 1));
                    }
                    for (double[] c : uv) {
                        if (c[0] < -SLACK || c[0] > 1 + SLACK || c[1] < -SLACK || c[1] > 1 + SLACK) {
                            bad.add(obj.getFileName() + ":" + line + " (" + mat + ") uv " + c[0] + "," + c[1] + " is outside the sprite");
                            break;
                        }
                    }
                    if (!obj.getFileName().toString().startsWith("roof_")) {
                        // the game draws a quad as the triangles (0,1,2) and (0,2,3): test every triangle of the fan
                        for (int i = 1; i + 1 < uv.size(); i++) {
                            double[][] tri = {uv.get(0), uv.get(i), uv.get(i + 1)};
                            for (int k = 0; k < 2; k++) {
                                for (double edge : new double[] {0.0, 1.0}) {
                                    boolean all = true;
                                    for (double[] c : tri) {
                                        all &= Math.abs(c[k] - edge) < 1e-5;
                                    }
                                    if (all) {
                                        bad.add(obj.getFileName() + ":" + line + " (" + mat + ") a triangle has every "
                                                + (k == 0 ? "u" : "v") + " exactly " + edge
                                                + ": a column on the sprite edge reads the neighbour sprite");
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(bad.isEmpty(), bad.size() + " bad uv faces, first: " + bad.subList(0, Math.min(8, bad.size())));
    }

    @Test
    void everyObjMaterialIsMappedToATextureOfTheModel() throws IOException {
        Path assets = root().resolve("src/main/resources/assets");
        List<String> bad = new ArrayList<>();
        for (Map.Entry<Path, JsonObject> e : objModels(assets).entrySet()) {
            JsonObject json = e.getValue();
            Path obj = objPath(assets, json.get("model").getAsString());
            List<String> lines = Files.readAllLines(obj);
            Map<String, String> mats = new HashMap<>();
            Set<String> used = new HashSet<>();
            for (String l : lines) {
                String[] t = l.trim().split("\\s+");
                if (t[0].equals("mtllib")) {
                    Path mtl = obj.resolveSibling(t[1]);
                    if (!Files.exists(mtl)) {
                        bad.add(obj.getFileName() + ": mtl file missing " + t[1]);
                        continue;
                    }
                    String cur = null;
                    for (String m : Files.readAllLines(mtl)) {
                        String[] mt = m.trim().split("\\s+");
                        if (mt[0].equals("newmtl")) {
                            cur = mt[1];
                            mats.put(cur, "");
                        } else if (mt[0].equals("map_Kd") && cur != null) {
                            mats.put(cur, mt[1]);
                        }
                    }
                } else if (t[0].equals("usemtl")) {
                    used.add(t[1]);
                }
            }
            JsonObject textures = json.has("textures") ? json.getAsJsonObject("textures") : new JsonObject();
            boolean inherits = json.has("parent");
            for (String m : used) {
                String map = mats.get(m);
                if (map == null) {
                    bad.add(obj.getFileName() + ": usemtl " + m + " has no newmtl");
                } else if (map.isEmpty()) {
                    bad.add(obj.getFileName() + ": material " + m + " has no map_Kd");
                } else if (map.startsWith("#")) {
                    String key = map.substring(1);
                    if (!textures.has(key)) {
                        if (!inherits) {
                            bad.add(e.getKey().getFileName() + ": material " + m + " wants #" + key + " which is not in textures");
                        }
                        continue;
                    }
                    String loc = textures.get(key).getAsString();
                    if (loc.startsWith("murim:") && !Files.exists(assets.resolve("murim/textures/" + loc.substring(6) + ".png"))) {
                        bad.add(e.getKey().getFileName() + ": texture " + loc + " has no png");
                    }
                }
            }
        }
        assertTrue(bad.isEmpty(), bad.size() + " unmapped materials, first: " + bad.subList(0, Math.min(8, bad.size())));
    }

    @Test
    void roundWindowModelsExistAndUseCutoutMipped() throws IOException {
        Path assets = root().resolve("src/main/resources/assets");
        for (String part : new String[] {"bottom_left", "bottom_right", "top_left", "top_right"}) {
            Path json = assets.resolve("murim/models/block/round_window_" + part + ".json");
            assertTrue(Files.exists(json), json.toString());
            JsonObject m = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
            assertTrue("minecraft:cutout_mipped".equals(m.get("render_type").getAsString()), part);
            for (String key : new String[] {"tex", "w", "g"}) {
                assertTrue(m.getAsJsonObject("textures").has(key), part + " has no texture #" + key);
            }
        }
    }
}
