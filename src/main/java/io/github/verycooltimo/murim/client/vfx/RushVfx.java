package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.CameraShakeHandler;
import io.github.verycooltimo.murim.client.ClientAuraState;
import io.github.verycooltimo.murim.network.RushPayload;
import io.github.verycooltimo.murim.network.TechniqueEventPayload;
import io.github.verycooltimo.murim.technique.RushRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
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

import static io.github.verycooltimo.murim.client.vfx.PlumVfx.EDGE;
import static io.github.verycooltimo.murim.client.vfx.PlumVfx.PINK;

/**
 * Натиск Цветущей Сливы (docs/design/techniques/seven-plum-rush-spec.md, рефы rush/u1–u11):
 * концентрация → один большой взмах → из следа рождается горизонтальный розовый ураган и
 * летит вперёд до 16 блоков → заворачивает цель локальным вихрем (три удара) → пауза → мастер
 * мчится к ней и бьёт прямым уколом, останавливаясь чуть за ней. Синее на рефах — противник,
 * у нас только розовое.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class RushVfx {

    private static final ResourceLocation TECHNIQUE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "seven_plum_rush");
    private static final VfxColour CORE = new VfxColour(1.0F, 0xF9 / 255.0F, 0xFC / 255.0F);
    private static final VfxColour BODY = new VfxColour(1.0F, 0xC4 / 255.0F, 0xDF / 255.0F);
    private static final VfxColour RIM = new VfxColour(1.0F, 0x65 / 255.0F, 0xAC / 255.0F);
    private static final int WINDUP = 40;

    private static final List<Cast> CASTS = new ArrayList<>();
    private static int clientTicks;

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
        int release = -1;
        Vec3 origin;
        Vec3 f;
        Vec3 side;
        /** Горизонтальная проекция оси и «вверх» поперёк наклонной оси урагана. */
        Vec3 fFlat = new Vec3(0.0D, 0.0D, 1.0D);
        Vec3 up = new Vec3(0.0D, 1.0D, 0.0D);
        int wrapTick = -1;
        Vec3 wrapAt;
        int hitTick = -1;
        Vec3 hitAt;
        final List<Mote> petals = new ArrayList<>();
        final List<double[]> dust = new ArrayList<>();
        final List<Vec3> dash = new ArrayList<>();
        final List<Vec3> blade = new ArrayList<>();
        final double phase;

        Cast(int entityId, int layer) {
            this.entityId = entityId;
            this.layer = layer;
            this.start = clientTicks;
            this.random = new Random(entityId * 6151L + clientTicks);
            this.phase = random.nextDouble() * Math.PI * 2.0D;
        }

        int since() {
            return release < 0 ? -1 : clientTicks - release;
        }

        int wrapAge() {
            return wrapTick < 0 ? -1 : clientTicks - wrapTick;
        }
    }

    public static void onTechniqueEvent(TechniqueEventPayload payload) {
        if (payload.event() != TechniqueEventPayload.Event.STARTED || !TECHNIQUE.equals(payload.techniqueId())
                || payload.layer() <= 0) {
            return;
        }
        Cast c = new Cast(payload.sourceId(), payload.layer());
        CASTS.add(c);
        if (c.layer >= 3) {
            // Только розовая аура: синее на рефах — техника противника.
            ClientAuraState.techniqueAura(c.entityId, 2 + Math.min(2, c.layer / 3), 1, WINDUP + 4);
        }
    }

    public static void onRush(RushPayload p) {
        Cast c = null;
        for (Cast x : CASTS) {
            if (x.entityId == p.entityId()) {
                c = x;
            }
        }
        if (c == null) {
            c = new Cast(p.entityId(), p.layer());
            CASTS.add(c);
        }
        Minecraft mc = Minecraft.getInstance();
        boolean own = mc.player != null && mc.player.getId() == c.entityId;
        if (p.stage() == 0) {
            c.release = clientTicks;
            c.origin = p.origin();
            Vec3 fv = Vec3.directionFromRotation(0.0F, p.yaw());
            Vec3 d3 = p.centre().subtract(p.origin());
            // 03.10: ураган в любом направлении — ось наклонена к цели (вверх или вниз).
            c.f = d3.lengthSqr() > 0.25D ? d3.normalize() : new Vec3(fv.x, 0.0D, fv.z).normalize();
            Vec3 flat = new Vec3(c.f.x, 0.0D, c.f.z);
            c.fFlat = flat.lengthSqr() < 1.0E-6D ? new Vec3(fv.x, 0.0D, fv.z).normalize() : flat.normalize();
            c.side = new Vec3(-c.fFlat.z, 0.0D, c.fFlat.x);
            c.up = c.side.cross(c.f).normalize();
            if (own) {
                SpeedLines.directional(0.0F, 0.5F, 6, SpeedLines.WHITE);
            }
            dust(c, c.origin.add(c.f.scale(0.6D)), 6, 0.16D);
        } else if (p.stage() == 1) {
            c.wrapTick = clientTicks;
            c.wrapAt = p.centre().add(0.0D, 1.1D, 0.0D);
            burst(c, c.wrapAt, 24, 0.25D);
        } else {
            c.hitTick = clientTicks;
            c.hitAt = p.centre();
            if (own) {
                ImpactFrames.trigger(p.centre());
                SpeedLines.radial(0.5F, 0.5F, 1.0F, 7, SpeedLines.WHITE);
            }
            if (mc.player != null && mc.player.position().distanceTo(p.centre()) < 16.0D) {
                CameraShakeHandler.quake(own ? 0.75F : 0.45F, 16);
                mc.player.level().playLocalSound(p.centre().x, p.centre().y, p.centre().z,
                        net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_STRONG, net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.7F, false);
            }
            burst(c, p.centre(), 40, 0.3D);
        }
    }

    private static void burst(Cast c, Vec3 at, int n, double speed) {
        if (c.layer < 3) {
            return;
        }
        for (int i = 0; i < n && c.petals.size() < 400; i++) {
            Vec3 v = new Vec3(c.random.nextGaussian(), c.random.nextDouble(), c.random.nextGaussian()).normalize().scale(speed * (0.4D + c.random.nextDouble()));
            c.petals.add(new Mote(at, v, 30 + c.random.nextInt(20), c.random.nextInt(4), (float) (c.random.nextDouble() - 0.5D),
                    0.08D + 0.05D * c.random.nextDouble()));
        }
    }

    private static void dust(Cast c, Vec3 at, int n, double speed) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.getBlockState(BlockPos.containing(at.add(0.0D, -0.2D, 0.0D))).isAir()) {
            return;
        }
        for (int i = 0; i < n; i++) {
            double a = c.random.nextDouble() * Math.PI * 2.0D;
            c.dust.add(new double[] {at.x, at.y + 0.1D, at.z, Math.cos(a) * speed, Math.sin(a) * speed, 0, c.random.nextInt(16)});
        }
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
            // Концентрация: лепестки тянутся к будущему направлению атаки.
            if (c.release < 0 && age > 12 && age < WINDUP && e != null && c.layer >= 3 && age % 2 == 0) {
                Vec3 look = Vec3.directionFromRotation(0.0F, e.getYRot());
                Vec3 at = e.position().add((c.random.nextDouble() - 0.5D) * 1.2D, 0.4D + c.random.nextDouble() * 1.4D, (c.random.nextDouble() - 0.5D) * 1.2D);
                c.petals.add(new Mote(at, new Vec3(look.x, 0.0D, look.z).scale(0.04D), 30, c.random.nextInt(4), 0.3F, 0.08D));
            }
            if (c.release < 0 && age == 2 && e != null) {
                dust(c, e.position(), 5, 0.12D);
            }
            // След клинка во взмахе (тики 34–40).
            if (c.release < 0 && age >= 34 && e != null) {
                double k = (age - 34) / 6.0D;
                double a = Math.toRadians(-60.0D + 150.0D * k);
                Vec3 look = Vec3.directionFromRotation(0.0F, e.getYRot());
                Vec3 f = new Vec3(look.x, 0.0D, look.z).normalize();
                Vec3 s = new Vec3(-f.z, 0.0D, f.x);
                c.blade.add(e.position().add(f.scale(0.9D + 0.6D * Math.cos(a))).add(s.scale(1.4D * Math.sin(a))).add(0.0D, 1.8D - 1.0D * k, 0.0D));
            }
            int s = c.since();
            if (s >= 0 && c.wrapTick < 0 && s % 2 == 0) {
                // Нижние витки задевают землю — пыль по ходу урагана.
                Vec3 head = c.origin.add(c.f.scale(RushRules.head(s)));
                dust(c, head.add(c.side.scale((c.random.nextDouble() - 0.5D) * 3.0D)), 2, 0.14D);
                if (c.layer >= 3) {
                    for (int i = 0; i < 4; i++) {
                        Vec3 p = point(c, RushRules.head(s) - c.random.nextDouble() * RushRules.length(c.layer), s, c.random.nextInt(3), c.random.nextDouble());
                        Vec3 axisP = RushRules.axis(c.origin, c.f, RushRules.head(s) - 1.0D);
                        Vec3 rad = p.subtract(axisP);
                        Vec3 tang = c.f.cross(rad).normalize().scale(0.12D);
                        c.petals.add(new Mote(p, c.f.scale(0.35D).add(tang).add(c.random.nextGaussian() * 0.03D, 0.0D, c.random.nextGaussian() * 0.03D),
                                26, c.random.nextInt(4), (float) (c.random.nextDouble() - 0.5D), 0.09D));
                    }
                }
            }
            int w = c.wrapAge();
            if (w >= 0) {
                if (w == RushRules.PULSES[1] || w == RushRules.PULSES[2]) {
                    burst(c, c.wrapAt, 14, 0.22D);
                }
                if (w == RushRules.DASH && mc.player != null && mc.player.getId() == c.entityId && c.layer >= 3) {
                    TechniqueCaption.show(Component.translatable("technique.murim.seven_plum_blossoms.school"),
                            Component.translatable("technique.murim.seven_plum_blossoms.rush"), 30);
                    SpeedLines.radial(0.5F, 0.5F, 0.8F, 10, SpeedLines.WHITE);
                }
                if (w >= RushRules.DASH && w <= RushRules.DASH + RushRules.DASH_TICKS + 2 && e != null) {
                    c.dash.add(e.position().add(0.0D, 1.0D, 0.0D));
                    if (w == RushRules.DASH || w == RushRules.DASH + RushRules.DASH_TICKS) {
                        dust(c, e.position(), 6, 0.18D);
                    }
                }
            }
            if (c.hitTick >= 0 && clientTicks - c.hitTick == 3) {
                smoke(c, c.hitAt);
            }
            for (Mote m : c.petals) {
                m.prev = m.pos;
                m.age++;
                m.vel = new Vec3(m.vel.x * 0.93D, m.vel.y * 0.93D - 0.0025D, m.vel.z * 0.93D);
                m.pos = m.pos.add(m.vel);
            }
            c.petals.removeIf(m -> m.age >= m.life);
            for (double[] d : c.dust) {
                d[0] += d[3];
                d[2] += d[4];
                d[3] *= 0.87D;
                d[4] *= 0.87D;
                d[5] += 1.0D;
            }
            c.dust.removeIf(d -> d[5] > (d.length > 7 ? d[7] : 18.0D));
            if (age > 300 || (w > RushRules.END + 40) || (c.wrapTick < 0 && s > 60)) {
                it.remove();
            }
        }
    }

    /** Вал дыма у точки укола (через 3 тика после контакта). */
    private static void smoke(Cast c, Vec3 at) {
        Vec3 ground = new Vec3(at.x, c.origin == null ? at.y - 1.0D : c.origin.y, at.z);
        for (int i = 0; i < 10; i++) {
            double a = Math.PI * 2.0D * i / 10.0D;
            double size = 0.5D + 0.9D * c.random.nextDouble();
            c.dust.add(new double[] {ground.x + Math.cos(a) * 0.8D, ground.y + size * 0.3D, ground.z + Math.sin(a) * 0.8D,
                    Math.cos(a) * 0.18D, Math.sin(a) * 0.18D, 0, c.random.nextInt(16), 34, size});
        }
    }

    /**
     * Точка спиральной ленты урагана: {@code d} — расстояние по оси от старта, {@code strand} —
     * лента (0 — главная широкая, 1–2 — вспомогательные), {@code u} — доля по сечению (для лепестков).
     */
    private static Vec3 point(Cast c, double d, float s, int strand, double u) {
        return point(c, d, s, strand, u, 1.0D);
    }

    /**
     * Точка спиральной ленты: шаг длинный (4–5 блоков на оборот), радиус раскрывается к голове
     * ({@code open} 0…1 — доля от хвоста), у каждой ленты своя фаза и шаг (codex 02.10: не кольца).
     */
    private static Vec3 point(Cast c, double d, float s, int strand, double u, double open) {
        double r = RushRules.radius(c.layer) * (strand == 0 ? 1.0D : strand == 1 ? 0.8D : strand == 2 ? 0.62D : 0.9D)
                * (0.3D + 0.7D * open) * (0.85D + 0.15D * Math.sin(d * 1.1D + c.phase + strand * 1.7D));
        double pitch = strand == 0 ? 4.6D : strand == 1 ? 3.9D : strand == 2 ? 5.4D : 3.4D;
        double ph = d / pitch * Math.PI * 2.0D - s * (Math.PI * 2.0D / 16.0D) + strand * 2.1D + u * 0.6D;
        Vec3 axis = RushRules.axis(c.origin, c.f, d).add(c.side.scale(0.2D * Math.sin(d * 0.7D + s * 0.2D)));
        return axis.add(c.side.scale(Math.cos(ph) * r)).add(c.up.scale(Math.sin(ph) * r * 0.85D));
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
            PoseStack.Pose pose = ps.last();
            for (Cast c : CASTS) {
                VertexConsumer air = buffers.getBuffer(MurimRenderTypes.airBand());
                bladeTrail(c, pose, camera, air, partial);
                if (c.release >= 0) {
                    float s = clientTicks - c.release + partial;
                    tornado(c, pose, camera, air, s);
                    wrap(c, pose, camera, air, partial);
                    dashTrail(c, pose, camera, air, partial);
                }
                buffers.endBatch(MurimRenderTypes.airBand());
                if (!c.dust.isEmpty()) {
                    RenderType dt = MurimRenderTypes.dustPuffs();
                    VertexConsumer dv = buffers.getBuffer(dt);
                    for (double[] d : c.dust) {
                        if (d.length > 7) {
                            continue;
                        }
                        float pt = (float) (d[5] / 18.0D);
                        PlumVfx.puff(dv, pose, camera, new Vec3(d[0], d[1], d[2]), 0.3D * (0.7D + 0.8D * pt), (int) d[6],
                                pt < 0.5F ? 1.0F : Mth.clamp(1.0F - (pt - 0.5F) / 0.5F, 0.0F, 1.0F), 0.68F);
                    }
                    buffers.endBatch(dt);
                    RenderType st = MurimRenderTypes.smokeCel();
                    VertexConsumer sv = buffers.getBuffer(st);
                    for (double[] d : c.dust) {
                        if (d.length <= 7) {
                            continue;
                        }
                        float pt = (float) (d[5] / d[7]);
                        float a = Mth.clamp(pt / 0.06F, 0.0F, 1.0F) * (pt < 0.75F ? 1.0F : Mth.clamp(1.0F - (pt - 0.75F) / 0.25F, 0.0F, 1.0F));
                        PlumVfx.smokePuff(sv, pose, camera, new Vec3(d[0], d[1], d[2]), d[8] * (0.6D + 0.8D * Math.sqrt(pt)), (int) d[6], a, 0.82F,
                                (float) d[6]);
                    }
                    buffers.endBatch(st);
                }
                if (c.layer >= 3 && !c.petals.isEmpty()) {
                    RenderType pt = MurimRenderTypes.plumPetals();
                    VertexConsumer pc = buffers.getBuffer(pt);
                    for (Mote m : c.petals) {
                        if (m.pos.distanceToSqr(camera) < 1.6D) {
                            continue;
                        }
                        float a = Mth.clamp((m.life - m.age - partial) / 10.0F, 0.0F, 1.0F);
                        PlumVfx.petal(pc, pose, camera, m.prev.lerp(m.pos, partial), m.size * 1.6D, m.cell, (m.age + partial) * m.spin, a,
                                1.0F, 0.75F, 0.8F);
                    }
                    buffers.endBatch(pt);
                    RenderType gt = MurimRenderTypes.mote();
                    VertexConsumer g = buffers.getBuffer(gt);
                    for (Mote m : c.petals) {
                        if (m.pos.distanceToSqr(camera) > 1.6D) {
                            float a = Mth.clamp((m.life - m.age - partial) / 10.0F, 0.0F, 1.0F);
                            PlumVfx.glow(g, pose, camera, m.prev.lerp(m.pos, partial), m.size * 2.0D, 0.35F * a, PINK);
                        }
                    }
                    buffers.endBatch(gt);
                }
            }
        } finally {
            ps.popPose();
        }
    }

    /** След клинка во взмахе: из него рождается ураган. */
    private static void bladeTrail(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        if (c.blade.size() < 2) {
            return;
        }
        float age = c.release < 0 ? 0.0F : clientTicks - c.release + partial;
        float a = (float) Mth.clamp(1.0D - age / 8.0D, 0.0D, 1.0D);
        if (a <= 0.0F) {
            return;
        }
        Vec3[] p = c.blade.toArray(new Vec3[0]);
        double[] w = new double[p.length];
        for (int i = 0; i < p.length; i++) {
            w[i] = 0.32D * Math.sin(Math.PI * (i + 0.5D) / p.length);
        }
        PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 2.2D), 0.15F * a, RIM);
        PlumVfx.strip(v, pose, camera, p, w, 0.55F * a, BODY);
        PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 0.3D), 0.95F * a, CORE);
    }

    /**
     * Летящий горизонтальный ураган: одна главная широкая лента и две вспомогательные с разным
     * шагом и запаздыванием, прозрачный центр; раскрывается из взмаха за 12 тиков. После
     * обволакивания за 4 тика уходит в локальный вихрь.
     */
    private static void tornado(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float s) {
        double head = c.wrapTick >= 0 ? RushRules.head(c.wrapTick - c.release) : RushRules.head(s);
        double len = RushRules.length(c.layer) * Mth.clamp(s / 12.0D, 0.15D, 1.0D);
        // Поток не схлопывается: ещё 8 тиков голова стоит на цели, хвост втекает в неё.
        if (c.wrapTick >= 0) {
            double flow = Mth.clamp((clientTicks - c.wrapTick) / 8.0D, 0.0D, 1.0D);
            len = len * (1.0D - 0.8D * flow);
        }
        float fade = c.wrapTick >= 0 ? (float) Mth.clamp(1.0D - (clientTicks - c.wrapTick - 4) / 6.0D, 0.0D, 1.0D)
                : (float) Mth.clamp((RushRules.RANGE - head + 2.0D) / 3.0D, 0.0D, 1.0D);
        if (fade <= 0.0F) {
            return;
        }
        // Тело урагана: полупрозрачная масса вдоль оси, чтобы витки читались одним потоком.
        {
            int n = 24;
            double from = Math.max(0.0D, head - len);
            Vec3[] p = new Vec3[n + 1];
            double[] w = new double[n + 1];
            for (int i = 0; i <= n; i++) {
                double u = i / (double) n;
                double d = from + (head - from) * u;
                p[i] = RushRules.axis(c.origin, c.f, d);
                w[i] = RushRules.radius(c.layer) * 0.95D * Math.sin(Math.PI * Math.min(1.0D, u * 1.05D + 0.02D));
            }
            PlumVfx.strip(v, pose, camera, p, w, 0.16F * fade, BODY);
            PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 0.4D), 0.12F * fade, CORE);
        }
        // Рваные короткие штрихи между витками: тело потока (а не полые обручи).
        for (int k = 0; k < 10; k++) {
            double d0 = Math.max(0.0D, head - len) + len * ((k * 0.137D + c.phase * 0.1D) % 1.0D);
            double u0 = (d0 - (head - len)) / Math.max(0.1D, len);
            Vec3[] q = new Vec3[5];
            double[] qw = new double[5];
            for (int i = 0; i < 5; i++) {
                q[i] = point(c, d0 + i * 0.35D, s, k % 4, 0.3D * k, Math.min(1.0D, u0 + i * 0.05D));
                qw[i] = 0.18D * Math.sin(Math.PI * (i + 0.5D) / 5.0D) * (0.4D + 0.6D * u0);
            }
            PlumVfx.strip(v, pose, camera, q, qw, 0.35F * fade, BODY);
            PlumVfx.strip(v, pose, camera, q, PlumVfx.scale(qw, 0.3D), 0.7F * fade, CORE);
        }
        int strands = c.layer >= 5 ? 4 : c.layer >= 4 ? 3 : 2;
        for (int k = 0; k < strands; k++) {
            double lag = k == 0 ? 0.0D : k == 1 ? 0.8D : k == 2 ? 1.6D : 0.4D;
            double from = Math.max(0.0D, head - len + lag * 0.5D);
            double to = head - lag;
            if (to <= from) {
                continue;
            }
            int n = 48;
            Vec3[] p = new Vec3[n + 1];
            double[] w = new double[n + 1];
            for (int i = 0; i <= n; i++) {
                double u = i / (double) n;
                double d = from + (to - from) * u;
                p[i] = point(c, d, s, k, 0.0D, u);
                // Широкие ленты: ширина растёт к голове, хвост острый.
                double base = k == 0 ? 1.1D : k == 1 ? 0.7D : k == 2 ? 0.5D : 0.42D;
                w[i] = base * (0.35D + 0.65D * u) * (0.8D + 0.2D * Math.sin(d * 2.0D + s * 0.3D))
                        * Math.sin(Math.PI * Math.min(1.0D, u * 1.03D + 0.02D));
            }
            PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 1.8D), 0.1F * fade, RIM);
            PlumVfx.strip(v, pose, camera, p, w, 0.4F * fade, BODY);
            PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 0.25D), 0.9F * fade, CORE);
        }
    }

    /**
     * Локальный вихрь (u7): ленты проходят перед телом и за ним на трёх высотах, цель внутри
     * потока; на каждом ударе — сгущение, режущая черта и вспышка в точке контакта.
     */
    private static void wrap(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        if (c.wrapTick < 0) {
            return;
        }
        float t = clientTicks - c.wrapTick + partial;
        if (t > RushRules.WRAP + 8) {
            return;
        }
        float grow = (float) Mth.clamp(t / 5.0D, 0.0D, 1.0D);
        float out = (float) Mth.clamp((RushRules.WRAP + 8 - t) / 8.0D, 0.0D, 1.0D);
        double squeeze = 1.0D;
        for (int p : RushRules.PULSES) {
            squeeze -= 0.3D * Math.max(0.0D, 1.0D - Math.abs(t - p) / 2.0D);
        }
        double spread = t > RushRules.WRAP ? 1.0D + (t - RushRules.WRAP) * 0.2D : 1.0D;
        Vec3 feet = c.wrapAt.subtract(0.0D, 1.1D, 0.0D);
        double[] heights = {0.35D, 1.05D, 1.75D, 0.7D};
        int arcs = c.layer >= 4 ? 4 : 3;
        for (int k = 0; k < arcs; k++) {
            int n = 34;
            Vec3[] p = new Vec3[n + 1];
            double[] w = new double[n + 1];
            double tilt = Math.toRadians(k == 0 ? 14.0D : k == 1 ? -10.0D : k == 2 ? 22.0D : -26.0D);
            double r = (1.25D + 0.15D * (k % 2)) * squeeze * spread * grow;
            for (int i = 0; i <= n; i++) {
                double u = i / (double) n;
                double a = u * Math.PI * 1.75D + t * 0.5D * (k % 2 == 0 ? 1 : -1) + k * 1.9D;
                Vec3 ring = c.side.scale(Math.cos(a) * r).add(c.fFlat.scale(Math.sin(a) * r));
                p[i] = feet.add(0.0D, heights[k] + Math.sin(a) * r * Math.sin(tilt), 0.0D).add(ring);
                w[i] = (k == 0 ? 0.5D : 0.32D) * Math.sin(Math.PI * u);
            }
            PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 1.8D), 0.12F * out, RIM);
            PlumVfx.strip(v, pose, camera, p, w, 0.48F * out, BODY);
            PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 0.25D), 0.9F * out, CORE);
        }
        // Режущие черты ударов: короткая яркая линия через корпус, каждый раз под новым углом.
        for (int j = 0; j < RushRules.PULSES.length; j++) {
            float pt = t - RushRules.PULSES[j];
            if (pt < 0.0F || pt > 5.0F) {
                continue;
            }
            double ang = Math.toRadians(new double[] {-35.0D, 40.0D, -5.0D}[j]);
            Vec3 axis = c.side.scale(Math.cos(ang)).add(0.0D, Math.sin(ang), 0.0D);
            float a = (float) PlumVfx.curve(pt, 0.0, 1.0, 5.0, 0.0);
            Vec3[] q = {c.wrapAt.subtract(axis.scale(1.3D)), c.wrapAt, c.wrapAt.add(axis.scale(1.3D))};
            double[] qw = {0.0D, 0.14D, 0.0D};
            PlumVfx.strip(v, pose, camera, q, PlumVfx.scale(qw, 2.5D), 0.25F * a, RIM);
            PlumVfx.strip(v, pose, camera, q, qw, 0.95F * a, CORE);
        }
    }

    /** Длинная прозрачная лента рывка и короткий укол клинка. */
    private static void dashTrail(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        if (c.dash.size() < 2 || c.wrapTick < 0) {
            return;
        }
        float t = clientTicks - c.wrapTick + partial;
        float a = (float) Mth.clamp((RushRules.DASH + RushRules.DASH_TICKS + 16 - t) / 10.0D, 0.0D, 1.0D);
        Vec3[] p = c.dash.toArray(new Vec3[0]);
        double[] w = new double[p.length];
        for (int i = 0; i < p.length; i++) {
            w[i] = 0.16D * (i + 1.0D) / p.length;
        }
        PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 2.0D), 0.12F * a, RIM);
        PlumVfx.strip(v, pose, camera, p, w, 0.35F * a, BODY);
        PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 0.2D), 0.85F * a, EDGE);
        // Прямой укол: узкий бело-розовый след вперёд от конца пути.
        float th = t - RushRules.THRUST;
        // Всплеск на контакте: клинья вперёд по ходу укола.
        if (c.hitTick >= 0 && c.hitAt != null) {
            float ht = clientTicks - c.hitTick + partial;
            if (ht < 6.0F) {
                Vec3 dir = c.dash.get(c.dash.size() - 1).subtract(c.dash.get(0));
                dir = new Vec3(dir.x, 0.0D, dir.z).normalize();
                Vec3 sideV = new Vec3(-dir.z, 0.0D, dir.x);
                float ha = (float) PlumVfx.curve(ht, 0.0, 1.0, 6.0, 0.0);
                for (int k = -3; k <= 3; k++) {
                    Vec3 d2 = dir.add(sideV.scale(k * 0.18D)).add(0.0D, (k % 2) * 0.12D, 0.0D).normalize();
                    double len = (2.4D - Math.abs(k) * 0.3D) * (0.6D + 0.4D * Math.min(1.0D, ht / 2.0D));
                    Vec3[] q = {c.hitAt, c.hitAt.add(d2.scale(len * 0.5D)), c.hitAt.add(d2.scale(len))};
                    double[] qw = {0.12D, 0.07D, 0.0D};
                    PlumVfx.strip(v, pose, camera, q, qw, 0.85F * ha, k == 0 ? CORE : BODY);
                }
            }
        }
        if (th > -2.0F && th < 6.0F) {
            Vec3 end = p[p.length - 1];
            Vec3 dir = end.subtract(p[0]);
            dir = new Vec3(dir.x, 0.0D, dir.z).normalize();
            float ta = (float) PlumVfx.curve(th, -2.0, 0.0, 0.0, 1.0, 6.0, 0.0);
            Vec3[] q = {end.subtract(dir.scale(1.5D)), end, end.add(dir.scale(2.2D))};
            double[] qw = {0.02D, 0.12D, 0.0D};
            PlumVfx.strip(v, pose, camera, q, PlumVfx.scale(qw, 2.5D), 0.2F * ta, RIM);
            PlumVfx.strip(v, pose, camera, q, qw, 0.95F * ta, CORE);
        }
    }

    private RushVfx() {
    }
}
