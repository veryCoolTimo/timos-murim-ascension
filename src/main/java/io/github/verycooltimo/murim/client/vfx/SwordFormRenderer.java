package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.TechniquePhase;
import io.github.verycooltimo.murim.technique.SwordFormRules;
import io.github.verycooltimo.murim.technique.TechniqueBehavior;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
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
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Эффекты формы меча — Меч Шести Равновесий (концепт 01.10, референсы «huashan basic sword»).
 *
 * <ul>
 *   <li><b>След клинка</b> — сухая белая лента по НАСТОЯЩЕМУ движению меча: каждый кадр снимаются
 *       середина и кончик клинка с анимированной модели, лента натягивается между ними по
 *       времени. Белое ядро {@code #F2F4F7}, рваные серые края — текстура сухой кисти;
 *       без свечения и лепестков (на референсах мастер бьёт «сухо»).</li>
 *   <li><b>Пыль</b> — частицы: на каждом ударе у передней ступни взлетает кольцо белой пыли,
 *       расползается, тормозит и оседает.</li>
 *   <li><b>Широкий разрез</b> (слой 3+) — последний удар серии: плоский серп через всю площадь
 *       и низкая волна пыли по плитам.</li>
 *   <li><b>Единение с мечом</b> (слой 4) — холодный синий столб из клинка в небо, прямой
 *       раскол земли вперёд со светом в шве, поднятые обломки, клубы пыли у основания.</li>
 * </ul>
 *
 * <p>След виден только со стороны: в первом лице модели игрока нет, и снимать клинок не с чего.
 * Пыль, разрез, столб и раскол видны и от первого лица.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class SwordFormRenderer {

    private static final VfxColour WHITE = hex(0xF2F4F7);
    private static final VfxColour GREY = hex(0xA9AEAA);
    private static final VfxColour DUST = hex(0xE9EAE4);
    private static final VfxColour BLUE = hex(0x6FA8FF);
    private static final VfxColour BLUE_CORE = hex(0xF0FFFF);
    private static final VfxColour STONE = hex(0x50535C);

    /** Сколько тиков живёт кусок следа. */
    // Дуги серии держатся дольше и накладываются друг на друга (разбор codex 01.10).
    private static final float TRAIL_LIFE = 6.0F;

    private static final Map<Integer, Form> ACTIVE = new ConcurrentHashMap<>();
    private static int clientTicks;

    private record Sample(float age, Vec3 mid, Vec3 tip) {
    }

    private static final class Mote {
        Vec3 pos;
        Vec3 prev;
        Vec3 vel;
        int age;
        final int life;
        final float size;
        final boolean stone;

        Mote(Vec3 pos, Vec3 vel, int life, float size, boolean stone) {
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.life = life;
            this.size = size;
            this.stone = stone;
        }
    }

    private static final class Form {
        final int sourceId;
        final int startTick;
        final int layer;
        final float impactAge;
        final double wideReach;
        final double crackLength;
        final List<Sample> samples = new ArrayList<>();
        final List<Mote> motes = new ArrayList<>();
        final Random random;
        int strikesFired;
        /** Возраст широкого разреза, столба и раскола (−1 — ещё не было). */
        float wideAt = -1.0F;
        float unityAt = -1.0F;
        Vec3 unityOrigin;
        Vec3 unityDir;
        Vec3 pillarAt;
        Vec3 wideCentre;
        float wideYaw;

        /** Копия для сглаженного следа: те же параметры, пустой список замеров. */
        Form(Form other) {
            this.sourceId = other.sourceId;
            this.startTick = other.startTick;
            this.layer = other.layer;
            this.impactAge = other.impactAge;
            this.wideReach = other.wideReach;
            this.crackLength = other.crackLength;
            this.random = other.random;
            this.strikesFired = other.strikesFired;
        }

        Form(int sourceId, TechniqueDefinition definition, int layer) {
            this.sourceId = sourceId;
            this.startTick = clientTicks;
            this.layer = layer;
            this.impactAge = definition.startTickOf(TechniquePhase.IMPACT) - 1.0F;
            TechniqueBehavior.SwordForm b = (TechniqueBehavior.SwordForm) definition.behavior();
            this.wideReach = b.wideReach();
            this.crackLength = b.crackLength();
            this.random = new Random(sourceId * 31L + startTick);
        }

        float age(float partial) {
            return clientTicks - startTick + partial;
        }

        float strikeAge(int index) {
            return impactAge + index * SwordFormRules.SERIES_GAP;
        }

        boolean done() {
            return age(0) > strikeAge(SwordFormRules.strikes(layer) - 1) + 40.0F && motes.isEmpty();
        }
    }

    public static void start(int sourceId, TechniqueDefinition definition, int layer) {
        if (definition == null || !(definition.behavior() instanceof TechniqueBehavior.SwordForm)) {
            return;
        }
        ACTIVE.put(sourceId, new Form(sourceId, definition, layer));
    }

    public static void cancel(int sourceId) {
        ACTIVE.remove(sourceId);
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            ACTIVE.clear();
            return;
        }
        if (minecraft.isPaused()) {
            return;
        }
        clientTicks++;
        Iterator<Form> it = ACTIVE.values().iterator();
        while (it.hasNext()) {
            Form f = it.next();
            Entity e = minecraft.level.getEntity(f.sourceId);
            if (e != null) {
                fire(f, e);
            }
            step(f);
            if (f.done()) {
                it.remove();
            }
        }
    }

    /** Наступил момент удара — пыль, разрез, столб и раскол. */
    private static void fire(Form f, Entity e) {
        float age = f.age(0);
        int strikes = SwordFormRules.strikes(f.layer);
        while (f.strikesFired < strikes && age >= f.strikeAge(f.strikesFired)) {
            int index = f.strikesFired++;
            boolean last = index == strikes - 1;
            Vec3 look = Vec3.directionFromRotation(0.0F, e.getYRot());
            Vec3 foot = e.position().add(look.scale(0.45D));
            dustRing(f, foot, last && SwordFormRules.wideFinish(f.layer) ? 1.6D : 1.0D);
            // Пыль и в точке удара — там, куда клинок доходит до земли (разбор codex).
            dustRing(f, e.position().add(look.scale(2.0D)), 0.8D);
            if (last && SwordFormRules.wideFinish(f.layer)) {
                f.wideAt = age;
                f.wideCentre = e.position().add(0.0D, 1.05D, 0.0D);
                f.wideYaw = e.getYRot();
                dustWave(f, e.position(), look);
            }
            if (last && SwordFormRules.unity(f.layer)) {
                f.unityAt = age;
                f.unityOrigin = e.position().add(look.scale(0.8D));
                f.unityDir = look;
                // Столб — впереди по взгляду: в момент удара кончик ещё над головой, и столб
                // от него вставал за спиной (кадры 01.10).
                f.pillarAt = e.position().add(look.scale(1.3D));
                crackDebris(f);
            }
        }
    }

    private static void dustRing(Form f, Vec3 at, double scale) {
        Random r = f.random;
        int n = (int) (24 * scale);
        for (int i = 0; i < n; i++) {
            double a = r.nextDouble() * Math.PI * 2.0D;
            double speed = (0.05D + 0.07D * r.nextDouble()) * scale;
            Vec3 dir = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
            f.motes.add(new Mote(at.add(dir.scale(0.2D)).add(0.0D, 0.18D, 0.0D),
                    dir.scale(speed).add(0.0D, 0.015D + 0.025D * r.nextDouble(), 0.0D),
                    16 + r.nextInt(12), (float) (0.22D + 0.16D * r.nextDouble()), false));
        }
    }

    /** Низкая волна пыли по плитам вперёд от широкого разреза. */
    private static void dustWave(Form f, Vec3 feet, Vec3 look) {
        Random r = f.random;
        for (int i = 0; i < 46; i++) {
            double a = (r.nextDouble() - 0.5D) * Math.toRadians(220.0D);
            Vec3 dir = new Vec3(look.x * Math.cos(a) - look.z * Math.sin(a), 0.0D, look.x * Math.sin(a) + look.z * Math.cos(a));
            f.motes.add(new Mote(feet.add(dir.scale(0.6D)).add(0.0D, 0.08D, 0.0D),
                    dir.scale(0.2D + 0.18D * r.nextDouble()).add(0.0D, 0.01D, 0.0D),
                    18 + r.nextInt(14), (float) (0.18D + 0.14D * r.nextDouble()), false));
        }
    }

    /** Раскол: обломки камня вдоль линии вверх и клубы пыли у основания столба. */
    private static void crackDebris(Form f) {
        Random r = f.random;
        for (int i = 0; i < 30; i++) {
            double along = r.nextDouble() * f.crackLength;
            Vec3 at = f.unityOrigin.add(f.unityDir.scale(along)).add((r.nextDouble() - 0.5D) * 0.6D, 0.05D,
                    (r.nextDouble() - 0.5D) * 0.6D);
            f.motes.add(new Mote(at, new Vec3((r.nextDouble() - 0.5D) * 0.06D, 0.18D + 0.2D * r.nextDouble(),
                    (r.nextDouble() - 0.5D) * 0.06D), 26 + r.nextInt(14), (float) (0.06D + 0.14D * r.nextDouble()), true));
        }
        // Стена пыли вдоль всего раскола.
        for (int i = 0; i < 50; i++) {
            double along = r.nextDouble() * f.crackLength;
            Vec3 at = f.unityOrigin.add(f.unityDir.scale(along)).add(0.0D, 0.15D, 0.0D);
            Vec3 side = new Vec3(-f.unityDir.z, 0.0D, f.unityDir.x).scale(r.nextBoolean() ? 1.0D : -1.0D);
            f.motes.add(new Mote(at, side.scale(0.06D + 0.08D * r.nextDouble()).add(0.0D, 0.03D + 0.03D * r.nextDouble(), 0.0D),
                    24 + r.nextInt(18), (float) (0.3D + 0.3D * r.nextDouble()), false));
        }
        for (int i = 0; i < 40; i++) {
            double a = r.nextDouble() * Math.PI * 2.0D;
            Vec3 dir = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
            f.motes.add(new Mote(f.pillarAt.add(dir.scale(0.4D)).add(0.0D, 0.1D, 0.0D),
                    dir.scale(0.08D + 0.12D * r.nextDouble()).add(0.0D, 0.02D, 0.0D),
                    26 + r.nextInt(20), (float) (0.3D + 0.25D * r.nextDouble()), false));
        }
    }

    private static void step(Form f) {
        Iterator<Mote> it = f.motes.iterator();
        while (it.hasNext()) {
            Mote m = it.next();
            m.prev = m.pos;
            if (++m.age >= m.life) {
                it.remove();
                continue;
            }
            if (m.stone) {
                m.vel = new Vec3(m.vel.x * 0.98D, m.vel.y - 0.03D, m.vel.z * 0.98D);
            } else {
                // Пыль: тормозит, чуть всплывает и оседает.
                m.vel = new Vec3(m.vel.x * 0.88D, m.vel.y * 0.9D - 0.001D, m.vel.z * 0.88D);
            }
            m.pos = m.pos.add(m.vel);
        }
    }

    @SubscribeEvent
    static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || ACTIVE.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        poseStack.pushPose();
        try {
            poseStack.translate(-camera.x, -camera.y, -camera.z);
            PoseStack.Pose pose = poseStack.last();
            for (Form f : ACTIVE.values()) {
                float age = f.age(partial);
                Entity e = minecraft.level.getEntity(f.sourceId);
                if (e instanceof AbstractClientPlayer player) {
                    sample(f, player, age);
                }
                trail(f, pose, camera, buffers, age);
                if (f.wideAt >= 0.0F) {
                    wide(f, pose, camera, buffers, age - f.wideAt);
                }
                if (f.unityAt >= 0.0F) {
                    unity(f, pose, camera, buffers, age - f.unityAt);
                }
                motes(f, pose, camera, buffers, partial);
            }
        } finally {
            poseStack.popPose();
        }
    }

    /** Снимает клинок в окнах ударов: чуть до кадра попадания и чуть после. */
    private static void sample(Form f, AbstractClientPlayer player, float age) {
        boolean window = false;
        for (int i = 0; i < SwordFormRules.strikes(f.layer); i++) {
            float s = f.strikeAge(i);
            // Анимация приходит на тик позже события и доводит клинок вниз за ~2 тика после кадра
            // попадания — окно до +4, иначе след обрывался над головой (кадры 01.10).
            if (age >= s - 3.0F && age <= s + 4.0F) {
                window = true;
                break;
            }
        }
        if (window) {
            Vec3 hand = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.RIGHT_HAND);
            Vec3 bladeTip = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.BLADE_TIP);
            // Лента от середины клинка до кончика, вынесенного на 40 %: настоящий меч короткий,
            // и след по нему читался серой складкой, а не серпом (кадры 01.10).
            Vec3 mid = hand == null || bladeTip == null ? null : hand.lerp(bladeTip, 0.5D);
            Vec3 tip = hand == null || bladeTip == null ? null : hand.add(bladeTip.subtract(hand).scale(2.2D));
            if (mid != null && tip != null) {
                Sample last = f.samples.isEmpty() ? null : f.samples.get(f.samples.size() - 1);
                if (last == null || age - last.age > 0.05F) {
                    // Разрыв между окнами — новый мазок, а не перемычка через полэкрана.
                    if (last != null && age - last.age > 1.5F) {
                        f.samples.add(new Sample(-1.0F, mid, tip));
                    }
                    f.samples.add(new Sample(age, mid, tip));
                }
            }
        }
        f.samples.removeIf(s -> s.age >= 0.0F && age - s.age > TRAIL_LIFE);
    }

    /**
     * Лента следа: между соседними замерами — четырёхугольник от середины к кончику клинка.
     * U вдоль времени (свежий край — плотный), V поперёк — от середины к кончику.
     */
    private static void trail(Form f, PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float age) {
        if (f.samples.size() < 2) {
            return;
        }
        f = smoothed(f);
        boolean blue = SwordFormRules.unity(f.layer) && f.strikesFired >= SwordFormRules.strikes(f.layer);
        VfxColour core = blue ? BLUE_CORE : WHITE;
        VfxColour edge = blue ? BLUE : GREY;
        RenderType ink = MurimRenderTypes.ink();
        VertexConsumer c = buffers.getBuffer(ink);
        for (int i = 1; i < f.samples.size(); i++) {
            Sample a = f.samples.get(i - 1);
            Sample b = f.samples.get(i);
            if (a.age < 0.0F || b.age < 0.0F) {
                continue;
            }
            float ua = Mth.clamp((age - a.age) / TRAIL_LIFE, 0.0F, 1.0F);
            float ub = Mth.clamp((age - b.age) / TRAIL_LIFE, 0.0F, 1.0F);
            float aa = (1.0F - ua) * (1.0F - ua) * 0.95F;
            float ab = (1.0F - ub) * (1.0F - ub) * 0.95F;
            Vec3 n = camera.subtract(b.tip).normalize();
            // Сухой край со стороны рукояти — серый, ядро к кончику — белое.
            VfxDraw.vertex(c, pose, a.mid, n, ua, 0.0F, aa * 0.6F, edge.red(), edge.green(), edge.blue());
            VfxDraw.vertex(c, pose, b.mid, n, ub, 0.0F, ab * 0.6F, edge.red(), edge.green(), edge.blue());
            VfxDraw.vertex(c, pose, b.tip, n, ub, 1.0F, ab, core.red(), core.green(), core.blue());
            VfxDraw.vertex(c, pose, a.tip, n, ua, 1.0F, aa, core.red(), core.green(), core.blue());
        }
        buffers.endBatch(ink);
        // Лёгкий светлый подслой: ночью сухая серо-белая лента иначе тонет (кадры 01.10).
        VertexConsumer sheen = buffers.getBuffer(MurimRenderTypes.ribbon());
        for (int i = 1; i < f.samples.size(); i++) {
            Sample a = f.samples.get(i - 1);
            Sample b = f.samples.get(i);
            if (a.age < 0.0F || b.age < 0.0F) {
                continue;
            }
            float ua = Mth.clamp((age - a.age) / TRAIL_LIFE, 0.0F, 1.0F);
            float ub = Mth.clamp((age - b.age) / TRAIL_LIFE, 0.0F, 1.0F);
            Vec3 n = camera.subtract(b.tip).normalize();
            VfxDraw.vertex(sheen, pose, a.mid, n, 0.0F, 0.0F, 0.0F, core.red(), core.green(), core.blue());
            VfxDraw.vertex(sheen, pose, b.mid, n, 1.0F, 0.0F, 0.0F, core.red(), core.green(), core.blue());
            VfxDraw.vertex(sheen, pose, b.tip, n, 1.0F, 1.0F, 0.55F * (1.0F - ub), core.red(), core.green(), core.blue());
            VfxDraw.vertex(sheen, pose, a.tip, n, 0.0F, 1.0F, 0.55F * (1.0F - ua), core.red(), core.green(), core.blue());
        }
        buffers.endBatch(MurimRenderTypes.ribbon());
        if (blue) {
            VertexConsumer g = buffers.getBuffer(MurimRenderTypes.ribbon());
            for (int i = 1; i < f.samples.size(); i++) {
                Sample a = f.samples.get(i - 1);
                Sample b = f.samples.get(i);
                if (a.age < 0.0F || b.age < 0.0F) {
                    continue;
                }
                float ua = Mth.clamp((age - a.age) / TRAIL_LIFE, 0.0F, 1.0F);
                VfxDraw.segment(g, pose, a.tip, b.tip, camera, 0.12D, 0.6F * (1.0F - ua), BLUE.red(), BLUE.green(), BLUE.blue());
            }
            buffers.endBatch(MurimRenderTypes.ribbon());
        }
    }

    /**
     * Сглаживание следа: во взмахе всего 2–3 замера клинка за кадр, и лента выходила
     * ломаной — «сложенным листом» (кадры 01.10). Между замерами — по 5 точек сплайна
     * Катмулла — Рома на обеих направляющих (середина и кончик).
     */
    private static Form smoothed(Form f) {
        Form copy = new Form(f);
        List<Sample> in = f.samples;
        for (int i = 0; i < in.size() - 1; i++) {
            Sample p1 = in.get(i);
            Sample p2 = in.get(i + 1);
            if (p1.age < 0.0F || p2.age < 0.0F) {
                copy.samples.add(p1);
                continue;
            }
            Sample p0 = i > 0 && in.get(i - 1).age >= 0.0F ? in.get(i - 1) : p1;
            Sample p3 = i + 2 < in.size() && in.get(i + 2).age >= 0.0F ? in.get(i + 2) : p2;
            for (int k = 0; k < 5; k++) {
                double t = k / 5.0D;
                copy.samples.add(new Sample(Mth.lerp((float) t, p1.age, p2.age),
                        catmull(p0.mid, p1.mid, p2.mid, p3.mid, t), catmull(p0.tip, p1.tip, p2.tip, p3.tip, t)));
            }
        }
        copy.samples.add(in.get(in.size() - 1));
        return copy;
    }

    private static Vec3 catmull(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return p1.scale(2.0D).add(p2.subtract(p0).scale(t)).add(p0.scale(2.0D).subtract(p1.scale(5.0D)).add(p2.scale(4.0D)).subtract(p3).scale(t2))
                .add(p1.scale(3.0D).subtract(p0).subtract(p2.scale(3.0D)).add(p3).scale(t3)).scale(0.5D);
    }

    /**
     * Широкий разрез «начальной формы»: плоский серп на уровне груди, раскрывается за три тика
     * на 200° и гаснет за полсекунды. Внутренний край рваный (сухая кисть), внешний — белый.
     */
    private static void wide(Form f, PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float t) {
        if (t > 12.0F) {
            return;
        }
        float sweep = Mth.clamp(t / 3.0F, 0.0F, 1.0F);
        float alpha = t < 3.0F ? 1.0F : (float) Math.pow(1.0F - (t - 3.0F) / 9.0F, 1.5D);
        double outer = f.wideReach;
        double inner = outer * 0.55D;
        double yaw = Math.toRadians(f.wideYaw);
        int seg = 28;
        double span = Math.toRadians(320.0D) * sweep;
        double start = -span / 2.0D;
        RenderType ink = MurimRenderTypes.ink();
        VertexConsumer c = buffers.getBuffer(ink);
        Vec3 up = new Vec3(0.0D, 1.0D, 0.0D);
        for (int i = 0; i < seg; i++) {
            double a0 = start + span * i / seg;
            double a1 = start + span * (i + 1) / seg;
            // Толщина серпа: тоньше на концах.
            double w0 = Math.sin(Math.PI * i / seg);
            double w1 = Math.sin(Math.PI * (i + 1) / seg);
            Vec3 d0 = dir(yaw, a0);
            Vec3 d1 = dir(yaw, a1);
            // Плоскость разреза наклонена на ~12°: сбоку плоский серп иначе виден ребром —
            // тонкой линией (кадры 01.10).
            double tilt0 = Math.sin(a0) * 0.22D;
            double tilt1 = Math.sin(a1) * 0.22D;
            Vec3 i0 = f.wideCentre.add(d0.scale(outer - (outer - inner) * w0)).add(0.0D, tilt0 * (outer - (outer - inner) * w0), 0.0D);
            Vec3 i1 = f.wideCentre.add(d1.scale(outer - (outer - inner) * w1)).add(0.0D, tilt1 * (outer - (outer - inner) * w1), 0.0D);
            Vec3 o0 = f.wideCentre.add(d0.scale(outer)).add(0.0D, tilt0 * outer, 0.0D);
            Vec3 o1 = f.wideCentre.add(d1.scale(outer)).add(0.0D, tilt1 * outer, 0.0D);
            float u0 = (float) i / seg;
            float u1 = (float) (i + 1) / seg;
            VfxDraw.vertex(c, pose, i0, up, u0, 0.0F, alpha * 0.4F, GREY.red(), GREY.green(), GREY.blue());
            VfxDraw.vertex(c, pose, i1, up, u1, 0.0F, alpha * 0.4F, GREY.red(), GREY.green(), GREY.blue());
            VfxDraw.vertex(c, pose, o1, up, u1, 1.0F, alpha, WHITE.red(), WHITE.green(), WHITE.blue());
            VfxDraw.vertex(c, pose, o0, up, u0, 1.0F, alpha, WHITE.red(), WHITE.green(), WHITE.blue());
        }
        buffers.endBatch(ink);
        // Тонкие параллельные следы снаружи и внутри серпа.
        VertexConsumer g = buffers.getBuffer(MurimRenderTypes.ribbon());
        for (double k : new double[] {1.12D, 0.48D}) {
            for (int i = 0; i < seg; i++) {
                double a0 = start + span * i / seg;
                double a1 = start + span * (i + 1) / seg;
                Vec3 p0 = f.wideCentre.add(dir(yaw, a0).scale(outer * k)).add(0.0D, Math.sin(a0) * 0.22D * outer * k, 0.0D);
                Vec3 p1 = f.wideCentre.add(dir(yaw, a1).scale(outer * k)).add(0.0D, Math.sin(a1) * 0.22D * outer * k, 0.0D);
                VfxDraw.segment(g, pose, p0, p1, camera, 0.03D, 0.5F * alpha * (float) Math.sin(Math.PI * i / seg), WHITE.red(), WHITE.green(), WHITE.blue());
            }
        }
        buffers.endBatch(MurimRenderTypes.ribbon());
    }

    private static Vec3 dir(double yaw, double a) {
        // Вперёд по взгляду — направление (−sin yaw, cos yaw); угол a отсчитывается от него.
        double ang = yaw + a;
        return new Vec3(-Math.sin(ang), 0.0D, Math.cos(ang));
    }

    /** Синий столб из клинка в небо и раскол земли вперёд со светом в шве. */
    private static void unity(Form f, PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float t) {
        if (t > 36.0F) {
            return;
        }
        VertexConsumer g = buffers.getBuffer(MurimRenderTypes.ribbon());
        // Столб: вырастает за 2 тика, держится, гаснет к 30.
        float rise = Mth.clamp(t / 2.0F, 0.0F, 1.0F);
        float fade = t < 12.0F ? 1.0F : Mth.clamp(1.0F - (t - 12.0F) / 18.0F, 0.0F, 1.0F);
        double height = 14.0D * rise;
        Vec3 base = f.pillarAt;
        Vec3 top = base.add(0.0D, height, 0.0D);
        float flicker = 0.9F + 0.1F * Mth.sin(t * 2.3F);
        // Толще в 2,5 раза, с неоднородной оболочкой: три полосы с разной фазой мерцания.
        VfxDraw.segment(g, pose, base, top, camera, 2.6D, 0.25F * fade * flicker, BLUE.red(), BLUE.green(), BLUE.blue());
        VfxDraw.segment(g, pose, base.add(0.15D * Mth.sin(t * 1.7F), 0.0D, 0.0D), top, camera, 1.3D, 0.45F * fade, BLUE.red() * 1.2F, BLUE.green() * 1.1F, 1.0F);
        VfxDraw.segment(g, pose, base, top, camera, 0.4D, 0.95F * fade, BLUE_CORE.red(), BLUE_CORE.green(), BLUE_CORE.blue());
        flatDisc(g, pose, base.add(0.0D, 0.03D, 0.0D), 2.2D * rise, 0.5F * fade, BLUE);
        // Раскол: открывается за 5 тиков по прямой, зубчатый, свет в шве.
        float open = Mth.clamp(t / 5.0F, 0.0F, 1.0F);
        float crackFade = Mth.clamp(1.0F - (t - 18.0F) / 18.0F, 0.0F, 1.0F);
        buffers.endBatch(MurimRenderTypes.ribbon());
        Random r = new Random(f.sourceId * 7L + f.startTick);
        Vec3 side = new Vec3(-f.unityDir.z, 0.0D, f.unityDir.x);
        // Тёмный разлом под светом: раскол — дыра в плитах, а не линия на целой поверхности.
        {
            Random rd = new Random(f.sourceId * 7L + f.startTick);
            RenderType dark = MurimRenderTypes.solid();
            VertexConsumer dc = buffers.getBuffer(dark);
            Vec3 p = f.unityOrigin.add(0.0D, 0.025D, 0.0D);
            for (int i = 0; i < 16 && (float) i / 16 < Mth.clamp(t / 5.0F, 0.0F, 1.0F); i++) {
                Vec3 q = f.unityOrigin.add(f.unityDir.scale(f.crackLength * (i + 1) / 16))
                        .add(side.scale((rd.nextDouble() - 0.5D) * 0.35D)).add(0.0D, 0.025D, 0.0D);
                flat(dc, pose, p, q, 0.42D * (1.0F - i / 16.0F * 0.5F), 0.85F * Mth.clamp(1.0F - (t - 18.0F) / 18.0F, 0.0F, 1.0F),
                        new VfxColour(0.04F, 0.05F, 0.08F));
                p = q;
            }
            buffers.endBatch(dark);
        }
        g = buffers.getBuffer(MurimRenderTypes.ribbon());
        Vec3 at = f.unityOrigin.add(0.0D, 0.03D, 0.0D);
        int segs = 16;
        for (int i = 0; i < segs && (float) i / segs < open; i++) {
            Vec3 next = f.unityOrigin.add(f.unityDir.scale(f.crackLength * (i + 1) / segs))
                    .add(side.scale((r.nextDouble() - 0.5D) * 0.35D)).add(0.0D, 0.03D, 0.0D);
            float w = 1.0F - (float) i / segs * 0.6F;
            flat(g, pose, at, next, 0.32D * w, 0.45F * crackFade, BLUE);
            flat(g, pose, at, next, 0.09D * w, 1.0F * crackFade, BLUE_CORE);
            at = next;
        }
        buffers.endBatch(MurimRenderTypes.ribbon());
    }

    private static void motes(Form f, PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float partial) {
        if (f.motes.isEmpty()) {
            return;
        }
        RenderType dustType = MurimRenderTypes.impurity();
        VertexConsumer d = buffers.getBuffer(dustType);
        for (Mote m : f.motes) {
            if (m.stone) {
                continue;
            }
            float t = (m.age + partial) / m.life;
            Vec3 at = m.prev.lerp(m.pos, partial);
            float a = 0.8F * (float) Math.sin(Math.PI * Math.min(1.0F, t * 1.2F + 0.05F)) * (1.0F - t);
            VfxDraw.billboard(d, pose, at, camera, m.size * (0.6F + 1.2F * t), a, DUST.red(), DUST.green(), DUST.blue());
        }
        buffers.endBatch(dustType);
        RenderType solid = MurimRenderTypes.solid();
        VertexConsumer s = buffers.getBuffer(solid);
        for (Mote m : f.motes) {
            if (!m.stone) {
                continue;
            }
            float t = (m.age + partial) / m.life;
            Vec3 at = m.prev.lerp(m.pos, partial);
            VfxDraw.billboard(s, pose, at, camera, m.size, Mth.clamp((1.0F - t) * 4.0F, 0.0F, 1.0F),
                    STONE.red(), STONE.green(), STONE.blue());
        }
        buffers.endBatch(solid);
    }

    /** Круглое пятно света на полу. */
    private static void flatDisc(VertexConsumer c, PoseStack.Pose pose, Vec3 centre, double r, float alpha, VfxColour col) {
        Vec3 n = new Vec3(0.0D, 1.0D, 0.0D);
        int seg = 20;
        for (int i = 0; i < seg; i++) {
            double a0 = Math.PI * 2.0D * i / seg;
            double a1 = Math.PI * 2.0D * (i + 1) / seg;
            Vec3 p0 = centre.add(Math.cos(a0) * r, 0.0D, Math.sin(a0) * r);
            Vec3 p1 = centre.add(Math.cos(a1) * r, 0.0D, Math.sin(a1) * r);
            VfxDraw.vertex(c, pose, centre, n, 0.5F, 0.5F, alpha, col.red(), col.green(), col.blue());
            VfxDraw.vertex(c, pose, centre, n, 0.5F, 0.5F, alpha, col.red(), col.green(), col.blue());
            VfxDraw.vertex(c, pose, p1, n, 0.5F, 0.0F, 0.0F, col.red(), col.green(), col.blue());
            VfxDraw.vertex(c, pose, p0, n, 0.5F, 0.0F, 0.0F, col.red(), col.green(), col.blue());
        }
    }

    /** Отрезок, лежащий на полу. */
    private static void flat(VertexConsumer c, PoseStack.Pose pose, Vec3 a, Vec3 z, double width, float alpha, VfxColour col) {
        Vec3 d = z.subtract(a);
        Vec3 s = new Vec3(-d.z, 0.0D, d.x);
        if (s.lengthSqr() < 1.0E-9D) {
            return;
        }
        s = s.normalize().scale(width);
        Vec3 n = new Vec3(0.0D, 1.0D, 0.0D);
        VfxDraw.vertex(c, pose, a.subtract(s), n, 0.0F, 0.0F, alpha, col.red(), col.green(), col.blue());
        VfxDraw.vertex(c, pose, z.subtract(s), n, 1.0F, 0.0F, alpha, col.red(), col.green(), col.blue());
        VfxDraw.vertex(c, pose, z.add(s), n, 1.0F, 1.0F, alpha, col.red(), col.green(), col.blue());
        VfxDraw.vertex(c, pose, a.add(s), n, 0.0F, 1.0F, alpha, col.red(), col.green(), col.blue());
    }

    private static VfxColour hex(int c) {
        return new VfxColour((c >> 16 & 255) / 255.0F, (c >> 8 & 255) / 255.0F, (c & 255) / 255.0F);
    }

    private SwordFormRenderer() {
    }
}
