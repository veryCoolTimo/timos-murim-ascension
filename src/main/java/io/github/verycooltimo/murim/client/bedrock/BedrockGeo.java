package io.github.verycooltimo.murim.client.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Модель Bedrock ({@code *.geo.json}, формат 1.12.0, box UV) → ванильный {@link LayerDefinition}.
 *
 * <p>Зачем своё, а не GeckoLib (ADR-23): GeckoLib в сборке пока нет, а его подключение —
 * новая зависимость (режим «ask first»). Врагу этапа M1 нужны только кубы, кости и ключевые
 * кадры — это ванильная иерархическая модель с {@code KeyframeAnimations}. Файлы остаются в
 * формате Blockbench: автор правит их там же и кладёт обратно, без конвертации в Java.
 *
 * <p>Пересчёт координат сверен с ванильной моделью игрока (Bedrock {@code rightArm} pivot
 * [-5, 22, 0] ↔ Java {@code PartPose.offset(-5, 2, 0)}): x и z совпадают, y у Java идёт вниз
 * от высоты 24. Повороты костей — те же градусы (ванильные Bedrock-анимации гуманоида дают те же
 * знаки, что Java-код HumanoidModel: лук −5,73° / +28,65°, покачивание рук ±2,865°).
 */
public final class BedrockGeo {

    /** Читает модель из ресурсов мода (classpath): слой нужен до загрузки ресурс-паков. */
    public static LayerDefinition load(String classpath) {
        try (InputStream in = BedrockGeo.class.getResourceAsStream(classpath)) {
            if (in == null) {
                throw new IllegalStateException("Нет модели " + classpath);
            }
            return parse(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Не прочитать модель " + classpath, e);
        }
    }

    static LayerDefinition parse(JsonObject root) {
        JsonObject geo = root.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        JsonObject desc = geo.getAsJsonObject("description");
        int texW = desc.get("texture_width").getAsInt();
        int texH = desc.get("texture_height").getAsInt();

        Map<String, JsonObject> bones = new LinkedHashMap<>();
        for (JsonElement e : geo.getAsJsonArray("bones")) {
            JsonObject b = e.getAsJsonObject();
            bones.put(b.get("name").getAsString(), b);
        }
        MeshDefinition mesh = new MeshDefinition();
        Map<String, PartDefinition> parts = new HashMap<>();
        // Кости без родителя висят на корне модели; порядок в файле — родитель раньше ребёнка,
        // но на всякий случай строим рекурсивно.
        for (String name : bones.keySet()) {
            build(name, bones, parts, mesh.getRoot());
        }
        return LayerDefinition.create(mesh, texW, texH);
    }

    private static PartDefinition build(String name, Map<String, JsonObject> bones, Map<String, PartDefinition> parts, PartDefinition top) {
        PartDefinition done = parts.get(name);
        if (done != null) {
            return done;
        }
        JsonObject b = bones.get(name);
        float[] pivot = vec(b.get("pivot"));
        String parentName = b.has("parent") ? b.get("parent").getAsString() : null;
        PartDefinition parent = parentName == null || !bones.containsKey(parentName) ? top : build(parentName, bones, parts, top);
        float[] parentPivot = parentName == null || !bones.containsKey(parentName) ? new float[] {0.0F, 0.0F, 0.0F}
                : vec(bones.get(parentName).get("pivot"));

        CubeListBuilder cubes = CubeListBuilder.create();
        if (b.has("cubes")) {
            for (JsonElement ce : b.getAsJsonArray("cubes")) {
                JsonObject c = ce.getAsJsonObject();
                float[] o = vec(c.get("origin"));
                float[] s = vec(c.get("size"));
                JsonArray uv = c.getAsJsonArray("uv");
                float inflate = c.has("inflate") ? c.get("inflate").getAsFloat() : 0.0F;
                boolean mirror = c.has("mirror") && c.get("mirror").getAsBoolean();
                // Java: x как есть, y вниз от 24; куб — относительно точки вращения своей кости.
                float x = o[0] - pivot[0];
                float y = pivot[1] - o[1] - s[1];
                float z = o[2] - pivot[2];
                cubes.texOffs(uv.get(0).getAsInt(), uv.get(1).getAsInt()).mirror(mirror)
                        .addBox(x, y, z, s[0], s[1], s[2], new CubeDeformation(inflate));
            }
        }
        float[] rot = b.has("rotation") ? vec(b.get("rotation")) : new float[] {0.0F, 0.0F, 0.0F};
        // Смещение кости — от точки вращения родителя (у корня — от ступней на высоте 24).
        float ox = pivot[0] - parentPivot[0];
        float oy = parentName == null || !bones.containsKey(parentName) ? 24.0F - pivot[1] : parentPivot[1] - pivot[1];
        float oz = pivot[2] - parentPivot[2];
        PartDefinition part = parent.addOrReplaceChild(name, cubes, PartPose.offsetAndRotation(ox, oy, oz,
                (float) Math.toRadians(rot[0]), (float) Math.toRadians(rot[1]), (float) Math.toRadians(rot[2])));
        parts.put(name, part);
        return part;
    }

    // ------------------------------------------------------------------ прямая сборка ModelPart

    /**
     * Модель из ресурсов мода сразу в {@link ModelPart}, без {@link LayerDefinition}: так можно то, чего не умеет
     * {@code CubeListBuilder}, — UV на каждую грань (дробные развёртки автора, эмблема на груди ученика) и поворот
     * отдельного куба (у куба своя кость-обёртка с его pivot и углами, как в Java-экспорте Blockbench).
     * Корень — безымянная часть с костями файла детьми, как у запечённого слоя.
     * API: reference/minecraft-src/net/minecraft/client/model/geom/ModelPart.java#Cube (конструктор, compile)
     */
    public static ModelPart bake(String classpath) {
        try (InputStream in = BedrockGeo.class.getResourceAsStream(classpath)) {
            if (in == null) {
                throw new IllegalStateException("Нет модели " + classpath);
            }
            JsonObject geo = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject()
                    .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
            JsonObject desc = geo.getAsJsonObject("description");
            int texW = desc.get("texture_width").getAsInt();
            int texH = desc.get("texture_height").getAsInt();
            Map<String, JsonObject> bones = new LinkedHashMap<>();
            Map<String, List<String>> children = new LinkedHashMap<>();
            List<String> top = new ArrayList<>();
            for (JsonElement e : geo.getAsJsonArray("bones")) {
                JsonObject b = e.getAsJsonObject();
                bones.put(b.get("name").getAsString(), b);
            }
            for (Map.Entry<String, JsonObject> e : bones.entrySet()) {
                String parent = e.getValue().has("parent") ? e.getValue().get("parent").getAsString() : null;
                if (parent == null || !bones.containsKey(parent)) {
                    top.add(e.getKey());
                } else {
                    children.computeIfAbsent(parent, k -> new ArrayList<>()).add(e.getKey());
                }
            }
            Map<String, ModelPart> kids = new LinkedHashMap<>();
            for (String name : top) {
                kids.put(name, bakeBone(name, null, bones, children, texW, texH));
            }
            return new ModelPart(List.of(), kids);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Не прочитать модель " + classpath, e);
        }
    }

    private static ModelPart bakeBone(String name, float[] parentPivot, Map<String, JsonObject> bones,
                                      Map<String, List<String>> children, int texW, int texH) {
        JsonObject b = bones.get(name);
        float[] pivot = vec(b.get("pivot"));
        List<ModelPart.Cube> cubes = new ArrayList<>();
        Map<String, ModelPart> kids = new LinkedHashMap<>();
        if (b.has("cubes")) {
            int i = 0;
            for (JsonElement ce : b.getAsJsonArray("cubes")) {
                JsonObject c = ce.getAsJsonObject();
                if (c.has("rotation") && nonZero(vec(c.get("rotation")))) {
                    // Повёрнутый куб — в своей кости с его pivot (Blockbench так же пишет Java-модель: cube_r1…).
                    float[] cp = c.has("pivot") ? vec(c.get("pivot")) : pivot;
                    ModelPart wrap = new ModelPart(List.of(cube(c, cp, texW, texH)), Map.of());
                    PartPose pose = pose(cp, pivot, false, vec(c.get("rotation")));
                    wrap.setInitialPose(pose);
                    wrap.loadPose(pose);
                    kids.put(name + "_r" + i++, wrap);
                } else {
                    cubes.add(cube(c, pivot, texW, texH));
                }
            }
        }
        for (String child : children.getOrDefault(name, List.of())) {
            kids.put(child, bakeBone(child, pivot, bones, children, texW, texH));
        }
        ModelPart part = new ModelPart(cubes, kids);
        PartPose pose = pose(pivot, parentPivot == null ? new float[3] : parentPivot, parentPivot == null,
                b.has("rotation") ? vec(b.get("rotation")) : new float[3]);
        part.setInitialPose(pose);
        part.loadPose(pose);
        return part;
    }

    /** Смещение кости от pivot родителя (у корня — от ступней на высоте 24) и её углы — как в {@link #build}. */
    private static PartPose pose(float[] pivot, float[] parentPivot, boolean root, float[] rot) {
        float ox = pivot[0] - parentPivot[0];
        float oy = root ? 24.0F - pivot[1] : parentPivot[1] - pivot[1];
        float oz = pivot[2] - parentPivot[2];
        return PartPose.offsetAndRotation(ox, oy, oz,
                (float) Math.toRadians(rot[0]), (float) Math.toRadians(rot[1]), (float) Math.toRadians(rot[2]));
    }

    private static ModelPart.Cube cube(JsonObject c, float[] pivot, int texW, int texH) {
        float[] o = vec(c.get("origin"));
        float[] s = vec(c.get("size"));
        float inflate = c.has("inflate") ? c.get("inflate").getAsFloat() : 0.0F;
        boolean mirror = c.has("mirror") && c.get("mirror").getAsBoolean();
        float x = o[0] - pivot[0];
        float y = pivot[1] - o[1] - s[1];
        float z = o[2] - pivot[2];
        JsonElement uv = c.get("uv");
        if (uv != null && uv.isJsonObject()) {
            return new FaceCube(x, y, z, s, inflate, uv.getAsJsonObject(), texW, texH);
        }
        int u = uv == null ? 0 : uv.getAsJsonArray().get(0).getAsInt();
        int v = uv == null ? 0 : uv.getAsJsonArray().get(1).getAsInt();
        return new ModelPart.Cube(u, v, x, y, z, s[0], s[1], s[2], inflate, inflate, inflate, mirror, texW, texH,
                EnumSet.allOf(Direction.class));
    }

    private static boolean nonZero(float[] v) {
        return v[0] != 0.0F || v[1] != 0.0F || v[2] != 0.0F;
    }

    /**
     * Куб с UV на каждую грань. Вершины и порядок углов — как у ванильного {@link ModelPart.Cube} (его Polygon
     * закрыт), грани Bedrock ↔ Java: up → DOWN (верх в пространстве модели с y вниз), down → UP, east → WEST
     * (минимальный x), west → EAST; у всех граней прямоугольник (uv, uv + uv_size) — так его пишет импорт.
     */
    static final class FaceCube extends ModelPart.Cube {

        /** На грань: 4 вершины × (x, y, z, u, v). */
        private final float[][] quads;
        private final Vector3f[] normals;

        FaceCube(float x, float y, float z, float[] s, float g, JsonObject uv, int texW, int texH) {
            super(0, 0, x, y, z, s[0], s[1], s[2], g, g, g, false, texW, texH, EnumSet.noneOf(Direction.class));
            float x0 = x - g;
            float y0 = y - g;
            float z0 = z - g;
            float x1 = x + s[0] + g;
            float y1 = y + s[1] + g;
            float z1 = z + s[2] + g;
            float[] v7 = {x0, y0, z0};
            float[] v0 = {x1, y0, z0};
            float[] v1 = {x1, y1, z0};
            float[] v2 = {x0, y1, z0};
            float[] v3 = {x0, y0, z1};
            float[] v4 = {x1, y0, z1};
            float[] v5 = {x1, y1, z1};
            float[] v6 = {x0, y1, z1};
            String[] faces = {"up", "down", "east", "north", "west", "south"};
            float[][][] verts = {{v4, v3, v7, v0}, {v1, v2, v6, v5}, {v7, v3, v6, v2}, {v0, v7, v2, v1}, {v4, v0, v1, v5}, {v3, v4, v5, v6}};
            Direction[] dirs = {Direction.DOWN, Direction.UP, Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH};
            List<float[]> qs = new ArrayList<>();
            List<Vector3f> ns = new ArrayList<>();
            for (int f = 0; f < faces.length; f++) {
                if (!uv.has(faces[f])) {
                    continue;
                }
                JsonObject fu = uv.getAsJsonObject(faces[f]);
                float[] at = vec2(fu.get("uv"));
                float[] size = vec2(fu.get("uv_size"));
                float u1 = at[0] / texW;
                float vv1 = at[1] / texH;
                float u2 = (at[0] + size[0]) / texW;
                float vv2 = (at[1] + size[1]) / texH;
                // Углы как в ModelPart.Polygon: (u2,v1) (u1,v1) (u1,v2) (u2,v2).
                float[][] corner = {{u2, vv1}, {u1, vv1}, {u1, vv2}, {u2, vv2}};
                float[] q = new float[20];
                for (int k = 0; k < 4; k++) {
                    float[] p = verts[f][k];
                    q[k * 5] = p[0] / 16.0F;
                    q[k * 5 + 1] = p[1] / 16.0F;
                    q[k * 5 + 2] = p[2] / 16.0F;
                    q[k * 5 + 3] = corner[k][0];
                    q[k * 5 + 4] = corner[k][1];
                }
                qs.add(q);
                ns.add(dirs[f].step());
            }
            quads = qs.toArray(new float[0][]);
            normals = ns.toArray(new Vector3f[0]);
        }

        @Override
        public void compile(PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay, int color) {
            Matrix4f m = pose.pose();
            Vector3f n = new Vector3f();
            Vector3f p = new Vector3f();
            for (int f = 0; f < quads.length; f++) {
                pose.transformNormal(normals[f], n);
                float[] q = quads[f];
                for (int k = 0; k < 4; k++) {
                    m.transformPosition(q[k * 5], q[k * 5 + 1], q[k * 5 + 2], p);
                    buffer.addVertex(p.x(), p.y(), p.z(), color, q[k * 5 + 3], q[k * 5 + 4], overlay, light, n.x(), n.y(), n.z());
                }
            }
        }
    }

    private static float[] vec2(JsonElement e) {
        if (e == null || !e.isJsonArray()) {
            return new float[] {0.0F, 0.0F};
        }
        JsonArray a = e.getAsJsonArray();
        return new float[] {a.get(0).getAsFloat(), a.get(1).getAsFloat()};
    }

    private static float[] vec(JsonElement e) {
        if (e == null || !e.isJsonArray()) {
            return new float[] {0.0F, 0.0F, 0.0F};
        }
        JsonArray a = e.getAsJsonArray();
        return new float[] {a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
    }

    private BedrockGeo() {
    }
}
