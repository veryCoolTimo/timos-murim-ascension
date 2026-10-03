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

    // Цвета рефа (DESCRIPTIONS.md, кадры 4–5): белое ядро #FFF0FF, циановый край #88FFFF,
    // розовая оболочка #FA76FF, розово-лиловый ореол #CF55EC и фиолетовое поле #9A32E5.
    private static final float[] CORE = {1.0F, 0.95F, 1.0F};
    private static final float[] CYAN = {0.53F, 1.0F, 1.0F};
    private static final float[] MID = {0.98F, 0.46F, 1.0F};
    private static final float[] FLAME = {0.81F, 0.33F, 0.93F};
    private static final float[] DEEP = {0.60F, 0.20F, 0.90F};
    // Градиент по длине: у кисти раскалённый розово-белый, к острию фиолетово-синий.
    private static final float[] HOT = {1.0F, 0.80F, 0.98F};
    private static final float[] TIP = {0.50F, 0.36F, 1.0F};
    private static final float[] TIP_LIGHT = {0.70F, 0.72F, 1.0F};
    private static final float[] DEEP_TIP = {0.26F, 0.22F, 0.95F};

    private static float[] grad(float[] x, float[] y, float t) {
        t = Mth.clamp(t, 0.0F, 1.0F);
        return new float[] {Mth.lerp(t, x[0], y[0]), Mth.lerp(t, x[1], y[1]), Mth.lerp(t, x[2], y[2])};
    }

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
        draw(pose, buffers, base, dir, null, length, time, seed, scale, false);
    }

    /**
     * @param armDir  направление от кулака к локтю: ци охватывает и предплечье (реф, codex 03.10); null — без
     * @param evolved клинок высшей ступени (автор 03.10: «аура-меч для высшего реалма — больше
     *                эффектов и чёрные волны, как на третьем рефе»): вдвое больше вихря и завитков,
     *                по клинку бегут чёрные рваные волны
     */
    public static void draw(PoseStack pose, MultiBufferSource buffers, Vec3 base, Vec3 dir, Vec3 armDir, double length,
                            float time, long seed, float scale, boolean evolved) {
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
            // Реф, кадр 4: лезвие слегка изогнуто серпом, как язык пламени.
            double bow = 0.13D * Math.sin(Math.PI * k) * scale;
            pts[i] = base.add(dir.scale(len * k)).add(a.scale(sway + bow)).add(b.scale(sway2));
            // Сужение к острию, языки пламени: шум по сегменту, бегущий от кулака.
            double taper = k < 0.06D ? 0.7D + k * 5.0D : 1.0D - 0.65D * Math.pow((k - 0.06D) / 0.94D, 1.6D);
            // Остриё: последние 20 % сходятся в точку (codex 03.10).
            if (k > 0.8D) {
                taper *= Math.max(0.08D, (1.0D - k) / 0.2D);
            }
            // Плотные и прозрачные зоны бегут от кулака к острию; шум интерполируется между тиками.
            float tt = time * 1.2F;
            int t0 = Mth.floor(tt);
            double n = Mth.lerp(tt - t0, noise(seed, i - t0, 0), noise(seed, i - t0 - 1, 0));
            double flick = 1.0D + 0.40D * n + 0.18D * Mth.sin(time * 1.3F + (float) i * 1.7F);
            w[i] = taper * flick * pulse;
        }

        VertexConsumer glow = buffers.getBuffer(MurimRenderTypes.essence());
        // Автор 03.10: «оно должно заходить на руку — не держит, а идёт прямо из руки». Клинок
        // начинается у локтя: та же многослойная оболочка обнимает предплечье и без шва
        // переходит в лезвие у кисти; по руке бегут яркие нити (реф, кадр 3).
        if (armDir != null) {
            int armSeg = 5;
            Vec3 elbow = base.add(armDir.scale(0.62D));
            for (int i = 0; i < armSeg; i++) {
                double k0 = i / (double) armSeg, k1 = (i + 1) / (double) armSeg;
                Vec3 p0 = elbow.add(base.subtract(elbow).scale(k0));
                Vec3 p1 = elbow.add(base.subtract(elbow).scale(k1));
                double flick = 1.0D + 0.25D * Mth.sin(time * 1.1F - (float) i * 1.3F);
                double hw = (0.45D + 0.55D * k1) * w[0] * flick;
                float[] deep = grad(DEEP_TIP, DEEP, 0.3F + 0.7F * (float) k1);
                VfxDraw.segment(glow, last, p0, p1, cam, 1.2D * hw * scale, 0.4F, deep[0], deep[1], deep[2]);
                VfxDraw.segment(glow, last, p0, p1, cam, 0.75D * hw * scale, 0.7F * (float) (0.5D + 0.5D * k1), FLAME[0], FLAME[1], FLAME[2]);
                VfxDraw.segment(glow, last, p0, p1, cam, 0.38D * hw * scale, 0.8F * (float) (0.3D + 0.7D * k1), HOT[0], HOT[1], HOT[2]);
            }
            // Нити вдоль предплечья к пальцам: две, скользят к кисти.
            for (int n = 0; n < 2; n++) {
                Vec3 prev = null;
                for (int j = 0; j <= 6; j++) {
                    double k = j / 6.0D;
                    double ph = k * 6.0D + time * 0.5D + n * Math.PI;
                    double r = 0.16D * scale * (0.6D + 0.4D * k);
                    Vec3 p = elbow.add(base.subtract(elbow).scale(k)).add(a.scale(Math.cos(ph) * r)).add(b.scale(Math.sin(ph) * r));
                    if (prev != null) {
                        VfxDraw.segment(glow, last, prev, p, cam, 0.03D * scale, 0.9F, HOT[0], HOT[1], HOT[2]);
                    }
                    prev = p;
                }
            }
        }
        // Слои с градиентом по длине (автор: «не один цвет, а градиенты»): у кисти раскалённый
        // розово-белый, к середине розовый #FA76FF, к острию фиолетово-синий с циановым отливом.
        // Градиент медленно течёт к острию.
        float flow = 0.08F * Mth.sin(time * 0.3F);
        for (int i = 0; i < SEGMENTS; i++) {
            double hw = 0.5D * (w[i] + w[i + 1]);
            float k = Mth.clamp((i + 0.5F) / SEGMENTS + flow, 0.0F, 1.0F);
            float fade = i > SEGMENTS - 3 ? 0.6F : 1.0F;
            float[] deep = grad(DEEP, DEEP_TIP, k);
            float[] flame = k < 0.5F ? grad(FLAME, MID, k * 2.0F) : grad(MID, TIP, (k - 0.5F) * 2.0F);
            float[] mid = k < 0.4F ? grad(HOT, MID, k / 0.4F) : grad(MID, TIP_LIGHT, (k - 0.4F) / 0.6F);
            VfxDraw.segment(glow, last, pts[i], pts[i + 1], cam, 0.55D * hw * scale, 0.22F * fade, deep[0], deep[1], deep[2]);
            VfxDraw.segment(glow, last, pts[i], pts[i + 1], cam, 0.33D * hw * scale, 0.6F * fade, flame[0], flame[1], flame[2]);
            VfxDraw.segment(glow, last, pts[i], pts[i + 1], cam, 0.20D * hw * scale, 0.95F, mid[0], mid[1], mid[2]);
        }
        // Реф, кадр 4: от лезвия вверх, вдоль руки и выше, поднимаются длинные завитки пламени ци —
        // тонкие, сужающиеся, закрученные; каждый живёт 12–22 тика, рождается на клинке, всплывает
        // вверх и тает. Это и есть «пульсирует как огонь»: силуэт всё время рвётся и обновляется.
        for (int n = 0; n < (evolved ? 18 : 10); n++) {
            double lifeT = 12.0D + 10.0D * hash(seed + 71, n);
            double age = (time + hash(seed + 73, n) * 60.0D) / lifeT;
            int gen = (int) Math.floor(age);
            double life = age - gen;
            double k0 = 0.08D + 0.75D * hash(seed + 79, n * 977 + gen);
            int si = (int) Math.min(SEGMENTS, Math.round(k0 * SEGMENTS));
            double side = hash(seed + 83, n * 977 + gen) < 0.5D ? -1.0D : 1.0D;
            double wispLen = (0.7D + 0.8D * hash(seed + 89, n * 977 + gen)) * scale;
            float alpha = (float) (Math.min(1.0D, life * 5.0D) * (1.0D - life)) * 1.0F;
            float[] col = grad(HOT, FLAME, (float) (0.3D + 0.7D * k0));
            Vec3 prev = null;
            double prevW = 0.0D;
            for (int j = 0; j <= 8; j++) {
                double u = j / 8.0D;
                double curl = Math.sin(u * 4.0D + time * 0.25D + n) * 0.12D * u * scale;
                Vec3 p = pts[si]
                        .add(dir.scale(-(wispLen * u + 0.25D * life * scale)))
                        .add(a.scale(side * (0.05D * scale + 0.18D * u * u * scale) + curl))
                        .add(b.scale(Math.cos(u * 3.0D + n) * 0.06D * u * scale));
                double ww = 0.10D * scale * (1.0D - 0.85D * u) * w[si];
                if (prev != null) {
                    VfxDraw.segment(glow, last, prev, p, cam, 0.5D * (ww + prevW), alpha * (float) (1.0D - 0.5D * u), col[0], col[1], col[2]);
                }
                prev = p;
                prevW = ww;
            }
        }
        // Языки пламени срываются с кромок и уходят назад-вверх по клинку.
        for (int f = 0; f < 12; f++) {
            // Время жизни 3–7 тиков (0,15–0,35 с), фазы независимые; рождение — на новом месте.
            double lifeTicks = 3.0D + 4.0D * hash(seed + 3, f);
            double age = (time + hash(seed, f) * 40.0D) / lifeTicks;
            int gen = (int) Math.floor(age);
            float life = (float) (age - gen);
            int at = 1 + (int) (hash(seed + 7, f * 1000 + gen) * (SEGMENTS - 3));
            double ang = hash(seed + 11, f * 1000 + gen) * Math.PI * 2.0D;
            Vec3 out = a.scale(Math.cos(ang)).add(b.scale(Math.sin(ang)));
            Vec3 from = pts[at].add(out.scale(0.16D * w[at] * scale));
            double reach = (0.2D + 0.3D * hash(seed + 13, f * 1000 + gen)) * scale;
            // Языки тянутся ВДОЛЬ клинка (не поперёк): масса читается единой.
            Vec3 to = from.add(out.scale(reach * 0.25D * (0.5D + life))).add(dir.scale(reach * (1.2D + 1.6D * life)));
            float al = 0.75F * (1.0F - life) * Math.min(1.0F, life * 4.0F);
            VfxDraw.segment(glow, last, from, to, cam, 0.09D * scale * (1.0D - life * 0.6D), al, FLAME[0], FLAME[1], FLAME[2]);
        }

        // Трескучие дуги: ломаные вокруг клинка, новый рисунок каждые 2 тика. Сначала точки —
        // потом проходы по буферам: буфер нельзя брать заново, пока используешь прежний.
        int frame = (int) (time / 3.0F);
        java.util.List<Vec3[]> arcs = new java.util.ArrayList<>();
        for (int n = 0; n < 3; n++) {
            if (n > 0 || hash(seed + 31, frame * 3 + n) < 0.4D) {
                continue;
            }
            double start = hash(seed + 41, frame * 3 + n) * 0.6D;
            double span = 0.2D + hash(seed + 43, frame * 3 + n) * 0.35D;
            Vec3[] line = new Vec3[7];
            for (int j = 0; j <= 6; j++) {
                double k = Math.min(1.0D, start + span * j / 6.0D);
                double ang = hash(seed + 53, frame * 97 + n * 13 + j) * Math.PI * 2.0D;
                double r = (0.06D + 0.12D * hash(seed + 59, frame * 89 + n * 7 + j)) * scale;
                int si = (int) Math.min(SEGMENTS, Math.round(k * SEGMENTS));
                line[j] = pts[si].add(a.scale(Math.cos(ang) * r)).add(b.scale(Math.sin(ang) * r));
            }
            arcs.add(line);
        }
        for (Vec3[] line : arcs) {
            for (int j = 1; j < line.length; j++) {
                VfxDraw.segment(glow, last, line[j - 1], line[j], cam, 0.022D * scale, 0.6F, FLAME[0], FLAME[1], FLAME[2]);
            }
        }

        // Аура вокруг клинка закручивается (автор 03.10: «больше ауры, которая вокруг него
        // закручивается»): ленты-витки обвивают лезвие от кисти к острию, расходятся наружу и
        // вращаются; каждый виток сужается и тает к острию. У высшей ступени их вдвое больше.
        int spirals = evolved ? 6 : 3;
        for (int n = 0; n < spirals; n++) {
            double phase0 = n * (Math.PI * 2.0D / spirals) + time * (0.22D + 0.04D * n);
            double pitch = 5.5D + 1.5D * hash(seed + 101, n);
            Vec3 prev = null;
            double prevW = 0.0D;
            for (int i = 0; i <= SEGMENTS; i++) {
                double k = i / (double) SEGMENTS;
                double ph = phase0 + k * pitch;
                // Радиус: у кисти плотно, к середине раскрывается, у острия снова сходится.
                double r = (0.10D + 0.30D * Math.sin(Math.PI * Math.min(1.0D, k * 1.15D))) * scale * (evolved ? 1.35D : 1.0D)
                        * (1.0D + 0.15D * Mth.sin(time * 0.8F + (float) n));
                Vec3 p = pts[i].add(a.scale(Math.cos(ph) * r)).add(b.scale(Math.sin(ph) * r));
                double ww = 0.09D * scale * (1.0D - 0.75D * k);
                if (prev != null) {
                    float[] col = grad(MID, TIP, (float) k);
                    float al = (float) (0.8D * (1.0D - 0.7D * k) * (0.6D + 0.4D * Math.sin(ph * 0.5D)));
                    VfxDraw.segment(glow, last, prev, p, cam, 0.5D * (ww + prevW), al, col[0], col[1], col[2]);
                }
                prev = p;
                prevW = ww;
            }
        }
        if (evolved) {
            // Высшая ступень: широкое вращающееся кольцо-вихрь у кисти и второй ряд завитков.
            for (int n = 0; n < 2; n++) {
                Vec3 prev = null;
                for (int j = 0; j <= 16; j++) {
                    double ang = j / 16.0D * Math.PI * 1.6D + time * 0.35D * (n == 0 ? 1 : -1) + n;
                    double r = (0.32D + 0.06D * n) * scale;
                    Vec3 c = pts[2 + n * 3];
                    Vec3 p = c.add(a.scale(Math.cos(ang) * r)).add(b.scale(Math.sin(ang) * r)).add(dir.scale(0.05D * Math.sin(ang * 2.0D)));
                    if (prev != null) {
                        VfxDraw.segment(glow, last, prev, p, cam, 0.03D * scale * (1.0D - j / 16.0D), 0.7F * (1.0F - j / 16.0F), HOT[0], HOT[1], HOT[2]);
                    }
                    prev = p;
                }
            }
        }

        // Раскалённая сердцевина и сердцевины дуг: узкие, чёткие.
        VertexConsumer core = buffers.getBuffer(MurimRenderTypes.airBand());
        for (int i = 0; i < SEGMENTS - 1; i++) {
            VfxDraw.segment(core, last, pts[i], pts[i + 1], cam, 0.05D * scale * w[i], 1.0F, CORE[0], CORE[1], CORE[2]);
            // Тонкий циановый край вдоль ядра (кадр 5).
            if (noise(seed + 17, i - (int) (time * 0.7F), 0) > 0.1D)
            VfxDraw.segment(core, last, pts[i].add(a.scale(0.09D * scale * w[i])), pts[i + 1].add(a.scale(0.09D * scale * w[i + 1])), cam, 0.012D * scale, 0.8F, CYAN[0], CYAN[1], CYAN[2]);
        }
        for (Vec3[] line : arcs) {
            for (int j = 1; j < line.length; j++) {
                VfxDraw.segment(core, last, line[j - 1], line[j], cam, 0.008D * scale, 0.9F, CORE[0], CORE[1], CORE[2]);
            }
        }

        // Сгусток в кулаке: из него клинок и растёт.
        VertexConsumer mote = buffers.getBuffer(MurimRenderTypes.mote());
        VfxDraw.billboard(mote, last, base, cam, 0.34D * scale * pulse, 0.6F, FLAME[0], FLAME[1], FLAME[2]);
        VfxDraw.billboard(mote, last, base, cam, 0.13D * scale * pulse, 1.0F, CORE[0], CORE[1], CORE[2]);

        if (evolved) {
            // Чёрные волны (реф, третья панель): рваные почти чёрные полосы обтекают лезвие по бокам
            // и бегут волной от кисти к острию; с кромок отрываются чёрные клочья. Обычное
            // смешивание (не аддитивное), иначе чёрное не видно.
            VertexConsumer ink = buffers.getBuffer(MurimRenderTypes.darkBand());
            for (int n = 0; n < 4; n++) {
                double side = n % 2 == 0 ? 1.0D : -1.0D;
                double off = (0.12D + 0.06D * (n / 2)) * scale;
                for (int i = 0; i < SEGMENTS - 1; i++) {
                    double k0 = i / (double) SEGMENTS, k1 = (i + 1) / (double) SEGMENTS;
                    double wave0 = Math.sin(k0 * 10.0D - time * 0.6D + n * 1.7D);
                    double wave1 = Math.sin(k1 * 10.0D - time * 0.6D + n * 1.7D);
                    Vec3 q0 = pts[i].add(a.scale(side * (off * w[i] + 0.05D * scale * wave0))).add(b.scale(0.04D * scale * wave0));
                    Vec3 q1 = pts[i + 1].add(a.scale(side * (off * w[i + 1] + 0.05D * scale * wave1))).add(b.scale(0.04D * scale * wave1));
                    float al = (float) (0.85D * (0.55D + 0.45D * wave0) * (1.0D - 0.6D * k0));
                    VfxDraw.segment(ink, last, q0, q1, cam, 0.05D * scale * w[i] * (0.7D + 0.3D * wave0), al, 0.07F, 0.04F, 0.12F);
                }
            }
            for (int f = 0; f < 6; f++) {
                double lifeT = 8.0D + 6.0D * hash(seed + 131, f);
                double age = (time + hash(seed + 137, f) * 30.0D) / lifeT;
                int gen = (int) Math.floor(age);
                double life = age - gen;
                int at = 1 + (int) (hash(seed + 139, f * 977 + gen) * (SEGMENTS - 3));
                double ang = hash(seed + 149, f * 977 + gen) * Math.PI * 2.0D;
                Vec3 out = a.scale(Math.cos(ang)).add(b.scale(Math.sin(ang)));
                Vec3 from = pts[at].add(out.scale((0.15D + 0.35D * life) * scale));
                Vec3 to = from.add(out.scale(0.08D * scale)).add(dir.scale(-0.12D * scale));
                VfxDraw.segment(ink, last, from, to, cam, 0.025D * scale * (1.0D - life), (float) (0.9D * (1.0D - life)), 0.06F, 0.03F, 0.1F);
            }
        }
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
