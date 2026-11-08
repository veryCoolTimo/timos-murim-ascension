package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.CameraShakeHandler;
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
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

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
    // Presentation launches only; server RainRules contact/damage clocks stay unchanged.
    private static final int[] RAIN_WAVES = {128, 130, 132};
    // Round 8 API: read-only /tmp/mr-ref-1121/minecraft-src/net/minecraft/
    // world/phys/Vec3.java#add,subtract,scale,lerp,cross,normalize,lengthSqr;
    // util/Mth.java#clamp. Rendering continues through existing VfxDraw/PlumVfx helpers.


    private static final ResourceLocation TECHNIQUE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "twenty_four_plum_rainfall");
    /** Crimson trail halo with a bright white core. */
    private static final VfxColour TRAIL_RIM = hex(0xE3264E);
    private static final VfxColour TRAIL_BODY = hex(0xE3264E);
    private static final VfxColour TRAIL_CORE = hex(0xFFF0F4);
    /** Ветер (r03): холодно-белый. */
    private static final VfxColour WIND = hex(0xE8EDF1);
    /** Opening white and bloom ground crimson. */
    private static final VfxColour SKY_WHITE = hex(0xFFFFFF);
    private static final VfxColour SKY_MILK = hex(0xE3264E);
    private static final VfxColour SKY_LILAC = hex(0x4A0D22);
    private static final VfxColour RAIN_PINK = hex(0xE3264E);
    private static final VfxColour LOBE_IN = hex(0xE3264E);
    private static final VfxColour LOBE_OUT = hex(0x4A0D22);
    private static final VfxColour HEART = hex(0xFFEFF3);
    private static final VfxColour BLOOD = hex(0x4A0D22);
    private static final VfxColour GROUND = hex(0xE3264E);

    private static net.minecraft.client.multiplayer.ClientLevel presentationLevel;
    private static void syncLevel() {
        var current = Minecraft.getInstance().level;
        if (presentationLevel != current) {
            restoreVictim();
            CASTS.clear();
            VfxBudget.setRain(0);
            presentationLevel = current;
        }
    }
    private static final List<Cast> CASTS = new ArrayList<>();
    private static int clientTicks;

    /** Вблизи камеры тает: от первого лица ничего не закрывает экран (0 ближе 2 блоков, 1 дальше 3,5). */
    private static float near(Vec3 at, Vec3 camera) {
        return (float) Mth.clamp((at.distanceTo(camera) - 2.0D) / 1.5D, 0.0D, 1.0D);
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
        ribbon(v, pose, camera, p, w, a, col);
    }

    /** Widths are half widths: retain a soft 0.08-block body and erode opacity at tips. */
    private static void ribbon(VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Vec3[] p, double[] w, float[] opacity, VfxColour col) {
        float[] a = opacity.clone();
        double[] material = w.clone();
        for (int i = 0; i < a.length; i++) {
            a[i] *= (float) Math.min(1, Math.max(0, w[i]) / .04);
            material[i] = Math.max(.04, w[i]);
        }
        PlumVfx.stripVar(v, pose, camera, p, material, near(p, a, camera), col);
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
        Vec3 fallFrom;
        Vec3 fallTo;
        int fallTick = -1;
        final double uSpeed;
        Vec3 pos = Vec3.ZERO;
        Vec3 prev = Vec3.ZERO;
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
            this.cohort = stream < 0 ? io.github.verycooltimo.murim.technique.PlumPresentationRules.cohort(a) : cohort;
            this.stream = stream;
            this.delay = delay;
            this.lane = lane;
            this.uSpeed = uSpeed;
        }
    }

    /** Painted rain lance: independent delay, slant and travel speed.
     * API: minecraft-src/net/minecraft/world/entity/Entity.java#getEyePosition,getBbWidth,getBbHeight,position;
     * minecraft-src/net/minecraft/client/multiplayer/ClientLevel.java#getEntity.
     */


    private record Beam(double dx, double dz, int born, double len, double width, double top, boolean white) {
    }

    private record Ring(Vec3 centre, int born, double rMax, int life, double width) {
    }

    private record Mark(Vec3 pos, int born, double size, double angle) {
    }


    private record Lance(Vec3 from, Vec3 approach, Vec3 to, int born, double duration, double width, double length, int cell) {}
    private record Landed(Vec3 pos, int born, int life, int cell, double size, double spin) {}
    private record Contact(Vec3 pos, int born, double phase) {}

    private static final class Cast {
        final PlumLayerCapture capture = new PlumLayerCapture();
        final int entityId;
        final int executionTier;
        int stars;
        boolean has(PlumLayers.Kind kind) { return PlumLayers.has(PlumLayers.Form.RAIN, stars, kind); }
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
        int hitFinal = -1;
        Vec3 hitAt;
        final List<Beam> beams = new ArrayList<>();
        final List<Ring> rings = new ArrayList<>();
        final List<Mark> marks = new ArrayList<>();
        final List<Vec3[]> cores = new ArrayList<>();
        final List<double[]> coreGlows = new ArrayList<>();
        boolean caption;
        boolean streamed;
        /** Точки следа замаха: старт за спиной мастера → за спину цели. */
        Vec3[] swing;
        int nextPeak;
        final List<Mote> motes = new ArrayList<>();
        final List<Puff> puffs = new ArrayList<>();
        final List<WindArc> winds = new ArrayList<>();
        final List<Ghost> ghosts = new ArrayList<>();
        final List<Thrust> thrusts = new ArrayList<>();
        final List<SkyPetal> sky = new ArrayList<>();
        final List<Lance> lances = new ArrayList<>();
        final List<Landed> landed = new ArrayList<>();
        final List<Contact> contacts = new ArrayList<>();
        int coreBorn = -1;
        ResourceLocation skin;
        PartPose[] dashPose;

        Cast(int entityId, int executionTier) {
            this.entityId = entityId;
            this.executionTier = executionTier;
            this.start = clientTicks;
            this.random = new Random(entityId * 7919L + clientTicks);
            this.density = RainRules.density(executionTier);
            this.scale = RainRules.scale(8); // Keep the existing apex geometry; stars gate individual layers.
        }

        int t() {
            return clientTicks - start;
        }

        int n(double full) { return n(full, PlumLayers.Kind.DUST); }
        int n(double full, PlumLayers.Kind kind) {
            return (int) Math.ceil(full * RainRules.density(8) * PlumLayers.density(stars, kind));
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
        syncLevel();
        if (TECHNIQUE.equals(payload.techniqueId()) && payload.event() == TechniqueEventPayload.Event.CANCELLED) {
            for (Cast c : CASTS) if (c.entityId == payload.sourceId()) dissolve(c);
            return;
        }
        if (TECHNIQUE.equals(payload.techniqueId()) && payload.event() == TechniqueEventPayload.Event.FINISHED) {
            for (Cast c : CASTS) if (c.entityId == payload.sourceId() && c.t() < RainRules.END) dissolve(c);
            return;
        }
        if (payload.event() != TechniqueEventPayload.Event.STARTED || !TECHNIQUE.equals(payload.techniqueId())
                || payload.executionTier() <= 0) {
            return;
        }
        for (Cast previous : CASTS) if (previous.entityId == payload.sourceId()) dissolve(previous);
        CASTS.removeIf(c -> c.entityId == payload.sourceId() && c.landed.isEmpty());
        if (CASTS.size() >= 6) return;
        Cast c = new Cast(payload.sourceId(), payload.executionTier());
        c.stars = payload.masteryStars();
        CASTS.add(c);
    }

    public static void onRain(RainPayload p) {
        if (p.executionTier() <= 0) return;
        syncLevel();
        Cast c = null;
        for (Cast x : CASTS) {
            if (x.entityId == p.entityId()) {
                c = x;
            }
        }
        if (c != null) c.stars = p.stars();
        Minecraft mc = Minecraft.getInstance();
        if (p.stage() == RainPayload.RELEASE) {
            if (c == null) {
                if (CASTS.size() >= 6) return;
                c = new Cast(p.entityId(), p.executionTier());
                c.stars = p.stars();
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
            dissolve(c);
            return;
        }
        int k = p.stage() - RainPayload.HIT;
        if (k >= 0 && k < 3) {
            hit(c, p.centre(), k, mc);
        }
    }

    private static void dissolve(Cast c) {
        if (c.lostTick >= 0) return;
        c.lostTick = clientTicks;
        c.lances.clear();
        if (savedPose != null && savedPose.entity.getId() == c.targetId) restoreVictim();
        for (SkyPetal s : c.sky) {
            if (!s.dead) {
                s.free = true;
                s.vel = new Vec3(c.random.nextGaussian() * 0.03D, -0.02D, c.random.nextGaussian() * 0.03D);
            }
        }
    }

    // ------------------------------------------------------------------ фазы

    /** Замах (r04): белая зубчатая вспышка у рук, розовая аура, дым из-под ног, линии скорости. */
    private static void release(Cast c, Minecraft mc) {
        dust(c, c.origin, c.n(10) + 3, 0.18D);
        if (c.own() && c.stars >= 10) {
            if (c.stars >= 10) SpeedLines.directional(0.0F, 0.3F, 4, SpeedLines.WHITE);
        }
        if (mc.level != null && mc.level.getEntity(c.entityId) instanceof AbstractClientPlayer player
                && mc.getEntityRenderDispatcher().getRenderer(player) instanceof PlayerRenderer renderer) {
            c.skin = player.getSkin().texture();
            c.dashPose = capture(renderer.getModel());
        }
        if (mc.player != null) {
            io.github.verycooltimo.murim.client.Sfx.play(c.origin.x, c.origin.y, c.origin.z, io.github.verycooltimo.murim.registry.ModSounds.SWORD_SWING.get(),
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.05F, false);
        }
    }

    /** Film-only framing weight; the observer follows the existing cast clock. */
    public static double filmFlowerFrame(int owner) {
        for (Cast c : CASTS) if (c.entityId == owner && c.target != null && c.lostTick < 0) {
            double t = c.t();
            return Mth.clamp((t - 78) / 6.0, 0, 1) * Mth.clamp((164 - t) / 10.0, 0, 1);
        }
        return 0;
    }

    /** Straight swing trail from behind the caster to beyond the target, sloping downward. */
    private static void buildSwing(Cast c) {
        Vec3 aim = c.target != null ? c.target : c.dest;
        Vec3 to = new Vec3(aim.x - c.origin.x, 0.0D, aim.z - c.origin.z);
        double dist = Math.max(2.0D, to.length());
        Vec3 d = to.lengthSqr() < 1.0E-6D ? c.dir : to.normalize();
        Vec3 from = c.origin.subtract(d.scale(1.2D));
        double len = dist + 1.2D + 4.6D;
        int n = 40;
        c.swing = new Vec3[n + 1];
        for (int i = 0; i <= n; i++) {
            double u = i / (double) n;
            // Straight pass, retaining the build's downward slope.
            double y = 1.75D - 0.9D * u;
            c.swing[i] = from.add(d.scale(len * u)).add(0.0D, y, 0.0D);
        }
    }

    /** Метка прошла (r06): первые светящиеся лепестки висят вокруг цели. */
    private static void suspended(Cast c) {
        if (c.stars < 10) return; // A target-centred field belongs to VOLUME.
        if (!c.has(PlumLayers.Kind.PETALS) || c.target == null) {
            return;
        }
        for (int i = 0; i < Math.max(4, c.n(16, PlumLayers.Kind.PETALS)); i++) {
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
     * bfed8c10b: dense small particles in five uneven lobes, bright contour and white star core.
     */
    private static void bloom(Cast c) {
        if (!c.has(PlumLayers.Kind.FLOWER)) return;
        // Reserve the separate centre droplets and corolla inside the restored pre-round-A apex budget.
        int corolla = Math.max(8, c.n(36, PlumLayers.Kind.FLOWER));
        int reserve = c.n(48, PlumLayers.Kind.FLOWER) + RainPresentation.streamPetals(c.executionTier)
                + RainPresentation.lanceCount(c.executionTier);
        int budget = Math.min(skyBudget(c), Math.max(0, VfxBudget.rainRoom() - rainLive() - reserve));
        if (budget < 6) return;
        corolla = Math.min(corolla, budget - 5);
        int perLobe = Math.max(1, (budget - corolla) / 5);
        corolla = Math.max(1, budget - perLobe * 5);
        int total = 0;
        for (int k = 0; k < 5; k++) {
            for (int i = 0; i < perLobe; i++) {
                // Плотнее к краю доли: край читается, середина светится сквозь.
                // Половина — у кромки (край читается), половина — по всей доле (заполненная середина).
                double sPos = i % 3 == 0 ? 0.12D + 0.88D * Math.pow(c.random.nextDouble(), 0.3D) : 0.08D + 0.9D * Math.sqrt(c.random.nextDouble());
                double l = (c.random.nextDouble() * 2.0D - 1.0D);
                l = i % 3 == 0 ? Math.signum(l) * (.85 + .15 * Math.abs(l)) : l * .80;
                double pigment = c.random.nextDouble();
                int tone = pigment < .65 ? 1 : pigment < .90 ? 2 : 0;
                c.sky.add(new SkyPetal(k, sPos, l, c.random.nextDouble() * Math.PI * 2.0D, 0.09D + 0.13D * c.random.nextDouble(),
                        c.random.nextInt(4), (float) ((c.random.nextDouble() - 0.5D) * 0.6D), tone, wave(c), -1,
                        c.random.nextDouble() * 10.0D + sPos * 6.0D, 0.0D, 0.0D));
                total++;
            }
        }
        // Венчик у белого сердца: острые светлые лепестки внахлёст вокруг ядра.
        for (int i = 0; i < corolla; i++) {
            c.sky.add(new SkyPetal(i % 5, 0.02D + 0.12D * c.random.nextDouble(), c.random.nextDouble() * 2.0D - 1.0D,
                    c.random.nextDouble() * Math.PI * 2.0D, 0.14D + 0.08D * c.random.nextDouble(), c.random.nextInt(4),
                    (float) ((c.random.nextDouble() - 0.5D) * 0.5D), 0, wave(c), -1, c.random.nextDouble() * 4.0D, 0.0D, 0.0D));
        }
        // Сердцевина: глоу на своих мелких орбитах.
        for (int i = 0; i < c.n(48, PlumLayers.Kind.FLOWER) && rainLive() < VfxBudget.rainRoom(); i++) {
            c.coreGlows.add(new double[] {c.random.nextDouble() * Math.PI * 2.0D, 0.1D + 0.9D * c.random.nextDouble(),
                    (0.05D + 0.1D * c.random.nextDouble()) * (c.random.nextBoolean() ? 1 : -1), c.random.nextDouble() * Math.PI});
        }
        c.coreBorn = clientTicks;
        TechniqueLight.add(c.target.add(0, 1, 0), 0xE3264E, 5, (.4F) * PlumLayers.light(c.stars), 4, 2, 12);
        MurimMod.LOGGER.debug("Ливень: цветок-ядро, лепестков {}", total);
    }

    private static int skyBudget(Cast c) {
        int count = (int) (RainPresentation.flowerPetals(c.executionTier) * PlumLayers.density(c.stars, PlumLayers.Kind.FLOWER));
        count = switch (io.github.verycooltimo.murim.client.ClientConfig.VFX_QUALITY.get()) {
            case HIGH -> count; case MEDIUM -> count * 2 / 3; case LOW -> count / 3;
        };
        count = switch (Minecraft.getInstance().options.particles().get()) {
            case ALL -> count; case DECREASED -> count / 2; case MINIMAL -> count / 8;
        };
        return Math.max(0, Math.min(count, VfxBudget.rainRoom() - rainLive()));
    }

    private static int rainLive() {
        int count = 0;
        for (Cast c : CASTS) {
            // The old fall simulation retains dead entries until the final contact for cleanup.
            // They must not consume the room reserved for later lances and landed petals.
            for (SkyPetal petal : c.sky) if (!petal.dead) count++;
            count += c.motes.size() + c.puffs.size() + c.beams.size() + c.cores.size()
                    + c.rings.size() + c.marks.size() + c.coreGlows.size()
                    + c.lances.size() + c.landed.size() + c.contacts.size() * 3;
        }
        return count;
    }

    /** Old flower plane tilted back 40 degrees, with its original dir/side mix. */
    // Restored from bfed8c10b. API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/phys/Vec3.java.
    private static Vec3[] flowerBasis(Cast c, double t) {
        double tilt = Math.toRadians(40.0D);
        Vec3 n = new Vec3(0.0D, -Math.cos(tilt), 0.0D).add(c.dir.scale(Math.sin(tilt) * (0.8D / Math.hypot(0.8D, 0.65D)))).add(c.side.scale(Math.sin(tilt) * (0.65D / Math.hypot(0.8D, 0.65D)))).normalize();
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
    private static final double FLOWER_R = 9.5D;
    /** Точка доли: s — от сердцевины к краю, l — поперёк (−1…1); доля чашей выгнута к зрителю. */
    private static Vec3 lobePoint(Cast c, int k, double sPos, double l, double t, double depth) {
        Vec3[] f = flowerBasis(c, t);
        double op = open(t);
        double breath = 1.0D + 0.04D * Math.sin(t * Math.PI * 2.0D / 28.0D + k * 1.3D);
        // Автор 03.10: «цветок огромный» — доли 7 блоков вместо 5.
        double rMax = FLOWER_R * c.scale * LOBE_LEN[k] * breath;
        double rc = 1.2D * c.scale;
        double ang = Math.toRadians(LOBE_ANGLE[k] + 3.0D * Math.sin(t * 0.07D + k * 2.0D));
        Vec3 axis = f[1].scale(Math.cos(ang)).add(f[2].scale(Math.sin(ang)));
        Vec3 across = f[0].cross(axis).normalize();
        double r = rc + (rMax - rc) * sPos;
        // Округлая доля сливы с маленькой выемкой на конце.
        double hw = rMax * 0.43D * Math.pow(Math.sin(Math.PI * Math.min(1.0D, sPos * 0.92D + 0.04D)), 0.75D)
                * (1.0D - 0.12D * Math.exp(-Math.pow(l * 6.0D, 2.0D)) * Math.max(0.0D, sPos - 0.85D) * 6.0D);
        // Бутон: доли сложены к оси (вдоль нормали), раскрытый — чаша.
        double fold = Math.toRadians(80.0D * (1.0D - op) + 0.0D * op);
        double cup = 1.9D * c.scale * sPos * sPos;
        Vec3 radial = axis.scale(Math.cos(fold)).add(f[0].scale(-Math.sin(fold)));
        return c.core.add(radial.scale(r * Math.max(0.15D, op))).add(across.scale(l * hw * Math.max(0.15D, op)))
                .add(f[0].scale(-cup * op + depth));
    }

    /** Five loose painted ribbons leave separate lobes, with staggered individual petals. */
    private static void streams(Cast c) {
        if (!c.has(PlumLayers.Kind.THREADS)) return;
        int count = Math.min(RainPresentation.streamPetals(c.executionTier),
                Math.max(0, VfxBudget.rainRoom() - rainLive()));
        int streams = RainPresentation.streamCount(c.executionTier);
        for (int i = 0; i < count; i++) {
            int stream = i % streams;
            c.sky.add(new SkyPetal(stream, 0, 0, c.random.nextDouble() * Math.PI * 2,
                    .3 + .3 * c.random.nextDouble(), c.random.nextInt(8),
                    (float) (.01 + .02 * c.random.nextDouble()), 1, 2, stream,
                    stream * 1.1 + (i / streams) * .65, 0, .85 + .3 * c.random.nextDouble()));
        }
    }

    /** Server-confirmed crossed strokes, torn ground rings and fading drop marks. */
    private static void hit(Cast c, Vec3 at, int k, Minecraft mc) {
        if (c.stars < 10) return;
        TechniqueLight.add(at, 0xE3264E, k == 2 ? 6 : 4, (k == 2 ? .95F : .65F) * PlumLayers.light(c.stars), 1, 1, 10);
        if (c.origin != null) TechniqueLight.add(c.origin.add(0, 1, 0), 0xE3264E, 3, (.45F) * PlumLayers.light(c.stars), 1, 1, 8);
        Vec3 ground = new Vec3(at.x, c.target != null ? c.target.y : at.y - 1.0D, at.z);
        boolean last = k == 2;
        int cross = last ? 28 : 8;
        for (int i = 0; i < cross; i++) {
            Vec3 d = new Vec3(c.random.nextGaussian(), c.random.nextGaussian() * 0.7D + (last ? 0.6D : 0.3D), c.random.nextGaussian()).normalize();
            double r = (last ? 4.2D : 0.8D) * (0.5D + 0.6D * c.random.nextDouble()) * (0.7D + 0.3D * c.scale);
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
        c.rings.add(new Ring(ground.add(0.0D, 0.05D, 0.0D), clientTicks, (last ? 8.0D : 3.2D) * c.scale, last ? 14 : 10, last ? 0.32D : 0.18D));
        if (last) {
            // Рваные лучи удара по земле: 24–36 разной длины, несимметрично.
            int cuts = Math.max(6, c.n(16));
            double base = c.random.nextDouble() * Math.PI * 2.0D;
            for (int i = 0; i < cuts; i++) {
                // Три пучка по сторонам удара, а не ровная звезда.
                double a = base + (i % 3) * 2.2D + (c.random.nextDouble() - 0.5D) * 0.6D;
                double len = 3.0D + 2.0D * c.random.nextDouble();
                Vec3 d = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
                Vec3 g0 = ground.add(0.0D, 0.04D, 0.0D);
                c.cores.add(new Vec3[] {g0.add(d.scale(0.3D)), g0.add(d.scale(len * 0.5D)), g0.add(d.scale(len)), new Vec3(clientTicks, 2, 0)});
            }
            c.rings.add(new Ring(ground.add(0.0D, 0.06D, 0.0D), clientTicks + 1, 6.0D * c.scale, 8, 0.12D));
        }
        for (int i = 0; i < (last ? c.n(14) : c.n(5)); i++) {
            double a = c.random.nextDouble() * Math.PI * 2.0D;
            double r = rainSpread(c) * Math.sqrt(c.random.nextDouble());
            c.marks.add(new Mark(ground.add(Math.cos(a) * r, 0.03D, Math.sin(a) * r), clientTicks, 0.25D + 0.35D * c.random.nextDouble(),
                    c.random.nextDouble() * Math.PI));
        }

        float distance = mc.player == null ? 99.0F : (float) mc.player.position().distanceTo(at);
        if (last) {
            c.hitFinal = clientTicks;
            c.hitAt = at;
            if (c.has(PlumLayers.Kind.COLUMN)) {
                for (int i = 0; i < RainPresentation.columnCount(c.executionTier) * PlumLayers.density(c.stars, PlumLayers.Kind.COLUMN); i++) {
                    double angle = c.random.nextDouble() * Math.PI * 2;
                    double radius = 2.2D * c.scale * Math.sqrt(c.random.nextDouble());
                    c.beams.add(new Beam(Math.cos(angle) * radius, Math.sin(angle) * radius, clientTicks,
                            11.0D * c.scale, 0.12D, 11.0D, true));
                }
            }
            if (c.own() && c.stars >= 10) {
                if (c.stars >= 10) SpeedLines.radial(0.5F, 0.5F, 0.6F, 6, SpeedLines.WHITE);
            }
            if (mc.player != null && distance < 24.0F) {
                float q = distance < 8.0F ? 1.0F : 1.0F - (distance - 8.0F) / 16.0F;
                if (c.stars >= 10) CameraShakeHandler.quake(Math.max(q, c.own() ? 0.85F : 0.0F), 16);
                io.github.verycooltimo.murim.client.Sfx.play(at.x, at.y, at.z, io.github.verycooltimo.murim.registry.ModSounds.IMPACT_HEAVY.get(),
                        net.minecraft.sounds.SoundSource.PLAYERS, 0.8F, 1.0F, false);
                io.github.verycooltimo.murim.client.Sfx.play(at.x, at.y, at.z, io.github.verycooltimo.murim.registry.ModSounds.PETAL_BURST.get(),
                        net.minecraft.sounds.SoundSource.PLAYERS, 0.8F, 1.0F, false);
            }
        } else if (mc.player != null && distance < 16.0F) {
            if (c.stars >= 10) CameraShakeHandler.quake(0.25F, 5);
            io.github.verycooltimo.murim.client.Sfx.play(at.x, at.y, at.z, io.github.verycooltimo.murim.registry.ModSounds.SWORD_SWING.get(),
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.6F, 1.15F, false);
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
        if (!c.has(PlumLayers.Kind.DUST)) return;
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
        restoreVictim();
        syncLevel();
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
            } else if (c.targetId >= 0 && c.lostTick < 0) {
                dissolve(c);
            }
            if (e != null && c.lostTick < 0) {
                body(c, e, t, mc);
            }
            if (c.released && c.lostTick < 0) {
                after(c, e, t, mc);
            }
            tickLances(c);
            tickMotes(c);
            tickSky(c, t);
            c.beams.removeIf(b -> clientTicks - b.born() > 5);
            c.cores.removeIf(x -> clientTicks - x[3].x > 12);
            c.rings.removeIf(r -> clientTicks - r.born() > r.life());
            c.marks.removeIf(m -> clientTicks - m.born() > 64);
            if ((t > RainRules.END + 40 || c.lostTick >= 0 && clientTicks - c.lostTick > 40) && c.landed.isEmpty()) {
                it.remove();
            }
        }
        VfxBudget.setRain(rainLive());
    }

    /** Стойка, уколы, ветер от рук, натяжение замаха — всё, что исходит от тела мастера. */
    private static void body(Cast c, Entity e, int t, Minecraft mc) {
        if (c.stars >= 8 && t % 6 == 0)
            TechniqueLight.add(e.position().add(0, 1, 0), 0xE3264E, c.stars < 10 ? 1.2F : 3, .18F * PlumLayers.light(c.stars), 3, 3, 8);
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
        if (c.has(PlumLayers.Kind.PETALS) && t > 2 && t < RainRules.FLURRY && t % 3 == 0) {
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
            if (c.has(PlumLayers.Kind.GHOST) && e instanceof AbstractClientPlayer player
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
            int winds = Math.max(2, c.n(t >= RainRules.WIND_RISE ? 5 : 3));
            for (int i = 0; i < winds; i++) {
                Vec3 v = d.add(c.random.nextGaussian() * 0.25D, c.random.nextGaussian() * 0.2D, c.random.nextGaussian() * 0.25D)
                        .normalize().scale(0.25D + 0.3D * c.random.nextDouble());
                windRibbon(c, hand, v, 12, 0.05D + 0.04D * c.scale);
            }
            if (k % 2 == 0) {
                dust(c, feet, 2, 0.1D);
            }
            if (c.has(PlumLayers.Kind.PETALS)) {
                for (int i = 0; i < Math.max(3, c.n(16)); i++) {
                    Mote m = petalMote(c, hand.add(d.scale(2.4D + 1.3D * c.scale)),
                            d.scale(.06D + .08D * c.random.nextDouble())
                                    .add(c.random.nextGaussian() * .09D, c.random.nextGaussian() * .08D,
                                            c.random.nextGaussian() * .09D), 14 + c.random.nextInt(11));
                    m.tone = i % 3 == 0 ? 0 : 1;
                    m.turbulence = 0.01D;
                    c.motes.add(m);
                }
            }
            if (c.stars >= 10 && c.eyes()) {
                // От первого лица укол читается линиями скорости: точка схождения прыгает туда,
                // куда колет клинок, — шесть сторон видны на экране (r02).
                double[] dd = RainRules.DIRS[RainRules.ORDER[k % RainRules.ORDER.length]];
                float cx = (float) (0.5D + 0.42D * Math.sin(Math.toRadians(dd[0])));
                float cy = (float) (0.5D - 0.6D * Math.sin(Math.toRadians(dd[1])));
                if (c.stars >= 10) SpeedLines.radial(cx, cy, 0.3F + 0.05F * (k / 6), 3, SpeedLines.WHITE);
            } else if (c.stars >= 10 && c.own() && k % 6 == 0) {
                if (c.stars >= 10) SpeedLines.radial(0.5F, 0.5F, 0.35F + 0.1F * (k / 6), 5, SpeedLines.WHITE);
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
        if ((t == 50 || t == 55) && c.executionTier >= 5) {
            double a0 = Math.toRadians(yaw + (t == 50 ? 40.0D : 200.0D));
            c.winds.add(new WindArc(feet, clientTicks, a0, t == 50 ? 1 : -1, 3.2D * c.scale, 0.5D, 1.6D, 0.12D, 16, 0.28D * c.scale, 0.55D));
        }
        if ((t == 36 || t == 44 || t == 52) && c.executionTier >= 1) {
            dust(c, feet, c.n(8) + 1, 0.16D);
        }
        // Натяжение замаха (r04): лепестки и ветер втягиваются к клинку.
        if (t >= RainRules.LOAD && t < RainRules.RELEASE) {
            Vec3 tip = shoulder(e, yaw).add(0.0D, 0.8D, 0.0D).subtract(f.scale(0.4D));
            if (c.has(PlumLayers.Kind.PETALS)) {
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
                    && c.has(PlumLayers.Kind.GHOST)) {
                c.ghosts.add(new Ghost(e.position(), bodyYaw(e), clientTicks, 9, c.dashPose, false, 0xE3264E, 0.42F));
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
        if (c.swing != null && t >= RainRules.RELEASE + 2 && t < RainRules.TRAIL_HOLD && c.has(PlumLayers.Kind.PETALS) && c.random.nextInt(3) > 0) {
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
        // The flower stays above the victim; only its .3-block offset points away from the caster.
        Vec3 caster = e == null ? c.origin : e.position();
        Vec3 want = RainPresentation.centre(c.target, caster);
        c.corePrev = c.core == null ? want : c.core;
        c.core = want;
        if (t >= RainRules.BLOOM && t < RAIN_WAVES[0] && c.coreBorn < 0 && RainRules.shells(c.executionTier) > 0) {
            bloom(c);
        }
        if (t >= RainRules.STREAM && t < RAIN_WAVES[0] && !c.streamed) {
            c.streamed = true;
            streams(c);
        }
        // Медленные лепестки вокруг цели всё время иллюзии (r07).
        if (c.stars >= 10 && c.has(PlumLayers.Kind.PETALS) && t > RainRules.BLOOM && t < RainRules.COHORTS[0] && t % 5 == 0) {
            for (int i = 0; i < Math.max(1, c.n(2)); i++) {
                double a = c.random.nextDouble() * Math.PI * 2.0D;
                double r = 0.5D + 3.0D * c.random.nextDouble();
                Mote m = petalMote(c, c.target.add(Math.cos(a) * r, 3.5D + 2.5D * c.random.nextDouble(), Math.sin(a) * r),
                        new Vec3(0.0D, -0.03D, 0.0D), 70 + c.random.nextInt(30));
                m.drag = .917D;
                m.turbulence = 0.006D;
                m.tone = c.random.nextInt(8) == 0 ? 2 : c.random.nextInt(3) == 0 ? 0 : 1;
                c.motes.add(m);
            }
        }
        if (t == RainRules.CAPTION && c.own() && c.executionTier >= 3) {
            if (c.stars >= 10) TechniqueCaption.showSecret(Component.translatable("technique.murim.twenty_four_plum.school"),
                    Component.translatable("technique.murim.twenty_four_plum.rainfall"), RainRules.contact(2) + 34 - RainRules.CAPTION);
        }
        // Касания волн ливня толкают надпись: морось слабее, обрушение сильнее.
        for (int k = 0; k < RainRules.COHORTS.length; k++) {
            if (t == RainRules.contact(k) && c.own()) {
                if (c.stars >= 10) TechniqueCaption.impact(k == 2 ? 1.0F : 0.5F);
            }
        }
        // Ливень: три волны срываются вниз; вертикальные ленты — столб света.
        for (int k = 0; k < RainRules.COHORTS.length; k++) {
            if (t == RAIN_WAVES[k]) {
                for (SkyPetal s : c.sky) {
                    if (s.cohort == k && !s.dead && !s.free && s.fallTick < 0) {
                        s.fallTick = clientTicks;
                        // Rim first; retain each interior petal until its own falling path begins.
                        s.fallFrom = s.pos;
                        // Keep the column as broad as the flower. Only a sparse subset lands
                        // in the broken splash annulus; all other petals melt on contact.
                        if (frac(s.w * 6.7D) < .22D) {
                            s.fallTo = splashLanding(c, s.w);
                        } else {
                            s.fallTo = new Vec3(s.fallFrom.x, c.target.y + .07D, s.fallFrom.z);
                        }
                    }
                }
                int beams = c.has(PlumLayers.Kind.THREADS) ? RainPresentation.beamCount(c.executionTier, k) : 0;
                for (int i = 0; i < beams; i++) {
                    double a = c.random.nextDouble() * Math.PI * 2.0D;
                    double r = rainSpread(c) * Math.sqrt(c.random.nextDouble());
                    c.beams.add(new Beam(Math.cos(a) * r, Math.sin(a) * r, clientTicks,
                            3.0D + 3.0D * c.random.nextDouble(), 0.03D + 0.05D * c.random.nextDouble(),
                            PlumLayers.rainFlowerHeight(c.executionTier), c.random.nextInt(3) == 0));
                }
            }
        }
        for (int k = 0; k < RAIN_WAVES.length; k++) {
            if (!c.has(PlumLayers.Kind.THREADS) || t != RAIN_WAVES[k]) continue;
            int count = RainPresentation.lanceCount(c.executionTier);
            count = k == 2 ? count - count / 6 - count * 2 / 6 : count * (k + 1) / 6;
            count = Math.min(count, Math.max(0, VfxBudget.rainRoom() - rainLive()));
            for (int i = 0; i < count; i++) {
                double angle = c.random.nextDouble() * Math.PI * 2;
                boolean ground = i % 4 == 0;
                double radius = ground ? RainRules.RAIN_RADIUS * Math.sqrt(c.random.nextDouble()) : .22 * c.random.nextDouble();
                var victim = mc.level == null ? null : mc.level.getEntity(c.targetId);
                if (victim == null) continue;
                Vec3 eye = victim.getEyePosition();
                Vec3 to = ground ? c.target.add(Math.cos(angle) * radius, -.12, Math.sin(angle) * radius)
                        : RainPresentation.bodyEnd(victim.position(), eye, victim.getBbWidth(), victim.getBbHeight(), angle);
                if (!RainPresentation.eyeSafe(to, eye)) continue;
                double height = c.core.y - to.y;
                double slant = Math.toRadians(10 + 15 * c.random.nextDouble());
                Vec3 from = to.add(Math.cos(angle) * height * Math.tan(slant), height, Math.sin(angle) * height * Math.tan(slant));
                Vec3 approach = ground ? to : to.add(Math.cos(angle) * .75, .2, Math.sin(angle) * .75);
                c.lances.add(new Lance(from, approach, to, clientTicks + c.random.nextInt(7),
                        12 / (.7 + .7 * c.random.nextDouble()), .18 + .07 * c.random.nextDouble(),
                        1.5 + 1.5 * c.random.nextDouble(), 10 + c.random.nextInt(2)));
            }
        }
        if (t >= RainRules.BLOOM && t <= RainPresentation.rainEnd() && t % 6 == 0) {
            float strength = (float) RainPresentation.smooth((t - RainRules.BLOOM) / 10) * PlumLayers.light(c.stars);
            TechniqueLight.add(c.target.add(0, 1, 0), 0xE3264E, 5, .4F * strength, 3, 3, 6);
            if (e != null) TechniqueLight.add(e.position().add(0, 1, 0), 0xE3264E, 3, .18F * strength, 3, 3, 6);
        }
        // The caster's view is never turned (author 10.10): the petals above are for the opponent to see;
        // the player looks around himself if he wants.
    }

    private static double targetHeight(Cast c) {
        var world = Minecraft.getInstance().level;
        var target = world == null ? null : world.getEntity(c.targetId);
        return target == null ? 1.8 : target.getBbHeight();
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
            if (c.t() >= RainRules.BLOOM && m.kind == Mote.PETAL) m.drag = .917;
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
    }

    /** Лепестки ядра: орбиты (дыхание, качание), нити к цели, падение. */
    // API verified in /tmp/mr-ref-1121/minecraft-src: LevelReader#hasChunkAt(BlockPos),
    // BlockGetter#clip(ClipContext), ClipContext(Vec3,Vec3,Block,Fluid,CollisionContext),
    // AABB#contains(Vec3), #clip(Vec3,Vec3), HitResult#getType/getLocation,
    // ClientLevel#getEntity(int), Entity#getBoundingBox; Vec3 and Mth#clamp overloads.
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
                // A short 1–2 block rising ring, then gravity wins; no hovering floor carpet.
                s.vel = s.vel.scale(.96D).add(0.0D, -.026D, 0.0D);
                Vec3 moved = s.pos.add(s.vel);
                s.pos = moved;
                // Melt before floor contact; never accumulate clamped, horizontal survivors.
                if (s.pos.y < c.target.y + .08 && s.vel.y < 0) s.dead = true;
                if (s.freeAge > 24 + (int) (frac(s.w * 7.3) * 16)) {
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
                var world = Minecraft.getInstance().level;
                if (world.hasChunkAt(BlockPos.containing(s.prev)) && world.hasChunkAt(BlockPos.containing(s.pos))) {
                    var block = world.clip(new net.minecraft.world.level.ClipContext(s.prev, s.pos,
                            net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE,
                            net.minecraft.world.phys.shapes.CollisionContext.empty()));
                    Vec3 contact = block.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? null : block.getLocation();
                    var target = world.getEntity(c.targetId);
                    if (target != null) {
                        var body = target.getBoundingBox();
                        Vec3 at = body.contains(s.prev) ? s.prev : body.clip(s.prev, s.pos).orElse(null);
                        if (at != null && (contact == null || s.prev.distanceToSqr(at) < s.prev.distanceToSqr(contact))) contact = at;
                    }
                    if (contact != null) {
                        s.pos = contact;
                        s.dead = true;
                        for (int bit = 0; bit < 3 && c.motes.size() < 256; bit++) {
                            Mote fragment = petalMote(c, contact, new Vec3((bit - 1) * .05, .03, -.02), 4);
                            fragment.tone = 2;
                            c.motes.add(fragment);
                        }
                        continue;
                    }
                }
                if (u >= 1.0D) {
                    if (frac(s.w * 6.7D) >= .22D) {
                        s.dead = true;
                        continue;
                    }
                    s.free = true;
                    Vec3 out = new Vec3(s.pos.x - c.target.x, 0, s.pos.z - c.target.z).normalize();
                    s.vel = out.scale(.08D + .07D * frac(s.w * 3.7D))
                            .add(0, .28D + .08D * frac(s.w * 5.1D), 0);
                    s.freeAge = 0;
                }
                continue;
            }
            double held = Math.min(t, RainRules.STILL);
            s.pos = s.stream >= 0 ? streamPoint(c, s, held) : shellPoint(c, s, held);
            if (s.prev == Vec3.ZERO) {
                s.prev = s.pos;
            }
        }
        if (t > RainRules.contact(2) + 4) {
            c.sky.removeIf(s -> s.dead);
        }
    }

    private static Vec3 shellPoint(Cast c, SkyPetal s, double t) {
        // Лепесток живёт внутри своей доли: медленно колышется по s и l, чуть выходит из плоскости.
        double sPos = Mth.clamp(s.a + 0.04D * Math.sin(t * 0.09D + s.w), 0.05D, 1.0D);
        double l = Mth.clamp(s.e + 0.08D * Math.sin(t * 0.11D + s.w * 1.7D), -1.0D, 1.0D);
        // Объём: лепестки расходятся в толщину чаши до ±0,9 блока у сердца, тоньше к краю.
        double depth = (.78D - .16D * s.a) * Math.sin(s.w * 3.0D) + 0.08D * Math.sin(t * 0.13D + s.w);
        Vec3 home = lobePoint(c, s.shell, sPos, l, t, depth * c.scale);
        double d = drift(t, s.w);
        if (d <= 0.0D) {
            return home;
        }
        // Каждый лепесток ползёт к своей точке над целью (облако 2–3 блока, 4,5–6 над ногами),
        // проходит лишь часть пути и висит там до ливня: «не близко и медленно».
        double ang = s.w * 5.3D;
        double rr = (1.6D + 1.8D * frac(s.w * 2.9D)) * c.scale;
        Vec3 aim = c.target.add(Math.cos(ang) * rr, 4.5D + 1.5D * frac(s.w * 4.1D), Math.sin(ang) * rr);
        return home.lerp(aim, d * RainRules.DRIFT_SHARE * (0.75D + 0.4D * frac(s.w * 1.7D)));
    }

    private static double frac(double x) {
        return x - Math.floor(x);
    }

    /** Each petal descends its own spiral from a lobe to shoulder height before the first wave. */
    private static Vec3 streamPoint(Cast c, SkyPetal s, double t) {
        double age = Math.max(0, t - RainRules.STREAM - s.delay);
        double u = Math.min(1, age * s.uSpeed / 22);
        Vec3 start = lobePoint(c, s.stream, .72, 0, t, 0);
        Vec3 end = c.target.add(Math.cos(s.stream * 1.26) * .3, targetHeight(c) * .7,
                Math.sin(s.stream * 1.26) * .3);
        double phase = age * (.13 + .025 * s.stream) + s.w;
        double radius = .18 * Math.sin(Math.PI * u);
        return start.lerp(end, u).add(Math.cos(phase) * radius,
                .07 * Math.sin(phase * 1.3) * Math.sin(Math.PI * u), Math.sin(phase) * radius);
    }

    // ------------------------------------------------------------------ рендер

    // Author choreography: blade first, then the target-centred illusion and three cohorts.
    @SubscribeEvent
    static void onRender(RenderLevelStageEvent event) {
        syncLevel();
        // Restore fields even if another subscriber cancelled after our Pre (there is then no Post).
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) restoreVictim();
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            rainSky(event);
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || CASTS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        PoseStack ps = event.getPoseStack();
        var buffers = mc.renderBuffers().bufferSource();
        ps.pushPose();
        try {
            ps.translate(-camera.x, -camera.y, -camera.z);
            for (Cast c : CASTS) {
                // A cast may render before its origin arrives; skip only capture until then.
                if (PlumLayerCapture.enabled() && c.origin != null) c.capture.begin("RAIN", (int) c.t(), c.entityId, c.target == null ? c.origin.y : c.target.y,
                        c.target == null ? java.util.List.of() : java.util.List.of(c.target.add(0, 1, 0)), "RainVfx.onRender");
                models(mc, c, ps, buffers, partial);
                float t = c.t() + partial;
                PlumLayerCapture.layer("RainVfx#puffs");
                puffs(c, ps.last(), camera, buffers, partial);
                PlumLayerCapture.layer("RainVfx#petals");
                petals(c, ps.last(), camera, buffers, partial, t);
                paintedRain(c, ps.last(), camera, buffers, partial, t);
                RenderType bands = MurimRenderTypes.airBand();
                VertexConsumer v = buffers.getBuffer(bands);
                PlumLayerCapture.layer("RainVfx#thrusts");
                thrusts(c, ps.last(), camera, v, partial);
                PlumLayerCapture.layer("RainVfx#winds");
                winds(c, ps.last(), camera, v, partial);
                PlumLayerCapture.layer("RainVfx#ribbons");
                if (t < RainRules.BLOOM) ribbons(c, ps.last(), camera, v, partial);
                PlumLayerCapture.layer("RainVfx#swing");
                swing(c, ps.last(), camera, v, t);
                if (c.target != null) {
                    PlumLayerCapture.layer("RainVfx#groundLight");
                    groundLight(c, ps.last(), v, t);
                    coreRibbons(c, ps.last(), camera, v, t);
                    rain(c, ps.last(), camera, v, partial);
                    impact(c, ps.last(), camera, v, partial);
                }
                buffers.endBatch(bands);
                c.capture.end();
            }
        } finally { PlumLayerCapture.clear(); ps.popPose(); }
    }


    /** Остаточные образы: рука с мечом на каждом уколе (r02) и тело на проходе. */
    private static void models(Minecraft mc, Cast c, PoseStack ps, MultiBufferSource.BufferSource buffers, float partial) {
        if (!c.has(PlumLayers.Kind.GHOST)) return;
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
        if (!c.has(PlumLayers.Kind.TRAIL)) return;
        for (Thrust th : c.thrusts) {
            float age = clientTicks - th.born() + partial;
            float a = (float) PlumVfx.curve(age, 0.0, 1.0, 1.0, 0.95, 4.0, 0.0);
            if (a <= 0.0F) {
                continue;
            }
            double reach = (2.4D + 1.3D * c.scale) * Mth.clamp(age / 0.8D, 0.15D, 1.0D);
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
            Vec3 tip = th.hand().add(th.dir().scale(reach));
            Vec3[] p = {tail, tail.lerp(tip, .08), tail.lerp(tip, .86), tip};
            double width = .08D + .09D * c.scale;
            double[] w = {0, width, width, 0};
            fstrip(v, pose, camera, p, w, .85F * a, TRAIL_RIM);
            fstrip(v, pose, camera, p, PlumVfx.scale(w, .55), .95F * a, TRAIL_CORE);
        }
    }

    /** Восходящие ленты ветра (r03): хвост — та же спираль в прошлые тики. */
    private static void winds(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        if (!c.has(PlumLayers.Kind.WIND)) return;
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
        if (!c.has(PlumLayers.Kind.WIND) && !c.has(PlumLayers.Kind.TRAIL)) return;
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
                if (!c.has(PlumLayers.Kind.WIND)) continue;
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
        if (!c.has(PlumLayers.Kind.TRAIL)) return;
        if (c.executionTier < 3 || c.swing == null) {
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
        boolean pink = c.has(PlumLayers.Kind.PETALS);
        for (int i = 0; i <= m; i++) {
            double u = i / (double) n;
            p[i] = c.swing[Math.min(n, i)];
            // Сужение к обоим концам; голова ярче, пока рисуется.
            double taper = Math.sin(Math.PI * Math.min(1.0D, u * 1.02D + 0.01D));
            w[i] = (.14D + .32D * c.scale) * taper;
            if (i == 0 || i == m) w[i] = 0;
            double headGlow = x < 1.0D ? Math.max(0.0D, 1.0D - (drawn - u) * 4.0D) : 0.0D;
            a[i] = (float) Mth.clamp((0.75D + 0.25D * headGlow) * fade, 0.0D, 1.0D);
        }
        VfxColour rim = pink ? TRAIL_RIM : COLD;
        ribbon(v, pose, camera, p, w, PlumVfx.scaled(a, .8F), rim);
        ribbon(v, pose, camera, p, PlumVfx.scale(w, .52D), a, TRAIL_CORE);
        // 6 Nov: release crescent removed; the straight dash trail above owns the swing.
        // Белая зубчатая вспышка в момент резкой смены движения (r04).
        if (c.stars >= 10 && s < 3.5F) {
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

    /** Reused ground light, crimson and driven by the bloom envelope. */
    private static void groundLight(Cast c, PoseStack.Pose pose, VertexConsumer v, float t) {
        if (!c.has(PlumLayers.Kind.AURA)) return;
        if (c.executionTier < 3 || c.lostTick >= 0) return;
        float a = (float) PlumVfx.curve(t, RainRules.MARK, 0.0, RainRules.BLOOM_FULL, 1.0, RainRules.contact(2), 1.0,
                RainRules.AFTER + 10, 0.4, RainRules.END, 0.0);
        if (a <= 0.01F) {
            return;
        }
        a *= (float) RainPresentation.smooth((t - RainRules.BLOOM) / 10);
        double rMax = c.stars < 10 ? 1.2D : 5.5D * c.scale;
        a *= PlumLayers.light(c.stars);
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

    private static void puffs(Cast c, PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float partial) {
        if (!c.has(PlumLayers.Kind.DUST)) return;
        if (c.t() >= RainRules.BLOOM || c.puffs.isEmpty()) {
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

    }

    /** Лепестки (атлас) и их свечение; цветок-ядро — сотни лепестков на орбитах и глоу сердцевины. */
    private static double petalDepth(Vec3 at, Vec3 camera) {
        return 1 - Math.max(0, Math.min(1, (at.distanceTo(camera) - 7) / 28));
    }

    private static double petalSize(Vec3 at, Vec3 camera, double size) {
        // Build radius is the far-plane floor; nearer petals add mass rather than remove it.
        return Math.min(.20D, size * 1.6D) * (1 + .35D * petalDepth(at, camera));
    }

    private static float[] tint(int tone) {
        return switch (tone) {
            case 0 -> new float[] {1.0F, 239 / 255F, 243 / 255F};
            case 3 -> new float[] {1.0F, 239 / 255F, 243 / 255F};
            case 2 -> new float[] {74 / 255F, 13 / 255F, 34 / 255F};
            default -> new float[] {227 / 255F, 38 / 255F, 78 / 255F};
        };
    }

    /** Existing opening preserved; pilot 2 starts at BLOOM. */
    private static void openingPetals(Cast c, PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float partial) {
        if (!c.has(PlumLayers.Kind.PETALS)) return;
        RenderType pt = MurimRenderTypes.plumPetals();
        VertexConsumer pc = buffers.getBuffer(pt);
        PlumLayerCapture.layer("RainVfx#petals.motes");
        for (Mote m : c.motes) {
            if (m.kind != Mote.PETAL) {
                continue;
            }
            Vec3 at = m.prev.lerp(m.pos, partial);
            if (at.distanceToSqr(camera) < 4.0D) {
                continue;
            }
            float a = Mth.clamp((m.life - m.age - partial) / 10.0F, 0.0F, 1.0F) * Mth.clamp((m.age + partial) / 3.0F, 0.0F, 1.0F)
                    * near(at, camera.add(0.0D, 0.0D, 0.0D));
            float[] tint = tint(m.tone);
            PlumVfx.paintedPetal(pc, pose, camera, at, petalSize(at, camera, m.size), m.cell, (m.age + partial) * m.spin, a, tint[0], tint[1], tint[2]);
        }
        buffers.endBatch(pt);
        RenderType gt = TechniqueGlow.want(.65F * PlumLayers.light(c.stars)) ? TechniqueGlow.textured(
                ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/mote.png")) : MurimRenderTypes.mote();
        VertexConsumer g = buffers.getBuffer(gt);
        PlumLayerCapture.layer("RainVfx#petals.motes");
        for (Mote m : c.motes) {
            if (m.kind != Mote.PETAL || m.cell != 0) {
                continue;
            }
            Vec3 at = m.prev.lerp(m.pos, partial);
            if (at.distanceToSqr(camera) > 1.7D) {
                float a = Mth.clamp((m.life - m.age - partial) / 10.0F, 0.0F, 1.0F);
                PlumVfx.glow(g, pose, camera, at, m.size * 2.0D, (0.32F * a * near(at, camera)) * PlumLayers.light(c.stars), m.tone == 0 ? SKY_WHITE : hex(0xE3264E));
            }
        }
        buffers.endBatch(gt);
    }

    private static void petals(Cast c, PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float partial, float t) {
        if (!c.has(PlumLayers.Kind.PETALS)) return;
        if (!c.has(PlumLayers.Kind.PETALS)) {
            return;
        }
        if (t < RainRules.BLOOM) {
            openingPetals(c, pose, camera, buffers, partial);
            return;
        }
        RenderType pt = MurimRenderTypes.plumPetals();
        VertexConsumer pc = buffers.getBuffer(pt);
        PlumLayerCapture.layer("RainVfx#petals.motes");
        for (Mote m : c.motes) {
            if (m.kind != Mote.PETAL) {
                continue;
            }
            Vec3 at = m.prev.lerp(m.pos, partial);
            if (at.distanceToSqr(camera) < 4.0D) {
                continue;
            }
            float a = Mth.clamp((m.life - m.age - partial) / 10.0F, 0.0F, 1.0F) * Mth.clamp((m.age + partial) / 3.0F, 0.0F, 1.0F)
                    * near(at, camera.add(0.0D, 0.0D, 0.0D));
            float[] tint = tint(m.tone);
            PlumVfx.paintedPetal(pc, pose, camera, at, petalSize(at, camera, m.size), m.cell, (m.age + partial) * m.spin, a, tint[0], tint[1], tint[2]);
        }
        float skyA = 1.0F;
        PlumLayerCapture.layer("RainVfx#petals.sky");
        for (SkyPetal s : c.sky) {
            if (c.stars < 10) continue;
            if (s.dead || s.stream >= 0) {
                continue;
            }
            Vec3 at = s.prev.lerp(s.pos, partial);
            if (at.distanceToSqr(camera) < 4.0D) {
                continue;
            }
            float a = skyA * (s.free ? Mth.clamp(1.0F - (s.freeAge + partial) / (24.0F + (int) (frac(s.w * 7.3) * 16)), 0.0F, 1.0F) : 1.0F)
                    * appear(c, s, t);
            if (s.free && s.vel.y < 0) a *= (float) PlumBeats.smooth((at.y - c.target.y - .08) / .65);
            float[] tint = s.stream < 0 && s.tone == 1 ? lobeTint(s.a) : tint(s.tone);

            PlumVfx.paintedPetal(pc, pose, camera, at, petalSize(at, camera, s.size) * (s.free ? .4D + .6D * a : 1), s.cell, (t + s.cell * 7.0F) * s.spin * 0.6F, a * near(at, camera),
                    tint[0], tint[1], tint[2]);
        }
        buffers.endBatch(pt);
        RenderType gt = TechniqueGlow.want(.65F * PlumLayers.light(c.stars)) ? TechniqueGlow.textured(
                ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/vfx/mote.png")) : MurimRenderTypes.mote();
        VertexConsumer g = buffers.getBuffer(gt);
        PlumLayerCapture.layer("RainVfx#petals.motes");
        for (Mote m : c.motes) {
            if (m.kind != Mote.PETAL || m.cell != 0) {
                continue;
            }
            Vec3 at = m.prev.lerp(m.pos, partial);
            if (at.distanceToSqr(camera) > 1.7D) {
                float a = Mth.clamp((m.life - m.age - partial) / 10.0F, 0.0F, 1.0F);
                PlumVfx.glow(g, pose, camera, at, m.size * 2.0D, (0.32F * a * near(at, camera)) * PlumLayers.light(c.stars), m.tone == 0 ? SKY_WHITE : SKY_MILK);
            }
        }
        PlumLayerCapture.layer("RainVfx#petals.sky");
        for (SkyPetal s : c.sky) {
            if (s.cell != 0 || s.dead || !s.free && s.fallTick >= 0 && clientTicks >= s.fallTick) {
                continue;
            }
            Vec3 at = s.prev.lerp(s.pos, partial);
            float a = appear(c, s, t) * (s.free ? Mth.clamp(1.0F - (s.freeAge + partial) / (24.0F + (int) (frac(s.w * 7.3) * 16)), 0.0F, 1.0F) : 1.0F);
            if (s.free && s.vel.y < 0) a *= (float) PlumBeats.smooth((at.y - c.target.y - .08) / .65);
            // Светятся изнутри: белая точка в сердце лепестка, розовый ореол.
            a *= near(at, camera);
            PlumVfx.glow(g, pose, camera, at, s.size * 2.2D, (0.26F * a) * PlumLayers.light(c.stars), s.tone == 2 ? BLOOD : s.stream < 0 && s.a > 0.6D ? LOBE_OUT : SKY_MILK);
            PlumVfx.glow(g, pose, camera, at, s.size * 0.7D, (0.55F * a) * PlumLayers.light(c.stars), SKY_WHITE);
        }
        // Ливень: яркая голова падающего лепестка — видно, что всё летит ВНИЗ.
        PlumLayerCapture.layer("RainVfx#petals.sky");
        for (SkyPetal s : c.sky) {
            if (s.free || s.fallTick < 0 || clientTicks < s.fallTick || s.dead) {
                continue;
            }
            double u = Mth.clamp((clientTicks - s.fallTick + partial) / RainRules.FALL, 0.0D, 1.0D);
            Vec3 head = fall(s, u);
            PlumVfx.glow(g, pose, camera, head, 0.16D, (0.9F * near(head, camera)) * PlumLayers.light(c.stars), SKY_WHITE);
            PlumVfx.glow(g, pose, camera, head, 0.34D, (0.3F * near(head, camera)) * PlumLayers.light(c.stars), s.tone == 2 ? BLOOD : RAIN_PINK);
        }
        // Сердцевина ядра: десятки мелких глоу на своих орбитах (каждый ≤0,24 — чёткость).
        if (c.has(PlumLayers.Kind.CAPTION)) for (Ring ring : c.rings) {
            double age = clientTicks + partial - ring.born();
            if (age >= 0 && age < 2)
                PlumVfx.glow(g, pose, camera, ring.centre(), 1, (float) (1 - age / 2), SKY_WHITE);
        }
        PlumLayerCapture.layer("RainVfx#petals.coreGlows");
        if (c.core != null && c.coreBorn >= 0) {
            Vec3 core = c.corePrev == null ? c.core : c.corePrev.lerp(c.core, partial);
            float grow = (float) Mth.clamp((clientTicks - c.coreBorn + partial) / 20.0D, 0.0D, 1.0D);
            float out = (float) Mth.clamp(1.0D - (t - RAIN_WAVES[2]) / RainRules.FALL, 0.0D, 1.0D)
                    * (c.lostTick >= 0 ? Mth.clamp(1.0F - (clientTicks - c.lostTick + partial) / 12.0F, 0.0F, 1.0F) : 1.0F);
            for (int k = 0; k < 5; k++) {
                for (double sp : new double[] {0.25D, 0.5D, 0.75D}) {
                    Vec3 lp = lobePoint(c, k, sp, 0.0D, t, 0.0D);
                    double hw = FLOWER_R * c.scale * LOBE_LEN[k] * 0.32D * Math.sin(Math.PI * sp);
                    PlumVfx.glow(g, pose, camera, lp, hw, (0.1F * grow * out * (float) (io.github.verycooltimo.murim.technique.PlumPresentationRules.flowerAlpha(t, sp, RAIN_WAVES))) * PlumLayers.light(c.stars), sp < 0.4D ? SKY_MILK : LOBE_IN);
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
                PlumVfx.glow(g, pose, camera, tip, 0.14D * c.scale, (0.85F * grow * out) * PlumLayers.light(c.stars), k % 4 == 0 ? BLOOD : LOBE_IN);
            }
            for (double[] q : c.coreGlows) {
                double a = q[0] + q[2] * t;
                double r = q[1] * c.scale * grow;
                Vec3 p = core.add(c.dir.scale(Math.cos(a) * r)).add(c.side.scale(Math.sin(a) * r * Math.cos(q[3])))
                        .add(0.0D, Math.sin(a) * r * Math.sin(q[3]), 0.0D);
                PlumVfx.glow(g, pose, camera, p, 0.08D + 0.16D * ((q[1] * 7.0D) % 1.0D), (0.6F * grow * out) * PlumLayers.light(c.stars), SKY_WHITE);
            }
            // Белое сердце 1,4 блока (r08) и широкая засветка неба: ступени, без размытия кадра.
            PlumVfx.glow(g, pose, camera, core, 0.6D * c.scale, (1.0F * grow * out) * PlumLayers.light(c.stars), HEART);
            PlumVfx.glow(g, pose, camera, core, 1.1D * c.scale, (0.9F * grow * out) * PlumLayers.light(c.stars), HEART);
            PlumVfx.glow(g, pose, camera, core, 2.2D * c.scale, (0.32F * grow * out) * PlumLayers.light(c.stars), hex(0xE3264E));
            PlumVfx.glow(g, pose, camera, core, 0.9D * c.scale, (0.95F * grow * out) * PlumLayers.light(c.stars), HEART);
            PlumVfx.glow(g, pose, camera, core, 1.8D * c.scale, (0.65F * grow * out) * PlumLayers.light(c.stars), HEART);
            PlumVfx.glow(g, pose, camera, core, 3.2D * c.scale, (0.2F * grow * out) * PlumLayers.light(c.stars), SKY_MILK);
            PlumVfx.glow(g, pose, camera, core, 7.5D * c.scale, (0.08F * grow * out) * PlumLayers.light(c.stars), SKY_LILAC);
        }
        // Вспышка обрушения: белое ядро в точке удара, гаснет в розовое.
        if (c.hitFinal >= 0 && c.hitAt != null) {
            float age = clientTicks - c.hitFinal + partial;
            float a = (float) PlumVfx.curve(age, 0.0, 1.0, 3.0, 0.8, 16.0, 0.25, 36.0, 0.0);
            if (a > 0.0F) {
                PlumVfx.glow(g, pose, camera, c.hitAt, 1.0D + 0.08D * age, (0.6F * a) * PlumLayers.light(c.stars), age < 4.0F ? SKY_WHITE : SKY_MILK);
                PlumVfx.glow(g, pose, camera, c.hitAt, 2.6D + 0.1D * age, (0.25F * a) * PlumLayers.light(c.stars), RAIN_PINK);
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

    /** Two hinged halves with a dark reverse face, retained for streams and ground petals. */
    private static void paintedPetal(VertexConsumer v, PoseStack.Pose pose, Vec3 at, Vec3 right, Vec3 up,
                                      Vec3 normal, double size, int cell, float alpha, float tone, double fold) {
        Vec3 r = right.scale(size / 2);
        Vec3 u = up.scale(size / 2);
        Vec3 ridge = normal.scale(size * fold);
        float u0 = (cell % 4) / 4F, u1 = u0 + .25F, mid = (u0 + u1) / 2;
        float v0 = (cell / 4) / 3F, v1 = v0 + 1 / 3F;
        texturedQuad(v, pose, at.subtract(r).subtract(u), at.subtract(r).add(u), at.add(u).add(ridge), at.subtract(u).add(ridge),
                u0, mid, v0, v1, alpha, tone);
        texturedQuad(v, pose, at.subtract(u).add(ridge), at.add(u).add(ridge), at.add(r).add(u), at.add(r).subtract(u),
                mid, u1, v0, v1, alpha, cell >= 4 && cell < 8 ? tone * .54F : tone * .85F);
    }

    private static void atlas(VertexConsumer v, PoseStack.Pose pose, Vec3 at, Vec3 right, Vec3 up,
                              int cell, float alpha, float tone) {
        // Tight lance crops measured from sheet_c alpha bounds. Full-cell UVs would make a
        // .2-block quad paint a .02-block needle: most of these cells is empty canvas.
        float u0 = cell == 10 ? .609375F : .8639323F;
        float u1 = cell == 10 ? .63671875F : .8828125F;
        float v0 = cell == 10 ? .66796875F : .6796875F;
        texturedQuad(v, pose, at.subtract(right).subtract(up), at.subtract(right).add(up),
                at.add(right).add(up), at.add(right).subtract(up), u0, u1, v0, .966796875F, alpha, tone);
    }

    private static void texturedQuad(VertexConsumer v, PoseStack.Pose pose, Vec3 a, Vec3 b, Vec3 c, Vec3 d,
                                     float u0, float u1, float v0, float v1, float alpha, float tone) {
        if (alpha <= 0) return;
        Vec3 n = b.subtract(a).cross(d.subtract(a)).normalize();
        VfxDraw.vertex(v, pose, a, n, u0, v1, alpha, tone, tone, tone);
        VfxDraw.vertex(v, pose, b, n, u0, v0, alpha, tone, tone, tone);
        VfxDraw.vertex(v, pose, c, n, u1, v0, alpha, tone, tone, tone);
        VfxDraw.vertex(v, pose, d, n, u1, v1, alpha, tone, tone, tone);
    }

    // API: read-only minecraft-src/net/minecraft/world/{level/BlockGetter.java#clip,
    // level/ClipContext.java,phys/AABB.java#clip,phys/HitResult.java#getLocation}.
    private static Vec3 blockContact(Vec3 from, Vec3 to) {
        var world = Minecraft.getInstance().level;
        if (world == null || !world.hasChunkAt(BlockPos.containing(from)) || !world.hasChunkAt(BlockPos.containing(to))) return null;
        var hit = world.clip(new net.minecraft.world.level.ClipContext(from, to,
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE,
                net.minecraft.world.phys.shapes.CollisionContext.empty()));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? null : hit.getLocation();
    }

    private static void tickLances(Cast c) {
        c.contacts.removeIf(contact -> clientTicks - contact.born >= 4);
        c.landed.removeIf(petal -> clientTicks - petal.born >= petal.life);
        if (c.lostTick >= 0) return;
        var world = Minecraft.getInstance().level;
        if (world == null) return;
        Iterator<Lance> iterator = c.lances.iterator();
        while (iterator.hasNext()) {
            Lance lance = iterator.next();
            double age = clientTicks - lance.born;
            if (age < 0) continue;
            Vec3 previous = RainPresentation.lancePoint(lance.from, lance.approach, lance.to, Math.max(0, Math.min(1, (age - 1) / lance.duration)));
            Vec3 current = RainPresentation.lancePoint(lance.from, lance.approach, lance.to, Math.min(1, age / lance.duration));
            Vec3 contact = blockContact(previous, current);
            var victim = world.getEntity(c.targetId);
            if (victim != null && victim.isAlive()) {
                var body = victim.getBoundingBox();
                // AABB#clip excludes a hit exactly at t=1. Probe just beyond the endpoint;
                // keep the rendered lance capped at to and use only the returned surface point.
                Vec3 probe = age >= lance.duration ? current.add(current.subtract(lance.approach).normalize().scale(.001)) : current;
                Vec3 bodyHit = body.clip(previous, probe).orElse(null);
                if (bodyHit != null && RainPresentation.eyeSafe(bodyHit, victim.getEyePosition()) && (contact == null || previous.distanceToSqr(bodyHit) < previous.distanceToSqr(contact))) contact = bodyHit;
            }
            if (contact == null && age < lance.duration) continue;
            iterator.remove();
            if (contact == null) continue; // A miss cannot paint a body splash.
            if (victim != null && !RainPresentation.eyeSafe(contact, victim.getEyePosition())) continue;
            if (c.contacts.size() < 20 && rainLive() + 3 <= VfxBudget.rainRoom()) c.contacts.add(new Contact(contact, clientTicks, c.random.nextDouble() * Math.PI * 2));
            if (c.landed.size() < 80 && rainLive() < VfxBudget.rainRoom()) {
                double angle = c.random.nextDouble() * Math.PI * 2;
                double radius = RainRules.RAIN_RADIUS * Math.sqrt(c.random.nextDouble());
                Vec3 above = c.target.add(Math.cos(angle) * radius, 2, Math.sin(angle) * radius);
                Vec3 floor = blockContact(above, above.add(0, -6, 0));
                if (floor != null) c.landed.add(new Landed(floor.add(0, .025 + c.random.nextDouble() * .015, 0),
                        clientTicks, 60 + c.random.nextInt(101), c.random.nextInt(8), .3 + .25 * c.random.nextDouble(), angle));
            }
        }
    }

    // API: read-only neoforge-src/net/neoforged/neoforge/client/event/RenderLivingEvent.java;
    // minecraft-src/net/minecraft/world/entity/Entity.java#getXRot,setXRot,xRotO,getEyePosition;
    // LivingEntity.java#yHeadRot,yHeadRotO,yBodyRot; LivingEntityRenderer.java#render (Pre before interpolation).
    private record SavedPose(LivingEntity entity, float pitch, float oldPitch, float yaw, float oldYaw, PoseStack stack, float bodyYaw, float lean) {}
    private static SavedPose savedPose;

    private static void restoreVictim() {
        if (savedPose == null) return;
        SavedPose saved = savedPose;
        savedPose = null;
        saved.entity.setXRot(saved.pitch);
        saved.entity.xRotO = saved.oldPitch;
        saved.entity.yHeadRot = saved.yaw;
        saved.entity.yHeadRotO = saved.oldYaw;
    }

    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    static void victimPre(RenderLivingEvent.Pre<?, ?> event) {
        // Run after cancellers. A later cancellation omits Post; AFTER_LEVEL restores fields as a fallback.
        restoreVictim();
        LivingEntity entity = event.getEntity();
        if (!entity.isAlive() || event.isCanceled()) return;
        Cast selected = null;
        double weight = 0;
        for (Cast c : CASTS) if (c.targetId == entity.getId() && c.entityId != entity.getId() && c.core != null && c.lostTick < 0) {
            double next = RainPresentation.poseWeight(c.t() + event.getPartialTick());
            if (next > weight) { selected = c; weight = next; }
        }
        if (selected == null || weight <= 0) return;
        PoseStack stack = event.getPoseStack();
        savedPose = new SavedPose(entity, entity.getXRot(), entity.xRotO, entity.yHeadRot, entity.yHeadRotO, stack, entity.yBodyRot, (float) (-8 * weight));
        Vec3 toward = selected.core.subtract(entity.getEyePosition(event.getPartialTick()));
        double desired = -Math.toDegrees(Math.atan2(toward.y, Math.hypot(toward.x, toward.z))) + 8 * weight;
        float pitch = (float) Mth.clamp(entity.getXRot() * (1 - weight)
                + RainPresentation.headPitch(selected.t() + event.getPartialTick(), desired), -90, 90);
        entity.setXRot(pitch);
        entity.xRotO = pitch;
        // Preserve yaw: the flower is directly overhead, so no unnatural head swivel is needed.
        // Dispatcher owns the surrounding pose frame: cancellation safely discards this lean.
        // API: read-only minecraft-src/net/minecraft/client/renderer/entity/EntityRenderDispatcher.java#render.
        stack.mulPose(Axis.YP.rotationDegrees(-entity.yBodyRot));
        stack.mulPose(Axis.XP.rotationDegrees((float) (-8 * weight)));
        stack.mulPose(Axis.YP.rotationDegrees(entity.yBodyRot));
    }

    @SubscribeEvent
    static void victimPost(RenderLivingEvent.Post<?, ?> event) {
        if (savedPose != null && savedPose.entity == event.getEntity()) {
            SavedPose saved = savedPose;
            saved.stack.mulPose(Axis.YP.rotationDegrees(-saved.bodyYaw));
            saved.stack.mulPose(Axis.XP.rotationDegrees(-saved.lean));
            saved.stack.mulPose(Axis.YP.rotationDegrees(saved.bodyYaw));
            restoreVictim();
        }
    }

    private static double skyWeight(double partial) {
        if (!io.github.verycooltimo.murim.client.ClientConfig.RAIN_SKY_TINT.get()) return 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return 0;
        double strength = 0;
        for (Cast c : CASTS) if (c.target != null && mc.player.position().distanceToSqr(c.target) <= 32 * 32) {
            double tint = PlumLayers.skyTint(c.stars) * RainPresentation.skyStrength(c.stars, c.t() + partial);
            if (c.lostTick >= 0) tint = PlumLayers.skyTint(c.stars) * RainPresentation.skyStrength(c.stars, c.lostTick - c.start)
                    * (1 - RainPresentation.smooth((clientTicks + partial - c.lostTick) / 20));
            tint *= 1 - RainPresentation.smooth((mc.player.position().distanceTo(c.target) - 28) / 4);
            strength = Math.max(strength, tint); // overlapping casts never accumulate beyond 25%.
        }
        return strength;
    }

    // API: read-only neoforge-src/net/neoforged/neoforge/client/event/ViewportEvent.java#ComputeFogColor.
    @SubscribeEvent
    static void rainFog(ViewportEvent.ComputeFogColor event) {
        syncLevel();
        float strength = (float) skyWeight(event.getPartialTick());
        if (strength <= 0) return;
        float horizon = 1 - Math.abs(event.getCamera().getLookVector().y());
        event.setRed(event.getRed() * (1 - strength) + (42 / 255F + horizon * .10F) * strength);
        event.setGreen(event.getGreen() * (1 - strength) + 43 / 255F * strength);
        event.setBlue(event.getBlue() * (1 - strength) + 110 / 255F * strength);
    }

    /** Curved sky cap, anchored to the observer. Sky only: drawn before terrain, no screen overlay. */
    private static void rainSky(RenderLevelStageEvent event) {
        double strength = skyWeight(event.getPartialTick().getGameTimeDeltaPartialTick(false));
        if (strength <= 0) return;
        PoseStack stack = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        RenderType material = MurimRenderTypes.rainAir();
        VertexConsumer v = buffers.getBuffer(material);
        stack.pushPose();
        try {
            stack.translate(-camera.x, -camera.y, -camera.z);
            for (int row = 0; row < 4; row++) for (int segment = 0; segment < 32; segment++) {
                double a = segment * Math.PI * 2 / 32, b = (segment + 1) * Math.PI * 2 / 32;
                double lo = row * Math.PI / 8, hi = (row + 1) * Math.PI / 8;
                Vec3 p0 = skyPoint(camera, a, lo), p1 = skyPoint(camera, a, hi);
                Vec3 p2 = skyPoint(camera, b, hi), p3 = skyPoint(camera, b, lo);
                Vec3 n = camera.subtract(p0).normalize();
                // Opaque core sample of air_p24 (pixel 338,196); tint toward night blue / wine horizon.
                Vec3[] points = {p0, p1, p2, p3};
                for (Vec3 point : points) {
                    float horizon = (float) (1 - RainPresentation.smooth((point.y - camera.y) / 38));
                    VfxDraw.vertex(v, stack.last(), point, n, .2203776F, .1918945F, (float) strength,
                            (42 + 48 * horizon) / 242F, (43 - 23 * horizon) / 231F, (110 - 50 * horizon) / 253F);
                }
            }
            buffers.endBatch(material);
        } finally { stack.popPose(); }
    }

    private static Vec3 skyPoint(Vec3 camera, double azimuth, double elevation) {
        return camera.add(96 * Math.cos(azimuth) * Math.cos(elevation), 96 * Math.sin(elevation),
                96 * Math.sin(azimuth) * Math.cos(elevation));
    }

    private static double rainSpread(Cast c) { return FLOWER_R * .82D * c.scale; }

    private static Vec3 splashLanding(Cast c, double key) {
        double angle = Math.floor(frac(key * 2.9) * 7) * Math.PI * 2 / 7 + frac(key * 4.1) * .48;
        double radius = 5 + 2 * frac(key * 1.7);
        double height = .07;
        return c.target.add(Math.cos(angle) * radius, height, Math.sin(angle) * radius);
    }

    private static Vec3 fall(SkyPetal s, double u) {
        double k = u * u * (3.0D - 2.0D * u);
        double y = s.fallFrom.y + (s.fallTo.y - s.fallFrom.y) * u * u;
        return new Vec3(s.fallFrom.x + (s.fallTo.x - s.fallFrom.x) * k, y, s.fallFrom.z + (s.fallTo.z - s.fallFrom.z) * k);
    }

    private static double drift(double t, double seed) {
        double lag = 6.0D * frac(seed * 3.7D);
        double k = Mth.clamp((t - RainRules.DRIFT - lag) / (RainRules.COHORTS[0] - RainRules.DRIFT - 6.0D), 0.0D, 1.0D);
        return k * k * (3.0D - 2.0D * k);
    }

    private static void coreRibbons(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float t) {
        if (!c.has(PlumLayers.Kind.FLOWER)) return;
        if (c.executionTier < 3 || c.core == null || c.coreBorn < 0) {
            return;
        }
        float grow = (float) Mth.clamp((t - RainRules.BLOOM) / 10.0D, 0.0D, 1.0D);
        float out = (float) Mth.clamp(1.0D - (t - RAIN_WAVES[2]) / RainRules.FALL, 0.0D, 1.0D)
                * (c.lostTick >= 0 ? Mth.clamp(1.0F - (clientTicks - c.lostTick) / 12.0F, 0.0F, 1.0F) : 1.0F);
        // Лепестки уходят к цели — кромки долей тают вместе с формой цветка.
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
                // Rim disappears first, followed by the interior while rain is already falling.
                double peel = io.github.verycooltimo.murim.technique.PlumPresentationRules.flowerAlpha(t, sPos, RAIN_WAVES);
                w[i] = (0.045D + 0.03D * sPos) * c.scale * peel;
            }
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.4D), 0.16F * a, LOBE_OUT);
            fstrip(v, pose, camera, p, w, 0.62F * a, LOBE_IN);
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.32D), 0.85F * a, HEART);
        }
        for (int lobe = 0; lobe < 5; lobe++) {
            Vec3 tip = lobePoint(c, lobe, .3, 0, t, 0);
            Vec3 axis = tip.subtract(c.core).normalize();
            for (int part = 0; part < 3; part++) {
                Vec3 from = c.core.add(axis.scale(.6 + part * .85));
                Vec3 to = from.add(axis.scale(.55));
                Vec3[] vein = {from, from.lerp(to, .5), to};
                fstrip(v, pose, camera, vein, new double[]{.04, .06, .04}, .75F * a, HEART);
            }
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

    private static void rain(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        if (!c.has(PlumLayers.Kind.THREADS)) return;
        for (SkyPetal s : c.sky) {
            if (s.free || s.fallTick < 0 || s.dead) {
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
            double len = Math.min(1.8D + 3.4D * u, d.length());
            tail = d.lengthSqr() < 1.0E-6D ? head.add(0.0D, 0.3D, 0.0D) : head.add(d.normalize().scale(len));
            Vec3[] p = {tail, tail.lerp(head, 0.5D), head};
            double wd = 0.045D + 0.065D * s.size / 0.22D;
            double[] w = {0.0D, wd, wd * 0.6D};
            float fade = (float) Math.min(1, (1 - u) / .25);
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.5D), 0.2F * fade, s.tone == 2 ? BLOOD : RAIN_PINK);
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.45D), 0.95F * fade, SKY_WHITE);
        }
        double floor = c.target.y;
        for (Beam b : c.beams) {
            float age = clientTicks - b.born() + partial;
            if (age < 0.0F) {
                continue;
            }
            // Голова падает с высоты к земле за 3 тика; хвост — длина ленты.
            double headY = floor + b.top() - (b.top() + 0.5D) * Mth.clamp(age / 3.0D, 0.0D, 1.0D);
            double tailY = Math.min(floor + b.top() + 1.0D, headY + b.len());
            headY = Math.max(floor, headY);
            float a = (float) PlumVfx.curve(age, 0.0, 0.6, 1.0, 1.0, 2.0, 0.8, 3.0, 0.0);
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

    private static void impact(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        if (!c.has(PlumLayers.Kind.CAPTION)) return;
        // Contact geometry must remain visible during and after the rain waves.
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

    private static float[] lobeTint(double sPos) {
        return tint(sPos > .7 ? 2 : 1);
    }

    private static void paintedRain(Cast c, PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float partial, float t) {
        if (!c.has(PlumLayers.Kind.THREADS)) return;
        RenderType material = MurimRenderTypes.rainPetals();
        VertexConsumer pc = buffers.getBuffer(material);
        for (SkyPetal petal : c.sky) {
            if (petal.stream < 0 || petal.dead || petal.fallTick >= 0) continue;
            Vec3 at = petal.prev.lerp(petal.pos, partial);
            Vec3 normal = camera.subtract(at).normalize();
            Vec3 right = new Vec3(0, 1, 0).cross(normal).normalize();
            Vec3 up = normal.cross(right).normalize();
            double fade = (1 - RainPresentation.smooth((t - RAIN_WAVES[0] + 8) / 12))
                    * (1 - RainPresentation.smooth((t - RainRules.STREAM - petal.delay - 22 / petal.uSpeed) / 4));
            float alpha = (float) fade * appear(c, petal, t) * near(at, camera);
            paintedPetal(pc, pose, at, right, up, normal, petal.size, petal.cell, alpha, 1, .1);
        }
        for (Lance lance : c.lances) {
            double age = clientTicks + partial - lance.born;
            if (age < 0 || age >= lance.duration) continue;
            double u = age / lance.duration;
            Vec3 head = RainPresentation.lancePoint(lance.from, lance.approach, lance.to, u);
            Vec3 axis = (u < .9 ? lance.from.subtract(lance.approach) : lance.approach.subtract(lance.to)).normalize();
            Vec3 right = axis.cross(c.side).normalize();
            Vec3 second = axis.cross(right).normalize();
            Vec3 centre = head.add(axis.scale(lance.length / 2));
            float alpha = (float) Math.min(RainPresentation.smooth(age / 3), RainPresentation.smooth((lance.duration - age) / 3));
            atlas(pc, pose, centre, right.scale(lance.width / 2), axis.scale(lance.length / 2), lance.cell, alpha * near(head, camera), 1);
            atlas(pc, pose, centre, second.scale(lance.width / 2), axis.scale(lance.length / 2), lance.cell, alpha * .85F * near(head, camera), .8F);
        }
        for (Contact contact : c.contacts) {
            double age = clientTicks + partial - contact.born;
            // Four-tick body-surface contact, independently eased in and out.
            double burst = Math.min(1, age / 3);
            float alpha = (float) (RainPresentation.smooth(age) * (1 - RainPresentation.smooth((age - 2) / 2)));
            for (int i = 0; i < 3; i++) {
                double a = contact.phase + i * 2.1;
                Vec3 at = contact.pos.add(Math.cos(a) * .3 * burst, .1 * Math.sin(a + age * .2), Math.sin(a) * .3 * burst);
                paintedPetal(pc, pose, at, c.side, new Vec3(0, 1, 0), c.dir, .3, i + 4, alpha, .9F, .1);
            }
        }
        for (Landed petal : c.landed) {
            double age = clientTicks + partial - petal.born;
            float a = (float) (RainPresentation.smooth(age / 3) * (1 - RainPresentation.smooth((age - petal.life + 20) / 20)));
            Vec3 right = new Vec3(Math.cos(petal.spin), 0, Math.sin(petal.spin));
            Vec3 up = new Vec3(-Math.sin(petal.spin), 0, Math.cos(petal.spin));
            paintedPetal(pc, pose, petal.pos, right, up, new Vec3(0, 1, 0), petal.size, petal.cell, a * .8F, .65F, .04);
        }
        buffers.endBatch(material);
    }

    private RainVfx() {
    }
}
