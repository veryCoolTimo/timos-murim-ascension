package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.CameraShakeHandler;
import io.github.verycooltimo.murim.client.ClientAuraState;
import io.github.verycooltimo.murim.network.ExplosionPayload;
import io.github.verycooltimo.murim.network.TechniqueEventPayload;
import io.github.verycooltimo.murim.technique.ExplosionRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
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
import static io.github.verycooltimo.murim.client.vfx.PlumVfx.PINK;

/**
 * Взрыв Цветущей Сливы (рефы «7 plum blossoms sword/explosion» 01–03, шкала — {@link ExplosionRules}).
 *
 * <p>Автор 03.10: «Он короткий: просто долгий замах справа вверх, и на ударе — взрыв лепестков».
 * Стойка (синяя ци, пыль) → долгий медленный замах: острие идёт снизу справа вверх и за плечо,
 * за ним тянется холодный след, вокруг закручиваются и стягиваются к клинку розовые лепестки
 * и ветер → короткая пауза наверху, клинок дрожит → быстрый удар вниз-вперёд, голубо-белая
 * косая дуга (ref2) → в точке удара белое ядро и взрыв лепестков конусом к цели (ref3; в небо —
 * тоже): лучи, рваные розовые шлейфы, слитная масса, три полосы скорости, турбулентность растёт.
 * Импакт-кадр, сильная тряска и дым — только по пакету попадания. Всё — симуляция частиц.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class ExplosionVfx {

    private static final ResourceLocation TECHNIQUE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "seven_plum_explosion");
    /** Взрыв (ref3): белое ядро, светло-розовый, насыщенный, красноватый. */
    private static final VfxColour WHITE = hex(0xFFFFFF);
    private static final VfxColour BLOOM = hex(0xFFC3D2);
    private static final VfxColour HOT = hex(0xF65A86);
    private static final VfxColour DEEP = hex(0xEA365F);
    /** След клинка (ref2): белое ядро, голубые края, синий контур. */
    private static final VfxColour BLADE_CORE = hex(0xF4FFFF);
    private static final VfxColour BLADE_EDGE = hex(0x89EFFF);
    private static final VfxColour BLADE_RIM = hex(0x5877E8);
    private static final VfxColour WIND = hex(0xE8EDF1);

    private static final List<Cast> CASTS = new ArrayList<>();
    private static int clientTicks;

    private static VfxColour hex(int rgb) {
        return new VfxColour(((rgb >> 16) & 0xFF) / 255.0F, ((rgb >> 8) & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F);
    }

    /** Вблизи камеры тает: от первого лица ничего не закрывает экран. */
    private static float near(Vec3 at, Vec3 camera) {
        return (float) Mth.clamp((at.distanceTo(camera) - 1.2D) / 1.5D, 0.0D, 1.0D);
    }

    private static void fstrip(VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Vec3[] p, double[] w, float[] a, VfxColour col) {
        float[] r = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = a[i] * near(p[i], camera);
        }
        PlumVfx.stripVar(v, pose, camera, p, w, r, col);
    }

    private static void fstrip(VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Vec3[] p, double[] w, float alpha, VfxColour col) {
        fstrip(v, pose, camera, p, w, PlumVfx.filled(p.length, alpha), col);
    }

    // ------------------------------------------------------------------ частицы

    /** Свободная частица: лепесток, световой штрих-обломок ветви, лента ветра, искра. */
    private static final class Mote {
        static final int PETAL = 0;
        static final int WIND_RIBBON = 1;
        static final int SHARD = 2;
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
        double drag = 0.9D;
        double gravity;
        double turbulence;
        /** Турбулентность растёт с возрастом (DESCRIPTIONS: «сначала слабая, затем усиливается»). */
        double turbulenceGrow;
        int delay;
        /** Тон лепестка: 0 — белый, 1 — розовый, 2 — насыщенный. */
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

    /** Луч взрыва (ref3): из ядра наружу, растёт за 2 тика, гаснет к 8-му. */
    private record Ray(Vec3 from, Vec3 dir, double len, double width, int born, boolean white) {
    }

    private static final class Cast {
        final int entityId;
        final int layer;
        int start;
        final Random random;
        final double density;
        Vec3 feet;
        Vec3 normal = new Vec3(0.0D, 0.0D, 1.0D);
        Vec3 side = new Vec3(-1.0D, 0.0D, 0.0D);
        int targetId = -1;
        int lungeTick = -1;
        Vec3 lungeFrom;
        Vec3 lungeTo;
        int blastTick = -1;
        Vec3 strike;
        Vec3 axis;
        boolean caption;
        boolean impactShown;
        /** Острие в замахе (тик за тиком): холодный след набора силы. */
        final List<Vec3> charge = new ArrayList<>();
        final List<Mote> motes = new ArrayList<>();
        final List<Puff> puffs = new ArrayList<>();
        final List<Ray> rays = new ArrayList<>();
        /** Точки острия во взмахе удара: голубо-белый след (ref2). */
        final List<Vec3> blade = new ArrayList<>();
        /** Перекрестья ударов по целям: {точка, тик}. */
        final List<Object[]> cuts = new ArrayList<>();

        Cast(int entityId, int layer) {
            this.entityId = entityId;
            this.layer = layer;
            this.start = clientTicks;
            this.random = new Random(entityId * 8191L + clientTicks);
            this.density = ExplosionRules.density(layer);
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
    }

    private static Component school() {
        return Component.translatable("technique.murim.seven_plum_blossoms.school");
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
        // Стойка: тело горит холодной синей ци до прыжка.
        // codex 03.10: аура тела тише — главное в замахе след клинка.
        ClientAuraState.techniqueAura(c.entityId, 1, 0, ExplosionRules.LUNGE + 2);
        Minecraft mc = Minecraft.getInstance();
        Entity e = mc.level == null ? null : mc.level.getEntity(c.entityId);
        if (e != null) {
            dust(c, e.position(), 4 + c.layer, 0.12D);
            facing(c, e.getYRot());
        }
    }

    private static void facing(Cast c, float yaw) {
        Vec3 f = Vec3.directionFromRotation(0.0F, yaw);
        c.normal = new Vec3(f.x, 0.0D, f.z).normalize();
        c.side = new Vec3(-c.normal.z, 0.0D, c.normal.x);
    }

    public static void onExplosion(ExplosionPayload p) {
        Cast c = null;
        for (Cast x : CASTS) {
            if (x.entityId == p.entityId()) {
                c = x;
            }
        }
        Minecraft mc = Minecraft.getInstance();
        if (c == null) {
            return;
        }
        if (p.stage() == ExplosionPayload.LUNGE) {
            c.start = clientTicks - ExplosionRules.LUNGE;
            c.lungeTick = clientTicks;
            c.lungeFrom = p.origin();
            c.lungeTo = p.centre();
            c.feet = p.origin();
            c.targetId = p.targetId();
            facing(c, p.yaw());
            dust(c, p.origin(), c.n(10) + 4, 0.2D);
            if (c.own()) {
                SpeedLines.radial(0.5F, 0.5F, 0.7F, 6, SpeedLines.WHITE);
            }
            if (mc.player != null) {
                mc.player.level().playLocalSound(p.origin().x, p.origin().y, p.origin().z, net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP,
                        net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.3F, false);
            }
            return;
        }
        if (p.stage() == ExplosionPayload.BLAST) {
            c.start = clientTicks - ExplosionRules.CONTACT;
            c.blastTick = clientTicks;
            c.strike = p.origin();
            Vec3 ax = p.centre().subtract(p.origin());
            c.axis = ax.lengthSqr() < 1.0E-6D ? c.normal : ax.normalize();
            blast(c, mc);
            return;
        }
        if (p.stage() == ExplosionPayload.HIT) {
            hit(c, p.centre(), p.yaw() > 0.5F, mc);
        }
    }

    /**
     * Контакт: белое ядро, рваные лучи и розовые шлейфы (ref3), взрыв лепестков из точки удара
     * ({@link #burst}); кольцо пыли по земле под ударом, ветер наружу.
     */
    private static void blast(Cast c, Minecraft mc) {
        Random r = c.random;
        burst(c);
        // Автор 03.10: «убери этот типо ветер от взрыва линиями» — тонких лучей, ветряных лент
        // и следов-осколков у лепестков больше нет; остаются ядро, розовые массы и лепестки.
        Vec3 up = c.side.cross(c.axis).normalize();
        // Ударная волна: кольцо пыли под точкой удара.
        groundDust(c, c.strike.subtract(0.0D, ExplosionRules.STRIKE_Y, 0.0D), 10 + c.n(14), 0.32D);
        if (c.layer >= 3) {
            ClientAuraState.techniqueAura(c.entityId, 2, 1, 24);
        }
        Vec3 at = c.strike;
        if (c.own()) {
            SpeedLines.radial(0.5F, 0.5F, 1.0F, 8, SpeedLines.WHITE);
        }
        if (mc.player != null) {
            double dist = mc.player.position().distanceTo(at);
            if (dist < 20.0D) {
                // Взрыв у клинка — короткий толчок; сильная тряска — только по попаданию.
                CameraShakeHandler.quake((float) Math.max(c.own() ? 0.4D : 0.0D, 0.4D * (1.0D - dist / 20.0D)), 8);
            }
            mc.player.level().playLocalSound(at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_STRONG,
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.6F, false);
        }
    }

    /**
     * Взрыв из точки удара: лепестки трёх полос скорости (ближний плотный слой и дальние
     * одиночки) — веером с перевесом к цели. Световых штрихов нет (автор 03.10).
     */
    private static void burst(Cast c) {
        Random r = c.random;
        double speedK = 0.8D + 0.25D * c.density;
        if (!ExplosionRules.petals(c.layer)) {
            return;
        }
        int n = c.n(760);
        for (int i = 0; i < n; i++) {
            // Облако лепестков в момент удара: шар ~1 блок вокруг точки, чуть вытянут вдоль клинка.
            Vec3 p = c.strike.add(c.side.scale(r.nextGaussian() * 0.45D)).add(r.nextGaussian() * 0.25D, r.nextGaussian() * 0.4D,
                    r.nextGaussian() * 0.25D);
            double band = r.nextDouble();
            double speed = band < 0.35D ? 1.1D + 0.6D * r.nextDouble() : band < 0.75D ? 0.55D + 0.35D * r.nextDouble() : 0.2D + 0.25D * r.nextDouble();
            // Короткий остаток (codex 03.10): лепестки редеют за ~1,3 с, не висят конфетти.
            Mote m = petal(c, p, fan(c, p, r).scale(speed * speedK), 18 + r.nextInt(22));
            m.delay = r.nextInt(2);
            m.drag = band < 0.35D ? 0.88D : 0.9D;
            m.gravity = band < 0.35D ? 0.0015D : 0.003D;
            m.turbulence = 0.004D;
            m.turbulenceGrow = 0.0007D;
            m.tone = r.nextInt(6) == 0 ? 0 : r.nextInt(4) == 0 ? 2 : 1;
            c.motes.add(m);
        }
    }

    /** Направление выброса: к цели с раскрытием от удара, плюс шум. */
    private static Vec3 fan(Cast c, Vec3 p, Random r) {
        Vec3 out = p.subtract(c.strike);
        Vec3 lat = out.subtract(c.axis.scale(out.dot(c.axis)));
        lat = lat.lengthSqr() < 1.0E-6D ? Vec3.ZERO : lat.normalize();
        // Ref3: лучи и лепестки во все стороны от ядра — часть уходит назад и вбок.
        if (r.nextDouble() < 0.08D) {
            return c.axis.scale(-0.5D).add(lat.scale(0.8D)).add(r.nextGaussian() * 0.3D, 0.2D + r.nextDouble() * 0.3D, r.nextGaussian() * 0.3D)
                    .normalize();
        }
        // codex 03.10: 70 % выброса — в конусе ±20° вокруг оси на цель (в небо — тоже).
        return c.axis.scale(1.0D).add(lat.scale(0.12D + 0.28D * r.nextDouble()))
                .add(r.nextGaussian() * 0.09D, r.nextGaussian() * 0.07D + 0.03D, r.nextGaussian() * 0.09D).normalize();
    }

    /** Попадание выброса: перекрестье, лепестки у цели; главное — импакт-кадр, тряска, дым. */
    private static void hit(Cast c, Vec3 at, boolean grind, Minecraft mc) {
        Random r = c.random;
        c.cuts.add(new Object[] {at, clientTicks, grind});
        if (ExplosionRules.petals(c.layer)) {
            for (int i = 0; i < (grind ? c.n(8) : c.n(30)); i++) {
                Vec3 v = (c.axis == null ? c.normal : c.axis).scale(0.25D).add(r.nextGaussian() * 0.2D, 0.1D + r.nextDouble() * 0.2D, r.nextGaussian() * 0.2D);
                Mote m = petal(c, at, v, 30 + r.nextInt(20));
                m.turbulence = 0.006D;
                m.tone = r.nextInt(3) == 0 ? 2 : 1;
                c.motes.add(m);
            }
        }
        double dist = mc.player == null ? 99.0D : mc.player.position().distanceTo(at);
        if (grind) {
            if (dist < 16.0D) {
                CameraShakeHandler.quake(0.2F, 4);
            }
            return;
        }
        if (c.own() && !c.impactShown) {
            c.impactShown = true;
            ImpactFrames.trigger(at);
            SpeedLines.radial(0.5F, 0.5F, 0.9F, 7, SpeedLines.WHITE);
        }
        if (mc.player != null && dist < 24.0D) {
            float q = dist < 8.0D ? 1.0F : (float) (1.0D - (dist - 8.0D) / 16.0D);
            CameraShakeHandler.quake(Math.max(q, c.own() ? 0.85F : 0.0F), 18);
            mc.player.level().playLocalSound(at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(),
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.8F, 1.1F, false);
        }
        smoke(c, at);
    }

    /** Дым манхвы у цели: низкий вал одной массой, через 6 тиков над ним встаёт облако. */
    private static void smoke(Cast c, Vec3 at) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        // Цель в небе: дым — облаком вокруг неё; на земле — вал по земле.
        BlockPos below = BlockPos.containing(at.x, at.y - 1.2D, at.z);
        boolean ground = !mc.level.getBlockState(below).isAir() || !mc.level.getBlockState(below.below()).isAir();
        Vec3 g = ground ? new Vec3(at.x, below.getY() + 1.0D, at.z) : at;
        Entity caster = mc.level.getEntity(c.entityId);
        Random r = c.random;
        int n = Math.max(8, c.n(22));
        double rad = 1.8D + 0.4D * c.density;
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2.0D * i / n + r.nextDouble() * 0.3D;
            Vec3 out = new Vec3(Math.cos(a), ground ? 0.0D : (r.nextDouble() - 0.5D) * 1.2D, Math.sin(a)).normalize();
            Vec3 p = g.add(out.scale(rad * (0.4D + 0.4D * r.nextDouble())));
            if (caster != null && p.distanceTo(caster.position()) < 2.6D) {
                continue;
            }
            boolean hollow = i % 3 == 0;
            double size = (0.5D + 0.9D * Math.pow(r.nextDouble(), 1.5D)) * (hollow ? 1.25D : 1.0D) * (0.7D + 0.3D * c.density);
            Puff bank = new Puff(p.add(0.0D, ground ? size * 0.45D : 0.0D, 0.0D), out.scale(0.1D + 0.2D * r.nextDouble()),
                    30 + r.nextInt(16), r.nextInt(16), size, true, hollow ? 0.56F : 0.84F + 0.12F * r.nextFloat(), (float) (r.nextDouble() * 6.28D));
            // Дым — после пика взрыва (codex 03.10).
            bank.delay = 4 + r.nextInt(4);
            c.puffs.add(bank);
        }
        for (int i = 0; i < n / 2; i++) {
            double a = r.nextDouble() * Math.PI * 2.0D;
            Vec3 p = g.add(Math.cos(a) * rad * 0.35D, ground ? 0.8D : 0.3D, Math.sin(a) * rad * 0.35D);
            if (caster != null && p.distanceTo(caster.position()) < 2.6D) {
                continue;
            }
            Puff rise = new Puff(p, new Vec3(0.0D, 0.05D + 0.04D * r.nextDouble(), 0.0D), 38 + r.nextInt(14), r.nextInt(16),
                    (0.8D + 0.9D * r.nextDouble()) * (0.7D + 0.3D * c.density), true, 0.74F + 0.14F * r.nextFloat(), (float) (r.nextDouble() * 6.28D));
            rise.delay = 6 + r.nextInt(8);
            c.puffs.add(rise);
        }
    }

    private static Mote petal(Cast c, Vec3 at, Vec3 vel, int life) {
        Mote m = new Mote(at, vel, life, Mote.PETAL, c.random.nextInt(4), (float) ((c.random.nextDouble() - 0.5D) * 0.7D),
                0.06D + 0.05D * c.random.nextDouble(), 1);
        m.drag = 0.92D;
        m.gravity = 0.0025D;
        return m;
    }

    private static void wind(Cast c, Vec3 at, Vec3 vel, int life, double size) {
        Mote m = new Mote(at, vel, life, Mote.WIND_RIBBON, 0, 0.0F, size, 10);
        m.drag = 0.9D;
        m.turbulence = 0.03D;
        c.motes.add(m);
    }

    /** Серая пыль от движения (не белая): по земле наружу. */
    private static void dust(Cast c, Vec3 feet, int n, double speed) {
        groundDust(c, feet, n, speed);
    }

    private static void groundDust(Cast c, Vec3 at, int n, double speed) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.getBlockState(BlockPos.containing(at.add(0.0D, -0.2D, 0.0D))).isAir()) {
            return;
        }
        for (int i = 0; i < n; i++) {
            double a = c.random.nextDouble() * Math.PI * 2.0D;
            Vec3 out = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
            c.puffs.add(new Puff(at.add(out.scale(0.3D)).add(0.0D, 0.1D, 0.0D), out.scale(speed * (0.6D + 0.8D * c.random.nextDouble()))
                    .add(0.0D, 0.01D, 0.0D), 16 + c.random.nextInt(8), c.random.nextInt(16), 0.22D + 0.18D * c.random.nextDouble(),
                    false, 0.62F, 0.0F));
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
            int t = c.t();
            Entity e = mc.level.getEntity(c.entityId);
            if (e != null && c.lungeTick < 0 && t >= ExplosionRules.STANCE && t < ExplosionRules.LUNGE) {
                windup(c, e, t, mc);
            }
            if (t == ExplosionRules.CAPTION && c.own() && !c.caption) {
                c.caption = true;
                TechniqueCaption.show(school(), Component.translatable("technique.murim.seven_plum_blossoms.explosion"), 44);
            }
            // Стойка: пыль из-под ног (серая), меч поднят.
            if (t > 0 && t < ExplosionRules.LUNGE && t % 6 == 0 && e != null) {
                dust(c, e.position(), 2, 0.08D);
            }
            // Острие в ударе: из-за правого плеча вниз-вперёд, к точке удара.
            if (c.lungeTick >= 0 && t >= ExplosionRules.LUNGE && t <= ExplosionRules.CONTACT + 1 && e != null) {
                double k = Mth.clamp((t - ExplosionRules.LUNGE) / (double) ExplosionRules.LUNGE_TICKS, 0.0D, 1.0D);
                for (int sub = 0; sub < 3; sub++) {
                    double kk = Math.min(1.0D, k + sub / 3.0D / ExplosionRules.LUNGE_TICKS);
                    // Угол от вертикали: −40° — над правым плечом сзади, +120° — впереди внизу.
                    double a = Math.toRadians(-40.0D + 160.0D * kk * kk);
                    // Косая дуга (ref2): из-за правого плеча вниз-влево; в вертикальной плоскости
                    // со спины она читалась столбом (codex 03.10). side — правая сторона мастера.
                    Vec3 shoulder = e.position().add(0.0D, 1.45D, 0.0D);
                    Vec3 radial = c.normal.scale(Math.sin(a)).add(0.0D, Math.cos(a) * 0.8D, 0.0D).add(c.side.scale(Math.cos(a) * 0.75D));
                    // От первого лица дуга дальше и впереди: видна сбоку от цели, а не тает у глаз.
                    boolean fp = c.own() && mc.options.getCameraType().isFirstPerson() && mc.getCameraEntity() == e;
                    Vec3 arc = shoulder.add(radial.scale(fp ? 2.6D : 1.8D)).add(c.normal.scale(fp ? 0.9D : 0.0D));
                    // Конец дуги — ровно в точке удара, где родится взрыв (codex 03.10: одна точка).
                    double land = Mth.clamp((kk - 0.45D) / 0.55D, 0.0D, 1.0D);
                    land = land * land * (3.0D - 2.0D * land);
                    c.blade.add(c.lungeTo == null ? arc : arc.lerp(c.lungeTo, land));
                }
            }
            tickMotes(c);
            if (t > ExplosionRules.END + 30 || t > 300) {
                it.remove();
            }
        }
    }

    /** Острие в замахе: k 0 — снизу справа впереди, 1 — высоко за правым плечом. */
    private static Vec3 windTip(Cast c, Entity e, double k) {
        double a = Math.toRadians(150.0D - 190.0D * k);
        Vec3 shoulder = e.position().add(0.0D, 1.45D, 0.0D).add(c.side.scale(0.3D));
        // Дуга в косой плоскости: вверх, назад и чуть наружу вправо.
        // Снизу далеко справа → вверх к центру за плечом: со спины дуга читается «справа вверх»,
        // а не столбом (кадры 03.10).
        Vec3 radial = c.normal.scale(Math.sin(a) * 0.85D).add(0.0D, Math.cos(a), 0.0D).add(c.side.scale(0.2D + 1.0D * (1.0D - k)));
        return shoulder.add(radial.normalize().scale(1.7D));
    }

    /**
     * Долгий замах (автор: «долгий замах справа вверх»): острие медленно идёт по дуге, за ним
     * холодный след; ци и лепестки закручиваются вокруг мастера и стягиваются к клинку,
     * ветер втягивается; наверху клинок дрожит. С 3-го слоя лепестки.
     */
    private static void windup(Cast c, Entity e, int t, Minecraft mc) {
        Random r = c.random;
        double raw = Mth.clamp((t - ExplosionRules.STANCE) / (double) (ExplosionRules.WINDUP_END - ExplosionRules.STANCE), 0.0D, 1.0D);
        double k = raw * raw * (3.0D - 2.0D * raw);
        Vec3 tip = windTip(c, e, k);
        if (t >= ExplosionRules.WINDUP_END) {
            // Пауза наверху: мелкая дрожь клинка — сила на пределе.
            tip = tip.add(r.nextGaussian() * 0.03D, r.nextGaussian() * 0.03D, r.nextGaussian() * 0.03D);
        }
        // Весь путь острия остаётся следом: одна непрерывная дуга снизу справа за плечо.
        c.charge.add(tip);
        if (ExplosionRules.petals(c.layer)) {
            for (int i = 0; i < (t % 2 == 0 ? Math.max(1, c.n(1.5D)) : 0); i++) {
                // Лепесток рождается на кольце вокруг мастера и по спирали летит к клинку.
                double ang = r.nextDouble() * Math.PI * 2.0D;
                double rad = 2.5D + 1.5D * r.nextDouble();
                Vec3 at = e.position().add(Math.cos(ang) * rad, 0.3D + 2.2D * r.nextDouble(), Math.sin(ang) * rad);
                Vec3 to = tip.subtract(at);
                Vec3 swirl = new Vec3(-to.z, 0.0D, to.x).normalize().scale(0.12D);
                Mote m = petal(c, at, to.scale(0.09D).add(swirl), 12 + r.nextInt(6));
                m.drag = 0.96D;
                m.gravity = 0.0D;
                m.tone = r.nextInt(5) == 0 ? 0 : 1;
                c.motes.add(m);
            }
        }
        if (t % 3 == 0) {
            double ang = r.nextDouble() * Math.PI * 2.0D;
            Vec3 at = e.position().add(Math.cos(ang) * 3.2D, 0.4D + r.nextDouble() * 1.6D, Math.sin(ang) * 3.2D);
            Vec3 in = e.position().add(0.0D, 1.2D, 0.0D).subtract(at).normalize();
            wind(c, at, in.scale(0.32D).add(new Vec3(-in.z, 0.0D, in.x).scale(0.18D)), 12 + r.nextInt(5), 0.1D);
        }
        if (t == ExplosionRules.STANCE || t == ExplosionRules.WINDUP_END) {
            dust(c, e.position(), 3 + c.n(4), 0.14D);
        }
        if (t == ExplosionRules.STANCE && mc.player != null) {
            mc.player.level().playLocalSound(e.getX(), e.getY(), e.getZ(), net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP,
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.4F, 0.6F, false);
        }
    }

    private static void tickMotes(Cast c) {
        for (Mote m : c.motes) {
            if (m.delay > 0) {
                m.delay--;
                continue;
            }
            if (m.trail.length > 1) {
                System.arraycopy(m.trail, 0, m.trail, 1, m.trail.length - 1);
                m.trail[0] = m.pos;
                m.count = Math.min(m.trail.length, m.count + 1);
            }
            m.prev = m.pos;
            m.age++;
            Vec3 v = m.vel.scale(m.drag).add(0.0D, -m.gravity, 0.0D);
            double tb = m.turbulence + m.turbulenceGrow * m.age;
            if (tb > 0.0D) {
                // Порывы: поле, меняющееся во времени, — не белый шум.
                double ph = m.age * 0.21D + m.cell * 1.7D + m.pos.x * 0.5D;
                v = v.add(Math.sin(ph) * tb, Math.sin(ph * 1.3D + 1.1D) * tb * 0.4D, Math.cos(ph * 0.9D + m.pos.z * 0.5D) * tb);
            }
            // Лепесток парит: покачивание.
            if (m.kind == Mote.PETAL) {
                double swayK = Math.sin(m.age * 0.35D + m.cell * 1.7D) * 0.004D;
                v = v.add(swayK, 0.0D, -swayK);
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
        c.rays.removeIf(x -> clientTicks - x.born() > 10);
        c.cuts.removeIf(x -> clientTicks - (int) x[1] > 8);
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
                float t = c.t() + partial;
                VertexConsumer air = buffers.getBuffer(MurimRenderTypes.airBand());
                chargeTrail(c, pose, camera, air, t);
                bladeTrail(c, pose, camera, air, t);
                rays(c, pose, camera, air, partial);
                ribbons(c, pose, camera, air, partial);
                cuts(c, pose, camera, air, partial);
                buffers.endBatch(MurimRenderTypes.airBand());
                puffs(c, pose, camera, buffers, partial);
                petals(c, pose, camera, buffers, partial, t);
            }
        } finally {
            ps.popPose();
        }
    }

    /**
     * След замаха: тонкая холодная лента за острием (последние 14 тиков), голубая кромка,
     * белое ядро; ярче к острию. Гаснет за 3 тика после удара.
     */
    private static void chargeTrail(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float t) {
        if (c.charge.size() < 2) {
            return;
        }
        float a = (float) Mth.clamp((ExplosionRules.LUNGE + 3.0D - t) / 3.0D, 0.0D, 1.0D);
        if (a <= 0.0F) {
            return;
        }
        Vec3[] p = c.charge.toArray(new Vec3[0]);
        double[] w = new double[p.length];
        for (int i = 0; i < p.length; i++) {
            double u = (i + 1.0D) / p.length;
            w[i] = 0.14D * Math.pow(u, 1.5D);
        }
        fstrip(v, pose, camera, p, PlumVfx.scale(w, 1.8D), 0.22F * a, BLADE_RIM);
        fstrip(v, pose, camera, p, w, 0.5F * a, BLADE_EDGE);
        fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.35D), 0.9F * a, BLADE_CORE);
    }

    /** След клинка (ref2): широкая сегментированная лента — белое ядро, голубой край, синий контур. */
    private static void bladeTrail(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float t) {
        if (c.blade.size() < 2) {
            return;
        }
        // Держится первые тики взрыва — переход от удара к розовому выбросу.
        float a = (float) Mth.clamp((ExplosionRules.CONTACT + 3.5D - t) / 2.5D, 0.0D, 1.0D);
        if (a <= 0.0F) {
            return;
        }
        Vec3[] p = c.blade.toArray(new Vec3[0]);
        double[] w = new double[p.length];
        for (int i = 0; i < p.length; i++) {
            // Ведущая кромка широкая, хвост острый (codex 03.10: видна кромка и место попадания).
            double u = (i + 0.5D) / p.length;
            w[i] = 0.13D * Math.pow(u, 1.3D) * Math.min(1.0D, (1.0D - u) * 8.0D + 0.25D);
        }
        fstrip(v, pose, camera, p, PlumVfx.scale(w, 1.5D), 0.3F * a, BLADE_RIM);
        fstrip(v, pose, camera, p, w, 0.6F * a, BLADE_EDGE);
        fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.35D), 0.95F * a, BLADE_CORE);
    }

    /** Лучи взрыва: острые клинья от ядра, белое ядро в розовой кайме; сначала растут, потом тают. */
    private static void rays(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Ray r : c.rays) {
            float age = clientTicks - r.born() + partial;
            double grow = 1.0D - Math.pow(1.0D - Mth.clamp(age / 2.0D, 0.0D, 1.0D), 3.0D);
            float a = (float) PlumVfx.curve(age, 0.0, 1.0, 2.0, 1.0, 8.0, 0.0);
            // Хвост отрывается от ядра после роста: луч улетает, а не висит.
            double tail = Mth.clamp((age - 2.0D) / 6.0D, 0.0D, 1.0D) * 0.7D;
            Vec3 p0 = r.from().add(r.dir().scale(r.len() * tail));
            Vec3 p2 = r.from().add(r.dir().scale(r.len() * grow));
            Vec3[] p = {p0, p0.lerp(p2, 0.3D), p2};
            double[] w = {r.width(), r.width() * 0.7D, 0.0D};
            if (r.width() > 0.4D) {
                // Рваный шлейф: ширина гуляет вдоль, край неровный (ref3 — «рваные массы»).
                double jag = (r.dir().x * 37.0D + r.dir().z * 17.0D) % 1.0D;
                Vec3[] q = {p0, p0.lerp(p2, 0.25D), p0.lerp(p2, 0.5D), p0.lerp(p2, 0.75D), p2};
                double[] qw = {r.width() * 0.5D, r.width() * (0.8D + 0.3D * jag), r.width() * (0.95D - 0.3D * jag),
                        r.width() * (0.55D + 0.25D * jag), 0.0D};
                float ma = (float) PlumVfx.curve(age, 0.0, 0.6, 1.5, 1.0, 9.0, 0.0);
                // Не гладкий веер: три узкие пряди со смещением поперёк — рваный поток.
                Vec3 across = r.dir().cross(new Vec3(0.0D, 1.0D, 0.0D));
                across = across.lengthSqr() < 1.0E-6D ? c.side : across.normalize();
                for (int k = -1; k <= 1; k++) {
                    double off = k * r.width() * 0.45D;
                    double kj = 0.75D + 0.5D * ((jag * (k + 2) * 3.7D) % 1.0D);
                    Vec3[] qk = new Vec3[q.length];
                    for (int j = 0; j < q.length; j++) {
                        qk[j] = q[j].add(across.scale(off * j / (q.length - 1.0D)));
                    }
                    fstrip(v, pose, camera, qk, PlumVfx.scale(qw, 0.38D * kj), 0.32F * ma, HOT);
                    fstrip(v, pose, camera, qk, PlumVfx.scale(qw, 0.15D * kj), 0.4F * ma, BLOOM);
                }
                continue;
            }
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.6D), 0.25F * a, r.white() ? BLOOM : HOT);
            fstrip(v, pose, camera, p, w, 0.95F * a, WHITE);
        }
        // Ядро удара: звезда из коротких белых клиньев (ref3 — острые розовые выступы у ядра).
        if (c.blastTick >= 0) {
            float age = clientTicks - c.blastTick + partial;
            if (age < 5.0F) {
                float fa = (float) PlumVfx.curve(age, 0.0, 1.0, 1.5, 1.0, 5.0, 0.0);
                Vec3 up = c.side.cross(c.axis).normalize();
                for (int i = 0; i < 12; i++) {
                    double ang = Math.PI * 2.0D * i / 12.0D + 0.2D;
                    Vec3 d = c.side.scale(Math.cos(ang)).add(up.scale(Math.sin(ang))).add(c.axis.scale(-0.3D)).normalize();
                    double len = (i % 2 == 0 ? 1.0D : 0.55D) * (0.6D + 0.6D * Math.min(1.0D, age / 1.5D));
                    Vec3[] q = {c.strike, c.strike.add(d.scale(len * 0.5D)), c.strike.add(d.scale(len))};
                    fstrip(v, pose, camera, q, new double[] {0.12D, 0.07D, 0.0D}, 0.9F * fa, i % 2 == 0 ? WHITE : HOT);
                }
            }
        }
    }

    /** Ленты ветра и световые штрихи-обломки: полоса по хвосту частицы, острая на концах. */
    private static void ribbons(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Mote m : c.motes) {
            if (m.kind == Mote.PETAL || m.count < 2 || m.delay > 0) {
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
            float a = (float) PlumVfx.curve(life, 0.0, 0.3, 0.12, 1.0, 0.6, 0.8, 1.0, 0.0);
            if (m.kind == Mote.WIND_RIBBON) {
                fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.0D), 0.1F * a, COLD);
                fstrip(v, pose, camera, p, w, 0.38F * a, WIND);
                fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.25D), 0.85F * a, EDGE);
            } else if (m.cell == 1) {
                // След быстрого лепестка — тонкая розовая черта.
                fstrip(v, pose, camera, p, w, 0.7F * a, HOT);
            } else {
                fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.2D), 0.22F * a, ExplosionRules.petals(c.layer) ? HOT : COLD);
                fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.45D), 0.95F * a, WHITE);
            }
        }
    }

    /** Попадание: перекрестье белых черт через корпус цели. */
    private static void cuts(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Object[] x : c.cuts) {
            Vec3 at = (Vec3) x[0];
            float age = clientTicks - (int) x[1] + partial;
            boolean grind = (boolean) x[2];
            float a = (float) PlumVfx.curve(age, 0.0, 1.0, 2.0, 1.0, 7.0, 0.0);
            int k = grind ? 2 : 5;
            for (int i = 0; i < k; i++) {
                double ang = Math.toRadians(-60.0D + 120.0D * i / Math.max(1, k - 1) + (int) x[1] * 13.0D);
                Vec3 d = c.side.scale(Math.cos(ang)).add(0.0D, Math.sin(ang), 0.0D);
                double len = (grind ? 0.8D : 1.6D) * (0.6D + 0.4D * Math.min(1.0D, age / 2.0D));
                Vec3[] p = {at.subtract(d.scale(len)), at, at.add(d.scale(len))};
                double[] w = {0.0D, grind ? 0.06D : 0.12D, 0.0D};
                fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.5D), 0.25F * a, HOT);
                fstrip(v, pose, camera, p, w, 0.95F * a, WHITE);
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

    /** Лепестки (атлас) и их свечение; ядро и слитная масса взрыва. */
    private static void petals(Cast c, PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float partial, float t) {
        if (!ExplosionRules.petals(c.layer)) {
            return;
        }
        RenderType pt = MurimRenderTypes.plumPetals();
        VertexConsumer pc = buffers.getBuffer(pt);
        for (Mote m : c.motes) {
            if (m.kind != Mote.PETAL || m.delay > 0) {
                continue;
            }
            Vec3 at = m.prev.lerp(m.pos, partial);
            float a = Mth.clamp((m.life - m.age - partial) / 10.0F, 0.0F, 1.0F) * near(at, camera);
            float[] tint = tint(m.tone);
            PlumVfx.petal(pc, pose, camera, at, m.size * 1.6D, m.cell, (m.age + partial) * m.spin, a, tint[0], tint[1], tint[2]);
        }
        buffers.endBatch(pt);
        RenderType gt = MurimRenderTypes.mote();
        VertexConsumer g = buffers.getBuffer(gt);
        for (Mote m : c.motes) {
            if (m.kind != Mote.PETAL || m.delay > 0) {
                continue;
            }
            Vec3 at = m.prev.lerp(m.pos, partial);
            float a = Mth.clamp((m.life - m.age - partial) / 10.0F, 0.0F, 1.0F) * near(at, camera);
            PlumVfx.glow(g, pose, camera, at, m.size * 2.0D, 0.32F * a, PINK);
        }
        {
            // Ядро взрыва — мягкое бело-розовое свечение на 4 тика.
            if (c.blastTick >= 0) {
                float age = clientTicks - c.blastTick + partial;
                if (age < 5.0F) {
                    // Пик белого ядра — 1 тик, спад — 3–4 тика (codex 03.10).
                    float fa = (float) PlumVfx.curve(age, 0.0, 1.0, 1.0, 0.9, 4.5, 0.0);
                    PlumVfx.glow(g, pose, camera, c.strike, 1.6D + 1.4D * Math.min(1.0D, age / 1.5D), 0.6F * fa, BLOOM);
                    PlumVfx.glow(g, pose, camera, c.strike, 0.9D, 0.8F * fa, WHITE);
                }
                // Слитный цветной выброс (ref3, codex 03.10): бело-розовая масса растёт из ядра по
                // оси на цель за 3 тика и тает к 8-му — одна вспышка, разрываемая лепестками.
                if (age < 8.0F) {
                    double grow = 1.0D - Math.pow(1.0D - Mth.clamp(age / 3.0D, 0.0D, 1.0D), 2.0D);
                    float ma = (float) PlumVfx.curve(age, 0.0, 0.7, 1.5, 1.0, 8.0, 0.0);
                    double reach = 5.0D * (0.6D + 0.4D * c.density);
                    for (int i = 0; i < 6; i++) {
                        double u = (i + 0.5D) / 6.0D;
                        Vec3 at = c.strike.add(c.axis.scale(reach * u * grow));
                        double size = (1.1D + 2.4D * u) * (0.4D + 0.6D * grow);
                        float aa = ma * (float) (1.0D - 0.55D * u) * near(at, camera);
                        PlumVfx.glow(g, pose, camera, at, size, 0.42F * aa, HOT);
                        PlumVfx.glow(g, pose, camera, at, size * 0.55D, 0.5F * aa, BLOOM);
                        if (i < 2) {
                            PlumVfx.glow(g, pose, camera, at, size * 0.3D, 0.6F * aa, WHITE);
                        }
                    }
                }
            }
        }
        buffers.endBatch(gt);
    }

    private static float[] tint(int tone) {
        return switch (tone) {
            case 0 -> new float[] {1.0F, 0.93F, 0.95F};
            case 2 -> new float[] {0.97F, 0.36F, 0.53F};
            default -> new float[] {1.0F, 0.72F, 0.8F};
        };
    }

    private ExplosionVfx() {
    }
}
