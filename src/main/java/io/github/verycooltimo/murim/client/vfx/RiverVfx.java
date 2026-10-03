package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.CameraShakeHandler;
import io.github.verycooltimo.murim.client.ClientAuraState;
import io.github.verycooltimo.murim.network.RiverPayload;
import io.github.verycooltimo.murim.network.TechniqueEventPayload;
import io.github.verycooltimo.murim.technique.RiverRules;
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
 * «Опадающие Лепестки, Перекрывающие Реку» (рефы river-01…16, шкала — {@link RiverRules}). Всё —
 * симуляция: ленты с хвостами, лепестки со скоростью и состоянием (рождение → поле → вихрь → поток →
 * узел → разлёт → опадание). Лепестки рождаются там, где прошёл дрожащий клинок (кости из
 * {@link BoneAnchorLayer}); узел, взрыв, импакт-кадр, тряска и дым — только по факту контакта/попадания.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class RiverVfx {

    private static final ResourceLocation TECHNIQUE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "twenty_four_plum_river");

    /** Голубой предвыброс (r01): #477AEF / #65C9FF / #9BFFF4 / #F4FFFF. */
    private static final VfxColour BLUE_DEEP = hex(0x477AEF);
    private static final VfxColour BLUE = hex(0x65C9FF);
    private static final VfxColour BLUE_TEAL = hex(0x9BFFF4);
    private static final VfxColour BLUE_WHITE = hex(0xF4FFFF);
    /** Красная оболочка и нить (r02–r07): #F83249 / #EC244A, розовый #FF86C6 / #FF5B99. */
    private static final VfxColour RED = hex(0xF83249);
    private static final VfxColour RED_SPIRAL = hex(0xEC244A);
    private static final VfxColour PINK = hex(0xFF86C6);
    private static final VfxColour PINK_HOT = hex(0xFF5B99);
    private static final VfxColour PINK_LIGHT = hex(0xFFC2DB);
    /** Лепестки и свет (r08–r16): белый, светло-розовый, малиновый. */
    private static final VfxColour WHITE = hex(0xFFFFFF);
    private static final VfxColour PETAL_PINK = hex(0xFFC7DC);
    private static final VfxColour CRIMSON = hex(0xE92C65);
    private static final VfxColour BURST_PINK = hex(0xFFC4DF);
    private static final VfxColour BURST_RIM = hex(0xFF397A);
    private static final VfxColour WIND = hex(0xE8EDF1);

    private static final List<Cast> CASTS = new ArrayList<>();
    private static int clientTicks;

    private static VfxColour hex(int rgb) {
        return new VfxColour(((rgb >> 16) & 0xFF) / 255.0F, ((rgb >> 8) & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F);
    }

    /** Вблизи камеры тает: от первого лица ничего не закрывает экран. */
    private static float near(Vec3 at, Vec3 camera) {
        return (float) Mth.clamp((at.distanceTo(camera) - nearFrom) / nearSpan, 0.0D, 1.0D);
    }

    /** Порог таяния у камеры: от первого лица шире — лепестки не застилают экран. */
    private static double nearFrom = 0.9D;
    private static double nearSpan = 1.3D;

    private static void fstrip(VertexConsumer v, PoseStack.Pose pose, Vec3 camera, Vec3[] p, double[] w, float alpha, VfxColour col) {
        float[] a = new float[p.length];
        for (int i = 0; i < p.length; i++) {
            a[i] = alpha * near(p[i], camera);
        }
        PlumVfx.stripVar(v, pose, camera, p, w, a, col);
    }

    private static double smooth(double k) {
        k = Mth.clamp(k, 0.0D, 1.0D);
        return k * k * (3.0D - 2.0D * k);
    }

    // ------------------------------------------------------------------ частицы

    /** Лента с хвостом (ветер, голубой поток, красный язык оболочки), искра, осколок взрыва. */
    private static final class Mote {
        static final int WIND = 0;
        static final int BLUE = 1;
        static final int FLICK = 2;
        static final int SPARK = 3;
        static final int SHARD = 4;
        Vec3 pos;
        Vec3 prev;
        Vec3 vel;
        int age;
        final int life;
        final int kind;
        final double size;
        final Vec3[] trail;
        int count;
        double drag = 0.92D;
        double gravity;
        double turbulence;
        final int cell;

        Mote(Vec3 pos, Vec3 vel, int life, int kind, double size, int trail, int cell) {
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.life = life;
            this.kind = kind;
            this.size = size;
            this.trail = new Vec3[Math.max(1, trail)];
            this.cell = cell;
        }
    }

    /** Лепесток техники: проходит состояния от рождения у клинка до опадания. */
    private static final class Petal {
        static final int FIELD = 0;
        static final int GATHER = 1;
        static final int STREAM = 2;
        static final int NODE = 3;
        static final int FREE = 4;
        int state;
        Vec3 pos;
        Vec3 prev;
        Vec3 vel = Vec3.ZERO;
        final int born;
        final int cell;
        final float spin;
        final double size;
        /** 0 — белый, 1 — светло-розовый, 2 — розовый, 3 — малиновый. */
        final int tone;
        final double s1;
        final double s2;
        final double s3;
        /** Вихрь: смещение вдоль оси, радиус, угол в момент начала сбора. */
        double a0;
        double r0;
        double th0;
        /** Поток: струя, момент выхода из вихря. */
        int strand;
        double leave;
        int freeAge;
        double gravity;
        double drag = 0.9D;
        boolean dead;
        final Vec3[] tail = new Vec3[5];
        int tails;

        Petal(Vec3 pos, int born, int cell, float spin, double size, int tone, double s1, double s2, double s3) {
            this.pos = pos;
            this.prev = pos;
            this.born = born;
            this.cell = cell;
            this.spin = spin;
            this.size = size;
            this.tone = tone;
            this.s1 = s1;
            this.s2 = s2;
            this.s3 = s3;
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

    /** Звёздочка рождения лепестка (r08–r09): четыре луча, живёт 3 тика. */
    private record Star(Vec3 pos, int born, double size, double angle) {
    }

    /** Застывшая копия руки — резонанс (r07). */
    private record Ghost(Vec3 pos, float yaw, int born, int life, PartPose[] pose, int rgb, float alpha) {
    }

    /** Луч звёздного взрыва: направление, длина, ширина, рождение. */
    private record Ray(Vec3 dir, double len, double width, int born, int life) {
    }

    /** Полный цветок-акцент (r14): пять лепестков вокруг точки. */
    private record Flower(Vec3 pos, Vec3 vel, int born, double size, double angle) {
    }

    private record Ring(Vec3 centre, Vec3 normal, int born, double rMax, int life, double width) {
    }

    /** Штрих вибрации поперёк клинка (r07): от точки клинка, живёт 2–3 тика. */
    private record Stroke(Vec3 at, Vec3 across, int born, double len) {
    }

    /** Веер-отпечаток на земле: три точки полосы и рождение. */
    private record Scar(Vec3 a, Vec3 b, Vec3 c, int born) {
    }

    private static final class Cast {
        final int entityId;
        final int layer;
        int start;
        final Random random;
        final double scale;
        final int petalsMax;
        /** Точка прицела (тик 94), кисть сервера, цель. */
        Vec3 aim;
        Vec3 serverHand;
        int targetId = -1;
        int contactTick = -1;
        Vec3 contact;
        int contactId = -1;
        int missTick = -1;
        Vec3 missAt;
        int burstTick = -1;
        Vec3 burstAt;
        Vec3 axis = new Vec3(0.0D, 0.0D, 1.0D);
        boolean caption;
        /** Клинок: кисть, середина, остриё в этом и прошлом тике. */
        Vec3 hand;
        Vec3 mid;
        Vec3 tip;
        Vec3 handPrev;
        Vec3 tipPrev;
        Vec3 midPrev;
        /** Центр вихря (с запаздыванием за кистью) и его прошлое значение. */
        Vec3 gather;
        Vec3 gatherPrev;
        final Vec3[] handHist = new Vec3[4];
        /** Плоскость вихря: ось вперёд и две поперечные. */
        Vec3 fwd = new Vec3(0.0D, 0.0D, 1.0D);
        Vec3 e1 = new Vec3(1.0D, 0.0D, 0.0D);
        Vec3 e2 = new Vec3(0.0D, 1.0D, 0.0D);
        /** Путь потока: кисть в момент выпуска → контакт. */
        Vec3 streamFrom;
        Vec3 streamBend;
        int flight = 6;
        /** Дуга взмаха (r14): точки, построенные на выпуске. */
        Vec3[] arc;
        int born;
        int lone;
        final List<Mote> motes = new ArrayList<>();
        final List<Petal> petals = new ArrayList<>();
        final List<Puff> puffs = new ArrayList<>();
        final List<Star> stars = new ArrayList<>();
        final List<Ghost> ghosts = new ArrayList<>();
        final List<Ray> rays = new ArrayList<>();
        final List<Flower> flowers = new ArrayList<>();
        final List<Ring> rings = new ArrayList<>();
        final List<Stroke> strokes = new ArrayList<>();
        final List<Scar> scars = new ArrayList<>();
        ResourceLocation skin;

        Cast(int entityId, int layer) {
            this.entityId = entityId;
            this.layer = layer;
            this.start = clientTicks;
            this.random = new Random(entityId * 7919L + clientTicks);
            this.scale = RiverRules.scale(layer);
            this.petalsMax = RiverRules.petals(layer);
        }

        int t() {
            return clientTicks - start;
        }

        int n(double full) {
            return (int) Math.ceil(full * scale * Math.max(0.25D, layer / 7.0D));
        }

        boolean own() {
            Minecraft mc = Minecraft.getInstance();
            return mc.player != null && mc.player.getId() == entityId;
        }

        boolean eyes() {
            Minecraft mc = Minecraft.getInstance();
            return own() && mc.options.getCameraType().isFirstPerson() && mc.getCameraEntity() == mc.player;
        }

        /** Узел: точка контакта (живой цели), иначе точка прицела. */
        Vec3 node() {
            return contact != null ? contact : aim;
        }
    }

    // ------------------------------------------------------------------ события

    public static void onTechniqueEvent(TechniqueEventPayload payload) {
        if (payload.event() != TechniqueEventPayload.Event.STARTED || !TECHNIQUE.equals(payload.techniqueId())
                || payload.layer() <= 0) {
            return;
        }
        CASTS.removeIf(c -> c.entityId == payload.sourceId());
        CASTS.add(new Cast(payload.sourceId(), payload.layer()));
        // Голубой предвыброс (r01) — холодная аура до 14-го тика, дальше красно-розовая.
        ClientAuraState.techniqueAura(payload.sourceId(), 2 + Math.min(2, payload.layer() / 3), 0, RiverRules.BLUE_END + 2);
    }

    public static void onRiver(RiverPayload p) {
        Cast c = null;
        for (Cast x : CASTS) {
            if (x.entityId == p.entityId()) {
                c = x;
            }
        }
        Minecraft mc = Minecraft.getInstance();
        if (p.stage() == RiverPayload.AIM) {
            if (c == null) {
                c = new Cast(p.entityId(), p.layer());
                CASTS.add(c);
            }
            // Сверка шкалы: пакет прицела приходит ровно на тике AIM.
            c.start = clientTicks - RiverRules.AIM;
            c.aim = p.centre();
            c.serverHand = p.origin();
            c.targetId = p.targetId();
            c.flight = RiverRules.flight(p.centre().distanceTo(p.origin()));
            return;
        }
        if (c == null) {
            return;
        }
        if (p.stage() == RiverPayload.CONTACT) {
            c.contactTick = clientTicks;
            c.contact = p.centre();
            c.contactId = p.targetId();
            onContact(c, mc);
        } else if (p.stage() == RiverPayload.MISS) {
            c.missTick = clientTicks;
            c.missAt = p.centre();
            scatter(c, p.centre());
        } else if (p.stage() == RiverPayload.BURST) {
            c.burstTick = clientTicks;
            c.burstAt = p.centre();
            burst(c, p.centre(), mc);
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
            if (c.contactId >= 0 && c.burstTick < 0 && mc.level.getEntity(c.contactId) instanceof LivingEntity tg && tg.isAlive()) {
                c.contact = tg.position().add(0.0D, tg.getBbHeight() * 0.5D, 0.0D);
            }
            if (e != null) {
                blade(c, e, t);
                body(c, e, t, mc);
            }
            tickPetals(c, t);
            tickMotes(c);
            if (t > RiverRules.END + 50) {
                it.remove();
            }
        }
    }

    /**
     * Клинок: в третьем лице — кости {@link BoneAnchorLayer} (кисть, середина, остриё из анимации);
     * от первого лица тела нет — клинок строится от глаз по той же шкале проводок.
     */
    private static void blade(Cast c, Entity e, int t) {
        c.handPrev = c.hand;
        c.midPrev = c.mid;
        c.tipPrev = c.tip;
        Vec3 hand = null;
        Vec3 mid = null;
        Vec3 tip = null;
        if (!c.eyes() && e instanceof AbstractClientPlayer player) {
            hand = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.RIGHT_HAND);
            mid = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.BLADE_MID);
            tip = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.BLADE_TIP);
            // Кость устарела (игрок не рисовался) — не верим ей дальше 3 блоков от тела.
            if (hand != null && hand.distanceTo(e.position()) > 3.0D) {
                hand = null;
            }
        }
        if (hand == null || mid == null || tip == null) {
            float yaw = e.getYRot();
            Vec3 f = Vec3.directionFromRotation(0.0F, yaw);
            Vec3 right = f.cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
            Vec3 eye = e.getEyePosition();
            // От первого лица всё действие вынесено вперёд-вправо-вниз: поле и вихрь не закрывают цель.
            hand = eye.add(right.scale(0.8D)).add(0.0D, -0.8D, 0.0D).add(f.scale(2.2D));
            double[] o = fpBlade(t);
            Vec3 d = Vec3.directionFromRotation((float) (e.getXRot() - o[1]), yaw + (float) o[0]);
            tip = hand.add(d.scale(1.1D));
            mid = hand.add(d.scale(0.55D));
        }
        c.hand = hand;
        c.mid = mid;
        c.tip = tip;
        if (c.handPrev == null) {
            c.handPrev = hand;
            c.midPrev = mid;
            c.tipPrev = tip;
        }
        System.arraycopy(c.handHist, 0, c.handHist, 1, c.handHist.length - 1);
        c.handHist[0] = hand;
        float bodyYaw = e instanceof LivingEntity le ? le.yBodyRot : e.getYRot();
        Vec3 f = Vec3.directionFromRotation(0.0F, bodyYaw);
        if (c.aim != null) {
            Vec3 to = c.aim.subtract(hand);
            if (to.lengthSqr() > 1.0E-4D) {
                f = to.normalize();
            }
        }
        // Плоскость вихря строится от направления мастер → цель (в небо — вместе с ней).
        if (t < RiverRules.RELEASE) {
            c.fwd = c.fwd.lerp(f, 0.25D).normalize();
            Vec3 ref = Math.abs(c.fwd.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
            c.e1 = c.fwd.cross(ref).normalize();
            c.e2 = c.e1.cross(c.fwd).normalize();
        }
        // Центр вихря: перед кистью; на натяжении тянется за ней с запаздыванием 2 тика.
        Vec3 lagged = c.handHist[Math.min(2, c.handHist.length - 1)] != null ? c.handHist[2] : hand;
        Vec3 g = (t >= RiverRules.AIM ? lagged : hand).add(c.fwd.scale(c.eyes() ? 1.8D : 0.9D))
                .add(c.eyes() ? c.e1.scale(0.5D).add(0.0D, -0.3D, 0.0D) : Vec3.ZERO);
        c.gatherPrev = c.gather == null ? g : c.gather;
        c.gather = g;
    }

    /** Отклонение клинка от взгляда (рысканье°, тангаж° вверх) от первого лица по шкале проводок. */
    private static double[] fpBlade(double t) {
        double[][] keys = {{0, 15, -45}, {30, 10, -40}, {36, 0, -8}, {46, 0, -8}, {50, -28, 18}, {54, -28, 18}, {62, 30, -16},
                {68, 30, -16}, {76, 14, 24}, {80, 14, 24}, {86, 0, 0}, {94, 0, 0}, {100, 45, -28}, {104, -55, 42}, {136, -50, 38},
                {150, 10, -40}};
        double yaw = keys[keys.length - 1][1];
        double pitch = keys[keys.length - 1][2];
        for (int i = 1; i < keys.length; i++) {
            if (t <= keys[i][0]) {
                double k = smooth((t - keys[i - 1][0]) / (keys[i][0] - keys[i - 1][0]));
                yaw = keys[i - 1][1] + (keys[i][1] - keys[i - 1][1]) * k;
                pitch = keys[i - 1][2] + (keys[i][2] - keys[i - 1][2]) * k;
                break;
            }
        }
        if (t >= RiverRules.RESONANCE && t < RiverRules.AIM) {
            double amp = t < RiverRules.GATHER ? 2.5D : 1.0D;
            double s = ((int) t) % 2 == 0 ? 1.0D : -1.0D;
            yaw += s * amp;
            pitch += s * amp * 0.6D;
        }
        return new double[] {yaw, pitch};
    }

    /** Тело мастера: голубой выброс, красная оболочка, нить, резонанс, рождение лепестков. */
    private static void body(Cast c, Entity e, int t, Minecraft mc) {
        Vec3 feet = e.position();
        float yaw = e instanceof LivingEntity le ? le.yBodyRot : e.getYRot();
        Vec3 f = Vec3.directionFromRotation(0.0F, yaw);
        f = new Vec3(f.x, 0.0D, f.z).normalize();
        // r01: голубые восходящие ленты от ног и радиальный всплеск у земли.
        if (t == 1) {
            dust(c, feet, c.n(10) + 3, 0.16D);
            groundSplash(c, feet);
            if (c.own()) {
                SpeedLines.radial(0.5F, 0.55F, 0.4F, 6, SpeedLines.WHITE);
            }
        }
        if (t >= 1 && t < RiverRules.BLUE_END - 3) {
            int n = Math.max(1, c.n(4));
            for (int i = 0; i < n; i++) {
                double a = c.random.nextDouble() * Math.PI * 2.0D;
                double r = 0.4D + 0.5D * c.random.nextDouble();
                Vec3 at = feet.add(Math.cos(a) * r, 0.1D + 0.3D * c.random.nextDouble(), Math.sin(a) * r);
                Vec3 v = new Vec3(-Math.sin(a) * 0.08D + Math.cos(a) * 0.04D, 0.32D + 0.2D * c.random.nextDouble(),
                        Math.cos(a) * 0.08D + Math.sin(a) * 0.04D).scale(0.6D + 0.4D * c.scale);
                Mote m = new Mote(at, v, 6 + c.random.nextInt(4), Mote.BLUE, 0.05D + 0.06D * c.scale, 8, 0);
                m.drag = 0.93D;
                m.turbulence = 0.03D;
                c.motes.add(m);
            }
        }
        // r02–r06: красно-розовая оболочка — живая аура и рваные языки по силуэту.
        if (t == RiverRules.CONCENTRATE) {
            ClientAuraState.techniqueAura(c.entityId, 2 + Math.min(2, c.layer / 3), 1, RiverRules.BURST + 10 - RiverRules.CONCENTRATE);
            dust(c, feet, c.n(6) + 1, 0.1D);
            if (c.own()) {
                SpeedLines.radial(0.5F, 0.5F, 0.35F, 5, SpeedLines.WHITE);
            }
        }
        if (t >= RiverRules.CONCENTRATE && t < RiverRules.RELEASE && (t < RiverRules.STROKES[0][0] - 2 || t >= RiverRules.STROKES[1][0] + 2)) {
            // Пульс оболочки: сильнее на 12–30, затем ровнее.
            double pulse = 0.5D + 0.5D * Math.sin(t * 0.7D);
            int n = (int) Math.round((t < RiverRules.RESONANCE ? 3.0D : 1.4D) * pulse * Math.max(0.4D, c.scale) + 0.3D);
            for (int i = 0; i < n; i++) {
                double a = c.random.nextDouble() * Math.PI * 2.0D;
                double h = 0.15D + 1.75D * c.random.nextDouble();
                Vec3 out = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
                Vec3 at = feet.add(out.scale(0.3D + 0.1D * c.random.nextDouble())).add(0.0D, h, 0.0D);
                Mote m = new Mote(at, out.scale(0.07D + 0.05D * c.random.nextDouble()).add(0.0D, 0.06D + 0.05D * c.random.nextDouble(), 0.0D),
                        4 + c.random.nextInt(4), Mote.FLICK, 0.035D + 0.03D * c.scale, 5, 0);
                m.drag = 0.85D;
                m.turbulence = 0.02D;
                c.motes.add(m);
            }
        }
        // Аура: тише на рождении (лепесток читается сам), вспышка на выпуске, гаснет к паузе узла.
        if (t == RiverRules.STROKES[0][0] - 2) {
            // Одиночный лепесток (r08–r09) читается в тишине: аура гаснет до второй проводки.
            ClientAuraState.techniqueAura(c.entityId, 2, 1, 1);
        }
        if (t == RiverRules.STROKES[1][0] + 2) {
            ClientAuraState.techniqueAura(c.entityId, 2, 1, RiverRules.RELEASE - RiverRules.STROKES[1][0] - 2);
        }
        if (t == RiverRules.RELEASE) {
            ClientAuraState.techniqueAura(c.entityId, 2 + Math.min(2, c.layer / 3), 1, 6);
        }
        // Шаг в стойку и в жест — пыль от движения.
        if (t == 6 || t == 18 || t == 36) {
            dust(c, feet, c.n(5) + 1, 0.1D);
        }
        // r07: резонанс — копии руки с малым смещением и поперечные штрихи вдоль клинка.
        if (t >= RiverRules.RESONANCE && t < RiverRules.AIM && c.hand != null) {
            if (RiverRules.afterimages(c.layer)) {
                int every = t < RiverRules.STROKES[0][0] ? 2 : 4;
                if (t % every == 0) {
                    ghostArm(c, e, mc);
                }
                int k = t < RiverRules.GATHER ? 3 : 1;
                for (int i = 0; i < k; i++) {
                    double u = 0.2D + 0.8D * c.random.nextDouble();
                    Vec3 at = c.hand.lerp(c.tip, u);
                    Vec3 along = c.tip.subtract(c.hand);
                    Vec3 across = along.cross(c.fwd);
                    if (across.lengthSqr() < 1.0E-6D) {
                        across = along.cross(new Vec3(0.0D, 1.0D, 0.0D));
                    }
                    across = across.lengthSqr() < 1.0E-6D ? c.e1 : across.normalize();
                    if (c.random.nextBoolean()) {
                        across = across.scale(-1.0D);
                    }
                    c.strokes.add(new Stroke(at, across, clientTicks, (0.14D + 0.16D * c.random.nextDouble()) * (0.6D + 0.4D * c.scale)));
                }
            }
            if (t == RiverRules.RESONANCE && mc.player != null) {
                mc.player.level().playLocalSound(feet.x, feet.y + 1.0D, feet.z, net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_RESONATE,
                        net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.6F, false);
            }
        }
        // r08–r09: в каждом месте, где прошла вибрация, рождается лепесток.
        if (c.petalsMax > 0 && t >= RiverRules.STROKES[0][0] && t < RiverRules.GATHER && c.hand != null) {
            birth(c, t, mc);
        }
        // Проводки — тоже движения: ветер из кисти и пыль.
        for (int[] s : RiverRules.STROKES) {
            if (t == s[0] + 1 && c.hand != null) {
                Vec3 d = c.tip.subtract(c.tipPrev == null ? c.tip : c.tipPrev);
                Vec3 v = d.lengthSqr() < 1.0E-4D ? c.fwd.scale(0.2D) : d.normalize().scale(0.25D);
                wind(c, c.tip, v, 10, 0.05D + 0.03D * c.scale);
                dust(c, feet, 2, 0.08D);
            }
        }
        if (t == RiverRules.GATHER) {
            gatherStart(c);
            dust(c, feet, c.n(4) + 1, 0.08D);
            if (mc.player != null) {
                mc.player.level().playLocalSound(feet.x, feet.y + 1.0D, feet.z, net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME,
                        net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.7F, false);
            }
        }
        // Сбор (r10): ветер втягивается в вихрь.
        if (t >= RiverRules.GATHER && t < RiverRules.RELEASE && c.gather != null && t % 2 == 0) {
            double a = c.random.nextDouble() * Math.PI * 2.0D;
            Vec3 at = c.gather.add(c.e1.scale(Math.cos(a) * 2.4D * c.scale)).add(c.e2.scale(Math.sin(a) * 2.4D * c.scale));
            wind(c, at, c.gather.subtract(at).scale(0.18D).add(c.e1.scale(Math.sin(a) * 0.08D)).add(c.e2.scale(-Math.cos(a) * 0.08D)),
                    8, 0.05D);
        }
        if (t == RiverRules.RELEASE) {
            release(c, e, mc);
        }
        if (t > RiverRules.RELEASE && t <= RiverRules.RELEASE + 4 && c.tip != null && c.tipPrev != null) {
            // Взмах: ветер срывается с острия по ходу движения, пыль конусом назад.
            Vec3 d = c.tip.subtract(c.tipPrev);
            if (d.lengthSqr() > 1.0E-4D) {
                for (int i = 0; i < 2; i++) {
                    wind(c, c.tip.lerp(c.tipPrev, c.random.nextDouble()), d.normalize().scale(0.35D)
                            .add(c.random.nextGaussian() * 0.05D, c.random.nextGaussian() * 0.05D, c.random.nextGaussian() * 0.05D), 12, 0.07D + 0.05D * c.scale);
                }
            }
            dust(c, feet, 3, 0.2D);
        }
        // Мастер опускает меч: тоже движение.
        if (t == RiverRules.HOLD_END + 4) {
            dust(c, feet, c.n(5) + 1, 0.1D);
            wind(c, c.hand == null ? feet.add(0.0D, 1.2D, 0.0D) : c.hand, f.scale(0.15D).add(0.0D, -0.06D, 0.0D), 12, 0.06D);
        }
        c.ghosts.removeIf(g -> clientTicks - g.born() > g.life());
        c.strokes.removeIf(s -> clientTicks - s.born() > 3);
        c.stars.removeIf(s -> clientTicks - s.born() > 4);
    }

    /** Рождение лепестков вдоль пройденного клинком (по нескольким точкам клинка, а не только острию). */
    private static void birth(Cast c, int t, Minecraft mc) {
        Vec3 h0 = c.handPrev == null ? c.hand : c.handPrev;
        Vec3 t0 = c.tipPrev == null ? c.tip : c.tipPrev;
        int want;
        if (t < RiverRules.STROKES[1][0]) {
            // Первая проводка: только одиночные лепестки, каждый со звёздочкой; потом пауза.
            // Главный лепесток на 47-м, остальные — слабее и позже (51–53), после паузы.
            want = t >= 51 ? Math.min(RiverRules.LONE, t - 49) : t >= 47 ? 1 : c.lone;
            while (c.lone < want) {
                c.lone++;
                Vec3 at = c.hand.lerp(c.tip, 0.55D + 0.12D * c.lone);
                // Отходит от клинка на ~0,5 блока: один лепесток в свободном просвете.
                Vec3 away = c.fwd.scale(0.06D).add(c.e1.scale((c.lone % 2 == 0 ? 1 : -1) * 0.03D)).add(0.0D, 0.015D, 0.0D);
                Petal p = spawnPetal(c, at, away, c.lone == 1 ? 0.26D : 0.15D, 0);
                p.drag = 0.88D;
                if (c.lone == 1) {
                    c.stars.add(new Star(at, clientTicks, 0.42D, c.random.nextDouble()));
                }
                if (mc.player != null) {
                    mc.player.level().playLocalSound(at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME,
                            net.minecraft.sounds.SoundSource.PLAYERS, 0.6F, 1.6F + 0.15F * c.lone, false);
                }
            }
            return;
        }
        int target = RiverRules.LONE + (int) Math.round((c.petalsMax - RiverRules.LONE) * RiverRules.born(t));
        int quota = target - c.born;
        for (int i = 0; i < quota; i++) {
            double u = 0.18D + 0.82D * Math.sqrt(c.random.nextDouble());
            double k = c.random.nextDouble();
            Vec3 cur = c.hand.lerp(c.tip, u);
            Vec3 old = h0.lerp(t0, u);
            Vec3 at = old.lerp(cur, k).add(c.random.nextGaussian() * 0.06D, c.random.nextGaussian() * 0.06D, c.random.nextGaussian() * 0.06D);
            // Лепесток едва сносит по ходу клинка и чуть наружу: поле дышит и растёт в объёме.
            Vec3 sweep = cur.subtract(old).scale(0.12D);
            // Поле занимает объём перед мастером (≈4 × 3 × 2,5): лепесток отходит вперёд и вбок от клинка.
            Vec3 out = c.fwd.scale(0.08D + 0.22D * c.random.nextDouble()).add(c.e1.scale(c.random.nextGaussian() * 0.2D))
                    .add(c.e2.scale(c.random.nextGaussian() * 0.12D - 0.02D));
            int tone = c.random.nextDouble() < 0.72D ? 0 : c.random.nextDouble() < 0.75D ? 1 : 2;
            Petal p = spawnPetal(c, at, sweep.add(out).add(0.0D, 0.01D * c.random.nextGaussian(), 0.0D),
                    0.12D + 0.1D * c.random.nextDouble(), tone);
            if (c.born % 12 == 0) {
                c.stars.add(new Star(at, clientTicks, 0.26D, c.random.nextDouble()));
            }
            if (p == null) {
                break;
            }
        }
    }

    private static Petal spawnPetal(Cast c, Vec3 at, Vec3 vel, double size, int tone) {
        Petal p = new Petal(at, clientTicks, c.random.nextInt(4), (float) ((c.random.nextDouble() - 0.5D) * 0.5D), size, tone,
                c.random.nextDouble(), c.random.nextDouble(), c.random.nextDouble());
        p.vel = vel;
        p.drag = 0.9D;
        c.petals.add(p);
        c.born++;
        return p;
    }

    /** Начало сбора: каждый лепесток запоминает своё место в системе вихря. */
    private static void gatherStart(Cast c) {
        if (c.gather == null) {
            return;
        }
        for (Petal p : c.petals) {
            if (p.state != Petal.FIELD) {
                continue;
            }
            Vec3 d = p.pos.subtract(c.gather);
            p.a0 = d.dot(c.fwd);
            double x = d.dot(c.e1);
            double y = d.dot(c.e2);
            p.r0 = Math.hypot(x, y);
            p.th0 = Math.atan2(y, x);
            p.state = Petal.GATHER;
        }
    }

    /** Копия правой руки со смещением ±3° (r07: дискретные повторения, а не размытие). */
    private static void ghostArm(Cast c, Entity e, Minecraft mc) {
        if (c.eyes() || !(e instanceof AbstractClientPlayer player)
                || !(mc.getEntityRenderDispatcher().getRenderer(player) instanceof PlayerRenderer renderer)) {
            return;
        }
        if (c.skin == null) {
            c.skin = player.getSkin().texture();
        }
        PlayerModel<AbstractClientPlayer> model = renderer.getModel();
        ModelPart[] parts = parts(model);
        PartPose[] pose = new PartPose[parts.length];
        for (int i = 0; i < parts.length; i++) {
            pose[i] = parts[i].storePose();
        }
        float yaw = player.yBodyRot;
        for (int k = 0; k < 2; k++) {
            float dx = (float) Math.toRadians((c.random.nextBoolean() ? 1 : -1) * (2.0D + 2.0D * c.random.nextDouble()));
            float dy = (float) Math.toRadians((c.random.nextBoolean() ? 1 : -1) * (1.5D + 2.0D * c.random.nextDouble()));
            PartPose[] q = pose.clone();
            for (int j = 4; j <= 5; j++) {
                PartPose o = pose[j];
                // API: reference/minecraft-src/net/minecraft/client/model/geom/PartPose.java#offsetAndRotation
                q[j] = PartPose.offsetAndRotation(o.x, o.y, o.z, o.xRot + dx, o.yRot + dy, o.zRot);
            }
            c.ghosts.add(new Ghost(player.position(), yaw, clientTicks, 3, q, k == 0 ? 0xFF6A7E : 0xFFB0C4, k == 0 ? 0.42F : 0.24F));
        }
    }

    /** Выпуск (r14, r11–r12): широкий изогнутый взмах, лепестки уходят жгутом к цели. */
    private static void release(Cast c, Entity e, Minecraft mc) {
        Vec3 from = c.gather != null ? c.gather : c.hand;
        Vec3 to = c.aim != null ? c.aim : from.add(c.fwd.scale(12.0D));
        c.streamFrom = from;
        Vec3 d = to.subtract(from);
        double dist = Math.max(1.0D, d.length());
        Vec3 dir = d.normalize();
        c.axis = dir;
        Vec3 ref = Math.abs(dir.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 side = dir.cross(ref).normalize();
        Vec3 up = side.cross(dir).normalize();
        // Изгиб жгута по ходу взмаха: вверх и вправо, не больше 3 блоков.
        double bow = Math.min(3.0D, 0.9D + dist * 0.18D) * (0.7D + 0.3D * c.scale);
        c.streamBend = from.lerp(to, 0.45D).add(up.scale(bow)).add(side.scale(bow * 0.55D));
        int strands = Math.max(1, RiverRules.strands(c.layer));
        int window = c.flight + 6;
        int i = 0;
        for (Petal p : c.petals) {
            if (p.dead || p.state == Petal.FREE) {
                continue;
            }
            p.state = Petal.STREAM;
            // Толстая струя — 0-я: ей треть лепестков.
            p.strand = p.s1 < 0.3D ? 0 : 1 + (int) (p.s2 * (strands - 1));
            p.strand = Math.min(strands - 1, p.strand);
            p.leave = RiverRules.RELEASE + Math.pow(p.s3, 1.3D) * window * 0.85D;
            i++;
        }
        buildArc(c, e, side, up, dir);
        // Дуга набрана веществом (r14): вытянутые лепестки вдоль серпа с просветами, опадают.
        if (c.petalsMax > 0) {
            for (int k = 0; k < c.n(90); k++) {
                double u = c.random.nextDouble();
                Vec3 at = c.arc[(int) (u * (c.arc.length - 1))].add(c.random.nextGaussian() * 0.15D, c.random.nextGaussian() * 0.15D,
                        c.random.nextGaussian() * 0.15D);
                Petal q = new Petal(at, clientTicks + (int) (u * 4.0D), c.random.nextInt(4), (float) ((c.random.nextDouble() - 0.5D) * 0.5D),
                        0.12D + 0.08D * c.random.nextDouble(), c.random.nextDouble() < 0.7D ? 0 : 1, c.random.nextDouble(),
                        c.random.nextDouble(), c.random.nextDouble());
                q.state = Petal.FREE;
                q.vel = dir.scale(0.04D).add(c.random.nextGaussian() * 0.02D, -0.01D, c.random.nextGaussian() * 0.02D);
                q.drag = 0.95D;
                q.gravity = 0.002D;
                c.petals.add(q);
            }
        }
        if (c.layer >= 2) {
            for (int k = 0; k < Math.max(2, c.n(8)); k++) {
                double u = 0.2D + 0.7D * c.random.nextDouble();
                // Половина цветков — в стороне от кромки на 0,2–0,5 блока.
                Vec3 at = c.arc[(int) (u * (c.arc.length - 1))].add(k % 2 == 0 ? Vec3.ZERO
                        : new Vec3(c.random.nextGaussian(), c.random.nextGaussian(), c.random.nextGaussian()).normalize()
                                .scale(0.2D + 0.3D * c.random.nextDouble()));
                c.flowers.add(new Flower(at, new Vec3(c.random.nextGaussian() * 0.01D, -0.004D, c.random.nextGaussian() * 0.01D),
                        clientTicks + 2 + c.random.nextInt(4), 0.25D + 0.2D * c.random.nextDouble(), c.random.nextDouble() * 6.28D));
            }
        }
        if (c.own()) {
            SpeedLines.directional(0.0F, 0.35F, 5, SpeedLines.WHITE);
        }
        if (mc.player != null) {
            Vec3 o = e.position();
            mc.player.level().playLocalSound(o.x, o.y + 1.0D, o.z, net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP,
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.8F, false);
            mc.player.level().playLocalSound(o.x, o.y + 1.0D, o.z, net.minecraft.sounds.SoundEvents.TRIDENT_RIPTIDE_3.value(),
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.7F, 1.3F, false);
        }
        MurimMod.LOGGER.debug("Река: выпуск, лепестков {}, струй {}", i, strands);
    }

    /**
     * Дуга взмаха (r14): высокая изогнутая лента 5–6 блоков у мастера, восходящая по ходу выпуска:
     * снизу-слева-спереди вверх-вправо, плоскость повёрнута к цели.
     */
    private static void buildArc(Cast c, Entity e, Vec3 side, Vec3 up, Vec3 dir) {
        Vec3 centre = e.position().add(0.0D, 1.2D, 0.0D).add(dir.scale(1.0D));
        double r = 2.9D * c.scale;
        int n = 40;
        c.arc = new Vec3[n + 1];
        for (int i = 0; i <= n; i++) {
            double u = i / (double) n;
            double ang = Math.toRadians(-130.0D + 210.0D * u);
            // Плоскость дуги: «вправо» и «вверх», с наклоном вперёд к цели по ходу.
            c.arc[i] = centre.add(side.scale(Math.cos(ang) * r * 0.8D)).add(up.scale(Math.sin(ang) * r + 0.4D * r))
                    .add(dir.scale(0.9D * Math.sin(Math.PI * u) + 0.6D * u));
        }
    }

    /** Контакт (r11–r12): поток упирается в цель — веер лепестков, резкие штрихи, узел. */
    private static void onContact(Cast c, Minecraft mc) {
        Vec3 at = c.contact;
        int n = c.n(18);
        for (int i = 0; i < n; i++) {
            Vec3 v = c.axis.scale(-0.2D).add(new Vec3(c.random.nextGaussian(), c.random.nextGaussian(), c.random.nextGaussian()).scale(0.18D));
            Mote m = new Mote(at, v, 6 + c.random.nextInt(4), Mote.SPARK, 0.04D + 0.03D * c.random.nextDouble(), 4, 0);
            m.drag = 0.8D;
            c.motes.add(m);
        }
        c.rings.add(new Ring(at, c.axis, clientTicks, 1.4D * c.scale, 8, 0.08D));
        if (c.own() && RiverRules.caption(c.layer)) {
            c.caption = true;
            TechniqueCaption.showSecret(Component.translatable("technique.murim.twenty_four_plum.school"),
                    Component.translatable("technique.murim.twenty_four_plum.river"), RiverRules.BURST + 36 - (c.t()));
        }
        float distance = mc.player == null ? 99.0F : (float) mc.player.position().distanceTo(at);
        if (mc.player != null && distance < 20.0F) {
            CameraShakeHandler.quake(0.2F, 5);
            mc.player.level().playLocalSound(at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_RESONATE,
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.2F, 0.6F, false);
            mc.player.level().playLocalSound(at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.BEACON_POWER_SELECT,
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.8F, 1.8F, false);
        }
    }

    /** Промах: поток рассыпается на лепестки без ударных эффектов. */
    private static void scatter(Cast c, Vec3 at) {
        for (Petal p : c.petals) {
            if (p.state == Petal.STREAM || p.state == Petal.NODE || p.state == Petal.GATHER) {
                p.state = Petal.FREE;
                p.vel = new Vec3(c.random.nextGaussian() * 0.05D, -0.01D + c.random.nextGaussian() * 0.03D, c.random.nextGaussian() * 0.05D)
                        .add(c.axis.scale(0.08D));
                p.drag = 0.94D;
                p.gravity = 0.004D;
            }
        }
    }

    /**
     * Звёздный взрыв (r16 + роман): белое ядро до 2,2 блока за 2 тика, восемь неравных лучей (два
     * вдоль оси длиннее), направленный выброс 70 % по оси и 30 % вбок, редкие цветки, импакт-кадр,
     * тряска, дым — всё по факту попадания.
     */
    private static void burst(Cast c, Vec3 at, Minecraft mc) {
        Vec3 axis = c.axis;
        Vec3 ref = Math.abs(axis.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 side = axis.cross(ref).normalize();
        Vec3 up = side.cross(axis).normalize();
        int rays = RiverRules.rays(c.layer);
        double s = c.scale;
        if (rays > 0) {
            c.rays.add(new Ray(axis, 9.0D * s, 0.22D * s, clientTicks, 9));
            c.rays.add(new Ray(axis.scale(-1.0D), 4.0D * s, 0.16D * s, clientTicks, 8));
            for (int i = 2; i < rays; i++) {
                double a = (i - 2) * Math.PI * 2.0D / Math.max(1, rays - 2) + 0.4D * c.random.nextDouble();
                Vec3 d = side.scale(Math.cos(a)).add(up.scale(Math.sin(a))).add(axis.scale(0.35D * c.random.nextGaussian())).normalize();
                c.rays.add(new Ray(d, (1.8D + 2.2D * c.random.nextDouble()) * s, (0.08D + 0.06D * c.random.nextDouble()) * s, clientTicks,
                        6 + c.random.nextInt(3)));
            }
        }
        // Узловые и долетающие лепестки разлетаются: 70 % вдоль атаки, 30 % вбок.
        for (Petal p : c.petals) {
            if (p.dead || p.state == Petal.FREE) {
                continue;
            }
            boolean along = p.s2 < 0.7D;
            Vec3 jitter = new Vec3(c.random.nextGaussian(), c.random.nextGaussian(), c.random.nextGaussian()).scale(0.25D);
            Vec3 dir = along ? axis.add(jitter.scale(0.6D)).normalize()
                    : side.scale(c.random.nextGaussian()).add(up.scale(c.random.nextGaussian())).add(jitter).normalize();
            p.pos = at.add(jitter.scale(0.3D));
            p.prev = p.pos;
            p.state = Petal.FREE;
            p.vel = dir.scale((along ? 0.7D + 0.7D * c.random.nextDouble() : 0.35D + 0.4D * c.random.nextDouble()) * (0.7D + 0.3D * s));
            p.drag = 0.84D + 0.06D * c.random.nextDouble();
            p.gravity = 0.0035D;
            p.freeAge = 0;
            p.tails = 0;
        }
        int shards = c.n(70);
        for (int i = 0; i < shards; i++) {
            boolean along = i % 10 < 7;
            Vec3 jitter = new Vec3(c.random.nextGaussian(), c.random.nextGaussian(), c.random.nextGaussian()).scale(along ? 0.18D : 0.6D);
            Vec3 dir = along ? axis.add(jitter).normalize() : side.scale(c.random.nextGaussian()).add(up.scale(c.random.nextGaussian())).add(jitter).normalize();
            Mote m = new Mote(at, dir.scale((along ? 1.3D : 0.7D) * (0.6D + 0.6D * c.random.nextDouble()) * (0.7D + 0.3D * s)),
                    7 + c.random.nextInt(6), Mote.SHARD, 0.04D + 0.05D * c.random.nextDouble(), 6, 0);
            m.drag = 0.82D;
            c.motes.add(m);
        }
        for (int i = 0; i < Math.max(2, c.n(8)); i++) {
            Vec3 d = axis.scale(0.3D + 0.6D * c.random.nextDouble()).add(side.scale(c.random.nextGaussian() * 0.5D))
                    .add(up.scale(c.random.nextGaussian() * 0.5D));
            c.flowers.add(new Flower(at.add(d.scale(1.6D * s)), d.normalize().scale(0.12D).add(0.0D, -0.004D, 0.0D), clientTicks + 1,
                    0.25D + 0.2D * c.random.nextDouble(), c.random.nextDouble() * 6.28D));
        }
        c.rings.add(new Ring(at, axis, clientTicks, 3.2D * s, 9, 0.18D));
        c.rings.add(new Ring(at, axis, clientTicks + 2, 5.0D * s, 10, 0.08D));
        // Отпечаток: короткий веер по ходу выброса, только если узел у самой земли.
        Vec3 ground = groundBelow(at, 2.2D);
        if (ground != null) {
            Vec3 flat = new Vec3(axis.x, 0.0D, axis.z);
            flat = flat.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : flat.normalize();
            for (int i = 0; i < Math.max(4, c.n(11)); i++) {
                double a = Math.atan2(flat.z, flat.x) + (c.random.nextDouble() - 0.5D) * 1.4D;
                double len = (2.0D + 3.0D * c.random.nextDouble()) * s;
                Vec3 d = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
                Vec3 g0 = ground.add(0.0D, 0.04D, 0.0D);
                c.scars.add(new Scar(g0.add(d.scale(0.3D)), g0.add(d.scale(len * 0.5D)), g0.add(d.scale(len)), clientTicks));
            }
        }
        smoke(c, at, ground);
        float distance = mc.player == null ? 99.0F : (float) mc.player.position().distanceTo(at);
        if (c.own()) {
            ImpactFrames.trigger(at);
            SpeedLines.radial(0.5F, 0.5F, 0.65F, 7, SpeedLines.WHITE);
            if (c.caption) {
                TechniqueCaption.impact(1.0F);
            }
        }
        if (mc.player != null && distance < 24.0F) {
            float q = distance < 8.0F ? 1.0F : 1.0F - (distance - 8.0F) / 16.0F;
            CameraShakeHandler.quake(Math.max(q, c.own() ? 0.9F : 0.0F), 16);
            mc.player.level().playLocalSound(at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(),
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.8F, 1.3F, false);
            mc.player.level().playLocalSound(at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME,
                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.5F, false);
        }
    }

    private static Vec3 groundBelow(Vec3 at, double max) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        for (double dy = 0.0D; dy <= max + 1.0D; dy += 0.25D) {
            BlockPos pos = BlockPos.containing(at.x, at.y - dy, at.z);
            if (!mc.level.getBlockState(pos).isAir()) {
                return new Vec3(at.x, pos.getY() + 1.0D, at.z);
            }
        }
        return null;
    }

    /** Дым манхвы: у земли — низкий вал, потом облако; в воздухе — клубы в точке удара. */
    private static void smoke(Cast c, Vec3 at, Vec3 ground) {
        Minecraft mc = Minecraft.getInstance();
        Entity caster = mc.level == null ? null : mc.level.getEntity(c.entityId);
        int n = Math.max(5, c.n(10));
        double r = 1.3D * c.scale;
        Vec3 base = ground != null ? ground : at;
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2.0D * i / n + c.random.nextDouble() * 0.3D;
            Vec3 out = ground != null ? new Vec3(Math.cos(a), 0.0D, Math.sin(a))
                    : new Vec3(Math.cos(a), c.random.nextGaussian() * 0.6D, Math.sin(a)).normalize();
            Vec3 p = base.add(out.scale(r * (0.35D + 0.4D * c.random.nextDouble())));
            if (caster != null && p.distanceTo(caster.position().add(0.0D, 1.0D, 0.0D)) < 2.6D) {
                continue;
            }
            boolean hollow = i % 3 == 0;
            double size = (0.45D + 0.7D * Math.pow(c.random.nextDouble(), 1.5D)) * (hollow ? 1.2D : 1.0D) * c.scale;
            Puff bank = new Puff(ground != null ? p.add(0.0D, size * 0.45D, 0.0D) : p,
                    out.scale(0.1D + 0.2D * c.random.nextDouble()).add(c.axis.scale(0.08D)), 32 + c.random.nextInt(16),
                    c.random.nextInt(16), size, true, hollow ? 0.56F : 0.84F + 0.12F * c.random.nextFloat(), (float) (c.random.nextDouble() * 6.28D));
            bank.delay = 2 + c.random.nextInt(4);
            c.puffs.add(bank);
        }
        if (ground != null) {
            for (int i = 0; i < n / 2; i++) {
                double a = c.random.nextDouble() * Math.PI * 2.0D;
                Vec3 p = ground.add(Math.cos(a) * r * 0.3D, 0.8D, Math.sin(a) * r * 0.3D);
                Puff rise = new Puff(p, new Vec3(0.0D, 0.06D + 0.04D * c.random.nextDouble(), 0.0D), 40 + c.random.nextInt(14),
                        c.random.nextInt(16), (0.9D + 0.9D * c.random.nextDouble()) * c.scale, true, 0.74F + 0.14F * c.random.nextFloat(),
                        (float) (c.random.nextDouble() * 6.28D));
                rise.delay = 6 + c.random.nextInt(8);
                c.puffs.add(rise);
            }
        }
    }

    private static void groundSplash(Cast c, Vec3 feet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.getBlockState(BlockPos.containing(feet.add(0.0D, -0.2D, 0.0D))).isAir()) {
            return;
        }
        c.rings.add(new Ring(feet.add(0.0D, 0.05D, 0.0D), new Vec3(0.0D, 1.0D, 0.0D), clientTicks, 2.4D * c.scale, 10, 0.12D));
        for (int i = 0; i < c.n(10) + 2; i++) {
            double a = c.random.nextDouble() * Math.PI * 2.0D;
            Vec3 out = new Vec3(Math.cos(a), 0.15D + 0.25D * c.random.nextDouble(), Math.sin(a));
            Mote m = new Mote(feet.add(out.scale(0.3D)).add(0.0D, 0.1D, 0.0D), out.scale(0.25D + 0.15D * c.random.nextDouble()),
                    8 + c.random.nextInt(4), Mote.BLUE, 0.04D, 5, 0);
            m.drag = 0.85D;
            c.motes.add(m);
        }
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

    private static void wind(Cast c, Vec3 at, Vec3 vel, int life, double size) {
        Mote m = new Mote(at, vel, life, Mote.WIND, size, 10, 0);
        m.drag = 0.9D;
        m.turbulence = 0.03D;
        c.motes.add(m);
    }

    private static ModelPart[] parts(PlayerModel<?> m) {
        return new ModelPart[] {m.head, m.hat, m.body, m.jacket, m.rightArm, m.rightSleeve, m.leftArm, m.leftSleeve,
                m.rightLeg, m.rightPants, m.leftLeg, m.leftPants};
    }

    // ------------------------------------------------------------------ симуляция

    private static void tickPetals(Cast c, int t) {
        double kGather = smooth((t - RiverRules.GATHER) / (double) (RiverRules.AIM - RiverRules.GATHER));
        double radius = (2.2D + (0.65D - 2.2D) * kGather) * c.scale;
        Vec3 node = c.node();
        double nodeK = nodeK(c, 0.0F);
        for (Petal p : c.petals) {
            p.prev = p.pos;
            if (p.dead) {
                continue;
            }
            switch (p.state) {
                case Petal.FIELD -> {
                    // Висит, где родился: слабое колыхание и снос.
                    double ph = (clientTicks - p.born) * 0.17D + p.s1 * 6.28D;
                    p.vel = p.vel.scale(p.drag).add(Math.sin(ph) * 0.0025D, Math.cos(ph * 1.3D) * 0.002D, Math.cos(ph * 0.8D) * 0.0025D);
                    p.pos = p.pos.add(p.vel);
                }
                case Petal.GATHER -> {
                    if (c.gather == null) {
                        break;
                    }
                    double dt = t - RiverRules.GATHER;
                    // 70 % массы сходится, периферия (30 %) отстаёт вдвое и остаётся различимой.
                    double kk = p.s3 < 0.3D ? kGather * 0.5D : kGather;
                    double r = p.r0 + (radius * (0.12D + 0.88D * Math.pow(p.s1, 0.7D)) * (p.s3 < 0.3D ? 2.2D : 1.0D) - p.r0) * kk;
                    double a = p.a0 * (1.0D - kk) + (p.s2 - 0.5D) * 0.5D * c.scale * kk;
                    double th = p.th0 + (0.05D + 0.03D * p.s3) * dt + 0.022D * dt * dt;
                    p.pos = c.gather.add(c.fwd.scale(a)).add(c.e1.scale(Math.cos(th) * r)).add(c.e2.scale(Math.sin(th) * r));
                }
                case Petal.STREAM -> {
                    if (t < p.leave || c.streamFrom == null) {
                        // Ещё в вихре у кисти: кружит быстро и тесно.
                        if (c.gather != null) {
                            double th = p.th0 + 0.6D * t;
                            double r = 0.65D * c.scale * (0.35D + 0.65D * p.s1);
                            p.pos = c.gather.add(c.e1.scale(Math.cos(th) * r)).add(c.e2.scale(Math.sin(th) * r));
                        }
                        break;
                    }
                    double u = (t - p.leave) / Math.max(2.0D, c.flight);
                    if (u >= 1.0D) {
                        if (c.missTick >= 0) {
                            p.state = Petal.FREE;
                            p.vel = c.axis.scale(0.1D).add(new Vec3(p.s1 - 0.5D, p.s2 - 0.5D, p.s3 - 0.5D).scale(0.12D));
                            p.gravity = 0.004D;
                            p.drag = 0.94D;
                        } else {
                            p.state = Petal.NODE;
                        }
                        break;
                    }
                    pushTail(p);
                    p.pos = streamPoint(c, p, u, t);
                }
                case Petal.NODE -> {
                    if (node == null) {
                        break;
                    }
                    // Оболочка различимых лепестков вокруг узла: радиус 0,65 → 0,12, вращение ускоряется.
                    double r = (0.65D + (0.12D - 0.65D) * nodeK) * c.scale * (0.5D + 0.9D * p.s1);
                    double th = p.s2 * 6.28D + (0.25D + 0.5D * nodeK) * (clientTicks - c.contactTick);
                    double tilt = p.s3 * Math.PI;
                    Vec3 ref = Math.abs(c.axis.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
                    Vec3 s1 = c.axis.cross(ref).normalize();
                    Vec3 s2 = s1.cross(c.axis).normalize();
                    Vec3 plane1 = s1.scale(Math.cos(tilt)).add(c.axis.scale(Math.sin(tilt)));
                    Vec3 want = node.add(plane1.scale(Math.cos(th) * r)).add(s2.scale(Math.sin(th) * r));
                    pushTail(p);
                    p.pos = p.pos.lerp(want, 0.55D);
                }
                case Petal.FREE -> {
                    p.freeAge++;
                    double ph = p.freeAge * 0.2D + p.s1 * 6.28D;
                    // Опадание: сопротивление гасит разлёт, лепесток порхает вниз.
                    double flutter = p.freeAge > 8 ? 0.008D : 0.0D;
                    p.vel = p.vel.scale(p.drag).add(Math.sin(ph) * flutter, -p.gravity, Math.cos(ph * 0.9D) * flutter);
                    if (p.freeAge > 10) {
                        p.drag = Math.max(p.drag, 0.95D);
                        p.vel = new Vec3(p.vel.x, Math.max(p.vel.y, -0.06D), p.vel.z);
                    }
                    pushTail(p);
                    p.pos = p.pos.add(p.vel);
                    if (p.freeAge > 70 + (int) (p.s3 * 40)) {
                        p.dead = true;
                    }
                }
                default -> {
                }
            }
        }
        // Взрыва не было (цель умерла, ушла слишком далеко) — узел рассыпается без ударных эффектов.
        if (t == RiverRules.BURST + 3 && c.burstTick < 0 && c.missTick < 0 && node != null) {
            scatter(c, node);
        }
        if (t > RiverRules.END + 30) {
            c.petals.removeIf(p -> p.dead);
        }
    }

    /** Сжатие узла 0 → 1 от контакта до тика BURST − 4: последние 4 тика точка почти неподвижна. */
    private static double nodeK(Cast c, float partial) {
        if (c.contactTick < 0) {
            return 0.0D;
        }
        double span = Math.max(3.0D, RiverRules.BURST - 4 - (c.contactTick - c.start));
        return smooth((clientTicks - c.contactTick + partial) / span);
    }

    private static void pushTail(Petal p) {
        System.arraycopy(p.tail, 0, p.tail, 1, p.tail.length - 1);
        p.tail[0] = p.pos;
        p.tails = Math.min(p.tail.length, p.tails + 1);
    }

    /**
     * Точка лепестка в жгуте: кривая Безье кисть → изгиб → узел, струи переплетаются (угол вокруг
     * касательной растёт по ходу), ширина 1,8 у мастера → 0,6 у контакта.
     */
    private static Vec3 streamPoint(Cast c, Petal p, double u, double t) {
        Vec3 end = c.node();
        Vec3 a = c.streamFrom;
        Vec3 b = c.streamBend;
        double e = 1.0D - (1.0D - u) * (1.0D - u);
        Vec3 centre = bezier(a, b, end, e);
        Vec3 tangent = bezier(a, b, end, Math.min(1.0D, e + 0.02D)).subtract(bezier(a, b, end, Math.max(0.0D, e - 0.02D)));
        tangent = tangent.lengthSqr() < 1.0E-8D ? c.axis : tangent.normalize();
        Vec3 ref = Math.abs(tangent.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 n1 = tangent.cross(ref).normalize();
        Vec3 n2 = n1.cross(tangent).normalize();
        int strands = Math.max(1, RiverRules.strands(c.layer));
        double phi = p.strand * Math.PI * 2.0D / strands + e * 4.0D * Math.PI * (p.strand % 2 == 0 ? 1.0D : -1.0D) + t * 0.05D;
        double half = (0.9D + (0.3D - 0.9D) * e) * c.scale;
        double strandR = p.strand == 0 ? 0.0D : half;
        // Струи разделены просветами: разброс внутри струи мал, толстая 0-я — шире.
        double spread = (p.strand == 0 ? 0.28D : 0.05D) * half;
        Vec3 off = n1.scale(Math.cos(phi) * strandR).add(n2.scale(Math.sin(phi) * strandR))
                .add(n1.scale((p.s2 - 0.5D) * spread)).add(n2.scale((p.s3 - 0.5D) * spread));
        return centre.add(off);
    }

    private static Vec3 bezier(Vec3 a, Vec3 b, Vec3 c, double u) {
        double v = 1.0D - u;
        return a.scale(v * v).add(b.scale(2.0D * v * u)).add(c.scale(u * u));
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
        c.rays.removeIf(r -> clientTicks - r.born() > r.life() + 2);
        c.rings.removeIf(r -> clientTicks - r.born() > r.life());
        c.flowers.removeIf(f -> clientTicks - f.born() > 46);
        c.scars.removeIf(s -> clientTicks - s.born() > 70);
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
                nearFrom = c.eyes() ? 1.4D : 0.9D;
                nearSpan = c.eyes() ? 1.8D : 1.3D;
                models(mc, c, ps, buffers, partial);
                PoseStack.Pose pose = ps.last();
                VertexConsumer air = buffers.getBuffer(MurimRenderTypes.airBand());
                ribbons(c, pose, camera, air, partial);
                thread(c, mc, pose, camera, air, t);
                vibration(c, pose, camera, air, partial);
                spirals(c, pose, camera, air, t);
                arc(c, pose, camera, air, t);
                link(c, pose, camera, air, t);
                streamTails(c, pose, camera, air, partial);
                nodeRays(c, pose, camera, air, t);
                burstRays(c, pose, camera, air, partial);
                rings(c, pose, camera, air, partial);
                buffers.endBatch(MurimRenderTypes.airBand());
                puffs(c, pose, camera, buffers, partial);
                petals(c, pose, camera, buffers, partial, t);
            }
        } finally {
            ps.popPose();
        }
    }

    /** Копии руки резонанса (r07). */
    private static void models(Minecraft mc, Cast c, PoseStack ps, MultiBufferSource.BufferSource buffers, float partial) {
        if (c.ghosts.isEmpty() || !(mc.level.getEntity(c.entityId) instanceof AbstractClientPlayer player)) {
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
                    parts[i].visible = i == 4 || i == 5;
                }
                draw(model, ps, buffers.getBuffer(type), g.pos(), g.yaw(), g.alpha() * (1.0F - k), g.rgb());
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

    /** Ленты с хвостами: ветер, голубой поток, красные языки оболочки, искры и осколки. */
    private static void ribbons(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Mote m : c.motes) {
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
            float life = (m.age + partial) / m.life;
            float a = (float) PlumVfx.curve(life, 0.0, 0.3, 0.12, 1.0, 0.6, 0.8, 1.0, 0.0);
            switch (m.kind) {
                case Mote.BLUE -> {
                    fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.2D), 0.2F * a, BLUE_DEEP);
                    fstrip(v, pose, camera, p, w, 0.55F * a, BLUE);
                    fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.4D), 0.9F * a, BLUE_TEAL);
                    fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.15D), 0.95F * a, BLUE_WHITE);
                }
                case Mote.FLICK -> {
                    fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.0D), 0.25F * a, RED);
                    fstrip(v, pose, camera, p, w, 0.75F * a, RED);
                    fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.3D), 0.8F * a, PINK_LIGHT);
                }
                case Mote.SPARK, Mote.SHARD -> {
                    fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.4D), 0.22F * a, m.kind == Mote.SHARD ? BURST_RIM : PINK_HOT);
                    fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.5D), 0.95F * a, WHITE);
                }
                default -> {
                    fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.0D), 0.1F * a, COLD);
                    fstrip(v, pose, camera, p, w, 0.38F * a, WIND);
                    fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.25D), 0.85F * a, EDGE);
                }
            }
        }
    }

    /** Красная нить вдоль левой руки к пальцам (r04–r05) и компактные красные глаза (r03). */
    private static void thread(Cast c, Minecraft mc, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float t) {
        if (t < RiverRules.CONCENTRATE || t > RiverRules.STROKES[0][0] + 6 || c.eyes()
                || !(mc.level.getEntity(c.entityId) instanceof AbstractClientPlayer player)) {
            return;
        }
        Vec3 sh = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.LEFT_SHOULDER);
        Vec3 hand = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.LEFT_HAND);
        if (sh == null || hand == null || hand.distanceTo(player.position()) > 3.0D) {
            return;
        }
        float a = (float) PlumVfx.curve(t, RiverRules.CONCENTRATE, 0.0, RiverRules.CONCENTRATE + 6, 1.0, RiverRules.STROKES[0][0], 1.0,
                RiverRules.STROKES[0][0] + 6, 0.0);
        Vec3 d = hand.subtract(sh);
        Vec3 finger = hand.add(d.normalize().scale(0.32D));
        Vec3 side = d.cross(camera.subtract(hand));
        side = side.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 1.0D, 0.0D) : side.normalize();
        int n = 14;
        for (int s = 0; s < 2; s++) {
            Vec3[] p = new Vec3[n + 1];
            double[] w = new double[n + 1];
            for (int i = 0; i <= n; i++) {
                double u = i / (double) n;
                // Нить течёт: волна бежит от плеча к пальцам.
                double wave = 0.035D * Math.sin(u * 9.0D - t * 0.9D + s * 2.0D);
                p[i] = sh.lerp(finger, u).add(side.scale(wave + (s == 0 ? 0.0D : 0.03D)));
                w[i] = (s == 0 ? 0.03D : 0.015D) * (0.6D + 0.4D * Math.sin(Math.PI * u));
            }
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.2D), 0.3F * a, RED);
            fstrip(v, pose, camera, p, w, 0.85F * a, RED);
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.3D), 0.7F * a, PINK_LIGHT);
        }
        // r03: глаза — два компактных красных акцента с белым центром (с 4-го слоя).
        if (c.layer >= 4 && t < RiverRules.RESONANCE + 6) {
            Vec3 head = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.HEAD);
            if (head != null) {
                Vec3 look = Vec3.directionFromRotation(player.getXRot(), player.getYHeadRot());
                Vec3 right = look.cross(new Vec3(0.0D, 1.0D, 0.0D));
                right = right.lengthSqr() < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D) : right.normalize();
                for (int s = -1; s <= 1; s += 2) {
                    Vec3 eye = head.add(look.scale(0.26D)).add(right.scale(0.07D * s)).add(0.0D, 0.0D, 0.0D);
                    Vec3[] p = {eye.subtract(right.scale(0.05D)), eye, eye.add(right.scale(0.05D))};
                    fstrip(v, pose, camera, p, new double[] {0.0D, 0.03D, 0.0D}, 0.9F * a, RED);
                    fstrip(v, pose, camera, p, new double[] {0.0D, 0.012D, 0.0D}, 0.9F * a, WHITE);
                }
            }
        }
    }

    /** Резонанс (r07): поперечные штрихи вдоль клинка и две смещённые копии линии клинка. */
    private static void vibration(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        float t = c.t() + partial;
        if (!RiverRules.afterimages(c.layer) || t < RiverRules.RESONANCE || t > RiverRules.RELEASE || c.hand == null) {
            return;
        }
        float a = (float) PlumVfx.curve(t, RiverRules.RESONANCE, 0.0, RiverRules.RESONANCE + 3, 1.0, RiverRules.GATHER, 1.0,
                RiverRules.AIM, 0.5, RiverRules.RELEASE, 0.0);
        for (Stroke s : c.strokes) {
            float age = clientTicks - s.born() + partial;
            float k = (float) PlumVfx.curve(age, 0.0, 1.0, 1.0, 0.9, 3.0, 0.0);
            Vec3[] p = {s.at(), s.at().add(s.across().scale(s.len() * 0.5D)), s.at().add(s.across().scale(s.len()))};
            double[] w = {0.02D, 0.016D, 0.0D};
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.4D), 0.3F * k * a, RED);
            fstrip(v, pose, camera, p, w, 0.9F * k * a, PINK_LIGHT);
        }
        // Дискретные смещённые контуры клинка: колебание поперёк оси, высокая частота, малая амплитуда.
        Vec3 h = c.handPrev == null ? c.hand : c.handPrev.lerp(c.hand, partial);
        Vec3 tp = c.tipPrev == null ? c.tip : c.tipPrev.lerp(c.tip, partial);
        Vec3 along = tp.subtract(h);
        Vec3 across = along.cross(camera.subtract(h));
        if (across.lengthSqr() < 1.0E-8D) {
            return;
        }
        across = across.normalize();
        for (int k = -1; k <= 1; k += 2) {
            double off = 0.06D * k * (((int) t) % 2 == 0 ? 1.0D : 0.6D);
            Vec3[] p = {h.add(across.scale(off * 0.3D)), h.lerp(tp, 0.5D).add(across.scale(off)), tp.add(across.scale(off * 1.4D))};
            double[] w = {0.0D, 0.025D, 0.01D};
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.0D), 0.25F * a, RED);
            fstrip(v, pose, camera, p, w, 0.6F * a, PINK);
        }
        // Красная оболочка кисти и острия.
        Vec3[] p = {h.subtract(along.scale(0.1D)), h.lerp(tp, 0.5D), tp.add(along.scale(0.05D))};
        double[] w = {0.05D, 0.07D, 0.03D};
        fstrip(v, pose, camera, p, PlumVfx.scale(w, 1.8D), 0.18F * a, RED);
    }

    /** Сбор (r10): неравные красные спиральные ленты вокруг кисти, сходятся к центру. */
    private static void spirals(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float t) {
        int sp = RiverRules.spirals(c.layer);
        if (sp == 0 || t < RiverRules.GATHER || t > RiverRules.RELEASE + 3 || c.gather == null) {
            return;
        }
        Vec3 g = c.gatherPrev == null ? c.gather : c.gatherPrev.lerp(c.gather, t - (int) t);
        float a = (float) PlumVfx.curve(t, RiverRules.GATHER, 0.0, RiverRules.GATHER + 4, 1.0, RiverRules.RELEASE, 1.0,
                RiverRules.RELEASE + 3, 0.0);
        double k = smooth((t - RiverRules.GATHER) / (double) (RiverRules.AIM - RiverRules.GATHER));
        double outer = (2.4D + (0.9D - 2.4D) * k) * c.scale;
        for (int s = 0; s < sp; s++) {
            double rs = new double[] {1.0D, 0.7D, 1.2D}[s];
            double turns = new double[] {0.85D, 0.6D, 0.7D}[s];
            double phase = s * 2.1D + t * (0.35D + 0.15D * s);
            int n = 36;
            Vec3[] p = new Vec3[n + 1];
            double[] w = new double[n + 1];
            for (int i = 0; i <= n; i++) {
                double u = i / (double) n;
                double r = outer * rs * Math.pow(1.0D - u, 1.6D) + 0.04D;
                double th = phase + u * Math.PI * 2.0D * turns;
                double along = (0.5D - u) * 0.6D * c.scale * (1.0D - k);
                p[i] = g.add(c.e1.scale(Math.cos(th) * r)).add(c.e2.scale(Math.sin(th) * r)).add(c.fwd.scale(along));
                w[i] = (0.05D + 0.08D * Math.sin(Math.PI * u)) * c.scale * (s == 0 ? 1.0D : 0.7D);
            }
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.0D), 0.16F * a, RED_SPIRAL);
            fstrip(v, pose, camera, p, w, 0.7F * a, RED_SPIRAL);
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.45D), 0.55F * a, PINK_HOT);
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.15D), 0.8F * a, WHITE);
        }
    }

    /** Дуга взмаха (r14): прорисовывается с рывком руки 100–104, висит, гаснет к 118. */
    private static void arc(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float t) {
        if (c.arc == null || t < RiverRules.RELEASE || t > RiverRules.RELEASE + 20) {
            return;
        }
        float s = t - RiverRules.RELEASE;
        double x = Mth.clamp(s / 4.0D, 0.0D, 1.0D);
        double drawn = 1.0D - Math.pow(1.0D - x, 3.0D);
        float fade = (float) PlumVfx.curve(t, RiverRules.RELEASE + 8, 1.0, RiverRules.RELEASE + 20, 0.0);
        int n = c.arc.length - 1;
        int m = Math.max(2, (int) Math.ceil(n * drawn));
        Vec3[] p = new Vec3[m + 1];
        double[] w = new double[m + 1];
        float[] a = new float[m + 1];
        for (int i = 0; i <= m; i++) {
            double u = i / (double) n;
            p[i] = c.arc[Math.min(n, i)];
            // Серп: узко у концов, широко в середине; голова ярче, пока рисуется.
            double taper = Math.pow(Math.sin(Math.PI * Math.min(1.0D, u * 1.02D + 0.01D)), 0.7D);
            w[i] = 0.13D * c.scale * taper;
            double headGlow = x < 1.0D ? Math.max(0.0D, 1.0D - (drawn - u) * 4.0D) : 0.0D;
            // Непрерывная кромка — только на 2/3 дуги, дальше её добирают лепестки.
            double edge = u < 0.66D ? 1.0D : Math.max(0.0D, 1.0D - (u - 0.66D) / 0.12D);
            // Белые участки с переменными разрывами: след взмаха, а не обруч.
            edge *= Math.sin(u * 37.0D + 1.3D) + 0.6D * Math.sin(u * 13.0D) > -0.55D ? 1.0D : 0.15D;
            a[i] = (float) Mth.clamp((0.8D + 0.2D * headGlow) * fade * edge, 0.0D, 1.0D) * near(p[i], camera);
        }
        PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 1.6D), PlumVfx.scaled(a, 0.16F), CRIMSON);
        PlumVfx.stripVar(v, pose, camera, p, w, PlumVfx.scaled(a, 0.5F), RED);
        PlumVfx.stripVar(v, pose, camera, p, PlumVfx.scale(w, 0.6D), a, WHITE);
    }

    /** Тонкий красно-розовый след меч → узел (r12), живёт до взрыва. */
    private static void link(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float t) {
        if (c.streamFrom == null || t < RiverRules.RELEASE + 1 || c.missTick >= 0 || t > RiverRules.BURST + 3 || c.hand == null) {
            return;
        }
        Vec3 end = c.node();
        double head = Mth.clamp((t - RiverRules.RELEASE) / (double) c.flight, 0.0D, 1.0D);
        float a = (float) PlumVfx.curve(t, RiverRules.RELEASE + 1, 0.0, RiverRules.RELEASE + 3, 1.0, RiverRules.BURST, 1.0,
                RiverRules.BURST + 3, 0.0);
        int n = 30;
        Vec3[] p = new Vec3[n + 1];
        double[] w = new double[n + 1];
        Vec3 from = c.hand;
        for (int i = 0; i <= n; i++) {
            double u = i / (double) n * head;
            Vec3 q = bezier(from, c.streamBend, end, u);
            p[i] = q.add(0.0D, 0.04D * Math.sin(u * 14.0D - t * 0.8D), 0.0D);
            w[i] = (0.06D + 0.06D * Math.sin(Math.PI * u)) * (0.6D + 0.4D * c.scale);
        }
        fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.0D), 0.22F * a, RED);
        fstrip(v, pose, camera, p, w, 0.75F * a, CRIMSON);
        fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.35D), 0.9F * a, PETAL_PINK);
    }

    /** Хвосты лепестков в жгуте и разлёте: чёткие полосы 0,4–1,5 блока, без размытия. */
    private static void streamTails(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Petal p : c.petals) {
            if (p.dead || p.tails < 2 || p.state != Petal.STREAM && p.state != Petal.FREE && p.state != Petal.NODE) {
                continue;
            }
            if (p.state == Petal.FREE && p.freeAge > 10) {
                continue;
            }
            int n = Math.min(p.tails, p.state == Petal.NODE ? 2 : 4);
            Vec3[] q = new Vec3[n + 1];
            q[0] = p.prev.lerp(p.pos, partial);
            for (int i = 1; i <= n; i++) {
                q[i] = p.tail[i - 1];
            }
            if (q[0].distanceToSqr(q[n]) < 0.01D) {
                continue;
            }
            double[] w = new double[n + 1];
            for (int i = 0; i <= n; i++) {
                w[i] = p.size * 0.24D * (1.0D - i / (double) (n + 1));
            }
            float a = p.state == Petal.FREE ? 1.0F - p.freeAge / 10.0F : 1.0F;
            fstrip(v, pose, camera, q, PlumVfx.scale(w, 2.0D), 0.18F * a, p.tone >= 2 ? CRIMSON : PINK);
            fstrip(v, pose, camera, q, w, 0.7F * a, p.tone == 0 ? WHITE : PETAL_PINK);
        }
    }

    /** Узел (r12): перекрёстные лучи-блики вытягиваются, белый центр сжимается и плотнеет. */
    private static void nodeRays(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float t) {
        if (c.contactTick < 0 || c.contact == null || c.burstTick >= 0 && clientTicks - c.burstTick > 1) {
            return;
        }
        float age = clientTicks - c.contactTick + (t - (int) t);
        double k = nodeK(c, t - (int) t);
        Vec3 n = c.contact;
        Vec3 view = camera.subtract(n);
        if (view.lengthSqr() < 1.0E-6D) {
            return;
        }
        view = view.normalize();
        Vec3 ref = Math.abs(view.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 r1 = view.cross(ref).normalize();
        Vec3 r2 = r1.cross(view).normalize();
        double rot = 0.15D + 0.02D * age;
        int rays = Math.max(2, RiverRules.rays(c.layer) / 2 + 2);
        for (int i = 0; i < rays; i++) {
            double ang = rot + i * Math.PI / rays;
            boolean major = i % 2 == 0;
            double len = (major ? 0.4D + 1.0D * k : 0.2D + 0.4D * k) * c.scale;
            Vec3 d = r1.scale(Math.cos(ang)).add(r2.scale(Math.sin(ang)));
            Vec3[] p = {n.subtract(d.scale(len)), n, n.add(d.scale(len))};
            double[] w = {0.0D, (major ? 0.06D : 0.035D) * c.scale, 0.0D};
            float a = (float) (0.5D + 0.5D * k);
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.5D), 0.25F * a, PINK_HOT);
            fstrip(v, pose, camera, p, w, 0.95F * a, WHITE);
        }
    }

    /** Лучи звёздного взрыва: вспыхивают за тик, гаснут за 4–6. */
    private static void burstRays(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        if (c.burstAt == null) {
            return;
        }
        for (Ray r : c.rays) {
            float age = clientTicks - r.born() + partial;
            if (age < 0.0F) {
                continue;
            }
            double grow = Mth.clamp(age / 1.5D, 0.0D, 1.0D);
            float a = (float) PlumVfx.curve(age, 0.0, 1.0, 1.5, 1.0, r.life(), 0.0);
            Vec3 o = c.burstAt;
            Vec3 end = o.add(r.dir().scale(r.len() * grow));
            Vec3[] p = {o, o.lerp(end, 0.3D), end};
            double[] w = {r.width(), r.width() * 0.6D, 0.0D};
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.4D), 0.3F * a, BURST_RIM);
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 1.3D), 0.6F * a, BURST_PINK);
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.5D), 0.95F * a, WHITE);
        }
        // Направленный белый выброс: широкий клин по оси атаки 8–10 блоков (r16).
        float age = clientTicks - c.burstTick + partial;
        if (age < 10.0F && RiverRules.rays(c.layer) >= 6) {
            double len = (2.0D + 7.0D * (1.0D - Math.pow(1.0D - Mth.clamp(age / 3.0D, 0.0D, 1.0D), 3.0D))) * c.scale;
            float a = (float) PlumVfx.curve(age, 0.0, 1.0, 3.0, 1.0, 10.0, 0.0);
            Vec3 o = c.burstAt;
            int n = 12;
            Vec3[] p = new Vec3[n + 1];
            double[] w = new double[n + 1];
            for (int i = 0; i <= n; i++) {
                double u = i / (double) n;
                p[i] = o.add(c.axis.scale(len * u));
                w[i] = 1.75D * c.scale * Math.sin(Math.PI * Math.min(1.0D, 0.15D + u * 0.85D)) * (1.0D - 0.6D * u);
            }
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 1.15D), 0.14F * a, BURST_RIM);
            fstrip(v, pose, camera, p, w, 0.28F * a, BURST_PINK);
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.45D), 0.65F * a, WHITE);
        }
    }

    /** Кольца (всплеск у ног, контакт, взрыв) и веер-отпечаток на земле. */
    private static void rings(Cast c, PoseStack.Pose pose, Vec3 camera, VertexConsumer v, float partial) {
        for (Ring r : c.rings) {
            float age = clientTicks - r.born() + partial;
            if (age < 0.0F) {
                continue;
            }
            float k = Mth.clamp(age / r.life(), 0.0F, 1.0F);
            double rad = 0.3D + (r.rMax() - 0.3D) * (1.0D - Math.pow(1.0D - k, 4.0D));
            float a = (1.0F - k) * (1.0F - k);
            Vec3 nrm = r.normal().normalize();
            Vec3 ref = Math.abs(nrm.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
            Vec3 u1 = nrm.cross(ref).normalize();
            Vec3 u2 = u1.cross(nrm).normalize();
            int n = 40;
            Vec3[] p = new Vec3[n + 1];
            double[] w = new double[n + 1];
            for (int i = 0; i <= n; i++) {
                double ang = i * Math.PI * 2.0D / n;
                p[i] = r.centre().add(u1.scale(Math.cos(ang) * rad)).add(u2.scale(Math.sin(ang) * rad));
                w[i] = r.width() * (0.6D + 0.4D * Math.abs(Math.sin(ang * 3.0D + r.born())));
            }
            boolean blue = r.born() - c.start < RiverRules.BLUE_END;
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 2.0D), 0.25F * a, blue ? BLUE : PINK_HOT);
            fstrip(v, pose, camera, p, PlumVfx.scale(w, 0.4D), 0.9F * a, blue ? BLUE_WHITE : WHITE);
        }
        for (Scar s : c.scars) {
            float age = clientTicks - s.born() + partial;
            double grow = Mth.clamp(age / 2.0D, 0.0D, 1.0D);
            Vec3[] p = {s.a(), s.a().lerp(s.b(), grow), s.a().lerp(s.c(), grow)};
            float a = (float) PlumVfx.curve(age, 0.0, 1.0, 3.0, 1.0, 20.0, 0.5, 70.0, 0.0);
            VfxColour col = age < 4.0F ? WHITE : age < 30.0F ? PINK_HOT : CRIMSON;
            PlumVfx.flatStrip(v, pose, p, new double[] {0.16D, 0.1D, 0.0D}, 0.3F * a, CRIMSON);
            PlumVfx.flatStrip(v, pose, p, new double[] {0.05D, 0.03D, 0.0D}, 0.95F * a, col);
        }
        // Звёздочки рождения (r08): четыре луча, длинный вертикальный.
        for (Star s : c.stars) {
            float age = clientTicks - s.born() + partial;
            float a = (float) PlumVfx.curve(age, 0.0, 1.0, 1.0, 1.0, 3.5, 0.0);
            if (a <= 0.0F) {
                continue;
            }
            Vec3 view = camera.subtract(s.pos());
            if (view.lengthSqr() < 1.0E-6D) {
                continue;
            }
            view = view.normalize();
            Vec3 ref = Math.abs(view.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
            Vec3 r1 = view.cross(ref).normalize();
            Vec3 r2 = r1.cross(view).normalize();
            for (int k = 0; k < 2; k++) {
                Vec3 d = k == 0 ? r2 : r1;
                double len = s.size() * (k == 0 ? 1.0D : 0.6D) * (0.7D + 0.3D * age / 3.5D);
                Vec3[] p = {s.pos().subtract(d.scale(len)), s.pos(), s.pos().add(d.scale(len))};
                fstrip(v, pose, camera, p, new double[] {0.0D, 0.04D, 0.0D}, 0.35F * a, PINK);
                fstrip(v, pose, camera, p, new double[] {0.0D, 0.016D, 0.0D}, 0.95F * a, WHITE);
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

    /** Лепестки (атлас) и их свечение; узел и ядро взрыва — ступени глоу без размытия кадра. */
    private static void petals(Cast c, PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float partial, float t) {
        RenderType pt = MurimRenderTypes.plumPetals();
        VertexConsumer pc = buffers.getBuffer(pt);
        for (Petal p : c.petals) {
            if (p.dead) {
                continue;
            }
            Vec3 at = p.prev.lerp(p.pos, partial);
            float a = petalAlpha(c, p, partial) * near(at, camera);
            if (a <= 0.01F) {
                continue;
            }
            float[] tint = tint(p.tone);
            PlumVfx.petal(pc, pose, camera, at, p.size * 1.15D, p.cell, (clientTicks - p.born + partial) * p.spin, a, tint[0], tint[1], tint[2]);
        }
        for (Flower f : c.flowers) {
            float age = clientTicks - f.born() + partial;
            if (age < 0.0F) {
                continue;
            }
            float a = (float) PlumVfx.curve(age, 0.0, 0.0, 2.0, 1.0, 30.0, 0.9, 46.0, 0.0);
            Vec3 at = f.pos().add(f.vel().scale(age * 0.9D));
            Vec3 view = camera.subtract(at);
            if (view.lengthSqr() < 1.0E-6D) {
                continue;
            }
            view = view.normalize();
            Vec3 ref = Math.abs(view.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
            Vec3 r1 = view.cross(ref).normalize();
            Vec3 r2 = r1.cross(view).normalize();
            for (int k = 0; k < 5; k++) {
                double ang = f.angle() + k * Math.PI * 2.0D / 5.0D + age * 0.03D;
                Vec3 off = r1.scale(Math.cos(ang)).add(r2.scale(Math.sin(ang))).scale(f.size() * 0.5D);
                // Кадр 2 атласа — продолговатый лепесток с выемкой вправо: длинной осью по радиусу, выемкой наружу.
                PlumVfx.petal(pc, pose, camera, at.add(off), f.size() * 0.55D, 2, (float) ang, a * near(at, camera), 1.0F, 0.94F, 0.97F);
            }
        }
        buffers.endBatch(pt);
        RenderType gt = MurimRenderTypes.mote();
        VertexConsumer g = buffers.getBuffer(gt);
        for (Petal p : c.petals) {
            if (p.dead) {
                continue;
            }
            Vec3 at = p.prev.lerp(p.pos, partial);
            float a = petalAlpha(c, p, partial) * near(at, camera);
            if (a <= 0.01F) {
                continue;
            }
            // Светятся как искры ауры: белая точка в сердце, розовый ореол.
            PlumVfx.glow(g, pose, camera, at, p.size * 1.3D, 0.12F * a, p.tone >= 2 ? PINK_HOT : PETAL_PINK);
            PlumVfx.glow(g, pose, camera, at, p.size * 0.45D, 0.35F * a, WHITE);
        }
        // Белый центр вихря (r10): небольшой, 0,18–0,3.
        if (c.gather != null && t >= RiverRules.GATHER && t < RiverRules.RELEASE + 3 && c.petalsMax > 0) {
            Vec3 gc = c.gatherPrev == null ? c.gather : c.gatherPrev.lerp(c.gather, partial);
            double k = smooth((t - RiverRules.GATHER) / 14.0D);
            float a = (float) PlumVfx.curve(t, RiverRules.GATHER, 0.0, RiverRules.GATHER + 6, 1.0, RiverRules.RELEASE, 1.0,
                    RiverRules.RELEASE + 3, 0.0) * near(gc, camera);
            PlumVfx.glow(g, pose, camera, gc, (0.18D + 0.12D * k) * c.scale, 0.95F * a, WHITE);
            PlumVfx.glow(g, pose, camera, gc, (0.5D + 0.3D * k) * c.scale, 0.35F * a, PINK_HOT);
        }
        // Узел (r12): белый центр сжимается 0,65 → 0,12 и становится ярче.
        if (c.contactTick >= 0 && c.contact != null && (c.burstTick < 0 || clientTicks - c.burstTick < 1)) {
            double k = nodeK(c, partial);
            double r = (0.65D + (0.14D - 0.65D) * k) * c.scale;
            PlumVfx.glow(g, pose, camera, c.contact, r, 0.95F, WHITE);
            PlumVfx.glow(g, pose, camera, c.contact, r * 0.55D, 1.0F, WHITE);
            PlumVfx.glow(g, pose, camera, c.contact, r * 2.0D + 0.15D, 0.25F + 0.25F * (float) k, PINK_HOT);
        }
        // Ядро взрыва: до 2,2 блока за 2 тика, гаснет в розовое.
        if (c.burstTick >= 0 && c.burstAt != null) {
            float age = clientTicks - c.burstTick + partial;
            float a = (float) PlumVfx.curve(age, 0.0, 1.0, 3.0, 1.0, 7.0, 0.5, 14.0, 0.0);
            if (a > 0.0F) {
                double r = 2.2D * c.scale * Mth.clamp(age / 2.0D, 0.25D, 1.0D);
                PlumVfx.glow(g, pose, camera, c.burstAt, r, 1.0F * a, WHITE);
                PlumVfx.glow(g, pose, camera, c.burstAt, r * 0.6D, 1.0F * a, WHITE);
                PlumVfx.glow(g, pose, camera, c.burstAt, r * 1.6D, 0.4F * a, BURST_PINK);
                PlumVfx.glow(g, pose, camera, c.burstAt, r * 2.6D, 0.15F * a, BURST_RIM);
            }
        }
        buffers.endBatch(gt);
    }

    /** Лепесток проявляется за 2 тика, в узле тает в свет у центра, в опадании гаснет к концу. */
    private static float petalAlpha(Cast c, Petal p, float partial) {
        float a = Mth.clamp((clientTicks - p.born + partial) / 2.0F, 0.0F, 1.0F);
        if (p.state == Petal.NODE) {
            // Масса исчезает в точке: к концу сжатия от оболочки остаётся 15 %.
            a *= (float) (1.0D - 0.85D * nodeK(c, partial));
        }
        if (c.eyes() && (p.state == Petal.FIELD || p.state == Petal.GATHER || p.state == Petal.STREAM && c.t() < p.leave)) {
            // От первого лица поле полупрозрачно: цель видна сквозь него.
            a *= 0.55F;
        }
        if (p.state == Petal.FREE) {
            float life = 70.0F + (float) (p.s3 * 40.0D);
            a *= Mth.clamp((life - p.freeAge - partial) / 16.0F, 0.0F, 1.0F);
        }
        return a;
    }

    private static float[] tint(int tone) {
        return switch (tone) {
            case 0 -> new float[] {1.0F, 0.97F, 0.99F};
            case 1 -> new float[] {1.0F, 0.86F, 0.92F};
            case 2 -> new float[] {1.0F, 0.66F, 0.8F};
            default -> new float[] {0.98F, 0.4F, 0.58F};
        };
    }

    private RiverVfx() {
    }
}
