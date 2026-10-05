package io.github.verycooltimo.murim.client.bedrock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Куб с UV по граням (геометрия ученика) обязан совпадать с ванильным box-кубом, когда его грани — развёртка
 * Blockbench того же box UV: так проверено соответствие граней Bedrock ↔ Java и порядок углов.
 */
class FaceCubeTest {

    /** Вершины, как их пишет ModelPart: позиция и UV. */
    static final class Capture implements VertexConsumer {
        final List<float[]> out = new ArrayList<>();
        private float[] cur;

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            cur = new float[] {x, y, z, 0, 0};
            out.add(cur);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            cur[3] = u;
            cur[4] = v;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    }

    private static List<float[]> draw(ModelPart.Cube cube) {
        Capture c = new Capture();
        cube.compile(new PoseStack().last(), c, 0, 0, -1);
        return c.out;
    }

    /** Грани Blockbench для box UV (u, v) куба w×h×d — как их пишет импорт в geo (up/down: uv = правый нижний угол). */
    private static JsonObject faces(int u, int v, float w, float h, float d) {
        float[][] r = {
                {u + d + w, v + d, u + d, v}, {u + d + 2 * w, v, u + d + w, v + d}, // up, down
                {u, v + d, u + d, v + d + h}, {u + d, v + d, u + d + w, v + d + h}, // east, north
                {u + d + w, v + d, u + 2 * d + w, v + d + h}, {u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h}}; // west, south
        String[] n = {"up", "down", "east", "north", "west", "south"};
        StringBuilder s = new StringBuilder("{");
        for (int i = 0; i < 6; i++) {
            float[] q = r[i];
            boolean ud = i < 2;
            float au = ud ? q[2] : q[0];
            float av = ud ? q[3] : q[1];
            float su = ud ? q[0] - q[2] : q[2] - q[0];
            float sv = ud ? q[1] - q[3] : q[3] - q[1];
            s.append(i > 0 ? "," : "").append('"').append(n[i]).append("\":{\"uv\":[").append(au).append(',').append(av)
                    .append("],\"uv_size\":[").append(su).append(',').append(sv).append("]}");
        }
        return JsonParser.parseString(s.append('}').toString()).getAsJsonObject();
    }

    @Test
    @DisplayName("Куб с UV по граням = ванильный box-куб при той же развёртке")
    void faceCubeMatchesVanillaBox() {
        float[] s = {3, 5, 2};
        ModelPart.Cube vanilla = new ModelPart.Cube(10, 20, -1.5F, -2.0F, -1.0F, s[0], s[1], s[2], 0.25F, 0.25F, 0.25F, false,
                64, 64, EnumSet.allOf(Direction.class));
        ModelPart.Cube faces = new BedrockGeo.FaceCube(-1.5F, -2.0F, -1.0F, s, 0.25F, faces(10, 20, 3, 5, 2), 64, 64);
        List<float[]> a = draw(vanilla);
        List<float[]> b = draw(faces);
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            for (int k = 0; k < 5; k++) {
                assertEquals(a.get(i)[k], b.get(i)[k], 1e-5, "вершина " + i + " компонент " + k);
            }
        }
    }

    @Test
    @DisplayName("Ученик: каждый куб с дробным размером — с гранями из файла Blockbench, а не с box UV")
    void fractionalCubesUseStoredFaces() throws IOException {
        Path p = Path.of("src/main/resources/assets/murim/bedrock/sect_disciple.geo.json");
        if (!Files.exists(p)) {
            p = Path.of("../../" + p);
        }
        var bones = JsonParser.parseString(Files.readString(p)).getAsJsonObject().getAsJsonArray("minecraft:geometry")
                .get(0).getAsJsonObject().getAsJsonArray("bones");
        for (var b : bones) {
            if (!b.getAsJsonObject().has("cubes")) {
                continue;
            }
            for (var c : b.getAsJsonObject().getAsJsonArray("cubes")) {
                JsonObject cube = c.getAsJsonObject();
                boolean fractional = false;
                for (var x : cube.getAsJsonArray("size")) {
                    fractional |= x.getAsFloat() != Math.floor(x.getAsFloat());
                }
                if (fractional) {
                    assertTrue(cube.get("uv").isJsonObject(), b.getAsJsonObject().get("name") + ": box UV у дробного куба");
                }
            }
        }
    }
}
