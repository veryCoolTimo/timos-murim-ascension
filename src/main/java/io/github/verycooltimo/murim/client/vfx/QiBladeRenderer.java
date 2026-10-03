package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Клинок ци (автор 03.10, реф {@code docs/design/reference/techniques/qi sword/} — «Keen Qi»,
 * Absolute Regression): не предмет, а длинное лезвие света, растущее из кулака. Фиолетовое пламя
 * вокруг бело-раскалённой сердцевины, пульсирует и колышется как огонь, по нему бегут
 * трескучие дуги, у кулака — сгусток света.
 *
 * <p>Симуляция, а не картинка: ширина каждого из сегментов колеблется своим шумом (языки пламени
 * бегут от кулака к острию), ось слегка изгибается, дуги-молнии перестраиваются каждые 2 тика,
 * языки пламени срываются с кромок и уходят вверх. Всё — ленты к камере
 * ({@link VfxDraw#segment}), аддитивно, полный свет.
 */
public final class QiBladeRenderer {

    /** Сегментов вдоль клинка. */
    private static final int SEGMENTS = 14;

    // Цвета рефа: сердцевина почти белая, середина сиренево-розовая, пламя фиолетовое (#9A4DFF).
    private static final float[] CORE = {1.0F, 0.96F, 1.0F};
    private static final float[] MID = {0.78F, 0.52F, 1.0F};
    private static final float[] FLAME = {0.60F, 0.30F, 1.0F};
    private static final float[] DEEP = {0.42F, 0.16F, 0.95F};

    /**
     * Рисует клинок в текущей системе координат {@code pose}.
     *
     * @param base    точка в кулаке
     * @param dir     направление клинка (единичное)
     * @param length  длина, блоков
     * @param time    время в тиках с долей (пульс и пламя)
     * @param seed    своё зерно у каждого владельца
     * @param scale   1 — от третьего лица; от первого лица ленты тоньше
     */
    public static void draw(PoseStack pose, MultiBufferSource buffers, Vec3 base, Vec3 dir, double length,
                            float time, long seed, float scale) {
        draw(pose, buffers, base, dir, null, length, time, seed, scale);
    }

    /** @param armDir направление от кулака к локтю: ци охватывает и предплечье (реф, codex 03.10); null — без. */
    public static void draw(PoseStack pose, MultiBufferSource buffers, Vec3 base, Vec3 dir, Vec3 armDir, double length,
                            float time, long seed, float scale) {
        PoseStack.Pose last = pose.last();
        Vec3 cam = cameraLocal(last.pose());
        Vec3 a = perpendicular(dir);
        Vec3 b = dir.cross(a).normalize();

        // Пульс всего клинка (как дыхание огня) + рывки длины.
        // Codex 03.10: ±25–35 % при 2–3 Гц (20 тиков = 1 с → 0,8 рад/тик ≈ 2,5 Гц).
        float pulse = 1.0F + 0.22F * Mth.sin(time * 0.8F) + 0.09F * Mth.sin(time * 1.9F + 1.3F);
        double len = length * (0.95D + 0.06D * Mth.sin(time * 0.9F + 0.4F));

        Vec3[] pts = new Vec3[SEGMENTS + 1];
        double[] w = new double[SEGMENTS + 1];
        for (int i = 0; i <= SEGMENTS; i++) {
            double k = i / (double) SEGMENTS;
            // Лёгкий изгиб оси: волна бежит к острию.
            double sway = 0.035D * k * Mth.sin(time * 0.45F - (float) k * 5.0F);
            double sway2 = 0.025D * k * Mth.cos(time * 0.38F - (float) k * 4.0F + 1.0F);
            pts[i] = base.add(dir.scale(len * k)).add(a.scale(sway)).add(b.scale(sway2));
            // Сужение к острию, языки пламени: шум по сегменту, бегущий от кулака.
            double taper = k < 0.08D ? 0.55D + k * 5.6D : 1.0D - 0.78D * Math.pow((k - 0.08D) / 0.92D, 1.4D);
            // Плотные и прозрачные зоны бегут от кулака к острию; шум интерполируется между тиками.
            float tt = time * 1.2F;
            int t0 = Mth.floor(tt);
            double n = Mth.lerp(tt - t0, noise(seed, i - t0, 0), noise(seed, i - t0 - 1, 0));
            double flick = 1.0D + 0.40D * n + 0.18D * Mth.sin(time * 1.3F + (float) i * 1.7F);
            w[i] = taper * flick * pulse;
        }

        VertexConsumer glow = buffers.getBuffer(MurimRenderTypes.essence());
        // Ци обнимает кисть и предплечье: мягкая фиолетовая оболочка на 0,35 блока.
        if (armDir != null) {
            Vec3 elbow = base.add(armDir.scale(0.35D));
            VfxDraw.segment(glow, last, base, elbow, cam, 0.13D * scale * pulse, 0.5F, FLAME[0], FLAME[1], FLAME[2]);
            VfxDraw.segment(glow, last, base, base.add(armDir.scale(0.18D)), cam, 0.08D * scale, 0.45F, MID[0], MID[1], MID[2]);
        }
        // Широкое пламя и средний слой.
        for (int i = 0; i < SEGMENTS; i++) {
            double hw = 0.5D * (w[i] + w[i + 1]);
            float fade = i > SEGMENTS - 3 ? 0.6F : 1.0F;
            VfxDraw.segment(glow, last, pts[i], pts[i + 1], cam, 0.46D * hw * scale, 0.35F * fade, DEEP[0], DEEP[1], DEEP[2]);
            VfxDraw.segment(glow, last, pts[i], pts[i + 1], cam, 0.27D * hw * scale, 0.8F * fade, FLAME[0], FLAME[1], FLAME[2]);
            VfxDraw.segment(glow, last, pts[i], pts[i + 1], cam, 0.11D * hw * scale, 0.7F, MID[0], MID[1], MID[2]);
        }
        // Языки пламени срываются с кромок и уходят назад-вверх по клинку.
        for (int f = 0; f < 8; f++) {
            // Время жизни 3–7 тиков (0,15–0,35 с), фазы независимые; рождение — на новом месте.
            double lifeTicks = 3.0D + 4.0D * hash(seed + 3, f);
            double age = (time + hash(seed, f) * 40.0D) / lifeTicks;
            int gen = (int) Math.floor(age);
            float life = (float) (age - gen);
            int at = 1 + (int) (hash(seed + 7, f * 1000 + gen) * (SEGMENTS - 3));
            double ang = hash(seed + 11, f * 1000 + gen) * Math.PI * 2.0D;
            Vec3 out = a.scale(Math.cos(ang)).add(b.scale(Math.sin(ang)));
            Vec3 from = pts[at].add(out.scale(0.06D * w[at] * scale));
            double reach = (0.12D + 0.18D * hash(seed + 13, f * 1000 + gen)) * scale;
            Vec3 to = from.add(out.scale(reach * (0.4D + life))).add(dir.scale(reach * (0.6D + 0.8D * life)));
            float al = 0.75F * (1.0F - life) * Math.min(1.0F, life * 4.0F);
            VfxDraw.segment(glow, last, from, to, cam, 0.05D * scale * (1.0D - life * 0.6D), al, FLAME[0], FLAME[1], FLAME[2]);
        }

        // Трескучие дуги: ломаные вокруг клинка, новый рисунок каждые 2 тика. Сначала точки —
        // потом проходы по буферам: буфер нельзя брать заново, пока используешь прежний.
        int frame = (int) (time / 3.0F);
        java.util.List<Vec3[]> arcs = new java.util.ArrayList<>();
        for (int n = 0; n < 3; n++) {
            if (hash(seed + 31, frame * 3 + n) < 0.25D) {
                continue;
            }
            double start = hash(seed + 41, frame * 3 + n) * 0.6D;
            double span = 0.2D + hash(seed + 43, frame * 3 + n) * 0.35D;
            Vec3[] line = new Vec3[7];
            for (int j = 0; j <= 6; j++) {
                double k = Math.min(1.0D, start + span * j / 6.0D);
                double ang = hash(seed + 53, frame * 97 + n * 13 + j) * Math.PI * 2.0D;
                double r = (0.05D + 0.11D * hash(seed + 59, frame * 89 + n * 7 + j)) * scale;
                int si = (int) Math.min(SEGMENTS, Math.round(k * SEGMENTS));
                line[j] = pts[si].add(a.scale(Math.cos(ang) * r)).add(b.scale(Math.sin(ang) * r));
            }
            arcs.add(line);
        }
        for (Vec3[] line : arcs) {
            for (int j = 1; j < line.length; j++) {
                VfxDraw.segment(glow, last, line[j - 1], line[j], cam, 0.014D * scale, 0.45F, FLAME[0], FLAME[1], FLAME[2]);
            }
        }

        // Раскалённая сердцевина и сердцевины дуг: узкие, чёткие.
        VertexConsumer core = buffers.getBuffer(MurimRenderTypes.airBand());
        for (int i = 0; i < SEGMENTS - 1; i++) {
            VfxDraw.segment(core, last, pts[i], pts[i + 1], cam, 0.016D * scale * (1.0D - i / (double) SEGMENTS * 0.6D), 1.0F, CORE[0], CORE[1], CORE[2]);
        }
        for (Vec3[] line : arcs) {
            for (int j = 1; j < line.length; j++) {
                VfxDraw.segment(core, last, line[j - 1], line[j], cam, 0.005D * scale, 0.8F, CORE[0], CORE[1], CORE[2]);
            }
        }

        // Сгусток в кулаке: из него клинок и растёт.
        VertexConsumer mote = buffers.getBuffer(MurimRenderTypes.mote());
        VfxDraw.billboard(mote, last, base, cam, 0.22D * scale * pulse, 0.6F, FLAME[0], FLAME[1], FLAME[2]);
        VfxDraw.billboard(mote, last, base, cam, 0.07D * scale * pulse, 1.0F, CORE[0], CORE[1], CORE[2]);
    }

    /** Камера в локальных координатах: позы рендера сущностей и руки — относительно камеры. */
    private static Vec3 cameraLocal(Matrix4f m) {
        Matrix4f inv = new Matrix4f(m).invert();
        Vector4f v = inv.transform(new Vector4f(0.0F, 0.0F, 0.0F, 1.0F));
        return new Vec3(v.x, v.y, v.z);
    }

    private static Vec3 perpendicular(Vec3 d) {
        Vec3 ref = Math.abs(d.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        return d.cross(ref).normalize();
    }

    /** Детерминированный шум [0, 1). */
    static double hash(long seed, int i) {
        long h = seed * 0x9E3779B97F4A7C15L + i * 0xC2B2AE3D27D4EB4FL;
        h ^= (h >>> 31);
        h *= 0x94D049BB133111EBL;
        h ^= (h >>> 29);
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Плавный шум [-1, 1] по сегменту, меняется со временем. */
    private static double noise(long seed, int i, int t) {
        return hash(seed, i * 131 + t) * 2.0D - 1.0D;
    }

    private QiBladeRenderer() {
    }
}
