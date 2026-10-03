package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.CameraShakeHandler;
import io.github.verycooltimo.murim.client.ClientAuraState;
import io.github.verycooltimo.murim.network.SeaHoldPayload;
import io.github.verycooltimo.murim.network.SeaPayload;
import io.github.verycooltimo.murim.network.TechniqueEventPayload;
import io.github.verycooltimo.murim.technique.SeaRules;
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
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import static io.github.verycooltimo.murim.client.vfx.PlumVfx.EDGE;

/**
 * Море Цветущей Сливы (Меч 24 Движений, секретная форма; рефов нет — источник гл. 195, метод
 * docs/design/research/technique-design-grammar.md; шкала — {@link SeaRules}). Один материал — цветы
 * сливы из пяти лепестков-частиц: сходят с клинка и плывут низко у земли (тяга к своему месту,
 * затухание, волна ветра), при захвате срываются и обвивают чужой снаряд спиралью вдоль древка,
 * после посадки оседают, в конце тают, как снег. Линий-ветра, дыма и импакт-кадра нет (бриф 03.10).
 * Захват решает сервер; клиент рисует по пакетам.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class SeaVfx {

    private static final ResourceLocation TECHNIQUE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "twenty_four_plum_sea");
    private static final ResourceLocation END_ANIMATION = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "twenty_four_plum_sea_end");
    /** Палитра (спец.): бутон #E63D70, цветы #F19BC5, белая кромка #FAFFFF, тень лепестка #AD305A. */
    // Атлас лепестков уже розовый — тон задаётся множителем к нему, как в RainVfx (codex r1: иначе фуксия).
    private static final float[] BUD = {1.0F, 0.42F, 0.58F};
    private static final float[] FLOWER = {1.0F, 0.86F, 0.92F};
    private static final float[] SHADE = {0.86F, 0.56F, 0.68F};
    private static final float[] WHITE = {1.0F, 0.98F, 1.0F};
    /** Головной цветок обвивки — чуть глубже основного тона, не малиновый (codex r2 №6). */
    private static final float[] HEAD = {1.0F, 0.74F, 0.84F};
    private static final float[] BLOOD_TINT = {0.75F, 0.08F, 0.16F};
    private static final VfxColour GLOW_PINK = new VfxColour(0xF1 / 255.0F, 0x9B / 255.0F, 0xC5 / 255.0F);
    private static final VfxColour GLOW_WHITE = new VfxColour(0xFA / 255.0F, 0xFF / 255.0F, 1.0F);
    private static final VfxColour GLOW_BUD = new VfxColour(0xE6 / 255.0F, 0x3D / 255.0F, 0x70 / 255.0F);
    private static final VfxColour STROKE = new VfxColour(0xE8 / 255.0F, 0xED / 255.0F, 0xF1 / 255.0F);

    private static final List<Cast> CASTS = new ArrayList<>();
    private static int clientTicks;
    /** Последнее отправленное состояние R (своё Море). */
    private static boolean sentHeld;

    private static float[] rgb(int c) {
        return new float[] {((c >> 16) & 0xFF) / 255.0F, ((c >> 8) & 0xFF) / 255.0F, (c & 0xFF) / 255.0F};
    }

    /** Вблизи камеры тает: от первого лица поле не закрывает экран. */
    private static float near(Vec3 at, Vec3 camera) {
        return (float) Mth.clamp((at.distanceTo(camera) - 0.9D) / 1.4D, 0.0D, 1.0D);
    }

    // ------------------------------------------------------------------ частицы

    /** Цветок поля: место в секторе (полярно от оси), позиция и скорость; 5 лепестков вокруг центра. */
    private static final class Flower {
        final double r;
        final double a;
        final double h;
        final double size;
        final int cell;
        final double phase;
        final float tilt;
        int born;
        Vec3 pos;
        Vec3 prev;
        Vec3 vel = Vec3.ZERO;
        boolean placed;
        /** Сорван в обвивку: не рисуется, вернётся (распустится на месте) с этого тика. */
        int tornUntil = -1;
        float bloom;
        /** Мираж: с этого тика тает. */
        int melt = -1;

        Flower(double r, double a, double h, double size, int cell, double phase, float tilt) {
            this.r = r;
            this.a = a;
            this.h = h;
            this.size = size;
            this.cell = cell;
            this.phase = phase;
            this.tilt = tilt;
        }
    }

    /** Свободный лепесток: след сорванных цветов, оседание обвивки, капля крови, пыль поля. */
    private static final class Mote {
        Vec3 pos;
        Vec3 prev;
        Vec3 vel;
        int age;
        final int life;
        final int cell;
        final float spin;
        final double size;
        double drag = 0.9D;
        double gravity = 0.004D;
        double turbulence = 0.004D;
        float[] tint = FLOWER;
        boolean glow;
        /** Не таять у камеры (капля внизу кадра от первого лица). */
        boolean screen;
        /** Сесть на землю и лежать. */
        double floor = Double.NaN;

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

    /** Лепесток обвивки: место на спирали вдоль древка (s — вдоль, phi — угол, rho — радиус). */
    private static final class WrapPetal {
        final double s;
        final double phi;
        final double rho;
        final double size;
        final int cell;
        final float spin;
        final int arrive;
        final boolean head;
        Vec3 from;
        Vec3 pos;
        Vec3 prev;

        WrapPetal(double s, double phi, double rho, double size, int cell, float spin, int arrive, boolean head, Vec3 from) {
            this.s = s;
            this.phi = phi;
            this.rho = rho;
            this.size = size;
            this.cell = cell;
            this.spin = spin;
            this.arrive = arrive;
            this.head = head;
            this.from = from;
            this.pos = from;
            this.prev = from;
        }
    }

    /** Обвивка вокруг одного захваченного снаряда. */
    private static final class Wrap {
        final int projectile;
        final int born;
        final Vec3 entry;
        Vec3 at;
        Vec3 prevAt;
        Vec3 dir;
        double length;
        int landed = -1;
        boolean snuffed;
        final List<WrapPetal> petals = new ArrayList<>();

        Wrap(int projectile, int born, Vec3 entry, Vec3 dir, double length) {
            this.projectile = projectile;
            this.born = born;
            this.entry = entry;
            this.at = entry;
            this.prevAt = entry;
            this.dir = dir;
            this.length = length;
        }
    }

    /** Белый штрих клинка у пойманного снаряда (слои 1–2): две короткие чёткие черты крестом. */
    private record Stroke(Vec3 at, Vec3 a, Vec3 b, int born) {
    }

    /** Остаточный образ руки с мечом на захвате (слой 2+). */
    private record Ghost(Vec3 pos, float yaw, int born, PartPose[] pose) {
    }

    private record Puff(Vec3 pos, Vec3 vel, int born, int life, int cell, double size) {
    }

    private static final class Cast {
        final int entityId;
        final int layer;
        int start;
        final Random random;
        final double density;
        final double scale;
        Vec3 axis;
        Vec3 prevAxis;
        int meltTick = -1;
        int lostTick = -1;
        boolean began;
        boolean caption;
        int bleedTick = -1;
        final List<Flower> flowers = new ArrayList<>();
        final List<Mote> motes = new ArrayList<>();
        final List<Wrap> wraps = new ArrayList<>();
        final List<Stroke> strokes = new ArrayList<>();
        final List<Ghost> ghosts = new ArrayList<>();
        final List<Puff> puffs = new ArrayList<>();
        ResourceLocation skin;

        Cast(int entityId, int layer) {
            this.entityId = entityId;
            this.layer = layer;
            this.start = clientTicks;
            this.random = new Random(entityId * 31L + clientTicks);
            this.density = SeaRules.density(layer);
            this.scale = SeaRules.scale(layer);
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

        boolean eyes() {
            Minecraft mc = Minecraft.getInstance();
            return own() && mc.options.getCameraType().isFirstPerson() && mc.getCameraEntity() == mc.player;
        }

        int activeWraps() {
            int k = 0;
            for (Wrap w : wraps) {
                if (w.landed < 0 && !w.snuffed) {
                    k++;
                }
            }
            return k;
        }
    }

    // ------------------------------------------------------------------ события

    public static void onTechniqueEvent(TechniqueEventPayload payload) {
        if (!TECHNIQUE.equals(payload.techniqueId())) {
            return;
        }
        if (payload.event() == TechniqueEventPayload.Event.CANCELLED) {
            for (Cast c : CASTS) {
                if (c.entityId == payload.sourceId() && c.lostTick < 0) {
                    lost(c);
                }
            }
            return;
        }
        if (payload.event() != TechniqueEventPayload.Event.STARTED || payload.layer() <= 0) {
            return;
        }
        CASTS.removeIf(c -> c.entityId == payload.sourceId());
        Cast c = new Cast(payload.sourceId(), payload.layer());
        CASTS.add(c);
        if (c.own()) {
            sentHeld = false;
        }
        if (SeaRules.petals(c.layer)) {
            // Стойка и бутон — холодная синяя аура; розовая — с раскрытия.
            ClientAuraState.techniqueAura(c.entityId, 2, 0, SeaRules.RELEASE + 4);
        }
    }

    public static void onSea(SeaPayload p) {
        Cast c = null;
        for (Cast x : CASTS) {
            if (x.entityId == p.entityId()) {
                c = x;
            }
        }
        if (p.stage() == SeaPayload.BEGIN) {
            if (c == null) {
                c = new Cast(p.entityId(), p.layer());
                CASTS.add(c);
            }
            // Сверка шкалы: пакет раскрытия приходит на тике RELEASE.
            c.start = clientTicks - SeaRules.RELEASE;
            c.axis = p.b().normalize();
            c.prevAxis = c.axis;
            c.began = true;
            begin(c, p.a());
            return;
        }
        if (c == null) {
            return;
        }
        switch (p.stage()) {
            case SeaPayload.CAPTURE -> capture(c, p.a(), p.b(), p.value());
            case SeaPayload.LAND, SeaPayload.SNUFF -> land(c, p.value(), p.a(), p.stage() == SeaPayload.SNUFF);
            case SeaPayload.BLEED -> bleed(c, p.a());
            case SeaPayload.MELT -> melt(c);
            case SeaPayload.LOST -> lost(c);
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ фазы

    /** Раскрытие: пыль кольцом, розовая аура, поле цветов (родятся по ходу клинка и кольцами от острия). */
    private static void begin(Cast c, Vec3 feet) {
        dust(c, feet, c.n(12) + 4, 0.16D);
        if (SeaRules.petals(c.layer)) {
            ClientAuraState.techniqueAura(c.entityId, 2, 1, SeaRules.END - SeaRules.RELEASE);
            buildField(c);
        }
        Sfx(c, feet, io.github.verycooltimo.murim.registry.ModSounds.SWORD_SWING.get(), 0.6F, 0.8F);
    }

    /** Поле: до 240 цветов (7-й слой) в секторе, площадь равномерно, у краёв реже. */
    private static void buildField(Cast c) {
        int n = Math.min(220, Math.max(20, c.n(170)));
        double radius = SeaRules.radius(c.layer);
        for (int i = 0; i < n; i++) {
            double r = 0.6D + (radius - 0.6D) * Math.sqrt(c.random.nextDouble());
            double a = SeaRules.SECTOR * (2.0D * c.random.nextDouble() - 1.0D);
            // Край сектора и дальний край редеют на последних 0,5 блока/8° — дуга видна, не линейка.
            if ((Math.abs(a) > SeaRules.SECTOR - 8.0D || r > radius - 0.5D) && c.random.nextBoolean()) {
                continue;
            }
            // Край сектора реже и ниже: граница видна, но не линейкой.
            double h = 0.12D + 0.3D * Math.pow(c.random.nextDouble(), 1.6D);
            Flower f = new Flower(r, a, h, (0.14D + 0.06D * c.random.nextDouble()) * c.scale, c.random.nextInt(4),
                    c.random.nextDouble() * Math.PI * 2.0D, (float) (c.random.nextDouble() * 6.28D));
            // Родится, когда клинок прошёл свой угол (14 → 24) и прилив дошёл до радиуса.
            double sweep = SeaRules.RELEASE + (SeaRules.SWEEP_END - SeaRules.RELEASE) * (a + SeaRules.SECTOR) / (2.0D * SeaRules.SECTOR);
            double tide = tideTime(r / radius);
            f.born = c.start + (int) Math.round(Math.max(sweep, tide)) + c.random.nextInt(3);
            c.flowers.add(f);
        }
    }

    /** Тик, к которому прилив дошёл до доли радиуса k (обращение {@link SeaRules#grown}). */
    private static double tideTime(double k) {
        for (int t = SeaRules.RELEASE; t <= SeaRules.TIDE_END; t++) {
            if (SeaRules.grown(t) >= k) {
                return t;
            }
        }
        return SeaRules.TIDE_END;
    }

    /** Захват: цветы срываются с поля и обвивают снаряд спиралью вдоль древка; штрих клинка, образ руки. */
    private static void capture(Cast c, Vec3 at, Vec3 vel, int projectile) {
        Minecraft mc = Minecraft.getInstance();
        Entity e = mc.level == null ? null : mc.level.getEntity(projectile);
        Vec3 dir = vel.lengthSqr() > 1.0E-6D ? vel.normalize() : new Vec3(0.0D, 0.0D, 1.0D);
        double length = e == null ? 0.5D : Mth.clamp(Math.max(e.getBbWidth(), e.getBbHeight()) * 1.6D, 0.35D, 1.0D);
        if (e instanceof net.minecraft.world.entity.projectile.AbstractArrow) {
            length = 0.75D;
        }
        Wrap w = new Wrap(projectile, clientTicks, at, dir, length);
        c.wraps.add(w);
        if (SeaRules.petals(c.layer)) {
            // Сорванные цветы — ближайшие к пути снаряда; от них и летят лепестки обвивки.
            List<Flower> near = new ArrayList<>();
            Entity caster = mc.level == null ? null : mc.level.getEntity(c.entityId);
            for (Flower f : c.flowers) {
                if (f.placed && f.tornUntil < clientTicks && f.melt < 0 && f.bloom > 0.5F) {
                    near.add(f);
                }
            }
            near.sort(java.util.Comparator.comparingDouble(f -> f.pos.distanceToSqr(at)));
            int torn = Math.min(near.size(), 4);
            int petals = Math.min(14, 9 + (int) Math.round(5 * Math.min(1.0D, c.density)));
            boolean high = caster != null && at.y > caster.getY() + 2.6D;
            for (int i = 0; i < petals; i++) {
                Vec3 from;
                if (torn > 0) {
                    Flower f = near.get(i % torn);
                    from = f.pos;
                } else {
                    from = at.add(c.random.nextGaussian() * 0.6D, -0.8D, c.random.nextGaussian() * 0.6D);
                }
                if (high && SeaRules.slotBuds(c.layer) && i % 2 == 0) {
                    // Высокий снаряд: лепестки поднимаются к нему столбом из поля под ним.
                    from = new Vec3(at.x + c.random.nextGaussian() * 0.3D, caster.getY() + 0.5D, at.z + c.random.nextGaussian() * 0.3D);
                }
                boolean head = i < 5;
                // Спираль вдоль древка: от хвоста к наконечнику, неравный шаг, просветы между витками.
                double s = head ? 0.22D + 0.04D * c.random.nextDouble() : -0.45D + 0.6D * (i - 5) / Math.max(1.0D, petals - 6.0D);
                double phi = head ? i * Math.PI * 2.0D / 5.0D + 0.3D : (i - 5) * 2.15D + c.random.nextDouble() * 0.4D;
                double rho = head ? 0.09D : 0.09D + 0.05D * c.random.nextDouble();
                w.petals.add(new WrapPetal(s, phi, rho, (head ? 0.06D : 0.04D + 0.016D * c.random.nextDouble()) * (0.85D + 0.15D * c.scale),
                        c.random.nextInt(4), (float) ((c.random.nextDouble() - 0.5D) * 0.5D), clientTicks + 1 + c.random.nextInt(3), head, from));
            }
            for (int i = 0; i < torn; i++) {
                near.get(i).tornUntil = clientTicks + 24;
                near.get(i).bloom = 0.0F;
            }
            // Волна торможения (4+): поклон цветов от точки захвата по полю.
            if (SeaRules.bowWave(c.layer)) {
                Vec3 ground = caster != null ? new Vec3(at.x, caster.getY(), at.z) : at;
                for (Flower f : c.flowers) {
                    if (!f.placed) {
                        continue;
                    }
                    Vec3 d = new Vec3(f.pos.x - ground.x, 0.0D, f.pos.z - ground.z);
                    double dist = d.length();
                    if (dist < 3.2D && dist > 1.0E-3D) {
                        double k = (1.0D - dist / 3.2D);
                        f.vel = f.vel.add(d.normalize().scale(0.16D * k)).add(0.0D, -0.08D * k, 0.0D);
                    }
                }
            }
        }
        if (c.layer >= 1 && !SeaRules.petals(c.layer)) {
            Vec3 up = dir.cross(new Vec3(0.0D, 1.0D, 0.0D));
            up = up.lengthSqr() < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D) : up.normalize();
            Vec3 v2 = up.cross(dir).normalize();
            c.strokes.add(new Stroke(at, up.scale(0.55D).add(v2.scale(0.25D)), v2.scale(0.45D).subtract(up.scale(0.2D)), clientTicks));
        }
        if (SeaRules.afterimages(c.layer) && mc.level != null && mc.level.getEntity(c.entityId) instanceof AbstractClientPlayer player
                && mc.getEntityRenderDispatcher().getRenderer(player) instanceof PlayerRenderer renderer) {
            if (c.skin == null) {
                c.skin = player.getSkin().texture();
            }
            c.ghosts.add(new Ghost(player.position(), player.yBodyRot, clientTicks, capturePose(renderer.getModel())));
        }
        float vol = 0.75F / (1.0F + 0.6F * Math.max(0, c.activeWraps() - 1));
        Sfx(c, at, io.github.verycooltimo.murim.registry.ModSounds.BARRIER_HIT.get(), vol, 0.62F + 0.06F * c.random.nextFloat());
        if (c.own()) {
            CameraShakeHandler.quake(0.12F, 4);
        }
    }

    /** Снаряд лёг (или исчез): обвивка раскрывается и оседает лепестками; огненный шар гаснет. */
    private static void land(Cast c, int projectile, Vec3 at, boolean snuff) {
        for (Wrap w : c.wraps) {
            if (w.projectile != projectile || w.landed >= 0) {
                continue;
            }
            w.landed = clientTicks;
            w.snuffed = snuff;
            Vec3 where = at.lengthSqr() > 1.0E-6D ? at : w.at;
            Minecraft mc = Minecraft.getInstance();
            Entity caster = mc.level == null ? null : mc.level.getEntity(c.entityId);
            double floor = groundY(where, caster);
            for (WrapPetal p : w.petals) {
                Vec3 out = p.pos.subtract(where);
                out = out.lengthSqr() < 1.0E-6D ? new Vec3(c.random.nextGaussian(), 0.3D, c.random.nextGaussian()) : out;
                Mote m = new Mote(p.pos, out.normalize().scale(0.05D + 0.04D * c.random.nextDouble()).add(0.0D, 0.03D, 0.0D),
                        20 + c.random.nextInt(10), p.cell, p.spin, p.size);
                m.drag = 0.9D;
                m.gravity = 0.006D;
                m.turbulence = 0.006D;
                m.tint = c.random.nextInt(3) == 0 ? WHITE : FLOWER;
                m.floor = floor + 0.03D;
                c.motes.add(m);
            }
            w.petals.clear();
            if (snuff) {
                Sfx(c, where, net.minecraft.sounds.SoundEvents.FIRE_EXTINGUISH, 0.5F, 1.4F);
            }
        }
    }

    /** Платёж продления: капля крови у губ (и внизу кадра от первого лица), глухой удар сердца. */
    private static void bleed(Cast c, Vec3 eye) {
        c.bleedTick = clientTicks;
        Minecraft mc = Minecraft.getInstance();
        Entity e = mc.level == null ? null : mc.level.getEntity(c.entityId);
        if (e == null) {
            return;
        }
        Vec3 f = Vec3.directionFromRotation(0.0F, e.getYRot());
        if (c.eyes()) {
            Vec3 look = e.getLookAngle();
            Vec3 drop = e.getEyePosition().add(look.scale(0.55D)).add(0.0D, -0.3D, 0.0D);
            for (int i = 0; i < 3; i++) {
                Mote m = new Mote(drop.add(c.random.nextGaussian() * 0.02D, -0.03D * i, 0.0D), new Vec3(0.0D, -0.012D, 0.0D), 26, 0, 0.0F, 0.022D - 0.004D * i);
                m.tint = BLOOD_TINT;
                m.drag = 1.0D;
                m.gravity = 0.0015D;
                m.turbulence = 0.0D;
                m.screen = true;
                c.motes.add(m);
            }
        } else {
            Vec3 mouth = e.getEyePosition().add(f.scale(0.24D)).add(0.0D, -0.16D, 0.0D);
            for (int i = 0; i < 3; i++) {
                Mote m = new Mote(mouth.add(0.0D, -0.05D * i, 0.0D), new Vec3(f.x * 0.01D, -0.01D, f.z * 0.01D), 26, 0, 0.0F, 0.05D);
                m.tint = BLOOD_TINT;
                m.drag = 0.98D;
                m.gravity = 0.004D;
                m.turbulence = 0.0D;
                c.motes.add(m);
            }
        }
        Sfx(c, e.position(), io.github.verycooltimo.murim.registry.ModSounds.IMPACT_HEAVY.get(), 0.32F, 0.5F);
        if (c.own()) {
            CameraShakeHandler.quake(0.18F, 5);
        }
    }

    /** Тихий мираж: цветы отрываются, поднимаются, белеют, сжимаются и гаснут; мастер опускает меч. */
    private static void melt(Cast c) {
        c.meltTick = clientTicks;
        for (Flower f : c.flowers) {
            f.melt = clientTicks + c.random.nextInt(14);
            f.vel = f.vel.add(c.random.nextGaussian() * 0.01D, 0.008D + 0.012D * c.random.nextDouble(), c.random.nextGaussian() * 0.01D);
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getEntity(c.entityId) instanceof AbstractClientPlayer player) {
            io.github.verycooltimo.murim.client.MurimPlayerAnimations.play(player, END_ANIMATION);
            dust(c, player.position(), c.n(6) + 2, 0.08D);
        }
    }

    /** Прервано: поле рассыпается, обвивки отпускают. */
    private static void lost(Cast c) {
        c.lostTick = clientTicks;
        if (c.meltTick < 0) {
            melt(c);
        }
        for (Wrap w : c.wraps) {
            if (w.landed < 0) {
                land(c, w.projectile, w.at, false);
            }
        }
    }

    private static double groundY(Vec3 at, Entity caster) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            BlockPos p = BlockPos.containing(at);
            for (int i = 0; i < 24; i++) {
                if (!mc.level.getBlockState(p.below()).getCollisionShape(mc.level, p.below()).isEmpty()) {
                    return p.getY();
                }
                p = p.below();
            }
        }
        return caster != null ? caster.getY() : at.y - 1.0D;
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
                    clientTicks, 16 + c.random.nextInt(8), c.random.nextInt(16), 0.2D + 0.16D * c.random.nextDouble()));
        }
    }

    private static void Sfx(Cast c, Vec3 at, net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        if (Minecraft.getInstance().player != null) {
            io.github.verycooltimo.murim.client.Sfx.play(at.x, at.y, at.z, sound, net.minecraft.sounds.SoundSource.PLAYERS, volume, pitch, false);
        }
    }

    // ------------------------------------------------------------------ тело и поле

    private static Vec3 flat(Vec3 v) {
        Vec3 f = new Vec3(v.x, 0.0D, v.z);
        return f.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : f.normalize();
    }

    /** Остриё меча: стойка вперёд-вниз; от первого лица — перед глазами справа внизу. */
    private static Vec3 tip(Cast c, Entity e, float partial) {
        if (c.eyes()) {
            Vec3 look = e.getViewVector(partial);
            Vec3 right = flat(look).cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
            return e.getEyePosition(partial).add(look.scale(1.25D)).add(right.scale(0.32D)).add(0.0D, -0.45D, 0.0D);
        }
        float yaw = e instanceof LivingEntity le ? Mth.rotLerp(partial, le.yBodyRotO, le.yBodyRot) : e.getYRot();
        Vec3 f = flat(Vec3.directionFromRotation(0.0F, yaw));
        Vec3 right = f.cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
        return e.getPosition(partial).add(0.0D, 0.85D, 0.0D).add(f.scale(1.55D)).add(right.scale(0.2D));
    }

    /** Место цветка в мире: полярно от оси сектора (горизонтальная проекция), высота с волной. */
    private static Vec3 home(Cast c, Flower f, Vec3 feet, Vec3 axis, int t) {
        Vec3 fwd = flat(axis);
        double ang = Math.toRadians(f.a);
        Vec3 d = fwd.scale(Math.cos(ang)).add(fwd.cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize().scale(Math.sin(ang)));
        // Волна-поклон бежит от мастера наружу: живое море, не стоячий ковёр.
        double wave = 0.05D * Math.sin(f.phase + t * 0.16D - f.r * 0.9D);
        return feet.add(d.scale(f.r)).add(0.0D, f.h * c.scale + wave, 0.0D);
    }

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
            if (e != null) {
                body(c, e, t);
                field(c, e, t);
                wraps(c, mc);
                holdKey(c, t);
            }
            tickMotes(c);
            c.strokes.removeIf(s -> clientTicks - s.born() > 5);
            c.ghosts.removeIf(g -> clientTicks - g.born() > 8);
            c.puffs.removeIf(p -> clientTicks - p.born() > p.life());
            boolean done = c.meltTick >= 0 && clientTicks - c.meltTick > SeaRules.MELT + 10 && c.motes.isEmpty();
            if (done || t > SeaRules.END + 80 || e == null && t > 40) {
                it.remove();
            }
        }
    }

    /** R своего Моря: сообщить серверу при смене (продление — его решение). */
    private static void holdKey(Cast c, int t) {
        if (!c.own() || c.meltTick >= 0 || t < SeaRules.RELEASE) {
            return;
        }
        boolean down = io.github.verycooltimo.murim.client.ModKeyMappings.TECHNIQUE.isDown();
        if (down != sentHeld) {
            sentHeld = down;
            PacketDistributor.sendToServer(new SeaHoldPayload(down));
        }
    }

    /** Стойка, бутон на острие, проход клинка: пыль, лепестки с острия. */
    private static void body(Cast c, Entity e, int t) {
        Vec3 feet = e.position();
        if (t == 1 || t == 6) {
            dust(c, feet, c.n(6) + 2, 0.09D);
        }
        if (!SeaRules.petals(c.layer)) {
            return;
        }
        if (t == SeaRules.BUD) {
            Sfx(c, tip(c, e, 1.0F), io.github.verycooltimo.murim.registry.ModSounds.BLOSSOM_OPEN.get(), 0.6F, 1.1F);
        }
        // Бутон раскрылся — с острия срываются первые лепестки и плывут вперёд.
        if (t > SeaRules.BUD + SeaRules.BUD_OPEN && t < SeaRules.RELEASE && t % 2 == 0) {
            Vec3 tp = tip(c, e, 1.0F);
            Vec3 f = flat(e.getLookAngle());
            Mote m = new Mote(tp, f.scale(0.05D).add(0.0D, 0.01D, 0.0D), 30, c.random.nextInt(4), 0.3F, 0.07D);
            m.tint = c.random.nextBoolean() ? BUD : FLOWER;
            m.drag = 0.97D;
            m.gravity = 0.001D;
            c.motes.add(m);
        }
        // Проход клинка 14 → 24: с острия сходит поток лепестков вперёд по сектору.
        if (t >= SeaRules.RELEASE && t < SeaRules.SWEEP_END && c.axis != null) {
            Vec3 tp = tip(c, e, 1.0F);
            double k = (t - SeaRules.RELEASE) / (double) (SeaRules.SWEEP_END - SeaRules.RELEASE);
            double ang = Math.toRadians(-SeaRules.SECTOR + 2.0D * SeaRules.SECTOR * k);
            Vec3 fwd = flat(c.axis);
            Vec3 d = fwd.scale(Math.cos(ang)).add(fwd.cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize().scale(Math.sin(ang)));
            for (int i = 0; i < Math.max(2, c.n(6)); i++) {
                Mote m = new Mote(tp.add(c.random.nextGaussian() * 0.1D, c.random.nextGaussian() * 0.08D, c.random.nextGaussian() * 0.1D),
                        d.scale(0.12D + 0.12D * c.random.nextDouble()).add(0.0D, -0.01D, 0.0D), 22 + c.random.nextInt(10),
                        c.random.nextInt(4), (float) ((c.random.nextDouble() - 0.5D) * 0.5D), 0.06D + 0.03D * c.random.nextDouble());
                m.drag = 0.93D;
                m.tint = c.random.nextInt(5) == 0 ? WHITE : FLOWER;
                c.motes.add(m);
            }
            if (t % 3 == 0) {
                dust(c, feet.add(d.scale(0.8D)), 2, 0.08D);
            }
        }
    }

    /** Поле: ось к взгляду (≤ 4°/тик, как на сервере), цветы тянутся к своим местам, волна, мираж. */
    private static void field(Cast c, Entity e, int t) {
        if (c.axis != null) {
            c.prevAxis = c.axis;
            if (c.meltTick < 0) {
                c.axis = SeaRules.turn(c.axis, e.getLookAngle());
            }
        }
        if (c.flowers.isEmpty() || c.axis == null) {
            return;
        }
        Vec3 feet = e.position();
        Vec3 tp = tip(c, e, 1.0F);
        for (Flower f : c.flowers) {
            f.prev = f.pos;
            if (clientTicks < f.born) {
                continue;
            }
            if (!f.placed) {
                // Сходит с клинка и плывёт к своему месту.
                f.placed = true;
                f.pos = tp.add(c.random.nextGaussian() * 0.15D, c.random.nextGaussian() * 0.1D, c.random.nextGaussian() * 0.15D);
                f.prev = f.pos;
                f.vel = home(c, f, feet, c.axis, t).subtract(f.pos).scale(0.12D);
            }
            if (f.melt >= 0 && clientTicks >= f.melt) {
                // Тает: всплывает, кружит, сжимается.
                double ph = f.phase + clientTicks * 0.2D;
                f.vel = f.vel.scale(0.94D).add(Math.sin(ph) * 0.004D, 0.004D, Math.cos(ph * 1.3D) * 0.004D);
                f.pos = f.pos.add(f.vel);
                continue;
            }
            if (f.tornUntil >= clientTicks) {
                continue;
            }
            f.bloom = Math.min(1.0F, f.bloom + 0.2F);
            Vec3 h = home(c, f, feet, c.axis, t);
            // Пружина к месту с затуханием: при повороте сектора цветы плывут с запаздыванием.
            f.vel = f.vel.scale(0.82D).add(h.subtract(f.pos).scale(0.07D));
            double ph = f.phase + clientTicks * 0.13D;
            f.vel = f.vel.add(Math.sin(ph) * 0.003D, 0.0D, Math.cos(ph * 0.8D) * 0.003D);
            f.pos = f.pos.add(f.vel);
        }
    }

    /** Обвивки: следуют за своим снарядом, лепестки слетаются на спираль; след сорванных цветов. */
    private static void wraps(Cast c, Minecraft mc) {
        for (Wrap w : c.wraps) {
            if (w.landed >= 0) {
                continue;
            }
            Entity p = mc.level.getEntity(w.projectile);
            w.prevAt = w.at;
            if (p != null && p.isAlive()) {
                Vec3 nowAt = p.position().add(0.0D, p.getBbHeight() * 0.5D, 0.0D);
                Vec3 mv = nowAt.subtract(w.at);
                w.at = nowAt;
                if (mv.lengthSqr() > 1.0E-4D) {
                    // Обвивка держит ось древка, а не скорость падения: снаряд опускается, оставаясь стрелой.
                    Vec3 look = p.getLookAngle();
                    w.dir = look.lengthSqr() > 1.0E-6D && !(p instanceof net.minecraft.world.entity.projectile.AbstractHurtingProjectile) ? look : w.dir;
                    if (p instanceof net.minecraft.world.entity.projectile.AbstractArrow) {
                        w.dir = Vec3.directionFromRotation(p.getXRot(), p.getYRot()).scale(-1.0D);
                        w.dir = new Vec3(-w.dir.x, w.dir.y, -w.dir.z).normalize();
                    }
                }
                if (SeaRules.petals(c.layer) && clientTicks - w.born < 10) {
                    // След: сорванные цветы от точки входа к снаряду, оседают.
                    for (int i = 0; i < 2; i++) {
                        double u = c.random.nextDouble();
                        Vec3 at = w.entry.lerp(w.at, u).add(c.random.nextGaussian() * 0.05D, c.random.nextGaussian() * 0.05D, c.random.nextGaussian() * 0.05D);
                        Mote m = new Mote(at, new Vec3(0.0D, -0.01D, 0.0D), 26 + c.random.nextInt(14), c.random.nextInt(4),
                                (float) ((c.random.nextDouble() - 0.5D) * 0.5D), 0.05D + 0.03D * c.random.nextDouble());
                        m.tint = FLOWER;
                        m.gravity = 0.003D;
                        c.motes.add(m);
                    }
                }
            }
            double spin = (clientTicks - w.born) * 0.35D;
            Vec3[] basis = basis(w.dir);
            for (WrapPetal q : w.petals) {
                q.prev = q.pos;
                Vec3 target = w.at.add(w.dir.scale(q.s * w.length))
                        .add(basis[0].scale(Math.cos(q.phi + spin) * q.rho)).add(basis[1].scale(Math.sin(q.phi + spin) * q.rho));
                if (q.head) {
                    // Головной цветок смыкается вокруг наконечника, как бутон: радиус сходится.
                    double close = Mth.clamp((clientTicks - q.arrive) / 3.0D, 0.0D, 1.0D);
                    target = w.at.add(w.dir.scale(q.s * w.length + 0.06D * close))
                            .add(basis[0].scale(Math.cos(q.phi) * (0.22D - 0.12D * close)))
                            .add(basis[1].scale(Math.sin(q.phi) * (0.22D - 0.12D * close)));
                }
                if (clientTicks < q.arrive) {
                    double k = Mth.clamp(1.0D - (q.arrive - clientTicks) / 3.0D, 0.0D, 1.0D);
                    double e = k * k * (3.0D - 2.0D * k);
                    q.pos = q.from.lerp(target, e).add(0.0D, Math.sin(Math.PI * k) * 0.25D, 0.0D);
                } else {
                    q.pos = target;
                }
            }
        }
        c.wraps.removeIf(w -> w.landed >= 0 && clientTicks - w.landed > 40);
    }

    private static Vec3[] basis(Vec3 dir) {
        Vec3 ref = Math.abs(dir.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 a = dir.cross(ref).normalize();
        Vec3 b = dir.cross(a).normalize();
        return new Vec3[] {a, b};
    }

    private static void tickMotes(Cast c) {
        for (Mote m : c.motes) {
            m.prev = m.pos;
            m.age++;
            Vec3 v = m.vel.scale(m.drag).add(0.0D, -m.gravity, 0.0D);
            if (m.turbulence > 0.0D) {
                double ph = m.age * 0.21D + m.cell * 1.7D + m.pos.x * 0.5D;
                v = v.add(Math.sin(ph) * m.turbulence, Math.sin(ph * 1.3D + 1.1D) * m.turbulence * 0.4D, Math.cos(ph * 0.9D + m.pos.z * 0.5D) * m.turbulence);
            }
            Vec3 next = m.pos.add(v);
            if (!Double.isNaN(m.floor) && next.y < m.floor) {
                // Лёг на траву: лежит, медленно гаснет.
                next = new Vec3(next.x, m.floor, next.z);
                v = Vec3.ZERO;
                m.turbulence = 0.0D;
                m.gravity = 0.0D;
            }
            m.vel = v;
            m.pos = next;
        }
        c.motes.removeIf(m -> m.age >= m.life);
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
                ghosts(mc, c, ps, buffers, partial);
                PoseStack.Pose pose = ps.last();
                VertexConsumer air = buffers.getBuffer(MurimRenderTypes.airBand());
                strokes(c, pose, camera, air, partial);
                buffers.endBatch(MurimRenderTypes.airBand());
                puffs(c, pose, camera, buffers, partial);
                petals(c, mc, pose, camera, buffers, partial, t);
            }
        } finally {
            ps.popPose();
        }
    }

    /** Белые штрихи клинка у пойманного снаряда: короткие, чёткие, 4 тика. */
    private static void strokes(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Stroke s : c.strokes) {
            float age = clientTicks - s.born() + partial;
            float a = (float) PlumVfx.curve(age, 0.0, 1.0, 1.5, 0.9, 4.5, 0.0);
            if (a <= 0.0F) {
                continue;
            }
            double grow = Mth.clamp(age / 1.2D, 0.3D, 1.0D);
            for (Vec3 d : new Vec3[] {s.a(), s.b()}) {
                Vec3[] p = {s.at().subtract(d.scale(grow)), s.at(), s.at().add(d.scale(grow))};
                double[] w = {0.0D, 0.035D, 0.0D};
                PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 2.2D), 0.18F * a * near(s.at(), camera), STROKE);
                PlumVfx.strip(v, pose, camera, p, w, 0.95F * a * near(s.at(), camera), EDGE);
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
            float age = clientTicks - p.born() + partial;
            float pt = age / p.life();
            Vec3 at = p.pos().add(p.vel().scale(8.0D * (1.0D - Math.pow(0.86D, age)) / 1.12D));
            float alpha = pt < 0.5F ? 1.0F : Mth.clamp(1.0F - (pt - 0.5F) / 0.5F, 0.0F, 1.0F);
            PlumVfx.puff(d, pose, camera, at, p.size() * (0.7D + 0.8D * pt), p.cell(), alpha * near(at, camera), 0.62F);
        }
        buffers.endBatch(dust);
    }

    /** Лепестки: цветы поля, обвивки, свободные; свечение — мелкое, без размытия. */
    private static void petals(Cast c, Minecraft mc, PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers,
                               float partial, float t) {
        Entity e = mc.level.getEntity(c.entityId);
        RenderType pt = MurimRenderTypes.plumPetals();
        VertexConsumer pc = buffers.getBuffer(pt);
        // Бутон на острие (8 → 12 раскрывается) и бутоны-слоты на клинке.
        if (e != null && SeaRules.petals(c.layer) && t >= SeaRules.BUD && c.meltTick < 0 && c.lostTick < 0) {
            Vec3 tp = tip(c, e, partial);
            float k = (float) Mth.clamp((t - SeaRules.BUD) / SeaRules.BUD_OPEN, 0.0D, 1.0D);
            float fade = (float) Mth.clamp(1.0D - (t - SeaRules.SWEEP_END) / 6.0D, 0.0D, 1.0D);
            if (fade > 0.0F) {
                double diameter = 0.05D + 0.13D * k;
                for (int i = 0; i < 5; i++) {
                    double th = Math.PI * 2.0D * i / 5.0D;
                    Vec3 off = new Vec3(Math.cos(th), Math.sin(th) * 0.7D, Math.sin(th)).scale(diameter * 0.5D * k);
                    PlumVfx.petal(pc, pose, camera, tp.add(off), diameter * 0.5D, i % 4, (float) (th + 0.4D * i), fade,
                            BUD[0], BUD[1], BUD[2]);
                }
            }
            if (SeaRules.slotBuds(c.layer) && t >= SeaRules.TIDE_END) {
                int slots = SeaRules.captures(c.layer);
                int used = c.activeWraps();
                Vec3 hand = e.getPosition(partial).add(0.0D, 1.0D, 0.0D);
                for (int i = 0; i < slots; i++) {
                    Vec3 at = hand.lerp(tp, 0.35D + 0.6D * i / Math.max(1.0D, slots - 1.0D));
                    boolean free = i >= used;
                    PlumVfx.petal(pc, pose, camera, at, free ? 0.045D : 0.03D, i % 4, i * 1.3F, (free ? 1.0F : 0.35F) * near(at, camera),
                            free ? BUD[0] : SHADE[0], free ? BUD[1] : SHADE[1], free ? BUD[2] : SHADE[2]);
                }
            }
        }
        for (Flower f : c.flowers) {
            if (!f.placed || f.tornUntil >= clientTicks || f.pos == null) {
                continue;
            }
            Vec3 at = (f.prev == null ? f.pos : f.prev.lerp(f.pos, partial));
            float melt = f.melt < 0 ? 0.0F : (float) Mth.clamp((clientTicks - f.melt + partial) / (double) SeaRules.MELT, 0.0D, 1.0D);
            float appear = (float) Mth.clamp((clientTicks - f.born + partial) / 4.0D, 0.0D, 1.0D);
            float a = Math.min(appear, Math.max(f.bloom, 0.0F)) * (1.0F - melt * melt) * near(at, camera);
            if (a <= 0.01F) {
                continue;
            }
            // Тает, как снег: белеет и сжимается, край остаётся чётким.
            float w = Mth.clamp(melt * 1.6F, 0.0F, 1.0F);
            float[] col = {FLOWER[0] + (WHITE[0] - FLOWER[0]) * w, FLOWER[1] + (WHITE[1] - FLOWER[1]) * w, FLOWER[2] + (WHITE[2] - FLOWER[2]) * w};
            double size = f.size * (1.0D - 0.75D * melt) * (0.6D + 0.4D * appear);
            flower(pc, pose, camera, at, size, f.cell, f.tilt + (float) (t * 0.02D), a, col);
        }
        for (Wrap w : c.wraps) {
            for (WrapPetal q : w.petals) {
                Vec3 at = q.prev.lerp(q.pos, partial);
                float[] col = q.head ? HEAD : FLOWER;
                PlumVfx.petal(pc, pose, camera, at, q.size * 1.15D, q.cell, (t + q.cell * 5.0F) * q.spin, near(at, camera),
                        col[0], col[1], col[2]);
            }
        }
        for (Mote m : c.motes) {
            Vec3 at = m.prev.lerp(m.pos, partial);
            float a = Mth.clamp((m.life - m.age - partial) / 10.0F, 0.0F, 1.0F) * Mth.clamp((m.age + partial) / 2.0F, 0.0F, 1.0F)
                    * (m.screen ? 1.0F : near(at, camera));
            PlumVfx.petal(pc, pose, camera, at, m.size * 1.5D, m.cell, (m.age + partial) * m.spin, a, m.tint[0], m.tint[1], m.tint[2]);
        }
        buffers.endBatch(pt);
        RenderType gt = MurimRenderTypes.mote();
        VertexConsumer g = buffers.getBuffer(gt);
        // Сердце каждого цветка — крошечная белая точка (светится, как лепестки ауры).
        for (Flower f : c.flowers) {
            if (!f.placed || f.tornUntil >= clientTicks || f.pos == null) {
                continue;
            }
            Vec3 at = (f.prev == null ? f.pos : f.prev.lerp(f.pos, partial));
            float melt = f.melt < 0 ? 0.0F : (float) Mth.clamp((clientTicks - f.melt + partial) / (double) SeaRules.MELT, 0.0D, 1.0D);
            float a = f.bloom * (1.0F - melt) * near(at, camera);
            PlumVfx.glow(g, pose, camera, at, f.size * 0.45D, 0.55F * a, GLOW_WHITE);
            PlumVfx.glow(g, pose, camera, at, f.size * 1.4D, 0.12F * a, GLOW_PINK);
        }
        for (Wrap w : c.wraps) {
            if (w.landed >= 0) {
                continue;
            }
            float age = clientTicks - w.born + partial;
            float a = (float) PlumVfx.curve(age, 0.0, 0.0, 3.0, 1.0, 20.0, 0.6) * near(w.at, camera);
            PlumVfx.glow(g, pose, camera, w.prevAt.lerp(w.at, partial), 0.16D, 0.4F * a, w.snuffed ? GLOW_WHITE : GLOW_BUD);
        }
        buffers.endBatch(gt);
    }

    /** Цветок сливы: пять лепестков вокруг центра в своей плоскости, неравные. */
    private static void flower(VertexConsumer pc, PoseStack.Pose pose, Vec3 camera, Vec3 at, double size, int cell, float rot, float alpha, float[] col) {
        for (int i = 0; i < 5; i++) {
            double th = rot + Math.PI * 2.0D * i / 5.0D;
            Vec3 off = new Vec3(Math.cos(th), 0.25D * Math.sin(th * 2.0D), Math.sin(th)).scale(size * 0.6D);
            float shade = i == 2 ? 0.9F : 1.0F;
            PlumVfx.petal(pc, pose, camera, at.add(off), size * (0.55D + 0.05D * (i % 3)), (cell + i) % 4, (float) th, alpha,
                    col[0] * shade, col[1] * shade, col[2] * shade);
        }
    }

    // ------------------------------------------------------------------ остаточный образ

    private static PartPose[] capturePose(PlayerModel<?> m) {
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

    /** Копия руки с мечом на захвате — тает за 8 тиков; от первого лица не рисуется. */
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
        boolean[] vis = new boolean[parts.length];
        for (int i = 0; i < parts.length; i++) {
            saved[i] = parts[i].storePose();
            vis[i] = parts[i].visible;
        }
        RenderType type = RenderType.entityTranslucent(c.skin != null ? c.skin : player.getSkin().texture());
        try {
            for (Ghost gh : c.ghosts) {
                float k = (clientTicks - gh.born() + partial) / 8.0F;
                if (k < 0.0F || k > 1.0F) {
                    continue;
                }
                for (int i = 0; i < parts.length; i++) {
                    parts[i].loadPose(gh.pose()[i]);
                    parts[i].visible = i == 4 || i == 5;
                }
                ps.pushPose();
                try {
                    // API: reference/minecraft-src/net/minecraft/client/renderer/entity/LivingEntityRenderer.java#render
                    ps.translate(gh.pos().x, gh.pos().y, gh.pos().z);
                    ps.mulPose(Axis.YP.rotationDegrees(180.0F - gh.yaw()));
                    ps.scale(-1.0F, -1.0F, 1.0F);
                    ps.scale(0.9375F, 0.9375F, 0.9375F);
                    ps.translate(0.0F, -1.501F, 0.0F);
                    int colour = ((int) (Mth.clamp(0.5F * (1.0F - k) * (1.0F - k), 0.0F, 1.0F) * 255.0F) << 24) | 0xFFD6E6;
                    model.renderToBuffer(ps, buffers.getBuffer(type), 0x00F000F0, OverlayTexture.NO_OVERLAY, colour);
                } finally {
                    ps.popPose();
                }
            }
            buffers.endBatch(type);
        } finally {
            for (int i = 0; i < parts.length; i++) {
                parts[i].loadPose(saved[i]);
                parts[i].visible = vis[i];
            }
        }
    }

    /** Надпись — когда море выросло (держится 50+ тиков, вверху экрана). */
    @SubscribeEvent
    static void onCaptionTick(ClientTickEvent.Pre event) {
        for (Cast c : CASTS) {
            if (!c.caption && c.began && c.t() >= SeaRules.CAPTION && c.own() && SeaRules.petals(c.layer) && c.meltTick < 0) {
                c.caption = true;
                TechniqueCaption.showSecret(Component.translatable("technique.murim.twenty_four_plum.school"),
                        Component.translatable("technique.murim.twenty_four_plum.sea"), 60);
            }
        }
    }

    private SeaVfx() {
    }
}
