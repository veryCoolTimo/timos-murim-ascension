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
    private static final VfxColour RIM = new VfxColour(0xED / 255.0F, 0x60 / 255.0F, 0x9B / 255.0F);

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

    /** Клуб пыли манхвы (атлас 4×4, как у основы меча): скользит по земле и растёт. */
    private static final class Puff {
        Vec3 pos;
        Vec3 prev;
        Vec3 vel;
        int age;
        final int life;
        final int cell;
        final double size;

        Puff(Vec3 pos, Vec3 vel, int life, int cell, double size) {
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.life = life;
            this.cell = cell;
            this.size = size;
        }
    }

    /** Порыв ветра от удара: изогнутая лента у земли, уходящая от корня по кругу наружу. */
    private record Gust(double angle, double sweep, double height, double width, float delay, double reach) {
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
        final List<Puff> puffs = new ArrayList<>();
        final List<Gust> gusts = new ArrayList<>();
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
        spawnGround(cast);
        if (PlumRules.blossoms(cast.layer)) {
            // Лепестки цветка уходят в поток разреза, остальные рождаются вдоль него.
            int total = cast.layer >= 4 ? 8 : 5;
            for (int i = 0; i < total; i++) {
                // 5 — лепестки цветка, 5 — из нижней пятой части столпа, остальные выше.
                double u = i < 5 ? 0.02D * i : i < 10 ? 0.2D * cast.random.nextDouble() : 0.3D + 0.4D * cast.random.nextDouble();
                Vec3 at = arcPoint(cast, u, cast.random.nextDouble(), 1.0D).add(cast.right.scale((cast.random.nextDouble() - 0.5D) * 0.3D));
                Vec3 vel = new Vec3(0.0D, 0.08D + 0.06D * cast.random.nextDouble(), 0.0D)
                        .add(cast.forward.scale(0.03D + 0.04D * cast.random.nextDouble()))
                        .add(cast.right.scale((cast.random.nextDouble() - 0.5D) * 0.06D));
                cast.petals.add(new Petal(at, vel, cast.random.nextInt(4),
                        (float) ((cast.random.nextDouble() - 0.5D) * 0.5D), 0.06D + 0.05D * cast.random.nextDouble()));
            }
        }
    }

    /**
     * Пыль и ветер от удара (автор 02.10: «от удара поднимается пыль и ветер, как с аурой»).
     * Пыль — неравными группами из-под корня дуги и вдоль взмаха, по земле наружу и чуть вверх;
     * ветер — изогнутые ленты у земли, разбегающиеся от корня. Количество растёт со слоем.
     */
    private static void spawnGround(Cast c) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.level.getBlockState(
                net.minecraft.core.BlockPos.containing(c.origin.add(0.0D, -0.2D, 0.0D))).isAir()) {
            return;
        }
        Random r = c.random;
        Vec3 root = arcEdge(c, 0.0D, 1.0D);
        int puffs = c.layer >= 4 ? 16 : c.layer == 3 ? 12 : c.layer == 2 ? 8 : 5;
        for (int i = 0; i < puffs; i++) {
            // Две трети — у корня, треть — вдоль взмаха от стопы.
            boolean atRoot = i % 3 != 2;
            Vec3 at = atRoot ? root : c.origin.add(c.forward.scale(0.4D * r.nextDouble()));
            double a = r.nextDouble() * Math.PI * 2.0D;
            Vec3 out = c.forward.scale(Math.cos(a)).add(c.right.scale(Math.sin(a)));
            if (out.dot(c.forward) < -0.3D) {
                out = out.add(c.forward.scale(0.6D)).normalize();
            }
            at = at.add(out.scale(0.2D + 0.3D * r.nextDouble())).add(0.0D, 0.15D, 0.0D);
            Vec3 vel = out.scale(0.12D + 0.14D * r.nextDouble()).add(0.0D, 0.02D + 0.05D * r.nextDouble(), 0.0D);
            c.puffs.add(new Puff(at, vel, 12 + r.nextInt(10), r.nextInt(16), 0.25D + 0.2D * r.nextDouble() + 0.04D * c.layer));
        }
        int gusts = c.layer >= 4 ? 7 : c.layer == 3 ? 5 : c.layer == 2 ? 4 : 2;
        for (int i = 0; i < gusts; i++) {
            double a = -Math.PI * 0.75D + Math.PI * 1.5D * (i + r.nextDouble() * 0.7D) / gusts;
            c.gusts.add(new Gust(a, Math.toRadians(35.0D + 30.0D * r.nextDouble()) * (r.nextBoolean() ? 1 : -1),
                    0.08D + 0.5D * r.nextDouble() * r.nextDouble(), 0.05D + 0.05D * r.nextDouble(),
                    r.nextFloat() * 1.5F, 2.4D + 1.4D * r.nextDouble() + 0.3D * c.layer));
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
            for (Puff p : c.puffs) {
                p.prev = p.pos;
                p.age++;
                p.vel = new Vec3(p.vel.x * 0.86D, p.vel.y * 0.9D, p.vel.z * 0.86D);
                p.pos = p.pos.add(p.vel);
            }
            c.puffs.removeIf(p -> p.age >= p.life);
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
                    float sa = clientTicks - c.slashTick + partial;
                    slash(c, pose, camera, air, sa);
                    gusts(c, pose, camera, air, sa);
                }
                buffers.endBatch(MurimRenderTypes.airBand());
                if (!c.puffs.isEmpty()) {
                    RenderType dust = MurimRenderTypes.dustPuffs();
                    VertexConsumer d = buffers.getBuffer(dust);
                    for (Puff p : c.puffs) {
                        float pt = (p.age + partial) / p.life;
                        float alpha = pt < 0.5F ? 1.0F : Mth.clamp(1.0F - (pt - 0.5F) / 0.5F, 0.0F, 1.0F);
                        puff(d, pose, camera, p.prev.lerp(p.pos, partial), p.size * (0.7D + 0.8D * pt), p.cell, alpha);
                    }
                    buffers.endBatch(dust);
                }
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
     * Внешняя (режущая) кромка саблевидной дуги разреза в вертикальной плоскости взмаха F–U
     * (решение codex 02.10 по ref4–7, whirl5: «должен быть изогнутым», «широко снизу»).
     * θ = −75° + 90°·u; корень у стопы впереди (O + 0,55F), выпуклость вперёд, остриё на высоте H
     * слегка загибается назад. {@code scale} — для слабых соседних штрихов (меньший радиус).
     */
    private static Vec3 arcEdge(Cast c, double u, double scale) {
        double h = PlumRules.height(c.layer) * scale;
        double r = h / (Math.sin(Math.toRadians(15.0D)) + Math.sin(Math.toRadians(75.0D)));
        double th = Math.toRadians(-75.0D + 90.0D * u);
        double cf = 0.55D - r * Math.cos(Math.toRadians(75.0D));
        double cu = r * Math.sin(Math.toRadians(75.0D));
        return c.origin.add(c.forward.scale(cf + r * Math.cos(th))).add(0.0D, cu + r * Math.sin(th), 0.0D);
    }

    /** Нормаль дуги наружу (в плоскости F–U) — от неё внутрь отсчитывается ширина полотна. */
    private static Vec3 arcNormal(Cast c, double u) {
        double th = Math.toRadians(-75.0D + 90.0D * u);
        return c.forward.scale(Math.cos(th)).add(0.0D, Math.sin(th), 0.0D);
    }

    /** Точка полотна: v=0 — внешняя кромка, v=1 — внутренняя (отстающая) сторона. */
    private static Vec3 arcPoint(Cast c, double u, double v, double scale) {
        double w = PlumRules.width(c.layer) * scale * Math.pow(1.0D - u, 1.15D);
        return arcEdge(c, u, scale).subtract(arcNormal(c, u).scale(v * w));
    }

    /**
     * Разрез: саблевидное полотно прорисовывается от корня к острию за 5 тиков вслед за мечом;
     * уже появившиеся участки стоят на месте. Гаснет от основания к острию к 14-му тику.
     * Белое ядро ~75 % ширины, тонкая розовая кайма по выпуклой кромке, шире — по внутренней,
     * слабое розовое свечение вокруг. Слои 1–2 — холодные.
     */
    private static void slash(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float age) {
        if (age > 14.5F) {
            return;
        }
        boolean pink = PlumRules.blossoms(c.layer);
        sabre(c, pose, camera, v, age, 1.0D, 1.0F, pink);
        // Слабые соседние штрихи: L2–3 — один, L4+ — два, тоньше и позже на тик.
        if (c.layer >= 2) {
            sabre(c, pose, camera, v, age - 1.0F, 0.82D, 0.35F, pink);
        }
        if (c.layer >= 4) {
            sabre(c, pose, camera, v, age - 1.6F, 0.66D, 0.25F, pink);
        }
        // Вспышка у корня на 2 тика.
        if (age < 2.0F) {
            Vec3 base = arcEdge(c, 0.0D, 1.0D).add(0.0D, 0.15D, 0.0D);
            Vec3 side = c.right;
            int m = 8;
            Vec3[] bp = new Vec3[m + 1];
            double[] bw = new double[m + 1];
            for (int i = 0; i <= m; i++) {
                double u = i / (double) m;
                bp[i] = base.add(side.scale((u - 0.5D) * 2.0D * PlumRules.width(c.layer)));
                bw[i] = 0.14D * Math.pow(Math.sin(Math.PI * u), 0.7D);
            }
            float fa = (float) curve(age, 0.0, 1.0, 0.6, 0.9, 2.0, 0.0);
            strip(v, pose, camera, bp, bw, 0.85F * fa, EDGE);
        }
        // Короткая холодная дуга по траектории меча у стопы (ref1, whirl1, whirl5), гаснет к +3.
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

    /** Одно саблевидное полотно; полосы — вдоль дуги, к камере, центр каждой — на своей доле ширины. */
    private static void sabre(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float age, double scale,
                              float bright, boolean pink) {
        if (age < 0.0F) {
            return;
        }
        double t = Mth.clamp(age / 5.0D, 0.0D, 1.0D);
        double shown = t * t * (3.0D - 2.0D * t);
        int n = 28;
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i <= n; i++) {
            if (i / (double) n <= shown + 1.0E-6D) {
                idx.add(i);
            }
        }
        if (idx.size() < 2) {
            return;
        }
        int m = idx.size();
        Vec3[] body = new Vec3[m];
        Vec3[] core = new Vec3[m];
        Vec3[] rim = new Vec3[m];
        Vec3[] inner = new Vec3[m];
        double[] wBody = new double[m];
        double[] wGlow = new double[m];
        double[] wCore = new double[m];
        double[] wRim = new double[m];
        double[] wInner = new double[m];
        float[] a = new float[m];
        double wBase = PlumRules.width(c.layer) * scale;
        for (int k = 0; k < m; k++) {
            double u = idx.get(k) / (double) n;
            double w = wBase * Math.pow(1.0D - u, 1.15D);
            body[k] = arcPoint(c, u, 0.5D, scale);
            core[k] = arcPoint(c, u, 0.42D, scale);
            rim[k] = arcPoint(c, u, 0.03D, scale);
            inner[k] = arcPoint(c, u, 0.88D, scale);
            wBody[k] = 0.5D * w;
            wGlow[k] = 0.62D * w;
            wCore[k] = 0.375D * w;
            wRim[k] = 0.03D * w + 0.004D;
            wInner[k] = 0.12D * w;
            // Гаснет от основания к острию: α = clamp((10 + 4u − t)/4).
            a[k] = bright * (float) Mth.clamp((10.0D + 4.0D * u - age) / 4.0D, 0.0D, 1.0D);
        }
        VfxColour body0 = pink ? PINK : COLD;
        stripVar(v, pose, camera, body, wGlow, scaled(a, 0.18F), body0);
        stripVar(v, pose, camera, body, wBody, scaled(a, pink ? 0.5F : 0.42F), body0);
        if (pink) {
            stripVar(v, pose, camera, inner, wInner, scaled(a, 0.55F), RIM);
            stripVar(v, pose, camera, rim, wRim, scaled(a, 0.6F), RIM);
        }
        stripVar(v, pose, camera, core, wCore, scaled(a, 0.9F), EDGE);
    }

    /**
     * Ветер: ленты у земли расходятся от корня дуги по кругу, закручиваясь (как порывы давления
     * ауры), за ~10 тиков уходят на 2,5–4 блока и гаснут с хвоста; у корня — два восходящих вихря.
     */
    private static void gusts(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float age) {
        Vec3 root = arcEdge(c, 0.0D, 1.0D).add(0.0D, 0.05D, 0.0D);
        for (Gust g : c.gusts) {
            float t = age - g.delay();
            if (t < 0.0F || t > 12.0F) {
                continue;
            }
            double reach = g.reach() * (1.0D - Math.exp(-t / 3.5D));
            float alpha = (float) curve(t, 0.0, 0.0, 0.8, 0.85, 6.0, 0.6, 12.0, 0.0);
            int m = 10;
            Vec3[] p = new Vec3[m + 1];
            double[] w = new double[m + 1];
            for (int i = 0; i <= m; i++) {
                double u = i / (double) m;
                // Голова впереди, хвост отстаёт по радиусу и закручен по дуге.
                double rad = Math.max(0.2D, reach - 1.1D * u);
                double ang = g.angle() + g.sweep() * u;
                Vec3 dir = c.forward.scale(Math.cos(ang)).add(c.right.scale(Math.sin(ang)));
                p[i] = root.add(dir.scale(rad)).add(0.0D, g.height() * (1.0D - u) + 0.05D, 0.0D);
                w[i] = g.width() * Math.sin(Math.PI * Math.min(1.0D, u * 1.3D + 0.05D)) * (1.0D - 0.3D * t / 12.0D);
            }
            strip(v, pose, camera, p, w, 0.4F * alpha, COLD);
            strip(v, pose, camera, p, scale(w, 0.3D), 0.85F * alpha, EDGE);
        }
        // Восходящие вихри у корня: вверх по спирали вокруг основания дуги (L2+).
        if (c.layer >= 2 && age < 9.0F) {
            for (int k = 0; k < 2; k++) {
                int m = 14;
                Vec3[] p = new Vec3[m + 1];
                double[] w = new double[m + 1];
                double rise = Math.min(1.0D, age / 4.0D);
                for (int i = 0; i <= m; i++) {
                    double u = i / (double) m;
                    double ang = Math.PI * k + u * Math.PI * 1.6D + age * 0.25D;
                    double rad = 0.45D + 0.35D * u;
                    p[i] = root.add(c.forward.scale(Math.cos(ang) * rad)).add(c.right.scale(Math.sin(ang) * rad))
                            .add(0.0D, u * (0.6D + 1.6D * rise), 0.0D);
                    w[i] = 0.06D * Math.sin(Math.PI * u);
                }
                float a = (float) curve(age, 0.0, 0.0, 1.0, 0.7, 9.0, 0.0);
                strip(v, pose, camera, p, w, 0.35F * a, COLD);
                strip(v, pose, camera, p, scale(w, 0.3D), 0.8F * a, EDGE);
            }
        }
    }

    private static void puff(VertexConsumer c, PoseStack.Pose pose, Vec3 camera, Vec3 centre, double size, int cell, float alpha) {
        if (alpha <= 0.0F) {
            return;
        }
        Vec3 forward = camera.subtract(centre);
        if (forward.lengthSqr() < 1.0E-6D) {
            return;
        }
        forward = forward.normalize();
        Vec3 reference = Math.abs(forward.y) > 0.95D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 right = forward.cross(reference).normalize().scale(size);
        Vec3 up = right.normalize().cross(forward).normalize().scale(size);
        float u0 = (cell % 4) / 4.0F, u1 = u0 + 0.25F;
        float v0 = (cell / 4) / 4.0F, v1 = v0 + 0.25F;
        Vec3 n = new Vec3(0.0D, 1.0D, 0.0D);
        VfxDraw.vertex(c, pose, centre.subtract(right).subtract(up), n, u0, v1, alpha, 1.0F, 1.0F, 1.0F);
        VfxDraw.vertex(c, pose, centre.add(right).subtract(up), n, u1, v1, alpha, 1.0F, 1.0F, 1.0F);
        VfxDraw.vertex(c, pose, centre.add(right).add(up), n, u1, v0, alpha, 1.0F, 1.0F, 1.0F);
        VfxDraw.vertex(c, pose, centre.subtract(right).add(up), n, u0, v0, alpha, 1.0F, 1.0F, 1.0F);
    }

    private static float[] scaled(float[] a, float k) {
        float[] r = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = a[i] * k;
        }
        return r;
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
