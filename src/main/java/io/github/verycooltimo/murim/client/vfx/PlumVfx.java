package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.PlumSlashPayload;
import io.github.verycooltimo.murim.network.TechniqueEventPayload;
import io.github.verycooltimo.murim.technique.PlumRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Меч Семи Цветков Сливы, форма 1 «Разрез» (docs/design/techniques/seven-plum-blossoms-spec.md
 * §3.2–3.4; refs ref3, ref4, ref9, whirl1–5).
 *
 * <p>Такты: холодная подготовка у кисти (голубо-белые незамкнутые дуги, с L1) → на L3+ у острия
 * за 4 тика до выпуска собирается один красный бутон и раскрывается в цветок → выпуск: высокий
 * восходящий разрез-столп по коридору вперёд; на L3+ цвет цветка бежит по разрезу от основания
 * к острию, лепестки цветка уходят в поток. Слой 0 — без эффектов.
 *
 * <p>Профиль ширины от хвоста (основание) к голове (верх): широкое тело в первой трети от головы,
 * хвост длиннее и тоньше. Угасание с хвоста, ширина уходит раньше альфы. Альфа-смешение, без
 * свечения и размытия.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class PlumVfx {

    private static final net.minecraft.resources.ResourceLocation TECHNIQUE =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "seven_plum_blossoms");
    /** Выпуск через 8 тиков после начала (windup спецификации). */
    private static final int COMMIT = 8;

    private static final VfxColour COLD = new VfxColour(0xC9 / 255.0F, 0xE7 / 255.0F, 0xF4 / 255.0F);
    private static final VfxColour PINK = new VfxColour(0xF1 / 255.0F, 0x9B / 255.0F, 0xC5 / 255.0F);
    private static final VfxColour EDGE = new VfxColour(0xFA / 255.0F, 0xFF / 255.0F, 1.0F);

    private static final List<Cast> CASTS = new ArrayList<>();
    private static int clientTicks;

    private static final class Petal {
        Vec3 pos;
        Vec3 prev;
        Vec3 vel;
        int age;
        final int cell;
        final float spin;
        final double size;

        Petal(Vec3 pos, Vec3 vel, int cell, float spin, double size) {
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.cell = cell;
            this.spin = spin;
            this.size = size;
        }
    }

    private static final class Cast {
        final int entityId;
        final int layer;
        final int start;
        Vec3 origin;
        Vec3 forward;
        Vec3 right;
        double length;
        int slashTick = -1;
        final List<Petal> petals = new ArrayList<>();
        final Random random;

        Cast(int entityId, int layer) {
            this.entityId = entityId;
            this.layer = layer;
            this.start = clientTicks;
            this.random = new Random(entityId * 977L + clientTicks);
        }
    }

    public static void onTechniqueEvent(TechniqueEventPayload payload) {
        if (payload.event() != TechniqueEventPayload.Event.STARTED || !TECHNIQUE.equals(payload.techniqueId())
                || payload.layer() <= 0) {
            return;
        }
        CASTS.add(new Cast(payload.sourceId(), payload.layer()));
    }

    public static void onSlash(PlumSlashPayload p) {
        if (p.layer() <= 0) {
            return;
        }
        Cast cast = null;
        for (Cast c : CASTS) {
            if (c.entityId == p.entityId() && c.slashTick < 0) {
                cast = c;
            }
        }
        if (cast == null) {
            cast = new Cast(p.entityId(), p.layer());
            CASTS.add(cast);
        }
        cast.origin = p.origin();
        Vec3 f = Vec3.directionFromRotation(0.0F, p.yaw());
        cast.forward = new Vec3(f.x, 0.0D, f.z).normalize();
        cast.right = new Vec3(-cast.forward.z, 0.0D, cast.forward.x);
        cast.length = p.length();
        cast.slashTick = clientTicks;
        if (PlumRules.blossoms(cast.layer)) {
            // Лепестки цветка уходят в поток разреза, остальные рождаются вдоль него.
            int total = cast.layer >= 4 ? 12 : 8;
            for (int i = 0; i < total; i++) {
                double u = i < 5 ? 0.05D * i : 0.25D + 0.7D * cast.random.nextDouble();
                Vec3 at = slashPoint(cast, u).add(cast.right.scale((cast.random.nextDouble() - 0.5D) * 0.6D));
                Vec3 vel = cast.forward.scale(0.08D + 0.08D * cast.random.nextDouble())
                        .add(0.0D, 0.03D + 0.05D * cast.random.nextDouble(), 0.0D)
                        .add(cast.right.scale((cast.random.nextDouble() - 0.5D) * 0.08D));
                cast.petals.add(new Petal(at, vel, cast.random.nextInt(4),
                        (float) ((cast.random.nextDouble() - 0.5D) * 0.5D), 0.07D + 0.05D * cast.random.nextDouble()));
            }
        }
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            CASTS.clear();
            return;
        }
        if (minecraft.isPaused()) {
            return;
        }
        clientTicks++;
        Iterator<Cast> it = CASTS.iterator();
        while (it.hasNext()) {
            Cast c = it.next();
            for (Petal p : c.petals) {
                p.prev = p.pos;
                p.age++;
                p.vel = new Vec3(p.vel.x * 0.88D, p.vel.y * 0.88D - 0.004D, p.vel.z * 0.88D);
                p.pos = p.pos.add(p.vel);
            }
            if (clientTicks - c.start > 40) {
                it.remove();
            }
        }
    }

    /** Точка главной полосы разреза: u=0 — основание у стоп, u=1 — верх-вперёд (голова). */
    private static Vec3 slashPoint(Cast c, double u) {
        double h = PlumRules.height(c.layer);
        // Восходящая дуга: почти вертикаль (ref4, whirl5; отклонение ~12°), изгиб от взмаха.
        double s = 0.4D + 0.25D * c.length * (1.0D - Math.pow(1.0D - u, 1.6D));
        double y = 0.15D + h * Math.pow(u, 0.85D);
        return c.origin.add(c.forward.scale(s)).add(c.right.scale(0.15D + 0.15D * Math.sin(Math.PI * u))).add(0.0D, y, 0.0D);
    }

    /** Приблизительный сокет острия: правая рука с мечом перед телом. */
    private static Vec3 tip(Entity e, float partial, float raise) {
        Vec3 pos = e.getPosition(partial);
        Vec3 f = Vec3.directionFromRotation(0.0F, e.getViewYRot(partial));
        Vec3 r = new Vec3(-f.z, 0.0D, f.x);
        // В подготовке меч поднят у плеча (поза blockout), острие — над головой чуть впереди.
        return pos.add(f.scale(0.35D)).add(r.scale(0.35D)).add(0.0D, 1.5D + raise, 0.0D);
    }

    @SubscribeEvent
    static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || CASTS.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        poseStack.pushPose();
        try {
            poseStack.translate(-camera.x, -camera.y, -camera.z);
            PoseStack.Pose pose = poseStack.last();
            for (Cast c : CASTS) {
                float t = clientTicks - c.start + partial;
                Entity entity = minecraft.level.getEntity(c.entityId);
                VertexConsumer air = buffers.getBuffer(MurimRenderTypes.airBand());
                if (entity != null) {
                    preparation(c, entity, pose, camera, air, t, partial);
                }
                if (c.slashTick >= 0) {
                    slash(c, pose, camera, air, clientTicks - c.slashTick + partial);
                }
                buffers.endBatch(MurimRenderTypes.airBand());
                RenderType petals = MurimRenderTypes.plumPetals();
                VertexConsumer pc = buffers.getBuffer(petals);
                if (entity != null && PlumRules.blossoms(c.layer) && c.slashTick < 0) {
                    blossom(c, entity, pose, camera, pc, t, partial);
                }
                for (Petal p : c.petals) {
                    float a = Mth.clamp(1.0F - (p.age + partial - 8.0F) / 8.0F, 0.0F, 1.0F);
                    petal(pc, pose, camera, p.prev.lerp(p.pos, partial), p.size, p.cell, (p.age + partial) * p.spin, a,
                            1.0F, 0.75F, 0.8F);
                }
                buffers.endBatch(petals);
            }
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * Холодная подготовка: незамкнутые дуги у вооружённой кисти и предплечья (ref3, whirl2–3).
     * L1 — одна с t=5; L2 — две (t=3, t=5); L3+ — ещё лента у плеча. После выпуска за 3 тика гаснут.
     */
    private static void preparation(Cast c, Entity e, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float t, float partial) {
        float fade = c.slashTick < 0 ? 1.0F : Mth.clamp(1.0F - (clientTicks - c.slashTick + partial) / 3.0F, 0.0F, 1.0F);
        if (fade <= 0.0F) {
            return;
        }
        Vec3 hand = tip(e, partial, 0.0F).add(0.0D, -0.1D, 0.0D);
        Vec3 f = Vec3.directionFromRotation(0.0F, e.getViewYRot(partial));
        Vec3 r = new Vec3(-f.z, 0.0D, f.x);
        arc(v, pose, camera, hand, r, f, 0.28D, 160.0D, 20.0D, c.layer >= 2 ? 0.08D : 0.06D, t - 5.0F, fade);
        if (c.layer >= 2) {
            arc(v, pose, camera, hand.add(f.scale(-0.35D)).add(0.0D, -0.25D, 0.0D), r, f, 0.22D, 130.0D, 200.0D, 0.05D, t - 3.0F, fade);
        }
        if (c.layer >= 3) {
            arc(v, pose, camera, e.getPosition(partial).add(r.scale(0.3D)).add(0.0D, 1.35D, 0.0D), r, f, 0.4D, 150.0D, 90.0D, 0.13D, t - 2.0F, fade * 0.8F);
        }
    }

    /** Незамкнутая дуга вокруг точки: радиус, угол раскрытия и поворот (градусы), ширина, возраст. */
    private static void arc(VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Vec3 centre, Vec3 r, Vec3 f, double radius,
                            double sweep, double rot, double width, float age, float fade) {
        if (age < 0.0F) {
            return;
        }
        float a = Mth.clamp(age / 1.0F, 0.0F, 1.0F) * fade;
        int n = 12;
        Vec3[] p = new Vec3[n + 1];
        double[] w = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            double u = i / (double) n;
            double th = Math.toRadians(rot + sweep * u + age * 12.0D);
            p[i] = centre.add(r.scale(radius * Math.cos(th))).add(f.scale(radius * 0.6D * Math.sin(th))).add(0.0D, 0.25D * u, 0.0D);
            w[i] = width * Math.pow(Math.sin(Math.PI * u), 0.9D);
        }
        strip(v, pose, camera, p, w, 0.4F * a, COLD);
        strip(v, pose, camera, p, scale(w, 0.22D), 0.85F * a, EDGE);
    }

    /** Один красный цветок у острия: бутон за 4 тика до выпуска, раскрывается к выпуску. */
    private static void blossom(Cast c, Entity e, PoseStack.Pose pose, Vec3 camera, VertexConsumer pc, float t, float partial) {
        float k = Mth.clamp((t - (COMMIT - 4)) / 3.0F, 0.0F, 1.0F);
        if (t < COMMIT - 4) {
            return;
        }
        double diameter = 0.04D + (c.layer >= 4 ? 0.12D : 0.08D) * k;
        Vec3 at = tip(e, partial, 0.35F);
        for (int i = 0; i < 5; i++) {
            double th = Math.PI * 2.0D * i / 5.0D;
            Vec3 off = new Vec3(Math.cos(th), Math.sin(th) * 0.7D, Math.sin(th)).scale(diameter * 0.5D * k);
            petal(pc, pose, camera, at.add(off), diameter * (0.45D + 0.08D * i % 3), i % 4, (float) (th + 0.4D * i), 1.0F,
                    1.0F, 0.35F, 0.45F);
        }
    }

    /**
     * Разрез-столп. Фронт доходит до 65 % за первый тик, до вершины — ко второму. Участок,
     * рождённый в {@code b(u)}, раскрывается за полтика, держит пик до 1,5, затем теряет кромку
     * и ширину; старый хвост срезается с 2-го тика, к 6-му остаётся только тонкий верх.
     */
    private static void slash(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float age) {
        if (age > 8.0F) {
            return;
        }
        double maxWidth = PlumRules.width(c.layer);
        boolean pink = PlumRules.blossoms(c.layer);
        float bodyAlpha = c.layer >= 4 ? 0.65F : c.layer == 3 ? 0.58F : c.layer == 2 ? 0.45F : 0.38F;
        double cut = curve(age, 2.0, 0.0, 4.0, 0.45, 6.0, 0.85, 8.0, 1.0);
        int n = 28;
        List<Vec3> pts = new ArrayList<>();
        List<Double> half = new ArrayList<>();
        List<Float> alpha = new ArrayList<>();
        List<Float> edgeA = new ArrayList<>();
        List<Float> pinkA = new ArrayList<>();
        for (int i = 0; i <= n; i++) {
            double u = i / (double) n;
            double born = u <= 0.65D ? u / 0.65D : 1.0D + (u - 0.65D) / 0.35D;
            double seg = age - born;
            if (seg < 0.0D) {
                break;
            }
            if (u < cut - 0.1D) {
                continue;
            }
            double tailCut = Mth.clamp((u - cut) / 0.1D, 0.0D, 1.0D);
            double open = curve(seg, 0.0, 0.0, 0.5, 0.8, 1.5, 1.0, 3.0, 0.6, 6.0, 0.25);
            double prof = curve(u, 0.0, 0.0, 0.12, 0.30, 0.45, 1.0, 0.75, 0.6, 0.9, 0.3, 1.0, 0.0);
            pts.add(slashPoint(c, u));
            half.add(0.5D * maxWidth * prof * open * tailCut);
            alpha.add(bodyAlpha * (float) Mth.clamp(seg / 0.3D, 0.0D, 1.0D));
            edgeA.add((float) curve(seg, 0.0, 0.0, 0.3, 0.95, 1.5, 0.95, 3.0, 0.0));
            // Цвет цветка бежит от основания к острию за 2 тика.
            pinkA.add(pink ? (float) Mth.clamp((age - u * 2.0D) / 0.8D, 0.0D, 1.0D) : 0.0F);
        }
        if (pts.size() < 2) {
            return;
        }
        Vec3[] p = pts.toArray(new Vec3[0]);
        double[] w = new double[p.length];
        float[] a = new float[p.length];
        float[] e = new float[p.length];
        float[] pk = new float[p.length];
        for (int i = 0; i < p.length; i++) {
            w[i] = half.get(i);
            a[i] = alpha.get(i);
            e[i] = edgeA.get(i);
            pk[i] = pinkA.get(i) * a[i];
            a[i] *= 1.0F - pinkA.get(i);
        }
        stripVar(v, pose, camera, p, w, a, COLD);
        if (pink) {
            stripVar(v, pose, camera, p, w, pk, PINK);
        }
        // Светлая кромка — по переднему (верхнему) краю, узкая.
        double[] ew = new double[p.length];
        for (int i = 0; i < p.length; i++) {
            ew[i] = w[i] * 0.22D;
        }
        stripVar(v, pose, camera, p, ew, e, EDGE);
        // Отрывы от тела разреза, на тик позже: L2–L3 один, L4+ два (0,8 и 1,2 блока).
        if (c.layer >= 2 && age > 1.0F) {
            branch(c, pose, camera, v, age - 1.0F, 0.5D, 1.0D, 0.8D, 0.10D, pink);
            if (c.layer >= 4) {
                branch(c, pose, camera, v, age - 1.3F, 0.3D, -1.0D, 1.2D, 0.14D, pink);
            }
        }
        // Опора: холодная дуга у стопы (ref1, whirl1), гаснет к +3.
        if (age < 3.0F) {
            Vec3 foot = c.origin.add(c.forward.scale(0.3D)).add(0.0D, 0.05D, 0.0D);
            int m = 10;
            Vec3[] fp = new Vec3[m + 1];
            double[] fw = new double[m + 1];
            for (int i = 0; i <= m; i++) {
                double u = i / (double) m;
                double th = Math.toRadians(-70.0D + 140.0D * u);
                fp[i] = foot.add(c.forward.scale(0.45D * Math.cos(th))).add(c.right.scale(0.6D * Math.sin(th)));
                fw[i] = 0.07D * Math.sin(Math.PI * u);
            }
            float fa = (float) curve(age, 0.0, 0.0, 0.3, 0.8, 3.0, 0.0);
            strip(v, pose, camera, fp, fw, 0.45F * fa, COLD);
            strip(v, pose, camera, fp, scale(fw, 0.3D), 0.9F * fa, EDGE);
        }
    }

    /** Отрыв: из точки {@code at} главной полосы вбок-вверх; длина и ширина в блоках. */
    private static void branch(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float age, double at,
                               double sideSign, double length, double width, boolean pink) {
        if (age < 0.0F || age > 4.0F) {
            return;
        }
        Vec3 from = slashPoint(c, at);
        Vec3 dir = c.forward.scale(0.5D).add(c.right.scale(0.55D * sideSign)).add(0.0D, 0.7D, 0.0D).normalize();
        int m = 8;
        Vec3[] bp = new Vec3[m + 1];
        double[] bw = new double[m + 1];
        double grow = Math.min(1.0D, age / 0.6D);
        for (int i = 0; i <= m; i++) {
            double u = i / (double) m * grow;
            bp[i] = from.add(dir.scale(length * u)).add(c.forward.scale(0.15D * u * u));
            bw[i] = 0.5D * width * Math.sin(Math.PI * Math.min(1.0D, (i / (double) m) * 1.1D + 0.04D))
                    * curve(age, 0.0, 0.4, 1.0, 1.0, 4.0, 0.0);
        }
        float ba = (float) curve(age, 0.0, 0.0, 0.4, 1.0, 4.0, 0.0);
        strip(v, pose, camera, bp, bw, 0.55F * ba, pink ? PINK : COLD);
        strip(v, pose, camera, bp, scale(bw, 0.22D), 0.85F * ba, EDGE);
    }

    private static double curve(double x, double... xy) {
        if (x <= xy[0]) {
            return xy[1];
        }
        for (int i = 2; i < xy.length; i += 2) {
            if (x <= xy[i]) {
                double k = (x - xy[i - 2]) / (xy[i] - xy[i - 2]);
                return xy[i - 1] + (xy[i + 1] - xy[i - 1]) * k;
            }
        }
        return xy[xy.length - 1];
    }

    private static double[] scale(double[] v, double k) {
        double[] r = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            r[i] = v[i] * k;
        }
        return r;
    }

    private static void strip(VertexConsumer c, PoseStack.Pose pose, Vec3 camera, Vec3[] p, double[] w, float alpha, VfxColour col) {
        float[] a = new float[p.length];
        java.util.Arrays.fill(a, alpha);
        stripVar(c, pose, camera, p, w, a, col);
    }

    /** Полоса к камере с шириной и альфой в каждой точке; поперечная ось без переворотов. */
    private static void stripVar(VertexConsumer c, PoseStack.Pose pose, Vec3 camera, Vec3[] p, double[] w, float[] a, VfxColour col) {
        Vec3 last = null;
        Vec3[] side = new Vec3[p.length];
        for (int i = 0; i < p.length; i++) {
            Vec3 t = p[Math.min(p.length - 1, i + 1)].subtract(p[Math.max(0, i - 1)]);
            Vec3 sd = t.cross(camera.subtract(p[i]));
            if (sd.lengthSqr() > 1.0E-10D) {
                sd = sd.normalize();
                if (last != null && sd.dot(last) < 0.0D) {
                    sd = sd.scale(-1.0D);
                }
                last = sd;
            }
            side[i] = last;
        }
        for (int i = 0; i + 1 < p.length; i++) {
            if (side[i] == null || side[i + 1] == null || a[i] <= 0.0F && a[i + 1] <= 0.0F) {
                continue;
            }
            Vec3 o0 = side[i].scale(w[i]);
            Vec3 o1 = side[i + 1].scale(w[i + 1]);
            Vec3 n = side[i];
            VfxDraw.vertex(c, pose, p[i].subtract(o0), n, 0.0F, 0.0F, a[i], col.red(), col.green(), col.blue());
            VfxDraw.vertex(c, pose, p[i + 1].subtract(o1), n, 1.0F, 0.0F, a[i + 1], col.red(), col.green(), col.blue());
            VfxDraw.vertex(c, pose, p[i + 1].add(o1), n, 1.0F, 1.0F, a[i + 1], col.red(), col.green(), col.blue());
            VfxDraw.vertex(c, pose, p[i].add(o0), n, 0.0F, 1.0F, a[i], col.red(), col.green(), col.blue());
        }
    }

    private static void petal(VertexConsumer c, PoseStack.Pose pose, Vec3 camera, Vec3 centre, double size, int cell,
                              float spin, float alpha, float red, float green, float blue) {
        if (alpha <= 0.0F) {
            return;
        }
        Vec3 forward = camera.subtract(centre);
        if (forward.lengthSqr() < 1.0E-6D) {
            return;
        }
        forward = forward.normalize();
        Vec3 reference = Math.abs(forward.y) > 0.95D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 right0 = forward.cross(reference).normalize();
        Vec3 up0 = right0.cross(forward).normalize();
        double cs = Math.cos(spin), sn = Math.sin(spin);
        Vec3 right = right0.scale(cs).add(up0.scale(sn)).scale(size);
        Vec3 up = up0.scale(cs).subtract(right0.scale(sn)).scale(size * 0.6D);
        float u0 = (cell % 2) / 2.0F, u1 = u0 + 0.5F;
        float v0 = (cell / 2) / 2.0F, v1 = v0 + 0.5F;
        Vec3 n = new Vec3(0.0D, 1.0D, 0.0D);
        VfxDraw.vertex(c, pose, centre.subtract(right).subtract(up), n, u0, v1, alpha, red, green, blue);
        VfxDraw.vertex(c, pose, centre.add(right).subtract(up), n, u1, v1, alpha, red, green, blue);
        VfxDraw.vertex(c, pose, centre.add(right).add(up), n, u1, v0, alpha, red, green, blue);
        VfxDraw.vertex(c, pose, centre.subtract(right).add(up), n, u0, v0, alpha, red, green, blue);
    }

    private PlumVfx() {
    }
}
