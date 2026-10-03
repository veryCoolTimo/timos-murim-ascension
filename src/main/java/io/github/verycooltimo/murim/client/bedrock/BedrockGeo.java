package io.github.verycooltimo.murim.client.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
