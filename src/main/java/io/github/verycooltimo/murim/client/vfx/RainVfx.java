package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.CameraShakeHandler;
import io.github.verycooltimo.murim.client.ClientAuraState;
import io.github.verycooltimo.murim.network.RainPayload;
import io.github.verycooltimo.murim.network.TechniqueEventPayload;
import io.github.verycooltimo.murim.technique.RainRules;
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
import net.minecraft.world.entity.LivingEntity;
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

/**
 * Ливень Цветущей Сливы (Меч 24 Движений, секретная техника; рефы «24 plum blossom rainfall» r01–r10,
 * шкала — {@link RainRules}). Всё — симуляция: ленты ветра с хвостами, лепестки со скоростью,
 * оболочки цветка-ядра из сотен лепестков на орбитах, ливень — те же лепестки, сорванные вниз.
 * Урон и тайминги — на сервере; удар (импакт-кадр, тряска, дым) — только по факту попадания.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class RainVfx {

    private static final ResourceLocation TECHNIQUE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "twenty_four_plum_rainfall");
    /** Розовое ядро следа (r04: #FF729F / #FF76A3 / #FFF0F4). */
    private static final VfxColour TRAIL_RIM = hex(0xFF729F);
    private static final VfxColour TRAIL_BODY = hex(0xFF76A3);
    private static final VfxColour TRAIL_CORE = hex(0xFFF0F4);
    /** Ветер (r03): холодно-белый. */
    private static final VfxColour WIND = hex(0xE8EDF1);
    /** Небесное ядро (r08): почти белое, молочно-розовое, лиловое. */
    private static final VfxColour SKY_WHITE = hex(0xFFFDFE);
    private static final VfxColour SKY_MILK = hex(0xFFD5EE);
    private static final VfxColour SKY_LILAC = hex(0xDEB4D3);
    private static final VfxColour RAIN_PINK = hex(0xFF9BCE);
    private static final VfxColour GROUND = hex(0xECA5B3);
    /** Лепестки цветка-ядра (codex 02.10): внутренние #FF85BC, внешние #DB4388, сердце #FFF9FC. */
    private static final VfxColour LOBE_IN = hex(0xFF85BC);
    private static final VfxColour LOBE_OUT = hex(0xDB4388);
    private static final VfxColour HEART = hex(0xFFF9FC);
    /** Капля крови на белом снегу (Mount Hua 230) — насыщенный розовый. */
    private static final VfxColour BLOOD = hex(0xE84F86);

    private static final List<Cast> CASTS = new ArrayList<>();
    private static int clientTicks;

    /** Вблизи камеры тает: от первого лица ничего не закрывает экран (0 ближе 1,2 блока, 1 дальше 2,7). */
    private static float near(Vec3 at, Vec3 camera) {
        return (float) Mth.clamp((at.distanceTo(camera) - 1.2D) / 1.5D, 0.0D, 1.0D);
    }

    private static float[] near(Vec3[] p, float[] a, Vec3 camera) {
        float[] r = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = a[i] * near(p[i], camera);
        }
        return r;
    }

    private static void fstrip(VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Vec3[] p, double[] w, float alpha, VfxColour col) {
        float[] a = new float[p.length];
        java.util.Arrays.fill(a, alpha);
        PlumVfx.stripVar(v, pose, camera, p, w, near(p, a, camera), col);
    }

    private static VfxColour hex(int rgb) {
        return new VfxColour(((rgb >> 16) & 0xFF) / 255.0F, ((rgb >> 8) & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F);
    }

    // ------------------------------------------------------------------ частицы

    /** Свободная частица: лепесток, искра, лента ветра (с хвостом), осколок удара. */
    private static final class Mote {
        static final int PETAL = 0;
        static final int WIND_RIBBON = 1;
        static final int SPARK = 2;
        static final int SHARD = 3;
        Vec3 pos;
        Vec3 prev;
        Vec3 vel;
        int age;
        final int life;
        final int kind;
        final int cell;
        final float spin;
        final double size;
        final Vec3[] trail;
        int count;
        double drag = 0.92D;
        double gravity;
        double turbulence;
        /** Тон лепестка: 0 — холодно-белый, 1 — розовый, 2 — «капля крови». */
        int tone = 1;

        Mote(Vec3 pos, Vec3 vel, int life, int kind, int cell, float spin, double size, int trail) {
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.life = life;
            this.kind = kind;
            this.cell = cell;
            this.spin = spin;
            this.size = size;
            this.trail = new Vec3[Math.max(1, trail)];
        }
    }

    /** Пыль и дым манхвы. */
    private static final class Puff {
        Vec3 pos;
        Vec3 prev;
        Vec3 vel;
        int age;
        int delay;
        final int life;
        final int cell;
        final double size;
        final boolean smoke;
        final float gray;
        final float spin;

        Puff(Vec3 pos, Vec3 vel, int life, int cell, double size, boolean smoke, float gray, float spin) {
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.life = life;
            this.cell = cell;
            this.size = size;
            this.smoke = smoke;
            this.gray = gray;
            this.spin = spin;
        }
    }

    /**
     * Восходящая лента ветра от рук (r03), задана формулой от возраста: по спирали вокруг мастера,
     * радиус и высота растут, хвост — те же точки в прошлые тики.
     */
    private record WindArc(Vec3 centre, int born, double a0, int sign, double rMax, double hFrom, double hTo, double tilt,
                           int life, double width, double turns) {
        Vec3 at(double age) {
            double k = Mth.clamp(age / life, 0.0D, 1.0D);
            double e = 1.0D - (1.0D - k) * (1.0D - k);
            double r = 0.5D + (rMax - 0.5D) * e;
            double a = a0 + sign * turns * Math.PI * 2.0D * e;
            double h = hFrom + (hTo - hFrom) * k + Math.sin(a) * tilt * r;
            return centre.add(Math.cos(a) * r, h, Math.sin(a) * r);
        }
    }

    /** Застывшая копия тела или руки: остаточный образ уколов (только рука) и прохода (всё тело). */
    private record Ghost(Vec3 pos, float yaw, int born, int life, PartPose[] pose, boolean armOnly, int rgb, float alpha) {
    }

    /** След клинка одного укола: из кисти вдоль направления. */
    private record Thrust(Vec3 hand, Vec3 dir, int born) {
    }

    /**
     * Лепесток цветка-ядра: орбита на своей оболочке → нить к цели → падение ливнем.
     * Положение считается каждый тик; хвост в падении — точки той же траектории в прошлом.
     */
    private static final class SkyPetal {
        final int shell;
        final double a;
        final double e;
        final double w;
        final double size;
        final int cell;
        final float spin;
        final int tone;
        final int cohort;
        final int stream;
        final double delay;
        final double lane;
        double u;
        final double uSpeed;
        Vec3 pos = Vec3.ZERO;
        Vec3 prev = Vec3.ZERO;
        Vec3 fallFrom;
        Vec3 fallTo;
        int fallTick = -1;
        boolean dead;
        boolean free;
        Vec3 vel = Vec3.ZERO;
        int freeAge;

        SkyPetal(int shell, double a, double e, double w, double size, int cell, float spin, int tone, int cohort, int stream,
                 double delay, double lane, double uSpeed) {
            this.shell = shell;
            this.a = a;
            this.e = e;
            this.w = w;
            this.size = size;
            this.cell = cell;
            this.spin = spin;
            this.tone = tone;
            this.cohort = cohort;
            this.stream = stream;
            this.delay = delay;
            this.lane = lane;
            this.uSpeed = uSpeed;
        }
    }

    /** Вертикальная лента ливня: голова падает с высоты к земле за несколько тиков. */
    private record Beam(double dx, double dz, int born, double len, double width, double top, boolean white) {
    }

    private record Ring(Vec3 centre, int born, double rMax, int life, double width) {
    }

    private record Mark(Vec3 pos, int born, double size, double angle) {
    }

    private static final class Cast {
        final int entityId;
        final int layer;
        int start;
        final Random random;
        final double density;
        final double scale;
        boolean released;
        Vec3 origin;
        Vec3 dest;
        Vec3 dir = new Vec3(0.0D, 0.0D, 1.0D);
        Vec3 side = new Vec3(-1.0D, 0.0D, 0.0D);
        float yaw;
        int targetId = -1;
        Vec3 target;
        int markTick = -1;
        int lostTick = -1;
        Vec3 core;
        Vec3 corePrev;
        boolean caption;
        int hitFinal = -1;
        Vec3 hitAt;
        /** Точки следа замаха: старт за спиной мастера → за спину цели. */
        Vec3[] swing;
        int nextPeak;
        final List<Mote> motes = new ArrayList<>();
        final List<Puff> puffs = new ArrayList<>();
        final List<WindArc> winds = new ArrayList<>();
        final List<Ghost> ghosts = new ArrayList<>();
        final List<Thrust> thrusts = new ArrayList<>();
        final List<SkyPetal> sky = new ArrayList<>();
        final List<Beam> beams = new ArrayList<>();
        final List<Ring> rings = new ArrayList<>();
        final List<Mark> marks = new ArrayList<>();
        final List<Vec3[]> cores = new ArrayList<>();
        int coreBorn = -1;
        /** Глоу ядра: {фаза, радиус орбиты, скорость, наклон}. */
        final List<double[]> coreGlows = new ArrayList<>();
        ResourceLocation skin;
        PartPose[] dashPose;

        Cast(int entityId, int layer) {
            this.entityId = entityId;
            this.layer = layer;
            this.start = clientTicks;
            this.random = new Random(entityId * 7919L + clientTicks);
            this.density = RainRules.density(layer);
            this.scale = RainRules.scale(layer);
        }

        int t() {
            return clientTicks - start;
        }

        int n(double full) {
            return (int) Math.ceil(full * density);
        }

        boolean own() {
            Minecraft mc = Minecraft.getInstance();
            return mc.player != null && mc.player.getId() == entityId;
        }

        /** Камера — глаза самого мастера (не просто вид от первого лица чужой камеры). */
        boolean eyes() {
            Minecraft mc = Minecraft.getInstance();
            return own() && mc.options.getCameraType().isFirstPerson() && mc.getCameraEntity() == mc.player;
        }
    }

    // ------------------------------------------------------------------ события

    public static void onTechniqueEvent(TechniqueEventPayload payload) {
        if (payload.event() != TechniqueEventPayload.Event.STARTED || !TECHNIQUE.equals(payload.techniqueId())
                || payload.layer() <= 0) {
            return;
        }
        CASTS.removeIf(c -> c.entityId == payload.sourceId());
        Cast c = new Cast(payload.sourceId(), payload.layer());
        CASTS.add(c);
        if (c.layer >= 3) {
            // Стойка и уколы — холодная синяя аура; розовая — с замаха.
            ClientAuraState.techniqueAura(c.entityId, 2, 0, RainRules.FLURRY + 8);
        }
    }

    public static void onRain(RainPayload p) {
        Cast c = null;
        for (Cast x : CASTS) {
            if (x.entityId == p.entityId()) {
                c = x;
            }
        }
        Minecraft mc = Minecraft.getInstance();
        if (p.stage() == RainPayload.RELEASE) {
            if (c == null) {
                c = new Cast(p.entityId(), p.layer());
                CASTS.add(c);
            }
            // Сверка шкалы: пакет замаха приходит ровно на тике RELEASE техники.
            c.start = clientTicks - RainRules.RELEASE;
            c.released = true;
            c.origin = p.origin();
            c.dest = p.centre();
            c.yaw = p.yaw();
            Vec3 f = Vec3.directionFromRotation(0.0F, p.yaw());
            c.dir = new Vec3(f.x, 0.0D, f.z).normalize();
            c.side = new Vec3(-c.dir.z, 0.0D, c.dir.x);
            c.targetId = p.targetId();
            if (c.targetId >= 0 && mc.level != null && mc.level.getEntity(c.targetId) instanceof LivingEntity t) {
                c.target = t.position();
            }
            buildSwing(c);
            release(c, mc);
            return;
        }
        if (c == null) {
            return;
        }
        if (p.stage() == RainPayload.MARKED) {
            c.markTick = clientTicks;
            c.targetId = p.targetId();
            c.target = p.centre();
            suspended(c);
            return;
        }
        if (p.stage() == RainPayload.LOST) {
            c.lostTick = clientTicks;
            for (SkyPetal s : c.sky) {
                if (!s.dead && s.fallTick < 0) {
                    s.free = true;
                    s.vel = new Vec3(c.random.nextGaussian() * 0.03D, -0.02D, c.random.nextGaussian() * 0.03D);
                }
            }
            return;
        }
        int k = p.stage() - RainPayload.HIT;
        if (k >= 0 && k < 3) {
            hit(c, p.centre(), k, mc);
        }
    }

    // ------------------------------------------------------------------ фазы

    /** Замах (r04): белая зубчатая вспышка у рук, розовая аура, дым из-под ног, линии скорости. */
    private static void release(Cast c, Minecraft mc) {
        if (c.layer >= 3) {
            ClientAuraState.techniqueAura(c.entityId, 2 + Math.min(2, c.layer / 3), 1, RainRules.BLOOM - 6 - RainRules.RELEASE);
        }
        dust(c, c.origin, c.n(10) + 3, 0.18D);
        if (c.own()) {
            SpeedLines.directional(0.0F, 0.3F, 4, SpeedLines.WHITE);
        }
        if (mc.level != null && mc.level.getEntity(c.entityId) instanceof AbstractClientPlayer player
                && mc.getEntityRenderDispatcher().getRenderer(player) instanceof PlayerRenderer renderer) {
            c.skin = player.getSkin().texture();
            c.dashPose = capture(renderer.getModel());
        }
        if (mc.player != null) {
            mc.player.level().playLocalSound(c.origin.x, c.origin.y, c.origin.z, net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP,
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.35F, false);
        }
    }

    /** След замаха: почти прямая от-за-спины-мастера до-за-спины-цели, небольшой изгиб и наклон. */
    private static void buildSwing(Cast c) {
        Vec3 aim = c.target != null ? c.target : c.dest;
        Vec3 to = new Vec3(aim.x - c.origin.x, 0.0D, aim.z - c.origin.z);
        double dist = Math.max(2.0D, to.length());
        Vec3 d = to.lengthSqr() < 1.0E-6D ? c.dir : to.normalize();
        Vec3 s = new Vec3(-d.z, 0.0D, d.x);
        Vec3 from = c.origin.subtract(d.scale(1.2D));
        double len = dist + 1.2D + 4.6D;
        int n = 40;
        c.swing = new Vec3[n + 1];
        for (int i = 0; i <= n; i++) {
            double u = i / (double) n;
            // Наклон вниз по ходу (r05), лёгкий боковой изгиб — дуга клинка, а не линейка.
            double y = 1.75D - 0.9D * u + 0.12D * Math.sin(Math.PI * u);
            double bow = 0.35D * Math.sin(Math.PI * u) + 0.3D * (u - 0.5D);
            c.swing[i] = from.add(d.scale(len * u)).add(s.scale(bow)).add(0.0D, y, 0.0D);
        }
    }

    /** Метка прошла (r06): первые светящиеся лепестки висят вокруг цели. */
    private static void suspended(Cast c) {
        if (!RainRules.petals(c.layer) || c.target == null) {
            return;
        }
        for (int i = 0; i < Math.max(4, c.n(16)); i++) {
            double a = c.random.nextDouble() * Math.PI * 2.0D;
            double r = 0.6D + 1.4D * c.random.nextDouble();
            Mote m = petalMote(c, c.target.add(Math.cos(a) * r, 0.4D + 2.0D * c.random.nextDouble(), Math.sin(a) * r),
                    new Vec3(c.random.nextGaussian() * 0.006D, -0.004D, c.random.nextGaussian() * 0.006D), 60 + c.random.nextInt(30));
            m.drag = 0.99D;
            m.gravity = 0.0004D;
            m.turbulence = 0.004D;
            m.tone = c.random.nextInt(6) == 0 ? 2 : 1;
            c.motes.add(m);
        }
    }

    /**
     * Цветок-ядро (r08): пять неравных долей-лепестков сливы, каждая наполнена сотней живых
     * лепестков, по краю — тонкая светящаяся кромка; сердцевина — белое ядро и тычинки.
     * Раскрывается из бутона (доли сложены к оси) и дышит.
     */
    private static void bloom(Cast c) {
        int perLobe = Math.max(16, (int) Math.ceil(110 * Math.min(1.25D, c.density + 0.15D)));
        int total = 0;
        for (int k = 0; k < 5; k++) {
            for (int i = 0; i < perLobe; i++) {
                // Плотнее к краю доли: край читается, середина светится сквозь.
                // Половина — у кромки (край читается), половина — по всей доле (заполненная середина).
                double sPos = i % 3 == 0 ? 0.12D + 0.88D * Math.pow(c.random.nextDouble(), 0.3D) : 0.08D + 0.9D * Math.sqrt(c.random.nextDouble());
                double l = (c.random.nextDouble() * 2.0D - 1.0D);
                l = i % 3 == 0 ? Math.signum(l) * Math.pow(Math.abs(l), 0.6D) : l;
                int tone = c.random.nextDouble() < 0.12D ? 2 : sPos < 0.45D && c.random.nextBoolean() ? 0 : 1;
                if (i % 3 == 2) {
                    // Третий слой — светлые лепестки в глубине чаши: доля светится изнутри.
                    tone = 3;
                }
                c.sky.add(new SkyPetal(k, sPos, l, c.random.nextDouble() * Math.PI * 2.0D, 0.09D + 0.13D * c.random.nextDouble(),
                        c.random.nextInt(4), (float) ((c.random.nextDouble() - 0.5D) * 0.6D), tone, wave(c), -1,
                        c.random.nextDouble() * 10.0D + sPos * 6.0D, 0.0D, 0.0D));
                total++;
            }
        }
        // Венчик у белого сердца: острые светлые лепестки внахлёст вокруг ядра.
        for (int i = 0; i < Math.max(8, c.n(36)); i++) {
            c.sky.add(new SkyPetal(i % 5, 0.02D + 0.12D * c.random.nextDouble(), c.random.nextDouble() * 2.0D - 1.0D,
                    c.random.nextDouble() * Math.PI * 2.0D, 0.14D + 0.08D * c.random.nextDouble(), c.random.nextInt(4),
                    (float) ((c.random.nextDouble() - 0.5D) * 0.5D), 0, wave(c), -1, c.random.nextDouble() * 4.0D, 0.0D, 0.0D));
        }
        // Сердцевина: глоу на своих мелких орбитах.
        for (int i = 0; i < c.n(48); i++) {
            c.coreGlows.add(new double[] {c.random.nextDouble() * Math.PI * 2.0D, 0.1D + 0.9D * c.random.nextDouble(),
                    (0.05D + 0.1D * c.random.nextDouble()) * (c.random.nextBoolean() ? 1 : -1), c.random.nextDouble() * Math.PI});
        }
        c.coreBorn = clientTicks;
        MurimMod.LOGGER.debug("Ливень: цветок-ядро, лепестков {}", total);
    }

    /** Наклон плоскости цветка: лицом вниз к цели и на 40° к мастеру — виден со спины цели. */
    private static Vec3[] flowerBasis(Cast c, double t) {
        double tilt = Math.toRadians(40.0D);
        Vec3 n = new Vec3(0.0D, -Math.cos(tilt), 0.0D).add(c.dir.scale(Math.sin(tilt) * 0.8D)).add(c.side.scale(Math.sin(tilt) * 0.65D)).normalize();
        Vec3 e1 = n.cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
        Vec3 e2 = n.cross(e1).normalize();
        // Медленное вращение всего цветка: 0,35°/тик.
        double r = Math.toRadians(0.35D * (t - RainRules.BLOOM));
        Vec3 a = e1.scale(Math.cos(r)).add(e2.scale(Math.sin(r)));
        Vec3 b = e2.scale(Math.cos(r)).subtract(e1.scale(Math.sin(r)));
        return new Vec3[] {n, a, b};
    }

    /** Раскрытие: 0 — бутон, 1 — цветок (t84–98), с лёгким перелётом. */
    private static double open(double t) {
        double k = Mth.clamp((t - RainRules.BLOOM) / 14.0D, 0.0D, 1.0D);
        return k * k * (3.0D - 2.0D * k) * (1.0D + 0.06D * Math.sin(Math.PI * k));
    }

    /** Ось доли {@code k}: пять неравных углов, одна доля подвёрнута. */
    private static final double[] LOBE_ANGLE = {0.0D, 76.0D, 139.0D, 213.0D, 289.0D};
    private static final double[] LOBE_LEN = {1.0D, 0.9D, 1.06D, 0.84D, 0.96D};

    /** Точка доли: s — от сердцевины к краю, l — поперёк (−1…1); доля чашей выгнута к зрителю. */
    private static Vec3 lobePoint(Cast c, int k, double sPos, double l, double t, double depth) {
        Vec3[] f = flowerBasis(c, t);
        double op = open(t);
        double breath = 1.0D + 0.04D * Math.sin(t * Math.PI * 2.0D / 28.0D + k * 1.3D);
        double rMax = 5.0D * c.scale * LOBE_LEN[k] * breath;
        double rc = 0.9D * c.scale;
        double ang = Math.toRadians(LOBE_ANGLE[k] + 3.0D * Math.sin(t * 0.07D + k * 2.0D));
        Vec3 axis = f[1].scale(Math.cos(ang)).add(f[2].scale(Math.sin(ang)));
        Vec3 across = f[0].cross(axis).normalize();
        double r = rc + (rMax - rc) * sPos;
        // Округлая доля сливы с маленькой выемкой на конце.
        double hw = rMax * 0.43D * Math.pow(Math.sin(Math.PI * Math.min(1.0D, sPos * 0.92D + 0.04D)), 0.75D)
                * (1.0D - 0.12D * Math.exp(-Math.pow(l * 6.0D, 2.0D)) * Math.max(0.0D, sPos - 0.85D) * 6.0D);
        // Бутон: доли сложены к оси (вдоль нормали), раскрытый — чаша.
        double fold = Math.toRadians(80.0D * (1.0D - op) + (k == 3 ? 22.0D : 0.0D) * op);
        double cup = 1.4D * c.scale * sPos * sPos;
        Vec3 radial = axis.scale(Math.cos(fold)).add(f[0].scale(-Math.sin(fold)));
        return c.core.add(radial.scale(r * Math.max(0.15D, op))).add(across.scale(l * hw * Math.max(0.15D, op)))
                .add(f[0].scale(-cup * op + depth));
    }

    /** Три неравные S-нити лепестков из ядра к цели (r09): 60/40/26 лепестков, заполняются сверху. */
    private static void streams(Cast c) {
        if (!RainRules.petals(c.layer)) {
            return;
        }
        int[] counts = {64, 46, 0};
        int streams = c.layer >= 5 ? 2 : 1;
        for (int k = 0; k < streams; k++) {
            int n = Math.max(8, c.n(counts[k]));
            for (int i = 0; i < n; i++) {
                int tone = c.random.nextDouble() < 0.12D ? 2 : c.random.nextDouble() < 0.35D ? 0 : 1;
                // Первые выходят раньше: нить вытягивается из ядра, голова идёт к цели.
                c.sky.add(new SkyPetal(-1, 0.0D, 0.0D, 0.0D, 0.1D + 0.12D * c.random.nextDouble(), c.random.nextInt(4),
                        (float) ((c.random.nextDouble() - 0.5D) * 0.7D), tone, wave(c), k,
                        k * 3.0D + 11.0D * i / (double) n, (c.random.nextDouble() - 0.5D) * 0.4D,
                        0.055D + 0.01D * c.random.nextDouble()));
            }
        }
    }

    /** Попадание волны ливня: ядро удара, осколки, кольцо; обрушение — импакт-кадр, тряска, дым. */
    private static void hit(Cast c, Vec3 at, int k, Minecraft mc) {
        Vec3 ground = new Vec3(at.x, c.target != null ? c.target.y : at.y - 1.0D, at.z);
        boolean last = k == 2;
        int cross = last ? 28 : 8;
        for (int i = 0; i < cross; i++) {
            Vec3 d = new Vec3(c.random.nextGaussian(), c.random.nextGaussian() * 0.7D + (last ? 0.6D : 0.3D), c.random.nextGaussian()).normalize();
            double r = (last ? 2.6D : 0.5D) * (0.5D + 0.6D * c.random.nextDouble()) * (0.7D + 0.3D * c.scale);
            c.cores.add(new Vec3[] {at.subtract(d.scale(r)), at, at.add(d.scale(r)), new Vec3(clientTicks, last ? 1 : 0, 0)});
        }
        int shards = last ? c.n(72) : c.n(20);
        for (int i = 0; i < shards; i++) {
            double a = c.random.nextDouble() * Math.PI * 2.0D;
            double el = c.random.nextDouble() * 0.25D;
            Vec3 v = new Vec3(Math.cos(a) * Math.cos(el), Math.sin(el), Math.sin(a) * Math.cos(el))
                    .scale((0.35D + 0.4D * c.random.nextDouble()) * (last ? 1.0D : 0.7D));
            Mote m = new Mote(ground.add(0.0D, 0.3D, 0.0D), v, 10 + c.random.nextInt(5), Mote.SHARD, 0, 0.0F,
                    0.05D + 0.04D * c.random.nextDouble(), 6);
            m.drag = 0.86D;
            m.gravity = 0.03D;
            c.motes.add(m);
        }
        c.rings.add(new Ring(ground.add(0.0D, 0.05D, 0.0D), clientTicks, (last ? 5.0D : 2.6D) * c.scale, last ? 14 : 10, last ? 0.32D : 0.18D));
        if (last) {
            // Рваные лучи удара по земле: 24–36 разной длины, несимметрично.
            int cuts = Math.max(6, c.n(12));
            double base = c.random.nextDouble() * Math.PI * 2.0D;
            for (int i = 0; i < cuts; i++) {
                // Три пучка по сторонам удара, а не ровная звезда.
                double a = base + (i % 3) * 2.2D + (c.random.nextDouble() - 0.5D) * 0.6D;
                double len = (1.5D + 1.5D * c.random.nextDouble()) * c.scale;
                Vec3 d = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
                Vec3 g0 = ground.add(0.0D, 0.04D, 0.0D);
                c.cores.add(new Vec3[] {g0.add(d.scale(0.3D)), g0.add(d.scale(len * 0.5D)), g0.add(d.scale(len)), new Vec3(clientTicks, 2, 0)});
            }
            c.rings.add(new Ring(ground.add(0.0D, 0.06D, 0.0D), clientTicks + 1, 6.0D * c.scale, 8, 0.12D));
        }
        for (int i = 0; i < (last ? c.n(14) : c.n(5)); i++) {
            double a = c.random.nextDouble() * Math.PI * 2.0D;
            double r = RainRules.RAIN_RADIUS * Math.sqrt(c.random.nextDouble()) * c.scale;
            c.marks.add(new Mark(ground.add(Math.cos(a) * r, 0.03D, Math.sin(a) * r), clientTicks, 0.25D + 0.35D * c.random.nextDouble(),
                    c.random.nextDouble() * Math.PI));
        }
        if (RainRules.petals(c.layer)) {
            for (int i = 0; i < (last ? c.n(70) : c.n(18)); i++) {
                Vec3 v = new Vec3(c.random.nextGaussian(), 0.4D + c.random.nextDouble(), c.random.nextGaussian()).normalize()
                        .scale(0.15D + 0.25D * c.random.nextDouble());
                Mote m = petalMote(c, ground.add(0.0D, 0.4D, 0.0D), v, 40 + c.random.nextInt(30));
                m.tone = c.random.nextInt(5) == 0 ? 2 : c.random.nextInt(3) == 0 ? 0 : 1;
                m.turbulence = 0.006D;
                c.motes.add(m);
            }
        }
        float distance = mc.player == null ? 99.0F : (float) mc.player.position().distanceTo(at);
        if (last) {
            c.hitFinal = clientTicks;
            c.hitAt = at;
            if (c.own()) {
                ImpactFrames.trigger(at);
                SpeedLines.radial(0.5F, 0.5F, 0.6F, 6, SpeedLines.WHITE);
            }
            if (mc.player != null && distance < 24.0F) {
                float q = distance < 8.0F ? 1.0F : 1.0F - (distance - 8.0F) / 16.0F;
                CameraShakeHandler.quake(Math.max(q, c.own() ? 0.85F : 0.0F), 16);
                mc.player.level().playLocalSound(at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(),
                        net.minecraft.sounds.SoundSource.PLAYERS, 0.7F, 1.4F, false);
                mc.player.level().playLocalSound(at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP,
                        net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.55F, false);
            }
            smoke(c, ground);
        } else if (mc.player != null && distance < 16.0F) {
            CameraShakeHandler.quake(0.25F, 5);
            mc.player.level().playLocalSound(at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP,
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.6F, 1.5F, false);
        }
    }

    /** Дым манхвы: низкий вал одной массой, через 6 тиков над ним встаёт облако. */
    private static void smoke(Cast c, Vec3 ground) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.getBlockState(BlockPos.containing(ground.add(0.0D, -0.2D, 0.0D))).isAir()) {
            return;
        }
        Entity caster = mc.level.getEntity(c.entityId);
        int n = Math.max(8, c.n(24));
        double r = 2.4D * c.scale;
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2.0D * i / n + c.random.nextDouble() * 0.3D;
            Vec3 out = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
            Vec3 at = ground.add(out.scale(r * (0.45D + 0.4D * c.random.nextDouble())));
            // Мастера дым не накрывает: камера от первого лица не тонет в стене дыма.
            if (caster != null && at.distanceTo(caster.position()) < 2.8D) {
                continue;
            }
            boolean hollow = i % 3 == 0;
            double size = (0.5D + 1.0D * Math.pow(c.random.nextDouble(), 1.5D)) * (hollow ? 1.25D : 1.0D) * c.scale;
            Puff bank = new Puff(at.add(0.0D, size * 0.45D, 0.0D), out.scale(0.12D + 0.24D * c.random.nextDouble())
                    .add(0.0D, 0.01D * c.random.nextDouble(), 0.0D),
                    32 + c.random.nextInt(16), c.random.nextInt(16), size, true,
                    hollow ? 0.56F : 0.84F + 0.12F * c.random.nextFloat(), (float) (c.random.nextDouble() * 6.28D));
            bank.delay = 2 + (i % 4 == 0 ? 0 : c.random.nextInt(i % 4 * 4));
            c.puffs.add(bank);
        }
        for (int i = 0; i < n / 2; i++) {
            double a = c.random.nextDouble() * Math.PI * 2.0D;
            Vec3 at = ground.add(Math.cos(a) * r * 0.35D, 0.8D, Math.sin(a) * r * 0.35D);
            if (caster != null && at.distanceTo(caster.position()) < 2.8D) {
                continue;
            }
            Puff rise = new Puff(at, new Vec3(0.0D, 0.06D + 0.04D * c.random.nextDouble(), 0.0D), 40 + c.random.nextInt(14),
                    c.random.nextInt(16), (0.9D + 1.0D * c.random.nextDouble()) * c.scale, true, 0.74F + 0.14F * c.random.nextFloat(),
                    (float) (c.random.nextDouble() * 6.28D));
            rise.delay = 6 + c.random.nextInt(8);
            c.puffs.add(rise);
        }
    }

    /** Волна ливня лепестка: 20 % / 30 % / 50 % — морось, морось, обрушение. */
    private static int wave(Cast c) {
        double r = c.random.nextDouble();
        return r < 0.2D ? 0 : r < 0.5D ? 1 : 2;
    }

    private static Mote petalMote(Cast c, Vec3 at, Vec3 vel, int life) {
        return new Mote(at, vel, life, Mote.PETAL, c.random.nextInt(4), (float) ((c.random.nextDouble() - 0.5D) * 0.6D),
                0.06D + 0.06D * c.random.nextDouble(), 1);
    }

    private static void dust(Cast c, Vec3 feet, int n, double speed) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.getBlockState(BlockPos.containing(feet.add(0.0D, -0.2D, 0.0D))).isAir()) {
            return;
        }
        for (int i = 0; i < n; i++) {
            double a = c.random.nextDouble() * Math.PI * 2.0D;
            Vec3 out = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
            c.puffs.add(new Puff(feet.add(out.scale(0.3D)).add(0.0D, 0.1D, 0.0D), out.scale(speed * (0.6D + 0.8D * c.random.nextDouble())),
                    16 + c.random.nextInt(8), c.random.nextInt(16), 0.22D + 0.18D * c.random.nextDouble(), false, 0.62F, 0.0F));
        }
    }

    private static Vec3 windRibbon(Cast c, Vec3 at, Vec3 vel, int life, double size) {
        Mote m = new Mote(at, vel, life, Mote.WIND_RIBBON, 0, 0.0F, size, 10);
        m.drag = 0.9D;
        m.turbulence = 0.03D;
        c.motes.add(m);
        return at;
    }

    // ------------------------------------------------------------------ тело

    private static Vec3 forward(float yaw) {
        Vec3 f = Vec3.directionFromRotation(0.0F, yaw);
        return new Vec3(f.x, 0.0D, f.z).normalize();
    }

    /** Правое плечо (правая рука при взгляде (−sin, cos) смотрит в (−cos, −sin)). */
    private static Vec3 shoulder(Entity e, float yaw) {
        double r = Math.toRadians(yaw);
        return e.position().add(0.0D, 1.38D, 0.0D).add(-Math.cos(r) * 0.34D, 0.0D, -Math.sin(r) * 0.34D);
    }

    private static float bodyYaw(Entity e) {
        return e instanceof LivingEntity le ? le.yBodyRot : e.getYRot();
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
        return new ModelPart[] {m.head, m.hat, m.body, m.jacket, m.rightArm, m.rightSleeve, m.leftArm, m.leftSleeve,
                m.rightLeg, m.rightPants, m.leftLeg, m.leftPants};
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
            int t = c.t();
            Entity e = mc.level.getEntity(c.entityId);
            if (c.targetId >= 0 && mc.level.getEntity(c.targetId) instanceof LivingEntity tg && tg.isAlive()) {
                c.target = tg.position();
            }
            if (e != null) {
                body(c, e, t, mc);
            }
            if (c.released) {
                after(c, e, t, mc);
            }
            tickMotes(c);
            tickSky(c, t);
            if (t > RainRules.END + 40 || t > 420) {
                it.remove();
            }
        }
    }

    /** Стойка, уколы, ветер от рук, натяжение замаха — всё, что исходит от тела мастера. */
    private static void body(Cast c, Entity e, int t, Minecraft mc) {
        float yaw = bodyYaw(e);
        Vec3 f = forward(yaw);
        Vec3 feet = e.position();
        if (t == 1) {
            dust(c, feet, c.n(6) + 2, 0.1D);
        }
        // Стойка (r01): меч к камере; несколько медленных лепестков.
        if (t == RainRules.STANCE_HOLD) {
            windRibbon(c, shoulder(e, yaw).add(f.scale(0.6D)), f.scale(0.3D).add(0.0D, 0.03D, 0.0D), 12, 0.07D);
            dust(c, feet, c.n(6), 0.08D);
        }
        if (RainRules.petals(c.layer) && t > 2 && t < RainRules.FLURRY && t % 3 == 0) {
            Mote m = petalMote(c, feet.add((c.random.nextDouble() - 0.5D) * 1.6D, 0.6D + 1.2D * c.random.nextDouble(),
                    (c.random.nextDouble() - 0.5D) * 1.6D), new Vec3(0.01D, -0.004D, 0.006D), 40);
            m.tone = 0;
            m.drag = 0.98D;
            m.turbulence = 0.003D;
            c.motes.add(m);
        }
        // Уколы в шесть сторон (r02): на каждом пике — копия руки, след клинка, ветер из кисти.
        while (c.nextPeak < RainRules.PEAKS.length && t >= RainRules.PEAKS[c.nextPeak]) {
            int k = c.nextPeak++;
            Vec3 d = RainRules.thrust(c.eyes() ? e.getYRot() : yaw, k);
            if (c.eyes()) {
                // От первого лица веер уже (половина угла): иначе все уколы уходят за край кадра.
                double[] dd = RainRules.DIRS[RainRules.ORDER[k % RainRules.ORDER.length]];
                d = Vec3.directionFromRotation((float) (-dd[1] * 0.55D), e.getYRot() + (float) (dd[0] * 0.5D));
            }
            // От первого лица след укола начинается дальше от глаз: иначе он целиком тает у камеры.
            boolean fpOwn = c.eyes();
            Vec3 hand = fpOwn ? e.getEyePosition().add(0.0D, -0.35D, 0.0D).add(d.scale(2.2D)) : shoulder(e, yaw).add(d.scale(0.62D));
            c.thrusts.add(new Thrust(hand, d, clientTicks));
            if (RainRules.afterimages(c.layer) && e instanceof AbstractClientPlayer player
                    && mc.getEntityRenderDispatcher().getRenderer(player) instanceof PlayerRenderer renderer) {
                if (c.skin == null) {
                    c.skin = player.getSkin().texture();
                }
                PartPose[] pose = capture(renderer.getModel());
                double[] dd = RainRules.DIRS[RainRules.ORDER[k % RainRules.ORDER.length]];
                for (int j = 4; j <= 5; j++) {
                    PartPose o = pose[j];
                    // API: reference/minecraft-src/net/minecraft/client/model/geom/PartPose.java#offsetAndRotation
                    pose[j] = PartPose.offsetAndRotation(o.x, o.y, o.z, (float) Math.toRadians(-90.0D - dd[1]),
                            (float) Math.toRadians(dd[0]), 0.0F);
                }
                c.ghosts.add(new Ghost(player.position(), yaw, clientTicks, 6, pose, true, 0xF2F6FA, 0.6F));
                c.ghosts.add(new Ghost(player.position(), yaw, clientTicks + 1, 6, pose, true, 0xDCE8F2, 0.42F));
                c.ghosts.add(new Ghost(player.position(), yaw, clientTicks + 2, 6, pose, true, 0xC9DDEA, 0.18F));
            }
            int winds = t >= RainRules.WIND_RISE ? 3 : 2;
            for (int i = 0; i < winds; i++) {
                Vec3 v = d.add(c.random.nextGaussian() * 0.25D, c.random.nextGaussian() * 0.2D, c.random.nextGaussian() * 0.25D)
                        .normalize().scale(0.25D + 0.3D * c.random.nextDouble());
                windRibbon(c, hand, v, 12, 0.05D + 0.04D * c.scale);
            }
            if (k % 2 == 0) {
                dust(c, feet, 2, 0.1D);
            }
            if (RainRules.petals(c.layer)) {
                for (int i = 0; i < 3; i++) {
                    Mote m = petalMote(c, hand.add(d.scale(0.8D * c.random.nextDouble())),
                            d.scale(0.08D + 0.1D * c.random.nextDouble()).add(c.random.nextGaussian() * 0.04D, 0.02D, c.random.nextGaussian() * 0.04D), 26);
                    m.tone = 0;
                    m.turbulence = 0.01D;
                    c.motes.add(m);
                }
            }
            if (c.eyes()) {
                // От первого лица укол читается линиями скорости: точка схождения прыгает туда,
                // куда колет клинок, — шесть сторон видны на экране (r02).
                double[] dd = RainRules.DIRS[RainRules.ORDER[k % RainRules.ORDER.length]];
                float cx = (float) (0.5D + 0.42D * Math.sin(Math.toRadians(dd[0])));
                float cy = (float) (0.5D - 0.6D * Math.sin(Math.toRadians(dd[1])));
                SpeedLines.radial(cx, cy, 0.3F + 0.05F * (k / 6), 3, SpeedLines.WHITE);
            } else if (c.own() && k % 6 == 0) {
                SpeedLines.radial(0.5F, 0.5F, 0.35F + 0.1F * (k / 6), 5, SpeedLines.WHITE);
            }
        }
        // Ветер поднимается от рук (r03): семь неравных восходящих лент, потом две широкие дуги.
        int[] windAt = {40, 44, 47, 50, 53};
        for (int i = 0; i < windAt.length; i++) {
            if (t == windAt[i] && i < Math.max(2, c.n(7))) {
                double a0 = Math.toRadians(yaw + 90.0D) + i * 2.3D + c.random.nextDouble() * 0.6D;
                c.winds.add(new WindArc(feet, clientTicks, a0, i % 3 == 1 ? -1 : 1, (1.2D + 1.6D * c.random.nextDouble()) * c.scale,
                        1.2D, 1.2D + 2.6D * c.scale, (c.random.nextDouble() - 0.5D) * 0.35D, 18, 0.09D + 0.05D * c.scale, 0.45D + 0.2D * c.random.nextDouble()));
            }
        }
        if ((t == 50 || t == 55) && c.layer >= 2) {
            double a0 = Math.toRadians(yaw + (t == 50 ? 40.0D : 200.0D));
            c.winds.add(new WindArc(feet, clientTicks, a0, t == 50 ? 1 : -1, 3.2D * c.scale, 0.5D, 1.6D, 0.12D, 16, 0.28D * c.scale, 0.55D));
        }
        if ((t == 36 || t == 44 || t == 52) && c.layer >= 1) {
            dust(c, feet, c.n(8) + 1, 0.16D);
        }
        // Натяжение замаха (r04): лепестки и ветер втягиваются к клинку.
        if (t >= RainRules.LOAD && t < RainRules.RELEASE) {
            Vec3 tip = shoulder(e, yaw).add(0.0D, 0.8D, 0.0D).subtract(f.scale(0.4D));
            if (RainRules.petals(c.layer)) {
                for (int i = 0; i < 2; i++) {
                    double a = c.random.nextDouble() * Math.PI * 2.0D;
                    Vec3 at = tip.add(Math.cos(a) * 2.0D, (c.random.nextDouble() - 0.5D) * 1.4D, Math.sin(a) * 2.0D);
                    Mote m = petalMote(c, at, tip.subtract(at).scale(0.14D), 9);
                    m.drag = 1.0D;
                    m.tone = 1;
                    c.motes.add(m);
                }
            }
            if (t % 2 == 0) {
                double a = c.random.nextDouble() * Math.PI * 2.0D;
                Vec3 at = tip.add(Math.cos(a) * 1.6D, 0.3D, Math.sin(a) * 1.6D);
                windRibbon(c, at, tip.subtract(at).scale(0.16D), 8, 0.05D);
            }
        }
        // Мастер опускает меч (возврат в стойку): тоже движение — пыль и короткий ветер.
        if (t == RainRules.AFTER + 6 && c.released) {
            dust(c, feet, c.n(6) + 1, 0.1D);
            windRibbon(c, shoulder(e, yaw).add(f.scale(0.5D)), f.scale(0.18D).add(0.0D, -0.05D, 0.0D), 12, 0.06D);
        }
        c.ghosts.removeIf(g -> clientTicks - g.born() > g.life());
        c.thrusts.removeIf(th -> clientTicks - th.born() > 4);
    }

    /** После замаха: проход, метка, иллюзия, ливень, последствия. */
    private static void after(Cast c, Entity e, int t, Minecraft mc) {
        // Проход: копии тела, пыль по пути, линии воздуха у земли (r05).
        if (t > RainRules.RELEASE && t <= RainRules.RELEASE + RainRules.DASH_TICKS + 1 && e != null) {
            if ((t == RainRules.RELEASE + 1 || t == RainRules.RELEASE + 4 || t == RainRules.RELEASE + 7) && c.dashPose != null
                    && RainRules.afterimages(c.layer)) {
                c.ghosts.add(new Ghost(e.position(), bodyYaw(e), clientTicks, 9, c.dashPose, false, 0xFFC8DE, 0.42F));
            }
            dust(c, e.position(), 2, 0.2D);
            Vec3 back = c.dir.scale(-1.0D);
            for (int i = 0; i < 1; i++) {
                Vec3 at = e.position().add(c.side.scale((c.random.nextDouble() - 0.5D) * 1.6D)).add(0.0D, 0.2D + 0.9D * c.random.nextDouble(), 0.0D);
                windRibbon(c, at, back.scale(0.35D).add(c.side.scale(c.random.nextGaussian() * 0.12D)), 14, 0.08D + 0.06D * c.scale);
            }
            if (t == RainRules.RELEASE + RainRules.DASH_TICKS + 1) {
                dust(c, e.position(), c.n(10) + 2, 0.2D);
            }
        }
        // Искры, осыпающиеся со следа.
        if (c.swing != null && t >= RainRules.RELEASE + 2 && t < RainRules.TRAIL_HOLD && RainRules.petals(c.layer) && c.random.nextInt(3) > 0) {
            for (int i = 0; i < 2; i++) {
                double u = c.random.nextDouble() * Math.min(1.0D, (t - RainRules.RELEASE) / (double) RainRules.DASH_TICKS);
                Vec3 at = c.swing[(int) (u * (c.swing.length - 1))];
                Mote m = new Mote(at, new Vec3(0.0D, -0.015D - 0.025D * c.random.nextDouble(), 0.0D), 12 + c.random.nextInt(9),
                        Mote.SPARK, 0, 0.0F, 0.035D + 0.045D * c.random.nextDouble(), 4);
                m.drag = 0.97D;
                c.motes.add(m);
            }
        }
        boolean marked = c.markTick >= 0 && c.lostTick < 0 && c.target != null;
        if (!marked) {
            return;
        }
        // Ядро ведёт живую цель, не быстрее 0,6 блока за тик.
        Vec3 want = RainRules.core(c.target, c.dir, c.layer);
        c.corePrev = c.core;
        if (c.core == null) {
            c.core = want;
            c.corePrev = want;
        } else {
            Vec3 d = want.subtract(c.core);
            double l = d.length();
            c.core = l > 0.6D ? c.core.add(d.scale(0.6D / l)) : want;
        }
        if (t == RainRules.BLOOM && RainRules.shells(c.layer) > 0) {
            bloom(c);
        }
        if (t == RainRules.STREAM) {
            streams(c);
        }
        // Медленные лепестки вокруг цели всё время иллюзии (r07).
        if (RainRules.petals(c.layer) && t > RainRules.BLOOM && t < RainRules.COHORTS[0] && t % 5 == 0) {
            for (int i = 0; i < Math.max(1, c.n(2)); i++) {
                double a = c.random.nextDouble() * Math.PI * 2.0D;
                double r = 0.5D + 3.0D * c.random.nextDouble();
                Mote m = petalMote(c, c.target.add(Math.cos(a) * r, 3.5D + 2.5D * c.random.nextDouble(), Math.sin(a) * r),
                        new Vec3(0.0D, -0.03D, 0.0D), 70 + c.random.nextInt(30));
                m.drag = 0.995D;
                m.turbulence = 0.006D;
                m.tone = c.random.nextInt(8) == 0 ? 2 : c.random.nextInt(3) == 0 ? 0 : 1;
                c.motes.add(m);
            }
        }
        if (t == RainRules.CAPTION && c.own() && c.layer >= 3) {
            TechniqueCaption.show(Component.translatable("technique.murim.twenty_four_plum.school"),
                    Component.translatable("technique.murim.twenty_four_plum.rainfall"), 24);
        }
        // Ливень: три волны срываются вниз; вертикальные ленты — столб света.
        for (int k = 0; k < RainRules.COHORTS.length; k++) {
            if (t == RainRules.COHORTS[k]) {
                for (SkyPetal s : c.sky) {
                    if (s.cohort == k && !s.dead && !s.free && s.fallTick < 0) {
                        s.fallTick = clientTicks + c.random.nextInt(2);
                        s.fallFrom = s.pos;
                        double a = c.random.nextDouble() * Math.PI * 2.0D;
                        double r = (k == 2 ? 0.7D : RainRules.RAIN_RADIUS * c.scale) * Math.sqrt(c.random.nextDouble());
                        s.fallTo = c.target.add(Math.cos(a) * r, 0.2D + 1.5D * c.random.nextDouble(), Math.sin(a) * r);
                    }
                }
                int beams = Math.max(3, c.n(k == 0 ? 6 : k == 1 ? 10 : 14));
                for (int i = 0; i < beams; i++) {
                    double a = c.random.nextDouble() * Math.PI * 2.0D;
                    double r = RainRules.RAIN_RADIUS * c.scale * Math.sqrt(c.random.nextDouble());
                    c.beams.add(new Beam(Math.cos(a) * r, Math.sin(a) * r, clientTicks + c.random.nextInt(4),
                            (2.0D + 2.0D * c.random.nextDouble()) * c.scale, 0.03D + 0.05D * c.random.nextDouble(),
                            9.0D + 3.0D * c.random.nextDouble(), c.random.nextInt(3) == 0));
                }
            }
            // Обрушение (r10): на касании последней волны — короткий белый столб на всю высоту.
            if (k == 2 && t == RainRules.contact(2) - 1 && RainRules.column(c.layer)) {
                for (int i = 0; i < c.n(26); i++) {
                    double a = c.random.nextDouble() * Math.PI * 2.0D;
                    double r = 1.1D * c.scale * Math.sqrt(c.random.nextDouble());
                    c.beams.add(new Beam(Math.cos(a) * r, Math.sin(a) * r, clientTicks, (8.0D + 3.0D * c.random.nextDouble()) * c.scale,
                            0.05D + 0.07D * c.random.nextDouble(), 11.0D, true));
                }
            }
        }
        // Камера мастера: после прохода плавно оглядывается на цель и поднимает взгляд к ядру.
        if (c.own() && mc.player != null && t >= RainRules.RELEASE + RainRules.DASH_TICKS && t <= RainRules.AFTER + 10) {
            Vec3 eye = mc.player.getEyePosition();
            Vec3 to = c.target.subtract(eye);
            float wantYaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
            float dy = Mth.wrapDegrees(wantYaw - mc.player.getYRot());
            if (t < RainRules.BLOOM + 6) {
                mc.player.setYRot(mc.player.getYRot() + dy * 0.24F);
            }
            float wantPitch;
            if (t >= RainRules.BLOOM && t < RainRules.COHORTS[1] && c.core != null) {
                Vec3 up = c.core.subtract(eye);
                wantPitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(up.y, Math.hypot(up.x, up.z))) * 0.72D, -38.0D, 0.0D);
            } else {
                wantPitch = 4.0F;
            }
            float k = t < RainRules.BLOOM ? 0.0F : t < RainRules.COHORTS[1] ? 0.06F : 0.12F;
            mc.player.setXRot(mc.player.getXRot() + (wantPitch - mc.player.getXRot()) * k);
        }
    }

    private static void tickMotes(Cast c) {
        for (Mote m : c.motes) {
            if (m.trail.length > 1) {
                System.arraycopy(m.trail, 0, m.trail, 1, m.trail.length - 1);
                m.trail[0] = m.pos;
                m.count = Math.min(m.trail.length, m.count + 1);
            }
            m.prev = m.pos;
            m.age++;
            Vec3 v = m.vel.scale(m.drag).add(0.0D, -m.gravity, 0.0D);
            if (m.turbulence > 0.0D) {
                // Порывы: поле, меняющееся во времени, — не белый шум.
                double ph = m.age * 0.21D + m.cell * 1.7D + m.pos.x * 0.5D;
                v = v.add(Math.sin(ph) * m.turbulence, Math.sin(ph * 1.3D + 1.1D) * m.turbulence * 0.4D, Math.cos(ph * 0.9D + m.pos.z * 0.5D) * m.turbulence);
            }
            m.vel = v;
            m.pos = m.pos.add(v);
        }
        c.motes.removeIf(m -> m.age >= m.life);
        for (Puff p : c.puffs) {
            if (p.delay > 0) {
                p.delay--;
                continue;
            }
            p.prev = p.pos;
            p.age++;
            p.vel = p.smoke ? new Vec3(p.vel.x * 0.93D, p.vel.y * 0.97D, p.vel.z * 0.93D) : new Vec3(p.vel.x * 0.86D, p.vel.y * 0.9D, p.vel.z * 0.86D);
            p.pos = p.pos.add(p.vel);
        }
        c.puffs.removeIf(p -> p.age >= p.life);
        c.winds.removeIf(w -> clientTicks - w.born() > w.life() + 10);
        c.beams.removeIf(b -> clientTicks - b.born() > 5);
        c.rings.removeIf(r -> clientTicks - r.born() > r.life());
        c.marks.removeIf(m -> clientTicks - m.born() > 64);
        c.cores.removeIf(x -> clientTicks - x[3].x > (x[3].y > 1.5D ? 13 : 6));
    }

    /** Лепестки ядра: орбиты (дыхание, качание), нити к цели, падение. */
    private static void tickSky(Cast c, int t) {
        if (c.sky.isEmpty() || c.core == null || c.target == null) {
            for (SkyPetal s : c.sky) {
                s.prev = s.pos;
            }
            return;
        }
        for (SkyPetal s : c.sky) {
            s.prev = s.pos;
            if (s.dead) {
                continue;
            }
            if (s.free) {
                s.freeAge++;
                s.vel = s.vel.scale(0.96D).add(0.0D, -0.004D, 0.0D);
                s.pos = s.pos.add(s.vel);
                if (s.freeAge > 30) {
                    s.dead = true;
                }
                continue;
            }
            if (s.fallTick >= 0) {
                if (clientTicks < s.fallTick) {
                    continue;
                }
                double u = Mth.clamp((clientTicks - s.fallTick) / (double) RainRules.FALL, 0.0D, 1.0D);
                s.pos = fall(s, u);
                if (u >= 1.0D) {
                    s.dead = true;
                }
                continue;
            }
            s.pos = s.stream >= 0 ? streamPoint(c, s, t) : shellPoint(c, s, t);
            if (s.prev == Vec3.ZERO) {
                s.prev = s.pos;
            }
        }
        if (t > RainRules.contact(2) + 4) {
            c.sky.removeIf(s -> s.dead);
        }
    }

    /** Падение: высота по u² (ускорение), по горизонтали — плавное схождение к точке удара. */
    private static Vec3 fall(SkyPetal s, double u) {
        double k = u * u * (3.0D - 2.0D * u);
        double y = s.fallFrom.y + (s.fallTo.y - s.fallFrom.y) * u * u;
        return new Vec3(s.fallFrom.x + (s.fallTo.x - s.fallFrom.x) * k, y, s.fallFrom.z + (s.fallTo.z - s.fallFrom.z) * k);
    }

    private static Vec3 shellPoint(Cast c, SkyPetal s, double t) {
        // Лепесток живёт внутри своей доли: медленно колышется по s и l, чуть выходит из плоскости.
        double sPos = Mth.clamp(s.a + 0.04D * Math.sin(t * 0.09D + s.w), 0.05D, 1.0D);
        double l = Mth.clamp(s.e + 0.08D * Math.sin(t * 0.11D + s.w * 1.7D), -1.0D, 1.0D);
        // Объём: лепестки расходятся в толщину чаши до ±0,9 блока у сердца, тоньше к краю.
        double depth = (0.9D - 0.6D * s.a) * Math.sin(s.w * 3.0D) + 0.08D * Math.sin(t * 0.13D + s.w);
        return lobePoint(c, s.shell, sPos, l, t, depth * c.scale);
    }

    /** S-нить: из основания своей доли к груди цели, боковые выносы +2,8 → −2,0 → +1,2 → 0. */
    private static Vec3 streamPoint(Cast c, SkyPetal s, double t) {
        double moving = Math.min(t, RainRules.STILL) - RainRules.STREAM - s.delay;
        // Голова доходит до 0,85 к t120, дальше нить сжимается и почти стоит.
        // Голова доходит до груди к t120; следующие подтягиваются и висят плотной нитью.
        double u = moving <= 0.0D ? 0.0D : Math.min(1.0D - 0.5D * (s.delay / 16.0D) * (s.delay / 16.0D), moving * s.uSpeed);
        s.u = u;
        return streamAt(c, s.stream, u, t).add(streamSide(c, s.stream).scale(s.lane))
                .add(0.0D, 0.06D * Math.sin(t * 0.21D + s.lane * 11.0D), 0.0D);
    }

    private static Vec3 streamSide(Cast c, int k) {
        double rot = Math.toRadians(new double[] {0.0D, 125.0D, 235.0D}[k]);
        return c.side.scale(Math.cos(rot)).add(c.dir.scale(Math.sin(rot)));
    }

    /** Точка нити k на доле пути u (сплайн Катмулла — Рома по четырём выносам). */
    private static Vec3 streamAt(Cast c, int k, double u, double t) {
        Vec3 from = lobePoint(c, new int[] {0, 2, 4}[k], 0.15D, 0.0D, t, 0.0D);
        Vec3 to = c.target.add(0.0D, 1.5D, 0.0D);
        Vec3 b = streamSide(c, k);
        double[] off = {0.0D, 1.1D, 0.2D, -0.9D, 0.0D};
        double scale = k == 0 ? 1.0D : k == 1 ? 0.8D : 0.6D;
        double x = u * 4.0D;
        int i = Math.min(3, (int) x);
        double f = x - i;
        double[] o = new double[4];
        for (int j = 0; j < 4; j++) {
            o[j] = off[Mth.clamp(i - 1 + j, 0, 4)] * scale;
        }
        double side = 0.5D * ((2.0D * o[1]) + (-o[0] + o[2]) * f + (2.0D * o[0] - 5.0D * o[1] + 4.0D * o[2] - o[3]) * f * f
                + (-o[0] + 3.0D * o[1] - 3.0D * o[2] + o[3]) * f * f * f);
        // Колыхание нити: живая, не проволока.
        side += 0.15D * Math.sin(t * 0.12D + u * 6.0D + k);
        return from.lerp(to, u).add(b.scale(side * Math.sin(Math.PI * Math.min(1.0D, u * 1.1D))));
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
                float t = c.t() + partial;
                models(mc, c, ps, buffers, partial);
                PoseStack.Pose pose = ps.last();
                VertexConsumer air = buffers.getBuffer(MurimRenderTypes.airBand());
                thrusts(c, pose, camera, air, partial);
                winds(c, pose, camera, air, partial);
                ribbons(c, pose, camera, air, partial);
                swing(c, pose, camera, air, t);
                if (c.target != null && c.markTick >= 0) {
                    groundLight(c, pose, air, t);
                    coreRibbons(c, pose, camera, air, t);
                    rain(c, pose, camera, air, partial);
                }
                impact(c, pose, camera, air, partial);
                buffers.endBatch(MurimRenderTypes.airBand());
                puffs(c, pose, camera, buffers, partial);
                petals(c, pose, camera, buffers, partial, t);
            }
        } finally {
            ps.popPose();
        }
    }

    /** Остаточные образы: рука с мечом на каждом уколе (r02) и тело на проходе. */
    private static void models(Minecraft mc, Cast c, PoseStack ps, MultiBufferSource.BufferSource buffers, float partial) {
        if (c.ghosts.isEmpty() || !(mc.level.getEntity(c.entityId) instanceof AbstractClientPlayer player)) {
            return;
        }
        // От первого лица — только копии руки (r02: веер рук перед глазами); копии тела закрывали бы экран.
        boolean fpOwn = c.eyes();
        EntityRenderer<? super AbstractClientPlayer> r = mc.getEntityRenderDispatcher().getRenderer(player);
        if (!(r instanceof PlayerRenderer renderer)) {
            return;
        }
        PlayerModel<AbstractClientPlayer> model = renderer.getModel();
        ModelPart[] parts = parts(model);
        PartPose[] saved = new PartPose[parts.length];
        boolean[] vis = new boolean[parts.length];
        for (int i = 0; i < parts.length; i++) {
            saved[i] = parts[i].storePose();
            vis[i] = parts[i].visible;
        }
        ResourceLocation skin = c.skin != null ? c.skin : player.getSkin().texture();
        RenderType type = RenderType.entityTranslucent(skin);
        try {
            for (Ghost g : c.ghosts) {
                float k = (clientTicks - g.born() + partial) / g.life();
                if (k < 0.0F || k > 1.0F || fpOwn && !g.armOnly()) {
                    continue;
                }
                for (int i = 0; i < parts.length; i++) {
                    parts[i].loadPose(g.pose()[i]);
                    // Копия укола — только правая рука с рукавом.
                    parts[i].visible = !g.armOnly() || i == 4 || i == 5;
                }
                draw(model, ps, buffers.getBuffer(type), g.pos(), g.yaw(), g.alpha() * (1.0F - k) * (1.0F - k), g.rgb());
            }
            buffers.endBatch(type);
        } finally {
            for (int i = 0; i < parts.length; i++) {
                parts[i].loadPose(saved[i]);
                parts[i].visible = vis[i];
            }
        }
    }

    /** Как LivingEntityRenderer: поворот корпуса, отражение осей, масштаб игрока, подъём на 1,501. */
    private static void draw(PlayerModel<?> model, PoseStack ps, VertexConsumer v, Vec3 at, float yaw, float alpha, int rgb) {
        if (alpha <= 0.01F) {
            return;
        }
        ps.pushPose();
        try {
            // API: reference/minecraft-src/net/minecraft/client/renderer/entity/LivingEntityRenderer.java#render
            ps.translate(at.x, at.y, at.z);
            ps.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));
            ps.scale(-1.0F, -1.0F, 1.0F);
            ps.scale(0.9375F, 0.9375F, 0.9375F);
            ps.translate(0.0F, -1.501F, 0.0F);
            int colour = ((int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F) << 24) | rgb;
            model.renderToBuffer(ps, v, 0x00F000F0, OverlayTexture.NO_OVERLAY, colour);
        } finally {
            ps.popPose();
        }
    }

    /** След клинка каждого укола: тонкая холодная полоса с белой кромкой, вытянута по ходу. */
    private static void thrusts(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Thrust th : c.thrusts) {
            float age = clientTicks - th.born() + partial;
            float a = (float) PlumVfx.curve(age, 0.0, 1.0, 1.0, 0.95, 4.0, 0.0);
            if (a <= 0.0F) {
                continue;
            }
            double reach = 2.4D + 1.3D * Mth.clamp(age / 1.5D, 0.0D, 1.0D);
            // Точка прокола: короткая белая звёздочка на острие.
            if (age < 3.0F) {
                Vec3 tip = th.hand().add(th.dir().scale(reach));
                Vec3 up = th.dir().cross(new Vec3(0.0D, 1.0D, 0.0D));
                up = up.lengthSqr() < 1.0E-6D ? c.side : up.normalize();
                Vec3 vv = up.cross(th.dir()).normalize();
                float sa = (float) PlumVfx.curve(age, 0.0, 1.0, 3.0, 0.0);
                for (Vec3 ax : new Vec3[] {up, vv}) {
                    Vec3[] q = {tip.subtract(ax.scale(0.22D)), tip, tip.add(ax.scale(0.22D))};
                    fstrip(v, pose, camera, q, new double[] {0.0D, 0.03D, 0.0D}, 0.9F * sa, EDGE);
                }
            }
            Vec3 tail = th.hand().subtract(th.dir().scale(0.15D));
            Vec3[] p = {tail, th.hand().add(th.dir().scale(reach * 0.45D)), th.hand().add(th.dir().scale(reach))};
            double[] w = {0.0D, 0.06D, 0.0D};
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.6D), 0.14F * a, COLD);
            fstrip(v, pose, camera, p, w, 0.55F * a, WIND);
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.3D), 0.95F * a, EDGE);
        }
    }

    /** Восходящие ленты ветра (r03): хвост — та же спираль в прошлые тики. */
    private static void winds(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (WindArc w : c.winds) {
            double age = clientTicks - w.born() + partial;
            if (age <= 0.0D) {
                continue;
            }
            double head = Math.min(age, w.life());
            double tail = Math.max(0.0D, age - 9.0D);
            if (head <= tail) {
                continue;
            }
            int n = 14;
            Vec3[] p = new Vec3[n + 1];
            double[] wd = new double[n + 1];
            for (int i = 0; i <= n; i++) {
                double u = i / (double) n;
                p[i] = w.at(tail + (head - tail) * u);
                wd[i] = w.width() * Math.sin(Math.PI * Math.min(1.0D, u * 0.95D + 0.04D)) * (0.5D + 0.5D * u);
            }
            float a = (float) PlumVfx.curve(age / (w.life() + 9.0D), 0.0, 0.0, 0.12, 1.0, 0.7, 0.8, 1.0, 0.0);
            fstrip(v, pose, camera, p, PlumVfx.scale(wd, 2.0D), 0.1F * a, COLD);
            fstrip(v, pose, camera, p, wd, 0.42F * a, WIND);
            fstrip(v, pose, camera, p, PlumVfx.scale(wd, 0.28D), 0.9F * a, EDGE);
        }
    }

    /** Ленты ветра с хвостами, искры следа и осколки удара. */
    private static void ribbons(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Mote m : c.motes) {
            if (m.kind == Mote.PETAL || m.count < 2) {
                continue;
            }
            int n = m.count;
            Vec3[] p = new Vec3[n + 1];
            double[] w = new double[n + 1];
            p[0] = m.prev.lerp(m.pos, partial);
            for (int i = 1; i <= n; i++) {
                p[i] = m.trail[i - 1];
            }
            for (int i = 0; i <= n; i++) {
                w[i] = m.size * Math.sin(Math.PI * Math.min(1.0D, 0.08D + i / (double) n * 0.95D));
            }
            float life = (m.age + partial) / m.life;
            float a = (float) PlumVfx.curve(life, 0.0, 0.2, 0.12, 1.0, 0.6, 0.8, 1.0, 0.0);
            if (m.kind == Mote.WIND_RIBBON) {
                fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.0D), 0.1F * a, COLD);
                fstrip(v, pose, camera, p, w, 0.38F * a, WIND);
                fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.25D), 0.85F * a, EDGE);
            } else {
                fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.2D), 0.18F * a, TRAIL_RIM);
                fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.4D), 0.95F * a, TRAIL_CORE);
            }
        }
    }

    /**
     * Огромный след замаха (r04–r05): прорисовывается вместе с рывком, висит, гаснет. Тонкое белое
     * ядро внутри насыщенной розовой полосы и широкой бледной оболочки.
     */
    private static void swing(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float t) {
        if (c.swing == null) {
            return;
        }
        float s = t - RainRules.RELEASE;
        if (s < 0.0F || t > RainRules.TRAIL_GONE) {
            return;
        }
        // Прорисовка как у рывка: резкий срыв и торможение.
        double x = Mth.clamp(s / (RainRules.DASH_TICKS - 1.0D), 0.0D, 1.0D);
        double drawn = 1.0D - Math.pow(1.0D - x, 3.0D);
        float fade = (float) Mth.clamp((RainRules.TRAIL_GONE - t) / (double) (RainRules.TRAIL_GONE - RainRules.TRAIL_HOLD), 0.0D, 1.0D);
        int n = c.swing.length - 1;
        int m = Math.max(2, (int) Math.ceil(n * drawn));
        Vec3[] p = new Vec3[m + 1];
        double[] w = new double[m + 1];
        float[] a = new float[m + 1];
        boolean pink = RainRules.petals(c.layer);
        for (int i = 0; i <= m; i++) {
            double u = i / (double) n;
            p[i] = c.swing[Math.min(n, i)];
            // Сужение к обоим концам; голова ярче, пока рисуется.
            double taper = Math.sin(Math.PI * Math.min(1.0D, u * 1.02D + 0.01D));
            w[i] = (0.1D + 0.05D * c.scale) * Math.pow(taper, 0.6D);
            double headGlow = x < 1.0D ? Math.max(0.0D, 1.0D - (drawn - u) * 4.0D) : 0.0D;
            a[i] = (float) Mth.clamp((0.75D + 0.25D * headGlow) * fade, 0.0D, 1.0D);
        }
        a = near(p, a, camera);
        VfxColour rim = pink ? TRAIL_RIM : COLD;
        VfxColour body = pink ? TRAIL_BODY : WIND;
        PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 3.0D), PlumVfx.scaled(a, 0.1F), rim);
        PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 1.8D), PlumVfx.scaled(a, 0.35F), rim);
        PlumVfx.stripVar(v, pose, camera, p, w, PlumVfx.scaled(a, 0.85F), body);
        PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 0.28D), a, TRAIL_CORE);
        // Резкий замах (r04): острый розовый полумесяц 4,5 блока по диагонали, живёт 3 тика.
        if (s < 3.0F && c.origin != null) {
            float ca = (float) PlumVfx.curve(s, 0.0, 1.0, 1.0, 1.0, 3.0, 0.0);
            Vec3 o = c.origin.add(0.0D, 1.3D, 0.0D).add(c.dir.scale(0.6D));
            Vec3 up = new Vec3(0.0D, 0.8D, 0.0D).add(c.side.scale(0.6D));
            int cn = 18;
            Vec3[] q = new Vec3[cn + 1];
            double[] qw = new double[cn + 1];
            double sweep = Mth.clamp(s / 1.2D, 0.15D, 1.0D);
            for (int i = 0; i <= cn; i++) {
                double u = i / (double) cn * sweep;
                double ang = Math.toRadians(125.0D - 160.0D * u);
                q[i] = o.add(up.scale(Math.sin(ang) * 1.6D)).add(c.dir.scale(Math.cos(ang) * 1.6D + 0.4D))
                        .add(c.side.scale(-0.9D * Math.cos(ang)));
                qw[i] = 0.4D * Math.pow(Math.sin(Math.PI * i / cn), 1.6D);
            }
            fstrip(v, pose, camera, q, PlumVfx.scale(qw, 1.6D), 0.18F * ca, TRAIL_RIM);
            fstrip(v, pose, camera, q, qw, 0.7F * ca, TRAIL_BODY);
            fstrip(v, pose, camera, q, PlumVfx.scale(qw, 0.25D), 0.95F * ca, TRAIL_CORE);
        }
        // Белая зубчатая вспышка в момент резкой смены движения (r04).
        if (s < 3.5F) {
            Vec3 at = c.swing[Math.max(0, Math.min(n, (int) (n * 0.12D)))];
            float fa = (float) PlumVfx.curve(s, 0.0, 1.0, 3.5, 0.0);
            for (int k = 0; k < 7; k++) {
                double ang = k * 2.0D * Math.PI / 7.0D + 0.4D * Math.sin(k * 3.1D);
                double len = (k % 2 == 0 ? 0.9D : 0.5D) * (0.6D + 0.4D * s / 3.5D);
                Vec3 d = c.side.scale(Math.cos(ang)).add(0.0D, Math.sin(ang), 0.0D);
                Vec3[] q = {at, at.add(d.scale(len * 0.5D)), at.add(d.scale(len))};
                double[] qw = {0.07D, 0.035D, 0.0D};
                fstrip(v, pose, camera, q, qw, 0.95F * fa, EDGE);
            }
        }
    }

    /** Розовый свет на земле под иллюзией (r09): концентрические плоские кольца. */
    private static void groundLight(Cast c, PoseStack.Pose pose, VertexConsumer v, float t) {
        float a = (float) PlumVfx.curve(t, RainRules.MARK, 0.0, RainRules.BLOOM_FULL, 1.0, RainRules.contact(2), 1.0,
                RainRules.AFTER + 10, 0.4, RainRules.END, 0.0);
        if (a <= 0.01F) {
            return;
        }
        double rMax = 5.5D * c.scale;
        Vec3 g = new Vec3(c.target.x, Math.floor(c.target.y + 0.01D) + 0.02D, c.target.z);
        for (int ring = 0; ring < 9; ring++) {
            double r = rMax * (ring + 0.5D) / 9.0D;
            int n = 28;
            Vec3[] p = new Vec3[n + 1];
            double[] w = new double[n + 1];
            for (int i = 0; i <= n; i++) {
                double ang = i * Math.PI * 2.0D / n;
                p[i] = g.add(Math.cos(ang) * r, 0.0D, Math.sin(ang) * r);
                w[i] = rMax / 9.0D;
            }
            PlumVfx.flatStrip(v, pose, p, w, 0.07F * a * (1.0F - ring * 0.1F), GROUND);
        }
    }

    /** Кромки долей (тонкая светящаяся линия по краю) и тычинки сердцевины (r08). */
    private static void coreRibbons(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float t) {
        if (c.core == null || c.coreBorn < 0) {
            return;
        }
        float grow = (float) Mth.clamp((t - RainRules.BLOOM) / 10.0D, 0.0D, 1.0D);
        float out = (float) Mth.clamp(1.0D - (t - RainRules.COHORTS[1]) / 8.0D, 0.0D, 1.0D)
                * (c.lostTick >= 0 ? Mth.clamp(1.0F - (clientTicks - c.lostTick) / 12.0F, 0.0F, 1.0F) : 1.0F);
        float a = grow * out;
        if (a <= 0.0F) {
            return;
        }
        for (int k = 0; k < 5; k++) {
            int n = 36;
            Vec3[] p = new Vec3[n + 1];
            double[] w = new double[n + 1];
            for (int i = 0; i <= n; i++) {
                double u = i / (double) n;
                // Обход края: вверх по левой кромке к выемке, вниз по правой.
                double sPos = u < 0.5D ? u * 2.0D : (1.0D - u) * 2.0D;
                double l = u < 0.5D ? -1.0D : 1.0D;
                p[i] = lobePoint(c, k, Math.min(1.0D, sPos), l, t, 0.0D);
                w[i] = (0.045D + 0.03D * sPos) * c.scale;
            }
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.4D), 0.16F * a, LOBE_OUT);
            fstrip(v, pose, camera, p, w, 0.62F * a, LOBE_IN);
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.32D), 0.85F * a, HEART);
        }
        // Тычинки: тонкие белые нити из сердца с розовой точкой на конце.
        Vec3[] f = flowerBasis(c, t);
        int st = Math.max(6, c.n(22));
        double op = open(t);
        for (int k = 0; k < st; k++) {
            double ang = k * 2.0D * Math.PI / st + 0.3D * Math.sin(k * 2.7D);
            double len = (1.4D + 0.9D * ((k * 0.618D) % 1.0D)) * c.scale * op;
            Vec3 dirS = f[1].scale(Math.cos(ang)).add(f[2].scale(Math.sin(ang))).scale(0.85D).add(f[0].scale(-0.5D)).normalize();
            Vec3 sway = f[0].scale(0.1D * Math.sin(t * 0.15D + k));
            Vec3[] p = {c.core, c.core.add(dirS.scale(len * 0.55D)).add(sway.scale(0.5D)), c.core.add(dirS.scale(len)).add(sway)};
            double[] w = {0.035D, 0.022D, 0.012D};
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.0D), 0.2F * a, LOBE_IN);
            fstrip(v, pose, camera, p, w, 0.9F * a, HEART);
        }
    }

    /** Ливень (r10): падающие лепестки вытягиваются в полосы; вертикальные ленты — столб. */
    private static void rain(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        float tt = c.t() + partial;
        for (SkyPetal s : c.sky) {
            if (s.stream < 0 || s.dead || s.fallTick >= 0 || s.u <= 0.02D || tt > RainRules.STILL) {
                continue;
            }
            Vec3 head = s.prev.lerp(s.pos, partial);
            Vec3 back = streamAt(c, s.stream, Math.max(0.0D, s.u - 0.05D), tt).add(streamSide(c, s.stream).scale(s.lane));
            Vec3 d = head.subtract(back);
            if (d.lengthSqr() < 1.0E-4D) {
                continue;
            }
            Vec3[] p = {head.subtract(d.normalize().scale(0.45D)), head};
            double[] w = {0.0D, 0.03D};
            fstrip(v, pose, camera, p, w, 0.5F, SKY_WHITE);
        }
        for (SkyPetal s : c.sky) {
            if (s.fallTick < 0 || s.dead && clientTicks - s.fallTick > RainRules.FALL + 1) {
                continue;
            }
            double u = Mth.clamp((clientTicks - s.fallTick + partial) / RainRules.FALL, 0.0D, 1.0D);
            if (u <= 0.0D) {
                continue;
            }
            // Хвост — та же траектория чуть раньше: полоса тем длиннее, чем выше скорость.
            double tailU = Math.max(0.0D, u - 0.32D);
            Vec3 head = fall(s, u);
            Vec3 tail = fall(s, tailU);
            Vec3 d = tail.subtract(head);
            double len = Math.min(0.8D + 1.2D * u, d.length());
            tail = d.lengthSqr() < 1.0E-6D ? head.add(0.0D, 0.3D, 0.0D) : head.add(d.normalize().scale(len));
            Vec3[] p = {tail, tail.lerp(head, 0.5D), head};
            double wd = 0.025D + 0.045D * s.size / 0.22D;
            double[] w = {0.0D, wd, wd * 0.6D};
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.5D), 0.2F, s.tone == 2 ? BLOOD : RAIN_PINK);
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.45D), 0.95F, SKY_WHITE);
        }
        double floor = Math.floor(c.target.y + 0.01D);
        for (Beam b : c.beams) {
            float age = clientTicks - b.born() + partial;
            if (age < 0.0F) {
                continue;
            }
            // Голова падает с высоты к земле за 3 тика; хвост — длина ленты.
            double headY = floor + b.top() - (b.top() + 0.5D) * Mth.clamp(age / 3.0D, 0.0D, 1.0D);
            double tailY = Math.min(floor + b.top() + 1.0D, headY + b.len());
            headY = Math.max(floor, headY);
            float a = (float) PlumVfx.curve(age, 0.0, 0.6, 1.0, 1.0, 3.0, 0.8, 4.5, 0.0);
            if (tailY - headY < 0.2D || a <= 0.0F) {
                continue;
            }
            double x = c.target.x + b.dx();
            double z = c.target.z + b.dz();
            Vec3[] p = {new Vec3(x, tailY, z), new Vec3(x + 0.04D, (tailY + headY) * 0.5D, z), new Vec3(x + 0.06D, headY, z)};
            double[] w = {0.0D, b.width(), b.width() * 0.8D};
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.4D), 0.16F * a, RAIN_PINK);
            fstrip(v, pose, camera, p, w, 0.5F * a, b.white() ? SKY_WHITE : SKY_MILK);
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.3D), 0.95F * a, SKY_WHITE);
        }
    }

    /** Удар: скрещённые белые черты в точке, кольца по земле, отпечатки капель. */
    private static void impact(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Vec3[] x : c.cores) {
            float age = (float) (clientTicks - x[3].x + partial);
            float a = (float) PlumVfx.curve(age, 0.0, 1.0, 1.5, 1.0, 5.0, 0.0);
            if (x[3].y > 1.5D) {
                double grow = Mth.clamp(age / 2.0D, 0.0D, 1.0D);
                Vec3[] p = {x[0], x[0].lerp(x[1], grow), x[0].lerp(x[2], grow)};
                a = (float) PlumVfx.curve(age, 0.0, 1.0, 2.0, 1.0, 12.0, 0.0);
                PlumVfx.flatStrip(v, pose, p, new double[] {0.16D, 0.1D, 0.0D}, 0.3F * a, RAIN_PINK);
                PlumVfx.flatStrip(v, pose, p, new double[] {0.05D, 0.03D, 0.0D}, 0.95F * a, SKY_WHITE);
                continue;
            }
            double w0 = x[3].y > 0.5D ? 0.11D : 0.06D;
            Vec3[] p = {x[0], x[1], x[2]};
            double[] w = {0.0D, w0, 0.0D};
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.6D), 0.25F * a, RAIN_PINK);
            fstrip(v, pose, camera, p, w, 0.95F * a, SKY_WHITE);
        }
        for (Ring r : c.rings) {
            float age = clientTicks - r.born() + partial;
            float k = Mth.clamp(age / r.life(), 0.0F, 1.0F);
            double rad = 0.4D + (r.rMax() - 0.4D) * (1.0D - Math.pow(1.0D - k, 4.0D));
            float a = (1.0F - k) * (1.0F - k);
            int n = 40;
            Vec3[] p = new Vec3[n + 1];
            double[] w = new double[n + 1];
            for (int i = 0; i <= n; i++) {
                double ang = i * Math.PI * 2.0D / n;
                // Рваный край: ширина гуляет по окружности.
                p[i] = r.centre().add(Math.cos(ang) * rad, 0.0D, Math.sin(ang) * rad);
                w[i] = r.width() * (0.6D + 0.4D * Math.abs(Math.sin(ang * 3.0D + r.born())));
            }
            PlumVfx.flatStrip(v, pose, p, PlumVfx.scale(w, 2.0D), 0.25F * a, RAIN_PINK);
            PlumVfx.flatStrip(v, pose, p, PlumVfx.scale(w, 0.4D), 0.9F * a, SKY_WHITE);
        }
        // Отпечаток ливня: следы ударов капель — белое → розовое → холодно-серое.
        for (Mark m : c.marks) {
            float age = clientTicks - m.born() + partial;
            VfxColour col = age < 4.0F ? SKY_WHITE : age < 30.0F ? RAIN_PINK : COLD;
            float a = (float) PlumVfx.curve(age, 0.0, 1.0, 4.0, 0.9, 30.0, 0.5, 64.0, 0.0);
            for (int arm = 0; arm < 3; arm++) {
                double ang = m.angle() + arm * Math.PI / 3.0D;
                Vec3 d = new Vec3(Math.cos(ang), 0.0D, Math.sin(ang)).scale(m.size());
                Vec3[] p = {m.pos().subtract(d), m.pos(), m.pos().add(d)};
                double[] w = {0.0D, 0.05D, 0.0D};
                PlumVfx.flatStrip(v, pose, p, w, 0.6F * a, col);
            }
        }
    }

    private static void puffs(Cast c, PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float partial) {
        if (c.puffs.isEmpty()) {
            return;
        }
        RenderType dust = MurimRenderTypes.dustPuffs();
        VertexConsumer d = buffers.getBuffer(dust);
        for (Puff p : c.puffs) {
            if (p.smoke || p.delay > 0) {
                continue;
            }
            float pt = (p.age + partial) / p.life;
            float alpha = pt < 0.5F ? 1.0F : Mth.clamp(1.0F - (pt - 0.5F) / 0.5F, 0.0F, 1.0F);
            PlumVfx.puff(d, pose, camera, p.prev.lerp(p.pos, partial), p.size * (0.7D + 0.8D * pt), p.cell, alpha, p.gray);
        }
        buffers.endBatch(dust);
        Minecraft mc = Minecraft.getInstance();
        boolean fp = c.eyes();
        org.joml.Vector3f look = mc.gameRenderer.getMainCamera().getLookVector();
        RenderType smokeType = MurimRenderTypes.smokeCel();
        VertexConsumer sm = buffers.getBuffer(smokeType);
        for (Puff p : c.puffs) {
            if (!p.smoke || p.delay > 0) {
                continue;
            }
            float pt = (p.age + partial) / p.life;
            float alpha = Mth.clamp(pt / 0.06F, 0.0F, 1.0F) * (pt < 0.75F ? 1.0F : Mth.clamp(1.0F - (pt - 0.75F) / 0.25F, 0.0F, 1.0F));
            double grow = 0.55D + 0.8D * Math.sqrt(pt) + (pt > 0.75F ? 1.2D * (pt - 0.75D) : 0.0D);
            Vec3 at = p.prev.lerp(p.pos, partial);
            float cam = (float) Mth.clamp((at.distanceTo(camera) - p.size * grow - 0.6D) / 1.5D, 0.0D, 1.0D);
            // От первого лица дым в центре взгляда (на цели) прозрачнее: удар читается сквозь вал.
            if (fp) {
                Vec3 to = at.subtract(camera).normalize();
                double cos = to.x * look.x() + to.y * look.y() + to.z * look.z();
                cam *= (float) Mth.clamp(0.25D + (0.985D - cos) / 0.03D, 0.25D, 1.0D);
            }
            PlumVfx.smokePuff(sm, pose, camera, at, p.size * grow, p.cell, alpha * cam, p.gray, p.spin);
        }
        buffers.endBatch(smokeType);
    }

    /** Лепестки (атлас) и их свечение; цветок-ядро — сотни лепестков на орбитах и глоу сердцевины. */
    private static void petals(Cast c, PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float partial, float t) {
        if (!RainRules.petals(c.layer)) {
            return;
        }
        RenderType pt = MurimRenderTypes.plumPetals();
        VertexConsumer pc = buffers.getBuffer(pt);
        for (Mote m : c.motes) {
            if (m.kind != Mote.PETAL) {
                continue;
            }
            Vec3 at = m.prev.lerp(m.pos, partial);
            if (at.distanceToSqr(camera) < 2.6D) {
                continue;
            }
            float a = Mth.clamp((m.life - m.age - partial) / 10.0F, 0.0F, 1.0F) * Mth.clamp((m.age + partial) / 3.0F, 0.0F, 1.0F)
                    * near(at, camera.add(0.0D, 0.0D, 0.0D));
            float[] tint = tint(m.tone);
            PlumVfx.petal(pc, pose, camera, at, m.size * 1.6D, m.cell, (m.age + partial) * m.spin, a, tint[0], tint[1], tint[2]);
        }
        float skyA = c.lostTick >= 0 ? 1.0F : 1.0F;
        for (SkyPetal s : c.sky) {
            if (s.dead || s.fallTick >= 0 && clientTicks >= s.fallTick) {
                continue;
            }
            Vec3 at = s.prev.lerp(s.pos, partial);
            if (at.distanceToSqr(camera) < 1.7D) {
                continue;
            }
            float a = skyA * (s.free ? Mth.clamp(1.0F - (s.freeAge + partial) / 30.0F, 0.0F, 1.0F) : 1.0F)
                    * appear(c, s, t);
            float[] tint = s.stream < 0 && s.tone == 1 ? lobeTint(s.a) : tint(s.tone);
            PlumVfx.petal(pc, pose, camera, at, s.size * 1.6D, s.cell, (t + s.cell * 7.0F) * s.spin * 0.6F, a * near(at, camera),
                    tint[0], tint[1], tint[2]);
        }
        buffers.endBatch(pt);
        RenderType gt = MurimRenderTypes.mote();
        VertexConsumer g = buffers.getBuffer(gt);
        for (Mote m : c.motes) {
            if (m.kind != Mote.PETAL) {
                continue;
            }
            Vec3 at = m.prev.lerp(m.pos, partial);
            if (at.distanceToSqr(camera) > 1.7D) {
                float a = Mth.clamp((m.life - m.age - partial) / 10.0F, 0.0F, 1.0F);
                PlumVfx.glow(g, pose, camera, at, m.size * 2.0D, 0.32F * a, m.tone == 0 ? SKY_WHITE : SKY_MILK);
            }
        }
        for (SkyPetal s : c.sky) {
            if (s.dead || s.fallTick >= 0 && clientTicks >= s.fallTick) {
                continue;
            }
            Vec3 at = s.prev.lerp(s.pos, partial);
            float a = appear(c, s, t) * (s.free ? Mth.clamp(1.0F - (s.freeAge + partial) / 30.0F, 0.0F, 1.0F) : 1.0F);
            // Светятся изнутри: белая точка в сердце лепестка, розовый ореол.
            a *= near(at, camera);
            PlumVfx.glow(g, pose, camera, at, s.size * 2.2D, 0.26F * a, s.tone == 2 ? BLOOD : s.stream < 0 && s.a > 0.6D ? LOBE_OUT : SKY_MILK);
            PlumVfx.glow(g, pose, camera, at, s.size * 0.7D, 0.55F * a, SKY_WHITE);
        }
        // Ливень: яркая голова падающего лепестка — видно, что всё летит ВНИЗ.
        for (SkyPetal s : c.sky) {
            if (s.fallTick < 0 || clientTicks < s.fallTick || s.dead) {
                continue;
            }
            double u = Mth.clamp((clientTicks - s.fallTick + partial) / RainRules.FALL, 0.0D, 1.0D);
            Vec3 head = fall(s, u);
            PlumVfx.glow(g, pose, camera, head, 0.16D, 0.9F * near(head, camera), SKY_WHITE);
            PlumVfx.glow(g, pose, camera, head, 0.34D, 0.3F * near(head, camera), s.tone == 2 ? BLOOD : RAIN_PINK);
        }
        // Сердцевина ядра: десятки мелких глоу на своих орбитах (каждый ≤0,24 — чёткость).
        if (c.core != null && c.coreBorn >= 0) {
            Vec3 core = c.corePrev == null ? c.core : c.corePrev.lerp(c.core, partial);
            float grow = (float) Mth.clamp((clientTicks - c.coreBorn + partial) / 20.0D, 0.0D, 1.0D);
            float out = (float) Mth.clamp(1.0D - (t - RainRules.COHORTS[1]) / 8.0D, 0.0D, 1.0D)
                    * (c.lostTick >= 0 ? Mth.clamp(1.0F - (clientTicks - c.lostTick + partial) / 12.0F, 0.0F, 1.0F) : 1.0F);
            for (int k = 0; k < 5; k++) {
                for (double sp : new double[] {0.25D, 0.5D, 0.75D}) {
                    Vec3 lp = lobePoint(c, k, sp, 0.0D, t, 0.0D);
                    double hw = 5.0D * c.scale * LOBE_LEN[k] * 0.32D * Math.sin(Math.PI * sp);
                    PlumVfx.glow(g, pose, camera, lp, hw, 0.1F * grow * out, sp < 0.4D ? SKY_MILK : LOBE_IN);
                }
            }
            Vec3[] fb = flowerBasis(c, t);
            int st = Math.max(6, c.n(22));
            double op = open(t);
            for (int k = 0; k < st; k++) {
                double ang = k * 2.0D * Math.PI / st + 0.3D * Math.sin(k * 2.7D);
                double len = (1.4D + 0.9D * ((k * 0.618D) % 1.0D)) * c.scale * op;
                Vec3 dirS = fb[1].scale(Math.cos(ang)).add(fb[2].scale(Math.sin(ang))).scale(0.85D).add(fb[0].scale(-0.5D)).normalize();
                Vec3 tip = core.add(dirS.scale(len)).add(fb[0].scale(0.1D * Math.sin(t * 0.15D + k)));
                PlumVfx.glow(g, pose, camera, tip, 0.14D * c.scale, 0.85F * grow * out, k % 4 == 0 ? BLOOD : LOBE_IN);
            }
            for (double[] q : c.coreGlows) {
                double a = q[0] + q[2] * t;
                double r = q[1] * c.scale * grow;
                Vec3 p = core.add(c.dir.scale(Math.cos(a) * r)).add(c.side.scale(Math.sin(a) * r * Math.cos(q[3])))
                        .add(0.0D, Math.sin(a) * r * Math.sin(q[3]), 0.0D);
                PlumVfx.glow(g, pose, camera, p, 0.08D + 0.16D * ((q[1] * 7.0D) % 1.0D), 0.6F * grow * out, SKY_WHITE);
            }
            // Белое сердце 1,4 блока (r08) и широкая засветка неба: ступени, без размытия кадра.
            PlumVfx.glow(g, pose, camera, core, 0.6D * c.scale, 1.0F * grow * out, HEART);
            PlumVfx.glow(g, pose, camera, core, 1.1D * c.scale, 0.9F * grow * out, HEART);
            PlumVfx.glow(g, pose, camera, core, 2.2D * c.scale, 0.32F * grow * out, hex(0xFFD2E8));
            PlumVfx.glow(g, pose, camera, core, 0.9D * c.scale, 0.95F * grow * out, HEART);
            PlumVfx.glow(g, pose, camera, core, 1.8D * c.scale, 0.65F * grow * out, HEART);
            PlumVfx.glow(g, pose, camera, core, 3.2D * c.scale, 0.2F * grow * out, SKY_MILK);
            PlumVfx.glow(g, pose, camera, core, 7.5D * c.scale, 0.08F * grow * out, SKY_LILAC);
        }
        // Вспышка обрушения: белое ядро в точке удара, гаснет в розовое.
        if (c.hitFinal >= 0 && c.hitAt != null) {
            float age = clientTicks - c.hitFinal + partial;
            float a = (float) PlumVfx.curve(age, 0.0, 1.0, 3.0, 0.8, 16.0, 0.25, 36.0, 0.0);
            if (a > 0.0F) {
                PlumVfx.glow(g, pose, camera, c.hitAt, 1.0D + 0.08D * age, 0.6F * a, age < 4.0F ? SKY_WHITE : SKY_MILK);
                PlumVfx.glow(g, pose, camera, c.hitAt, 2.6D + 0.1D * age, 0.25F * a, RAIN_PINK);
            }
        }
        buffers.endBatch(gt);
    }

    /** Появление лепестка оболочки — по одному, со своей задержкой (раскрытие, а не вспышка). */
    private static float appear(Cast c, SkyPetal s, float t) {
        double from = s.stream >= 0 ? RainRules.STREAM + s.delay : RainRules.BLOOM + s.delay * 0.8D;
        float a = (float) Mth.clamp((t - from) / 6.0D, 0.0D, 1.0D);
        if (c.lostTick >= 0) {
            a *= Mth.clamp(1.0F - (clientTicks - c.lostTick) / 12.0F, 0.0F, 1.0F);
        }
        return a;
    }

    /** Доля цветка: у сердца светло-розовый, к краю — глубокий (#FF85BC → #DB4388). */
    private static float[] lobeTint(double sPos) {
        float k = (float) Mth.clamp((sPos - 0.3D) / 0.7D, 0.0D, 1.0D);
        return new float[] {1.0F - 0.06F * k, 0.92F - 0.4F * k, 0.97F - 0.3F * k};
    }

    private static float[] tint(int tone) {
        return switch (tone) {
            case 0 -> new float[] {1.0F, 0.96F, 0.98F};
            case 3 -> new float[] {1.0F, 0.88F, 0.94F};
            case 2 -> new float[] {1.0F, 0.42F, 0.6F};
            default -> new float[] {1.0F, 0.8F, 0.88F};
        };
    }

    private RainVfx() {
    }
}
