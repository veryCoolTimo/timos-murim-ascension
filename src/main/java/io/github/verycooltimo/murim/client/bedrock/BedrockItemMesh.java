package io.github.verycooltimo.murim.client.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Модель предмета из Blockbench ({@code *.geo.json}, Bedrock 1.12, UV на каждую грань, свободные повороты кубов),
 * развёрнутая в готовые четырёхугольники и нарисованная напрямую. Ванильный {@code ModelPart} (им читает бандитов
 * {@link BedrockGeo}) не умеет ни UV по граням с дробными размерами, ни поворот отдельного куба.
 *
 * <p>Пересчёт Bedrock → пространство Blockbench (то, что автор видит в редакторе; сверено офлайн-рендером
 * {@code tools/art/geo_preview.py}, 03.10): ось X зеркальна — у куба min x = −(origin.x + size.x), pivot.x = −pivot.x;
 * углы X и Y меняют знак, Z — нет; порядок Эйлера ZYX (матрица Rz·Ry·Rx), поворот вокруг своего pivot, поворот
 * кости действует на детей; {@code inflate} раздувает куб. UV — в единицах {@code texture_width/height} из geo,
 * не по размеру PNG (автор рисует текстуру в 4× разрешении).
 *
 * <p>Ошибка первой версии (автор 03.10: «моделька сломалась»): углы X брались без смены знака — длинные
 * тонкие кубы кромок клинка разворачивались вниз, в рукоять.
 */
public final class BedrockItemMesh {

    private record Quad(float[][] pos, float[][] uv, float[] normal) {
    }

    private final String geoPath;
    private final ResourceLocation texture;
    private List<Quad> quads;
    /** Габариты модели по Y (единицы geo) — для подгонки рукояти и длины. */
    private float minY = Float.MAX_VALUE;
    private float maxY = -Float.MAX_VALUE;

    public BedrockItemMesh(String geoPath, ResourceLocation texture) {
        this.geoPath = geoPath;
        this.texture = texture;
    }

    public ResourceLocation texture() {
        return texture;
    }

    public RenderType renderType() {
        return RenderType.entityCutoutNoCull(texture);
    }

    public float minY() {
        quads();
        return minY;
    }

    public float maxY() {
        quads();
        return maxY;
    }

    private List<Quad> quads() {
        if (quads == null) {
            quads = load();
        }
        return quads;
    }

    private List<Quad> load() {
        List<Quad> out = new ArrayList<>();
        try (InputStream in = BedrockItemMesh.class.getResourceAsStream(geoPath)) {
            if (in == null) {
                MurimMod.LOGGER.error("Нет модели {}", geoPath);
                return out;
            }
            JsonObject geo = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject()
                    .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
            JsonObject desc = geo.getAsJsonObject("description");
            float tw = desc.get("texture_width").getAsFloat();
            float th = desc.get("texture_height").getAsFloat();
            Map<String, JsonObject> bones = new LinkedHashMap<>();
            for (JsonElement be : geo.getAsJsonArray("bones")) {
                JsonObject b = be.getAsJsonObject();
                bones.put(b.get("name").getAsString(), b);
            }
            Map<String, Matrix4f> memo = new HashMap<>();
            for (Map.Entry<String, JsonObject> e : bones.entrySet()) {
                Matrix4f bx = boneXf(e.getKey(), bones, memo);
                JsonObject bone = e.getValue();
                if (!bone.has("cubes")) {
                    continue;
                }
                for (JsonElement ce : bone.getAsJsonArray("cubes")) {
                    cube(ce.getAsJsonObject(), bx, tw, th, out);
                }
            }
        } catch (Exception ex) {
            MurimMod.LOGGER.error("Не прочитать модель {}", geoPath, ex);
        }
        return out;
    }

    private static Matrix4f boneXf(String name, Map<String, JsonObject> bones, Map<String, Matrix4f> memo) {
        Matrix4f done = memo.get(name);
        if (done != null) {
            return done;
        }
        JsonObject b = bones.get(name);
        Matrix4f own = b.has("rotation") ? about(vec(b.get("pivot")), vec(b.get("rotation"))) : new Matrix4f();
        String parent = b.has("parent") ? b.get("parent").getAsString() : null;
        // Сначала свой поворот, затем родительский: M = parent · own.
        Matrix4f m = parent != null && bones.containsKey(parent) ? new Matrix4f(boneXf(parent, bones, memo)).mul(own) : own;
        memo.put(name, m);
        return m;
    }

    /** Поворот вокруг pivot в пространстве Blockbench (pivot.x зеркален, углы X и Y со сменой знака, ZYX). */
    private static Matrix4f about(float[] pivot, float[] rot) {
        float px = -pivot[0];
        return new Matrix4f().translate(px, pivot[1], pivot[2])
                .rotateZ((float) Math.toRadians(rot[2]))
                .rotateY((float) Math.toRadians(-rot[1]))
                .rotateX((float) Math.toRadians(-rot[0]))
                .translate(-px, -pivot[1], -pivot[2]);
    }

    private static float[] vec(JsonElement e) {
        if (e == null || !e.isJsonArray()) {
            return new float[] {0.0F, 0.0F, 0.0F};
        }
        return new float[] {e.getAsJsonArray().get(0).getAsFloat(), e.getAsJsonArray().get(1).getAsFloat(),
                e.getAsJsonArray().get(2).getAsFloat()};
    }

    private void cube(JsonObject c, Matrix4f bone, float tw, float th, List<Quad> out) {
        float[] o = vec(c.get("origin"));
        float[] s = vec(c.get("size"));
        float inf = c.has("inflate") ? c.get("inflate").getAsFloat() : 0.0F;
        float x0 = -(o[0] + s[0]) - inf, x1 = -o[0] + inf;
        float y0 = o[1] - inf, y1 = o[1] + s[1] + inf;
        float z0 = o[2] - inf, z1 = o[2] + s[2] + inf;
        Matrix4f m = new Matrix4f(bone);
        if (c.has("rotation")) {
            m.mul(about(vec(c.get("pivot")), vec(c.get("rotation"))));
        }
        if (!c.has("uv") || !c.get("uv").isJsonObject()) {
            // Box UV в моделях автора не встречается; без граней куб пропускаем, а не рисуем мусор.
            return;
        }
        JsonObject uv = c.getAsJsonObject("uv");
        face(out, uv, "north", m, tw, th, new float[][] {{x1, y1, z0}, {x0, y1, z0}, {x0, y0, z0}, {x1, y0, z0}}, new float[] {0, 0, -1});
        face(out, uv, "south", m, tw, th, new float[][] {{x0, y1, z1}, {x1, y1, z1}, {x1, y0, z1}, {x0, y0, z1}}, new float[] {0, 0, 1});
        face(out, uv, "east", m, tw, th, new float[][] {{x1, y1, z1}, {x1, y1, z0}, {x1, y0, z0}, {x1, y0, z1}}, new float[] {1, 0, 0});
        face(out, uv, "west", m, tw, th, new float[][] {{x0, y1, z0}, {x0, y1, z1}, {x0, y0, z1}, {x0, y0, z0}}, new float[] {-1, 0, 0});
        face(out, uv, "up", m, tw, th, new float[][] {{x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}}, new float[] {0, 1, 0});
        face(out, uv, "down", m, tw, th, new float[][] {{x0, y0, z1}, {x1, y0, z1}, {x1, y0, z0}, {x0, y0, z0}}, new float[] {0, -1, 0});
    }

    private void face(List<Quad> out, JsonObject uv, String name, Matrix4f m, float tw, float th, float[][] corners, float[] n) {
        if (!uv.has(name)) {
            return;
        }
        JsonObject f = uv.getAsJsonObject(name);
        float u = f.getAsJsonArray("uv").get(0).getAsFloat();
        float v = f.getAsJsonArray("uv").get(1).getAsFloat();
        float su = f.getAsJsonArray("uv_size").get(0).getAsFloat();
        float sv = f.getAsJsonArray("uv_size").get(1).getAsFloat();
        float u0 = u / tw, v0 = v / th, u1 = (u + su) / tw, v1 = (v + sv) / th;
        float[][] uvs = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
        float[][] pos = new float[4][];
        for (int i = 0; i < 4; i++) {
            Vector3f p = m.transformPosition(new Vector3f(corners[i][0], corners[i][1], corners[i][2]));
            pos[i] = new float[] {p.x, p.y, p.z};
            minY = Math.min(minY, p.y);
            maxY = Math.max(maxY, p.y);
        }
        Vector3f nn = m.transformDirection(new Vector3f(n[0], n[1], n[2])).normalize();
        out.add(new Quad(pos, uvs, new float[] {nn.x, nn.y, nn.z}));
    }

    /**
     * Нарисовать модель: точка {@code gripY} (единицы geo) — в начале позы, ось +Y модели — по +Y позы;
     * {@code scale} — блоков на 16 единиц geo.
     */
    public void render(PoseStack ps, VertexConsumer v, int light, float scale, float gripY, float r, float g, float b, float alpha) {
        ps.pushPose();
        try {
            ps.scale(scale / 16.0F, scale / 16.0F, scale / 16.0F);
            ps.translate(0.0F, -gripY, 0.0F);
            PoseStack.Pose pose = ps.last();
            for (Quad q : quads()) {
                for (int i = 0; i < 4; i++) {
                    v.addVertex(pose.pose(), q.pos()[i][0], q.pos()[i][1], q.pos()[i][2])
                            .setColor(r, g, b, alpha)
                            .setUv(q.uv()[i][0], q.uv()[i][1])
                            .setOverlay(OverlayTexture.NO_OVERLAY)
                            .setLight(light)
                            .setNormal(pose, q.normal()[0], q.normal()[1], q.normal()[2]);
                }
            }
        } finally {
            ps.popPose();
        }
    }
}
