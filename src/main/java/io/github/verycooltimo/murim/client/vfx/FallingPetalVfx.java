package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.CameraShakeHandler;
import io.github.verycooltimo.murim.client.ClientAuraState;
import io.github.verycooltimo.murim.network.FallingPetalPayload;
import io.github.verycooltimo.murim.network.TechniqueEventPayload;
import io.github.verycooltimo.murim.technique.FallingPetalRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
 * Меч Падающего Цветка (FallingPetalRules, рефы «falling petal sword» ref1–ref7): всё — симуляция.
 * Следы клинка — ленты по настоящей траектории острия в мире (мастер движется — след вытягивается
 * в петли ref5), короткий яркий след возврата, закрученные ленты ветра со скоростью и
 * турбулентностью, серая пыль от опор, белые лепестки, которые срываются с хвостов следов и
 * падают, кружась, и которые подхватывает следующий проход мастера. Кольцо у голеней на
 * развороте (ref4), рваные белые всплески на подтверждённом контакте (ref3, ref6), высокая
 * белая волна-серп финала (ref7). Импакт-кадр, тряска и дым — только на попадании.
 * Слой 0 — без эффектов.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class FallingPetalVfx {

    private static final ResourceLocation TECHNIQUE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "falling_petal_sword");

    static final VfxColour CORE = new VfxColour(0xF5 / 255.0F, 0xF4 / 255.0F, 0xEA / 255.0F);
    static final VfxColour RIM = new VfxColour(0x9E / 255.0F, 0xAA / 255.0F, 0xA9 / 255.0F);
    static final VfxColour SHADOW = new VfxColour(0x45 / 255.0F, 0x4B / 255.0F, 0x4C / 255.0F);
    static final VfxColour WHITE = new VfxColour(1.0F, 1.0F, 1.0F);
    static final VfxColour COLD = new VfxColour(0xDC / 255.0F, 0xEA / 255.0F, 1.0F);
    static final VfxColour WIND = new VfxColour(0xD8 / 255.0F, 0xDE / 255.0F, 0xDC / 255.0F);
    static final VfxColour GREY = new VfxColour(0x8D / 255.0F, 0x93 / 255.0F, 0x89 / 255.0F);
    static final VfxColour EDGE_LIGHT = new VfxColour(0xE8 / 255.0F, 0xE7 / 255.0F, 0xDE / 255.0F);
    static final VfxColour OLD = new VfxColour(0x85 / 255.0F, 0x87 / 255.0F, 0x80 / 255.0F);
    static final VfxColour WARM = new VfxColour(0xC2 / 255.0F, 0xB4 / 255.0F, 0x97 / 255.0F);

    /** Белые лепестки (атлас 2×2, перекраска plum_petals): тон задаёт цвет вершин. */
    private static final RenderType PETALS = RenderType.entityTranslucent(
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/falling_petals.png"));

    private static final List<Cast> CASTS = new ArrayList<>();
    /** Псевдо-удар «клинок несут во входе»: длинный волнистый след рывка (ref5). */
    private static final int CARRY = 5;
    /** Белый изогнутый серп у пола в стойке (ref1). */
    private static final int FLOOR = 6;
    /** Высокая петля позади низкого прохода (ref5): клинок взлетает после удара 4. */
    private static final int LOOP = 7;
    private static int clientTicks;

    /** Точка следа острия: позиция и время рождения (тики техники, дробные). */
    private record Pt(Vec3 p, double born) {
    }

    /** След одного движения клинка. */
    private static final class Trail {
        final List<Pt> pts = new ArrayList<>();
        final boolean ret;
        final int strike;
        final double life;

        Trail(int strike, boolean ret) {
            this.strike = strike;
            this.ret = ret;
            this.life = ret ? 3.0D : strike == CARRY ? 9.0D : strike == FLOOR ? 10.0D : strike == LOOP ? 7.0D : 7.5D;
        }
    }

    /** Лента ветра: частица со скоростью и закруткой, тянущая хвост. */
    private static final class Ribbon {
        final List<Vec3> hist = new ArrayList<>();
        Vec3 pos;
        Vec3 vel;
        final double curl;
        final double width;
        final int life;
        final float alpha;
        final double phase;
        int age;
        /** Длина хвоста (точек истории): круговые следы ref2 длиннее порывов. */
        int tail = 10;
        /** Без турбулентности и затухания скорости — ровная дуга вокруг цели. */
        boolean orbit;
        /** Белый штрих удара: прямой, яркий, без серого тела. */
        boolean streak;

        Ribbon(Vec3 pos, Vec3 vel, double curl, double width, int life, float alpha, double phase) {
            this.pos = pos;
            this.vel = vel;
            this.curl = curl;
            this.width = width;
            this.life = life;
            this.alpha = alpha;
            this.phase = phase;
            hist.add(pos);
        }
    }

    /** Лепесток: падает, кружась, покачивается; ветер мастера подхватывает его. */
    private static final class Petal {
        Vec3 pos;
        Vec3 prev;
        Vec3 vel;
        int age;
        final int life;
        final int cell;
        final float spin;
        final double size;
        final double phase;
        final int tint;

        Petal(Vec3 pos, Vec3 vel, int life, int cell, float spin, double size, double phase, int tint) {
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.life = life;
            this.cell = cell;
            this.spin = spin;
            this.size = size;
            this.phase = phase;
            this.tint = tint;
        }
    }

    /** Клуб пыли/дыма: {x, y, z, vx, vy, vz, возраст, ячейка, жизнь, размер, дым 0/1}. */
    private static final class Puff {
        double x, y, z, vx, vy, vz;
        int age;
        final int cell;
        final int life;
        final double size;
        final boolean smoke;

        Puff(Vec3 p, Vec3 v, int cell, int life, double size, boolean smoke) {
            x = p.x;
            y = p.y;
            z = p.z;
            vx = v.x;
            vy = v.y;
            vz = v.z;
            this.cell = cell;
            this.life = life;
            this.size = size;
            this.smoke = smoke;
        }
    }

    /** Рваный всплеск контакта: лучи из точки, жизнь 4 тика. */
    private record Burst(Vec3 at, Vec3 away, int born, double scale, long seed, int life) {
    }

    private static final class Cast {
        final int entityId;
        final int layer;
        final int start;
        final Random random;
        int targetId = -1;
        Vec3 axis;
        boolean begun;
        int abortedAt = -1;
        final List<Trail> trails = new ArrayList<>();
        final List<Ribbon> ribbons = new ArrayList<>();
        final List<Petal> petals = new ArrayList<>();
        final List<Puff> puffs = new ArrayList<>();
        final List<Burst> bursts = new ArrayList<>();
        Vec3 ringAt;
        int ringBorn = -100;
        Vec3 waveBase;
        Vec3 waveF;
        /** Точка контакта удара в горло: основание финального серпа. */
        Vec3 throat;
        int waveBorn = -100;
        int crestTick = -100;
        Vec3 lastPos;
        Vec3 moved = Vec3.ZERO;
        Vec3 face;

        Cast(int entityId, int layer) {
            this.entityId = entityId;
            this.layer = layer;
            this.start = clientTicks;
            this.random = new Random(entityId * 7919L + clientTicks * 31L);
        }

        int age() {
            return clientTicks - start;
        }

        int since() {
            return age() - FallingPetalRules.STANCE;
        }

        boolean fx() {
            return layer >= 1;
        }

        boolean lunge() {
            return begun && targetId < 0;
        }

        double widthK() {
            return layer <= 1 ? 0.6D : layer == 2 ? 0.8D : layer == 3 ? 1.0D : layer <= 5 ? 1.1D : 1.2D;
        }

        double petalK() {
            return layer < 3 ? 0.0D : 0.55D + 0.12D * (layer - 3);
        }
    }

    private static Cast find(int entityId) {
        Cast c = null;
        for (Cast x : CASTS) {
            if (x.entityId == entityId) {
                c = x;
            }
        }
        return c;
    }

    public static void onTechniqueEvent(TechniqueEventPayload payload) {
        if (payload.event() != TechniqueEventPayload.Event.STARTED || !TECHNIQUE.equals(payload.techniqueId())) {
            if (payload.event() == TechniqueEventPayload.Event.CANCELLED && TECHNIQUE.equals(payload.techniqueId())) {
                Cast c = find(payload.sourceId());
                if (c != null && c.abortedAt < 0) {
                    c.abortedAt = c.since();
                }
            }
            return;
        }
        CASTS.removeIf(x -> x.entityId == payload.sourceId() && x.age() < 200 && !x.begun);
        Cast c = new Cast(payload.sourceId(), payload.layer());
        CASTS.add(c);
        if (c.fx()) {
            // Синяя живая аура стойки (AuraSim): горит в стойке и на входе, гаснет к первому удару.
            ClientAuraState.techniqueAura(c.entityId, 2, 0, 13);
        }
    }

    public static void onPayload(FallingPetalPayload p) {
        Cast c = find(p.entityId());
        if (c == null) {
            c = new Cast(p.entityId(), p.layer());
            CASTS.add(c);
        }
        Minecraft mc = Minecraft.getInstance();
        boolean own = mc.player != null && mc.player.getId() == c.entityId;
        int stage = p.stage();
        if (stage == 0) {
            c.begun = true;
            c.targetId = p.targetId();
            c.axis = new Vec3(p.axis().x, 0.0D, p.axis().z).normalize();
            return;
        }
        if (stage == 9) {
            c.abortedAt = c.since();
            return;
        }
        if (stage == 8) {
            // Колено цели касается земли (ref6): маленький всплеск у пола, пыль, короткий дым.
            if (c.fx()) {
                c.bursts.add(new Burst(p.at().add(0.0D, 0.1D, 0.0D), new Vec3(0.0D, 1.0D, 0.0D), clientTicks, 0.7D, c.random.nextLong(), 5));
                dust(c, p.at(), 6, 0.12D);
                smoke(c, p.at(), 2, 0.45D, 12);
            }
            return;
        }
        int k = stage - 1;
        Vec3 at = p.at();
        Entity e = mc.level == null ? null : mc.level.getEntity(c.entityId);
        Vec3 away = e == null ? c.axis : flat(at.subtract(e.position()));
        if (mc.player != null && mc.level != null) {
            double d = mc.player.position().distanceTo(at);
            float near = (float) Mth.clamp(1.0D - d / 16.0D, 0.0D, 1.0D);
            float own2 = own || mc.player.getId() == p.targetId() ? 1.0F : near;
            if (c.fx()) {
                // Тряска у всех рядом, в том числе у мастера (у него — напрямую, без деления на расстояние).
                if (k == 4) {
                    CameraShakeHandler.quake(0.8F * own2, 12);
                } else if (k == 2) {
                    CameraShakeHandler.quake(0.5F * own2, 8);
                } else {
                    CameraShakeHandler.quake(0.18F * own2, 4);
                }
            }
            io.github.verycooltimo.murim.client.Sfx.play(at.x, at.y, at.z, k == 4 ? io.github.verycooltimo.murim.registry.ModSounds.IMPACT_HEAVY.get()
                    : io.github.verycooltimo.murim.registry.ModSounds.SWORD_HIT.get(), SoundSource.PLAYERS, 1.0F,
                    k == 2 ? 0.8F : k == 4 ? 1.0F : 1.05F, false);
            if (k == 2) {
                // «Ломает кости»: глухой хруст поверх звона клинка.
                mc.level.playLocalSound(at.x, at.y, at.z, SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.PLAYERS, 0.45F, 1.6F, false);
            }
        }
        if (!c.fx()) {
            return;
        }
        // Плечо: всплеск живёт дольше импакт-кадра (8 тиков) и смотрит от мастера и вниз (ref3).
        Vec3 dirB = k == 2 ? away.add(0.0D, -0.35D, 0.0D) : away;
        // Основание всплеска — на поверхности цели со стороны мастера, а не в её центре.
        Entity tgtE = mc.level == null ? null : mc.level.getEntity(p.targetId());
        double half = tgtE == null ? 0.3D : tgtE.getBbWidth() * 0.5D;
        at = at.subtract(away.scale(half));
        c.bursts.add(new Burst(at, dirB, clientTicks, k == 2 ? 1.5D : k == 4 ? 0.7D : 0.85D, c.random.nextLong(), k == 2 ? 8 : 5));
        if (k == 4) {
            c.throat = at;
        }
        if (k == 2 || k == 4) {
            if (own) {
                ImpactFrames.trigger(at);
                SpeedLines.radial(0.5F, 0.5F, k == 4 ? 1.0F : 0.7F, k == 4 ? 8 : 5, SpeedLines.WHITE);
            }
            // Дым из места попадания, а не вал у ног: несколько неравных клочьев.
            smoke(c, at.subtract(0.0D, 0.25D, 0.0D).add(away.scale(k == 4 ? 0.45D : 0.2D)), k == 4 ? 4 : 2, k == 4 ? 0.2D : 0.17D, k == 4 ? 12 : 9);
        } else {
            smoke(c, at.subtract(0.0D, 0.6D, 0.0D), 1, 0.3D, 10);
        }
        // Удар собран в точке контакта (ref3/ref7): плотный веер лепестков и белых штрихов по
        // направлению реза, 2,5–4 блока; на прочих движениях лепестков меньше.
        petalsAt(c, at, (int) Math.round((k == 4 ? 45 : k == 2 ? 32 : 16) * c.petalK()), k == 4 ? 0.3D : 0.24D, away);
        int streaks = k == 4 ? 9 : k == 2 ? 7 : 4;
        for (int i = 0; i < streaks; i++) {
            Vec3 d = away.add(c.random.nextGaussian() * 0.35D, 0.1D + c.random.nextGaussian() * 0.3D, c.random.nextGaussian() * 0.35D).normalize();
            Ribbon st = new Ribbon(at, d.scale(0.5D + 0.25D * c.random.nextDouble()), 0.0D, 0.06D * c.widthK(), 4 + c.random.nextInt(2), 0.95F, 0.0D);
            st.tail = 5;
            st.streak = true;
            c.ribbons.add(st);
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
            Entity e = mc.level.getEntity(c.entityId);
            int age = c.age();
            int s = c.since();
            boolean live = e != null && (c.abortedAt < 0 || s <= c.abortedAt + 1) && s <= FallingPetalRules.END;
            if (e != null) {
                Vec3 now = e.position();
                c.moved = c.lastPos == null ? Vec3.ZERO : now.subtract(c.lastPos);
                c.lastPos = now;
                c.face = facing(c, e, mc);
                if (live && mc.player == e && c.face != null && age >= 1) {
                    // Мастер смотрит на живую цель весь натиск: плавный доворот, без рывка камеры.
                    float want = (float) Math.toDegrees(Math.atan2(-c.face.x, c.face.z));
                    float cur = mc.player.getYRot();
                    mc.player.setYRot(cur + Mth.wrapDegrees(want - cur) * (age < FallingPetalRules.STANCE ? 0.2F : 0.42F));
                }
            }
            if (live && c.fx()) {
                stance(c, e, age);
                sample(c, e, s);
                motion(c, e, s);
            }
            simulate(c, mc);
            boolean empty = c.trails.isEmpty() && c.ribbons.isEmpty() && c.petals.isEmpty() && c.puffs.isEmpty();
            if (age > 400 || (s > FallingPetalRules.END + 10 && empty) || (e == null && age > 40 && empty)) {
                it.remove();
            }
        }
    }

    private static Vec3 facing(Cast c, Entity e, Minecraft mc) {
        if (c.targetId >= 0) {
            Entity t = mc.level.getEntity(c.targetId);
            if (t != null) {
                Vec3 d = flat(t.position().subtract(e.position()));
                if (d.lengthSqr() > 1.0E-4D) {
                    return d;
                }
            }
        }
        if (c.axis != null) {
            return c.axis;
        }
        Vec3 look = Vec3.directionFromRotation(0.0F, e.getYRot());
        return flat(look);
    }

    /** Стойка ref1: пыль при посадке, низкая незамкнутая лента ветра у стоп, подпись. */
    private static void stance(Cast c, Entity e, int age) {
        Minecraft mc = Minecraft.getInstance();
        if (age == 2) {
            dust(c, e.position(), 7, 0.13D);
            if (mc.player == e) {
                TechniqueCaption.show(Component.translatable("technique.murim.falling_petal_sword.school"),
                        Component.translatable("technique.murim.falling_petal_sword.form"), 38);
            }
        }
        if (age == 3 || age == 6) {
            Vec3 f = c.face == null ? flat(Vec3.directionFromRotation(0.0F, e.getYRot())) : c.face;
            Vec3 side = FallingPetalRules.left(f);
            double sgn = age == 3 ? -1.0D : 1.0D;
            Vec3 at = e.position().add(f.scale(0.7D)).add(side.scale(0.5D * sgn)).add(0.0D, 0.12D, 0.0D);
            c.ribbons.add(new Ribbon(at, side.scale(-0.22D * sgn).add(f.scale(0.12D)), 0.32D * sgn, 0.09D * c.widthK(), 11, 0.55F,
                    c.random.nextDouble() * 6.0D));
            if (c.petalK() > 0.0D) {
                petalsAt(c, at.add(0.0D, 0.3D, 0.0D), 2, 0.05D, f);
            }
        }
        // Серп у пола (ref1): острие чертит дугу перед стопами, пока мастер садится в стойку.
        if (age >= 3 && age <= 6) {
            Vec3 f = c.face == null ? flat(Vec3.directionFromRotation(0.0F, e.getYRot())) : c.face;
            Vec3 side = FallingPetalRules.left(f);
            Trail tr = null;
            for (Trail x : c.trails) {
                if (x.strike == FLOOR) {
                    tr = x;
                }
            }
            if (tr == null) {
                tr = new Trail(FLOOR, false);
                c.trails.add(tr);
            }
            for (int j = 1; j <= 4; j++) {
                double u = (age - 3 + j / 4.0D) / 4.0D;
                double ang = Math.toRadians(-110.0D + 150.0D * u);
                double rr = 1.05D - 0.25D * u;
                Vec3 at = e.position().add(f.scale(0.35D + Math.cos(ang) * rr)).add(side.scale(Math.sin(ang) * rr))
                        .add(0.0D, 0.05D + 0.12D * u * u, 0.0D);
                tr.pts.add(new Pt(at, age - 1 + j / 4.0D));
            }
        }
        if (age > 3 && age < FallingPetalRules.STANCE && age % 3 == 0) {
            dust(c, e.position(), 2, 0.06D);
        }
    }

    /** Активное движение клинка в тик {@code s}: {удар, возврат 0/1, доля} или null. */
    private static double[] blade(Cast c, double s) {
        if (c.lunge()) {
            double p = (s - (FallingPetalRules.LUNGE_CUT - FallingPetalRules.CUT)) / FallingPetalRules.CUT;
            return p >= 0.0D && p <= 1.3D ? new double[] {0, 0, p} : null;
        }
        if (!c.begun) {
            return null;
        }
        if (s >= 1.0D && s <= FallingPetalRules.DASHES[0][1] - 1.0D) {
            return new double[] {CARRY, 0, (s - 1.0D) / (FallingPetalRules.DASHES[0][1] - 2.0D)};
        }
        if (s >= FallingPetalRules.STRIKES[3] + 3.4D && s <= FallingPetalRules.STRIKES[3] + 8.0D) {
            return new double[] {LOOP, 0, (s - FallingPetalRules.STRIKES[3] - 3.4D) / 4.6D};
        }
        for (int k = 0; k < FallingPetalRules.STRIKES.length; k++) {
            int hit = FallingPetalRules.STRIKES[k];
            double p = (s - (hit - FallingPetalRules.CUT)) / FallingPetalRules.CUT;
            if (p >= 0.0D && p <= 1.3D) {
                return new double[] {k, 0, p};
            }
            if (FallingPetalRules.RETURNS[k]) {
                double r = (s - (hit + 1.2D)) / FallingPetalRules.RETURN;
                if (r >= 0.0D && r <= 1.0D) {
                    return new double[] {k, 1, r};
                }
            }
        }
        return null;
    }

    private static Vec3 waist(Entity e, double frac) {
        return new Vec3(Mth.lerp(frac, e.xo, e.getX()), Mth.lerp(frac, e.yo, e.getY()) + 1.0D, Mth.lerp(frac, e.zo, e.getZ()));
    }

    /** Острие клинка в мире, 4 отсчёта на тик: след живёт в мире, а не прилеплен к телу. */
    private static void sample(Cast c, Entity e, int s) {
        if (c.face == null) {
            return;
        }
        for (int j = 1; j <= 4; j++) {
            double frac = j / 4.0D;
            double t = s - 1 + frac;
            double[] b = blade(c, t);
            if (b == null) {
                continue;
            }
            int k = (int) b[0];
            boolean ret = b[1] > 0.5D;
            Trail tr = c.trails.isEmpty() ? null : c.trails.get(c.trails.size() - 1);
            if (tr == null || tr.strike != k || tr.ret != ret) {
                tr = new Trail(k, ret);
                c.trails.add(tr);
            }
            Vec3 tipAt;
            if (k == CARRY) {
                // Клинок низко справа-сзади; волна по высоте — петли следа, вытянутые бегом.
                Vec3 sd = FallingPetalRules.left(c.face);
                tipAt = waist(e, frac).add(c.face.scale(-0.55D)).add(sd.scale(-0.5D))
                        .add(0.0D, -0.2D + 0.38D * Math.sin(b[2] * Math.PI * 2.4D), 0.0D);
            } else if (k == LOOP) {
                // Петля: позади и чуть сбоку, вверх на 1,2–1,5 блока над землёй и обратно вниз.
                Vec3 sd = FallingPetalRules.left(c.face);
                double ph = b[2] * Math.PI;
                tipAt = waist(e, frac).add(c.face.scale(-0.35D - 0.5D * Math.sin(ph))).add(sd.scale(-0.55D + 0.9D * b[2]))
                        .add(0.0D, -0.6D + 1.15D * Math.sin(ph), 0.0D);
            } else {
                double[] arc = ret ? FallingPetalRules.returnArc(k) : FallingPetalRules.ARCS[k];
                tipAt = FallingPetalRules.tip(waist(e, frac), c.face, arc, b[2]);
            }
            tr.pts.add(new Pt(tipAt, c.age() - 1 + frac));
        }
    }

    /** Ветер, пыль и лепестки от каждого движения: рывки, резы, возвраты, разворот, волна. */
    private static void motion(Cast c, Entity e, int s) {
        if (!c.begun || c.face == null) {
            return;
        }
        Vec3 f = c.face;
        Vec3 side = FallingPetalRules.left(f);
        double speed = Math.sqrt(c.moved.x * c.moved.x + c.moved.z * c.moved.z);
        Vec3 dir = speed > 1.0E-3D ? new Vec3(c.moved.x / speed, 0.0D, c.moved.z / speed) : f;
        // Рывки: ленты ветра срываются назад, закручиваясь, пыль от каждой опоры.
        if (speed > 0.12D) {
            int n = c.layer >= 5 ? 2 : 1;
            for (int i = 0; i < n; i++) {
                double h = 0.25D + c.random.nextDouble() * 1.3D;
                double sgn = c.random.nextBoolean() ? 1.0D : -1.0D;
                Vec3 at = e.position().add(0.0D, h, 0.0D).add(FallingPetalRules.left(dir).scale(sgn * (0.3D + 0.3D * c.random.nextDouble())));
                c.ribbons.add(new Ribbon(at, dir.scale(-0.16D - 0.12D * c.random.nextDouble()).add(0.0D, 0.02D, 0.0D),
                        0.18D * sgn, (0.07D + 0.05D * c.random.nextDouble()) * c.widthK(),
                        s >= FallingPetalRules.STRIKES[3] ? 5 + c.random.nextInt(2) : 9 + c.random.nextInt(4),
                        0.42F, c.random.nextDouble() * 6.0D));
            }
            // Скольжение — вытянутый след пыли назад (ref1/ref4).
            if (c.age() % 2 == 0) {
                dust(c, e.position().subtract(dir.scale(0.3D)), 4, 0.05D, dir.scale(-0.16D));
            }
            // Поток лепестков тянется ЗА мастером (подхват бегом), а не облаком вокруг.
            if (c.petalK() > 0.0D && c.random.nextDouble() < 0.9D * c.petalK()) {
                petalsAt(c, e.position().subtract(dir.scale(0.5D + c.random.nextDouble())).add(0.0D, 0.5D + c.random.nextDouble(), 0.0D),
                        1, 0.07D, dir.scale(-1.0D));
            }
        }
        // Круговые следы ref2: проход вокруг цели оставляет длинные серо-белые дуги, огибающие
        // цель на разных высотах — каждая со своей скоростью и закруткой, без замкнутых колец.
        Entity tgt = c.targetId >= 0 ? Minecraft.getInstance().level.getEntity(c.targetId) : null;
        if (tgt != null && speed > 0.1D && s > FallingPetalRules.STRIKES[0] + 1 && s < FallingPetalRules.STRIKES[3] && !nearContact(s)
                && c.ribbons.stream().filter(x -> x.orbit).count() < 8) {
            Vec3 rad = flat(e.position().subtract(tgt.position()));
            double rr = Mth.clamp(e.position().subtract(tgt.position()).horizontalDistance(), 0.9D, 2.2D);
            Vec3 tang = new Vec3(-rad.z, 0.0D, rad.x);
            double sgn = Math.signum(tang.dot(dir));
            if (sgn != 0.0D) {
                // ref2: широкий серый вихрь вокруг цели — по две длинные дуги каждый тик обхода.
                int n = 2;
                for (int i = 0; i < n; i++) {
                    double h = 0.25D + c.random.nextDouble() * 1.6D;
                    double sp = 0.55D + 0.15D * c.random.nextDouble();
                    Ribbon o = new Ribbon(tgt.position().add(rad.scale(rr * (0.9D + 0.25D * c.random.nextDouble()))).add(0.0D, h, 0.0D),
                            tang.scale(sgn * sp).add(0.0D, (c.random.nextDouble() - 0.5D) * 0.04D, 0.0D),
                            sgn * sp / rr, (0.16D + 0.1D * c.random.nextDouble()) * c.widthK(), 14 + c.random.nextInt(5),
                            0.5F, c.random.nextDouble() * 6.0D);
                    o.tail = 9;
                    o.orbit = true;
                    c.ribbons.add(o);
                }
            }
        }
        for (int di = 0; di < FallingPetalRules.DASHES.length; di++) {
            int[] d = FallingPetalRules.DASHES[di];
            if (di != FallingPetalRules.PIVOT && (s == d[0] || s == d[0] + d[1])) {
                // Постановка стопы — короткий острый веер наружу за 2–3 тика (ref6).
                dust(c, e.position(), s == d[0] ? 14 : 18, 0.24D);
            }
        }
        // На каждом контакте: две-три ленты по касательной реза и лепестки из хвоста следа.
        double[] b = blade(c, s);
        if (b != null && (int) b[0] < CARRY) {
            int k = (int) b[0];
            boolean ret = b[1] > 0.5D;
            double[] arc = ret ? FallingPetalRules.returnArc(k) : FallingPetalRules.ARCS[k];
            Vec3 tip = FallingPetalRules.tip(waist(e, 1.0D), f, arc, b[2]);
            Vec3 tip2 = FallingPetalRules.tip(waist(e, 1.0D), f, arc, b[2] + 0.15D);
            Vec3 tan = tip2.subtract(tip);
            tan = tan.lengthSqr() > 1.0E-6D ? tan.normalize() : side;
            double sw = Math.signum(arc[1] - arc[0]);
            if (!ret && b[2] > 0.45D && b[2] < 0.95D && c.age() % 2 == 0) {
                // Три направленные дуги ветра у стоп, пояса и плеч (ref2/ref5), между ними пусто.
                int n = c.layer >= 2 ? 3 : 1;
                double[] hs = {0.25D, 1.0D, 1.6D};
                for (int i = 0; i < n; i++) {
                    Vec3 from = new Vec3(tip.x, e.getY() + hs[i], tip.z);
                    Ribbon w = new Ribbon(from, tan.scale(0.7D + 0.15D * c.random.nextDouble()).add(0.0D, 0.02D * (i - 1), 0.0D),
                            0.09D * sw, (0.09D + 0.06D * c.random.nextDouble()) * c.widthK(), 12 + c.random.nextInt(3), 0.6F, c.random.nextDouble() * 6.0D);
                    w.tail = 18;
                    c.ribbons.add(w);
                }
                if (c.petalK() > 0.0D) {
                    petalsAt(c, tip, (int) Math.round(5 * c.petalK()), 0.14D, tan);
                }
            } else if (ret && b[2] > 0.4D && b[2] < 0.6D) {
                c.ribbons.add(new Ribbon(tip, tan.scale(0.22D), -0.25D * sw, 0.04D * c.widthK(), 6, 0.45F, 0.0D));
                if (c.petalK() > 0.0D) {
                    petalsAt(c, tip, 1, 0.06D, tan);
                }
            }
            if (!ret && Math.abs(b[2] - 0.75D) < 0.13D) {
                // Свист клинка у каждого реза, даже мимо: техника слышна.
                io.github.verycooltimo.murim.client.Sfx.play(tip.x, tip.y, tip.z, io.github.verycooltimo.murim.registry.ModSounds.SWORD_SWING.get(),
                        SoundSource.PLAYERS, 0.5F, 1.05F + 0.05F * k, false);
            }
        }
        // Шаг-разворот ref4: кольцо у голеней.
        int[] pivot = FallingPetalRules.DASHES[FallingPetalRules.PIVOT];
        if (!c.lunge() && s == pivot[0]) {
            c.ringBorn = clientTicks;
            c.ringAt = e.position();
            dust(c, e.position(), 3, 0.12D);
        }
        if (!c.lunge() && s > pivot[0] && s <= pivot[0] + pivot[1]) {
            c.ringAt = e.position();
            Vec3 at = e.position().add(0.0D, 0.2D, 0.0D);
            c.ribbons.add(new Ribbon(at.add(side.scale(0.5D)), f.scale(0.12D), 0.4D, 0.05D * c.widthK(), 8, 0.45F, 1.0D));
            if (c.petalK() > 0.0D) {
                petalsAt(c, at, 2, 0.08D, side);
            }
        }
        // Финальная волна ref7: высокий серп над бойцами — часть движения, рисуется и при промахе.
        if (!c.lunge() && s == FallingPetalRules.STRIKES[4]) {
            c.waveBorn = clientTicks;
            c.waveBase = e.position();
            c.waveF = f;
        }
    }

    /** Рядом с контактом удара (±2 тика) длинные ленты не рождаются: удар должен читаться один. */
    private static boolean nearContact(int s) {
        for (int hit : FallingPetalRules.STRIKES) {
            if (Math.abs(s - hit) <= 2) {
                return true;
            }
        }
        return false;
    }

    private static void simulate(Cast c, Minecraft mc) {
        double now = c.age();
        for (Trail t : c.trails) {
            t.pts.removeIf(p -> now - p.born > t.life + 0.5D);
        }
        c.trails.removeIf(t -> t.pts.isEmpty() && now > 2);
        Vec3 up = new Vec3(0.0D, 1.0D, 0.0D);
        for (Ribbon r : c.ribbons) {
            r.age++;
            double a = r.curl;
            double cs = Math.cos(a);
            double sn = Math.sin(a);
            Vec3 v = new Vec3(r.vel.x * cs - r.vel.z * sn, r.vel.y, r.vel.x * sn + r.vel.z * cs);
            double turb = r.orbit ? 0.004D : 0.012D;
            v = v.scale(r.orbit ? 0.985D : r.streak ? 0.85D : 0.95D).add(Math.sin(r.age * 0.9D + r.phase) * turb, Math.cos(r.age * 0.7D + r.phase) * turb * 0.6D,
                    Math.cos(r.age * 1.1D + r.phase * 1.3D) * turb);
            r.vel = v;
            r.pos = r.pos.add(v);
            r.hist.add(r.pos);
            if (r.hist.size() > r.tail) {
                r.hist.remove(0);
            }
        }
        c.ribbons.removeIf(r -> r.age >= r.life);
        Entity e = mc.level.getEntity(c.entityId);
        Vec3 master = e == null ? null : e.position().add(0.0D, 0.9D, 0.0D);
        double mspeed = Math.sqrt(c.moved.x * c.moved.x + c.moved.z * c.moved.z);
        for (Petal p : c.petals) {
            p.prev = p.pos;
            p.age++;
            Vec3 v = p.vel;
            // Проход мастера рядом снова подхватывает лепесток: ветер в сторону его движения.
            if (master != null && mspeed > 0.1D) {
                double d = p.pos.distanceTo(master);
                if (d < 2.2D) {
                    v = v.add(c.moved.scale(0.18D * (1.0D - d / 2.2D))).add(0.0D, 0.012D, 0.0D);
                }
            }
            double sway = Math.sin(p.age * 0.23D + p.phase);
            v = new Vec3(v.x * 0.9D + sway * 0.006D, Math.max(-0.028D, v.y * 0.9D - 0.0035D), v.z * 0.9D + Math.cos(p.age * 0.19D + p.phase) * 0.006D);
            p.vel = v;
            p.pos = p.pos.add(v);
            if (p.vel.y < 0.0D && !mc.level.getBlockState(BlockPos.containing(p.pos)).isAir()) {
                p.vel = Vec3.ZERO;
                p.pos = new Vec3(p.pos.x, Math.floor(p.pos.y) + 1.02D, p.pos.z);
            }
        }
        c.petals.removeIf(p -> p.age >= p.life);
        for (Puff d : c.puffs) {
            d.x += d.vx;
            d.y += d.vy;
            d.z += d.vz;
            d.vx *= 0.86D;
            d.vz *= 0.86D;
            d.vy = d.smoke ? d.vy * 0.9D + 0.004D : d.vy * 0.8D;
            d.age++;
        }
        c.puffs.removeIf(d -> d.age >= d.life);
        c.bursts.removeIf(b -> clientTicks - b.born > b.life + 1);
    }

    private static void dust(Cast c, Vec3 at, int n, double speed) {
        dust(c, at, n, speed, Vec3.ZERO);
    }

    private static void dust(Cast c, Vec3 at, int n, double speed, Vec3 drift) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.getBlockState(BlockPos.containing(at.add(0.0D, -0.2D, 0.0D))).isAir()) {
            return;
        }
        for (int i = 0; i < n && c.puffs.size() < 420; i++) {
            double a = c.random.nextDouble() * Math.PI * 2.0D;
            double sp = speed * (0.5D + 0.7D * c.random.nextDouble());
            c.puffs.add(new Puff(at.add(0.0D, 0.08D, 0.0D), new Vec3(Math.cos(a) * sp + drift.x, 0.006D, Math.sin(a) * sp + drift.z),
                    c.random.nextInt(16), 12 + c.random.nextInt(6), 0.16D + 0.12D * c.random.nextDouble(), false));
        }
    }

    /** Cel-дым манхвы: сначала низкий вал по земле, потом встаёт и разбухает. */
    private static void smoke(Cast c, Vec3 at, int n, double size, int life) {
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2.0D * i / Math.max(1, n) + c.random.nextDouble() * 0.6D;
            double sp = 0.07D + 0.08D * c.random.nextDouble();
            double off = 0.15D + 0.3D * c.random.nextDouble();
            c.puffs.add(new Puff(at.add(Math.cos(a) * off, (c.random.nextDouble() - 0.3D) * 0.4D, Math.sin(a) * off),
                    new Vec3(Math.cos(a) * sp, 0.012D, Math.sin(a) * sp), c.random.nextInt(16), life + c.random.nextInt(6),
                    size * (0.6D + 0.8D * c.random.nextDouble()), true));
        }
    }

    /** Лепестки: в основном молочно-белые, часть с бледно-розовым румянцем, редкий тёплый блик. */
    private static void petalsAt(Cast c, Vec3 at, int n, double speed, Vec3 along) {
        if (c.petalK() <= 0.0D) {
            return;
        }
        Vec3 a = along.lengthSqr() > 1.0E-6D ? along.normalize() : Vec3.ZERO;
        for (int i = 0; i < n && c.petals.size() < 700; i++) {
            Vec3 v = a.scale(speed * (0.6D + 0.8D * c.random.nextDouble()))
                    .add(c.random.nextGaussian() * speed * 0.35D, 0.02D + c.random.nextDouble() * speed * 0.4D, c.random.nextGaussian() * speed * 0.35D);
            int tint = c.random.nextInt(10);
            c.petals.add(new Petal(at.add(c.random.nextGaussian() * 0.12D, c.random.nextGaussian() * 0.12D, c.random.nextGaussian() * 0.12D), v,
                    34 + c.random.nextInt(30), c.random.nextInt(4), (float) ((c.random.nextDouble() - 0.5D) * 0.6D),
                    0.05D + 0.035D * c.random.nextDouble(), c.random.nextDouble() * 6.28D, tint < 8 ? 0 : tint < 10 ? 1 : 2));
        }
    }

    private static Vec3 flat(Vec3 v) {
        Vec3 f = new Vec3(v.x, 0.0D, v.z);
        return f.lengthSqr() < 1.0E-8D ? new Vec3(0.0D, 0.0D, 1.0D) : f.normalize();
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
                if (!c.fx()) {
                    continue;
                }
                double now = c.age() - 1 + partial;
                VertexConsumer air = buffers.getBuffer(MurimRenderTypes.airBand());
                for (Trail t : c.trails) {
                    trail(c, t, pose, camera, air, now);
                }
                for (Ribbon r : c.ribbons) {
                    ribbon(r, pose, camera, air, partial);
                }
                ring(c, pose, camera, air, partial);
                wave(c, pose, camera, air, partial);
                for (Burst b : c.bursts) {
                    burst(b, pose, camera, air, partial);
                }
                buffers.endBatch(MurimRenderTypes.airBand());
                if (!c.puffs.isEmpty()) {
                    RenderType dt = MurimRenderTypes.dustPuffs();
                    VertexConsumer dv = buffers.getBuffer(dt);
                    for (Puff d : c.puffs) {
                        if (d.smoke) {
                            continue;
                        }
                        float pt = (d.age + partial) / d.life;
                        PlumVfx.puff(dv, pose, camera, new Vec3(d.x + d.vx * partial, d.y + d.vy * partial, d.z + d.vz * partial),
                                d.size * (0.7D + 0.9D * pt), d.cell, pt < 0.45F ? 0.85F : Mth.clamp(1.0F - (pt - 0.45F) / 0.55F, 0.0F, 1.0F) * 0.85F, 0.47F);
                    }
                    buffers.endBatch(dt);
                    RenderType st = MurimRenderTypes.smokeCel();
                    VertexConsumer sv = buffers.getBuffer(st);
                    for (Puff d : c.puffs) {
                        if (!d.smoke) {
                            continue;
                        }
                        float pt = (d.age + partial) / d.life;
                        float a = Mth.clamp(pt / 0.08F, 0.0F, 1.0F) * (pt < 0.7F ? 1.0F : Mth.clamp(1.0F - (pt - 0.7F) / 0.3F, 0.0F, 1.0F));
                        PlumVfx.smokePuff(sv, pose, camera, new Vec3(d.x, d.y, d.z), d.size * (0.6D + 0.6D * Math.sqrt(pt)) * (1.0D - 0.35D * pt), d.cell, a * 0.9F, 0.58F,
                                (float) d.cell + pt);
                    }
                    buffers.endBatch(st);
                }
                if (!c.petals.isEmpty()) {
                    RenderType pt = PETALS;
                    VertexConsumer pc = buffers.getBuffer(pt);
                    for (Petal m : c.petals) {
                        Vec3 at = m.prev.lerp(m.pos, partial);
                        if (at.distanceToSqr(camera) < 1.2D) {
                            continue;
                        }
                        float a = Mth.clamp((m.life - m.age - partial) / 12.0F, 0.0F, 1.0F) * Mth.clamp((m.age + partial) / 2.0F, 0.0F, 1.0F);
                        // Лепесток переворачивается в полёте: ширина «мигает» вдоль оси вращения.
                        double flip = 0.55D + 0.45D * Math.abs(Math.sin((m.age + partial) * 0.35D + m.phase));
                        // ~80% #EDEAE2, ~20% #DABDC5 (бледный румянец сливы).
                        float r = m.tint == 1 ? 0xDA / 255.0F : 0xED / 255.0F;
                        float g = m.tint == 1 ? 0xBD / 255.0F : 0xEA / 255.0F;
                        float bl = m.tint == 1 ? 0xC5 / 255.0F : 0xE2 / 255.0F;
                        PlumVfx.petal(pc, pose, camera, at, m.size * 2.4D * flip, m.cell, (m.age + partial) * m.spin + (float) m.phase, a, r, g, bl);
                    }
                    buffers.endBatch(pt);
                    RenderType gt = MurimRenderTypes.mote();
                    VertexConsumer g = buffers.getBuffer(gt);
                    for (Petal m : c.petals) {
                        if (m.age < 10) {
                            float a = Mth.clamp((10 - m.age - partial) / 10.0F, 0.0F, 1.0F);
                            PlumVfx.glow(g, pose, camera, m.prev.lerp(m.pos, partial), m.size * 2.2D, 0.3F * a, m.tint == 1 ? PlumVfx.BLUSH : CORE);
                        }
                    }
                    buffers.endBatch(gt);
                }
            }
        } finally {
            ps.popPose();
        }
    }

    /**
     * След острия: ширина как у лепестка — острый хвост, широко у самого реза, острая голова;
     * тёмная кромка снизу, молочное тело, белое ядро. Возврат — узкий, ярко-белый, холодный.
     */
    private static void trail(Cast c, Trail t, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, double now) {
        List<Vec3> pts = new ArrayList<>();
        List<Double> ages = new ArrayList<>();
        for (Pt p : t.pts) {
            if (p.born <= now + 1.0E-3D) {
                pts.add(p.p);
                ages.add(now - p.born);
            }
        }
        if (pts.size() < 3) {
            return;
        }
        int n = pts.size();
        Vec3[] p = pts.toArray(new Vec3[0]);
        double[] w = new double[n];
        float[] a = new float[n];
        // Ширины по сверке codex 03.10 (ref1/5/7): обычный рез 0,25–0,35, возврат узкий.
        double base = (t.ret ? 0.1D : t.strike == CARRY ? 0.2D : t.strike == FLOOR ? 0.18D : t.strike == LOOP ? 0.2D : t.strike == 2 ? 0.36D : t.strike == 3 ? 0.32D : 0.28D) * c.widthK();
        for (int i = 0; i < n; i++) {
            double u = i / (double) (n - 1);
            double life = Mth.clamp(1.0D - ages.get(i) / t.life, 0.0D, 1.0D);
            // Профиль лепестка: максимум ближе к голове (у самого удара), хвост — игла.
            double shape = Math.pow(Math.sin(Math.PI * Math.pow(u, 0.62D)), 1.25D);
            // Свободный хвост тонкий, широко — только у самого контакта (голова следа).
            w[i] = base * shape * (0.35D + 0.65D * life) * (0.3D + 0.7D * u * u);
            a[i] = (float) (Math.pow(life, 0.8D) * Mth.clamp(u * 3.0D, 0.0D, 1.0D));
        }
        if (t.ret) {
            PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 2.2D), PlumVfx.scaled(a, 0.18F), COLD);
            PlumVfx.stripVar(v, pose, camera, p, w, PlumVfx.scaled(a, 0.95F), WHITE);
            return;
        }
        // Тёмная кромка чуть ниже следа: читаемый край манхвы.
        Vec3[] low = new Vec3[n];
        for (int i = 0; i < n; i++) {
            low[i] = p[i].add(0.0D, -w[i] * 0.45D, 0.0D);
        }
        PlumVfx.stripVar(v, pose, camera, low, PlumVfx.scale(w, 1.15D), PlumVfx.scaled(a, 0.55F), SHADOW);
        PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 1.35D), PlumVfx.scaled(a, 0.35F), RIM);
        PlumVfx.stripVar(v, pose, camera, p, w, PlumVfx.scaled(a, 0.95F), CORE);
        PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 0.5D), PlumVfx.scaled(a, 1.0F), WHITE);
        // Слой 6+: вторая тонкая отстающая кромка у плеча и низкого прохода.
        if (c.layer >= 6 && (t.strike == 2 || t.strike == 3) && n > 6) {
            Vec3[] lag = new Vec3[n - 3];
            double[] lw = new double[n - 3];
            float[] la = new float[n - 3];
            for (int i = 0; i < n - 3; i++) {
                lag[i] = p[i].add(0.0D, 0.22D, 0.0D);
                lw[i] = w[i + 3] * 0.3D;
                la[i] = a[i] * 0.6F;
            }
            PlumVfx.stripVar(v, pose, camera, lag, lw, la, CORE);
        }
    }

    /** Лента ветра: хвост из истории позиций, тонкий у хвоста, ширина — у головы. */
    private static void ribbon(Ribbon r, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        int n = r.hist.size();
        if (n < 3) {
            return;
        }
        Vec3[] p = new Vec3[n];
        double[] w = new double[n];
        float[] a = new float[n];
        float life = Mth.clamp(1.0F - (r.age + partial) / r.life, 0.0F, 1.0F);
        for (int i = 0; i < n; i++) {
            p[i] = r.hist.get(i);
            double u = i / (double) (n - 1);
            w[i] = r.width * Math.sin(Math.PI * Math.pow(u, 0.7D)) * (0.5D + 0.5D * life);
            a[i] = (float) (r.alpha * life * Math.min(1.0D, u * 2.5D));
        }

        p[n - 1] = r.hist.get(n - 2).lerp(r.hist.get(n - 1), partial);
        if (r.streak) {
            PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 1.5D), PlumVfx.scaled(a, 0.5F), SHADOW);
            PlumVfx.stripVar(v, pose, camera, p, w, a, WHITE);
            return;
        }
        // Серый объём (#8D9389) и тонкий светлый край (#E8E7DE); старый след темнеет к #858780.
        // Ночью серое тело терялось (съёмка 03.10): тело светлое #D8DEDC, серым только старение.
        PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 1.3D), PlumVfx.scaled(a, 0.35F), SHADOW);
        PlumVfx.stripVar(v, pose, camera, p, w, PlumVfx.scaled(a, 0.85F), PlumVfx.lerp(WIND, OLD, 1.0F - life));
        PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 0.35D), a, PlumVfx.lerp(WHITE, OLD, 1.0F - life));
    }

    /**
     * Кольцо у голеней на шаге-развороте (ref4): ОДИН незамкнутый неровный виток ~250° у опорной
     * ноги, на высоте голени, с лёгким подъёмом к ведущему концу; рисуется ходом стопы.
     */
    private static void ring(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        if (c.ringAt == null) {
            return;
        }
        float t = clientTicks - c.ringBorn + partial;
        if (t < 0.0F || t > 9.0F) {
            return;
        }
        float grow = Mth.clamp(t / 3.0F, 0.0F, 1.0F);
        float fade = Mth.clamp((9.0F - t) / 4.0F, 0.0F, 1.0F);
        int n = 32;
        double sweep = Math.toRadians(250.0D) * grow;
        double startA = c.face == null ? 0.0D : Math.atan2(c.face.z, c.face.x) + 1.4D;
        Vec3[] p = new Vec3[n + 1];
        double[] w = new double[n + 1];
        float[] a = new float[n + 1];
        for (int i = 0; i <= n; i++) {
            double u = i / (double) n;
            double ang = startA + sweep * u;
            // Неровный виток: радиус дышит, хвост ниже, голова выше (стопа поднимает след).
            double rr = 0.62D * (0.92D + 0.1D * Math.sin(u * 7.0D + c.ringBorn));
            p[i] = c.ringAt.add(Math.cos(ang) * rr, 0.15D + 0.15D * u, Math.sin(ang) * rr);
            w[i] = 0.05D * c.widthK() * Math.pow(Math.sin(Math.PI * Math.pow(u, 0.6D)), 1.3D);
            a[i] = (float) (fade * Mth.clamp(u * 3.0D, 0.0D, 1.0D));
        }
        PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 1.6D), PlumVfx.scaled(a, 0.4F), SHADOW);
        PlumVfx.stripVar(v, pose, camera, p, w, PlumVfx.scaled(a, 0.85F), CORE);
    }

    /**
     * Финальная волна-серп (ref7): ОДИН большой асимметричный серп от шеи цели вверх и назад над
     * обоими: острый ведущий конец, сужающийся хвост у клинка, тонкая тёмная кромка по внешней
     * стороне; растёт за 3 тика, держится, уходит. Слой 6+: короткий отстающий серый след.
     */
    private static void wave(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        if (c.waveBase == null) {
            return;
        }
        float t = clientTicks - c.waveBorn + partial;
        if (t < -1.0F || t > 10.0F) {
            return;
        }
        double height = c.layer <= 1 ? 2.4D : c.layer == 2 ? 2.8D : c.layer <= 4 ? 3.2D : c.layer <= 6 ? 3.5D : 3.7D;
        // Пик — на самом контакте (тик удара): волна вырастает за 1,5 тика и сразу распадается.
        t += 1.0F;
        float fade = Mth.clamp((11.0F - t) / 4.0F, 0.0F, 1.0F);
        Vec3 f = c.waveF;
        Vec3 s = FallingPetalRules.left(f);
        Vec3 base = c.waveBase;
        // Основание — у шеи цели (1,6 блока), вершина — над мастером, конец — далеко позади.
        Vec3 a = c.throat != null ? c.throat : base.add(f.scale(0.95D)).add(s.scale(-0.25D)).add(0.0D, 1.6D, 0.0D);
        Vec3 b = base.add(f.scale(0.35D)).add(s.scale(0.45D)).add(0.0D, height + 0.9D, 0.0D);
        Vec3 z = base.add(f.scale(-1.7D)).add(s.scale(0.9D)).add(0.0D, height * 0.62D, 0.0D);
        for (int edge = 0; edge < (c.layer >= 6 ? 2 : 1); edge++) {
            float te = t - edge * 2.0F;
            if (te < 0.0F) {
                continue;
            }
            float g = Mth.clamp(te / 1.6F, 0.0F, 1.0F);
            int n = 30;
            Vec3[] p = new Vec3[n + 1];
            double[] w = new double[n + 1];
            float[] al = new float[n + 1];
            for (int i = 0; i <= n; i++) {
                double uu = i / (double) n;
                // Отстающий след короче и ниже: только средняя треть дуги.
                double u = edge == 0 ? uu * g : (0.25D + 0.4D * uu) * g;
                double q = 1.0D - u;
                Vec3 pt = a.scale(q * q).add(b.scale(2.0D * q * u)).add(z.scale(u * u));
                p[i] = edge == 0 ? pt : pt.add(f.scale(0.3D)).add(0.0D, -0.35D, 0.0D);
                // Профиль серпа: игла у клинка, максимум на 65%, острый ведущий конец.
                // «Шейка» у горла не нулевая: серп непрерывно вырастает из точки удара.
                double prof = Math.max(0.3D * (1.0D - uu * 4.0D), Math.pow(Math.sin(Math.PI * Math.pow(uu, 0.9D)), 1.5D) * (1.0D - 0.45D * uu));
                w[i] = (edge == 0 ? 0.75D : 0.18D) * c.widthK() * prof * (0.25D + 0.75D * g);
                // Распад к концу жизни: хвост гаснет первым, рваными участками.
                double brk = Mth.clamp((t - 3.0D) / 6.0D, 0.0D, 1.0D);
                double rag = 0.5D + 0.5D * Math.sin(uu * 23.0D + c.waveBorn);
                double alive = Mth.clamp(uu * 1.4D + 0.2D - brk * (1.2D + 0.6D * rag), 0.0D, 1.0D);
                al[i] = (float) (fade * alive * (edge == 0 ? 1.0D : 0.55D));
            }
            if (edge == 0) {
                Vec3 up = new Vec3(0.0D, 1.0D, 0.0D);
                Vec3[] out = new Vec3[n + 1];
                for (int i = 0; i <= n; i++) {
                    out[i] = p[i].add(up.scale(w[i] * 0.8D));
                }
                PlumVfx.stripVar(v, pose, camera, out, PlumVfx.scale(w, 0.14D), PlumVfx.scaled(al, 0.75F), SHADOW);
                PlumVfx.stripVar(v, pose, camera, p, w, PlumVfx.scaled(al, 0.9F), CORE);
                PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 0.7D), al, WHITE);
            } else {
                PlumVfx.stripVar(v, pose, camera, p, w, al, RIM);
            }
        }
        // Гребень срывает лепестки, пока волна растёт.
        float grow = Mth.clamp(t / 1.6F, 0.0F, 1.0F);
        if (grow < 1.0F && c.petalK() > 0.0D && clientTicks != c.waveBorn && c.crestTick != clientTicks) {
            c.crestTick = clientTicks;
            double u = grow;
            double q = 1.0D - u;
            Vec3 crest = a.scale(q * q).add(b.scale(2.0D * q * u)).add(z.scale(u * u));
            petalsAt(c, crest, (int) Math.round(3 * c.petalK()), 0.09D, crest.subtract(a));
        }
    }

    /** Рваный белый всплеск контакта: 4–6 коротких клиньев наружу, без круглой вспышки. */
    private static void burst(Burst b, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        float t = clientTicks - b.born + partial;
        if (t < 0.0F || t > b.life) {
            return;
        }
        Random r = new Random(b.seed);
        float al = (float) PlumVfx.curve(t, 0.0, 1.0, 1.5, 1.0, b.life, 0.0);
        Vec3 away = b.away.lengthSqr() > 1.0E-6D ? b.away.normalize() : new Vec3(0.0D, 1.0D, 0.0D);
        // Плечо — плотный пучок коротких рваных зубцов: масса белого именно на плече.
        int n = b.scale > 1.2D ? 7 + r.nextInt(3) : 4 + r.nextInt(3);
        for (int i = 0; i < n; i++) {
            // После 2,5 тика связное тело распадается: лучи гаснут по одному, остаются клочья.
            double cut = 2.5D + r.nextDouble() * (b.life - 2.5D);
            float ai = t < 2.5F ? 1.0F : (float) Mth.clamp((cut - t) / 1.5D, 0.0D, 1.0D) * 0.8F;
            if (ai <= 0.0F) {
                r.nextGaussian(); r.nextGaussian(); r.nextGaussian(); r.nextDouble();
                r.nextGaussian(); r.nextGaussian(); r.nextGaussian();
                continue;
            }
            Vec3 d = away.add(r.nextGaussian() * 0.75D, r.nextGaussian() * 0.6D + 0.15D, r.nextGaussian() * 0.75D).normalize();
            double len = (0.28D + 0.32D * r.nextDouble()) * b.scale * (0.55D + 0.45D * Math.min(1.0D, t / 1.5D));
            Vec3 kink = d.add(r.nextGaussian() * 0.25D, r.nextGaussian() * 0.25D, r.nextGaussian() * 0.25D).normalize();
            Vec3 s0 = b.at.add(d.scale(0.08D * b.scale + t * 0.04D));
            Vec3[] q = {s0, s0.add(d.scale(len * 0.55D)), s0.add(d.scale(len * 0.55D)).add(kink.scale(len * 0.45D))};
            double[] qw = {0.07D * b.scale, 0.05D * b.scale, 0.0D};
            PlumVfx.strip(v, pose, camera, q, PlumVfx.scale(qw, 1.6D), 0.5F * al * ai, SHADOW);
            PlumVfx.strip(v, pose, camera, q, qw, 0.95F * al * ai, i % 2 == 0 ? WHITE : CORE);
        }
    }

    private FallingPetalVfx() {
    }
}
