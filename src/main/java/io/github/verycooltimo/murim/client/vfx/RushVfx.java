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
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static io.github.verycooltimo.murim.client.vfx.PlumVfx.EDGE;
import static io.github.verycooltimo.murim.client.vfx.PlumVfx.PINK;

/**
 * Натиск Цветущей Сливы (docs/design/techniques/seven-plum-rush-spec.md, рефы rush/u1–u11):
 * концентрация → один большой взмах → из следа рождается горизонтальный розовый ураган и
 * летит вперёд до 16 блоков → заворачивает цель локальным вихрем (три удара) → пауза → мастер
 * мчится к ней и бьёт прямым уколом, останавливаясь чуть за ней. Синее на рефах — противник,
 * у нас только розовое.
 *
 * <p>Автор 03.10: «натиск выглядит слабо, надо жёстче». Ураган крупнее, плотнее и быстрее;
 * по пути рвёт пыль с земли и затягивает её в витки, встречных волочёт; контакт с целью —
 * импакт-кадр, тряска и крупный дым; рывок — выстрел в 5 тиков с пылевым выбросом назад;
 * укол отбрасывает цель с дымным следом. Ветра линиями нет (правка автора по Взрыву) — ни
 * экранных линий скорости, ни лучей-клиньев: только ленты урагана, пыль, дым и лепестки.
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
        /** Затягивается вихрем урагана (а не просто летит по инерции). */
        boolean vortex;

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

    /** Пыль (серая, от земли) и дым манхвы (клубы у цели). */
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
        boolean vortex;
        double gravity;

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
        Vec3 dashDir;
        int knockId = -1;
        boolean knockAir;
        final Set<Integer> caught = new HashSet<>();
        final List<Mote> petals = new ArrayList<>();
        final List<Puff> puffs = new ArrayList<>();
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

        int wrapSince() {
            return wrapTick < 0 || release < 0 ? 0 : wrapTick - release;
        }

        boolean own() {
            Minecraft mc = Minecraft.getInstance();
            return mc.player != null && mc.player.getId() == entityId;
        }

        /** Плотность по слою: 0,4 на первом, 1 на седьмом. */
        double density() {
            return 0.3D + 0.1D * Math.min(7, layer);
        }

        int n(int full) {
            return Math.max(1, (int) Math.round(full * density()));
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
        boolean own = c.own();
        double dist = mc.player == null ? 99.0D : mc.player.position().distanceTo(p.centre());
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
            // Выпуск: отдача в ноги — кольцо пыли и выброс назад, камера вздрагивает.
            dustRing(c, c.origin, c.n(18), 0.34D, 0.3D);
            dustCone(c, c.origin, c.fFlat.scale(-1.0D), c.n(12), 0.38D);
            burst(c, c.origin.add(0.0D, 1.2D, 0.0D).add(c.f.scale(1.0D)), c.n(28), 0.45D, c.f);
            if (own) {
                CameraShakeHandler.quake(0.4F, 8);
            }
            if (mc.player != null && mc.player.position().distanceTo(c.origin) < 24.0D) {
                io.github.verycooltimo.murim.client.Sfx.play(c.origin.x, c.origin.y + 1.0D, c.origin.z, io.github.verycooltimo.murim.registry.ModSounds.WHIRLWIND.get(),
                        SoundSource.PLAYERS, 1.0F, 1.0F, false);
            }
        } else if (p.stage() == 1) {
            // Контакт урагана с целью: импакт-кадр, тряска, крупный дым, взрыв лепестков.
            c.wrapTick = clientTicks;
            c.wrapAt = p.centre().add(0.0D, 1.1D, 0.0D);
            burst(c, c.wrapAt, c.n(45), 0.42D, c.f);
            if (own) {
                ImpactFrames.trigger(c.wrapAt);
            }
            if (mc.player != null && dist < 24.0D) {
                float q = dist < 8.0D ? 0.8F : (float) (0.8D * (1.0D - (dist - 8.0D) / 16.0D));
                CameraShakeHandler.quake(Math.max(q, own ? 0.7F : 0.0F), 16);
                io.github.verycooltimo.murim.client.Sfx.play(c.wrapAt.x, c.wrapAt.y, c.wrapAt.z, io.github.verycooltimo.murim.registry.ModSounds.SWORD_HIT.get(),
                        SoundSource.PLAYERS, 1.0F, 0.85F, false);
                io.github.verycooltimo.murim.client.Sfx.play(c.wrapAt.x, c.wrapAt.y, c.wrapAt.z, io.github.verycooltimo.murim.registry.ModSounds.IMPACT_HEAVY.get(),
                        SoundSource.PLAYERS, 0.7F, 1.1F, false);
            }
            // Дым урагана — низкий вал и короче финального: вихрь и рывок должны читаться сквозь него.
            smoke(c, p.centre(), 1.05D, c.fFlat, false, 0.6D, 12);
            Double gy = groundY(p.centre().add(0.0D, 0.5D, 0.0D));
            if (gy != null) {
                dustRing(c, new Vec3(p.centre().x, gy, p.centre().z), c.n(26), 0.42D, 0.38D);
            }
        } else {
            c.hitTick = clientTicks;
            c.hitAt = p.centre();
            Entity caster = mc.level == null ? null : mc.level.getEntity(c.entityId);
            Vec3 dir = c.dash.size() >= 2 ? c.dash.get(c.dash.size() - 1).subtract(c.dash.get(0))
                    : caster != null ? p.centre().subtract(caster.position()) : c.fFlat;
            dir = new Vec3(dir.x, 0.0D, dir.z);
            c.dashDir = dir.lengthSqr() < 1.0E-4D ? c.fFlat : dir.normalize();
            if (own) {
                ImpactFrames.trigger(p.centre());
            }
            if (mc.player != null && dist < 24.0D) {
                float q = dist < 8.0D ? 1.0F : (float) (1.0D - (dist - 8.0D) / 16.0D);
                CameraShakeHandler.quake(Math.max(q, own ? 0.9F : 0.0F), 20);
                io.github.verycooltimo.murim.client.Sfx.play(p.centre().x, p.centre().y, p.centre().z, io.github.verycooltimo.murim.registry.ModSounds.SWORD_HIT.get(),
                        SoundSource.PLAYERS, 1.0F, 0.8F, false);
                io.github.verycooltimo.murim.client.Sfx.play(p.centre().x, p.centre().y, p.centre().z, io.github.verycooltimo.murim.registry.ModSounds.IMPACT_HEAVY.get(),
                        SoundSource.PLAYERS, 0.9F, 0.95F, false);
            }
            burst(c, p.centre(), c.n(45), 0.55D, c.dashDir);
            // codex 03.10: дым уносится по ходу отброса и редеет за ~1,5 с, клинок и цель видны.
            // Дым остаётся в точке удара, цель вылетает из него по отбросу — силуэт свободен.
            smoke(c, p.centre().add(c.dashDir.scale(0.6D)), 1.4D, c.dashDir.scale(0.6D), true, 0.7D, 20);
            // Цель уколота — следим за ней в полёте отброса (дымный след и пыль при падении).
            if (mc.level != null) {
                double best = 3.0D;
                for (LivingEntity e : mc.level.getEntitiesOfClass(LivingEntity.class, new AABB(p.centre(), p.centre()).inflate(3.0D))) {
                    double d = e.position().add(0.0D, e.getBbHeight() * 0.6D, 0.0D).distanceTo(p.centre());
                    if (e.getId() != c.entityId && d < best) {
                        best = d;
                        c.knockId = e.getId();
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ спавн

    /** Лепестки во все стороны с перевесом по {@code bias}. */
    private static void burst(Cast c, Vec3 at, int n, double speed, Vec3 bias) {
        if (c.layer < 3) {
            return;
        }
        for (int i = 0; i < n && c.petals.size() < 700; i++) {
            Vec3 v = new Vec3(c.random.nextGaussian(), c.random.nextDouble() * 0.8D, c.random.nextGaussian()).normalize()
                    .add(bias == null ? Vec3.ZERO : bias.scale(0.6D)).normalize().scale(speed * (0.35D + c.random.nextDouble()));
            c.petals.add(new Mote(at.add(c.random.nextGaussian() * 0.3D, c.random.nextGaussian() * 0.3D, c.random.nextGaussian() * 0.3D), v,
                    30 + c.random.nextInt(24), c.random.nextInt(4), (float) (c.random.nextDouble() - 0.5D), 0.08D + 0.06D * c.random.nextDouble()));
        }
    }

    /** Верх твёрдого блока под точкой (до 5 блоков вниз) или {@code null} — под ногами пусто. */
    private static Double groundY(Vec3 at) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        BlockPos p = BlockPos.containing(at);
        for (int i = 0; i < 6; i++) {
            BlockPos q = p.below(i);
            if (!mc.level.getBlockState(q).isAir()) {
                return q.getY() + 1.0D;
            }
        }
        return null;
    }

    /** Кольцо серой пыли по земле наружу. */
    private static void dustRing(Cast c, Vec3 at, int n, double speed, double size) {
        Double gy = groundY(at.add(0.0D, 0.3D, 0.0D));
        if (gy == null) {
            return;
        }
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2.0D * i / n + c.random.nextDouble() * 0.4D;
            Vec3 out = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
            Puff p = new Puff(new Vec3(at.x, gy + 0.15D, at.z).add(out.scale(0.4D)), out.scale(speed * (0.6D + 0.7D * c.random.nextDouble()))
                    .add(0.0D, 0.02D + 0.03D * c.random.nextDouble(), 0.0D), 18 + c.random.nextInt(10), c.random.nextInt(16),
                    size * (0.7D + 0.6D * c.random.nextDouble()), false, 0.62F + 0.08F * c.random.nextFloat(), 0.0F);
            c.puffs.add(p);
        }
    }

    /** Выброс пыли конусом в сторону {@code dir} (отдача шага и рывка). */
    private static void dustCone(Cast c, Vec3 feet, Vec3 dir, int n, double speed) {
        Double gy = groundY(feet.add(0.0D, 0.3D, 0.0D));
        if (gy == null) {
            return;
        }
        Vec3 sideV = new Vec3(-dir.z, 0.0D, dir.x);
        for (int i = 0; i < n; i++) {
            Vec3 v = dir.add(sideV.scale((c.random.nextDouble() - 0.5D) * 1.3D)).normalize()
                    .scale(speed * (0.6D + 0.8D * c.random.nextDouble())).add(0.0D, 0.04D + 0.06D * c.random.nextDouble(), 0.0D);
            c.puffs.add(new Puff(new Vec3(feet.x, gy + 0.2D, feet.z), v, 16 + c.random.nextInt(10), c.random.nextInt(16),
                    0.3D + 0.25D * c.random.nextDouble(), false, 0.6F + 0.1F * c.random.nextFloat(), 0.0F));
        }
    }

    /**
     * Дым манхвы у цели: низкий вал одной массой по земле, через 6 тиков над ним встаёт облако
     * (как у принятого Взрыва), крупнее в {@code scale} раз. Цель в небе — облаком вокруг неё.
     */
    private static void smoke(Cast c, Vec3 at, double scale, Vec3 push, boolean rise, double lifeK, int clubs) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Double gy = groundY(at.add(0.0D, 0.3D, 0.0D));
        boolean ground = gy != null && at.y - gy < 2.5D;
        Vec3 g = ground ? new Vec3(at.x, gy, at.z) : at;
        Entity caster = mc.level.getEntity(c.entityId);
        Random r = c.random;
        int n = Math.max(6, c.n(clubs));
        double rad = (1.8D + 0.6D * c.density()) * scale;
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2.0D * i / n + r.nextDouble() * 0.3D;
            Vec3 out = new Vec3(Math.cos(a), ground ? 0.0D : (r.nextDouble() - 0.5D) * 1.2D, Math.sin(a)).normalize();
            Vec3 p = g.add(out.scale(rad * (0.35D + 0.4D * r.nextDouble())));
            // Вокруг мастера — просвет: он стоит у цели, дым не должен его прятать.
            if (caster != null && p.distanceTo(caster.position()) < 2.0D) {
                continue;
            }
            boolean hollow = i % 3 == 0;
            double size = (0.6D + 1.0D * Math.pow(r.nextDouble(), 1.5D)) * (hollow ? 1.25D : 1.0D) * (0.7D + 0.3D * c.density()) * scale;
            Puff bank = new Puff(p.add(0.0D, ground ? size * 0.45D : 0.0D, 0.0D), out.scale(0.12D + 0.22D * r.nextDouble()).add(push.scale(0.12D)),
                    (int) ((36 + r.nextInt(18)) * lifeK), r.nextInt(16), size, true, hollow ? 0.56F : 0.84F + 0.12F * r.nextFloat(), (float) (r.nextDouble() * 6.28D));
            bank.delay = (rise ? 3 : 5) + r.nextInt(3);
            c.puffs.add(bank);
        }
        for (int i = 0; rise && i < n / 2; i++) {
            double a = r.nextDouble() * Math.PI * 2.0D;
            Vec3 p = g.add(Math.cos(a) * rad * 0.3D, ground ? 0.9D : 0.3D, Math.sin(a) * rad * 0.3D);
            if (caster != null && p.distanceTo(caster.position()) < 1.8D) {
                continue;
            }
            Puff column = new Puff(p, new Vec3(0.0D, 0.05D + 0.05D * r.nextDouble(), 0.0D), 44 + r.nextInt(16), r.nextInt(16),
                    (0.9D + 1.0D * r.nextDouble()) * (0.7D + 0.3D * c.density()) * scale, true, 0.74F + 0.14F * r.nextFloat(),
                    (float) (r.nextDouble() * 6.28D));
            column.delay = 5 + r.nextInt(8);
            c.puffs.add(column);
        }
    }

    /**
     * Ураган рвёт землю: под головой пыль и комья поднимаются и затягиваются в витки; по бокам
     * пути воздух отбрасывает пыль наружу.
     */
    private static void tear(Cast c, double head) {
        Vec3 tip = RushRules.axis(c.origin, c.f, head);
        double r = RushRules.radius(c.layer);
        Double gy = groundY(tip);
        if (gy == null || tip.y - gy > r + 0.6D) {
            return;
        }
        // Фронт срывает крупные клубы пыли (codex 03.10: 2–3 на блок пути, 0,8–1,4 блока, вверх и
        // назад, живут коротко) и немного мелкой пыли затягивает в витки.
        for (int i = 0; i < c.n(4); i++) {
            double off = (c.random.nextDouble() - 0.5D) * 1.6D * r;
            Vec3 at = new Vec3(tip.x, gy + 0.3D, tip.z).add(c.side.scale(off)).add(c.fFlat.scale(-c.random.nextDouble() * RushRules.SPEED));
            Puff p = new Puff(at, c.fFlat.scale(-0.08D - 0.08D * c.random.nextDouble()).add(c.side.scale(Math.signum(off) * 0.06D))
                    .add(0.0D, 0.14D + 0.12D * c.random.nextDouble(), 0.0D),
                    9 + c.random.nextInt(6), c.random.nextInt(16), 0.45D + 0.3D * c.random.nextDouble(), false,
                    0.6F + 0.08F * c.random.nextFloat(), 0.0F);
            c.puffs.add(p);
        }
        for (int i = 0; i < c.n(2); i++) {
            double off = (c.random.nextDouble() - 0.5D) * 2.0D * r;
            Vec3 at = new Vec3(tip.x, gy + 0.15D, tip.z).add(c.side.scale(off));
            Puff p = new Puff(at, c.fFlat.scale(0.3D).add(0.0D, 0.1D, 0.0D), 14 + c.random.nextInt(6), c.random.nextInt(16),
                    0.3D + 0.2D * c.random.nextDouble(), false, 0.6F, 0.0F);
            p.vortex = true;
            c.puffs.add(p);
        }
        // Комья: мелкие тёмные, вылетают вверх и падают.
        for (int i = 0; i < c.n(3); i++) {
            double off = (c.random.nextDouble() - 0.5D) * 1.6D * r;
            Vec3 at = new Vec3(tip.x, gy + 0.1D, tip.z).add(c.side.scale(off));
            Puff clod = new Puff(at, c.fFlat.scale(0.25D).add(c.side.scale(Math.signum(off) * 0.12D))
                    .add(0.0D, 0.3D + 0.2D * c.random.nextDouble(), 0.0D), 20 + c.random.nextInt(8), c.random.nextInt(16),
                    0.1D + 0.06D * c.random.nextDouble(), false, 0.32F, 0.0F);
            clod.gravity = 0.045D;
            c.puffs.add(clod);
        }
    }

    /**
     * Поле скоростей урагана: у головы частицы закручиваются вокруг оси, тянутся к ней и
     * летят вперёд со скоростью потока; позади хвоста поле стихает.
     */
    private static Vec3 vortex(Cast c, Vec3 pos, Vec3 vel) {
        if (c.release < 0 || c.wrapTick >= 0) {
            return vel;
        }
        double head = RushRules.head(c.since());
        double len = RushRules.length(c.layer);
        Vec3 a0 = RushRules.axis(c.origin, c.f, 0.0D);
        Vec3 rel = pos.subtract(a0);
        double along = rel.dot(c.f);
        if (along < head - len - 1.0D || along > head + 1.0D) {
            return vel;
        }
        Vec3 radial = rel.subtract(c.f.scale(along));
        double r = radial.length();
        double R = RushRules.radius(c.layer) * 1.4D;
        if (r > R || r < 1.0E-3D) {
            return vel;
        }
        double k = 1.0D - r / R;
        Vec3 tang = c.f.cross(radial.scale(1.0D / r));
        return vel.scale(0.8D).add(tang.scale(0.32D * (0.4D + k))).add(radial.scale(-0.06D)).add(c.f.scale(RushRules.SPEED * 0.55D * (0.5D + k)));
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
            // Стойка: пыль из-под ног.
            if (c.release < 0 && e != null && (age == 2 || age % 8 == 0)) {
                dustRing(c, e.position(), age == 2 ? 8 : 3, age == 2 ? 0.16D : 0.08D, 0.24D);
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
            if (s >= 0 && c.wrapTick < 0 && s < 30) {
                double head = RushRules.head(s);
                tear(c, head);
                if (c.own() && s % 3 == 0) {
                    CameraShakeHandler.quake(0.14F, 5);
                }
                if (c.layer >= 3) {
                    for (int i = 0; i < c.n(6); i++) {
                        Vec3 p = point(c, head - c.random.nextDouble() * RushRules.length(c.layer), s, c.random.nextInt(3), c.random.nextDouble());
                        Mote m = new Mote(p, c.f.scale(0.4D), 22 + c.random.nextInt(10), c.random.nextInt(4), (float) (c.random.nextDouble() - 0.5D),
                                0.08D + 0.04D * c.random.nextDouble());
                        m.vortex = true;
                        c.petals.add(m);
                    }
                }
                caught(c, mc, head);
            }
            int w = c.wrapAge();
            int dashT = RushRules.dash(c.wrapSince());
            if (w >= 0) {
                // Удары вихря по цели: сжатие, тряска, лепестки и пыль.
                if (w == RushRules.PULSES[1] || w == RushRules.PULSES[2]) {
                    burst(c, c.wrapAt, c.n(30), 0.32D, null);
                    dustRing(c, c.wrapAt.subtract(0.0D, 1.1D, 0.0D), c.n(10), 0.3D, 0.3D);
                    if (mc.player != null && mc.player.position().distanceTo(c.wrapAt) < 20.0D) {
                        CameraShakeHandler.quake(c.own() ? 0.35F : 0.25F, 6);
                    }
                }
                // Локальный вихрь носит лепестки вокруг цели.
                if (w < RushRules.WRAP && c.layer >= 3) {
                    for (int i = 0; i < c.n(5); i++) {
                        double a = c.random.nextDouble() * Math.PI * 2.0D;
                        Vec3 rad = c.side.scale(Math.cos(a)).add(c.fFlat.scale(Math.sin(a)));
                        Vec3 tang = new Vec3(-rad.z, 0.0D, rad.x);
                        c.petals.add(new Mote(c.wrapAt.add(rad.scale(1.5D)).add(0.0D, (c.random.nextDouble() - 0.5D) * 1.8D, 0.0D),
                                tang.scale(0.35D).add(0.0D, 0.03D, 0.0D), 14 + c.random.nextInt(8), c.random.nextInt(4), 0.4F, 0.09D));
                    }
                }
                if (w == dashT && c.own() && c.layer >= 3) {
                    TechniqueCaption.show(Component.translatable("technique.murim.seven_plum_blossoms.school"),
                            Component.translatable("technique.murim.seven_plum_blossoms.rush"), 30);
                }
                if (w >= dashT && w <= dashT + RushRules.DASH_TICKS + 2 && e != null) {
                    c.dash.add(e.position().add(0.0D, 1.0D, 0.0D));
                    // Выстрел: отдача в землю назад, пыль сдирается по всему пути.
                    if (w == dashT) {
                        Vec3 toward = c.wrapAt.subtract(e.position());
                        toward = new Vec3(toward.x, 0.0D, toward.z);
                        toward = toward.lengthSqr() < 1.0E-4D ? c.fFlat : toward.normalize();
                        dustCone(c, e.position(), toward.scale(-1.0D), c.n(16), 0.5D);
                        dustRing(c, e.position(), c.n(14), 0.3D, 0.3D);
                        if (c.own()) {
                            CameraShakeHandler.quake(0.4F, 6);
                        }
                        if (mc.player != null && mc.player.position().distanceTo(e.position()) < 24.0D) {
                            io.github.verycooltimo.murim.client.Sfx.play(e.getX(), e.getY() + 1.0D, e.getZ(), io.github.verycooltimo.murim.registry.ModSounds.DASH.get(),
                                    SoundSource.PLAYERS, 1.0F, 1.0F, false);
                        }
                    } else if (w <= dashT + RushRules.DASH_TICKS) {
                        dustRing(c, e.position(), c.n(5), 0.22D, 0.3D);
                        if (c.layer >= 3) {
                            // Сгусток лепестков на каждом тике пути (послеобраз рывка), живёт коротко.
                            Vec3 ctr = e.position().add(0.0D, 1.0D, 0.0D);
                            for (int i = 0; i < c.n(12); i++) {
                                c.petals.add(new Mote(ctr.add(c.random.nextGaussian() * 0.3D, c.random.nextGaussian() * 0.35D, c.random.nextGaussian() * 0.3D),
                                        new Vec3(c.random.nextGaussian() * 0.04D, 0.01D, c.random.nextGaussian() * 0.04D),
                                        8 + c.random.nextInt(8), c.random.nextInt(4), 0.4F, 0.09D));
                            }
                        }
                    }
                }
            }
            if (c.knockId >= 0 && c.hitTick >= 0) {
                knockTrail(c, mc, clientTicks - c.hitTick);
            }
            for (Mote m : c.petals) {
                m.prev = m.pos;
                m.age++;
                Vec3 v = new Vec3(m.vel.x * 0.93D, m.vel.y * 0.93D - 0.0025D, m.vel.z * 0.93D);
                if (m.vortex) {
                    v = vortex(c, m.pos, v);
                }
                m.vel = v;
                m.pos = m.pos.add(m.vel);
            }
            c.petals.removeIf(m -> m.age >= m.life);
            for (Puff p : c.puffs) {
                if (p.delay > 0) {
                    p.delay--;
                    continue;
                }
                p.prev = p.pos;
                p.age++;
                Vec3 v = p.smoke ? new Vec3(p.vel.x * 0.93D, p.vel.y * 0.97D, p.vel.z * 0.93D)
                        : new Vec3(p.vel.x * 0.88D, p.vel.y * 0.9D - p.gravity, p.vel.z * 0.88D);
                if (p.vortex) {
                    v = vortex(c, p.pos, v);
                }
                p.vel = v;
                p.pos = p.pos.add(p.vel);
            }
            c.puffs.removeIf(p -> p.age >= p.life);
            if (age > 320 || (w > RushRules.end(c.wrapSince()) + 50) || (c.wrapTick < 0 && s > 60)) {
                it.remove();
            }
        }
    }

    /** Встречные, которых ураган волочит (сервер тащит их, здесь — лепестки и пыль вокруг). */
    private static void caught(Cast c, Minecraft mc, double head) {
        Vec3 tip = RushRules.axis(c.origin, c.f, head);
        Vec3 a0 = RushRules.axis(c.origin, c.f, 0.0D);
        for (LivingEntity e : mc.level.getEntitiesOfClass(LivingEntity.class, new AABB(tip, tip).inflate(RushRules.CATCH_WIDTH + 1.0D))) {
            if (e.getId() == c.entityId) {
                continue;
            }
            Vec3 ctr = e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D);
            Vec3 rel = ctr.subtract(a0);
            double along = rel.dot(c.f);
            if (along < head - 3.0D || along > head + 0.8D || rel.subtract(c.f.scale(along)).length() > RushRules.CATCH_WIDTH + 0.5D) {
                continue;
            }
            if (c.caught.add(e.getId())) {
                burst(c, ctr, c.n(16), 0.25D, c.f);
                if (c.own()) {
                    CameraShakeHandler.quake(0.2F, 4);
                }
            }
            if (c.layer >= 3) {
                for (int i = 0; i < 2; i++) {
                    Mote m = new Mote(ctr.add(c.random.nextGaussian() * 0.5D, c.random.nextGaussian() * 0.6D, c.random.nextGaussian() * 0.5D),
                            c.f.scale(0.3D), 16, c.random.nextInt(4), 0.4F, 0.09D);
                    m.vortex = true;
                    c.petals.add(m);
                }
            }
            Puff p = new Puff(e.position().add(0.0D, 0.2D, 0.0D), c.f.scale(0.2D).add(0.0D, 0.05D, 0.0D), 16, c.random.nextInt(16), 0.35D, false, 0.62F, 0.0F);
            p.vortex = true;
            c.puffs.add(p);
        }
    }

    /** Отброшенная уколом цель тянет за собой дым и лепестки; при падении — кольцо пыли. */
    private static void knockTrail(Cast c, Minecraft mc, int t) {
        Entity e = mc.level.getEntity(c.knockId);
        if (e == null || t > 24) {
            c.knockId = -1;
            return;
        }
        if (t <= 12 && t % 2 == 0) {
            Vec3 at = e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D);
            Puff p = new Puff(at, c.dashDir.scale(-0.05D).add(0.0D, 0.02D, 0.0D), 26 + c.random.nextInt(10), c.random.nextInt(16),
                    0.45D + 0.35D * c.random.nextDouble(), true, 0.8F + 0.12F * c.random.nextFloat(), (float) (c.random.nextDouble() * 6.28D));
            c.puffs.add(p);
            burst(c, at, c.n(4), 0.12D, null);
        }
        if (!e.onGround()) {
            c.knockAir = true;
        } else if (c.knockAir) {
            c.knockAir = false;
            dustRing(c, e.position(), c.n(18), 0.36D, 0.36D);
            if (mc.player != null && mc.player.position().distanceTo(e.position()) < 16.0D) {
                CameraShakeHandler.quake(0.3F, 6);
            }
            c.knockId = -1;
        }
    }

    /**
     * Точка спиральной ленты урагана: {@code d} — расстояние по оси от старта, {@code strand} —
     * лента (0 — главная широкая, 1–5 — вспомогательные), {@code u} — доля по сечению (для лепестков).
     */
    private static Vec3 point(Cast c, double d, float s, int strand, double u) {
        return point(c, d, s, strand, u, 1.0D);
    }

    private static final double[] STRAND_R = {1.0D, 0.8D, 0.62D, 0.9D, 0.72D, 1.08D};
    private static final double[] STRAND_PITCH = {4.6D, 3.9D, 5.4D, 3.4D, 4.2D, 5.0D};

    /**
     * Точка спиральной ленты: шаг длинный (4–5 блоков на оборот), радиус раскрывается к голове
     * ({@code open} 0…1 — доля от хвоста), у каждой ленты своя фаза и шаг (codex 02.10: не кольца).
     * Вращение быстрое — оборот за 9 тиков (было 16): масса потока, а не дрейф.
     */
    private static Vec3 point(Cast c, double d, float s, int strand, double u, double open) {
        int k = Math.floorMod(strand, STRAND_R.length);
        double r = radius(c, s) * STRAND_R[k]
                * (0.3D + 0.7D * open) * (0.85D + 0.15D * Math.sin(d * 1.1D + c.phase + strand * 1.7D));
        double ph = d / STRAND_PITCH[k] * Math.PI * 2.0D - s * (Math.PI * 2.0D / 9.0D) + strand * 2.1D + u * 0.6D;
        Vec3 axis = RushRules.axis(c.origin, c.f, d).add(c.side.scale(0.2D * Math.sin(d * 0.7D + s * 0.2D)));
        return axis.add(c.side.scale(Math.cos(ph) * r)).add(c.up.scale(Math.sin(ph) * r * 0.85D));
    }

    /**
     * Радиус урагана с раскрытием из взмаха: первые 5 тиков он растёт от трети до полного —
     * рукав выстреливает из следа клинка, а не встаёт перед мастером куполом (codex 03.10).
     */
    private static double radius(Cast c, double s) {
        return RushRules.radius(c.layer) * Mth.clamp((s + 1.0D) / 6.0D, 0.33D, 1.0D);
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
                    dashTrail(c, pose, camera, air, partial, mc);
                }
                buffers.endBatch(MurimRenderTypes.airBand());
                if (c.release >= 0) {
                    mass(c, pose, camera, buffers, clientTicks - c.release + partial, partial);
                }
                puffs(c, pose, camera, buffers, partial);
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

    /**
     * Розовая масса урагана (codex 03.10: «петли и облака вместо тяжёлой массы»): мягкие светящиеся
     * пятна, бегущие по виткам с перевесом к голове, заполняют просветы между лентами; у головы —
     * плотное ядро. На уколе — вспышка 1,4 блока на 2 тика у груди цели.
     */
    private static void mass(Cast c, PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float s, float partial) {
        RenderType gt = MurimRenderTypes.mote();
        VertexConsumer g = buffers.getBuffer(gt);
        double head = c.wrapTick >= 0 ? RushRules.head(c.wrapTick - c.release) : RushRules.head(s);
        float fade = c.wrapTick >= 0 ? (float) Mth.clamp(1.0D - (clientTicks - c.wrapTick - 2) / 5.0D, 0.0D, 1.0D)
                : (float) Mth.clamp((RushRules.RANGE - head + 2.0D) / 3.0D, 0.0D, 1.0D);
        // Рождение: масса набирается за 6 тиков — иначе пятна в комке у мастера сливаются в белое.
        fade *= (float) Mth.clamp(s / 6.0D, 0.2D, 1.0D);
        if (fade > 0.0F && c.layer >= 2) {
            double len = RushRules.length(c.layer) * Mth.clamp(s / 8.0D, 0.15D, 1.0D);
            int n = 18 + 6 * Math.min(7, c.layer);
            for (int i = 0; i < n; i++) {
                double frac = ((i * 0.6180339D + c.phase) % 1.0D);
                double u = 1.0D - Math.pow(frac, 1.6D);
                double d = head - len * (1.0D - u);
                Vec3 at = point(c, d, s, i % 6, 0.2D * i, u);
                PlumVfx.glow(g, pose, camera, at, 0.55D + 0.6D * u, (0.07F + 0.09F * (float) u) * fade, PINK);
            }
            if (c.wrapTick < 0) {
                Vec3 tip = RushRules.axis(c.origin, c.f, head - 0.6D);
                PlumVfx.glow(g, pose, camera, tip, radius(c, s) * 0.9D, 0.18F * fade, BODY);
                PlumVfx.glow(g, pose, camera, tip, radius(c, s) * 0.45D, 0.2F * fade, CORE);
            }
        }
        if (c.hitTick >= 0 && c.hitAt != null) {
            float ht = clientTicks - c.hitTick + partial;
            if (ht < 3.0F) {
                float a = (float) PlumVfx.curve(ht, 0.0, 1.0, 2.0, 1.0, 3.0, 0.0);
                PlumVfx.glow(g, pose, camera, c.hitAt, 1.4D, 0.85F * a, CORE);
                PlumVfx.glow(g, pose, camera, c.hitAt, 2.2D, 0.4F * a, PINK);
            }
        }
        buffers.endBatch(gt);
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
            w[i] = 0.42D * Math.sin(Math.PI * (i + 0.5D) / p.length);
        }
        PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 2.2D), 0.18F * a, RIM);
        PlumVfx.strip(v, pose, camera, p, w, 0.6F * a, BODY);
        PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 0.3D), 0.95F * a, CORE);
    }

    /**
     * Летящий горизонтальный ураган: главная широкая лента и до пяти вспомогательных с разным
     * шагом и запаздыванием, плотное тело потока, прозрачный центр; у головы — сверло из
     * коротких толстых витков, вращающихся втрое быстрее (давление фронта). Раскрывается из
     * взмаха за 8 тиков. После обволакивания за 4 тика уходит в локальный вихрь.
     */
    private static void tornado(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float s) {
        double head = c.wrapTick >= 0 ? RushRules.head(c.wrapTick - c.release) : RushRules.head(s);
        double len = RushRules.length(c.layer) * Mth.clamp(s / 8.0D, 0.15D, 1.0D);
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
        double R = radius(c, s);
        // Тело урагана: плотная полупрозрачная масса вдоль оси — витки читаются одним потоком.
        {
            int n = 24;
            double from = Math.max(0.0D, head - len);
            Vec3[] p = new Vec3[n + 1];
            double[] w = new double[n + 1];
            for (int i = 0; i <= n; i++) {
                double u = i / (double) n;
                double d = from + (head - from) * u;
                p[i] = RushRules.axis(c.origin, c.f, d);
                w[i] = R * 0.95D * Math.sin(Math.PI * Math.min(1.0D, u * 1.05D + 0.02D)) * (0.6D + 0.4D * u);
            }
            PlumVfx.strip(v, pose, camera, p, w, 0.22F * fade, BODY);
            PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 0.4D), 0.16F * fade, CORE);
        }
        // Рваные короткие штрихи между витками: тело потока (а не полые обручи).
        // codex 03.10: масса плотнее к голове — штрихи распределены с перевесом к фронту.
        for (int k = 0; k < 26; k++) {
            double d0 = Math.max(0.0D, head - len) + len * Math.pow((k * 0.137D + c.phase * 0.1D) % 1.0D, 0.6D);
            double u0 = (d0 - (head - len)) / Math.max(0.1D, len);
            Vec3[] q = new Vec3[5];
            double[] qw = new double[5];
            for (int i = 0; i < 5; i++) {
                q[i] = point(c, d0 + i * 0.4D, s, k % 6, 0.3D * k, Math.min(1.0D, u0 + i * 0.05D));
                qw[i] = 0.26D * Math.sin(Math.PI * (i + 0.5D) / 5.0D) * (0.4D + 0.6D * u0);
            }
            PlumVfx.strip(v, pose, camera, q, qw, 0.4F * fade, BODY);
            PlumVfx.strip(v, pose, camera, q, PlumVfx.scale(qw, 0.3D), 0.75F * fade, CORE);
        }
        int strands = c.layer >= 6 ? 6 : c.layer >= 4 ? 4 : c.layer >= 2 ? 3 : 2;
        double[] lags = {0.0D, 0.8D, 1.6D, 0.4D, 1.2D, 0.2D};
        double[] bases = {1.5D, 1.0D, 0.75D, 0.6D, 0.55D, 0.5D};
        for (int k = 0; k < strands; k++) {
            double lag = lags[k];
            double from = Math.max(0.0D, head - len + lag * 0.5D);
            double to = head - lag;
            if (to <= from) {
                continue;
            }
            int n = 52;
            Vec3[] p = new Vec3[n + 1];
            double[] w = new double[n + 1];
            for (int i = 0; i <= n; i++) {
                double u = i / (double) n;
                double d = from + (to - from) * u;
                p[i] = point(c, d, s, k, 0.0D, u);
                // Широкие ленты: ширина растёт к голове, хвост острый.
                w[i] = bases[k] * (0.35D + 0.65D * u) * (0.8D + 0.2D * Math.sin(d * 2.0D + s * 0.3D))
                        * Math.sin(Math.PI * Math.min(1.0D, u * 1.03D + 0.02D));
            }
            PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 1.8D), 0.14F * fade, RIM);
            PlumVfx.strip(v, pose, camera, p, w, 0.5F * fade, BODY);
            PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 0.28D), 0.95F * fade, CORE);
        }
        // Сверло у головы: короткие толстые витки, быстрое вращение — фронт давит воздух.
        if (c.wrapTick < 0 && c.layer >= 2) {
            for (int k = 0; k < 5; k++) {
                int n = 14;
                Vec3[] p = new Vec3[n + 1];
                double[] w = new double[n + 1];
                for (int i = 0; i <= n; i++) {
                    double u = i / (double) n;
                    double d = head - 1.8D + 1.9D * u;
                    double r = R * (0.95D - 0.55D * u * u);
                    double ph = u * Math.PI * 1.3D - s * (Math.PI * 2.0D / 3.5D) + k * Math.PI * 2.0D / 5.0D;
                    p[i] = RushRules.axis(c.origin, c.f, d).add(c.side.scale(Math.cos(ph) * r)).add(c.up.scale(Math.sin(ph) * r * 0.85D));
                    w[i] = 0.9D * Math.sin(Math.PI * u);
                }
                PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 1.6D), 0.16F * fade, RIM);
                PlumVfx.strip(v, pose, camera, p, w, 0.55F * fade, BODY);
                PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 0.3D), 0.95F * fade, CORE);
            }
        }
    }

    /**
     * Локальный вихрь (u7): ленты проходят перед телом и за ним на нескольких высотах, цель
     * внутри потока; на каждом ударе — сжатие, режущая черта и вспышка в точке контакта.
     */
    private static void wrap(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        if (c.wrapTick < 0) {
            return;
        }
        float t = clientTicks - c.wrapTick + partial;
        if (t > RushRules.WRAP + 8) {
            return;
        }
        float grow = (float) Mth.clamp(t / 2.0D, 0.0D, 1.0D);
        float out = (float) Mth.clamp((RushRules.WRAP + 8 - t) / 8.0D, 0.0D, 1.0D);
        double squeeze = 1.0D;
        for (int p : RushRules.PULSES) {
            // codex 03.10: удар — сжатие с 1,5 до 0,7 блока за 1 тик и раскрытие за 2.
            double dt = t - p;
            if (dt >= -1.0D && dt <= 0.0D) {
                squeeze = Math.min(squeeze, 1.0D - 0.53D * (dt + 1.0D));
            } else if (dt > 0.0D && dt <= 2.0D) {
                squeeze = Math.min(squeeze, 0.47D + 0.53D * dt / 2.0D);
            }
        }
        double spread = t > RushRules.WRAP ? 1.0D + (t - RushRules.WRAP) * 0.25D : 1.0D;
        Vec3 feet = c.wrapAt.subtract(0.0D, 1.1D, 0.0D);
        double[] heights = {0.25D, 1.0D, 1.75D, 2.5D, 3.1D};
        double[] tilts = {14.0D, -10.0D, 22.0D, -26.0D, 6.0D};
        int arcs = c.layer >= 4 ? 5 : c.layer >= 2 ? 4 : 3;
        for (int k = 0; k < arcs; k++) {
            int n = 36;
            Vec3[] p = new Vec3[n + 1];
            double[] w = new double[n + 1];
            double tilt = Math.toRadians(tilts[k]);
            double r = (1.5D + 0.25D * (k % 2) - 0.15D * Math.max(0, k - 2)) * squeeze * spread * grow;
            for (int i = 0; i <= n; i++) {
                double u = i / (double) n;
                double a = u * Math.PI * 1.75D + t * 0.75D * (k % 2 == 0 ? 1 : -1) + k * 1.9D;
                Vec3 ring = c.side.scale(Math.cos(a) * r).add(c.fFlat.scale(Math.sin(a) * r));
                p[i] = feet.add(0.0D, heights[k] + Math.sin(a) * r * Math.sin(tilt), 0.0D).add(ring);
                w[i] = (k == 0 ? 0.75D : 0.48D) * Math.sin(Math.PI * u);
            }
            PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 1.8D), 0.14F * out, RIM);
            PlumVfx.strip(v, pose, camera, p, w, 0.52F * out, BODY);
            PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 0.25D), 0.95F * out, CORE);
        }
        // Режущие черты ударов (u7 — розовые срезы через корпус), каждый раз под новым углом.
        for (int j = 0; j < RushRules.PULSES.length; j++) {
            float pt = t - RushRules.PULSES[j];
            if (pt < 0.0F || pt > 5.0F) {
                continue;
            }
            double ang = Math.toRadians(new double[] {-35.0D, 40.0D, -5.0D}[j]);
            Vec3 axis = c.side.scale(Math.cos(ang)).add(0.0D, Math.sin(ang), 0.0D);
            float a = (float) PlumVfx.curve(pt, 0.0, 1.0, 5.0, 0.0);
            Vec3[] q = {c.wrapAt.subtract(axis.scale(1.6D)), c.wrapAt, c.wrapAt.add(axis.scale(1.6D))};
            double[] qw = {0.0D, 0.2D, 0.0D};
            PlumVfx.strip(v, pose, camera, q, PlumVfx.scale(qw, 2.5D), 0.25F * a, RIM);
            PlumVfx.strip(v, pose, camera, q, qw, 0.95F * a, CORE);
        }
    }

    /**
     * След рывка-выстрела: короткий толстый сгусток за спиной мастера (последние 3,5 блока пути,
     * шире у мастера), гаснет за 4 тика после прибытия; на уколе — короткая толстая вспышка
     * клинка, без длинной тонкой черты (codex 03.10: длинная черта — тот же «ветер линиями»).
     */
    private static void dashTrail(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial, Minecraft mc) {
        if (c.dash.size() < 1 || c.wrapTick < 0) {
            return;
        }
        float t = clientTicks - c.wrapTick + partial;
        int dashT = RushRules.dash(c.wrapSince());
        float a = (float) Mth.clamp((dashT + RushRules.DASH_TICKS + 5 - t) / 4.0D, 0.0D, 1.0D);
        List<Vec3> pts = new ArrayList<>(c.dash);
        Entity e = mc.level.getEntity(c.entityId);
        if (e != null && t <= dashT + RushRules.DASH_TICKS + 2) {
            pts.add(new Vec3(Mth.lerp(partial, e.xo, e.getX()), Mth.lerp(partial, e.yo, e.getY()) + 1.0D, Mth.lerp(partial, e.zo, e.getZ())));
        }
        if (pts.size() >= 2 && a > 0.0F) {
            // Хвост длиной 3,5 блока от текущего конца, разбитый на 10 отрезков.
            List<Vec3> tail = new ArrayList<>();
            double left = 3.5D;
            Vec3 cur = pts.get(pts.size() - 1);
            tail.add(cur);
            for (int i = pts.size() - 2; i >= 0 && left > 0.0D; i--) {
                Vec3 nxt = pts.get(i);
                double d = nxt.distanceTo(cur);
                if (d >= left) {
                    tail.add(cur.add(nxt.subtract(cur).scale(left / Math.max(1.0E-4D, d))));
                    left = 0.0D;
                } else {
                    tail.add(nxt);
                    left -= d;
                }
                cur = nxt;
            }
            if (tail.size() >= 2) {
                java.util.Collections.reverse(tail);
                Vec3[] p = tail.toArray(new Vec3[0]);
                double[] w = new double[p.length];
                for (int i = 0; i < p.length; i++) {
                    double u = (i + 1.0D) / p.length;
                    w[i] = 0.85D * Math.sqrt(u);
                }
                PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 1.6D), 0.2F * a, RIM);
                PlumVfx.strip(v, pose, camera, p, w, 0.6F * a, BODY);
                PlumVfx.strip(v, pose, camera, p, PlumVfx.scale(w, 0.35D), 0.95F * a, CORE);
            }
        }
        // Укол: короткая толстая вспышка клинка у груди цели.
        float th = t - RushRules.thrust(c.wrapSince());
        if (th > -1.0F && th < 4.0F && pts.size() >= 2) {
            Vec3 end = pts.get(pts.size() - 1);
            Vec3 dir = end.subtract(pts.get(0));
            dir = new Vec3(dir.x, 0.0D, dir.z);
            if (dir.lengthSqr() > 1.0E-4D) {
                dir = dir.normalize();
                float ta = (float) PlumVfx.curve(th, -1.0, 0.0, 0.0, 1.0, 4.0, 0.0);
                Vec3 mid = end.add(dir.scale(0.6D)).add(0.0D, 0.2D, 0.0D);
                Vec3[] q = {mid.subtract(dir.scale(0.9D)), mid, mid.add(dir.scale(1.3D))};
                double[] qw = {0.0D, 0.6D, 0.0D};
                PlumVfx.strip(v, pose, camera, q, PlumVfx.scale(qw, 2.0D), 0.25F * ta, RIM);
                PlumVfx.strip(v, pose, camera, q, qw, 0.95F * ta, CORE);
            }
        }
    }

    private RushVfx() {
    }
}
