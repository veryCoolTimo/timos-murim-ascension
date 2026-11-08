package io.github.verycooltimo.murim.decor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reference ratios use the round-3 chest width and existing tabletop centres. */
class CavePropSizeTest {
    private static final Path ASSETS = Path.of("src/main/resources/assets/murim");

    private JsonObject model(Path root, String name) throws Exception {
        return JsonParser.parseString(Files.readString(root.resolve(ASSETS)
                .resolve("models/block/" + name + ".json"))).getAsJsonObject();
    }

    private static final double UNIT = 5.6 / 24;

    private double size(JsonObject e, int axis) {
        return e.getAsJsonArray("to").get(axis).getAsDouble()
                - e.getAsJsonArray("from").get(axis).getAsDouble();
    }

    @Test
    void referenceRatiosKeepTheChestWidthAndTableCentres() throws Exception {
        Path root = CaveTestFiles.root();
        for (String name : new String[]{"pill_crate", "pill_crate_lid", "pill_crate_item",
                "pill_crate_pill", "pill_crate_pale", "wine_jar"}) {
            var data = model(root, name);
            assertFalse(data.has("loader"));
            assertFalse(data.has("transform"));
            double[] min = {100, 100, 100}, max = {-100, -100, -100};
            for (var value : data.getAsJsonArray("elements")) {
                var e = value.getAsJsonObject();
                assertEquals(6, e.getAsJsonObject("faces").size());
                for (int axis = 0; axis < 3; axis++) {
                    assertTrue(size(e, axis) > 0);
                    min[axis] = Math.min(min[axis], e.getAsJsonArray("from").get(axis).getAsDouble());
                    max[axis] = Math.max(max[axis], e.getAsJsonArray("to").get(axis).getAsDouble());
                }
                if (!name.equals("wine_jar")) assertFalse(e.has("rotation"));
                // Round 6 ropes cross at different front/back heights, so their
                // combined bounds are asymmetric. Placement follows the ceramic.
                if (name.equals("wine_jar") && e.get("name").getAsString().startsWith("jar_body_")) {
                    for (int axis : new int[]{0, 2}) {
                        assertEquals(8, (e.getAsJsonArray("from").get(axis).getAsDouble()
                                + e.getAsJsonArray("to").get(axis).getAsDouble())/2, 1e-5,
                                "Every ceramic tier part stays centred: " + e.get("name").getAsString());
                    }
                }
                if (e.has("name") && e.get("name").getAsString().startsWith("lid_step_")) {
                    assertEquals(5.6, size(e, 0), 1e-5, "Every shell part spans the existing chest width");
                }
            }
            if (name.equals("pill_crate") || name.equals("wine_jar")) {
                assertEquals(0, min[1], 1e-5);
            }
            if (name.equals("pill_crate")) {
                assertEquals(8, (min[0]+max[0])/2, 1e-5);
                assertEquals(8, (min[2]+max[2])/2, 1e-5);
            }
            if (name.equals("pill_crate")) assertEquals(10*UNIT, max[1], 1e-5);
            if (name.equals("pill_crate_item")) assertEquals(18.5*UNIT, max[1], 1e-5);
            // Source: tools/art/chungmyung_cave.py#jar: body 1.08W + cloth crown .05W.
            if (name.equals("wine_jar")) assertEquals((1.08+.05)*19.2*UNIT*1.5, max[1], 1e-5);
        }
    }

    @Test
    void referenceSilhouettesHaveSteppedRoofThreeBandsAndRaisedBelly() throws Exception {
        Path root = CaveTestFiles.root();
        int steps = 0;
        var bands = new HashSet<String>();
        double previousDepth = Double.POSITIVE_INFINITY;
        for (var value : model(root, "pill_crate_lid").getAsJsonArray("elements")) {
            var e = value.getAsJsonObject();
            if (!e.has("name")) continue;
            String name = e.get("name").getAsString();
            if (name.startsWith("lid_step_")) {
                double depth = e.getAsJsonArray("to").get(2).getAsDouble()
                        - e.getAsJsonArray("from").get(2).getAsDouble();
                assertTrue(depth < previousDepth, "Roof narrows at every upward step");
                double[] depths = {16, 14, 12, 8, 4};
                double[] heights = {2, 2, 2, 1, 1};
                assertEquals(depths[steps]*UNIT, depth, 1e-5);
                assertEquals(heights[steps]*UNIT, size(e, 1), 1e-5);
                previousDepth = depth;
                steps++;
            }
            if (name.startsWith("lid_band_")) bands.add(name.split("_")[2]);
        }
        assertEquals(5, steps);
        assertEquals(3, bands.size());
        var bodyBands = new HashSet<String>();
        for (var value : model(root, "pill_crate").getAsJsonArray("elements")) {
            var e = value.getAsJsonObject();
            if (e.has("name") && e.get("name").getAsString().startsWith("band_"))
                bodyBands.add(e.get("name").getAsString().split("_")[1]);
        }
        assertEquals(bands, bodyBands, "All three bands continue down the body");
    }

    @Test
    void jarHasTallCentralBellyConnectedLabelThinClothAndLowCrossedRopes() throws Exception {
        var elements = model(CaveTestFiles.root(), "wine_jar").getAsJsonArray("elements");
        var tiers = new java.util.TreeMap<Integer, java.util.List<JsonObject>>();
        var loops = new HashSet<String>();
        var flaps = new HashSet<String>();
        double labelBottom = 100, labelTop = 0, crossingY = -1, totalTop = 0;
        int labels = 0, returns = 0, ties = 0;
        for (var value : elements) {
            var e = value.getAsJsonObject();
            String name = e.get("name").getAsString();
            totalTop = Math.max(totalTop, e.getAsJsonArray("to").get(1).getAsDouble());
            if (name.startsWith("jar_body_")) {
                int tier = Integer.parseInt(name.split("_")[2]);
                tiers.computeIfAbsent(tier, key -> new java.util.ArrayList<>()).add(e);
            }
            if (name.startsWith("jar_label_") && name.endsWith("front")) {
                labels++;
                assertEquals(8, (e.getAsJsonArray("from").get(0).getAsDouble()
                        + e.getAsJsonArray("to").get(0).getAsDouble())/2, 1e-5,
                        "Every label strip is horizontally centred");
                assertFalse(e.has("rotation"), "Single front panel");
                double bottom = e.getAsJsonArray("from").get(1).getAsDouble();
                if (labelTop > 0) assertEquals(labelTop, bottom, 1e-5, "Paper has no vertical gaps");
                labelBottom = Math.min(labelBottom, bottom);
                labelTop = e.getAsJsonArray("to").get(1).getAsDouble();
                assertTrue(e.getAsJsonArray("to").get(2).getAsDouble() < 8, "Label faces north, like chest front");
            }
            if (name.startsWith("jar_label_") && name.endsWith("return")) returns++;
            if (name.startsWith("cloth_tie_")) {
                ties++;
                for (int axis = 0; axis < 3; axis++)
                    assertEquals(.012*19.2*UNIT*1.5, size(e, axis), 1e-5, "Thin red neck tie");
                assertEquals("#rope", e.getAsJsonObject("faces").getAsJsonObject("north")
                        .get("texture").getAsString());
            }
            if (name.startsWith("cloth_flap_")) {
                flaps.add(name.split("_")[2]);
                assertEquals(.025*19.2*UNIT*1.5, size(e, 1), 1e-5, "Thin stepped fabric");
            }
            assertFalse(name.contains("_wing_"), "No side label panels");
            assertFalse(name.startsWith("neck_cord_"), "No horizontal rope belts");
            if (name.startsWith("rope_loop_")) {
                loops.add(name.split("_")[2]);
                if (name.equals("rope_loop_0_link_0_0"))
                    crossingY = (e.getAsJsonArray("from").get(1).getAsDouble()
                            + e.getAsJsonArray("to").get(1).getAsDouble())/2;
                for (int axis = 0; axis < 3; axis++) {
                    assertEquals(.03*19.2*UNIT*1.5, size(e, axis), 1e-5, "Rope width is .03W");
                }
            }
        }
        assertEquals(25, tiers.size());
        double widest = 0, widestY = 0, top = 0, previousTop = 0;
        for (var entry : tiers.entrySet()) {
            var parts = entry.getValue();
            assertEquals(4, parts.size(), "Two perpendicular pairs tile the regular octagon");
            double width = Math.max(size(parts.getFirst(), 0), size(parts.getFirst(), 2));
            int rotated = 0;
            for (var e : parts) {
                assertEquals(width, Math.max(size(e, 0), size(e, 2)), 1e-5);
                assertEquals(width*(Math.sqrt(2)-1), Math.min(size(e, 0), size(e, 2)), 1e-5);
                assertEquals(previousTop, e.getAsJsonArray("from").get(1).getAsDouble(), 1e-5);
                if (e.has("rotation")) {
                    var rotation = e.getAsJsonObject("rotation");
                    assertEquals("y", rotation.get("axis").getAsString());
                    assertEquals(45, rotation.get("angle").getAsDouble());
                    assertFalse(rotation.get("rescale").getAsBoolean());
                    assertEquals(8, rotation.getAsJsonArray("origin").get(0).getAsDouble());
                    assertEquals(8, rotation.getAsJsonArray("origin").get(2).getAsDouble());
                    rotated++;
                }
            }
            assertEquals(2, rotated);
            var e = parts.getFirst();
            top = e.getAsJsonArray("to").get(1).getAsDouble();
            if (width > widest) {
                widest = width;
                widestY = (previousTop+top)/2;
            }
            previousTop = top;
        }
        assertTrue(widestY/top >= .45 && widestY/top <= .55);
        assertEquals(.50*widest, size(tiers.firstEntry().getValue().getFirst(), 0), 1e-5);
        assertEquals(.40*widest, size(tiers.lastEntry().getValue().getFirst(), 0), 1e-5);
        assertEquals(.05*widest, size(tiers.firstEntry().getValue().getFirst(), 1), 1e-5);
        assertTrue(top/widest >= 1.05 && top/widest <= 1.10);
        assertEquals(19.2*UNIT*1.5, widest, 1e-5);
        assertEquals(.54*widest, labelTop-labelBottom, 1e-5);
        assertEquals(.52*top, (labelTop+labelBottom)/2, 1e-5);
        assertTrue(labels >= 10, "Paper spans the belly");
        assertEquals(labels-1, returns, "Returns join the paper across every tier step");
        assertEquals(5, flaps.size(), "Five unequal hanging folds");
        assertEquals(128, ties, "Continuous fine cord around the neck");
        assertEquals(.25*top, crossingY, 1e-5);
        assertTrue(crossingY < labelBottom, "X crosses below the label");
        assertTrue(totalTop/widest >= 1.10 && totalTop/widest <= 1.16);
        assertTrue(totalTop/(18.5*UNIT) >= 1.725 && totalTop/(18.5*UNIT) <= 1.80);
        double neck = size(tiers.lastEntry().getValue().getFirst(), 1)/widest;
        assertTrue(neck >= .06 && neck <= .09);
        double[][] envelope = {{.10, .70}, {.30, .93}, {.50, 1}, {.70, .91}, {.85, .70}, {.97, .40}};
        for (double[] target : envelope) {
            double y = target[0]*top;
            for (var parts : tiers.values()) {
                var e = parts.getFirst();
                if (e.getAsJsonArray("from").get(1).getAsDouble() <= y
                        && y < e.getAsJsonArray("to").get(1).getAsDouble())
                    assertEquals(target[1], size(e, 0)/widest, .05, "Reference tier envelope");
            }
        }
        assertEquals(java.util.Set.of("0", "1"), loops);
    }

    @Test
    void jarTierTreadsUseTheSameContinuousGlazeAsSides() throws Exception {
        var root = CaveTestFiles.root();
        for (int sink = 0; sink <= 8; sink++) {
            var jar = model(root, "wine_jar" + (sink == 0 ? "" : "_sink_" + sink));
            assertFalse(jar.get("ambientocclusion").getAsBoolean());
            var previous = new java.util.HashMap<String, JsonArray>();
            for (var value : jar.getAsJsonArray("elements")) {
                var e = value.getAsJsonObject();
                String name = e.get("name").getAsString();
                if (!name.startsWith("jar_body_")) continue;
                assertTrue(e.get("shade").getAsBoolean(), "Body uses vanilla face shading");
                var faces = e.getAsJsonObject("faces");
                var side = faces.getAsJsonObject("north");
                for (String direction : new String[]{"up", "down", "south", "west", "east"}) {
                    var face = faces.getAsJsonObject(direction);
                    assertEquals(side.get("uv"), face.get("uv"),
                            "Tier ends sample the continuous glaze region: " + name);
                    assertEquals(direction.equals("up") ? "#clay_top" : "#clay",
                            face.get("texture").getAsString());
                }
                var uv = side.getAsJsonArray("uv");
                assertEquals(0, uv.get(0).getAsDouble());
                assertEquals(16, uv.get(2).getAsDouble());
                String facet = name.substring(name.lastIndexOf('_')+1);
                var below = previous.put(facet, uv);
                if (below != null) assertEquals(below.get(1).getAsDouble(), uv.get(3).getAsDouble(),
                        1e-5, "Glaze V joins the tier below without an edge stripe");
            }
            assertEquals(4, previous.size());
        }
    }

    @Test
    void mouthClothFitsSmallRotatedEnvelopeAndGlazeHasNoSpeckle() throws Exception {
        var elements = model(CaveTestFiles.root(), "wine_jar").getAsJsonArray("elements");
        double width = 19.2*UNIT*1.5, bodyTop = 1.08*width;
        double[] min = {100, 100}, max = {-100, -100};
        for (var value : elements) {
            var e = value.getAsJsonObject();
            if (!e.get("name").getAsString().startsWith("cloth_")) continue;
            assertTrue(e.getAsJsonArray("from").get(1).getAsDouble()
                    >= bodyTop-.12*width-1e-5, "Flaps leave glazed shoulder visible");
            assertTrue(e.getAsJsonArray("to").get(1).getAsDouble()
                    <= bodyTop+.05*width+1e-5, "Crumpled centre rises only .05W");
            double angle = e.has("rotation")
                    ? Math.toRadians(e.getAsJsonObject("rotation").get("angle").getAsDouble()) : 0;
            for (String xBound : new String[]{"from", "to"}) {
                for (String zBound : new String[]{"from", "to"}) {
                    double x = e.getAsJsonArray(xBound).get(0).getAsDouble()-8;
                    double z = e.getAsJsonArray(zBound).get(2).getAsDouble()-8;
                    double[] corner = {x*Math.cos(angle)+z*Math.sin(angle),
                            z*Math.cos(angle)-x*Math.sin(angle)};
                    for (int axis = 0; axis < 2; axis++) {
                        min[axis] = Math.min(min[axis], corner[axis]);
                        max[axis] = Math.max(max[axis], corner[axis]);
                    }
                }
            }
        }
        for (int axis = 0; axis < 2; axis++) assertTrue(max[axis]-min[axis] <= .60*width);
        var glaze = ImageIO.read(CaveTestFiles.root().resolve(ASSETS)
                .resolve("textures/block/cave_clay.png").toFile());
        // Broad patches have few transitions per scanline; dotted noise cannot pass.
        for (int y = 0; y < 32; y++) {
            int transitions = 0;
            for (int x = 1; x < 32; x++)
                if (glaze.getRGB(x, y) != glaze.getRGB(x-1, y)) transitions++;
            assertTrue(transitions <= 4, "Calm glaze has at most two broad patches per row");
            if (y >= 29) for (int x = 0; x < 32; x++)
                assertEquals(0xFF2E1F24, glaze.getRGB(x, y), "Continuous dark foot");
        }
    }

    @Test
    void meanRenderedJarGlazeStaysWithinEightDeltaEOfDarkPlum() throws Exception {
        var root = CaveTestFiles.root();
        var jar = model(root, "wine_jar");
        var side = ImageIO.read(root.resolve(ASSETS).resolve("textures/block/cave_clay.png").toFile());
        var top = ImageIO.read(root.resolve(ASSETS).resolve("textures/block/cave_clay_top.png").toFile());
        assertNotNull(side);
        assertNotNull(top);
        assertEquals("murim:block/cave_clay_top", jar.getAsJsonObject("textures")
                .get("clay_top").getAsString());
        // Texture-level regression, independent of camera/occlusion/lightmap.
        // Five exposed directions, equal sprite coverage; omit the hidden underside.
        // Verified in 1.21.1 ClientLevel#getShade: UP=1, N/S=.8, E/W=.6.
        double[] mean = new double[3];
        for (int y = 0; y < side.getHeight(); y++) {
            for (int x = 0; x < side.getWidth(); x++) {
                int rgb = side.getRGB(x, y), up = top.getRGB(x, y);
                for (int channel = 0; channel < 3; channel++) {
                    int shift = 16-8*channel;
                    int value = (rgb >> shift) & 255;
                    int topValue = (up >> shift) & 255;
                    assertEquals(Math.round(value*.8), topValue, "Treads compensate for brighter UP shading");
                    mean[channel] += (topValue + 2*.8*value + 2*.6*value)/5;
                }
            }
        }
        for (int channel = 0; channel < 3; channel++) mean[channel] /= side.getWidth()*side.getHeight();
        double[] actual = lab(mean), target = lab(new double[]{0x4A, 0x32, 0x38});
        double deltaE = Math.sqrt(Math.pow(actual[0]-target[0], 2)
                + Math.pow(actual[1]-target[1], 2) + Math.pow(actual[2]-target[2], 2));
        assertTrue(deltaE <= 8, "Mean face-shaded glaze must stay within CIE76 ΔE 8: " + deltaE);
    }

    /** sRGB -> CIELAB, D65 reference white; ΔE uses the CIE76 Euclidean distance. */
    private static double[] lab(double[] rgb) {
        double[] linear = new double[3];
        for (int i = 0; i < 3; i++) {
            double c = rgb[i]/255;
            linear[i] = c <= .04045 ? c/12.92 : Math.pow((c+.055)/1.055, 2.4);
        }
        double x = labCurve((.4124564*linear[0]+.3575761*linear[1]+.1804375*linear[2])/.95047);
        double y = labCurve(.2126729*linear[0]+.7151522*linear[1]+.0721750*linear[2]);
        double z = labCurve((.0193339*linear[0]+.1191920*linear[1]+.9503041*linear[2])/1.08883);
        return new double[]{116*y-16, 500*(x-y), 200*(y-z)};
    }

    private static double labCurve(double value) {
        double delta = 6.0/29;
        return value > delta*delta*delta ? Math.cbrt(value) : value/(3*delta*delta)+4.0/29;
    }

    @Test
    void frontLabelStaysMostlyVisibleAboveTheRopeCrossing() throws Exception {
        var elements = model(CaveTestFiles.root(), "wine_jar").getAsJsonArray("elements");
        var ropes = new java.util.ArrayList<JsonObject>();
        double bottom = 100, top = 0, halfWidth = 0;
        for (var value : elements) {
            var e = value.getAsJsonObject();
            String name = e.get("name").getAsString();
            if (name.startsWith("jar_label_") && name.endsWith("front")) {
                bottom = Math.min(bottom, e.getAsJsonArray("from").get(1).getAsDouble());
                top = Math.max(top, e.getAsJsonArray("to").get(1).getAsDouble());
                halfWidth = Math.max(halfWidth, size(e, 0)/2);
            }
            if (name.startsWith("rope_loop_") && e.getAsJsonArray("to").get(2).getAsDouble() < 8)
                ropes.add(e);
        }
        assertEquals(.38*19.2*UNIT*1.5, 2*halfWidth, 1e-5);
        assertFalse(ropes.isEmpty());
        // Conservative front projection: count every front rope even if hidden by ceramic.
        int covered = 0;
        for (int row = 0; row < 80; row++) {
            double y = bottom+(row+.5)*(top-bottom)/80;
            for (int col = 0; col < 60; col++) {
                double x = 8-halfWidth+(col+.5)*2*halfWidth/60;
                for (var rope : ropes) {
                    if (x >= rope.getAsJsonArray("from").get(0).getAsDouble()
                            && x <= rope.getAsJsonArray("to").get(0).getAsDouble()
                            && y >= rope.getAsJsonArray("from").get(1).getAsDouble()
                            && y <= rope.getAsJsonArray("to").get(1).getAsDouble()) {
                        assertTrue(y < bottom+.5*(top-bottom), "Rope only crosses lower paper");
                        covered++;
                        break;
                    }
                }
            }
        }
        assertTrue(covered > 0, "The lower paper meets the front X");
        assertTrue(covered <= .30*60*80, "At least 70 percent of the paper remains visible");
    }

    @Test
    void solidShellAndStrapsShareOneHingeTransformAndPelletsStaySeparate() throws Exception {
        Path root = CaveTestFiles.root();
        var lid = model(root, "pill_crate_lid").getAsJsonArray("elements");
        assertEquals(50, lid.size(), "Five wooden parts plus three straps with five shallow U sections");
        int shells = 0, straps = 0;
        for (var value : lid) {
            var e = value.getAsJsonObject();
            assertFalse(e.has("rotation"), "All lid geometry uses the renderer's shared hinge");
            String name = e.get("name").getAsString();
            if (name.startsWith("lid_step_")) shells++;
            if (name.startsWith("lid_band_")) {
                straps++;
                assertEquals(4*UNIT, size(e, 0), 1e-5);
                assertEquals(.5*UNIT, size(e, name.endsWith("top") ? 1 : 2), 1e-5);
            }
        }
        assertEquals(5, shells);
        assertEquals(45, straps);
        String renderer = Files.readString(root.resolve(
                "src/main/java/io/github/verycooltimo/murim/client/decor/PillBoxClient.java"));
        int pivot = renderer.indexOf("pose.translate(0.0F, hingeY, hingeZ)");
        int turn = renderer.indexOf("pose.mulPose(Axis.XP.rotationDegrees(angle))", pivot);
        int draw = renderer.indexOf("state, model,", turn);
        assertTrue(pivot > 0 && turn > pivot && draw > turn);
        assertFalse(renderer.substring(turn, draw).contains("popPose"));
        assertTrue(renderer.contains("be.isCrate() ? CRATE_LID : LID"));
        assertTrue(renderer.contains("be.isCrate() ? 28 : 5"));
        assertTrue(renderer.contains("i % 6 == 2"), "Five pale accents among 28 pellets");
        for (String name : new String[]{"pill_crate_pill", "pill_crate_pale"}) {
            assertEquals(2, model(root, name).getAsJsonArray("elements").size());
        }
    }

    @Test
    void blockstatesAndItemsResolveTheCuboidModels() throws Exception {
        Path root = CaveTestFiles.root();
        for (String name : new String[]{"pill_crate", "wine_jar"}) {
            var states = JsonParser.parseString(Files.readString(root.resolve(ASSETS)
                    .resolve("blockstates/" + name + ".json"))).getAsJsonObject();
            for (var entry : states.getAsJsonObject("variants").entrySet()) {
                String target = entry.getValue().getAsJsonObject().get("model").getAsString();
                assertTrue(target.startsWith("murim:block/"));
                var resolved = model(root, target.substring("murim:block/".length()));
                assertFalse(resolved.has("loader"));
                assertTrue(resolved.has("elements"));
            }
            var item = JsonParser.parseString(Files.readString(root.resolve(ASSETS)
                    .resolve("models/item/" + name + ".json"))).getAsJsonObject();
            assertEquals("murim:block/" + (name.equals("pill_crate") ? "pill_crate_item" : name),
                    item.get("parent").getAsString());
        }
    }

    @Test
    void everyUsedTextureHasPixelDetailAndEverySinkUsesCuboids() throws Exception {
        Path root = CaveTestFiles.root();
        var textures = new HashSet<String>();
        for (String name : new String[]{"pill_crate", "wine_jar", "pill_crate_lid", "pill_crate_item",
                "pill_crate_pill", "pill_crate_pale"}) {
            var base = model(root, name);
            for (var texture : base.getAsJsonObject("textures").entrySet()) {
                textures.add(texture.getValue().getAsString());
            }
            for (var value : base.getAsJsonArray("elements")) {
                var e = value.getAsJsonObject();
                for (var face : e.getAsJsonObject("faces").entrySet()) {
                    var uv = face.getValue().getAsJsonObject().getAsJsonArray("uv");
                    String faceTexture = face.getValue().getAsJsonObject().get("texture").getAsString();
                    // jar() gives both glaze sprites the same continuous UV field.
                    if (faceTexture.equals("#clay") || faceTexture.equals("#clay_top")) {
                        for (var coordinate : uv) assertTrue(coordinate.getAsDouble() >= 0
                                && coordinate.getAsDouble() <= 16, "Glaze stays within its sprite");
                        if (!face.getKey().equals("up") && !face.getKey().equals("down")) {
                            double height = 1.08*19.2*UNIT*1.5;
                            assertEquals(16*(1-e.getAsJsonArray("to").get(1).getAsDouble()/height),
                                    uv.get(1).getAsDouble(), 2e-5);
                            assertEquals(16*(1-e.getAsJsonArray("from").get(1).getAsDouble()/height),
                                    uv.get(3).getAsDouble(), 2e-5);
                        }
                        continue;
                    }
                    int uAxis = switch (face.getKey()) { case "east", "west" -> 2; default -> 0; };
                    int vAxis = switch (face.getKey()) { case "up", "down" -> 2; default -> 1; };
                    assertEquals(size(e, uAxis)/(name.equals("wine_jar") ? 1.5 : 1), uv.get(2).getAsDouble()-uv.get(0).getAsDouble(), 2e-5);
                    assertEquals(size(e, vAxis)/(name.equals("wine_jar") ? 1.5 : 1), uv.get(3).getAsDouble()-uv.get(1).getAsDouble(), 2e-5);
                }
            }
            if (!name.equals("pill_crate") && !name.equals("wine_jar")) continue;
            for (int sink = 1; sink <= 8; sink++) {
                var lowered = model(root, name + "_sink_" + sink);
                assertFalse(lowered.has("loader"));
                assertFalse(lowered.has("transform"));
                var original = base.getAsJsonArray("elements");
                var actual = lowered.getAsJsonArray("elements");
                assertEquals(original.size(), actual.size());
                for (int i = 0; i < original.size(); i++) {
                    assertEquals(original.get(i).getAsJsonObject().getAsJsonObject("faces"),
                            actual.get(i).getAsJsonObject().getAsJsonObject("faces"), "Sink must not slide the texture");
                    var e = original.get(i).getAsJsonObject();
                    if (e.has("rotation")) {
                        var rotation = actual.get(i).getAsJsonObject().getAsJsonObject("rotation");
                        assertEquals(e.getAsJsonObject("rotation").getAsJsonArray("origin").get(1).getAsDouble()-sink,
                                rotation.getAsJsonArray("origin").get(1).getAsDouble(), 1e-5);
                    }
                    for (String bound : new String[]{"from", "to"}) {
                        for (int axis = 0; axis < 3; axis++) {
                            double expected = original.get(i).getAsJsonObject().getAsJsonArray(bound).get(axis).getAsDouble();
                            assertEquals(expected - (axis == 1 ? sink : 0), actual.get(i).getAsJsonObject()
                                    .getAsJsonArray(bound).get(axis).getAsDouble(), 1e-5);
                        }
                    }
                }
            }
        }
        for (String texture : textures) {
            assertTrue(texture.startsWith("murim:block/cave_"), texture);
            var image = ImageIO.read(root.resolve(ASSETS).resolve("textures/"
                    + texture.substring("murim:".length()) + ".png").toFile());
            assertNotNull(image, texture);
            assertEquals(32, image.getWidth(), texture);
            assertEquals(32, image.getHeight(), texture);
            var colors = new HashSet<Integer>();
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) colors.add(image.getRGB(x, y));
            }
            assertTrue(colors.size() >= 3, texture + " must contain material detail");
            var palette = java.util.Map.of(
                    "wood", new int[]{0x786047, 0x967B57, 0x4D3D2D},
                    "inner", new int[]{0x786047, 0x967B57, 0x4D3D2D},
                    "band", new int[]{0xE6D29A, 0xF7E8B5, 0xB29A68},
                    // tools/art/chungmyung_cave.py#textures: top channels are rounded at 80%.
                    "clay", new int[]{0x4A3238, 0x63464D, 0x2E1F24},
                    "clay_top", new int[]{0x3B282D, 0x4F383E, 0x25191D},
                    "cloth", new int[]{0xB0A68C, 0xD0C6AA, 0x817B68},
                    "label", new int[]{0x873D35, 0xA05243, 0x642D2B},
                    "rope", new int[]{0x602A35, 0x8A4650, 0x32151D},
                    "pill", new int[]{0xBA6475, 0xCF8190, 0xA64F60},
                    "pale", new int[]{0xF4CCD9, 0xFFF0F3, 0xCF8190});
            String material = texture.substring("murim:block/cave_".length());
            for (int rgb : palette.get(material)) {
                assertTrue(colors.contains(0xFF000000 | rgb), texture + " reference palette");
            }
        }
    }
}
