package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.CameraShakeHandler;
import io.github.verycooltimo.murim.client.ClientAuraState;
import io.github.verycooltimo.murim.network.ScatterPayload;
import io.github.verycooltimo.murim.network.TechniqueEventPayload;
import io.github.verycooltimo.murim.technique.ScatterRules;
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
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import static io.github.verycooltimo.murim.client.vfx.PlumVfx.COLD;
import static io.github.verycooltimo.murim.client.vfx.PlumVfx.EDGE;

/**
 * Рассеяние Цветущей Сливы (Меч 24 Движений; рефы «24 blossoms technique/full» 01–19, шкала —
 * {@link ScatterRules}). Концентрация: остаточный образ дрожит, из тела торчат копии, рывками
 * расходятся и возвращаются → синий огонь, белые петли, лепестки стягиваются в скопления → прыжок
 * в сторону, клоны выстреливают из скоплений → каждый клон рубит хордами сквозь цель (урон — сервер,
 * по пути клона) → взмах мастера → вихрь Рассеяния. Всё — симуляция частиц и лент; клоны — копии
 * модели игрока в синем пламени. Импакт-кадр, тряска, дым — только по факту попадания.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class ScatterVfx {

    private static final ResourceLocation TECHNIQUE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "twenty_four_plum_scatter");
    /** Синий огонь (full-04): ядро #42DBFF, внешняя ступень #4772E8, холодный край #79DCFF. */
    private static final VfxColour FIRE_CORE = hex(0x42DBFF);
    private static final VfxColour FIRE_OUT = hex(0x4772E8);
    private static final VfxColour FIRE_EDGE = hex(0xB6FFFF);
    /** Лента удара (full-16): ядро белое, кайма #FF75AB / #C72B6B. */
    private static final VfxColour SLASH_RIM = hex(0xC72B6B);
    private static final VfxColour SLASH_BODY = hex(0xFF75AB);
    private static final VfxColour WHITE = hex(0xFFFFFF);
    /** Разрез клона (codex 03.10): центр #FFF8FC, края #EC729B. */
    private static final VfxColour SLASH_CORE = hex(0xFFF8FC);
    private static final VfxColour SLASH_EDGE = hex(0xEC729B);
    /** Рассеяние (full-19): розовая среда #EC709B, кайма #FFB6DE. */
    private static final VfxColour SCATTER_PINK = hex(0xEC709B);
    private static final VfxColour SCATTER_RIM = hex(0xFFB6DE);
    private static final VfxColour BLOOD = hex(0xE83F71);
    /** Осколки (full-14…16): серые пластины #797C88 с белой кромкой. */
    private static final VfxColour SHARD = hex(0x8A8D99);
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

    private static void fstrip(VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Vec3[] p, double[] w, float alpha, VfxColour col) {
        float[] a = new float[p.length];
        for (int i = 0; i < p.length; i++) {
            a[i] = alpha * near(p[i], camera);
        }
        PlumVfx.stripVar(v, pose, camera, p, w, a, col);
    }

    // ------------------------------------------------------------------ частицы

    private static final class Mote {
        static final int PETAL = 0;
        static final int WIND = 1;
        static final int SPARK = 2;
        static final int SHARD = 3;
        static final int FLAME = 4;
        static final int STREAK = 5;
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
        double lift;
        /** Тон лепестка: 0 — белый, 1 — розовый, 2 — алый (full-12). */
        int tone = 1;
        /** Скопление, к которому лепесток стягивается до прыжка (−1 — свободный). */
        int cluster = -1;

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

    /** Застывшая копия тела (или только руки): дрожь, торчащие копии, след прыжка. */
    private record Ghost(Vec3 pos, float yaw, int born, int life, PartPose[] pose, boolean armOnly, int rgb, float alpha) {
    }

    /** Белая дуга в пространстве вокруг цели (full-15): растёт по плоскости и гаснет. */
    private record Arc(Vec3 centre, Vec3 u, Vec3 v, double radius, double a0, double sweep, int born, int life, double width) {
    }

    /** Спиральная лента вихря (full-19): голова уходит наружу, хвост — та же спираль в прошлом. */
    private record Spiral(Vec3 centre, Vec3 u, Vec3 v, double a0, int sign, double rMax, double rise, int born, int life, double width,
                          double turns, boolean white) {
        Vec3 at(double age) {
            double k = Mth.clamp(age / life, 0.0D, 1.0D);
            double e = 1.0D - (1.0D - k) * (1.0D - k);
            double r = 0.4D + (rMax - 0.4D) * e;
            double a = a0 + sign * turns * Math.PI * 2.0D * e;
            return centre.add(u.scale(Math.cos(a) * r)).add(v.scale(Math.sin(a) * r)).add(0.0D, rise * k, 0.0D);
        }
    }

    /** Звёздная вспышка (full-19) и цветок света: живут несколько тиков. */
    private record Star(Vec3 pos, int born, int life, double size, boolean flower) {
    }

    private record Ring(Vec3 centre, int born, double rMax, int life, double width) {
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
        Vec3 centre;
        Vec3 jump = Vec3.ZERO;
        double base;
        boolean grounded = true;
        /** Рысканье мастера (как у сущности, градусы): по нему стоят скопления. */
        float facing;
        boolean facingKnown;
        int targetId = -1;
        ResourceLocation skin;
        PartPose[] basePose;
        PartPose[] livePose;
        boolean caption;
        int finalHit = -1;
        Vec3 finalAt;
        int scatterHit = -1;
        final List<Mote> motes = new ArrayList<>();
        final List<Puff> puffs = new ArrayList<>();
        final List<Ghost> ghosts = new ArrayList<>();
        final List<Arc> arcs = new ArrayList<>();
        final List<Spiral> spirals = new ArrayList<>();
        final List<Star> stars = new ArrayList<>();
        final List<Ring> rings = new ArrayList<>();
        /** Белые черты удара: {a, середина, b, (тик рождения, сила, 0)}. */
        final List<Vec3[]> cores = new ArrayList<>();
        /** Путь мастера во взмахе (full-18) на высоте груди. */
        final List<Vec3> dash = new ArrayList<>();
        /** Клинки клонов этого кадра (рука → острие), собираются при рисовании моделей. */
        final List<Vec3[]> blades = new ArrayList<>();
        /** Острие клинка каждого клона по тикам — след удара. */
        final List<List<Vec3>> tips = new ArrayList<>();
        final boolean[] burst = new boolean[6];

        Cast(int entityId, int layer) {
            this.entityId = entityId;
            this.layer = layer;
            this.start = clientTicks;
            this.random = new Random(entityId * 6151L + clientTicks);
            this.density = ScatterRules.density(layer);
            this.scale = ScatterRules.scale(layer);
            for (int i = 0; i < 6; i++) {
                tips.add(new ArrayList<>());
            }
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

        /** Скопление {@code i}: вокруг мастера до прыжка (по живому положению), после — по точке старта. */
        Vec3 cluster(Vec3 feet, int i) {
            return ScatterRules.cluster(feet, Math.toRadians(facing + 90.0D), i);
        }

        /** Центр корпуса клона {@code i} в тик {@code t}. */
        Vec3 clone(int i, double t) {
            return ScatterRules.clone(cluster(origin, i), jump, centre, base, layer, i, t, grounded);
        }

        double dissolveAt() {
            if (ScatterRules.scatter(layer)) {
                return ScatterRules.SCATTER - 2;
            }
            if (ScatterRules.finalSwing(layer)) {
                return ScatterRules.FINAL;
            }
            return ScatterRules.strokeStart(layer, ScatterRules.clones(layer) - 1, ScatterRules.strokes(layer) - 1) + ScatterRules.PASS + 8;
        }
    }

    // ------------------------------------------------------------------ события

    public static void onTechniqueEvent(TechniqueEventPayload payload) {
        if (!TECHNIQUE.equals(payload.techniqueId())) {
            return;
        }
        if (payload.event() == TechniqueEventPayload.Event.CANCELLED) {
            // Сорвали в концентрации: копии и лепестки рассыпаются, клонов не будет.
            for (Cast c : CASTS) {
                if (c.entityId == payload.sourceId() && !c.released) {
                    for (Mote m : c.motes) {
                        m.cluster = -1;
                    }
                    c.start -= 10000;
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
        if (c.layer >= 3) {
            // Концентрация: слабая синяя аура, целиком огонь — с тика FIRE.
            ClientAuraState.techniqueAura(c.entityId, 3, 0, ScatterRules.FIRE + 2);
        }
    }

    public static void onScatter(ScatterPayload p) {
        Cast c = null;
        for (Cast x : CASTS) {
            if (x.entityId == p.entityId()) {
                c = x;
            }
        }
        Minecraft mc = Minecraft.getInstance();
        if (p.stage() == ScatterPayload.RELEASE) {
            if (c == null) {
                c = new Cast(p.entityId(), p.layer());
                CASTS.add(c);
            }
            c.start = clientTicks - ScatterRules.RELEASE;
            c.released = true;
            c.origin = p.a();
            c.centre = p.b();
            c.base = p.base();
            int side = (p.arg() & 1) != 0 ? 1 : -1;
            c.grounded = (p.arg() & 2) != 0;
            c.facing = p.arg() >> 2;
            c.facingKnown = true;
            c.targetId = p.targetId();
            c.jump = ScatterRules.jump(c.origin, c.centre, side);
            release(c, mc);
            return;
        }
        if (c == null) {
            return;
        }
        if (p.stage() == ScatterPayload.HIT) {
            strokeHit(c, p.a(), p.arg() / 8, mc);
        } else if (p.stage() == ScatterPayload.FINAL_HIT) {
            finalHit(c, p.a(), mc);
        } else if (p.stage() == ScatterPayload.SCATTER_HIT) {
            scatterHit(c, p.a(), mc);
        }
    }

    // ------------------------------------------------------------------ фазы

    /** Прыжок (full-10): скопления взрываются лепестками, из них выходят клоны; пыль, ветер. */
    private static void release(Cast c, Minecraft mc) {
        if (c.layer >= 3) {
            ClientAuraState.techniqueAura(c.entityId, 3 + Math.min(3, c.layer / 2), 1, ScatterRules.END - ScatterRules.RELEASE);
        }
        if (mc.level != null && mc.level.getEntity(c.entityId) instanceof AbstractClientPlayer player
                && mc.getEntityRenderDispatcher().getRenderer(player) instanceof PlayerRenderer renderer) {
            c.skin = player.getSkin().texture();
            c.basePose = capture(renderer.getModel());
            if (ScatterRules.afterimages(c.layer)) {
                // Остаточный образ на месте толчка.
                c.ghosts.add(new Ghost(player.position(), bodyYaw(player), clientTicks, 10, c.basePose, false, 0xBFD8FF, 0.5F));
            }
        }
        dust(c, c.origin, c.n(12) + 3, 0.22D);
        Vec3 back = c.jump.lengthSqr() > 1.0E-6D ? c.jump.normalize().scale(-1.0D) : Vec3.ZERO;
        for (int i = 0; i < Math.max(2, c.n(6)); i++) {
            Vec3 at = c.origin.add((c.random.nextDouble() - 0.5D) * 1.2D, 0.3D + 1.2D * c.random.nextDouble(), (c.random.nextDouble() - 0.5D) * 1.2D);
            wind(c, at, back.scale(0.3D).add(c.random.nextGaussian() * 0.05D, 0.02D, c.random.nextGaussian() * 0.05D), 12, 0.07D);
        }
        // Узлы раскрываются по одному — в момент выхода своего клона (assault → burstCluster).
        if (c.own()) {
            SpeedLines.directional(0.0F, 0.35F, 5, SpeedLines.WHITE);
        }
        if (mc.player != null) {
            mc.player.level().playLocalSound(c.origin.x, c.origin.y, c.origin.z, net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP,
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.25F, false);
        }
    }

    /** Узел {@code i} раскрывается: лепестки направленным конусом по ходу выхода клона, вспышка. */
    private static void burstCluster(Cast c, int i) {
        Vec3 cl = c.cluster(c.origin, i);
        Vec3 aim = c.clone(i, ScatterRules.exit(i) + 2).subtract(cl);
        aim = aim.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 1.0D, 0.0D) : aim.normalize();
        for (Mote m : c.motes) {
            if (m.cluster == i) {
                Vec3 out = m.pos.subtract(cl);
                out = out.lengthSqr() < 1.0E-6D ? aim : out.normalize();
                m.vel = aim.scale(0.3D + 0.25D * c.random.nextDouble()).add(out.scale(0.08D));
                m.cluster = -1;
                m.drag = 0.88D;
                m.turbulence = 0.01D;
            }
        }
        if (ScatterRules.petals(c.layer)) {
            for (int k = 0; k < c.n(10); k++) {
                Vec3 d = aim.add(c.random.nextGaussian() * 0.35D, c.random.nextGaussian() * 0.35D, c.random.nextGaussian() * 0.35D).normalize();
                Mote m = petal(c, cl.add(d.scale(0.2D)), d.scale(0.2D + 0.15D * c.random.nextDouble()), 24 + c.random.nextInt(14));
                m.tone = c.random.nextInt(3) == 0 ? 2 : 1;
                m.turbulence = 0.01D;
                c.motes.add(m);
            }
        }
        c.cores.add(new Vec3[] {cl.subtract(aim.scale(0.5D)), cl, cl.add(aim.scale(0.9D)), new Vec3(clientTicks, 1, 0)});
        c.stars.add(new Star(cl, clientTicks, 2, 0.4D, false));
    }

    /** Удар клона попал: белые черты, осколки, лепестки, короткая тряска рядом. */
    private static void strokeHit(Cast c, Vec3 at, int clone, Minecraft mc) {
        c.stars.add(new Star(at, clientTicks, 2, 0.55D, false));
        for (int i = 0; i < 4; i++) {
            Vec3 d = new Vec3(c.random.nextGaussian(), c.random.nextGaussian() * 0.8D, c.random.nextGaussian()).normalize();
            double r = 0.5D + 0.5D * c.random.nextDouble();
            c.cores.add(new Vec3[] {at.subtract(d.scale(r)), at, at.add(d.scale(r)), new Vec3(clientTicks, 1, 0)});
        }
        shards(c, at, c.n(8) + 2, 0.35D);
        if (ScatterRules.petals(c.layer)) {
            for (int i = 0; i < c.n(6); i++) {
                Vec3 v = new Vec3(c.random.nextGaussian(), c.random.nextGaussian() * 0.6D + 0.3D, c.random.nextGaussian()).normalize()
                        .scale(0.12D + 0.18D * c.random.nextDouble());
                Mote m = petal(c, at, v, 26 + c.random.nextInt(16));
                m.tone = c.random.nextInt(3) == 0 ? 2 : c.random.nextInt(3) == 0 ? 0 : 1;
                m.turbulence = 0.008D;
                c.motes.add(m);
            }
        }
        if (c.grounded && c.centre != null) {
            dust(c, new Vec3(c.centre.x, c.centre.y - 1.0D, c.centre.z), 3, 0.2D);
        }
        if (mc.player != null && mc.player.position().distanceTo(at) < 16.0D) {
            CameraShakeHandler.quake(c.own() ? 0.22F : 0.15F, 4);
            mc.player.level().playLocalSound(at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_STRONG,
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.55F, 1.2F + 0.08F * clone, false);
        }
    }

    /** Взмах мастера попал (full-18): белая вспышка 1–2 тика, две острые ленты, лепестки. */
    private static void finalHit(Cast c, Vec3 at, Minecraft mc) {
        c.finalHit = clientTicks;
        c.finalAt = at;
        for (int i = 0; i < 2; i++) {
            Vec3 d = new Vec3(c.random.nextGaussian(), 0.6D * (i == 0 ? 1 : -1), c.random.nextGaussian()).normalize();
            c.cores.add(new Vec3[] {at.subtract(d.scale(1.8D)), at, at.add(d.scale(1.8D)), new Vec3(clientTicks, 2, 0)});
        }
        shards(c, at, c.n(14) + 3, 0.45D);
        if (ScatterRules.petals(c.layer)) {
            for (int i = 0; i < c.n(26); i++) {
                Vec3 v = new Vec3(c.random.nextGaussian(), c.random.nextGaussian() * 0.6D + 0.2D, c.random.nextGaussian()).normalize()
                        .scale(0.15D + 0.25D * c.random.nextDouble());
                Mote m = petal(c, at, v, 30 + c.random.nextInt(20));
                m.tone = c.random.nextInt(3) == 0 ? 2 : 1;
                m.turbulence = 0.008D;
                c.motes.add(m);
            }
        }
        if (c.own()) {
            SpeedLines.radial(0.5F, 0.5F, 0.55F, 5, SpeedLines.WHITE);
            TechniqueCaption.impact(0.5F);
        }
        if (mc.player != null && mc.player.position().distanceTo(at) < 20.0D) {
            CameraShakeHandler.quake(c.own() ? 0.5F : 0.35F, 8);
            mc.player.level().playLocalSound(at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP,
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.75F, false);
        }
    }

    /** Рассеяние попало: импакт-кадр, сильная тряска, дым манхвы, кольца по земле. */
    private static void scatterHit(Cast c, Vec3 at, Minecraft mc) {
        c.scatterHit = clientTicks;
        Vec3 ground = c.grounded ? new Vec3(at.x, at.y - 1.0D, at.z) : at;
        if (c.own()) {
            ImpactFrames.trigger(at);
            SpeedLines.radial(0.5F, 0.5F, 0.7F, 7, SpeedLines.WHITE);
            TechniqueCaption.impact(1.0F);
        }
        for (int i = 0; i < 10; i++) {
            Vec3 d = new Vec3(c.random.nextGaussian(), c.random.nextGaussian() * 0.7D, c.random.nextGaussian()).normalize();
            double r = 1.6D + 1.4D * c.random.nextDouble();
            c.cores.add(new Vec3[] {at.subtract(d.scale(r)), at, at.add(d.scale(r)), new Vec3(clientTicks, 2, 0)});
        }
        shards(c, at, c.n(30) + 4, 0.55D);
        if (c.grounded) {
            c.rings.add(new Ring(ground.add(0.0D, 0.05D, 0.0D), clientTicks, 5.5D * c.scale, 14, 0.3D));
            c.rings.add(new Ring(ground.add(0.0D, 0.06D, 0.0D), clientTicks + 2, 7.0D * c.scale, 10, 0.12D));
            smoke(c, ground);
        }
        float distance = mc.player == null ? 99.0F : (float) mc.player.position().distanceTo(at);
        if (mc.player != null && distance < 24.0F) {
            float q = distance < 8.0F ? 1.0F : 1.0F - (distance - 8.0F) / 16.0F;
            CameraShakeHandler.quake(Math.max(q, c.own() ? 0.85F : 0.0F), 16);
            mc.player.level().playLocalSound(at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(),
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.6F, 1.5F, false);
            mc.player.level().playLocalSound(at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP,
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.55F, false);
        }
    }

    /** Дым манхвы: низкий вал одной массой, потом встаёт облако (как у Ливня). */
    private static void smoke(Cast c, Vec3 ground) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.getBlockState(BlockPos.containing(ground.add(0.0D, -0.2D, 0.0D))).isAir()) {
            return;
        }
        Entity caster = mc.level.getEntity(c.entityId);
        int n = Math.max(8, c.n(26));
        double r = 2.8D * c.scale;
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2.0D * i / n + c.random.nextDouble() * 0.3D;
            Vec3 out = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
            Vec3 at = ground.add(out.scale(r * (0.45D + 0.4D * c.random.nextDouble())));
            if (caster != null && at.distanceTo(caster.position()) < 2.8D) {
                continue;
            }
            boolean hollow = i % 3 == 0;
            double size = (0.5D + 1.0D * Math.pow(c.random.nextDouble(), 1.5D)) * (hollow ? 1.25D : 1.0D) * c.scale;
            Puff bank = new Puff(at.add(0.0D, size * 0.45D, 0.0D), out.scale(0.12D + 0.24D * c.random.nextDouble())
                    .add(0.0D, 0.01D * c.random.nextDouble(), 0.0D), 32 + c.random.nextInt(16), c.random.nextInt(16), size, true,
                    hollow ? 0.56F : 0.84F + 0.12F * c.random.nextFloat(), (float) (c.random.nextDouble() * 6.28D));
            bank.delay = 2 + c.random.nextInt(6);
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

    private static void shards(Cast c, Vec3 at, int n, double speed) {
        for (int i = 0; i < n; i++) {
            Vec3 d = new Vec3(c.random.nextGaussian(), c.random.nextGaussian() * 0.7D + 0.15D, c.random.nextGaussian()).normalize();
            Mote m = new Mote(at, d.scale(speed * (0.5D + 0.7D * c.random.nextDouble())), 8 + c.random.nextInt(5), Mote.SHARD,
                    c.random.nextInt(4), (float) ((c.random.nextDouble() - 0.5D) * 0.9D), 0.1D + 0.12D * c.random.nextDouble(), 1);
            m.drag = 0.86D;
            m.gravity = 0.02D;
            c.motes.add(m);
        }
    }

    private static Mote petal(Cast c, Vec3 at, Vec3 vel, int life) {
        // Размер: в основном мелкие (0,08–0,14), каждый шестой — крупный «передний план» (до 0,3).
        double size = c.random.nextInt(10) == 0 ? 0.13D + 0.06D * c.random.nextDouble() : 0.045D + 0.035D * c.random.nextDouble();
        return new Mote(at, vel, life, Mote.PETAL, c.random.nextInt(4), (float) ((c.random.nextDouble() - 0.5D) * 0.6D), size, 1);
    }

    private static void wind(Cast c, Vec3 at, Vec3 vel, int life, double size) {
        Mote m = new Mote(at, vel, life, Mote.WIND, 0, 0.0F, size, 10);
        m.drag = 0.9D;
        m.turbulence = 0.03D;
        c.motes.add(m);
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

    /** Язык синего огня на теле клона: поднимается, закручивается, живёт 6–9 тиков. */
    private static void flame(Cast c, Vec3 at, Vec3 drift) {
        Mote m = new Mote(at, drift.add(c.random.nextGaussian() * 0.01D, 0.02D, c.random.nextGaussian() * 0.01D),
                6 + c.random.nextInt(4), Mote.FLAME, 0, 0.0F, 0.06D + 0.05D * c.random.nextDouble(), 5);
        m.drag = 0.86D;
        m.lift = 0.012D;
        m.turbulence = 0.02D;
        c.motes.add(m);
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

    private static float bodyYaw(Entity e) {
        return e instanceof LivingEntity le ? le.yBodyRot : e.getYRot();
    }

    private static Vec3 forward(float yaw) {
        Vec3 f = Vec3.directionFromRotation(0.0F, yaw);
        return new Vec3(f.x, 0.0D, f.z).normalize();
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
            if (e != null && t >= 0 && t < 400) {
                body(c, e, t, mc);
            }
            if (c.released && t >= 0) {
                assault(c, e, t, mc);
            }
            tickMotes(c, e, t);
            if (t > ScatterRules.END + 40 || t > 420 || t < -100) {
                it.remove();
            }
        }
    }

    /** Концентрация: дрожь, торчащие копии, рывки в стороны, синий огонь, петли, сбор лепестков. */
    private static void body(Cast c, Entity e, int t, Minecraft mc) {
        float yaw = bodyYaw(e);
        if (!c.released) {
            c.facing = yaw;
        }
        Vec3 f = forward(yaw);
        Vec3 side = new Vec3(-f.z, 0.0D, f.x);
        Vec3 feet = e.position();
        Vec3 chest = feet.add(0.0D, 1.25D, 0.0D);
        PlayerRenderer renderer = e instanceof AbstractClientPlayer p
                && mc.getEntityRenderDispatcher().getRenderer(p) instanceof PlayerRenderer r ? r : null;
        if (renderer != null && t <= ScatterRules.RELEASE + 2) {
            c.livePose = capture(renderer.getModel());
            if (c.skin == null) {
                c.skin = ((AbstractClientPlayer) e).getSkin().texture();
            }
        }
        if (t == 1) {
            dust(c, feet, c.n(6) + 2, 0.1D);
        }
        // Дрожь (full-02, full-05): сдвинутые контуры тела и руки, из тела торчат копии.
        if (t >= ScatterRules.VIBRATE && t < ScatterRules.SPLIT && ScatterRules.afterimages(c.layer) && c.livePose != null) {
            double a = c.random.nextDouble() * Math.PI * 2.0D;
            double r = 0.05D + 0.09D * c.random.nextDouble();
            c.ghosts.add(new Ghost(feet.add(Math.cos(a) * r, 0.0D, Math.sin(a) * r), yaw + (float) c.random.nextGaussian() * 3.0F,
                    clientTicks, 3, c.livePose, false, 0xC9DDF4, 0.22F));
            c.ghosts.add(new Ghost(feet.add(f.scale(0.05D * c.random.nextGaussian())), yaw + (float) c.random.nextGaussian() * 6.0F,
                    clientTicks, 3, c.livePose, true, 0xE6F0FF, 0.3F));
            if (t % 3 == 0) {
                // Копия «торчит» из тела: дальше и с наклоном наружу.
                int k = (t / 3) % 4;
                Vec3 out = k == 0 ? side : k == 1 ? side.scale(-1.0D) : k == 2 ? side.add(f.scale(-0.8D)).normalize()
                        : side.scale(-1.0D).add(f.scale(-0.8D)).normalize();
                c.ghosts.add(new Ghost(feet.add(out.scale(0.12D + 0.08D * c.random.nextDouble())),
                        yaw + (k % 2 == 0 ? 12.0F : -12.0F), clientTicks, 4, c.livePose, false, 0xB8D4FF, 0.26F));
            }
            if (t % 6 == 0) {
                dust(c, feet, 2, 0.08D);
            }
        }
        // От первого лица тела не видно: дрожь — мелкой встряской камеры, синий огонь — языками
        // по краям кадра (снизу и с боков), поднимающимися вверх.
        if (c.eyes()) {
            if (t >= ScatterRules.VIBRATE && t < ScatterRules.SPLIT && t % 2 == 0 && c.layer >= 2) {
                CameraShakeHandler.quake(0.05F, 2);
            }
            if (t >= ScatterRules.FIRE && t < ScatterRules.RELEASE && c.layer >= 3) {
                Vec3 eye = e.getEyePosition();
                Vec3 look = e.getLookAngle();
                Vec3 right = look.cross(new Vec3(0.0D, 1.0D, 0.0D));
                right = right.lengthSqr() < 1.0E-6D ? side : right.normalize();
                for (int k = 0; k < 2; k++) {
                    double sx = (c.random.nextBoolean() ? 1.0D : -1.0D) * (1.1D + 0.6D * c.random.nextDouble());
                    Vec3 at = eye.add(look.scale(2.6D)).add(right.scale(sx)).add(0.0D, -1.2D + 0.4D * c.random.nextDouble(), 0.0D);
                    Mote m = new Mote(at, new Vec3(0.0D, 0.06D, 0.0D).add(right.scale(sx * 0.01D)), 7 + c.random.nextInt(4), Mote.FLAME, 0, 0.0F,
                            0.05D + 0.04D * c.random.nextDouble(), 5);
                    m.drag = 0.9D;
                    m.lift = 0.01D;
                    m.turbulence = 0.015D;
                    c.motes.add(m);
                }
            }
        }
        // Рывки (full-06): на каждом — пыль, ветер наружу и короткая встряска.
        if ((t == ScatterRules.SPLIT || t == ScatterRules.SPLIT + 3 || t == ScatterRules.SPLIT + 5 || t == ScatterRules.SPLIT_BACK)
                && c.layer >= 1) {
            dust(c, feet, c.n(6) + 2, t == ScatterRules.SPLIT_BACK ? 0.24D : 0.14D);
            int n = Math.max(1, ScatterRules.clones(c.layer));
            for (int i = 0; i < n; i++) {
                double a = Math.toRadians(c.facing + 90.0D + 60.0D * i + 30.0D);
                Vec3 out = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
                Vec3 v = t == ScatterRules.SPLIT_BACK ? out.scale(-0.25D) : out.scale(0.3D);
                wind(c, chest.add(out.scale(t == ScatterRules.SPLIT_BACK ? 1.0D : 0.3D)), v.add(0.0D, 0.02D, 0.0D), 10, 0.06D);
            }
            if (c.own()) {
                CameraShakeHandler.quake(0.12F, 3);
            }
        }
        // Стойка в огне (full-04): широкая низкая стойка — пыль кольцом, ветер по земле.
        if (t == ScatterRules.FIRE) {
            if (c.layer >= 3) {
                ClientAuraState.techniqueAura(c.entityId, 5, 0, ScatterRules.RELEASE - ScatterRules.FIRE + 2);
            } else if (c.layer >= 1) {
                ClientAuraState.techniqueAura(c.entityId, 3, 0, ScatterRules.RELEASE - ScatterRules.FIRE + 2);
            }
            dust(c, feet, c.n(14) + 3, 0.22D);
            for (int i = 0; i < Math.max(2, c.n(8)); i++) {
                double a = c.random.nextDouble() * Math.PI * 2.0D;
                Vec3 out = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
                wind(c, feet.add(out.scale(0.5D)).add(0.0D, 0.15D, 0.0D), out.scale(0.32D), 12, 0.06D);
            }
        }
        // Пик концентрации (full-07): открытые острые белые дуги огибают мастера по наклонным
        // плоскостям — каждая живёт 5 тиков, новые рождаются со сдвигом плоскости.
        if (t >= ScatterRules.FIRE + 4 && t < ScatterRules.RELEASE && c.layer >= 2 && t % 3 == 0) {
            Vec3 n = new Vec3(c.random.nextGaussian(), 1.6D + c.random.nextDouble(), c.random.nextGaussian()).normalize();
            Vec3 u = n.cross(new Vec3(0.0D, 0.0D, 1.0D)).normalize();
            Vec3 w = n.cross(u).normalize();
            c.arcs.add(new Arc(chest, u, w, (0.95D + 0.35D * c.random.nextDouble()) * (0.8D + 0.2D * c.scale),
                    c.random.nextDouble() * Math.PI * 2.0D, Math.toRadians(140.0D + 70.0D * c.random.nextDouble()), clientTicks, 5,
                    0.05D + 0.02D * c.scale));
        }
        // Пик концентрации (full-07): тонкие голубые штрихи наружу от груди.
        if (t >= ScatterRules.FIRE + 4 && t < ScatterRules.RELEASE && c.layer >= 2 && c.random.nextInt(3) == 0) {
            Vec3 d = new Vec3(c.random.nextGaussian(), c.random.nextGaussian() * 0.6D, c.random.nextGaussian()).normalize();
            Mote m = new Mote(chest.add(d.scale(0.5D)), d.scale(0.4D + 0.2D * c.random.nextDouble()), 5, Mote.STREAK, 0, 0.0F,
                    0.025D + 0.02D * c.random.nextDouble(), 4);
            m.drag = 0.95D;
            c.motes.add(m);
        }
        // Лепестки приходят издалека (full-08): по спирали к шести скоплениям вокруг мастера.
        if (ScatterRules.petals(c.layer) && t >= ScatterRules.PETALS && t < ScatterRules.PETALS + 11) {
            int clusters = Math.max(1, ScatterRules.clones(c.layer));
            for (int i = 0; i < c.n(10); i++) {
                double a = c.random.nextDouble() * Math.PI * 2.0D;
                double r = 4.0D + 2.5D * c.random.nextDouble();
                Vec3 at = feet.add(Math.cos(a) * r, 0.3D + 3.2D * c.random.nextDouble(), Math.sin(a) * r);
                Vec3 tan = new Vec3(-Math.sin(a), 0.0D, Math.cos(a)).scale(0.12D);
                Mote m = petal(c, at, tan, 120);
                m.cluster = c.random.nextInt(clusters);
                m.tone = c.random.nextInt(5) == 0 ? 0 : c.random.nextInt(4) == 0 ? 2 : 1;
                m.drag = 0.86D;
                c.motes.add(m);
            }
        }
        // Натяжение (full-09): с груди — линии скорости к центру.
        if (t == ScatterRules.CLUSTER && c.own() && c.layer >= 2) {
            SpeedLines.radial(0.5F, 0.55F, 0.45F, 10, SpeedLines.WHITE);
        }
        if (t == ScatterRules.CLUSTER && c.layer >= 1) {
            dust(c, feet, c.n(8) + 2, 0.12D);
        }
        // Мастер ждёт на месте прыжка, потом опускает меч: пыль и короткий ветер.
        if (t == ScatterRules.END - 14 && c.released) {
            dust(c, feet, c.n(6) + 1, 0.1D);
            wind(c, chest.add(f.scale(0.5D)), f.scale(0.18D).add(0.0D, -0.05D, 0.0D), 12, 0.06D);
        }
        c.ghosts.removeIf(g -> clientTicks - g.born() > g.life());
    }

    /** После прыжка: клоны, их удары, взмах мастера, рассеяние. */
    private static void assault(Cast c, Entity e, int t, Minecraft mc) {
        if (c.centre == null) {
            return;
        }
        int n = ScatterRules.clones(c.layer);
        double dissolve = c.dissolveAt();
        // Прыжок мастера: копии тела по пути, пыль, ветер.
        if (t > ScatterRules.RELEASE && t <= ScatterRules.RELEASE + ScatterRules.JUMP_TICKS + 1 && e != null) {
            if (t % 2 == 0 && c.basePose != null && ScatterRules.afterimages(c.layer)) {
                c.ghosts.add(new Ghost(e.position(), bodyYaw(e), clientTicks, 8, c.basePose, false, 0xFFC8DE, 0.38F));
            }
            if (t == ScatterRules.RELEASE + ScatterRules.JUMP_TICKS + 1) {
                dust(c, e.position(), c.n(10) + 2, 0.2D);
            }
        }
        for (int i = 0; i < n; i++) {
            if (t == ScatterRules.exit(i)) {
                burstCluster(c, i);
            }
            Vec3 p = c.clone(i, t);
            Vec3 prev = c.clone(i, t - 1);
            boolean alive = t < dissolve;
            if (alive && t > ScatterRules.exit(i)) {
                double speed = p.distanceTo(prev);
                // Синий огонь по телу клона (full-15) и лепестки, срывающиеся с него (full-12).
                if (c.layer >= 3) {
                    for (int k = 0; k < Math.max(1, c.n(3)); k++) {
                        Vec3 at = p.add((c.random.nextDouble() - 0.5D) * 0.5D, (c.random.nextDouble() - 0.5D) * 1.6D,
                                (c.random.nextDouble() - 0.5D) * 0.5D);
                        flame(c, at, prev.subtract(p).scale(0.25D));
                    }
                }
                if (ScatterRules.petals(c.layer) && c.random.nextInt(4) == 0) {
                    Mote m = petal(c, p.add((c.random.nextDouble() - 0.5D) * 0.6D, (c.random.nextDouble() - 0.5D) * 1.4D,
                            (c.random.nextDouble() - 0.5D) * 0.6D), prev.subtract(p).scale(0.08D)
                            .add(c.random.nextGaussian() * 0.02D, 0.01D, c.random.nextGaussian() * 0.02D), 22 + c.random.nextInt(12));
                    m.tone = c.random.nextInt(2) == 0 ? 2 : 1;
                    m.turbulence = 0.006D;
                    c.motes.add(m);
                }
                if (speed > 0.6D && c.random.nextInt(4) == 0) {
                    wind(c, p.add(c.random.nextGaussian() * 0.3D, c.random.nextGaussian() * 0.4D, c.random.nextGaussian() * 0.3D),
                            prev.subtract(p).scale(0.25D), 10, 0.05D + 0.03D * c.scale);
                }
            }
            // Начало удара: белая дуга вокруг цели (full-15) и свист.
            for (int j = 0; j < ScatterRules.strokes(c.layer); j++) {
                if (t == (int) Math.ceil(ScatterRules.strokeStart(c.layer, i, j)) && c.layer >= 2) {
                    Vec3[] ch = ScatterRules.stroke(c.centre, c.base, i, j, c.grounded);
                    Vec3 u = ch[1].subtract(ch[0]).normalize();
                    Vec3 w = u.cross(new Vec3(c.random.nextGaussian(), 1.0D, c.random.nextGaussian())).normalize();
                    c.arcs.add(new Arc(c.centre, u, w, (3.0D + 1.2D * c.random.nextDouble()) * c.scale, c.random.nextDouble() * Math.PI * 2.0D,
                            Math.toRadians(150.0D + 80.0D * c.random.nextDouble()), clientTicks, 7, 0.05D + 0.03D * c.scale));
                    if (mc.player != null && mc.player.position().distanceTo(p) < 20.0D) {
                        mc.player.level().playLocalSound(p.x, p.y, p.z, net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP,
                                net.minecraft.sounds.SoundSource.PLAYERS, 0.35F, 1.5F + 0.1F * c.random.nextFloat(), false);
                    }
                }
            }
            // Распад клона: рассыпается лепестками, наследуя направление (full-17).
            if (t == (int) Math.ceil(dissolve) && !c.burst[i]) {
                c.burst[i] = true;
                Vec3 out = p.subtract(c.centre);
                out = out.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 1.0D, 0.0D) : out.normalize();
                for (int k = 0; k < Math.max(4, c.n(14)); k++) {
                    Vec3 body = p.add((c.random.nextDouble() - 0.5D) * 0.5D, (c.random.nextDouble() - 0.5D) * 1.6D, (c.random.nextDouble() - 0.5D) * 0.5D);
                    Mote m = petal(c, body, out.scale(0.1D).add(c.random.nextGaussian() * 0.06D, 0.03D + 0.04D * c.random.nextDouble(),
                            c.random.nextGaussian() * 0.06D), 30 + c.random.nextInt(20));
                    m.tone = c.random.nextInt(3) == 0 ? 2 : 1;
                    m.turbulence = 0.01D;
                    if (ScatterRules.petals(c.layer)) {
                        c.motes.add(m);
                    }
                }
            }
        }
        // Взмах мастера (full-18): путь на высоте груди — огромная лента.
        if (ScatterRules.finalSwing(c.layer) && e != null && t >= ScatterRules.FINAL && t <= ScatterRules.FINAL + ScatterRules.FINAL_TICKS + 1) {
            c.dash.add(e.position().add(0.0D, 1.15D, 0.0D));
            dust(c, e.position(), 2, 0.2D);
            if (c.basePose != null && ScatterRules.afterimages(c.layer) && t % 2 == 1) {
                c.ghosts.add(new Ghost(e.position(), bodyYaw(e), clientTicks, 8, c.basePose, false, 0xFFC8DE, 0.4F));
            }
            Vec3 back = c.dash.size() > 1 ? c.dash.get(0).subtract(c.dash.get(c.dash.size() - 1)).normalize() : Vec3.ZERO;
            wind(c, e.position().add(0.0D, 0.3D + 1.2D * c.random.nextDouble(), 0.0D), back.scale(0.35D), 14, 0.08D + 0.05D * c.scale);
            if (t == ScatterRules.FINAL && mc.player != null) {
                mc.player.level().playLocalSound(e.getX(), e.getY(), e.getZ(), net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP,
                        net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.0F, false);
            }
        }
        // Надпись: секретная форма — «Меч 24 Движений Цветущей Сливы / Рассеяние Цветущей Сливы».
        if (t == ScatterRules.SCATTER + 4 && c.own() && c.layer >= 3 && !c.caption) {
            c.caption = true;
            TechniqueCaption.showSecret(Component.translatable("technique.murim.twenty_four_plum.school"),
                    Component.translatable("technique.murim.twenty_four_plum.scatter"), ScatterRules.END - ScatterRules.SCATTER - 4);
        }
        // Рассеяние (full-19): широкие спиральные ленты вокруг цели, лепестки по спиралям наружу,
        // звёздные вспышки и цветы света. Сам вихрь — приём; удар (кадр, тряска, дым) — по попаданию.
        if (ScatterRules.scatter(c.layer) && t == ScatterRules.SCATTER) {
            Vec3 tiltN = new Vec3(c.random.nextGaussian() * 0.2D, 1.0D, c.random.nextGaussian() * 0.2D).normalize();
            Vec3 u = tiltN.cross(new Vec3(1.0D, 0.0D, 0.0D)).normalize();
            Vec3 v = tiltN.cross(u).normalize();
            // Каждый рукав — в своей наклонной плоскости (±25–70°), дуга 50–100°: вихрь из оборванных
            // росчерков, а не горизонтальный обруч.
            int arms = 5;
            for (int k = 0; k < arms; k++) {
                double az = c.random.nextDouble() * Math.PI * 2.0D;
                double tilt = Math.toRadians(25.0D + 45.0D * c.random.nextDouble()) * (k % 2 == 0 ? 1 : -1);
                Vec3 nrm = new Vec3(Math.sin(tilt) * Math.cos(az), Math.cos(tilt), Math.sin(tilt) * Math.sin(az));
                Vec3 ku = nrm.cross(new Vec3(Math.cos(az + 1.0D), 0.0D, Math.sin(az + 1.0D))).normalize();
                Vec3 kv = nrm.cross(ku).normalize();
                c.spirals.add(new Spiral(c.centre.add(0.0D, -0.3D + 0.3D * k, 0.0D), ku, kv, c.random.nextDouble() * Math.PI * 2.0D,
                        k % 2 == 0 ? 1 : -1, (8.0D + 3.0D * c.random.nextDouble()) * c.scale, 0.0D, clientTicks + k / 2, 7,
                        (0.5D + 0.2D * (k % 2)) * c.scale, (50.0D + 50.0D * c.random.nextDouble()) / 360.0D, k % 3 == 2));
            }
            // Воронка (full-19): три широкие изогнутые ленты вокруг цели, раскручиваются наружу.
            for (int k = 0; k < 3; k++) {
                c.spirals.add(new Spiral(c.centre.add(0.0D, -0.8D + 0.8D * k, 0.0D), u, v, k * Math.PI * 2.0D / 3.0D + 0.4D, 1,
                        (6.0D + 1.5D * k) * c.scale, 1.2D, clientTicks + 1, 10, 0.9D * c.scale, 0.42D, false));
            }
            // Тонкие диагональные разрезы сквозь вихрь (full-19): шесть длинных белых черт.
            for (int k = 0; k < Math.max(4, c.n(11)); k++) {
                double az = c.random.nextDouble() * Math.PI * 2.0D;
                double el = Math.toRadians(25.0D + 45.0D * c.random.nextDouble()) * (c.random.nextBoolean() ? 1 : -1);
                Vec3 d = new Vec3(Math.cos(az) * Math.cos(el), Math.sin(el), Math.sin(az) * Math.cos(el));
                Vec3 off = new Vec3(c.random.nextGaussian(), 0.3D * c.random.nextGaussian(), c.random.nextGaussian()).scale(2.0D);
                double len = (6.0D + 3.0D * c.random.nextDouble()) * c.scale;
                Vec3 mid = c.centre.add(off);
                c.cores.add(new Vec3[] {mid.subtract(d.scale(len)), mid, mid.add(d.scale(len)), new Vec3(clientTicks + k, 3, 0)});
            }
            if (ScatterRules.petals(c.layer)) {
                for (int k = 0; k < c.n(130); k++) {
                    double a = c.random.nextDouble() * Math.PI * 2.0D;
                    Vec3 out = u.scale(Math.cos(a)).add(v.scale(Math.sin(a)));
                    Vec3 tan = tiltN.cross(out).normalize();
                    Mote m = petal(c, c.centre.add(out.scale(0.4D + 0.6D * c.random.nextDouble())).add(0.0D, c.random.nextGaussian() * 0.4D, 0.0D),
                            out.scale(0.3D + 0.3D * c.random.nextDouble()).add(tan.scale(0.3D)).add(0.0D, 0.03D, 0.0D), 40 + c.random.nextInt(30));
                    m.tone = c.random.nextInt(4) == 0 ? 2 : c.random.nextInt(4) == 0 ? 0 : 1;
                    m.drag = 0.94D;
                    m.turbulence = 0.01D;
                    c.motes.add(m);
                }
            }
            if (mc.player != null && mc.player.position().distanceTo(c.centre) < 24.0D) {
                mc.player.level().playLocalSound(c.centre.x, c.centre.y, c.centre.z, net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP,
                        net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.85F, false);
            }
        }
        if (ScatterRules.scatter(c.layer) && t >= ScatterRules.SCATTER && t < ScatterRules.SCATTER + 14 && t % 2 == 0) {
            for (int k = 0; k < 1 + (c.layer >= 7 ? 1 : 0); k++) {
                double a = c.random.nextDouble() * Math.PI * 2.0D;
                double r = (1.5D + 5.0D * c.random.nextDouble()) * c.scale;
                Vec3 at = c.centre.add(Math.cos(a) * r, c.random.nextGaussian() * 1.2D, Math.sin(a) * r);
                c.stars.add(new Star(at, clientTicks, 3, 0.5D + 0.4D * c.random.nextDouble(), k == 1 && t % 4 == 0));
            }
        }
        // Камера мастера: после прыжка оглядывается на цель — удары клонов видны от первого лица.
        if (c.own() && mc.player != null && t >= ScatterRules.RELEASE + ScatterRules.JUMP_TICKS
                && (t < ScatterRules.FINAL || t > ScatterRules.FINAL + ScatterRules.FINAL_TICKS && t < ScatterRules.SCATTER + 20)) {
            Vec3 eye = mc.player.getEyePosition();
            Vec3 to = c.centre.subtract(eye);
            float wantYaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
            float dy = Mth.wrapDegrees(wantYaw - mc.player.getYRot());
            mc.player.setYRot(mc.player.getYRot() + dy * 0.2F);
            float wantPitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(to.y, Math.hypot(to.x, to.z))), -45.0D, 30.0D);
            mc.player.setXRot(mc.player.getXRot() + (wantPitch - mc.player.getXRot()) * 0.12F);
        }
    }

    private static void tickMotes(Cast c, Entity e, int t) {
        Vec3 feet = e != null ? e.position() : c.origin;
        for (Mote m : c.motes) {
            if (m.trail.length > 1) {
                System.arraycopy(m.trail, 0, m.trail, 1, m.trail.length - 1);
                m.trail[0] = m.pos;
                m.count = Math.min(m.trail.length, m.count + 1);
            }
            m.prev = m.pos;
            m.age++;
            Vec3 v = m.vel.scale(m.drag).add(0.0D, m.lift - m.gravity, 0.0D);
            if (m.cluster >= 0 && feet != null) {
                // Стягивание к скоплению по спирали; у скопления — кружение вокруг его центра.
                Vec3 cl = c.cluster(c.released && c.origin != null ? c.origin : feet, m.cluster);
                Vec3 to = cl.subtract(m.pos);
                double d = to.length();
                Vec3 around = new Vec3(-to.z, 0.0D, to.x).normalize();
                double pull = t >= ScatterRules.RELEASE - 14 ? 0.16D : 0.045D;
                v = v.add(to.scale(pull)).add(around.scale(d < 0.35D ? 0.035D : 0.015D)).add(0.0D, d < 0.7D ? Math.sin(m.age * 0.5D + m.cell) * 0.02D : 0.0D, 0.0D);
                if (v.length() > 0.45D) {
                    v = v.normalize().scale(0.45D);
                }
            }
            if (m.turbulence > 0.0D) {
                double ph = m.age * 0.21D + m.cell * 1.7D + m.pos.x * 0.5D;
                v = v.add(Math.sin(ph) * m.turbulence, Math.sin(ph * 1.3D + 1.1D) * m.turbulence * 0.4D, Math.cos(ph * 0.9D + m.pos.z * 0.5D) * m.turbulence);
            }
            m.vel = v;
            m.pos = m.pos.add(v);
        }
        c.motes.removeIf(m -> m.age >= m.life || m.cluster >= 0 && t > ScatterRules.RELEASE + 16);
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
        c.arcs.removeIf(a -> clientTicks - a.born() > a.life());
        c.spirals.removeIf(s -> clientTicks - s.born() > s.life() + 14);
        c.stars.removeIf(s -> clientTicks - s.born() > s.life());
        c.rings.removeIf(r -> clientTicks - r.born() > r.life());
        c.cores.removeIf(x -> clientTicks - x[3].x > (x[3].y > 2.5D ? 14 : x[3].y > 1.5D ? 10 : 6));
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
                c.blades.clear();
                models(mc, c, ps, buffers, t, partial, camera);
                PoseStack.Pose pose = ps.last();
                VertexConsumer air = buffers.getBuffer(MurimRenderTypes.airBand());
                blades(c, pose, camera, air, t);
                arcs(c, pose, camera, air, partial);
                dashTrail(c, pose, camera, air, t);
                spirals(c, pose, camera, air, partial);
                ribbons(c, pose, camera, air, partial);
                impact(c, pose, camera, air, partial);
                buffers.endBatch(MurimRenderTypes.airBand());
                puffs(c, pose, camera, buffers, partial);
                petals(c, pose, camera, buffers, partial, t);
            }
        } finally {
            ps.popPose();
        }
    }

    /** Копии тела (дрожь, торчащие, рывки в стороны), след прыжка и шесть клонов в синем огне. */
    private static void models(Minecraft mc, Cast c, PoseStack ps, MultiBufferSource.BufferSource buffers, float t, float partial, Vec3 camera) {
        if (!(mc.level.getEntity(c.entityId) instanceof AbstractClientPlayer player)) {
            return;
        }
        EntityRenderer<? super AbstractClientPlayer> r = mc.getEntityRenderDispatcher().getRenderer(player);
        if (!(r instanceof PlayerRenderer renderer)) {
            return;
        }
        boolean fpOwn = c.eyes();
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
                    parts[i].visible = !g.armOnly() || i == 4 || i == 5;
                }
                draw(model, ps, buffers.getBuffer(type), g.pos(), g.yaw(), 0.0F, 0.0F, g.alpha() * (1.0F - k), g.rgb());
            }
            for (int i = 0; i < parts.length; i++) {
                parts[i].visible = vis[i];
            }
            // Рывки в стороны (full-06): копии рывками расходятся по шести направлениям и возвращаются.
            if (!fpOwn && c.livePose != null && ScatterRules.afterimages(c.layer) && t >= ScatterRules.SPLIT && t < ScatterRules.SPLIT_BACK + 4) {
                double rad = splitRadius(t);
                int n = Math.max(1, ScatterRules.clones(c.layer));
                Vec3 feet = player.getPosition(partial);
                for (int i = 0; i < n; i++) {
                    double a = Math.toRadians(c.facing + 90.0D + 60.0D * i + 30.0D);
                    Vec3 at = feet.add(Math.cos(a) * rad, 0.0D, Math.sin(a) * rad);
                    load(parts, c.livePose);
                    float lean = (float) (rad * 14.0D);
                    draw(model, ps, buffers.getBuffer(type), at, c.facing + (float) (i % 2 == 0 ? 8.0D : -8.0D) * (float) rad, lean, 0.0F,
                            0.16F + 0.14F * (float) Math.min(1.0D, rad / 0.8D), 0xB8D4FF);
                }
            }
            if (c.released && c.basePose != null && c.centre != null) {
                for (int i = 0; i < ScatterRules.clones(c.layer); i++) {
                    drawClone(mc, c, model, parts, ps, buffers, type, i, t, camera);
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

    /** Радиус рывков: три рывка наружу (0,3 → 0,55 → 0,85 блока), держит, затем резко назад. */
    static double splitRadius(double t) {
        // Рывками, но без удержания: каждый рывок — за тик, между ними медленный дрейф и дрожь.
        double s = t - ScatterRules.SPLIT;
        double span = ScatterRules.SPLIT_BACK - ScatterRules.SPLIT;
        double steps = Math.floor(s / 2.5D) + Mth.clamp((s % 2.5D) / 1.0D, 0.0D, 1.0D);
        double r = Math.min(0.8D, 0.08D + 0.8D * steps / (span / 2.5D)) + 0.04D * Math.sin(t * 2.7D);
        double back = Mth.clamp((t - ScatterRules.SPLIT_BACK) / 3.0D, 0.0D, 1.0D);
        return Math.max(0.0D, r) * (1.0D - back * back);
    }

    private static void drawClone(Minecraft mc, Cast c, PlayerModel<AbstractClientPlayer> model, ModelPart[] parts, PoseStack ps,
                                  MultiBufferSource.BufferSource buffers, RenderType type, int i, float t, Vec3 camera) {
        double dissolve = c.dissolveAt();
        float fadeOut = (float) Mth.clamp(1.0D - (t - dissolve) / 3.0D, 0.0D, 1.0D);
        float fadeIn = (float) Mth.clamp((t - ScatterRules.exit(i)) / 2.0D, 0.0D, 1.0D);
        if (fadeOut <= 0.0F || fadeIn <= 0.0F) {
            return;
        }
        boolean moving = isMoving(c, i, t);
        for (int g = moving ? 1 : 0; g >= 0; g--) {
            float tg = t - g * 1.2F;
            Vec3 p = c.clone(i, tg);
            Vec3 nx = c.clone(i, tg + 0.5D);
            Vec3 d = nx.subtract(p);
            double h = Math.hypot(d.x, d.z);
            float yaw;
            float lean;
            if (d.length() > 0.08D) {
                yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
                lean = (float) Mth.clamp(Math.toDegrees(Math.atan2(-d.y, Math.max(1.0E-3D, h))) + 25.0D, -40.0D, 70.0D);
            } else {
                Vec3 to = c.centre.subtract(p);
                yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
                lean = 8.0F;
            }
            load(parts, c.basePose);
            float roll = clonePose(model, c, i, tg);
            Vec3 feet = p.subtract(0.0D, 0.9D, 0.0D);
            // Ярко — только клоны в замахе/резе: зависшие между ударами притухают, чтобы читались
            // 2–3 атакующих силуэта, а не клубок из шести (codex 03.10).
            float a = (g == 0 ? (striking(c, i, tg) ? 0.9F : 0.42F) : 0.16F) * fadeIn * fadeOut;
            a *= (float) Mth.clamp((p.distanceTo(camera) - 1.5D) / 2.0D, 0.0D, 1.0D);
            // Тело клона — тёмно-синий силуэт с голубой кромкой огня (full-15), шлейф — светлее.
            draw(model, ps, buffers.getBuffer(type), feet, yaw, lean, roll, a, g == 0 ? (fadeOut < 1.0F ? 0xFFD6E6 : 0xE4EEFF) : 0xC9DDF4);
            if (g == 0) {
                c.blades.add(bladeOf(model, feet, yaw, lean, roll));
            }
        }
    }

    private static boolean striking(Cast c, int i, double t) {
        if (t < ScatterRules.strokeStart(c.layer, i, 0) - 3.0D) {
            return true;
        }
        for (int j = 0; j < ScatterRules.strokes(c.layer); j++) {
            double st = ScatterRules.strokeStart(c.layer, i, j);
            if (t >= st - 3.0D && t <= st + ScatterRules.PASS + 1.0D) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMoving(Cast c, int i, double t) {
        return c.clone(i, t).distanceTo(c.clone(i, t - 1.0D)) > 0.25D;
    }

    /**
     * Поза клона: прыжок (ноги поджаты), полёт к цели (вытянут, меч отведён), удар (замах сверху →
     * рез поперёк, корпус закручивается), зависание (рука с мечом вперёд, разворот к цели).
     * Возвращает крен.
     */
    private static float clonePose(PlayerModel<?> m, Cast c, int i, double t) {
        int side = i % 2 == 0 ? 1 : -1;
        float rax;
        float raz;
        float lax;
        float laz;
        float rl;
        float ll;
        float body;
        float roll = 0.0F;
        double jEnd = ScatterRules.exit(i) + ScatterRules.JUMP_TICKS;
        double s0 = ScatterRules.strokeStart(c.layer, i, 0);
        if (t <= jEnd) {
            rax = 0.5F;
            raz = 0.3F;
            lax = 0.6F;
            laz = -0.3F;
            rl = -1.3F;
            ll = -0.5F;
            body = 0.0F;
        } else if (t < s0) {
            double k = Mth.clamp((t - jEnd) / Math.max(1.0D, s0 - jEnd), 0.0D, 1.0D);
            rax = (float) Mth.lerp(k, 0.4D, -2.7D);
            raz = (float) Mth.lerp(k, 0.5D, 0.7D);
            lax = 0.9F;
            laz = -0.2F;
            rl = -0.6F;
            ll = 0.9F;
            body = (float) (0.4D * k);
            roll = (float) (side * 10.0D * Math.sin(Math.PI * k));
        } else {
            int j = 0;
            for (int q = 0; q < ScatterRules.strokes(c.layer); q++) {
                if (t >= ScatterRules.strokeStart(c.layer, i, q)) {
                    j = q;
                }
            }
            double st = ScatterRules.strokeStart(c.layer, i, j);
            double k = (t - st) / ScatterRules.PASS;
            if (k <= 1.0D) {
                // Рез: за 1 тик — замах сверху, дальше рука проходит поперёк вниз.
                double e = Mth.clamp(k * 1.3D, 0.0D, 1.0D);
                e = e * e * (3.0D - 2.0D * e);
                rax = (float) Mth.lerp(e, -2.8D, -0.5D);
                raz = (float) Mth.lerp(e, 0.9D, -0.7D) * (j % 2 == 0 ? 1 : -1);
                body = (float) Mth.lerp(e, 0.6D, -0.6D) * (j % 2 == 0 ? 1 : -1);
                lax = -0.3F;
                laz = -0.6F;
                rl = -0.9F;
                ll = 0.7F;
                roll = (float) (side * 18.0D * Math.sin(Math.PI * e));
            } else {
                // Зависание: меч вперёд, к следующему заходу — снова замах.
                double next = j + 1 < ScatterRules.strokes(c.layer) ? ScatterRules.strokeStart(c.layer, i, j + 1) : st + 40.0D;
                double w = Mth.clamp((t - st - ScatterRules.PASS) / Math.max(1.0D, next - st - ScatterRules.PASS), 0.0D, 1.0D);
                rax = (float) Mth.lerp(w * w, -1.3D, -2.7D);
                raz = (float) Mth.lerp(w, -0.4D, 0.8D);
                lax = -0.4F;
                laz = -0.5F;
                rl = (float) Mth.lerp(w, -1.0D, -0.5D);
                ll = (float) Mth.lerp(w, 0.3D, 0.8D);
                body = (float) Mth.lerp(w, -0.4D, 0.4D);
            }
        }
        float sway = Mth.sin((float) t * 0.7F + i * 1.3F) * 0.06F;
        m.rightArm.xRot = rax + sway;
        m.rightArm.zRot = raz * side;
        m.leftArm.xRot = lax - sway;
        m.leftArm.zRot = laz;
        m.rightLeg.xRot = rl + sway;
        m.leftLeg.xRot = ll - sway;
        m.body.yRot = body * side;
        m.head.yRot = -body * side * 0.5F;
        m.hat.copyFrom(m.head);
        m.jacket.copyFrom(m.body);
        m.rightSleeve.copyFrom(m.rightArm);
        m.leftSleeve.copyFrom(m.leftArm);
        m.rightPants.copyFrom(m.rightLeg);
        m.leftPants.copyFrom(m.leftLeg);
        return roll;
    }

    private static void load(ModelPart[] parts, PartPose[] pose) {
        for (int i = 0; i < parts.length && i < pose.length; i++) {
            parts[i].loadPose(pose[i]);
        }
    }

    /** Как LivingEntityRenderer: поворот, наклон и крен у середины тела, масштаб игрока. */
    private static void transform(PoseStack ps, Vec3 at, float yaw, float lean, float roll) {
        // API: reference/minecraft-src/net/minecraft/client/renderer/entity/LivingEntityRenderer.java#render
        ps.translate(at.x, at.y, at.z);
        ps.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));
        ps.translate(0.0F, 0.9F, 0.0F);
        ps.mulPose(Axis.ZP.rotationDegrees(roll));
        ps.mulPose(Axis.XP.rotationDegrees(-lean));
        ps.translate(0.0F, -0.9F, 0.0F);
        ps.scale(-1.0F, -1.0F, 1.0F);
        ps.scale(0.9375F, 0.9375F, 0.9375F);
        ps.translate(0.0F, -1.501F, 0.0F);
    }

    private static void draw(PlayerModel<?> model, PoseStack ps, VertexConsumer v, Vec3 at, float yaw, float lean, float roll, float alpha, int rgb) {
        if (alpha <= 0.01F) {
            return;
        }
        ps.pushPose();
        try {
            transform(ps, at, yaw, lean, roll);
            int colour = ((int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F) << 24) | rgb;
            model.renderToBuffer(ps, v, 0x00F000F0, OverlayTexture.NO_OVERLAY, colour);
        } finally {
            ps.popPose();
        }
    }

    /**
     * Клинок клона в мире: кисть и острие по позе правой руки. Модель без предмета — меч рисуется
     * лентой ци. API: reference/minecraft-src/net/minecraft/client/model/geom/ModelPart.java#translateAndRotate
     */
    private static Vec3[] bladeOf(PlayerModel<?> model, Vec3 feet, float yaw, float lean, float roll) {
        PoseStack m = new PoseStack();
        transform(m, feet, yaw, lean, roll);
        model.rightArm.translateAndRotate(m);
        Vector3f hand = m.last().pose().transformPosition(new Vector3f(-0.0625F, 0.62F, 0.0F));
        Vector3f tip = m.last().pose().transformPosition(new Vector3f(-0.0625F, 0.7F, -1.3F));
        return new Vec3[] {new Vec3(hand.x(), hand.y(), hand.z()), new Vec3(tip.x(), tip.y(), tip.z())};
    }

    /** Клинки клонов и розово-белые ленты их ударов (full-16): лента идёт за острием по ходу реза. */
    private static void blades(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float t) {
        for (Vec3[] b : c.blades) {
            Vec3[] p = {b[0], b[0].lerp(b[1], 0.5D), b[1]};
            double[] w = {0.035D, 0.03D, 0.0D};
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.4D), 0.25F, FIRE_OUT);
            fstrip(v, pose, camera, p, w, 0.9F, FIRE_EDGE);
        }
        if (!c.released || c.centre == null) {
            return;
        }
        for (int i = 0; i < ScatterRules.clones(c.layer); i++) {
            for (int j = 0; j < ScatterRules.strokes(c.layer); j++) {
                double st = ScatterRules.strokeStart(c.layer, i, j);
                double age = t - st;
                if (age < 0.0D || age > ScatterRules.PASS + 3.0D) {
                    continue;
                }
                // Лента — дуга хорды с выгибом в своей плоскости: прорисовывается вместе с проходом.
                Vec3[] ch = ScatterRules.stroke(c.centre, c.base, i, j, c.grounded);
                Vec3 dir = ch[1].subtract(ch[0]);
                Vec3 bow = dir.cross(new Vec3(0.3D * (j % 2 == 0 ? 1 : -1), 1.0D, 0.2D * (i % 2 == 0 ? 1 : -1))).normalize();
                // Разрез пространства (full-16, 17): длинный (хорда, продлённая на 30 % в обе стороны,
                // 7–9 блоков), почти прямой, острые концы; прорисовывается за проход, держится 4 тика.
                double drawn = Mth.clamp(age / ScatterRules.PASS, 0.0D, 1.0D);
                drawn = 1.0D - Math.pow(1.0D - drawn, 2.2D);
                float fade = (float) Mth.clamp(1.0D - (age - ScatterRules.PASS) / 2.5D, 0.0D, 1.0D);
                Vec3 a0 = ch[0].subtract(dir.scale(0.3D));
                Vec3 a1 = ch[1].add(dir.scale(0.3D));
                int n = 24;
                Vec3[] p = new Vec3[n + 1];
                double[] w = new double[n + 1];
                for (int k = 0; k <= n; k++) {
                    double u = drawn * k / n;
                    p[k] = a0.lerp(a1, u).add(bow.scale(0.25D * Math.sin(Math.PI * u))).add(0.0D, 0.3D, 0.0D);
                    double x = k / (double) n;
                    // Острое к обоим концам: ширина как у клинка, самая широкая треть — у головы.
                    w[k] = (0.12D + 0.1D * c.scale) * Math.pow(Math.sin(Math.PI * Math.min(1.0D, x * 0.98D + 0.01D)), 1.4D) * (0.55D + 0.45D * x);
                }
                boolean pink = ScatterRules.petals(c.layer);
                fstrip(v, pose, camera, p, PlumVfx.scale(w, 1.9D), 0.22F * fade, pink ? SLASH_EDGE : FIRE_OUT);
                fstrip(v, pose, camera, p, w, 0.75F * fade, pink ? SLASH_EDGE : COLD);
                fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.45D), fade, SLASH_CORE);
            }
        }
    }

    /** Белые дуги в пространстве вокруг цели (full-15): тонкие, растут по плоскости. */
    private static void arcs(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Arc a : c.arcs) {
            float age = clientTicks - a.born() + partial;
            double grow = Mth.clamp(age / 3.0D, 0.0D, 1.0D);
            float fade = (float) PlumVfx.curve(age, 0.0, 0.6, 2.0, 1.0, a.life(), 0.0);
            int n = 24;
            Vec3[] p = new Vec3[n + 1];
            double[] w = new double[n + 1];
            for (int i = 0; i <= n; i++) {
                double u = i / (double) n;
                double ang = a.a0() + a.sweep() * grow * u;
                p[i] = a.centre().add(a.u().scale(Math.cos(ang) * a.radius())).add(a.v().scale(Math.sin(ang) * a.radius()));
                w[i] = a.width() * Math.sin(Math.PI * Math.min(1.0D, u * 0.98D + 0.01D));
            }
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.5D), 0.15F * fade, SCATTER_RIM);
            fstrip(v, pose, camera, p, w, 0.9F * fade, WHITE);
        }
    }

    /** Взмах мастера (full-18): широкая розово-белая лента по пути рывка, полумесяц на старте. */
    private static void dashTrail(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float t) {
        if (c.dash.size() < 2) {
            return;
        }
        float a = (float) Mth.clamp((ScatterRules.FINAL + ScatterRules.FINAL_TICKS + 16 - t) / 10.0D, 0.0D, 1.0D);
        if (a <= 0.0F) {
            return;
        }
        Vec3 first = c.dash.get(0);
        Vec3 last = c.dash.get(c.dash.size() - 1);
        Vec3 d = last.subtract(first);
        // Лента продлевается вперёд за мастера: разрез проходит сквозь цель, а не обрывается у плеча.
        int n = 24;
        Vec3[] p = new Vec3[n + 1];
        double[] w = new double[n + 1];
        Vec3 side = d.cross(new Vec3(0.0D, 1.0D, 0.0D));
        side = side.lengthSqr() < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D) : side.normalize();
        for (int i = 0; i <= n; i++) {
            double u = i / (double) n;
            p[i] = first.subtract(d.scale(0.1D)).add(d.scale(1.25D * u)).add(0.0D, 1.6D * Math.cos(Math.PI * 0.5D * u) - 0.9D * u, 0.0D)
                    .add(side.scale(1.2D * Math.sin(Math.PI * u)));
            w[i] = (0.2D + 0.14D * c.scale) * Math.pow(Math.sin(Math.PI * Math.min(1.0D, u * 1.02D + 0.01D)), 0.6D);
        }
        boolean pink = ScatterRules.petals(c.layer);
        fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.8D), 0.1F * a, pink ? SLASH_RIM : COLD);
        fstrip(v, pose, camera, p, PlumVfx.scale(w, 1.6D), 0.32F * a, pink ? SLASH_RIM : COLD);
        fstrip(v, pose, camera, p, w, 0.8F * a, pink ? SLASH_BODY : WIND);
        fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.28D), a, WHITE);
    }

    /** Вихрь Рассеяния (full-19): широкие спиральные ленты, хвост — та же спираль в прошлом. */
    private static void spirals(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Spiral s : c.spirals) {
            double age = clientTicks - s.born() + partial;
            if (age <= 0.0D) {
                continue;
            }
            double head = Math.min(age, s.life());
            double tail = Math.max(0.0D, age - 14.0D);
            if (head <= tail) {
                continue;
            }
            int n = 24;
            Vec3[] p = new Vec3[n + 1];
            double[] w = new double[n + 1];
            for (int i = 0; i <= n; i++) {
                double u = i / (double) n;
                p[i] = s.at(tail + (head - tail) * u);
                w[i] = s.width() * Math.sin(Math.PI * Math.min(1.0D, u * 0.95D + 0.04D)) * (0.4D + 0.6D * u);
            }
            float a = (float) PlumVfx.curve(age / (s.life() + 14.0D), 0.0, 0.0, 0.1, 1.0, 0.65, 0.85, 1.0, 0.0);
            if (s.white()) {
                fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.2D), 0.15F * a, SCATTER_RIM);
                fstrip(v, pose, camera, p, w, 0.9F * a, WHITE);
            } else {
                fstrip(v, pose, camera, p, PlumVfx.scale(w, 1.8D), 0.16F * a, SCATTER_PINK);
                fstrip(v, pose, camera, p, w, 0.55F * a, SCATTER_PINK);
                fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.45D), 0.8F * a, SCATTER_RIM);
                fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.15D), 0.95F * a, WHITE);
            }
        }
    }

    /** Ленты ветра, синие языки огня клонов, штрихи концентрации, осколки. */
    private static void ribbons(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Mote m : c.motes) {
            if (m.kind == Mote.PETAL) {
                continue;
            }
            float life = (m.age + partial) / m.life;
            if (m.kind == Mote.SHARD) {
                // Угловатая пластина (full-14): ромб, повёрнутый со временем, серый с белой кромкой.
                Vec3 at = m.prev.lerp(m.pos, partial);
                double ang = m.cell * 1.3D + (m.age + partial) * m.spin;
                Vec3 ax = new Vec3(Math.cos(ang), Math.sin(ang) * 0.8D, Math.sin(ang * 0.7D)).normalize().scale(m.size * 1.6D);
                Vec3[] p = {at.subtract(ax), at.add(ax.scale(0.2D)), at.add(ax)};
                double[] w = {0.0D, m.size * 0.9D, 0.0D};
                float a = (float) PlumVfx.curve(life, 0.0, 1.0, 0.6, 0.9, 1.0, 0.0);
                fstrip(v, pose, camera, p, w, 0.5F * a, SHARD);
                fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.25D), 0.9F * a, WHITE);
                continue;
            }
            if (m.count < 2) {
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
            float a = (float) PlumVfx.curve(life, 0.0, 0.2, 0.12, 1.0, 0.6, 0.8, 1.0, 0.0);
            if (m.kind == Mote.WIND) {
                fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.0D), 0.1F * a, COLD);
                fstrip(v, pose, camera, p, w, 0.38F * a, WIND);
                fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.25D), 0.85F * a, EDGE);
            } else if (m.kind == Mote.FLAME) {
                // Плоские ступени: внешняя #4772E8, ядро #42DBFF, светлый край — без размытия.
                fstrip(v, pose, camera, p, PlumVfx.scale(w, 1.8D), 0.3F * a, FIRE_OUT);
                fstrip(v, pose, camera, p, w, 0.7F * a, FIRE_CORE);
                fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.3D), 0.9F * a, FIRE_EDGE);
            } else {
                fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.0D), 0.25F * a, FIRE_OUT);
                fstrip(v, pose, camera, p, w, 0.9F * a, FIRE_EDGE);
            }
        }
    }

    /** Белые черты удара, звёздные вспышки, цветы света, кольца по земле. */
    private static void impact(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Vec3[] x : c.cores) {
            float age = (float) (clientTicks - x[3].x + partial);
            if (age < 0.0F) {
                continue;
            }
            boolean big = x[3].y > 1.5D;
            boolean cut = x[3].y > 2.5D;
            float a = (float) PlumVfx.curve(age, 0.0, 1.0, big ? 2.0 : 1.5, 1.0, cut ? 14.0 : big ? 10.0 : 6.0, 0.0);
            double w0 = cut ? 0.06D : big ? 0.14D : x[3].y > 0.5D ? 0.08D : 0.05D;
            Vec3[] p = {x[0], x[1], x[2]};
            double[] w = {0.0D, w0, 0.0D};
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.6D), 0.25F * a, SLASH_BODY);
            fstrip(v, pose, camera, p, w, 0.95F * a, WHITE);
        }
        for (Star s : c.stars) {
            float age = clientTicks - s.born() + partial;
            float a = (float) PlumVfx.curve(age, 0.0, 0.0, 0.8, 1.0, s.life(), 0.0);
            Vec3 to = camera.subtract(s.pos()).normalize();
            Vec3 r = to.cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
            Vec3 u = r.cross(to).normalize();
            double sz = s.size() * (0.7D + 0.3D * a);
            for (Vec3[] ax : new Vec3[][] {{r, u}, {u, r}}) {
                double len = ax[0] == r ? sz : sz * 1.4D;
                Vec3[] p = {s.pos().subtract(ax[0].scale(len)), s.pos(), s.pos().add(ax[0].scale(len))};
                double[] w = {0.0D, 0.06D * s.size(), 0.0D};
                fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.4D), 0.25F * a, SCATTER_PINK);
                fstrip(v, pose, camera, p, w, a, WHITE);
            }
        }
        for (Ring r : c.rings) {
            float age = clientTicks - r.born() + partial;
            float k = Mth.clamp(age / r.life(), 0.0F, 1.0F);
            if (age < 0.0F) {
                continue;
            }
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
            PlumVfx.flatStrip(v, pose, p, PlumVfx.scale(w, 2.0D), 0.25F * a, SCATTER_PINK);
            PlumVfx.flatStrip(v, pose, p, PlumVfx.scale(w, 0.4D), 0.9F * a, WHITE);
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

    /** Лепестки (атлас) и их свечение; скопления до прыжка; вспышки попаданий. */
    private static void petals(Cast c, PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float partial, float t) {
        RenderType pt = MurimRenderTypes.plumPetals();
        VertexConsumer pc = buffers.getBuffer(pt);
        for (Mote m : c.motes) {
            if (m.kind != Mote.PETAL) {
                continue;
            }
            Vec3 at = m.prev.lerp(m.pos, partial);
            if (at.distanceToSqr(camera) < 2.0D) {
                continue;
            }
            float a = Mth.clamp((m.life - m.age - partial) / 10.0F, 0.0F, 1.0F) * Mth.clamp((m.age + partial) / 3.0F, 0.0F, 1.0F)
                    * near(at, camera);
            float[] tint = tint(m.tone);
            // Крупный лепесток у самой камеры не раздувается в пятно на полэкрана.
            double size = m.size > 0.12D ? Mth.lerp(Mth.clamp((Math.sqrt(at.distanceToSqr(camera)) - 2.0D) / 4.0D, 0.0D, 1.0D), 0.08D, m.size) : m.size;
            PlumVfx.petal(pc, pose, camera, at, size * 1.6D, m.cell, (m.age + partial) * m.spin, a, tint[0], tint[1], tint[2]);
        }
        // Цветы света Рассеяния (full-19): пять крупных лепестков вокруг белой точки.
        for (Star s : c.stars) {
            if (!s.flower()) {
                continue;
            }
            float age = clientTicks - s.born() + partial;
            float a = (float) PlumVfx.curve(age, 0.0, 0.0, 1.0, 1.0, s.life() + 2.0, 0.0);
            Vec3 to = camera.subtract(s.pos()).normalize();
            Vec3 r = to.cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
            Vec3 u = r.cross(to).normalize();
            for (int k = 0; k < 5; k++) {
                double ang = k * Math.PI * 2.0D / 5.0D + s.born();
                Vec3 at = s.pos().add(r.scale(Math.cos(ang) * 0.3D)).add(u.scale(Math.sin(ang) * 0.3D));
                PlumVfx.petal(pc, pose, camera, at, 0.32D, k % 4, (float) (ang + Math.PI / 2.0D), a, 1.0F, 0.6F, 0.75F);
            }
        }
        buffers.endBatch(pt);
        RenderType gt = MurimRenderTypes.mote();
        VertexConsumer g = buffers.getBuffer(gt);
        for (Mote m : c.motes) {
            if (m.kind != Mote.PETAL) {
                continue;
            }
            Vec3 at = m.prev.lerp(m.pos, partial);
            if (at.distanceToSqr(camera) > 2.0D) {
                float a = Mth.clamp((m.life - m.age - partial) / 10.0F, 0.0F, 1.0F);
                // Ореол — тонкий и только у мелких: крупный лепесток переднего плана остаётся чётким силуэтом.
                if (m.size < 0.12D) {
                    PlumVfx.glow(g, pose, camera, at, m.size * 1.6D, 0.22F * a * near(at, camera), m.tone == 2 ? BLOOD : m.tone == 0 ? WHITE : SCATTER_RIM);
                }
            }
        }
        // Скопления лепестков перед прыжком (full-09): розовое ядро, пульс напряжения.
        if (ScatterRules.petals(c.layer) && t >= ScatterRules.RELEASE - 16 && t < ScatterRules.exit(5) + 1) {
            Minecraft mc = Minecraft.getInstance();
            Entity e = mc.level.getEntity(c.entityId);
            if (e != null) {
                float a = (float) PlumVfx.curve(t, ScatterRules.RELEASE - 16, 0.0, ScatterRules.RELEASE - 8, 1.0, ScatterRules.RELEASE, 1.0);
                for (int i = 0; i < Math.max(1, ScatterRules.clones(c.layer)); i++) {
                    if (t > ScatterRules.exit(i) + 1) {
                        continue;
                    }
                    Vec3 cl = c.cluster(c.released && c.origin != null ? c.origin : e.getPosition(partial), i);
                    float pulse = 0.75F + 0.25F * Mth.sin(t * 1.3F + i);
                    PlumVfx.glow(g, pose, camera, cl, 0.55D, 0.3F * a * pulse * near(cl, camera), SCATTER_PINK);
                    PlumVfx.glow(g, pose, camera, cl, 0.2D, 0.6F * a * pulse * near(cl, camera), WHITE);
                }
            }
        }
        if (c.finalHit >= 0 && c.finalAt != null) {
            float age = clientTicks - c.finalHit + partial;
            float a = (float) PlumVfx.curve(age, 0.0, 1.0, 2.0, 0.6, 8.0, 0.0);
            if (a > 0.0F) {
                PlumVfx.glow(g, pose, camera, c.finalAt, 0.9D, 0.8F * a, WHITE);
                PlumVfx.glow(g, pose, camera, c.finalAt, 2.0D, 0.25F * a, SCATTER_PINK);
            }
        }
        if (c.scatterHit >= 0 && c.centre != null) {
            float age = clientTicks - c.scatterHit + partial;
            float a = (float) PlumVfx.curve(age, 0.0, 1.0, 3.0, 0.8, 14.0, 0.25, 30.0, 0.0);
            if (a > 0.0F) {
                PlumVfx.glow(g, pose, camera, c.centre, age < 2.0F ? 2.6D : 1.2D + 0.08D * age, 0.6F * a, age < 4.0F ? WHITE : SCATTER_RIM);
                PlumVfx.glow(g, pose, camera, c.centre, 3.0D + 0.1D * age, 0.22F * a, SCATTER_PINK);
            }
        }
        buffers.endBatch(gt);
    }

    private static float[] tint(int tone) {
        return switch (tone) {
            case 0 -> new float[] {1.0F, 0.96F, 0.98F};
            case 2 -> new float[] {0.95F, 0.25F, 0.42F};
            default -> new float[] {1.0F, 0.62F, 0.76F};
        };
    }

    private ScatterVfx() {
    }
}
