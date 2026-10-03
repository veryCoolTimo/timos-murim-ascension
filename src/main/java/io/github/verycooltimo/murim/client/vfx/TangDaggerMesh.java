package io.github.verycooltimo.murim.client.vfx;

import com.google.gson.JsonArray;
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
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Модель кинжала Тан от автора ({@code assets/murim/bedrock/tang_dagger.geo.json}, Blockbench, 13 кубов
 * со свободными поворотами и UV на каждую грань). Ванильный {@code ModelPart} не умеет ни UV по граням с
 * дробными размерами, ни поворот отдельного куба, поэтому модель разворачивается в готовые четырёхугольники
 * один раз и рисуется напрямую ({@link RenderType#entityCutoutNoCull}).
 *
 * <p>Оси модели: остриё по +Y (кубы от навершия y≈3,3 до острия y≈15,5 пикселей), гарда на y≈8.
 * {@link #render} ставит гарду в начало координат и единицу — блоками (1/16 пикселя модели).
 * UV в файле — в пространстве 16×16, картинка 64×64: координаты делятся на 16 и масштаб не важен.
 */
public final class TangDaggerMesh {

    public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/item/tang_dagger.png");
    private static final String GEO = "/assets/murim/bedrock/tang_dagger.geo.json";
    /** Гарда — точка хвата (пиксели модели по Y). */
    public static final float GUARD_Y = 8.0F;

    /** Четырёхугольник: 4 вершины (x, y, z в пикселях), uv в долях текстуры, нормаль. */
    private record Quad(float[][] pos, float[][] uv, float[] normal) {
    }

    private static List<Quad> quads;

    private static List<Quad> quads() {
        if (quads == null) {
            quads = load();
        }
        return quads;
    }

    private static List<Quad> load() {
        List<Quad> out = new ArrayList<>();
        try (InputStream in = TangDaggerMesh.class.getResourceAsStream(GEO)) {
            if (in == null) {
                MurimMod.LOGGER.error("Нет модели кинжала {}", GEO);
                return out;
            }
            JsonObject geo = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject()
                    .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
            JsonObject desc = geo.getAsJsonObject("description");
            float tw = desc.get("texture_width").getAsFloat();
            float th = desc.get("texture_height").getAsFloat();
            for (JsonElement be : geo.getAsJsonArray("bones")) {
                JsonObject bone = be.getAsJsonObject();
                if (!bone.has("cubes")) {
                    continue;
                }
                for (JsonElement ce : bone.getAsJsonArray("cubes")) {
                    cube(ce.getAsJsonObject(), tw, th, out);
                }
            }
        } catch (Exception e) {
            MurimMod.LOGGER.error("Не прочитать модель кинжала", e);
        }
        return out;
    }

    private static float[] vec(JsonElement e) {
        JsonArray a = e.getAsJsonArray();
        return new float[] {a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
    }

    private static void cube(JsonObject c, float tw, float th, List<Quad> out) {
        float[] o = vec(c.get("origin"));
        float[] s = vec(c.get("size"));
        float x0 = o[0], y0 = o[1], z0 = o[2], x1 = o[0] + s[0], y1 = o[1] + s[1], z1 = o[2] + s[2];
        Matrix4f rot = new Matrix4f();
        if (c.has("rotation")) {
            float[] p = c.has("pivot") ? vec(c.get("pivot")) : new float[] {0.0F, 0.0F, 0.0F};
            float[] r = vec(c.get("rotation"));
            // Bedrock: поворот вокруг pivot, порядок Z → Y → X (как в Blockbench).
            rot.translate(p[0], p[1], p[2])
                    .rotateX((float) Math.toRadians(r[0])).rotateY((float) Math.toRadians(r[1])).rotateZ((float) Math.toRadians(r[2]))
                    .translate(-p[0], -p[1], -p[2]);
        }
        JsonObject uv = c.getAsJsonObject("uv");
        // Углы граней по порядку: верх-лево, верх-право, низ-право, низ-лево (как смотрит наблюдатель снаружи).
        face(out, uv, "north", rot, tw, th, new float[][] {{x1, y1, z0}, {x0, y1, z0}, {x0, y0, z0}, {x1, y0, z0}}, new float[] {0, 0, -1});
        face(out, uv, "south", rot, tw, th, new float[][] {{x0, y1, z1}, {x1, y1, z1}, {x1, y0, z1}, {x0, y0, z1}}, new float[] {0, 0, 1});
        face(out, uv, "east", rot, tw, th, new float[][] {{x1, y1, z1}, {x1, y1, z0}, {x1, y0, z0}, {x1, y0, z1}}, new float[] {1, 0, 0});
        face(out, uv, "west", rot, tw, th, new float[][] {{x0, y1, z0}, {x0, y1, z1}, {x0, y0, z1}, {x0, y0, z0}}, new float[] {-1, 0, 0});
        face(out, uv, "up", rot, tw, th, new float[][] {{x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}}, new float[] {0, 1, 0});
        face(out, uv, "down", rot, tw, th, new float[][] {{x0, y0, z1}, {x1, y0, z1}, {x1, y0, z0}, {x0, y0, z0}}, new float[] {0, -1, 0});
    }

    private static void face(List<Quad> out, JsonObject uv, String name, Matrix4f rot, float tw, float th, float[][] corners, float[] n) {
        if (uv == null || !uv.has(name)) {
            return;
        }
        JsonObject f = uv.getAsJsonObject(name);
        float[] a = {f.getAsJsonArray("uv").get(0).getAsFloat(), f.getAsJsonArray("uv").get(1).getAsFloat()};
        float[] sz = {f.getAsJsonArray("uv_size").get(0).getAsFloat(), f.getAsJsonArray("uv_size").get(1).getAsFloat()};
        float u0 = a[0] / tw, v0 = a[1] / th, u1 = (a[0] + sz[0]) / tw, v1 = (a[1] + sz[1]) / th;
        float[][] uvs = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
        float[][] pos = new float[4][];
        for (int i = 0; i < 4; i++) {
            Vector3f p = rot.transformPosition(new Vector3f(corners[i][0], corners[i][1], corners[i][2]));
            pos[i] = new float[] {p.x, p.y, p.z};
        }
        Vector3f nn = rot.transformDirection(new Vector3f(n[0], n[1], n[2])).normalize();
        out.add(new Quad(pos, uvs, new float[] {nn.x, nn.y, nn.z}));
    }

    public static RenderType renderType() {
        return RenderType.entityCutoutNoCull(TEXTURE);
    }

    /**
     * Нарисовать кинжал гардой в начале текущей позы, остриём по +Y; {@code scale} — множитель к натуральному
     * размеру (16 пикселей = 1 блок).
     */
    public static void render(PoseStack ps, VertexConsumer v, int light, float scale, float r, float g, float b, float alpha) {
        ps.pushPose();
        try {
            ps.scale(scale / 16.0F, scale / 16.0F, scale / 16.0F);
            ps.translate(0.0F, -GUARD_Y, 0.0F);
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

    /** Повернуть позу так, чтобы остриё (+Y модели) смотрело по {@code dir}, и докрутить на {@code roll} вокруг клинка. */
    public static void orient(PoseStack ps, double dx, double dy, double dz, float roll) {
        Quaternionf q = new Quaternionf().rotationTo(0.0F, 1.0F, 0.0F, (float) dx, (float) dy, (float) dz);
        ps.mulPose(q);
        ps.mulPose(new Quaternionf().rotationY(roll));
    }

    private TangDaggerMesh() {
    }
}
