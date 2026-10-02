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
    /** Первый удар вверх — через 12 тиков после начала: сперва стойка и сбор ци (автор 02.10). */
    private static final int COMMIT = 12;

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

    /** Часть дерева разреза: квадратичная кривая, ширина у основания, момент и время прорисовки, глубина. */
    private record Branch(Vec3 start, Vec3 ctrl, Vec3 end, double width, float born, float draw, int depth) {
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
        final List<Branch> branches = new ArrayList<>();
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
        buildTree(cast);
        spawnGround(cast);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getId() == p.entityId() && cast.layer >= 2) {
            // Выпуск — виньетка к точке удара (чуть выше центра: дуга уходит вверх).
            SpeedLines.radial(0.5F, 0.45F, Math.min(1.0F, 0.45F + 0.12F * cast.layer), 7, SpeedLines.WHITE);
        }
        if (PlumRules.blossoms(cast.layer)) {
            // Лепестки цветка уходят в поток разреза, остальные рождаются вдоль него.
            int total = cast.layer >= 4 ? 8 : 5;
            for (int i = 0; i < total; i++) {
                // 5 — лепестки цветка, 5 — из нижней пятой части столпа, остальные выше.
                double u = i < 5 ? 0.02D * i : i < 10 ? 0.2D * cast.random.nextDouble() : 0.3D + 0.4D * cast.random.nextDouble();
                Vec3 at = trunkBase(cast).add(0.0D, PlumRules.height(cast.layer) * u, 0.0D).add(cast.right.scale((cast.random.nextDouble() - 0.5D) * 1.6D * u));
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
        Vec3 root = trunkBase(c);
        int puffs = c.layer >= 4 ? 5 : c.layer == 3 ? 4 : 3;
        for (int i = 0; i < puffs; i++) {
            // Только от корня и наружу, вдоль земли (ref7): перед ногами комом не копится.
            double a = (r.nextDouble() - 0.5D) * Math.PI * 1.3D;
            Vec3 out = c.forward.scale(Math.cos(a)).add(c.right.scale(Math.sin(a)));
            Vec3 at = root.add(out.scale(0.3D + 0.3D * r.nextDouble())).add(0.0D, 0.12D, 0.0D);
            // Пыль вытянута по касательной вихря у стоп, а не разлетается шаром.
            Vec3 tangent = new Vec3(-out.z, 0.0D, out.x);
            Vec3 vel = out.scale(0.12D + 0.1D * r.nextDouble()).add(tangent.scale(0.12D + 0.08D * r.nextDouble()))
                    .add(0.0D, 0.01D + 0.02D * r.nextDouble(), 0.0D);
            c.puffs.add(new Puff(at, vel, 12 + r.nextInt(10), r.nextInt(16), 0.22D + 0.15D * r.nextDouble() + 0.03D * c.layer));
        }
        // Ветер — одна-две широкие закрученные приземные дуги (whirl5), не россыпь нитей.
        int gusts = c.layer >= 2 ? 2 : 1;
        for (int i = 0; i < gusts; i++) {
            double a = (i == 0 ? -0.6D : 0.7D) + (r.nextDouble() - 0.5D) * 0.4D;
            c.gusts.add(new Gust(a, Math.toRadians(110.0D) * (i == 0 ? 1 : -1), 0.25D + 0.15D * r.nextDouble(),
                    0.26D + 0.08D * r.nextDouble(), 0.5F * i, 2.6D + 0.25D * c.layer));
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
            if (c.slashTick >= 0 && clientTicks - c.slashTick == PlumRules.FALL_TICK + 6) {
                landing(c);
            }
            if (clientTicks - c.start > 70) {
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
                // От первого лица дуги подготовки и цветок висели бы у самых глаз и закрывали
                // пол-экрана: свои — только с видом со стороны, чужие — всегда.
                boolean ownFirstPerson = entity == minecraft.getCameraEntity() && minecraft.options.getCameraType().isFirstPerson();
                if (entity != null && !ownFirstPerson) {
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
                if (entity != null && !ownFirstPerson && PlumRules.blossoms(c.layer) && c.slashTick < 0) {
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
     * Основание ствола: из пола впереди, у цели (не от меча — автор 02.10), на пройденном
     * коридоре не дальше ~2,4 блока.
     */
    private static Vec3 trunkBase(Cast c) {
        return c.origin.add(c.forward.scale(PlumRules.trunkOffset(c.length))).add(0.0D, 0.02D, 0.0D);
    }

    /**
     * Дерево разреза (ref8, ref7, ref4): сначала ствол — прямая линия вверх из пола, потом по
     * очереди боковые дуги-удары влево-вправо, на старших слоях — с под-ветвями. Плоскость дерева
     * поперёк взгляда: со спины (как в референсах) видно целиком.
     */
    private static void buildTree(Cast c) {
        Random r = c.random;
        double h = PlumRules.height(c.layer);
        double wScale = PlumRules.width(c.layer) / 1.4D;
        Vec3 base = trunkBase(c);
        Vec3 top = base.add(0.0D, h, 0.0D).add(c.forward.scale(0.15D));
        // Ствол — от самого пола, в 2–3 раза толще ветвей (разбор codex 02.10).
        c.branches.add(new Branch(base.add(0.0D, -0.02D, 0.0D), base.lerp(top, 0.5D).add(c.right.scale(0.08D)), top,
                0.55D * wScale, 0.0F, 2.4F, 0));
        int[] counts = {0, 0, 4, 6, 10, 12, 14, 16};
        int count = counts[Math.max(0, Math.min(7, c.layer))];
        for (int i = 0; i < count; i++) {
            double at = 0.22D + 0.62D * (i + 0.5D) / count + (r.nextDouble() - 0.5D) * 0.06D;
            int side = i % 2 == 0 ? 1 : -1;
            // Каждая третья ветвь загибается через ствол: появляются пересечения ударов (ref6).
            boolean cross = i % 3 == 2;
            // Крона раскинута шире ствола (ref8): ветви пологие, выше — круче.
            double el = Math.toRadians(12.0D + 28.0D * r.nextDouble() + 20.0D * at);
            double len = (2.1D + 1.4D * r.nextDouble()) * (1.0D - 0.3D * at) * (h / 4.8D);
            Vec3 start = bezier(base, base.lerp(top, 0.5D).add(c.right.scale(0.08D)), top, at);
            Vec3 dir = c.right.scale(side * Math.cos(el)).add(0.0D, Math.sin(el), 0.0D)
                    .add(c.forward.scale((r.nextDouble() - 0.5D) * 0.35D)).normalize();
            Vec3 end = start.add(dir.scale(len));
            // Дуга-удар выгибается вверх, как серп взмаха.
            Vec3 ctrl = start.add(dir.scale(len * 0.5D)).add(0.0D, len * 0.2D, 0.0D);
            // Быстрые боковые взмахи (анимация 0,7–1,15 с): ветвь на каждый.
            float born = 2.0F + 9.0F * i / Math.max(1, count);
            double width = (0.17D + 0.05D * r.nextDouble()) * (1.0D - 0.4D * at) * wScale;
            c.branches.add(new Branch(start, ctrl, end, width, born, 0.6F, 1));
            if (c.layer >= 4) {
                // Под-ветвь от середины дуги — круче вверх, вдвое короче.
                Vec3 s2 = bezier(start, ctrl, end, 0.55D);
                double el2 = el + Math.toRadians(25.0D + 15.0D * r.nextDouble());
                Vec3 d2 = c.right.scale(side * Math.cos(el2)).add(0.0D, Math.sin(el2), 0.0D).normalize();
                double l2 = len * (0.45D + 0.15D * r.nextDouble());
                Vec3 e2 = s2.add(d2.scale(l2));
                c.branches.add(new Branch(s2, s2.add(d2.scale(l2 * 0.5D)).add(0.0D, l2 * 0.15D, 0.0D), e2,
                        width * 0.55D, born + 0.45F, 0.45F, 2));
            }
        }
    }

    /**
     * Падение дерева: рука вперёд (1,45 с) — дерево валится вперёд вокруг основания ствола,
     * разгоняясь, как под тяжестью, до ~85° за 7 тиков.
     */
    private static Vec3 fall(Cast c, Vec3 p, float age) {
        double k = Mth.clamp((age - PlumRules.FALL_TICK) / 7.0D, 0.0D, 1.0D);
        if (k <= 0.0D) {
            return p;
        }
        double th = Math.toRadians(85.0D) * k * k;
        Vec3 base = trunkBase(c);
        Vec3 d = p.subtract(base);
        double f = d.dot(c.forward);
        double up = d.y;
        double f2 = f * Math.cos(th) + up * Math.sin(th);
        double up2 = up * Math.cos(th) - f * Math.sin(th);
        return p.add(c.forward.scale(f2 - f)).add(0.0D, up2 - up, 0.0D);
    }

    /** Удар упавшего дерева о землю: пыль по линии падения, лепестки, виньетка своему игроку. */
    private static void landing(Cast c) {
        Random r = c.random;
        Vec3 base = trunkBase(c);
        double h = PlumRules.height(c.layer);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && !mc.level.getBlockState(net.minecraft.core.BlockPos.containing(base.add(0.0D, -0.2D, 0.0D))).isAir()) {
            int n = 4 + 2 * Math.min(4, c.layer);
            for (int i = 0; i < n; i++) {
                Vec3 at = base.add(c.forward.scale(h * (0.25D + 0.7D * r.nextDouble()))).add(c.right.scale((r.nextDouble() - 0.5D) * 2.4D))
                        .add(0.0D, 0.15D, 0.0D);
                Vec3 side = c.right.scale(r.nextBoolean() ? 1.0D : -1.0D);
                Vec3 vel = side.scale(0.1D + 0.1D * r.nextDouble()).add(c.forward.scale(0.05D)).add(0.0D, 0.03D, 0.0D);
                c.puffs.add(new Puff(at, vel, 12 + r.nextInt(8), r.nextInt(16), 0.3D + 0.15D * r.nextDouble()));
            }
        }
        if (PlumRules.blossoms(c.layer)) {
            for (int i = 0; i < 10; i++) {
                Vec3 at = base.add(c.forward.scale(h * (0.3D + 0.7D * r.nextDouble()))).add(c.right.scale((r.nextDouble() - 0.5D) * 2.4D))
                        .add(0.0D, 0.3D + 0.6D * r.nextDouble(), 0.0D);
                c.petals.add(new Petal(at, new Vec3((r.nextDouble() - 0.5D) * 0.12D, 0.06D + 0.06D * r.nextDouble(), (r.nextDouble() - 0.5D) * 0.12D),
                        r.nextInt(4), (float) ((r.nextDouble() - 0.5D) * 0.5D), 0.07D + 0.05D * r.nextDouble()));
            }
        }
        if (mc.player != null && mc.player.getId() == c.entityId) {
            SpeedLines.radial(0.5F, 0.55F, 0.6F, 5, SpeedLines.WHITE);
        }
    }

    private static Vec3 bezier(Vec3 a, Vec3 b, Vec3 z, double t) {
        double k = 1.0D - t;
        return a.scale(k * k).add(b.scale(2.0D * k * t)).add(z.scale(t * t));
    }

    /**
     * Разрез: дерево растёт по очереди (ствол за 1,5 тика, затем ветви через ~0,65 тика),
     * каждая часть прорисовывается от основания к острию; гаснет с 9-го тика от основания.
     * Белое ядро, ветви и ствол — в розовом свечении кроны (L3+); слои 1–2 холодные.
     */
    private static void slash(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float age) {
        if (age > PlumRules.FALL_TICK + 14.0F) {
            return;
        }
        boolean pink = PlumRules.blossoms(c.layer);
        for (Branch b : c.branches) {
            branch(c, pose, camera, v, age, b, pink);
        }
        Vec3 base = trunkBase(c);
        // Борозда от стопы к основанию ствола: удар и дерево — одно движение.
        if (age < 4.0F) {
            int m = 12;
            Vec3 from = c.origin.add(c.forward.scale(0.3D)).add(0.0D, 0.04D, 0.0D);
            Vec3[] gp = new Vec3[m + 1];
            double[] gw = new double[m + 1];
            float[] ga = new float[m + 1];
            for (int i = 0; i <= m; i++) {
                double u = i / (double) m;
                gp[i] = from.lerp(base.add(0.0D, 0.02D, 0.0D), u);
                gw[i] = 0.05D + 0.1D * u * u;
                ga[i] = (float) (0.85D * Mth.clamp(1.0D - (age - 1.5D * u) / 2.5D, 0.0D, 1.0D) * Mth.clamp(age / 0.3D, 0.0D, 1.0D));
            }
            stripVar(v, pose, camera, gp, gw, ga, pink ? PINK : COLD);
            stripVar(v, pose, camera, gp, scale(gw, 0.35D), ga, EDGE);
        }
        // Вспышка у основания ствола: короткие лучи вверх и наружу.
        if (age < 1.5F) {
            float fa = (float) curve(age, 0.0, 1.0, 1.5, 0.0);
            for (int i = 0; i < 9; i++) {
                double ang = Math.PI * (0.08D + 0.84D * i / 8.0D);
                Vec3 dir = c.right.scale(Math.cos(ang)).add(0.0D, Math.sin(ang), 0.0D);
                double len = (i % 2 == 0 ? 1.1D : 0.7D) * (0.6D + 0.4D * Math.min(1.0D, age));
                Vec3[] rp = {base.add(0.0D, 0.1D, 0.0D), base.add(0.0D, 0.1D, 0.0D).add(dir.scale(len * 0.5D)),
                        base.add(0.0D, 0.1D, 0.0D).add(dir.scale(len))};
                strip(v, pose, camera, rp, new double[] {0.07D, 0.04D, 0.0D}, 0.9F * fa, EDGE);
            }
        }
        // Холодная дуга у опорной стопы (ref1, whirl1), гаснет к +3.
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

    /** Одна часть дерева: от основания к острию за {@code draw} тиков, сужается к концу. */
    private static void branch(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float age, Branch b, boolean pink) {
        float t = age - b.born();
        if (t < 0.0F) {
            return;
        }
        double shown = Mth.clamp(t / b.draw(), 0.0D, 1.0D);
        shown = 1.0D - (1.0D - shown) * (1.0D - shown);
        int n = b.depth() == 0 ? 16 : 10;
        int m = (int) Math.ceil(n * shown);
        if (m < 1) {
            return;
        }
        Vec3[] p = new Vec3[m + 1];
        double[] w = new double[m + 1];
        float[] a = new float[m + 1];
        for (int i = 0; i <= m; i++) {
            double u = Math.min(shown, i / (double) n);
            p[i] = fall(c, bezier(b.start(), b.ctrl(), b.end(), u), age);
            // Открытый (растущий) конец тоже острый.
            double head = Mth.clamp((shown - u) / 0.12D, 0.0D, 1.0D);
            w[i] = b.width() * Math.pow(1.0D - u, 0.9D) * (shown >= 1.0D ? 1.0D : head);
            // Держится до падения, после удара о землю гаснет за ~4 тика.
            a[i] = (float) Mth.clamp((PlumRules.FALL_TICK + 9.0D + 2.0D * u - age) / 3.0D, 0.0D, 1.0D);
        }
        if (pink) {
            // Широкое бледное свечение каждой ветви сливается в общую розовую крону (ref8).
            stripVar(v, pose, camera, p, scale(w, 5.0D), scaled(a, 0.11F), PINK);
        }
        stripVar(v, pose, camera, p, w, scaled(a, pink ? 0.6F : 0.45F), pink ? PINK : COLD);
        stripVar(v, pose, camera, p, scale(w, 0.45D), scaled(a, 0.95F), EDGE);
    }

    /**
     * Вихрь у стоп (whirl5 со спины): холодная лента на 1,25 оборота вокруг опоры, раскрывается
     * с ⌀ ~2,2 до ~3,2 блока за 5 тиков и крутится; на 4-м слое и выше — второй, бледнее, со
     * сдвигом фазы. Гаснет к 10-му тику.
     */
    private static void gusts(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float age) {
        if (age > 10.0F) {
            return;
        }
        Vec3 centre = c.origin.add(c.forward.scale(0.3D)).add(0.0D, 0.08D, 0.0D);
        int swirls = c.layer >= 4 ? 2 : 1;
        for (int k = 0; k < swirls; k++) {
            double open = Mth.clamp(age / 5.0D, 0.0D, 1.0D);
            double radius = 1.1D + 0.5D * (1.0D - (1.0D - open) * (1.0D - open)) + 0.25D * k;
            float alpha = (float) curve(age, 0.0, 0.0, 0.8, 0.8, 5.0, 0.6, 10.0, 0.0) * (k == 0 ? 1.0F : 0.55F);
            int m = 28;
            Vec3[] p = new Vec3[m + 1];
            double[] w = new double[m + 1];
            for (int i = 0; i <= m; i++) {
                double u = i / (double) m;
                double ang = Math.PI * 2.0D * 1.25D * u + age * 0.35D + k * Math.PI;
                // Хвост ближе к стопам и ниже, голова раскрыта и чуть выше.
                double rad = radius * (0.7D + 0.3D * u);
                p[i] = centre.add(c.forward.scale(Math.cos(ang) * rad)).add(c.right.scale(Math.sin(ang) * rad))
                        .add(0.0D, 0.05D + 0.35D * u, 0.0D);
                w[i] = (0.07D + 0.05D * u) * Math.sin(Math.PI * Math.min(1.0D, u * 1.15D + 0.03D));
            }
            strip(v, pose, camera, p, w, 0.35F * alpha, COLD);
            strip(v, pose, camera, p, scale(w, 0.35D), 0.85F * alpha, EDGE);
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
