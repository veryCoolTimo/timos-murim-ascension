package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.CameraShakeHandler;
import io.github.verycooltimo.murim.client.ClientAuraState;
import io.github.verycooltimo.murim.network.ShowerPayload;
import io.github.verycooltimo.murim.network.TechniqueEventPayload;
import io.github.verycooltimo.murim.technique.ShowerRules;
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

/**
 * Ливень Цветов — 6-я форма Меча Семи Цветков Сливы (docs/design/techniques/seven-plum-shower-spec.md,
 * рефы «plum blossom shower»): сжатие (у вытянутой руки собирается бело-розовый узел, короткие розовые
 * следы стягиваются к нему, вокруг корпуса голубые дуги — shower2-01) → толчок и взлёт → зависание
 * над целью в широких розовых полосах (shower-2) → косое пикирование сквозь цель: за мастером пучок
 * параллельных разрезов, узкий у начала и широкий у конца, белое ядро в розовой оболочке, остаточные
 * образы (shower-3, -4, shower2-03) → белая звезда прокола → ливень разрезов по цели → белая вспышка
 * внизу, загнутая бело-розовая лента по земле, облака лепестков (shower-5).
 *
 * <p>Всё — частицы и полосы с чёткими краями (манхва, без размытия); удар, импакт-кадр, тряска и дым —
 * только по пакетам попадания с сервера.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class ShowerVfx {

    private static final ResourceLocation TECHNIQUE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "seven_plum_shower");
    /** Палитра — по рефам (DESCRIPTIONS.md, DESCRIPTIONS-2.md). */
    private static final VfxColour CORE = hex(0xFFF9FC);
    private static final VfxColour BODY = hex(0xFFC4DF);
    private static final VfxColour RIM = hex(0xFF78B5);
    private static final VfxColour DEEP = hex(0xF5366E);
    private static final VfxColour KNOT = hex(0xFF67B5);
    private static final VfxColour BAND = hex(0xD991AC);
    private static final VfxColour CYAN = hex(0x75E9F5);
    private static final VfxColour CYAN_HI = hex(0xD9FFFF);
    private static final VfxColour LILAC = hex(0xCDA9F1);

    private static final List<Cast> CASTS = new ArrayList<>();
    private static int clientTicks;

    private static VfxColour hex(int rgb) {
        return new VfxColour(((rgb >> 16) & 0xFF) / 255.0F, ((rgb >> 8) & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F);
    }

    /** Вблизи камеры тает: от первого лица пучок не закрывает экран (0 ближе 1,2 блока, 1 дальше 2,7). */
    private static float near(Vec3 at, Vec3 camera) {
        return (float) Mth.clamp((at.distanceTo(camera) - 1.2D) / 1.5D, 0.0D, 1.0D);
    }

    private static void fstrip(VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Vec3[] p, double[] w, float alpha, VfxColour col) {
        float[] a = new float[p.length];
        for (int i = 0; i < p.length; i++) {
            a[i] = alpha * near(p[i], camera);
        }
        PlumVfx.stripVar(v, pose, camera, p, w, a, col);
    }

    // ------------------------------------------------------------------ состояние

    private static final class Mote {
        static final int PETAL = 0;
        static final int STREAK = 1;
        static final int WIND = 2;
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

    /** Луч: звезда прокола, черта разреза (в пространстве) или борозда по земле (плоская). */
    private record Ray(Vec3 a, Vec3 b, int born, int life, double width, boolean flat) {
    }

    private record Ring(Vec3 centre, int born, double rMax, int life, double width) {
    }

    /** Разрез пучка: смещение в сечении (u — вбок, v — поперёк), доли пути начала и конца, опережение. */
    private record Streak(double u, double v, double t0, double t1, double lead, double width, int kind, int delay) {
    }

    private record Ghost(Vec3 pos, float yaw, int born, int life, PartPose[] pose, int rgb, float alpha) {
    }

    private static final class Cast {
        final int entityId;
        final int layer;
        final int start;
        final Random random;
        final double density;
        int release = -1;
        Vec3 origin;
        Vec3 apex;
        int targetId = -1;
        int diveTick = -1;
        Vec3 from;
        Vec3 to;
        Vec3 dir = new Vec3(0.0D, -1.0D, 0.0D);
        Vec3 s1 = new Vec3(1.0D, 0.0D, 0.0D);
        Vec3 s2 = new Vec3(0.0D, 0.0D, 1.0D);
        int hitTick = -1;
        Vec3 hitAt;
        int landTick = -1;
        Vec3 landAt;
        boolean landHit;
        ResourceLocation skin;
        final List<Mote> motes = new ArrayList<>();
        final List<Puff> puffs = new ArrayList<>();
        final List<Ray> rays = new ArrayList<>();
        final List<Ring> rings = new ArrayList<>();
        final List<Streak> streaks = new ArrayList<>();
        final List<Ghost> ghosts = new ArrayList<>();
        /** Точки тела мастера с толчка: след взлёта. */
        final List<Vec3> path = new ArrayList<>();

        Cast(int entityId, int layer) {
            this.entityId = entityId;
            this.layer = layer;
            this.start = clientTicks;
            this.random = new Random(entityId * 7919L + clientTicks);
            this.density = switch (Math.max(0, Math.min(8, layer))) {
                case 0 -> 0.0D;
                case 1 -> 0.15D;
                case 2 -> 0.25D;
                case 3 -> 0.4D;
                case 4 -> 0.55D;
                case 5 -> 0.7D;
                case 6 -> 0.85D;
                case 7 -> 1.0D;
                default -> 1.2D;
            };
        }

        int n(double full) {
            return (int) Math.ceil(full * density);
        }

        double scale() {
            return 0.6D + 0.4D * Math.min(1.0D, density);
        }

        boolean own() {
            Minecraft mc = Minecraft.getInstance();
            return mc.player != null && mc.player.getId() == entityId;
        }

        /** Камера — глаза самого мастера. */
        boolean eyes() {
            Minecraft mc = Minecraft.getInstance();
            return own() && mc.options.getCameraType().isFirstPerson() && mc.getCameraEntity() == mc.player;
        }

        /** Тик от начала техники (по событию START), до толчка — по счётчику, после — от толчка. */
        int t() {
            return release >= 0 ? ShowerRules.RELEASE + clientTicks - release : clientTicks - start;
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
        // Чек-лист: синяя аура в стойке и сжатии (shower2-01) → после толчка розовая с 3-го слоя.
        // Синяя на всём полёте оставляла голубые клочья над траекторией (codex 03.10, раунды 1–3).
        ClientAuraState.techniqueAura(c.entityId, 2 + Math.min(1, c.layer / 4), 0, ShowerRules.RELEASE + 1);
    }

    public static void onShower(ShowerPayload p) {
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
        switch (p.stage()) {
            case ShowerPayload.RELEASE -> release(c, p, mc);
            case ShowerPayload.DIVE -> dive(c, p, mc);
            case ShowerPayload.HIT -> hit(c, p.a(), p.b().subtract(p.a()), mc);
            case ShowerPayload.CUT -> cut(c, p.a(), p.extra(), mc);
            case ShowerPayload.LAND -> land(c, p.a(), p.extra() == 1, mc);
            default -> {
            }
        }
    }

    /** Толчок: пыль конусом назад, кольцо пробитого воздуха у стоп, ветер, лепестки, надпись. */
    private static void release(Cast c, ShowerPayload p, Minecraft mc) {
        c.release = clientTicks;
        if (c.layer >= 3) {
            ClientAuraState.techniqueAura(c.entityId, 2, 1, ShowerRules.DIVE + ShowerRules.DIVE_TICKS + 6);
        }
        c.origin = p.a();
        c.apex = p.b();
        c.targetId = p.extra();
        Vec3 up = c.apex.subtract(c.origin);
        Vec3 flat = new Vec3(up.x, 0.0D, up.z);
        Vec3 back = flat.lengthSqr() < 1.0E-4D ? Vec3.ZERO : flat.normalize().scale(-1.0D);
        // Пыль — серым конусом назад и в стороны от толчка.
        dustBurst(c, c.origin, c.n(16) + 5, 0.22D, back);
        c.rings.add(new Ring(c.origin.add(0.0D, 0.06D, 0.0D), clientTicks, 2.4D * c.scale(), 8, 0.16D));
        for (int i = 0; i < c.n(8) + 2; i++) {
            Vec3 v = back.scale(0.25D).add(c.random.nextGaussian() * 0.15D, 0.05D + 0.1D * c.random.nextDouble(), c.random.nextGaussian() * 0.15D);
            wind(c, c.origin.add(0.0D, 0.2D + c.random.nextDouble() * 0.8D, 0.0D), v, 12, 0.07D);
        }
        if (ShowerRules.petals(c.layer)) {
            for (int i = 0; i < c.n(26); i++) {
                Vec3 v = new Vec3(c.random.nextGaussian(), 0.3D + c.random.nextDouble(), c.random.nextGaussian()).normalize()
                        .scale(0.12D + 0.2D * c.random.nextDouble());
                petal(c, c.origin.add(0.0D, 0.5D + c.random.nextDouble(), 0.0D), v, 30 + c.random.nextInt(20));
            }
        }
        if (c.own()) {
            SpeedLines.radial(0.5F, 0.6F, 0.45F, 5, SpeedLines.WHITE);
            if (c.layer >= 3) {
                TechniqueCaption.show(Component.translatable("technique.murim.seven_plum_blossoms.school"),
                        Component.translatable("technique.murim.seven_plum_blossoms.shower"), 40);
            }
        }
        if (mc.player != null) {
            mc.player.level().playLocalSound(c.origin.x, c.origin.y, c.origin.z, net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP,
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.75F, false);
        }
        if (mc.level != null && mc.level.getEntity(c.entityId) instanceof AbstractClientPlayer player) {
            c.skin = player.getSkin().texture();
        }
    }

    /** Пикирование: строим пучок параллельных разрезов вдоль пути. */
    private static void dive(Cast c, ShowerPayload p, Minecraft mc) {
        c.diveTick = clientTicks;
        c.from = p.a().add(0.0D, 0.9D, 0.0D);
        c.to = p.b().add(0.0D, 0.9D, 0.0D);
        Vec3 d = c.to.subtract(c.from);
        if (d.lengthSqr() > 1.0E-4D) {
            c.dir = d.normalize();
        }
        Vec3 side = c.dir.cross(new Vec3(0.0D, 1.0D, 0.0D));
        c.s1 = side.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : side.normalize();
        c.s2 = c.s1.cross(c.dir).normalize();
        c.streaks.clear();
        int n = ShowerRules.streaks(c.layer);
        for (int i = 0; i < n; i++) {
            // Сечение: ровнее вбок (веер), уже поперёк; без симметрии — шаг с дрожанием.
            double u = -1.0D + 2.0D * (i + 0.2D + 0.6D * c.random.nextDouble()) / n;
            double v = (c.random.nextDouble() - 0.5D) * 1.2D;
            double t0 = c.random.nextDouble() * 0.3D;
            double t1 = 1.0D + c.random.nextDouble() * 0.25D;
            double lead = 0.2D + 0.9D * c.random.nextDouble();
            double r = c.random.nextDouble();
            int kind = r < 0.55D ? 0 : r < 0.85D ? 1 : 2;
            double w = kind == 0 ? 0.03D + 0.02D * c.random.nextDouble() : 0.035D + 0.045D * c.random.nextDouble();
            c.streaks.add(new Streak(u, v, t0, t1, lead, w, kind, c.random.nextInt(3)));
        }
        if (c.own()) {
            SpeedLines.radial(0.5F, 0.55F, 0.9F, 9, SpeedLines.WHITE);
        }
        if (mc.player != null) {
            mc.player.level().playLocalSound(c.from.x, c.from.y, c.from.z, net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP,
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.6F, false);
        }
    }

    /** Прокол: белая звезда, осколки, лепестки, импакт-кадр и тряска — по факту попадания. */
    private static void hit(Cast c, Vec3 at, Vec3 dir, Minecraft mc) {
        c.hitTick = clientTicks;
        c.hitAt = at;
        Vec3 d = dir.lengthSqr() < 1.0E-6D ? c.dir : dir.normalize();
        Vec3 a1 = d.cross(new Vec3(0.0D, 1.0D, 0.0D));
        a1 = a1.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : a1.normalize();
        Vec3 a2 = a1.cross(d).normalize();
        int rays = c.n(18) + 6;
        for (int i = 0; i < rays; i++) {
            double ang = Math.PI * 2.0D * i / rays + c.random.nextDouble() * 0.4D;
            // Звезда поперёк хода и несколько лучей вдоль (вперёд — длиннее).
            Vec3 r = a1.scale(Math.cos(ang)).add(a2.scale(Math.sin(ang))).add(d.scale((c.random.nextDouble() - 0.3D) * 0.8D)).normalize();
            double len = (0.6D + 1.1D * c.random.nextDouble()) * c.scale();
            c.rays.add(new Ray(at.add(r.scale(0.15D)), at.add(r.scale(len)), clientTicks, 5, 0.05D + 0.03D * c.random.nextDouble(), false));
        }
        for (int k = 0; k < 3; k++) {
            Vec3 r = d.add(a1.scale((c.random.nextDouble() - 0.5D) * 0.3D)).normalize();
            c.rays.add(new Ray(at, at.add(r.scale(2.6D + 1.2D * c.random.nextDouble())), clientTicks, 6, 0.09D, false));
        }
        for (int i = 0; i < c.n(20) + 4; i++) {
            Vec3 v = new Vec3(c.random.nextGaussian(), c.random.nextGaussian(), c.random.nextGaussian()).normalize()
                    .scale(0.3D + 0.35D * c.random.nextDouble()).add(d.scale(0.25D));
            Mote m = new Mote(at, v, 7 + c.random.nextInt(4), Mote.SHARD, 0, 0.0F, 0.035D + 0.03D * c.random.nextDouble(), 4);
            m.drag = 0.84D;
            m.gravity = 0.02D;
            c.motes.add(m);
        }
        if (ShowerRules.petals(c.layer)) {
            for (int i = 0; i < c.n(44); i++) {
                Vec3 v = new Vec3(c.random.nextGaussian(), c.random.nextGaussian() * 0.6D + 0.2D, c.random.nextGaussian()).normalize()
                        .scale(0.15D + 0.3D * c.random.nextDouble()).add(d.scale(0.2D));
                petal(c, at, v, 30 + c.random.nextInt(25));
            }
        }
        float distance = mc.player == null ? 99.0F : (float) mc.player.position().distanceTo(at);
        if (c.own()) {
            ImpactFrames.trigger(at);
        }
        if (mc.player != null && distance < 24.0F) {
            float q = distance < 8.0F ? 0.7F : 0.7F * (1.0F - (distance - 8.0F) / 16.0F);
            CameraShakeHandler.quake(Math.max(q, c.own() ? 0.7F : 0.0F), 12);
            mc.player.level().playLocalSound(at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_CRIT,
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.8F, false);
            mc.player.level().playLocalSound(at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_STRONG,
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.6F, false);
        }
    }

    /** Разрез ливня: короткая черта сквозь цель, параллельно ходу, каждый раз со своим сдвигом. */
    private static void cut(Cast c, Vec3 at, int k, Minecraft mc) {
        double off = (k - 1) * 0.32D;
        Vec3 o = at.add(c.s1.scale(off)).add(c.s2.scale(((k * 37) % 5 - 2) * 0.08D));
        Vec3 d = c.dir.add(c.s1.scale((k - 1) * 0.12D)).normalize();
        double len = 1.5D + 0.3D * k;
        c.rays.add(new Ray(o.subtract(d.scale(len)), o.add(d.scale(len)), clientTicks, 5, 0.06D, false));
        if (ShowerRules.petals(c.layer)) {
            for (int i = 0; i < c.n(8); i++) {
                Vec3 v = new Vec3(c.random.nextGaussian(), c.random.nextDouble(), c.random.nextGaussian()).normalize().scale(0.15D);
                petal(c, o, v, 24 + c.random.nextInt(12));
            }
        }
        if (mc.player != null && mc.player.position().distanceTo(at) < 16.0D) {
            CameraShakeHandler.quake(0.2F, 4);
            mc.player.level().playLocalSound(at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP,
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.6F, 1.7F + 0.1F * k, false);
        }
    }

    /** Приземление: белая вспышка внизу, загнутая лента по земле, кольца, облака лепестков, дым. */
    private static void land(Cast c, Vec3 feet, boolean pierced, Minecraft mc) {
        c.landTick = clientTicks;
        c.landAt = feet;
        c.landHit = pierced;
        Vec3 f = new Vec3(c.dir.x, 0.0D, c.dir.z);
        f = f.lengthSqr() < 1.0E-4D ? new Vec3(0.0D, 0.0D, 1.0D) : f.normalize();
        Vec3 side = new Vec3(-f.z, 0.0D, f.x);
        dustBurst(c, feet, (pierced ? c.n(18) : c.n(10)) + 4, pierced ? 0.26D : 0.16D, Vec3.ZERO);
        if (!pierced) {
            c.rings.add(new Ring(feet.add(0.0D, 0.05D, 0.0D), clientTicks, 2.0D * c.scale(), 8, 0.12D));
            return;
        }
        // Веер острых полос из точки удара — вперёд по ходу и вверх (shower-5).
        for (int i = 0; i < c.n(9) + 4; i++) {
            // Продолжение укола: вдоль пикирования по земле, узким веером, почти без подъёма.
            Vec3 r = f.scale(1.0D).add(side.scale((c.random.nextDouble() - 0.5D) * 0.7D))
                    .add(0.0D, 0.05D + 0.3D * c.random.nextDouble(), 0.0D).normalize();
            double len = (0.9D + 0.9D * c.random.nextDouble()) * c.scale();
            // От первого лица веер начинается дальше от глаз — не закрывает экран.
            Vec3 g0 = feet.add(0.0D, 0.2D, 0.0D).add(r.scale(c.eyes() ? 2.2D : 1.1D));
            c.rays.add(new Ray(g0, g0.add(r.scale(len)), clientTicks, 6, 0.05D + 0.04D * c.random.nextDouble(), false));
        }
        // Борозды по земле — три пучка вперёд и в стороны, не ровная звезда.
        int cuts = c.n(14) + 4;
        for (int i = 0; i < cuts; i++) {
            double a = (i % 3 - 1) * 0.9D + (c.random.nextDouble() - 0.5D) * 0.5D;
            Vec3 d = f.scale(Math.cos(a)).add(side.scale(Math.sin(a)));
            double len = (1.6D + 2.2D * c.random.nextDouble()) * c.scale();
            Vec3 g0 = feet.add(0.0D, 0.05D, 0.0D);
            c.rays.add(new Ray(g0.add(d.scale(0.4D)), g0.add(d.scale(len)), clientTicks, 12, 0.07D + 0.05D * c.random.nextDouble(), true));
        }
        if (ShowerRules.petals(c.layer)) {
            // Облака лепестков по краям вспышки (shower-5).
            for (int i = 0; i < c.n(80); i++) {
                Vec3 lat = side.scale(c.random.nextBoolean() ? 1.0D : -1.0D);
                Vec3 v = lat.scale(0.22D + 0.06D * c.random.nextGaussian()).add(f.scale(0.12D + 0.05D * c.random.nextGaussian()))
                        .add(0.0D, 0.16D + 0.06D * c.random.nextGaussian(), 0.0D);
                petal(c, feet.add(0.0D, 0.3D, 0.0D), v, 40 + c.random.nextInt(30));
            }
        }
        smoke(c, feet);
        float distance = mc.player == null ? 99.0F : (float) mc.player.position().distanceTo(feet);
        if (mc.player != null && distance < 24.0F) {
            float q = distance < 8.0F ? 1.0F : 1.0F - (distance - 8.0F) / 16.0F;
            CameraShakeHandler.quake(Math.max(q, c.own() ? 0.9F : 0.0F), 16);
            mc.player.level().playLocalSound(feet.x, feet.y, feet.z, net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(),
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.6F, 1.5F, false);
        }
        if (c.own()) {
            SpeedLines.radial(0.5F, 0.7F, 0.6F, 6, SpeedLines.WHITE);
        }
    }

    // ------------------------------------------------------------------ частицы

    private static void petal(Cast c, Vec3 at, Vec3 vel, int life) {
        Mote m = new Mote(at, vel, life, Mote.PETAL, c.random.nextInt(4), (float) ((c.random.nextDouble() - 0.5D) * 0.6D),
                0.06D + 0.06D * c.random.nextDouble(), 1);
        m.drag = 0.93D;
        m.gravity = 0.003D;
        m.turbulence = 0.008D;
        c.motes.add(m);
    }

    private static void wind(Cast c, Vec3 at, Vec3 vel, int life, double size) {
        Mote m = new Mote(at, vel, life, Mote.WIND, 0, 0.0F, size, 10);
        m.drag = 0.9D;
        m.turbulence = 0.03D;
        c.motes.add(m);
    }

    /** Пыль: серые клубы от стоп; {@code bias} — куда сильнее (конус толчка). */
    private static void dustBurst(Cast c, Vec3 feet, int n, double speed, Vec3 bias) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.getBlockState(BlockPos.containing(feet.add(0.0D, -0.2D, 0.0D))).isAir()) {
            return;
        }
        for (int i = 0; i < n; i++) {
            double a = c.random.nextDouble() * Math.PI * 2.0D;
            Vec3 out = new Vec3(Math.cos(a), 0.0D, Math.sin(a)).add(bias.scale(1.2D));
            out = out.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : out.normalize();
            c.puffs.add(new Puff(feet.add(out.scale(0.3D)).add(0.0D, 0.1D, 0.0D), out.scale(speed * (0.6D + 0.8D * c.random.nextDouble())),
                    16 + c.random.nextInt(10), c.random.nextInt(16), 0.24D + 0.2D * c.random.nextDouble(), false, 0.62F, 0.0F));
        }
    }

    /** Дым манхвы у места удара о землю: низкий вал одной массой, потом встаёт облако. Мастера не накрывает. */
    private static void smoke(Cast c, Vec3 ground) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.getBlockState(BlockPos.containing(ground.add(0.0D, -0.2D, 0.0D))).isAir()) {
            return;
        }
        Entity caster = mc.level.getEntity(c.entityId);
        int n = Math.max(8, c.n(22));
        double r = 2.2D * c.scale();
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2.0D * i / n + c.random.nextDouble() * 0.3D;
            Vec3 out = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
            Vec3 at = ground.add(out.scale(r * (0.45D + 0.4D * c.random.nextDouble())));
            if (caster != null && at.distanceTo(caster.position()) < 2.4D) {
                continue;
            }
            boolean hollow = i % 3 == 0;
            double size = (0.5D + 0.9D * Math.pow(c.random.nextDouble(), 1.5D)) * (hollow ? 1.25D : 1.0D) * c.scale();
            Puff bank = new Puff(at.add(0.0D, size * 0.45D, 0.0D), out.scale(0.12D + 0.22D * c.random.nextDouble()),
                    30 + c.random.nextInt(14), c.random.nextInt(16), size, true,
                    hollow ? 0.56F : 0.84F + 0.12F * c.random.nextFloat(), (float) (c.random.nextDouble() * 6.28D));
            bank.delay = 2 + c.random.nextInt(4);
            c.puffs.add(bank);
        }
        for (int i = 0; i < n / 2; i++) {
            double a = c.random.nextDouble() * Math.PI * 2.0D;
            Vec3 at = ground.add(Math.cos(a) * r * 0.4D, 0.8D, Math.sin(a) * r * 0.4D);
            if (caster != null && at.distanceTo(caster.position()) < 2.4D) {
                continue;
            }
            Puff rise = new Puff(at, new Vec3(0.0D, 0.06D + 0.04D * c.random.nextDouble(), 0.0D), 38 + c.random.nextInt(14),
                    c.random.nextInt(16), (0.8D + 0.9D * c.random.nextDouble()) * c.scale(), true, 0.74F + 0.14F * c.random.nextFloat(),
                    (float) (c.random.nextDouble() * 6.28D));
            rise.delay = 6 + c.random.nextInt(8);
            c.puffs.add(rise);
        }
    }

    // ------------------------------------------------------------------ тело

    private static Vec3 forward(float yaw) {
        Vec3 f = Vec3.directionFromRotation(0.0F, yaw);
        return new Vec3(f.x, 0.0D, f.z).normalize();
    }

    private static float bodyYaw(Entity e) {
        return e instanceof LivingEntity le ? le.yBodyRot : e.getYRot();
    }

    /** Узел света перед вытянутой (левой) рукой, низко (shower2-01); от первого лица — внизу кадра. */
    private static Vec3 knot(Cast c, Entity e) {
        if (c.eyes()) {
            Vec3 look = forward(e.getYRot());
            return e.getEyePosition().add(look.scale(1.9D)).add(0.0D, -0.75D, 0.0D);
        }
        float yaw = bodyYaw(e);
        double r = Math.toRadians(yaw);
        Vec3 left = new Vec3(Math.cos(r), 0.0D, Math.sin(r));
        return e.position().add(forward(yaw).scale(1.15D)).add(left.scale(0.3D)).add(0.0D, 0.85D, 0.0D);
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
            Entity e = mc.level.getEntity(c.entityId);
            int t = c.t();
            if (e != null) {
                if (c.release < 0) {
                    coil(c, e, t);
                } else {
                    flight(c, e, mc);
                }
            }
            tickMotes(c);
            if (t > ShowerRules.RELEASE + ShowerRules.END + 50 || (c.release < 0 && t > ShowerRules.RELEASE + 30)) {
                it.remove();
            }
        }
    }

    /** Стойка и сжатие: пыль, следы к узлу у руки, голубые дуги (в рендере), ветер внутрь, лепестки. */
    private static void coil(Cast c, Entity e, int t) {
        if (t == 2 || t == ShowerRules.LOAD) {
            dustBurst(c, e.position(), c.n(t == 2 ? 6 : 10) + 2, t == 2 ? 0.1D : 0.16D, Vec3.ZERO);
        }
        if (t < ShowerRules.COIL || t >= ShowerRules.RELEASE) {
            return;
        }
        Vec3 k = knot(c, e);
        double grow = Mth.clamp((t - ShowerRules.COIL) / (double) (ShowerRules.RELEASE - ShowerRules.COIL), 0.0D, 1.0D);
        // Короткие розовые следы стягиваются к узлу: появляются на расстоянии и приходят за 6–8 тиков.
        int n = Math.max(1, c.n(3 + 3 * grow));
        for (int i = 0; i < n; i++) {
            Vec3 dir = new Vec3(c.random.nextGaussian(), c.random.nextGaussian() * 0.6D, c.random.nextGaussian()).normalize();
            double r = 0.9D + 0.9D * c.random.nextDouble();
            Vec3 p = k.add(dir.scale(r));
            int life = 6 + c.random.nextInt(3);
            Mote m = new Mote(p, k.subtract(p).scale(1.0D / life), life, Mote.STREAK, 0, 0.0F, 0.03D + 0.025D * c.random.nextDouble(), 4);
            m.drag = 1.0D;
            c.motes.add(m);
        }
        if (t % 3 == 0) {
            // Ветер втягивается по спирали к телу.
            double a = c.random.nextDouble() * Math.PI * 2.0D;
            Vec3 at = e.position().add(Math.cos(a) * 1.9D, 0.3D + 1.3D * c.random.nextDouble(), Math.sin(a) * 1.9D);
            Vec3 tang = new Vec3(-Math.sin(a), 0.0D, Math.cos(a));
            Vec3 in = new Vec3(-Math.cos(a), 0.0D, -Math.sin(a));
            wind(c, at, tang.scale(0.16D).add(in.scale(0.1D)), 12, 0.05D);
        }
        if (ShowerRules.petals(c.layer) && t % 2 == 0) {
            double a = c.random.nextDouble() * Math.PI * 2.0D;
            Vec3 at = k.add(Math.cos(a) * 2.4D, (c.random.nextDouble() - 0.3D) * 1.4D, Math.sin(a) * 2.4D);
            Mote m = new Mote(at, k.subtract(at).scale(0.05D), 22, Mote.PETAL, c.random.nextInt(4), 0.3F, 0.07D, 1);
            m.drag = 1.0D;
            c.motes.add(m);
        }
    }

    /** Взлёт, зависание, пикирование, скольжение: следы, лепестки, ветер, остаточные образы. */
    private static void flight(Cast c, Entity e, Minecraft mc) {
        int s = clientTicks - c.release;
        Vec3 body = e.position().add(0.0D, 0.9D, 0.0D);
        if (s <= ShowerRules.SLIDE + 2) {
            c.path.add(body);
        }
        boolean diving = c.diveTick >= 0 && clientTicks - c.diveTick <= ShowerRules.DIVE_TICKS + 1;
        if (s <= ShowerRules.DIVE + ShowerRules.DIVE_TICKS + 1) {
            Vec3 back = c.diveTick >= 0 ? c.dir.scale(-1.0D) : c.apex.subtract(c.origin).normalize().scale(-1.0D);
            for (int i = 0; i < (diving ? 1 : s < ShowerRules.RISE_TICKS ? s % 2 : 0); i++) {
                wind(c, body.add(c.random.nextGaussian() * 0.3D, c.random.nextGaussian() * 0.4D, c.random.nextGaussian() * 0.3D),
                        back.scale(0.3D).add(c.random.nextGaussian() * 0.05D, c.random.nextGaussian() * 0.05D, c.random.nextGaussian() * 0.05D),
                        7, 0.045D);
            }
            if (ShowerRules.petals(c.layer)) {
                // Лепестки сходят с потока: основная скорость вдоль хода, небольшой разброс вбок.
                for (int i = 0; i < (diving ? c.n(3) : c.n(1)); i++) {
                    Vec3 along = diving ? c.dir : back.scale(-1.0D);
                    Vec3 v = along.scale(0.15D + 0.25D * c.random.nextDouble())
                            .add(c.s1.scale(c.random.nextGaussian() * 0.08D)).add(c.s2.scale(c.random.nextGaussian() * 0.06D));
                    petal(c, body.add(c.random.nextGaussian() * 0.35D, c.random.nextGaussian() * 0.35D, c.random.nextGaussian() * 0.35D), v,
                            24 + c.random.nextInt(16));
                }
            }
        }
        // Взгляд мастера ведёт на цель (зависание) и вдоль пикирования: от первого лица пучок и цель в кадре.
        if (c.own() && e == mc.player && s > ShowerRules.SLIDE && s <= ShowerRules.SLIDE + 8) {
            // После скольжения взгляд поднимается от земли — на цель позади, а не в траву.
            mc.player.setXRot(mc.player.getXRot() + (12.0F - mc.player.getXRot()) * 0.3F);
        }
        if (c.own() && e == mc.player && s >= ShowerRules.RISE_TICKS - 2 && s <= ShowerRules.SLIDE) {
            Vec3 want = null;
            if (c.diveTick >= 0) {
                want = c.dir;
            } else if (c.targetId >= 0 && mc.level.getEntity(c.targetId) instanceof LivingEntity t) {
                want = t.position().add(0.0D, t.getBbHeight() * 0.5D, 0.0D).subtract(e.getEyePosition());
            }
            if (want != null && want.lengthSqr() > 1.0E-4D) {
                want = want.normalize();
                float yaw = (float) Math.toDegrees(Math.atan2(-want.x, want.z));
                float pitch = (float) -Math.toDegrees(Math.asin(Mth.clamp(want.y, -1.0D, 1.0D)));
                // Пикирование круче 60° — экран в землю; держим наклон в пределах.
                pitch = Mth.clamp(pitch, -30.0F, 55.0F);
                mc.player.setYRot(mc.player.getYRot() + Mth.wrapDegrees(yaw - mc.player.getYRot()) * 0.5F);
                mc.player.setXRot(mc.player.getXRot() + (pitch - mc.player.getXRot()) * 0.5F);
            }
        }
        // Остаточные образы тела на пикировании (shower-4): короткие, чёткие, без размытия.
        if (diving && ShowerRules.afterimages(c.layer) && e instanceof AbstractClientPlayer player
                && mc.getEntityRenderDispatcher().getRenderer(player) instanceof PlayerRenderer renderer) {
            if (c.skin == null) {
                c.skin = player.getSkin().texture();
            }
            c.ghosts.add(new Ghost(e.position(), bodyYaw(e), clientTicks, 7, capture(renderer.getModel()), 0xFFE2EE, 0.6F));
        }
        // Скольжение за целью: пыль по ходу.
        if (c.landTick >= 0 && clientTicks - c.landTick <= ShowerRules.SLIDE_TICKS + 1 && e.onGround()) {
            dustBurst(c, e.position(), c.n(3) + 1, 0.12D, c.dir.scale(-1.0D));
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
                // Порывы — поле, меняющееся во времени, а не белый шум.
                double ph = m.age * 0.21D + m.cell * 1.7D + m.pos.x * 0.5D;
                v = v.add(Math.sin(ph) * m.turbulence, Math.sin(ph * 1.3D + 1.1D) * m.turbulence * 0.4D,
                        Math.cos(ph * 0.9D + m.pos.z * 0.5D) * m.turbulence);
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
        c.rays.removeIf(r -> clientTicks - r.born() > r.life());
        c.rings.removeIf(r -> clientTicks - r.born() > r.life());
        c.ghosts.removeIf(g -> clientTicks - g.born() > g.life());
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
                Entity e = mc.level.getEntity(c.entityId);
                ghosts(mc, c, ps, buffers, partial);
                PoseStack.Pose pose = ps.last();
                VertexConsumer air = buffers.getBuffer(MurimRenderTypes.airBand());
                if (e != null) {
                    coilArcs(c, e, pose, camera, air, partial);
                    knotStar(c, e, pose, camera, air, partial);
                }
                riseTrail(c, pose, camera, air, partial);
                apexBands(c, pose, camera, air, partial);
                beam(c, e, pose, camera, air, partial);
                motes(c, pose, camera, air, partial);
                rays(c, pose, camera, air, partial);
                ground(c, pose, air, partial);
                buffers.endBatch(MurimRenderTypes.airBand());
                glows(c, e, pose, camera, buffers, partial);
                puffs(c, pose, camera, buffers, partial);
                petals(c, pose, camera, buffers, partial);
            }
        } finally {
            ps.popPose();
        }
    }

    /** Голубые дуги вокруг корпуса в сжатии (shower2-01): три неравные, вращаются вразнобой; со 2-го слоя. */
    private static void coilArcs(Cast c, Entity e, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        if (c.layer < 2 || c.eyes()) {
            return;
        }
        float t = c.t() + partial;
        float a = (float) PlumVfx.curve(t, ShowerRules.COIL, 0.0, ShowerRules.COIL + 6, 1.0, ShowerRules.RELEASE + 2, 1.0, ShowerRules.RELEASE + 6, 0.0);
        if (a <= 0.0F) {
            return;
        }
        Vec3 centre = e.getPosition(partial).add(0.0D, 0.95D, 0.0D);
        for (int k = 0; k < 2; k++) {
            int n = 18;
            Vec3[] p = new Vec3[n + 1];
            double[] w = new double[n + 1];
            double r = 0.55D + 0.1D * k;
            double tilt = Math.toRadians(k == 0 ? 18.0D : k == 1 ? -25.0D : 40.0D);
            double a0 = t * (k % 2 == 0 ? 0.42D : -0.35D) + k * 2.1D;
            double span = Math.PI * (0.65D + 0.15D * k);
            for (int i = 0; i <= n; i++) {
                double u = i / (double) n;
                double ang = a0 + span * u;
                p[i] = centre.add(Math.cos(ang) * r, Math.sin(ang) * r * Math.sin(tilt) + (k - 1) * 0.35D, Math.sin(ang) * r);
                w[i] = 0.06D * Math.sin(Math.PI * u) * (0.5D + 0.5D * u);
            }
            PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 2.0D), 0.08F * a, COLD);
            PlumVfx.strip(v, pose, camera, p, w, 0.45F * a, CYAN);
            PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 0.3D), 0.9F * a, CYAN_HI);
        }
    }

    /** Узел у руки: короткая четырёхлучевая звёздочка, растёт к толчку. */
    private static void knotStar(Cast c, Entity e, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        float t = c.t() + partial;
        if (c.release >= 0 || t < ShowerRules.COIL) {
            return;
        }
        double grow = Mth.clamp((t - ShowerRules.COIL) / (ShowerRules.RELEASE - ShowerRules.COIL), 0.0D, 1.0D);
        Vec3 k = knot(c, e);
        double len = (0.2D + 0.45D * grow) * (0.85D + 0.15D * Math.sin(t * 1.3D));
        Vec3 look = camera.subtract(k).normalize();
        Vec3 a1 = look.cross(new Vec3(0.0D, 1.0D, 0.0D));
        a1 = a1.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : a1.normalize();
        Vec3 a2 = a1.cross(look).normalize();
        for (Vec3 ax : new Vec3[] {a1, a2, a1.add(a2).normalize(), a1.subtract(a2).normalize()}) {
            double l = ax == a1 || ax == a2 ? len : len * 0.5D;
            Vec3[] q = {k.subtract(ax.scale(l)), k, k.add(ax.scale(l))};
            fstrip(v, pose, camera, q, new double[] {0.0D, 0.05D, 0.0D}, 0.35F, KNOT);
            fstrip(v, pose, camera, q, new double[] {0.0D, 0.018D, 0.0D}, 0.95F, CORE);
        }
    }

    /** След взлёта: тонкая холодно-голубая полоса за телом (синий контур остаётся позади силуэта). */
    private static void riseTrail(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        if (c.release < 0 || c.path.size() < 2) {
            return;
        }
        float s = clientTicks - c.release + partial;
        float a = (float) PlumVfx.curve(s, 0.0, 1.0, ShowerRules.DIVE + 2, 0.9, ShowerRules.DIVE + 9, 0.0);
        if (a <= 0.0F) {
            return;
        }
        int m = Math.min(c.path.size(), ShowerRules.DIVE + 1);
        Vec3[] p = c.path.subList(0, m).toArray(new Vec3[0]);
        if (p.length < 2) {
            return;
        }
        double[] w = new double[p.length];
        for (int i = 0; i < p.length; i++) {
            w[i] = 0.14D * (0.2D + 0.8D * i / (p.length - 1.0D));
        }
        fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.0D), 0.07F * a, COLD);
        fstrip(v, pose, camera, p, w, 0.25F * a, BODY);
        fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.25D), 0.7F * a, CORE);
    }

    /** Зависание над целью: широкие полупрозрачные розовые полосы вдоль будущего пикирования (shower-2). */
    private static void apexBands(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        if (c.release < 0 || c.apex == null || c.layer < 3) {
            return;
        }
        float s = clientTicks - c.release + partial;
        float a = (float) PlumVfx.curve(s, ShowerRules.RISE_TICKS - 2, 0.0, ShowerRules.RISE_TICKS + 1, 1.0, ShowerRules.DIVE + 1, 1.0,
                ShowerRules.DIVE + 5, 0.0);
        if (a <= 0.0F) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Vec3 aim = c.origin.add(c.apex.subtract(c.origin).multiply(2.0D, 0.0D, 2.0D));
        if (c.targetId >= 0 && mc.level != null && mc.level.getEntity(c.targetId) instanceof LivingEntity t) {
            aim = t.position().add(0.0D, t.getBbHeight() * 0.5D, 0.0D);
        }
        Vec3 top = c.apex.add(0.0D, 0.9D, 0.0D);
        Vec3 d = aim.subtract(top);
        d = d.lengthSqr() < 1.0E-4D ? new Vec3(0.0D, -1.0D, 0.0D) : d.normalize();
        Vec3 side = d.cross(new Vec3(0.0D, 1.0D, 0.0D));
        side = side.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : side.normalize();
        Vec3 up = side.cross(d).normalize();
        // Закрученный поток вокруг оси будущего пикирования: пласты разной ширины витком, без симметрии крыльев.
        for (int k = 0; k < 9; k++) {
            boolean thin = k % 3 == 1;
            double a0 = k * 2.4D + s * 0.12D + c.entityId;
            double r0 = 0.35D + 0.2D * (k % 4);
            int n = 14;
            Vec3[] p = new Vec3[n + 1];
            double[] w = new double[n + 1];
            double l0 = -3.0D + 0.4D * (k % 3);
            double l1 = 4.5D + 1.2D * Math.abs(Math.sin(k * 1.3D));
            for (int i = 0; i <= n; i++) {
                double u = i / (double) n;
                double along = l0 + (l1 - l0) * u;
                double ang = a0 + u * (2.4D + 0.4D * (k % 2));
                double r = r0 * (0.7D + 0.3D * u);
                p[i] = top.add(d.scale(along)).add(side.scale(Math.cos(ang) * r)).add(up.scale(Math.sin(ang) * r));
                w[i] = (thin ? 0.05D : 0.3D + 0.5D * Math.abs(Math.sin(k * 1.7D + 0.4D))) * Math.sin(Math.PI * Math.pow(u, 0.8D));
            }
            if (thin) {
                fstrip(v, pose, camera, p, w, 0.5F * a, BODY);
                continue;
            }
            fstrip(v, pose, camera, p, w, 0.13F * a, BAND);
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.15D), 0.3F * a, BODY);
        }
    }

    /**
     * Пучок пикирования: параллельные разрезы в коридоре, узком у начала и широком у конца; голова
     * каждого чуть впереди мастера (клинок), хвост начинается у точки зависания. После прохода хвосты
     * догоняют головы — дорожки гаснут первыми, лепестки ещё летят.
     */
    private static void beam(Cast c, Entity e, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        if (c.diveTick < 0 || c.from == null) {
            return;
        }
        float age = clientTicks - c.diveTick + partial;
        double len = Math.max(0.5D, c.to.distanceTo(c.from));
        double prog;
        if (e != null && age < ShowerRules.DIVE_TICKS + 2) {
            Vec3 body = e.getPosition(partial).add(0.0D, 0.9D, 0.0D);
            prog = Mth.clamp(body.subtract(c.from).dot(c.dir) / len, 0.0D, 1.0D);
        } else {
            prog = 1.0D;
        }
        float hold = ShowerRules.DIVE_TICKS + 1.0F;
        double fade = Mth.clamp((age - hold) / 6.0D, 0.0D, 1.0D);
        float a = (float) (1.0D - fade * fade);
        if (a <= 0.0F) {
            return;
        }
        double spread = ShowerRules.spread(c.layer) * (0.9D + 0.1D * Math.min(1.0D, c.density));
        for (Streak st : c.streaks) {
            if (age < st.delay()) {
                continue;
            }
            double head = Math.min(st.t1(), prog + st.lead() / len);
            // Хвост тянется за мастером на ~4 блока (не лазер от зависания до земли), потом догоняет голову.
            double from = Math.max(st.t0(), prog - (1.6D + 1.6D * st.t0() + 0.4D * Math.abs(st.v())) / len);
            double tail = from + (head - from) * fade;
            if (head - tail < 0.02D) {
                continue;
            }
            int n = 6;
            Vec3[] p = new Vec3[n + 1];
            double[] w = new double[n + 1];
            for (int i = 0; i <= n; i++) {
                double u = i / (double) n;
                double tt = tail + (head - tail) * u;
                p[i] = beamPoint(c, tt, st.u(), st.v(), spread);
                // Чёткое начало, плавно сходит к острию.
                w[i] = st.width() * (u < 0.15D ? u / 0.15D : 1.0D - 0.6D * Math.max(0.0D, (u - 0.75D) / 0.25D));
            }
            switch (st.kind()) {
                case 0 -> {
                    fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.4D), 0.22F * a, BODY);
                    fstrip(v, pose, camera, p, w, 0.95F * a, CORE);
                }
                case 1 -> {
                    fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.4D), 0.2F * a, RIM);
                    fstrip(v, pose, camera, p, w, 0.8F * a, RIM);
                    fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.3D), 0.9F * a, CORE);
                }
                default -> fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.6D), 0.9F * a, DEEP);
            }
        }
        // Масса удара вокруг мастера: широкий бело-розовый конус от тела назад — тело внутри удара.
        if (e != null && age < ShowerRules.DIVE_TICKS + 2) {
            Vec3 body = e.getPosition(partial).add(0.0D, 0.9D, 0.0D);
            float ea = (float) PlumVfx.curve(age, 0.0, 0.3, 1.0, 1.0, ShowerRules.DIVE_TICKS + 2.0, 0.0);
            // Острый передний край — клинок впереди тела (shower-6), за ним плотная масса.
            Vec3[] p = {body.add(c.dir.scale(1.8D)), body.add(c.dir.scale(0.6D)), body, body.subtract(c.dir.scale(2.0D)), body.subtract(c.dir.scale(4.5D))};
            double[] w = {0.0D, 0.55D * spread + 0.2D, 0.85D * spread + 0.35D, 0.95D * spread + 0.4D, 0.0D};
            fstrip(v, pose, camera, p, w, 0.22F * ea, BODY);
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.55D), 0.4F * ea, BODY);
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.22D), 0.8F * ea, CORE);
        }
        // Следы самого тела: вытянутые полосы за корпусом (плечи, пояс, ноги) — пролёт тела, а не только пучок.
        if (e != null && age < ShowerRules.DIVE_TICKS + 3) {
            Vec3 body = e.getPosition(partial).add(0.0D, 0.9D, 0.0D);
            float ta = (float) PlumVfx.curve(age, 0.0, 0.0, 1.0, 1.0, ShowerRules.DIVE_TICKS + 3.0, 0.0);
            double[][] offs = {{0.0D, 0.45D, 2.6D}, {0.25D, 0.0D, 3.2D}, {-0.25D, -0.1D, 2.2D}, {0.0D, -0.45D, 1.8D}};
            for (double[] o : offs) {
                Vec3 h = body.add(c.s1.scale(o[0])).add(c.s2.scale(o[1]));
                Vec3[] p = {h.subtract(c.dir.scale(o[2])), h.subtract(c.dir.scale(o[2] * 0.4D)), h};
                double[] w = {0.0D, 0.07D, 0.03D};
                fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.2D), 0.18F * ta, BODY);
                fstrip(v, pose, camera, p, w, 0.9F * ta, CORE);
            }
        }
        // Голубые дуги поперёк пучка (shower2-03) — с 5-го слоя, коротко.
        if (ShowerRules.crossArcs(c.layer) && age < 9.0F) {
            float ca = (float) PlumVfx.curve(age, 0.0, 0.0, 1.5, 1.0, 9.0, 0.0);
            for (int k = 0; k < 2; k++) {
                double tt = k == 0 ? 0.45D : 0.72D;
                if (tt > prog + 0.1D) {
                    continue;
                }
                Vec3 o = beamPoint(c, tt, 0.0D, 0.0D, spread);
                double r = (0.25D + spread * tt) * 1.3D;
                int n = 14;
                Vec3[] p = new Vec3[n + 1];
                double[] w = new double[n + 1];
                for (int i = 0; i <= n; i++) {
                    double u = i / (double) n;
                    double ang = Math.PI * (0.15D + 0.9D * u) + k * 1.3D;
                    p[i] = o.add(c.s1.scale(Math.cos(ang) * r)).add(c.s2.scale(Math.sin(ang) * r)).add(c.dir.scale((u - 0.5D) * 0.6D));
                    w[i] = 0.05D * Math.sin(Math.PI * u);
                }
                fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.4D), 0.15F * ca, COLD);
                fstrip(v, pose, camera, p, w, 0.7F * ca, CYAN);
                fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.3D), 0.9F * ca, CYAN_HI);
            }
        }
    }

    /** Точка пучка: доля пути {@code t}, смещение в сечении, ширина коридора растёт к концу. */
    private static Vec3 beamPoint(Cast c, double t, double u, double v, double spread) {
        double width = 0.15D + spread * Math.pow(Math.max(0.0D, t), 1.2D);
        Vec3 base = c.from.add(c.to.subtract(c.from).scale(t));
        return base.add(c.s1.scale(u * width)).add(c.s2.scale(v * width));
    }

    /** Следы, стягивающиеся к узлу, ленты ветра, осколки удара. */
    private static void motes(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Mote m : c.motes) {
            if (m.kind == Mote.PETAL || m.count < 1) {
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
                w[i] = m.size * Math.sin(Math.PI * Math.min(1.0D, 0.1D + i / (double) n * 0.9D));
            }
            float life = (m.age + partial) / m.life;
            float a = (float) PlumVfx.curve(life, 0.0, 0.3, 0.15, 1.0, 0.7, 0.85, 1.0, 0.0);
            switch (m.kind) {
                case Mote.WIND -> {
                    fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.0D), 0.06F * a, COLD);
                    fstrip(v, pose, camera, p, w, 0.3F * a, BODY);
                    fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.25D), 0.85F * a, PlumVfx.EDGE);
                }
                case Mote.STREAK -> {
                    fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.2D), 0.2F * a, KNOT);
                    fstrip(v, pose, camera, p, w, 0.75F * a, RIM);
                    fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.35D), 0.95F * a, CORE);
                }
                default -> {
                    fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.2D), 0.2F * a, RIM);
                    fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.5D), 0.95F * a, CORE);
                }
            }
        }
    }

    /** Звезда прокола и черты разрезов (в пространстве), борозды по земле (плоские). */
    private static void rays(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Ray r : c.rays) {
            float age = clientTicks - r.born() + partial;
            if (age < 0.0F) {
                continue;
            }
            double grow = Mth.clamp(age / 1.5D, 0.0D, 1.0D);
            if (r.flat()) {
                float a = (float) PlumVfx.curve(age, 0.0, 1.0, 2.0, 1.0, r.life(), 0.0);
                Vec3[] p = {r.a(), r.a().lerp(r.b(), grow * 0.5D), r.a().lerp(r.b(), grow)};
                PlumVfx.flatStrip(v, pose, p, new double[] {r.width() * 2.4D, r.width() * 1.6D, 0.0D}, 0.3F * a, RIM);
                PlumVfx.flatStrip(v, pose, p, new double[] {r.width(), r.width() * 0.6D, 0.0D}, 0.95F * a, CORE);
                continue;
            }
            float a = (float) PlumVfx.curve(age, 0.0, 1.0, 1.5, 1.0, r.life(), 0.0);
            Vec3 mid = r.a().lerp(r.b(), 0.4D * grow);
            Vec3[] p = {r.a(), mid, r.a().lerp(r.b(), grow)};
            double[] w = {r.width() * 0.4D, r.width(), 0.0D};
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.4D), 0.12F * a, RIM);
            fstrip(v, pose, camera, p, w, 0.95F * a, CORE);
        }
    }

    /** Кольца пробитого воздуха и загнутая бело-розовая лента удара о землю (shower-5). */
    private static void ground(Cast c, PoseStack.Pose pose, VertexConsumer v, float partial) {
        for (Ring r : c.rings) {
            float age = clientTicks - r.born() + partial;
            if (age < 0.0F) {
                continue;
            }
            float k = Mth.clamp(age / r.life(), 0.0F, 1.0F);
            double rad = 0.4D + (r.rMax() - 0.4D) * (1.0D - Math.pow(1.0D - k, 4.0D));
            float a = (1.0F - k) * (1.0F - k);
            int n = 40;
            Vec3[] p = new Vec3[n + 1];
            double[] w = new double[n + 1];
            for (int i = 0; i <= n; i++) {
                double ang = i * Math.PI * 2.0D / n;
                p[i] = r.centre().add(Math.cos(ang) * rad, 0.0D, Math.sin(ang) * rad);
                w[i] = r.width() * (0.6D + 0.4D * Math.abs(Math.sin(ang * 3.0D + r.born())));
            }
            PlumVfx.flatStrip(v, pose, p, PlumVfx.scale(w, 2.0D), 0.25F * a, RIM);
            PlumVfx.flatStrip(v, pose, p, PlumVfx.scale(w, 0.4D), 0.9F * a, CORE);
        }
        if (c.landTick < 0 || !c.landHit) {
            return;
        }
        float age = clientTicks - c.landTick + partial;
        if (age > 12.0F) {
            return;
        }
        Vec3 f = new Vec3(c.dir.x, 0.0D, c.dir.z);
        f = f.lengthSqr() < 1.0E-4D ? new Vec3(0.0D, 0.0D, 1.0D) : f.normalize();
        Vec3 side = new Vec3(-f.z, 0.0D, f.x);
        double k = 1.0D - Math.pow(1.0D - Mth.clamp(age / 8.0D, 0.0D, 1.0D), 3.0D);
        float a = (float) PlumVfx.curve(age, 0.0, 1.0, 3.0, 1.0, 12.0, 0.0) * (c.eyes() ? 0.45F : 1.0F);
        Vec3 centre = c.landAt.add(f.scale(0.4D)).add(0.0D, 0.08D, 0.0D);
        // Загнутая лента: дуга вперёд ±110°, концы загибаются назад и вверх по краям.
        for (int band = 0; band < 1; band++) {
            double rad = (0.8D + 1.8D * k) * c.scale();
            int n = 30;
            Vec3[] p = new Vec3[n + 1];
            double[] w = new double[n + 1];
            for (int i = 0; i <= n; i++) {
                double u = i / (double) n;
                double ang = Math.toRadians(-62.0D + 124.0D * u);
                double curl = Math.pow(Math.abs(u - 0.5D) * 2.0D, 3.0D);
                p[i] = centre.add(f.scale(Math.cos(ang) * rad * 1.5D)).add(side.scale(Math.sin(ang) * rad * 0.8D)).add(0.0D, curl * 0.5D * rad * 0.3D, 0.0D);
                w[i] = (band == 0 ? 0.55D : 0.3D) * (1.0D - 0.6D * k) * Math.sin(Math.PI * Math.min(1.0D, 0.05D + u * 0.9D));
            }
            PlumVfx.flatStrip(v, pose, p, PlumVfx.scale(w, 1.8D), 0.22F * a, BODY);
            PlumVfx.flatStrip(v, pose, p, w, 0.55F * a, band == 0 ? CORE : LILAC);
            PlumVfx.flatStrip(v, pose, p, PlumVfx.scale(w, 0.35D), 0.95F * a, CORE);
        }
    }

    /** Свечения (аддитивно): узел у руки, звезда прокола, белая вспышка внизу. */
    private static void glows(Cast c, Entity e, PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float partial) {
        RenderType gt = MurimRenderTypes.mote();
        VertexConsumer g = buffers.getBuffer(gt);
        float t = c.t() + partial;
        if (e != null && c.release < 0 && t >= ShowerRules.COIL) {
            double grow = Mth.clamp((t - ShowerRules.COIL) / (ShowerRules.RELEASE - ShowerRules.COIL), 0.0D, 1.0D);
            Vec3 k = knot(c, e);
            float pulse = 0.85F + 0.15F * Mth.sin(t * 1.3F);
            float fa = near(k, camera);
            PlumVfx.glow(g, pose, camera, k, (0.35D + 0.75D * grow) * pulse, 0.6F * fa, KNOT);
            PlumVfx.glow(g, pose, camera, k, (0.12D + 0.2D * grow) * pulse, 0.9F * fa, CORE);
        }
        if (c.hitTick >= 0) {
            float age = clientTicks - c.hitTick + partial;
            if (age < 6.0F) {
                float a = (float) PlumVfx.curve(age, 0.0, 1.0, 6.0, 0.0);
                float fa = near(c.hitAt, camera);
                PlumVfx.glow(g, pose, camera, c.hitAt, 1.2D + 1.6D * Math.min(1.0D, age / 2.0D), 0.9F * a * fa, CORE);
                PlumVfx.glow(g, pose, camera, c.hitAt, 2.4D + 1.5D * Math.min(1.0D, age / 2.0D), 0.35F * a * fa, RIM);
            }
        }
        if (c.landTick >= 0 && c.landHit) {
            float age = clientTicks - c.landTick + partial;
            if (age < 9.0F) {
                float a = (float) PlumVfx.curve(age, 0.0, 1.0, 2.0, 0.9, 9.0, 0.0);
                Vec3 at = c.landAt.add(0.0D, 0.5D, 0.0D);
                float fa = near(at, camera);
                PlumVfx.glow(g, pose, camera, at, 1.2D + 0.8D * Math.min(1.0D, age / 3.0D), 1.0F * a * fa, CORE);
                PlumVfx.glow(g, pose, camera, at, 2.0D + 1.0D * Math.min(1.0D, age / 3.0D), 1.0F * a * fa, CORE);
                PlumVfx.glow(g, pose, camera, at, 2.8D + 1.2D * Math.min(1.0D, age / 3.0D), 0.7F * a * fa, CORE);
                PlumVfx.glow(g, pose, camera, at, 3.6D + 1.5D * Math.min(1.0D, age / 3.0D), 0.22F * a * fa, BODY);
            }
        }
        for (Mote m : c.motes) {
            if (m.kind == Mote.PETAL && m.pos.distanceToSqr(camera) > 1.6D) {
                float a = Mth.clamp((m.life - m.age - partial) / 10.0F, 0.0F, 1.0F);
                PlumVfx.glow(g, pose, camera, m.prev.lerp(m.pos, partial), m.size * 2.0D, 0.35F * a, PlumVfx.PINK);
            }
        }
        buffers.endBatch(gt);
    }

    private static void petals(Cast c, PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float partial) {
        if (!ShowerRules.petals(c.layer)) {
            return;
        }
        RenderType pt = MurimRenderTypes.plumPetals();
        VertexConsumer pc = buffers.getBuffer(pt);
        for (Mote m : c.motes) {
            if (m.kind != Mote.PETAL || m.pos.distanceToSqr(camera) < 1.6D) {
                continue;
            }
            float a = Mth.clamp((m.life - m.age - partial) / 10.0F, 0.0F, 1.0F) * Mth.clamp((m.age + partial) / 2.0F, 0.0F, 1.0F);
            PlumVfx.petal(pc, pose, camera, m.prev.lerp(m.pos, partial), m.size * 1.6D, m.cell, (m.age + partial) * m.spin, a,
                    1.0F, 0.72F, 0.8F);
        }
        buffers.endBatch(pt);
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
            PlumVfx.smokePuff(sm, pose, camera, at, p.size * grow, p.cell, alpha * cam, p.gray, p.spin);
        }
        buffers.endBatch(smokeType);
    }

    /** Остаточные образы тела на пикировании (shower-4). От первого лица не рисуются — закрыли бы экран. */
    private static void ghosts(Minecraft mc, Cast c, PoseStack ps, MultiBufferSource.BufferSource buffers, float partial) {
        if (c.ghosts.isEmpty() || c.eyes() || !(mc.level.getEntity(c.entityId) instanceof AbstractClientPlayer player)) {
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
                float k = (clientTicks - g.born() + partial) / g.life();
                if (k < 0.0F || k > 1.0F) {
                    continue;
                }
                for (int i = 0; i < parts.length; i++) {
                    parts[i].loadPose(g.pose()[i]);
                }
                draw(model, ps, buffers.getBuffer(type), g.pos(), g.yaw(), g.alpha() * (1.0F - k) * (1.0F - k), g.rgb());
            }
            buffers.endBatch(type);
        } finally {
            for (int i = 0; i < parts.length; i++) {
                parts[i].loadPose(saved[i]);
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

    private ShowerVfx() {
    }
}
