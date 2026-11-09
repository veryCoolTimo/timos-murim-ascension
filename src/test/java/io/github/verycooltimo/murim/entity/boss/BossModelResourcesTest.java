package io.github.verycooltimo.murim.entity.boss;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Модель хозяина крепости (9.11, tools/art/boss_model_1109.py): кости клипов на месте, холст текстур совпадает с
 * заявленным, все развёртки лежат внутри холста, у каждой новой кости есть родитель.
 */
class BossModelResourcesTest {

    private static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("src/main/resources/assets/murim/bedrock/fortress_master.geo.json"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("source tree not found from " + Path.of("").toAbsolutePath());
        }
        return p;
    }

    private static JsonObject geo() throws IOException {
        Path f = root().resolve("src/main/resources/assets/murim/bedrock/fortress_master.geo.json");
        return JsonParser.parseString(Files.readString(f)).getAsJsonObject().getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
    }

    @Test
    @DisplayName("every bone the clips animate still exists in the geometry")
    void animatedBonesExist() throws IOException {
        JsonObject g = geo();
        Set<String> names = new HashSet<>();
        for (JsonElement b : g.getAsJsonArray("bones")) {
            names.add(b.getAsJsonObject().get("name").getAsString());
        }
        Path f = root().resolve("src/main/resources/assets/murim/bedrock/fortress_master.animation.json");
        JsonObject anims = JsonParser.parseString(Files.readString(f)).getAsJsonObject().getAsJsonObject("animations");
        for (String clip : anims.keySet()) {
            JsonObject bones = anims.getAsJsonObject(clip).getAsJsonObject("bones");
            if (bones == null) {
                continue;
            }
            for (String bone : bones.keySet()) {
                assertTrue(names.contains(bone), clip + " animates missing bone " + bone);
            }
        }
        for (JsonElement b : g.getAsJsonArray("bones")) {
            JsonObject o = b.getAsJsonObject();
            if (o.has("parent")) {
                assertTrue(names.contains(o.get("parent").getAsString()), o.get("name").getAsString() + " has no parent bone");
            }
        }
    }

    @Test
    @DisplayName("texture sheets match the declared canvas and every cube net fits inside it")
    void netsInsideCanvas() throws IOException {
        JsonObject g = geo();
        int tw = g.getAsJsonObject("description").get("texture_width").getAsInt();
        int th = g.getAsJsonObject("description").get("texture_height").getAsInt();
        for (String name : new String[] {"fortress_master", "fortress_master_boil"}) {
            BufferedImage im = ImageIO.read(root().resolve("src/main/resources/assets/murim/textures/entity/" + name + ".png").toFile());
            assertEquals(tw, im.getWidth(), name + " width");
            assertEquals(th, im.getHeight(), name + " height");
        }
        for (JsonElement be : g.getAsJsonArray("bones")) {
            JsonArray cubes = be.getAsJsonObject().getAsJsonArray("cubes");
            if (cubes == null) {
                continue;
            }
            for (JsonElement ce : cubes) {
                JsonObject c = ce.getAsJsonObject();
                JsonArray uv = c.getAsJsonArray("uv");
                JsonArray s = c.getAsJsonArray("size");
                double w = s.get(0).getAsDouble(), h = s.get(1).getAsDouble(), d = s.get(2).getAsDouble();
                double right = uv.get(0).getAsDouble() + 2 * (w + d);
                double bottom = uv.get(1).getAsDouble() + d + h;
                assertTrue(right <= tw + 1e-6 && bottom <= th + 1e-6, "net outside canvas in " + be.getAsJsonObject().get("name"));
            }
        }
    }

    @Test
    @DisplayName("boil-only growths are transparent in the base sheet and painted in the boil sheet")
    void boilGrowthsOnlyInBoilSheet() throws IOException {
        BufferedImage base = ImageIO.read(root().resolve("src/main/resources/assets/murim/textures/entity/fortress_master.png").toFile());
        BufferedImage boil = ImageIO.read(root().resolve("src/main/resources/assets/murim/textures/entity/fortress_master_boil.png").toFile());
        int onlyBoil = 0;
        for (int y = 0; y < base.getHeight(); y++) {
            for (int x = 0; x < base.getWidth(); x++) {
                boolean a = (base.getRGB(x, y) >>> 24) > 0;
                boolean b = (boil.getRGB(x, y) >>> 24) > 0;
                assertTrue(!a || b, "texel painted in the base sheet but empty in the boil sheet: " + x + "," + y);
                if (b && !a) {
                    onlyBoil++;
                }
            }
        }
        assertTrue(onlyBoil > 100, "boil growths missing from the boil sheet");
    }
}
