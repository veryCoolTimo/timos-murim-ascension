package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.CameraShakeHandler;
import io.github.verycooltimo.murim.client.ClientAuraState;
import io.github.verycooltimo.murim.network.ExecPayload;
import io.github.verycooltimo.murim.network.TechniqueEventPayload;
import io.github.verycooltimo.murim.technique.ExecRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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

import static io.github.verycooltimo.murim.client.vfx.PlumVfx.COLD;
import static io.github.verycooltimo.murim.client.vfx.PlumVfx.EDGE;
import static io.github.verycooltimo.murim.client.vfx.PlumVfx.PINK;

/**
 * Казнь Цветущей Сливы (docs/design/techniques/seven-plum-execution-spec.md, рефы execution/e1–e8):
 * медленные проводки меча с двоящимся телом → шесть клонов выходят в шесть сторон, заходят с
 * разных сторон, бьют по разу, пролетают сквозь цель и рассыпаются лепестками → оригинал рывком
 * за спиной → шесть разрезов разом. Клоны — клиентские копии модели игрока (как остаточный
 * образ шага), без сущностей; урон и тайминги — на сервере (ExecRules).
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class ExecVfx {

    private static final ResourceLocation TECHNIQUE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "seven_plum_execution");
    /** Подготовка: проводки меча с тика 12 по 42 техники, кадр-образ каждые 5 тиков. */
    private static final int PREP_FROM = 12;
    private static final int PREP_TO = 42;

    private static final List<Cast> CASTS = new ArrayList<>();
    private static int clientTicks;

    private record Ghost(Vec3 pos, float yaw, int born, PartPose[] pose) {
    }

    private static final class Mote {
        Vec3 pos;
        Vec3 prev;
        Vec3 vel;
        int age;
        final int life;
        final int cell;
        final float spin;
        final double size;

        Mote(Vec3 pos, Vec3 vel, int life, int cell, float spin, double size) {
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.life = life;
            this.cell = cell;
            this.spin = spin;
            this.size = size;
        }
    }

    private static final class Cast {
        final int entityId;
        final int layer;
        final int start;
        final Random random;
        int exitTick = -1;
        Vec3 origin;
        Vec3 centre;
        double base;
        ResourceLocation skin;
        PartPose[] basePose;
        final List<Ghost> ghosts = new ArrayList<>();
        final List<Mote> petals = new ArrayList<>();
        /** Серая пыль из-под ног копий: {x, y, z, vx, vz, возраст, клетка атласа}. */
        final List<double[]> dust = new ArrayList<>();
        final List<List<Vec3>> trails = new ArrayList<>();
        final List<Vec3> dash = new ArrayList<>();
        int finalHit = -1;
        int targetId = -1;
        Vec3 hitAt;

        Cast(int entityId, int layer) {
            this.entityId = entityId;
            this.layer = layer;
            this.start = clientTicks;
            this.random = new Random(entityId * 4513L + clientTicks);
            for (int i = 0; i < 6; i++) {
                trails.add(new ArrayList<>());
            }
        }

        int since() {
            return exitTick < 0 ? -1 : clientTicks - exitTick;
        }
    }

    // ------------------------------------------------------------------ события

    public static void onTechniqueEvent(TechniqueEventPayload payload) {
        if (payload.event() != TechniqueEventPayload.Event.STARTED || !TECHNIQUE.equals(payload.techniqueId())
                || payload.layer() <= 0) {
            return;
        }
        Cast c = new Cast(payload.sourceId(), payload.layer());
        CASTS.add(c);
        if (c.layer >= 3) {
            // Слабая аура и гаснет к 42-му тику: перед выходом клонов — настоящая пауза (codex 02.10).
            ClientAuraState.techniqueAura(c.entityId, 2, 0, PREP_TO);
        }
    }

    public static void onExec(ExecPayload p) {
        Cast c = null;
        for (Cast x : CASTS) {
            if (x.entityId == p.entityId()) {
                c = x;
            }
        }
        if (p.stage() == 1) {
            if (c != null) {
                c.finalHit = c.since();
                c.hitAt = p.centre();
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null && mc.player.getId() == c.entityId) {
                    // Надпись — по читаемому попаданию (codex 02.10), не раньше.
                    if (c.layer >= 3) {
                        TechniqueCaption.show(Component.translatable("technique.murim.seven_plum_blossoms.school"),
                                Component.translatable("technique.murim.seven_plum_blossoms.execution"), 34);
                    }
                    ImpactFrames.trigger(p.centre());
                    SpeedLines.radial(0.5F, 0.5F, 1.0F, 7, SpeedLines.WHITE);
                }
                if (mc.player != null && mc.player.position().distanceTo(p.centre()) < 16.0D) {
                    CameraShakeHandler.quake(mc.player.getId() == c.entityId ? 0.6F : 0.4F, 14);
                    mc.player.level().playLocalSound(p.centre().x, p.centre().y, p.centre().z,
                            net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP, net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.7F, false);
                }
                for (int i = 0; i < 40; i++) {
                    petal(c, p.centre(), new Vec3(c.random.nextGaussian(), c.random.nextDouble() * 1.2D, c.random.nextGaussian()).scale(0.22D));
                }
            }
            return;
        }
        if (c == null) {
            c = new Cast(p.entityId(), p.layer());
            CASTS.add(c);
        }
        c.origin = p.origin();
        c.centre = p.centre();
        c.base = p.base();
        c.exitTick = clientTicks;
        c.targetId = findTarget(c.entityId, p.centre());
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getEntity(c.entityId) instanceof AbstractClientPlayer player
                && mc.getEntityRenderDispatcher().getRenderer(player) instanceof PlayerRenderer renderer) {
            c.skin = player.getSkin().texture();
            c.basePose = capture(renderer.getModel());
        }
        if (c.layer >= 3) {
            ClientAuraState.techniqueAura(c.entityId, 2 + Math.min(3, c.layer), 1, ExecRules.END);
        }
        if (mc.player != null && mc.player.getId() == c.entityId) {
            SpeedLines.radial(0.5F, 0.55F, 0.6F, 6, SpeedLines.WHITE);
        }
    }

    private static PartPose[] capture(PlayerModel<?> m) {
        ModelPart[] parts = parts(m);
        PartPose[] pose = new PartPose[parts.length];
        for (int i = 0; i < parts.length; i++) {
            pose[i] = parts[i].storePose();
        }
        return pose;
    }

    private static ModelPart[] parts(PlayerModel<?> m) {
        return new ModelPart[]{m.head, m.hat, m.body, m.jacket, m.rightArm, m.rightSleeve, m.leftArm, m.leftSleeve,
                m.rightLeg, m.rightPants, m.leftLeg, m.leftPants};
    }

    private static void petal(Cast c, Vec3 at, Vec3 vel) {
        if (c.petals.size() < 360) {
            c.petals.add(new Mote(at, vel, 30 + c.random.nextInt(20), c.random.nextInt(4), (float) (c.random.nextDouble() - 0.5D),
                    0.08D + 0.05D * c.random.nextDouble()));
        }
    }


    /** Цель на клиенте — живое существо у точки, присланной сервером (кроме самого мастера). */
    private static int findTarget(int casterId, Vec3 at) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return -1;
        }
        int best = -1;
        double bd = 2.25D;
        for (net.minecraft.world.entity.LivingEntity e : mc.level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                new net.minecraft.world.phys.AABB(at, at).inflate(1.5D, 3.0D, 1.5D))) {
            double d = (e.getX() - at.x) * (e.getX() - at.x) + (e.getZ() - at.z) * (e.getZ() - at.z);
            if (e.getId() != casterId && e.isAlive() && d < bd && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand)) {
                bd = d;
                best = e.getId();
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ тик

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            CASTS.clear();
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        clientTicks++;
        Iterator<Cast> it = CASTS.iterator();
        while (it.hasNext()) {
            Cast c = it.next();
            int age = clientTicks - c.start;
            Entity e = mc.level.getEntity(c.entityId);
            // Подготовка: шесть проводок меча — на каждой тело «двоится» застывшей копией.
            if (c.exitTick < 0 && age >= PREP_FROM && age <= PREP_TO && (age - PREP_FROM) % 5 == 0 && c.layer >= 2
                    && e instanceof AbstractClientPlayer player
                    && mc.getEntityRenderDispatcher().getRenderer(player) instanceof PlayerRenderer renderer) {
                PartPose[] pose = capture(renderer.getModel());
                for (int k = 0; k < 2; k++) {
                    Vec3 jit = new Vec3((c.random.nextDouble() - 0.5D) * 0.14D, 0.0D, (c.random.nextDouble() - 0.5D) * 0.14D);
                    c.ghosts.add(new Ghost(player.position().add(jit), player.getYRot(), clientTicks + k, pose));
                }
                if (c.layer >= 3) {
                    Vec3 hand = player.position().add(0.0D, 1.4D, 0.0D);
                    for (int i = 0; i < 2; i++) {
                        petal(c, hand, new Vec3(c.random.nextGaussian() * 0.05D, 0.03D, c.random.nextGaussian() * 0.05D));
                    }
                }
            }
            c.ghosts.removeIf(g -> clientTicks - g.born() > 9);
            // Клоны летят к живой цели: подошла или отошла — точка сбора за ней.
            if (c.targetId >= 0 && c.centre != null && mc.level.getEntity(c.targetId) instanceof net.minecraft.world.entity.LivingEntity t
                    && t.isAlive()) {
                c.centre = new Vec3(t.getX(), c.centre.y, t.getZ());
            }
            int s = c.since();
            if (s >= 0 && c.centre != null) {
                for (int i = 0; i < ExecRules.clones(c.layer); i++) {
                    Vec3 p = ExecRules.clone(c.origin, c.centre, c.base, i, s);
                    List<Vec3> tr = c.trails.get(i);
                    int ct = ExecRules.contact(i);
                    if (s <= ct + ExecRules.PASS_TICKS) {
                        tr.add(p.add(0.0D, 1.1D, 0.0D));
                        if (tr.size() > 10) {
                            tr.remove(0);
                        }
                    } else if (!tr.isEmpty()) {
                        tr.remove(0);
                    }
                    // Копия из лепестков: на лету с неё постоянно срываются лепестки.
                    if (c.layer >= 3 && s > ExecRules.EXIT && s < ExecRules.FINAL && c.random.nextInt(2) == 0) {
                        petal(c, p.add((c.random.nextDouble() - 0.5D) * 0.5D, 0.4D + 1.2D * c.random.nextDouble(),
                                (c.random.nextDouble() - 0.5D) * 0.5D), new Vec3(c.random.nextGaussian() * 0.02D, 0.01D, c.random.nextGaussian() * 0.02D));
                    }
                    // Пыль из-под ног бегущей копии.
                    if (s > ExecRules.SPREAD && s < ct && s % 3 == i % 3) {
                        dustAt(c, p);
                    }
                    // Распад: лепестки рвутся с тела, наследуя направление полёта.
                    if (s > ExecRules.FINAL && s <= ExecRules.FINAL + ExecRules.DISSOLVE && c.layer >= 3) {
                        Vec3 vel = new Vec3(p.x - c.centre.x, 0.0D, p.z - c.centre.z).normalize().scale(0.12D);
                        for (int k = 0; k < 9; k++) {
                            // Точки тела: корпус вытянут вдоль полёта — разброс вдоль скорости больше.
                            Vec3 fwd = vel.lengthSqr() > 1.0E-6D ? vel.normalize() : Vec3.ZERO;
                            Vec3 body = p.add(fwd.scale((c.random.nextDouble() - 0.5D) * 1.2D))
                                    .add((c.random.nextDouble() - 0.5D) * 0.4D, 0.3D + 1.2D * c.random.nextDouble(), (c.random.nextDouble() - 0.5D) * 0.4D);
                            petal(c, body, vel.add(c.random.nextGaussian() * 0.04D, 0.02D + c.random.nextDouble() * 0.04D,
                                    c.random.nextGaussian() * 0.04D));
                        }
                    }
                }
                if (ExecRules.finale(c.layer) && s >= ExecRules.DASH && s <= ExecRules.DASH + ExecRules.DASH_TICKS && e != null) {
                    c.dash.add(e.position().add(0.0D, 1.1D, 0.0D));
                }

            }
            for (Mote m : c.petals) {
                m.prev = m.pos;
                m.age++;
                double sway = Math.sin(m.age * 0.35D + m.cell) * 0.005D;
                m.vel = new Vec3(m.vel.x * 0.92D + sway, m.vel.y * 0.92D - 0.003D, m.vel.z * 0.92D - sway);
                m.pos = m.pos.add(m.vel);
            }
            c.petals.removeIf(m -> m.age >= m.life);
            for (double[] d : c.dust) {
                d[0] += d[3];
                d[2] += d[4];
                d[3] *= 0.85D;
                d[4] *= 0.85D;
                d[5] += 1.0D;
            }
            c.dust.removeIf(d -> d[5] > 18.0D);
            if (age > 400 || s > ExecRules.END + 60) {
                it.remove();
            }
        }
    }

    private static void dustAt(Cast c, Vec3 p) {
        // Серая пыль от бегущих ног — клубы манхвы, как у шагов.
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.getBlockState(BlockPos.containing(p.add(0.0D, -0.2D, 0.0D))).isAir()) {
            return;
        }
        double a = c.random.nextDouble() * Math.PI * 2.0D;
        c.dust.add(new double[] {p.x, p.y + 0.1D, p.z, Math.cos(a) * 0.08D, Math.sin(a) * 0.08D, 0, c.random.nextInt(16)});
    }

    // ------------------------------------------------------------------ рендер

    @SubscribeEvent
    static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || CASTS.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        PoseStack ps = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        ps.pushPose();
        try {
            ps.translate(-camera.x, -camera.y, -camera.z);
            for (Cast c : CASTS) {
                float s = c.exitTick < 0 ? -1.0F : clientTicks - c.exitTick + partial;
                models(mc, c, ps, buffers, s, partial);
                PoseStack.Pose pose = ps.last();
                VertexConsumer air = buffers.getBuffer(MurimRenderTypes.airBand());
                if (s >= 0.0F && c.centre != null) {
                    trails(c, pose, camera, air, s);
                    slashes(c, pose, camera, air, s);
                    dashTrail(c, pose, camera, air, s);
                    finale(c, pose, camera, air, s);
                }
                buffers.endBatch(MurimRenderTypes.airBand());
                if (!c.dust.isEmpty()) {
                    RenderType dt = MurimRenderTypes.dustPuffs();
                    VertexConsumer dv = buffers.getBuffer(dt);
                    for (double[] d : c.dust) {
                        float pt = (float) (d[5] / 18.0D);
                        PlumVfx.puff(dv, pose, camera, new Vec3(d[0], d[1], d[2]), 0.3D * (0.7D + 0.8D * pt), (int) d[6],
                                pt < 0.5F ? 1.0F : Mth.clamp(1.0F - (pt - 0.5F) / 0.5F, 0.0F, 1.0F), 0.68F);
                    }
                    buffers.endBatch(dt);
                }
                if (c.layer >= 3 && !c.petals.isEmpty()) {
                    RenderType petals = MurimRenderTypes.plumPetals();
                    VertexConsumer pc = buffers.getBuffer(petals);
                    for (Mote m : c.petals) {
                        if (m.pos.distanceToSqr(camera) < 1.6D) {
                            continue;
                        }
                        float a = Mth.clamp((m.life - m.age - partial) / 10.0F, 0.0F, 1.0F);
                        PlumVfx.petal(pc, pose, camera, m.prev.lerp(m.pos, partial), m.size * 1.6D, m.cell, (m.age + partial) * m.spin, a,
                                1.0F, 0.75F, 0.8F);
                    }
                    buffers.endBatch(petals);
                    RenderType glowType = MurimRenderTypes.mote();
                    VertexConsumer g = buffers.getBuffer(glowType);
                    // Внутреннее свечение копий: мягкое розовое пятно в груди.
                    if (s >= 0.0F && c.centre != null) {
                        for (int i = 0; i < ExecRules.clones(c.layer); i++) {
                            float dz = (float) Mth.clamp((s - ExecRules.FINAL) / ExecRules.DISSOLVE, 0.0D, 1.0D);
                            if (dz < 1.0F) {
                                Vec3 cp = ExecRules.clone(c.origin, c.centre, c.base, i, s).add(0.0D, 1.1D, 0.0D);
                                PlumVfx.glow(g, pose, camera, cp, 0.9D, 0.18F * (1.0F - dz), PINK);
                            }
                        }
                    }
                    for (Mote m : c.petals) {
                        if (m.pos.distanceToSqr(camera) > 1.6D) {
                            float a = Mth.clamp((m.life - m.age - partial) / 10.0F, 0.0F, 1.0F);
                            PlumVfx.glow(g, pose, camera, m.prev.lerp(m.pos, partial), m.size * 2.0D, 0.35F * a, PINK);
                        }
                    }
                    buffers.endBatch(glowType);
                }
            }
        } finally {
            ps.popPose();
        }
    }

    /**
     * Остаточные образы подготовки и шесть клонов: модель игрока с позой по фазе (бег, замах,
     * удар, пролёт), полупрозрачная; при распаде гаснет, пока с неё рвутся лепестки.
     */
    private static void models(Minecraft mc, Cast c, PoseStack ps, MultiBufferSource.BufferSource buffers, float s, float partial) {
        if (!(mc.level.getEntity(c.entityId) instanceof AbstractClientPlayer player)) {
            return;
        }
        EntityRenderer<? super AbstractClientPlayer> r = mc.getEntityRenderDispatcher().getRenderer(player);
        if (!(r instanceof PlayerRenderer renderer)) {
            return;
        }
        PlayerModel<AbstractClientPlayer> model = renderer.getModel();
        ModelPart[] parts = parts(model);
        PartPose[] saved = new PartPose[parts.length];
        for (int i = 0; i < parts.length; i++) {
            saved[i] = parts[i].storePose();
        }
        ResourceLocation skin = c.skin != null ? c.skin : player.getSkin().texture();
        RenderType type = RenderType.entityTranslucent(skin);
        try {
            for (Ghost g : c.ghosts) {
                float t = (clientTicks - g.born() + partial) / 9.0F;
                if (t < 0.0F || t > 1.0F) {
                    continue;
                }
                load(parts, g.pose());
                draw(model, ps, buffers.getBuffer(type), g.pos(), g.yaw(), 0.0F, 0.22F * (1.0F - t), 0xC9E7F4);
            }
            if (s >= 0.0F && c.centre != null && c.basePose != null) {
                for (int i = 0; i < ExecRules.clones(c.layer); i++) {
                    int ct = ExecRules.contact(i);
                    // Клоны стоят за спиной жертвы до финала и рассыпаются вместе с шестью разрезами.
                    float dissolve = (float) Mth.clamp((s - ExecRules.FINAL) / ExecRules.DISSOLVE, 0.0D, 1.0D);
                    if (dissolve >= 1.0F) {
                        continue;
                    }
                    Vec3 p = ExecRules.clone(c.origin, c.centre, c.base, i, s);
                    Vec3 next = ExecRules.clone(c.origin, c.centre, c.base, i, s + 0.5D);
                    Vec3 d = new Vec3(next.x - p.x, 0.0D, next.z - p.z);
                    // Приземлившись, клон смотрит дальше по ходу пролёта — спиной к жертве.
                    float yaw = d.lengthSqr() > 1.0E-6D ? (float) Math.toDegrees(Math.atan2(-d.x, d.z))
                            : (float) Math.toDegrees(Math.atan2(-(p.x - c.centre.x), p.z - c.centre.z));
                    float alpha = 0.72F * (1.0F - dissolve) * (float) Mth.clamp(s / 3.0D, 0.0D, 1.0D);
                    // Смазанное движение: три бледные копии позади по тому же маршруту.
                    boolean moving = s < ct + ExecRules.PASS_TICKS + 3;
                    for (int g = 3; g >= 0; g--) {
                        if (g > 0 && !moving) {
                            continue;
                        }
                        float sg = s - g * 1.3F;
                        Vec3 pg = ExecRules.clone(c.origin, c.centre, c.base, i, sg);
                        Vec3 ng = ExecRules.clone(c.origin, c.centre, c.base, i, sg + 0.5D);
                        Vec3 dg = new Vec3(ng.x - pg.x, 0.0D, ng.z - pg.z);
                        float yg = dg.lengthSqr() > 1.0E-6D ? (float) Math.toDegrees(Math.atan2(-dg.x, dg.z)) : yaw;
                        load(parts, c.basePose);
                        float[] lr = clonePose(model, i, sg, ct);
                        float ag = g == 0 ? alpha : alpha * (0.28F - 0.07F * g);
                        draw(model, ps, buffers.getBuffer(type), pg, yg, lr[0], lr[1], ag,
                                g == 0 ? (dissolve > 0.0F ? 0xFFD9EA : 0xFBEFFA) : 0xF3B9D6);
                    }
                }
            }
            buffers.endBatch(type);
        } finally {
            load(parts, saved);
        }
    }

    private static void load(ModelPart[] parts, PartPose[] pose) {
        for (int i = 0; i < parts.length && i < pose.length; i++) {
            parts[i].loadPose(pose[i]);
        }
    }

    /**
     * Ключевые позы клона (автор 02.10: «слишком деревянно»): {тик от контакта, наклон°, крен°,
     * правая рука x/y/z, левая рука x/z, правая нога x, левая нога x, поворот корпуса y}. Между
     * ключами — плавная интерполяция, позы перетекают, а не переключаются.
     */
    private static final float[][] KEYS = {
            {-24.0F, 5.0F, 0.0F, -0.4F, 0.0F, 0.1F, 0.3F, -0.2F, 0.0F, 0.0F, 0.0F},    // выход: собран
            {-22.0F, 25.0F, 0.0F, -0.2F, 0.0F, 0.2F, 0.6F, -0.3F, -0.9F, 0.6F, 0.0F},   // присед перед прыжком
            {-19.0F, 30.0F, 0.0F, -1.2F, -0.2F, 0.3F, 0.8F, -0.5F, -1.4F, -1.2F, 0.2F}, // прыжок: ноги поджаты
            {-12.0F, 45.0F, 10.0F, -1.6F, -0.15F, 0.0F, 0.9F, -0.4F, 0.6F, 1.0F, 0.0F}, // полёт вытянувшись
            {-4.0F, 40.0F, -6.0F, -1.6F, -0.1F, 0.0F, 0.8F, -0.5F, 0.7F, 1.1F, 0.0F},   // разгон к цели
            {-2.0F, 30.0F, 0.0F, -2.9F, 0.0F, 0.45F, 0.3F, -0.2F, -0.3F, 1.0F, -0.4F},  // замах
            {0.0F, 34.0F, 0.0F, -1.6F, 0.6F, 0.2F, 0.6F, -0.3F, -0.4F, 0.9F, 0.2F},     // удар
            {2.0F, 32.0F, 25.0F, -0.4F, 0.8F, 0.9F, 1.0F, -0.6F, -0.2F, 1.0F, 0.6F},    // проход сквозь, разворот
            {5.0F, 20.0F, 10.0F, -0.4F, 0.4F, 0.8F, 0.9F, -0.7F, -1.1F, -0.6F, 0.3F},   // в воздухе за целью
            {8.0F, 18.0F, 0.0F, -0.5F, 0.2F, 0.7F, 0.6F, -0.7F, -0.7F, 0.6F, 0.0F},     // приземление, присед
            {12.0F, 10.0F, 0.0F, -0.5F, 0.2F, 0.7F, 0.6F, -0.7F, -0.6F, 0.5F, 0.0F},    // низкая стойка
    };

    /** Поза клона по тику: плавно между ключами. Возвращает {наклон, крен}. */
    private static float[] clonePose(PlayerModel<?> m, int i, float s, int ct) {
        // Выход и прыжок у всех одновременно (по тику s), разгон и удар — от своего контакта.
        float t = s < ExecRules.SPREAD ? -24.0F + s * 12.0F / ExecRules.SPREAD
                : s < ct ? -12.0F + (s - ExecRules.SPREAD) * 12.0F / Math.max(1.0F, ct - ExecRules.SPREAD) : s - ct;
        float[] a = KEYS[0];
        float[] b = KEYS[KEYS.length - 1];
        float k = 1.0F;
        for (int j = 0; j + 1 < KEYS.length; j++) {
            if (t <= KEYS[j + 1][0]) {
                a = KEYS[j];
                b = KEYS[j + 1];
                k = Mth.clamp((t - a[0]) / Math.max(0.01F, b[0] - a[0]), 0.0F, 1.0F);
                break;
            }
        }
        if (t < KEYS[0][0]) {
            b = a;
            k = 0.0F;
        }
        float e = k * k * (3.0F - 2.0F * k);
        float[] v = new float[a.length];
        for (int j = 1; j < a.length; j++) {
            v[j] = a[j] + (b[j] - a[j]) * e;
        }
        int side = i % 2 == 0 ? 1 : -1;
        // Живость: лёгкое покачивание конечностей, свой ритм у каждой копии.
        float sway = Mth.sin(s * 0.7F + i * 1.3F) * 0.08F;
        m.rightArm.xRot = v[3] + sway;
        m.rightArm.yRot = v[4] * side;
        m.rightArm.zRot = v[5] * side;
        m.leftArm.xRot = v[6] - sway;
        m.leftArm.zRot = v[7];
        m.rightLeg.xRot = v[8] + sway;
        m.leftLeg.xRot = v[9] - sway;
        m.body.yRot = v[10] * side;
        m.head.yRot = -v[10] * side * 0.5F;
        if (i == 5 && t > 2.0F && t < 6.0F) {
            m.rightLeg.xRot = -1.6F;
        }
        m.hat.copyFrom(m.head);
        m.jacket.copyFrom(m.body);
        m.rightSleeve.copyFrom(m.rightArm);
        m.leftSleeve.copyFrom(m.leftArm);
        m.rightPants.copyFrom(m.rightLeg);
        m.leftPants.copyFrom(m.leftLeg);
        return new float[] {v[1], v[2] * side};
    }

    /** Как LivingEntityRenderer: поворот корпуса, отражение осей, масштаб игрока, подъём на 1,501. */
    private static void draw(PlayerModel<?> model, PoseStack ps, VertexConsumer v, Vec3 at, float yaw, float lean, float alpha, int rgb) {
        draw(model, ps, v, at, yaw, lean, 0.0F, alpha, rgb);
    }

    private static void draw(PlayerModel<?> model, PoseStack ps, VertexConsumer v, Vec3 at, float yaw, float lean, float roll, float alpha, int rgb) {
        if (alpha <= 0.01F) {
            return;
        }
        ps.pushPose();
        try {
            // API: reference/minecraft-src/net/minecraft/client/renderer/entity/LivingEntityRenderer.java#render
            ps.translate(at.x, at.y, at.z);
            ps.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));
            // Наклон и крен вокруг середины тела, а не стоп: фигура летит, а не падает.
            ps.translate(0.0F, 0.9F, 0.0F);
            ps.mulPose(Axis.ZP.rotationDegrees(roll));
            ps.mulPose(Axis.XP.rotationDegrees(-lean));
            ps.translate(0.0F, -0.9F, 0.0F);
            ps.scale(-1.0F, -1.0F, 1.0F);
            ps.scale(0.9375F, 0.9375F, 0.9375F);
            ps.translate(0.0F, -1.501F, 0.0F);
            int colour = ((int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F) << 24) | rgb;
            model.renderToBuffer(ps, v, 0x00F000F0, OverlayTexture.NO_OVERLAY, colour);
        } finally {
            ps.popPose();
        }
    }

    /** Холодные следы бега копий на высоте корпуса. */
    private static void trails(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float s) {
        for (List<Vec3> tr : c.trails) {
            if (tr.size() < 3) {
                continue;
            }
            Vec3[] p = tr.toArray(new Vec3[0]);
            double[] w = new double[p.length];
            for (int i = 0; i < p.length; i++) {
                w[i] = 0.18D * i / (p.length - 1.0D);
            }
            PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 2.0D), 0.1F, COLD);
            PlumVfx.strip(v, pose, camera, p, w, 0.32F, COLD);
            PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 0.25D), 0.8F, EDGE);
        }
    }

    /** Разрез каждого клона по цели: узкая лента клинка под своим углом, живёт 8 тиков. */
    private static void slashes(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float s) {
        for (int i = 0; i < ExecRules.clones(c.layer); i++) {
            float t = s - ExecRules.contact(i);
            if (t < 0.0F || t > 8.0F) {
                continue;
            }
            slashLine(c, pose, camera, v, i, (float) PlumVfx.curve(t, 0.0, 1.0, 2.0, 0.9, 8.0, 0.0), Mth.clamp(t / 2.0F, 0.0F, 1.0F), 1.0D);
        }
    }

    private static final double[] TILT = {-40.0D, 40.0D, -5.0D, -75.0D, 35.0D, 60.0D};

    private static void slashLine(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, int i, float alpha, float drawn, double scale) {
        Vec3 dir = ExecRules.sector(c.base, i);
        Vec3 across = new Vec3(-dir.z, 0.0D, dir.x);
        double tilt = Math.toRadians(TILT[i]);
        Vec3 axis = across.scale(Math.cos(tilt)).add(0.0D, Math.sin(tilt), 0.0D);
        Vec3 mid = c.centre.add(0.0D, 1.1D, 0.0D);
        double len = 1.6D * scale;
        int n = 14;
        Vec3[] p = new Vec3[n + 1];
        double[] w = new double[n + 1];
        for (int k = 0; k <= n; k++) {
            double u = (k / (double) n) * drawn;
            p[k] = mid.add(axis.scale(len * (u * 2.0D - 1.0D))).add(dir.scale(-0.25D * Math.sin(Math.PI * u)));
            w[k] = 0.1D * Math.sqrt(scale) * Math.sin(Math.PI * Math.min(1.0D, u / Math.max(0.05D, drawn) * 1.02D + 0.01D));
        }
        PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 3.0D), 0.14F * alpha, PINK);
        PlumVfx.strip(v, pose, camera, p, w, 0.6F * alpha, PINK);
        PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 0.35D), 0.95F * alpha, EDGE);
    }

    /** Широкая прозрачная розовая лента рывка оригинала. */
    private static void dashTrail(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float s) {
        if (c.dash.size() < 2) {
            return;
        }
        float a = (float) Mth.clamp((ExecRules.DASH + ExecRules.DASH_TICKS + 14 - s) / 10.0D, 0.0D, 1.0D);
        Vec3[] p = c.dash.toArray(new Vec3[0]);
        double[] w = new double[p.length];
        for (int i = 0; i < p.length; i++) {
            w[i] = 0.3D * Math.sin(Math.PI * (i + 0.5D) / p.length);
        }
        PlumVfx.strip(v, pose, camera, p, w, 0.22F * a, PINK);
        PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 0.2D), 0.8F * a, EDGE);
    }

    /** Казнь (e8): шесть разрезов разом раскрываются на цели крупнее одиночных. */
    private static void finale(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float s) {
        if (!ExecRules.finale(c.layer)) {
            return;
        }
        float t = s - ExecRules.FINAL;
        if (t < 0.0F || t > 12.0F) {
            return;
        }
        // Короткий яркий пик и быстрое затухание; шесть тонких линий сходятся в корпусе цели.
        float a = (float) PlumVfx.curve(t, 0.0, 1.0, 2.0, 1.0, 8.0, 0.0);
        for (int i = 0; i < 6; i++) {
            slashLine(c, pose, camera, v, i, a, Mth.clamp(t / 1.0F, 0.0F, 1.0F), 2.8D);
        }
    }

    private ExecVfx() {
    }
}
