package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.ClientPillState;
import io.github.verycooltimo.murim.cultivation.PerfectLift;
import io.github.verycooltimo.murim.cultivation.PillKind;
import io.github.verycooltimo.murim.network.PillPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import org.joml.Vector3f;

import java.util.Random;

/**
 * Пилюли на теле (docs/design/19b §2 и §10): the game «Провести поток» is a screen of its own
 * ({@link io.github.verycooltimo.murim.client.flow.FlowOverlay}); here are the old body scenes that stay: the recoil of a blow and the lift: безупречное усвоение высшей пилюли — подлёт на блок–полтора:
 * выброс ци у даньтяня, радужные и белые ленты вокруг тела, кольцо пыли на земле (рефы pill-01..04, канон
 * гл. 175–176, 249).
 *
 * <p>Всё — частицы и ленты с чёткими краями, без больших карточек и без огромных фигур над игроком:
 * масштаб эффекта — тело (правило автора 18.10). Стадия — {@code AFTER_PARTICLES}.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class PillVfx {

    private static final VfxColour WHITE = new VfxColour(1.0F, 1.0F, 1.0F);
    private static final VfxColour WILD = hex(0xFF5A3C);
    private static final VfxColour WILD_HOT = hex(0xFFD27A);
    private static final VfxColour HALO = hex(0xA7FFFF);
    /** Пять цветов рефа pill-03/04: зелёный, жёлтый, розовый, сиреневый, голубой; красный — огонь. */
    private static final VfxColour[] FIVE = {hex(0xC4F2A5), hex(0xF4E4AC), hex(0xEAC4D3), hex(0xC8B8E6), hex(0xB7E7FF)};
    private static final VfxColour FIRE = hex(0xFF7A5A);
    /** Насыщенные пять стихий для входа энергии: пастель рефа растворялась в белом (codex). */
    private static final VfxColour[] INFLOW = {hex(0x4FD35A), hex(0xF5C842), hex(0xF05AA0), hex(0x8C6CF0), hex(0x38C8F0),
            hex(0xFF6040)};

    private PillVfx() {
    }

    private static VfxColour hex(int c) {
        return new VfxColour(((c >> 16) & 0xFF) / 255.0F, ((c >> 8) & 0xFF) / 255.0F, (c & 0xFF) / 255.0F);
    }

    /** Лента для гладкой отрисовки: точки, прозрачность, цвет. */
    record Band(Vec3[] pts, float alpha, VfxColour col) {
    }

    /** Точки тела (уже вынесены к камере), оси экрана и уровень земли под ногами. */
    record Body(Vec3 dantian, Vec3 chest, Vec3 head, Vec3 lShoulder, Vec3 rShoulder, Vec3 lHand, Vec3 rHand,
                Vec3 lKnee, Vec3 rKnee, Vec3 screenRight, Vec3 screenUp, Vec3 camera, PoseStack.Pose pose,
                MultiBufferSource.BufferSource buffers, double ground) {
    }

    @SubscribeEvent
    static void onRender(RenderLevelStageEvent event) {
        // The body game owns this surface, including its quiet finale and torn feedback (ADR 0022).
        if (io.github.verycooltimo.murim.client.flow.FlowClient.bodyPresentation()) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        int finale = ClientPillState.finaleAge();
        boolean recoil = ClientPillState.deviationAge() >= 0;
        if (finale < 0 && !recoil) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.player instanceof AbstractClientPlayer player)) {
            return;
        }
        Vec3 d = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.DANTIAN);
        Vec3 c = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.CHEST);
        Vec3 h = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.HEAD);
        Vec3 ls = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.LEFT_SHOULDER);
        Vec3 rs = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.RIGHT_SHOULDER);
        Vec3 lh = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.LEFT_HAND);
        Vec3 rh = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.RIGHT_HAND);
        Vec3 lk = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.LEFT_KNEE);
        Vec3 rk = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.RIGHT_KNEE);
        if (d == null || c == null || h == null || ls == null || rs == null || lh == null || rh == null
                || lk == null || rk == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        Vector3f left = event.getCamera().getLeftVector();
        Vector3f camUp = event.getCamera().getUpVector();
        Vec3 screenRight = new Vec3(-left.x(), -left.y(), -left.z());
        Vec3 screenUp = new Vec3(camUp.x(), camUp.y(), camUp.z());
        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        try {
            ps.translate(-cam.x, -cam.y, -cam.z);
            // Точки костей лежат ВНУТРИ модели, и аддитивный слой с проверкой глубины тело закрывало
            // целиком (первые кадры стенда 03.10: ни жил, ни сгустка). Выносим к поверхности со
            // стороны камеры — тот же приём, что у ядра медитации.
            Body b = new Body(skin(d, cam), skin(c, cam), skin(h, cam), skin(ls, cam), skin(rs, cam), skin(lh, cam),
                    skin(rh, cam), skin(lk, cam), skin(rk, cam), screenRight, screenUp, cam, ps.last(),
                    mc.renderBuffers().bufferSource(), player.getY());
            if (recoil) {
                drawRecoil(b, ClientPillState.deviationAge() + partial);
            }
            if (finale >= 0) {
                drawLift(b, finale + partial, ClientPillState.finaleFull(), ClientPillState.finaleClots());
            }
            flushRings(b);
        } finally {
            RINGS.clear();
            ps.popPose();
        }
    }

    private static Vec3 skin(Vec3 bone, Vec3 cam) {
        Vec3 to = cam.subtract(bone);
        return to.lengthSqr() < 1.0E-6D ? bone : bone.add(to.normalize().scale(0.2D));
    }

    private static Vec3[] curve(Vec3 a, Vec3 m, Vec3 z, int n) {
        Vec3[] out = new Vec3[n + 1];
        for (int i = 0; i <= n; i++) {
            double t = i / (double) n;
            out[i] = a.scale((1 - t) * (1 - t)).add(m.scale(2 * t * (1 - t))).add(z.scale(t * t));
        }
        return out;
    }

    private static Vec3[] resample(Vec3[] pts, int n) {
        double[] len = new double[pts.length];
        for (int i = 1; i < pts.length; i++) {
            len[i] = len[i - 1] + pts[i].distanceTo(pts[i - 1]);
        }
        Vec3[] out = new Vec3[n + 1];
        for (int k = 0; k <= n; k++) {
            out[k] = along(pts, len, k / (double) n);
        }
        return out;
    }

    private static Vec3 along(Vec3[] pts, double[] len, double t) {
        double target = len[len.length - 1] * Mth.clamp(t, 0.0D, 1.0D);
        for (int i = 1; i < pts.length; i++) {
            if (len[i] >= target) {
                double seg = len[i] - len[i - 1];
                double f = seg <= 1.0E-9D ? 0.0D : (target - len[i - 1]) / seg;
                return pts[i - 1].lerp(pts[i], f);
            }
        }
        return pts[pts.length - 1];
    }

    private static Vec3 at(Vec3[] path, double t) {
        double[] len = new double[path.length];
        for (int i = 1; i < path.length; i++) {
            len[i] = len[i - 1] + path[i].distanceTo(path[i - 1]);
        }
        return along(path, len, t);
    }

    /**
     * Гладкая лента по точкам с общими боковыми векторами на стыках (урок Пика: отрезки по
     * отдельности дают пунктир и «гребёнку»). Рисуется часть пути {@code from..to}.
     */
    static void line(VertexConsumer vc, Body b, Vec3[] pts, double from, double to, double width, float alpha,
                             VfxColour col) {
        int n = pts.length - 1;
        if (n < 1 || alpha <= 0.0F || to <= from) {
            return;
        }
        Vec3[] sides = new Vec3[n + 1];
        for (int i = 0; i <= n; i++) {
            Vec3 tan = pts[Math.min(n, i + 1)].subtract(pts[Math.max(0, i - 1)]);
            Vec3 sd = tan.cross(b.camera().subtract(pts[i]));
            sides[i] = sd.lengthSqr() < 1.0E-12D ? new Vec3(0.0D, 1.0D, 0.0D) : sd.normalize();
        }
        for (int i = 0; i < n; i++) {
            double t0 = i / (double) n;
            double t1 = (i + 1) / (double) n;
            if (t1 <= from || t0 >= to) {
                continue;
            }
            double f0 = Math.max(0.0D, (from - t0) * n);
            double f1 = Math.min(1.0D, (to - t0) * n);
            Vec3 a = pts[i].lerp(pts[i + 1], f0);
            Vec3 z = pts[i].lerp(pts[i + 1], f1);
            Vec3 sa = sides[i].lerp(sides[i + 1], f0).normalize().scale(width);
            Vec3 sz = sides[i].lerp(sides[i + 1], f1).normalize().scale(width);
            Vec3 nrm = b.camera().subtract(a).normalize();
            VfxDraw.vertex(vc, b.pose(), a.subtract(sa), nrm, 0.0F, 0.0F, alpha, col.red(), col.green(), col.blue());
            VfxDraw.vertex(vc, b.pose(), z.subtract(sz), nrm, 1.0F, 0.0F, alpha, col.red(), col.green(), col.blue());
            VfxDraw.vertex(vc, b.pose(), z.add(sz), nrm, 1.0F, 1.0F, alpha, col.red(), col.green(), col.blue());
            VfxDraw.vertex(vc, b.pose(), a.add(sa), nrm, 0.0F, 1.0F, alpha, col.red(), col.green(), col.blue());
        }
    }

    /**
     * Широкая светящаяся лента с переменной шириной и цветом по длине (рефы автора: «перестройка тела», макет
     * {@code 3_rank_vortex_v1}): к концам сужается, цвет задаёт {@code colourAt}. Общие боковые векторы на стыках — без
     * «гребёнки». Каждая лента — один проход, поверх неё кладётся белое ядро вдвое уже.
     */
    static void band(VertexConsumer vc, Body b, Vec3[] pts, double halfMax, float alpha,
                     java.util.function.DoubleFunction<VfxColour> colourAt) {
        int n = pts.length - 1;
        if (n < 1 || alpha <= 0.0F) {
            return;
        }
        Vec3[] sides = new Vec3[n + 1];
        for (int i = 0; i <= n; i++) {
            Vec3 tan = pts[Math.min(n, i + 1)].subtract(pts[Math.max(0, i - 1)]);
            Vec3 sd = tan.cross(b.camera().subtract(pts[i]));
            sides[i] = sd.lengthSqr() < 1.0E-12D ? new Vec3(0.0D, 1.0D, 0.0D) : sd.normalize();
        }
        for (int i = 0; i < n; i++) {
            double u0 = i / (double) n;
            double u1 = (i + 1) / (double) n;
            double w0 = halfMax * Math.pow(Math.sin(Math.PI * u0), 0.75D);
            double w1 = halfMax * Math.pow(Math.sin(Math.PI * u1), 0.75D);
            VfxColour c0 = colourAt.apply(u0);
            VfxColour c1 = colourAt.apply(u1);
            Vec3 nrm = b.camera().subtract(pts[i]).normalize();
            Vec3 s0 = sides[i].scale(w0);
            Vec3 s1 = sides[i + 1].scale(w1);
            VfxDraw.vertex(vc, b.pose(), pts[i].subtract(s0), nrm, 0.0F, 0.0F, alpha, c0.red(), c0.green(), c0.blue());
            VfxDraw.vertex(vc, b.pose(), pts[i + 1].subtract(s1), nrm, 1.0F, 0.0F, alpha, c1.red(), c1.green(), c1.blue());
            VfxDraw.vertex(vc, b.pose(), pts[i + 1].add(s1), nrm, 1.0F, 1.0F, alpha, c1.red(), c1.green(), c1.blue());
            VfxDraw.vertex(vc, b.pose(), pts[i].add(s0), nrm, 0.0F, 1.0F, alpha, c0.red(), c0.green(), c0.blue());
        }
    }

    /** Перламутр: мягкая радуга по параметру {@code k} (розовый, голубой, жёлтый, сиреневый), всегда светлая. */
    static VfxColour iridescent(double k) {
        double tau = Math.PI * 2.0D;
        return new VfxColour((float) (0.66D + 0.34D * Math.sin(tau * k)), (float) (0.66D + 0.34D * Math.sin(tau * (k + 1.0D / 3.0D))),
                (float) (0.7D + 0.3D * Math.sin(tau * (k + 2.0D / 3.0D))));
    }

    // ------------------------------------------------------------------ отдача удара

    /** The qi deviation (a blow broke the flow): a red burst at the dantian that dies in a second. The old pill scene, kept. */
    private static void drawRecoil(Body b, float age) {
        float k = Mth.clamp(age / ClientPillState.DEVIATION_TICKS, 0.0F, 1.0F);
        VertexConsumer glow = b.buffers().getBuffer(MurimRenderTypes.impactCore());
        CoreGlow.draw(glow, b.pose(), b.dantian().add(0.0D, -0.07D, 0.0D), b.camera(), age, 0.1D + 0.22D * k,
                0.95F * (1.0F - k) * (1.0F - k), WILD, WILD_HOT);
        b.buffers().endBatch(MurimRenderTypes.impactCore());
    }

    /** Кольцо, отложенное до конца кадра: {@link #flushRings}. */
    private record QueuedRing(Vec3[] pts, double from, double to, double halfWidth, float alpha, VfxColour col) {
    }

    /**
     * Очередь колец кадра. Доступ только с потока отрисовки. Кольца нельзя рисовать «по месту»: {@code getBuffer} другого
     * {@code RenderType} закрывает партию предыдущего, и следующая запись в её буфер падает с «Not building!»
     * (поймано прогоном стенда 19.10). Поэтому кольца копятся и рисуются одной партией после всех остальных.
     */
    private static final java.util.List<QueuedRing> RINGS = new java.util.ArrayList<>();

    /**
     * Кольцо лицом к камере сплошной широкой лентой (air_band) с общими боковыми векторами: без «пунктира» из
     * отдельных отрезков.
     */
    private static void ring(VertexConsumer c, Body b, Vec3 centre, double radius, double halfWidth, float alpha, VfxColour col) {
        queueRing(centre, b.screenRight(), b.screenUp(), radius, halfWidth, alpha, col);
    }

    /** Ставит кольцо в очередь кадра; оси {@code right} и {@code up} задают плоскость. */
    static void queueRing(Vec3 centre, Vec3 right, Vec3 up, double radius, double halfWidth, float alpha, VfxColour col) {
        if (alpha <= 0.0F) {
            return;
        }
        int seg = 56;
        Vec3[] pts = new Vec3[seg + 1];
        for (int i = 0; i <= seg; i++) {
            double a = i * Math.PI * 2 / seg;
            pts[i] = centre.add(right.scale(Math.cos(a) * radius)).add(up.scale(Math.sin(a) * radius));
        }
        RINGS.add(new QueuedRing(pts, 0.0D, 1.0D, halfWidth, alpha, col));
    }

    /** Ставит в очередь готовую замкнутую ломаную. */
    static void queueRing(Vec3[] pts, double halfWidth, float alpha, VfxColour col) {
        if (alpha > 0.0F) {
            RINGS.add(new QueuedRing(pts, 0.0D, 1.0D, halfWidth, alpha, col));
        }
    }

    /** Ставит в очередь часть ломаной {@code from..to} (0..1): чёткий широкий мазок, например дугу вихря. */
    static void queueStroke(Vec3[] pts, double from, double to, double halfWidth, float alpha, VfxColour col) {
        if (alpha > 0.0F && to > from) {
            RINGS.add(new QueuedRing(pts, from, to, halfWidth, alpha, col));
        }
    }

    /** Рисует очередь колец одной партией и очищает её; вызывается после всех остальных партий кадра. */
    static void flushRings(Body b) {
        if (RINGS.isEmpty()) {
            return;
        }
        VertexConsumer wide = b.buffers().getBuffer(MurimRenderTypes.band());
        for (QueuedRing r : RINGS) {
            line(wide, b, r.pts(), r.from(), r.to(), r.halfWidth(), r.alpha(), r.col());
        }
        b.buffers().endBatch(MurimRenderTypes.band());
        RINGS.clear();
    }

    // ------------------------------------------------------------------ подлёт

    /** Радуга лент: красный, оранжевый, жёлтый, зелёный, голубой, фиолетовый, розовый — и белая нить в каждой. */
    private static final VfxColour[] RAINBOW = {hex(0xFF6040), hex(0xFFA23C), hex(0xF5E050), hex(0x4FD35A), hex(0x38C8F0),
            hex(0x8C6CF0), hex(0xF05AA0)};
    private static final VfxColour DUST = new VfxColour(0.76F, 0.70F, 0.60F);

    /**
     * Сцена подлёта (19.10): полное усвоение хорошей пилюли — «как прорыв, просто поглощая». Тело поднимается на
     * блок–полтора ({@link #onRenderPlayer}; высоту и потолок считает сервер), в верхней точке выброс ци у даньтяня,
     * вокруг тела радужные и белые ленты, на земле кольцо пыли, при низком потолке — стук головы со звёздами.
     * 5,5 с: подъём 0–42, выброс 52, удержание, спуск 80–110. Всё масштаба тела, без огромных фигур над игроком.
     *
     * <p>Фазы по контрасту (codex 03.10): подъём и приток энергии приглушены, пик — только выброс.
     */
    private static void drawLift(Body b, float age, boolean full, int[] clots) {
        Vec3 axis = new Vec3(b.chest().x, b.dantian().y, b.chest().z);
        Vec3 toCam = b.camera().subtract(b.chest()).normalize();
        double ground = b.ground() + 0.04D;
        VertexConsumer c = b.buffers().getBuffer(MurimRenderTypes.impactCore());
        float rise = Mth.clamp(age / 10.0F, 0.0F, 1.0F);
        float peak = Mth.clamp((age - PerfectLift.BURST) / 4.0F, 0.0F, 1.0F)
                * (1.0F - Mth.clamp((age - PerfectLift.BURST - 8.0F) / 14.0F, 0.0F, 1.0F));
        float glowK = 0.3F + 0.7F * peak;
        float fall = 1.0F - Mth.clamp((age - 86.0F) / 24.0F, 0.0F, 1.0F);
        float k = rise * fall;

        // Свет за телом (pill-01): голубое сияние шире корпуса, мягкая кайма по силуэту.
        Vec3 behind = b.chest().subtract(toCam.scale(0.3D));
        CoreGlow.draw(c, b.pose(), behind, b.camera(), age, 0.62D, 0.6F * glowK * k, HALO, WHITE);
        CoreGlow.draw(c, b.pose(), b.dantian().subtract(toCam.scale(0.3D)).add(0, -0.1D, 0), b.camera(), age + 9,
                0.46D, 0.8F * glowK * k, HALO, HALO);
        CoreGlow.draw(c, b.pose(), b.head().subtract(toCam.scale(0.25D)), b.camera(), age + 17, 0.3D, 0.7F * glowK * k,
                HALO, WHITE);
        Vec3[] rim = {b.lHand(), b.rHand(), b.chest()};
        for (int i = 0; i < rim.length; i++) {
            Vec3 at = rim[i].subtract(toCam.scale(0.1D));
            VfxDraw.billboard(c, b.pose(), at, b.camera(), 0.2D, 0.14F * glowK * k, HALO.red(), HALO.green(), HALO.blue());
            VfxDraw.billboard(c, b.pose(), at, b.camera(), 0.05D, 0.16F * glowK * k, 0.95F, 1.0F, 1.0F);
        }

        // Широкие светящиеся ленты вокруг тела (рефы автора «перестройка тела», макет 3_rank_vortex_v1 в масштабе тела):
        // три ленты шириной около трети роста тела, белое ядро и перламутровая кайма, раскручиваются вокруг тела от земли
        // чуть выше головы; тело остаётся читаемым (лента полупрозрачна), а не прячется за ними.
        float spirals = Mth.clamp((age - 12.0F) / 10.0F, 0.0F, 1.0F) * fall;
        java.util.List<Band> bands = new java.util.ArrayList<>();
        VfxColour[] palette = full ? new VfxColour[] {FIVE[0], FIVE[1], FIVE[2], FIVE[3], FIVE[4], FIRE} : RAINBOW;
        for (int i = 0; i < 5; i++) {
            double dir = i % 2 == 0 ? 1.0D : -1.0D;
            double base = i * Math.PI * 2.0D / 5.0D + age * 0.045D * dir;
            double bulge = 0.4D + 0.07D * (i % 3);
            int pts = 56;
            Vec3[] band = new Vec3[pts];
            for (int tr = 0; tr < pts; tr++) {
                double u = tr / (double) (pts - 1);
                double a = base + dir * u * Math.PI * 2.0D * 0.7D;
                double rad = bulge + 0.3D * Math.sin(Math.PI * u);
                double y = -0.4D + 0.95D * u + 0.08D * Math.sin(age * 0.12D + i);
                band[tr] = axis.add(Math.cos(a) * rad, y, Math.sin(a) * rad);
            }
            bands.add(new Band(band, spirals * 0.85F, i % 2 == 0 ? FIVE[4] : FIVE[2]));
        }

        // Осколки света вдоль лент (макет: десятки светлых осколков вокруг фигуры): квадраты разного размера, цвет по ленте.
        for (int i = 0; i < 70; i++) {
            Random r = new Random(9100L + i * 211L);
            Band band = bands.get(i % bands.size());
            float life = 18.0F + r.nextFloat() * 14.0F;
            float local = ((age * 0.8F + r.nextFloat() * life) % life) / life;
            int idx = Math.min(band.pts().length - 1, (int) (local * (band.pts().length - 1)));
            Vec3 at = band.pts()[idx].add((r.nextDouble() - 0.5D) * 0.35D, (r.nextDouble() - 0.5D) * 0.3D, (r.nextDouble() - 0.5D) * 0.35D);
            VfxColour fc = iridescent(i / 70.0D + age * 0.01D);
            float fa = spirals * (float) Math.sin(Math.PI * local);
            VfxDraw.billboard(c, b.pose(), at, b.camera(), 0.012D + 0.05D * r.nextDouble() * r.nextDouble() * 2.0D, 0.9F * fa,
                    fc.red(), fc.green(), fc.blue());
        }

        // Приток энергии (автор 03.10: «в тебя входит энергия»): сгустки пяти цветов по сходящимся спиралям втекают в
        // живот и сгущаются к выбросу; радиус — тело и немного вокруг, не комната.
        float inflow = Mth.clamp((age - 14.0F) / 8.0F, 0.0F, 1.0F) * (1.0F - Mth.clamp((age - PerfectLift.BURST + 2.0F) / 4.0F, 0.0F, 1.0F));
        if (inflow > 0.0F) {
            for (int i = 0; i < 9; i++) {
                Random r = new Random(31337L + i * 7919L);
                VfxColour col = INFLOW[i % INFLOW.length];
                float life = 16.0F + r.nextFloat() * 8.0F;
                float local = ((age * (1.0F + 0.6F * Mth.clamp((age - 30.0F) / 22.0F, 0.0F, 1.0F)) + r.nextFloat() * life) % life) / life;
                double base = r.nextDouble() * Math.PI * 2;
                double h0 = -0.2D + 1.4D * r.nextDouble();
                double rad0 = 1.1D + 0.8D * r.nextDouble();
                Vec3 prev = null;
                for (int tr = 0; tr < 4; tr++) {
                    float tk = Math.max(0.0F, local - tr * 0.05F);
                    double rad = rad0 * (1.0D - tk) * (1.0D - tk);
                    double a = base + tk * 3.0D;
                    Vec3 p = b.dantian().add(Math.cos(a) * rad, h0 * (1.0D - tk), Math.sin(a) * rad);
                    if (prev != null) {
                        float alpha = inflow * Math.min(1.0F, 0.4F + tk) * (1.0F - tr / 5.0F);
                        VfxDraw.billboard(c, b.pose(), p, b.camera(), 0.06D * (1.0D - tr / 5.0D), alpha, col.red(), col.green(), col.blue());
                    }
                    prev = p;
                }
                if (local > 0.92F) {
                    CoreGlow.draw(c, b.pose(), b.dantian(), b.camera(), age + i, 0.05D, inflow * 0.5F, col, col);
                }
            }
            CoreGlow.draw(c, b.pose(), b.dantian(), b.camera(), age, 0.06D + 0.08D * Mth.clamp((age - 24.0F) / 28.0F, 0.0F, 1.0F),
                    0.7F * inflow, HALO, WHITE);
        }

        // Выброс ци у даньтяня (pill-04): белые лучи из цепочек точек от живота, быстрое торможение; крупные клинья
        // сходят на нет. Длина — в пределах двух блоков вокруг тела.
        if (age >= PerfectLift.BURST) {
            float burstAge = age - PerfectLift.BURST;
            float fadeB = 1.0F - Mth.clamp(burstAge / 9.0F, 0.0F, 1.0F);
            for (int i = 0; i < 9; i++) {
                Random r = new Random(4242L + i * 977L);
                Vec3 dir = new Vec3(r.nextGaussian(), r.nextGaussian() * 0.5D + 0.2D, r.nextGaussian()).normalize();
                Vec3 origin = b.dantian().add(dir.scale(0.12D));
                double dist = 1.0D * (1.0D - Math.exp(-burstAge * 0.45D));
                double lenRay = 0.35D + 0.6D * r.nextDouble();
                VfxColour col = (i % 4 == 0) ? palette[i % palette.length] : WHITE;
                for (int j = 0; j < 5; j++) {
                    double sd = dist - lenRay * j / 5.0D;
                    if (sd <= 0) {
                        continue;
                    }
                    VfxDraw.billboard(c, b.pose(), origin.add(dir.scale(sd)), b.camera(), 0.07D * (1.0D - j / 6.0D),
                            fadeB * (1.0F - j / 6.0F), col.red(), col.green(), col.blue());
                }
            }
            for (int i = 0; i < 8; i++) {
                Random r = new Random(555L + i * 811L);
                double a = i * Math.PI * 2 / 8 + r.nextDouble() * 0.3D;
                Vec3 dir = new Vec3(Math.cos(a), 0.4D * r.nextGaussian() + 0.1D, Math.sin(a)).normalize();
                double len = (0.8D + 0.4D * r.nextDouble()) * (1.0D - Math.exp(-burstAge * 0.6D));
                int segs = 6;
                for (int j = 0; j < segs; j++) {
                    Vec3 p0 = b.dantian().add(dir.scale(len * j / segs));
                    Vec3 p1 = b.dantian().add(dir.scale(len * (j + 1) / segs));
                    double wid = 0.07D * (1.0D - j / (double) segs);
                    VfxDraw.segment(c, b.pose(), p0, p1, b.camera(), wid, 0.7F * fadeB * (1.0F - j / (float) segs), 1.0F, 1.0F, 1.0F);
                }
            }
            ring(c, b, b.dantian(), 0.2D + 1.1D * (1.0D - fadeB), 0.035D, 0.8F * fadeB, WHITE);
            ring(c, b, b.dantian(), 0.16D + 0.9D * (1.0D - fadeB), 0.05D, 0.4F * fadeB, HALO);
            CoreGlow.draw(c, b.pose(), b.dantian().add(toCam.scale(0.1D)), b.camera(), age, 0.14D + 0.16D * (1.0F - fadeB),
                    fadeB * fadeB, HALO, WHITE);
        }

        // Звёзды стука: при низком потолке голова «встречается» с ним (Хуашань, гл. 249) — жёлтые искры кругом над головой.
        int bump = ClientPillState.bumpAge();
        if (bump >= 0) {
            float sk = 1.0F - bump / 24.0F;
            for (int i = 0; i < 5; i++) {
                double a = i * Math.PI * 2 / 5 + bump * 0.35D;
                Vec3 p = b.head().add(b.screenRight().scale(Math.cos(a) * 0.2D)).add(b.screenUp().scale(0.2D + Math.sin(a) * 0.05D));
                VfxDraw.segment(c, b.pose(), p.add(0.0D, 0.03D, 0.0D), p.add(0.0D, -0.03D, 0.0D), b.camera(), 0.014D, sk, 1.0F, 0.94F, 0.5F);
                VfxDraw.segment(c, b.pose(), p.add(b.screenRight().scale(0.03D)), p.add(b.screenRight().scale(-0.03D)), b.camera(), 0.014D,
                        sk, 1.0F, 0.94F, 0.5F);
            }
        }
        b.buffers().endBatch(MurimRenderTypes.impactCore());

        // Ленты — гладкой лентой с общими боковыми векторами: без бусин на стыках.
        // Кайма — широкая полоса с чёткими краями (air_band), белое ядро — мягкая нить поверх.
        VertexConsumer wide = b.buffers().getBuffer(MurimRenderTypes.band());
        int bandIndex = 0;
        for (Band band : bands) {
            final double shift = bandIndex++ / 5.0D + age * 0.01D;
            band(wide, b, band.pts(), 0.19D, 0.56F * band.alpha(), u -> iridescent(shift + u * 0.6D));
        }
        b.buffers().endBatch(MurimRenderTypes.band());
        VertexConsumer rib = b.buffers().getBuffer(MurimRenderTypes.ribbon());
        for (Band band : bands) {
            band(rib, b, band.pts(), 0.085D, 0.35F * band.alpha(), u -> WHITE);
        }
        b.buffers().endBatch(MurimRenderTypes.ribbon());

        // Кольцо пыли на земле: два кольца-клуба манхвы расходятся от взлёта и от выброса. Земля — под ногами игрока,
        // а не под поднятым телом.
        var dustType = MurimRenderTypes.dustPuffs();
        VertexConsumer dust = b.buffers().getBuffer(dustType);
        dustRing(dust, b, axis, ground, age - 4.0F, 30.0F, 0.5D, 1.3D, 1.0F);
        dustRing(dust, b, axis, ground, age - PerfectLift.BURST, 30.0F, 0.6D, 1.8D, 1.0F);
        dustRing(dust, b, axis, ground, age - PerfectLift.BURST - 7.0F, 30.0F, 0.5D, 1.5D, 0.7F);
        b.buffers().endBatch(dustType);

        // Обломки и пыль с земли (pill-03): тёмные квадраты взлетают и падают.
        if (age >= PerfectLift.BURST) {
            float dAge = age - PerfectLift.BURST;
            VertexConsumer dark = b.buffers().getBuffer(MurimRenderTypes.shard());
            for (int i = 0; i < 60; i++) {
                Random r = new Random(77L + i * 313L);
                double ang = r.nextDouble() * Math.PI * 2;
                double sp = 0.04D + 0.04D * r.nextDouble();
                double vy = 0.1D + 0.08D * r.nextDouble();
                double x = Math.cos(ang) * (0.4D + sp * dAge);
                double z = Math.sin(ang) * (0.4D + sp * dAge);
                double y = ground + vy * dAge - 0.011D * dAge * dAge;
                if (y < ground) {
                    continue;
                }
                float alpha = 1.0F - Mth.clamp(dAge / 32.0F, 0.0F, 1.0F);
                VfxDraw.billboard(dark, b.pose(), new Vec3(axis.x + x, y, axis.z + z), b.camera(), 0.02D + 0.075D * r.nextDouble() * r.nextDouble() * 1.6D,
                        alpha, 0.35F, 0.33F, 0.32F);
            }
            b.buffers().endBatch(MurimRenderTypes.shard());
        }
    }

    /**
     * Расширяющееся кольцо клубов пыли по земле: клубы из ряда «горизонтальные» атласа, по одному на сектор, растут и
     * тают; живёт {@code life} тиков.
     */
    private static void dustRing(VertexConsumer c, Body b, Vec3 axis, double ground, float age, float life, double from, double to, float strength) {
        if (age < 0.0F || age > life || strength <= 0.0F) {
            return;
        }
        float k = age / life;
        double rr = from + (to - from) * (1.0D - Math.pow(1.0D - k, 2.0D));
        float a = strength * 1.0F * (1.0F - k) * Mth.clamp(age / 3.0F, 0.0F, 1.0F);
        int n = 40;
        for (int i = 0; i < n; i++) {
            Random r = new Random(6100L + i * 53L);
            double ang = i * Math.PI * 2 / n + r.nextDouble() * 0.25D;
            Vec3 p = new Vec3(axis.x + Math.cos(ang) * rr, ground + 0.07D + 0.05D * r.nextDouble(), axis.z + Math.sin(ang) * rr);
            tintedPuff(c, b, p, 0.14D + 0.14D * k + 0.06D * r.nextDouble(), 8 + r.nextInt(4), a, DUST, (float) (r.nextDouble() * 0.5D - 0.25D));
        }
    }

    /** Клуб атласа 4×4 в плоскости экрана, любого цвета (тот же приём, что у дыма узлов). */
    private static void tintedPuff(VertexConsumer c, Body b, Vec3 centre, double size, int cell, float alpha, VfxColour col, float angle) {
        if (alpha <= 0.0F) {
            return;
        }
        Vec3 forward = b.camera().subtract(centre);
        if (forward.lengthSqr() < 1.0E-6D) {
            return;
        }
        forward = forward.normalize();
        Vec3 reference = Math.abs(forward.y) > 0.95D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 r0 = forward.cross(reference).normalize();
        Vec3 u0 = r0.cross(forward).normalize();
        double cs = Math.cos(angle);
        double sn = Math.sin(angle);
        Vec3 right = r0.scale(cs).add(u0.scale(sn)).scale(size);
        Vec3 up = u0.scale(cs).subtract(r0.scale(sn)).scale(size);
        float u1 = (cell % 4) / 4.0F;
        float u2 = u1 + 0.25F;
        float v1 = (cell / 4) / 4.0F;
        float v2 = v1 + 0.25F;
        Vec3 n = new Vec3(0.0D, 1.0D, 0.0D);
        VfxDraw.vertex(c, b.pose(), centre.subtract(right).subtract(up), n, u1, v2, alpha, col.red(), col.green(), col.blue());
        VfxDraw.vertex(c, b.pose(), centre.add(right).subtract(up), n, u2, v2, alpha, col.red(), col.green(), col.blue());
        VfxDraw.vertex(c, b.pose(), centre.add(right).add(up), n, u2, v1, alpha, col.red(), col.green(), col.blue());
        VfxDraw.vertex(c, b.pose(), centre.subtract(right).add(up), n, u1, v1, alpha, col.red(), col.green(), col.blue());
    }

    // ------------------------------------------------------------------ парение

    /** Тело приподнимается в пятицветном выбросе (канон гл. 176: «начал парить в воздухе»). */
    /** Последним и только если не отменено: иначе Post не придёт и стек поз останется сдвинутым. */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    static void onRenderPlayer(RenderPlayerEvent.Pre event) {
        if (event.isCanceled() || event.getEntity() != Minecraft.getInstance().player) {
            return;
        }
        pushed = true;
        float lift = ClientPillState.lift(event.getPartialTick());
        // Низкий потолок: на верхней точке голова стукается — тело коротко проседает и дрожит, без пролома.
        int bump = ClientPillState.bumpAge();
        if (bump >= 0) {
            lift -= 0.1F * (float) Math.exp(-bump / 5.0F) * Math.abs(Mth.sin(bump * 0.9F));
        }
        event.getPoseStack().pushPose();
        if (lift != 0.0F) {
            event.getPoseStack().translate(0.0D, Math.max(0.0F, lift), 0.0D);
        }
    }

    private static boolean pushed;

    @SubscribeEvent
    static void onRenderPlayerPost(RenderPlayerEvent.Post event) {
        if (!pushed || event.getEntity() != Minecraft.getInstance().player) {
            return;
        }
        pushed = false;
        event.getPoseStack().popPose();
    }
}
