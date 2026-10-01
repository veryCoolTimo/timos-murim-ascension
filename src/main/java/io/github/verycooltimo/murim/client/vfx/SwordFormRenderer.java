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
 * Эффекты формы меча — Меч Шести Равновесий (референсы «huashan basic sword»).
 *
 * <p><b>Эффекты растут со слоем освоения</b> (автор 01.10: «когда 0 слоёв — эффектов нет»):
 * <ol start="0">
 *   <li>только движение тела, без эффектов;</li>
 *   <li>след клинка — один сплошной белый серп с острым хвостом;</li>
 *   <li>+ клубы пыли у передней ноги и в точке удара — спрайты в манере манхвы: белое
 *       с контуром тушью, без размытия;</li>
 *   <li>+ широкий разрез: три тонкие дуги эллипсом вокруг бойца и волна низких клубов по плитам;</li>
 *   <li>+ прямая тёмная борозда в плитах по линии удара с раскрошенными краями, стена клубов
 *       по её бокам и обломки (кадр 8 «единения с мечом»).</li>
 * </ol>
 *
 * <p>Синего света нет: на референсах мастер бьёт «сухо», цвет — белый, серый и тушь.
 * Первая версия со столбом и светящимся расколом отклонена автором.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class SwordFormRenderer {

    private static final VfxColour WHITE = hex(0xF4F5F2);
    private static final VfxColour GROOVE = hex(0x1E2026);
    private static final VfxColour GROOVE_EDGE = hex(0x6B6E76);
    private static final VfxColour STONE = hex(0x50535C);

    /** Сколько тиков держится штрих следа. */
    private static final float TRAIL_LIFE = 5.0F;


    private static final Map<Integer, Form> ACTIVE = new ConcurrentHashMap<>();
    private static int clientTicks;

    private record Sample(float age, Vec3 hand, Vec3 dir) {
    }

    private static final class Puff {
        Vec3 pos;
        Vec3 prev;
        Vec3 vel;
        int age;
        final int life;
        final float size;
        final int cell;
        final boolean stone;

        Puff(Vec3 pos, Vec3 vel, int life, float size, int cell, boolean stone) {
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.life = life;
            this.size = size;
            this.cell = cell;
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
        final List<Puff> puffs = new ArrayList<>();
        final Random random;
        int strikesFired;
        float wideAt = -1.0F;
        float grooveAt = -1.0F;
        Vec3 grooveFrom;
        Vec3 grooveDir;
        Vec3 wideCentre;
        float wideYaw;

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
            return age(0) > strikeAge(SwordFormRules.strikes(layer) - 1) + 100.0F && puffs.isEmpty();
        }
    }

    public static void start(int sourceId, TechniqueDefinition definition, int layer) {
        if (definition == null || !(definition.behavior() instanceof TechniqueBehavior.SwordForm)) {
            return;
        }
        // Нулевой слой — без эффектов: корявая техника не оставляет следа и не поднимает пыль.
        if (layer <= 0) {
            ACTIVE.remove(sourceId);
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

    private static void fire(Form f, Entity e) {
        float age = f.age(0);
        int strikes = SwordFormRules.strikes(f.layer);
        while (f.strikesFired < strikes && age >= f.strikeAge(f.strikesFired)) {
            int index = f.strikesFired++;
            boolean last = index == strikes - 1;
            Vec3 look = Vec3.directionFromRotation(0.0F, e.getYRot());
            if (f.layer >= 2) {
                puffs(f, e.position().add(look.scale(0.45D)), 3 + f.random.nextInt(2), 0.35D);
                puffs(f, e.position().add(look.scale(2.0D)), 2 + f.random.nextInt(2), 0.3D);
            }
            if (last && SwordFormRules.wideFinish(f.layer)) {
                f.wideAt = age;
                f.wideCentre = e.position().add(0.0D, 1.05D, 0.0D);
                f.wideYaw = e.getYRot();
                groundWave(f, e.position(), look);
            }
            if (last && SwordFormRules.unity(f.layer)) {
                f.grooveAt = age;
                f.grooveDir = look;
                // Борозда — от точки удара дальше по линии клинка, как на кадре 8.
                f.grooveFrom = e.position().add(look.scale(1.4D));
                grooveDebris(f);
            }
        }
    }

    /** Клубы пыли: спрайты строк 1–2 атласа, разлетаются от точки, тормозят и тают. */
    private static void puffs(Form f, Vec3 at, int count, double spread) {
        Random r = f.random;
        for (int i = 0; i < count; i++) {
            double a = r.nextDouble() * Math.PI * 2.0D;
            Vec3 dir = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
            f.puffs.add(new Puff(at.add(dir.scale(spread * r.nextDouble())).add(0.0D, 0.25D, 0.0D),
                    dir.scale(0.04D + 0.05D * r.nextDouble()).add(0.0D, 0.012D, 0.0D),
                    12 + r.nextInt(8), (float) (0.35D + 0.2D * r.nextDouble()), r.nextInt(8), false));
        }
    }

    /** Волна низких клубов (строка 3 атласа) по плитам вперёд и в стороны. */
    private static void groundWave(Form f, Vec3 feet, Vec3 look) {
        Random r = f.random;
        for (int i = 0; i < 14; i++) {
            double a = (r.nextDouble() - 0.5D) * Math.toRadians(240.0D);
            Vec3 dir = new Vec3(look.x * Math.cos(a) - look.z * Math.sin(a), 0.0D, look.x * Math.sin(a) + look.z * Math.cos(a));
            f.puffs.add(new Puff(feet.add(dir.scale(0.8D)).add(0.0D, 0.3D, 0.0D),
                    dir.scale(0.16D + 0.1D * r.nextDouble()), 16 + r.nextInt(8),
                    (float) (0.5D + 0.25D * r.nextDouble()), 8 + r.nextInt(4), false));
        }
    }

    /** Борозда: обломки плит вверх и стена клубов по обе стороны. */
    private static void grooveDebris(Form f) {
        Random r = f.random;
        Vec3 side = new Vec3(-f.grooveDir.z, 0.0D, f.grooveDir.x);
        for (int i = 0; i < 26; i++) {
            double along = r.nextDouble() * f.crackLength;
            Vec3 at = f.grooveFrom.add(f.grooveDir.scale(along)).add(side.scale((r.nextDouble() - 0.5D) * 0.6D)).add(0.0D, 0.05D, 0.0D);
            f.puffs.add(new Puff(at, new Vec3((r.nextDouble() - 0.5D) * 0.05D, 0.14D + 0.16D * r.nextDouble(),
                    (r.nextDouble() - 0.5D) * 0.05D), 22 + r.nextInt(12), (float) (0.05D + 0.1D * r.nextDouble()), 0, true));
        }
        for (int i = 0; i < 22; i++) {
            double along = r.nextDouble() * f.crackLength;
            double s = r.nextBoolean() ? 1.0D : -1.0D;
            Vec3 at = f.grooveFrom.add(f.grooveDir.scale(along)).add(side.scale(s * 0.4D)).add(0.0D, 0.35D, 0.0D);
            int cell = r.nextFloat() < 0.6F ? 8 + r.nextInt(4) : r.nextInt(8);
            f.puffs.add(new Puff(at, side.scale(s * (0.06D + 0.06D * r.nextDouble())).add(0.0D, 0.015D, 0.0D),
                    22 + r.nextInt(14), (float) (0.6D + 0.35D * r.nextDouble()), cell, false));
        }
    }

    private static void step(Form f) {
        Iterator<Puff> it = f.puffs.iterator();
        while (it.hasNext()) {
            Puff m = it.next();
            m.prev = m.pos;
            if (++m.age >= m.life) {
                it.remove();
                continue;
            }
            if (m.stone) {
                m.vel = new Vec3(m.vel.x * 0.98D, m.vel.y - 0.03D, m.vel.z * 0.98D);
            } else {
                m.vel = new Vec3(m.vel.x * 0.86D, m.vel.y * 0.9D, m.vel.z * 0.86D);
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
                if (f.grooveAt >= 0.0F) {
                    groove(f, pose, buffers, age - f.grooveAt);
                }
                streaks(f, pose, camera, buffers, age);
                if (f.wideAt >= 0.0F) {
                    wide(f, pose, camera, buffers, age - f.wideAt);
                }
                renderPuffs(f, pose, camera, buffers, partial);
            }
        } finally {
            poseStack.popPose();
        }
    }

    /** Снимает кисть и направление клинка в окнах ударов. */
    private static void sample(Form f, AbstractClientPlayer player, float age) {
        boolean window = false;
        for (int i = 0; i < SwordFormRules.strikes(f.layer); i++) {
            float s = f.strikeAge(i);
            if (age >= s - 3.0F && age <= s + 4.0F) {
                window = true;
                break;
            }
        }
        if (window) {
            Vec3 hand = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.RIGHT_HAND);
            Vec3 tip = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.BLADE_TIP);
            if (hand != null && tip != null) {
                Sample last = f.samples.isEmpty() ? null : f.samples.get(f.samples.size() - 1);
                if (last == null || age - last.age > 0.05F) {
                    if (last != null && age - last.age > 1.5F) {
                        f.samples.add(new Sample(-1.0F, hand, tip.subtract(hand)));
                    }
                    f.samples.add(new Sample(age, hand, tip.subtract(hand)));
                }
            }
        }
        f.samples.removeIf(s -> s.age >= 0.0F && age - s.age > TRAIL_LIFE);
    }

    /**
     * След клинка — ОДИН сплошной белый серп (автор 01.10: «нравилось, когда разрез был одним
     * шейпом»). Внешний край — вынесенный кончик клинка, внутренний — его середина; к хвосту
     * внутренний край подтягивается к внешнему, и серп сходится в острие. Края резкие,
     * сплошной цвет, без размытия; хвост гаснет быстрее, чем сужается.
     */
    private static void streaks(Form f, PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float age) {
        if (f.samples.size() < 2) {
            return;
        }
        List<Vec3> outer = new ArrayList<>();
        List<Vec3> inner = new ArrayList<>();
        List<Float> ages = new ArrayList<>();
        smoothPath(f.samples, 1.9D, outer, ages);
        smoothPath(f.samples, 0.6D, inner, new ArrayList<>());
        RenderType solid = MurimRenderTypes.solid();
        VertexConsumer c = buffers.getBuffer(solid);
        for (int i = 1; i < outer.size(); i++) {
            if (ages.get(i - 1) < 0.0F || ages.get(i) < 0.0F) {
                continue;
            }
            float u0 = Mth.clamp((age - ages.get(i - 1)) / TRAIL_LIFE, 0.0F, 1.0F);
            float u1 = Mth.clamp((age - ages.get(i)) / TRAIL_LIFE, 0.0F, 1.0F);
            if (u0 >= 1.0F && u1 >= 1.0F) {
                continue;
            }
            // Серп: внутренний край тянется к внешнему с возрастом — острый хвост.
            Vec3 o0 = outer.get(i - 1), o1 = outer.get(i);
            Vec3 i0 = inner.get(i - 1).lerp(o0, Math.pow(u0, 0.6D));
            Vec3 i1 = inner.get(i).lerp(o1, Math.pow(u1, 0.6D));
            float a0 = 1.0F - u0 * u0 * u0;
            float a1 = 1.0F - u1 * u1 * u1;
            Vec3 n = new Vec3(0.0D, 1.0D, 0.0D);
            VfxDraw.vertex(c, pose, i0, n, 0.0F, 0.0F, a0, WHITE.red(), WHITE.green(), WHITE.blue());
            VfxDraw.vertex(c, pose, i1, n, 1.0F, 0.0F, a1, WHITE.red(), WHITE.green(), WHITE.blue());
            VfxDraw.vertex(c, pose, o1, n, 1.0F, 1.0F, a1, WHITE.red(), WHITE.green(), WHITE.blue());
            VfxDraw.vertex(c, pose, o0, n, 0.0F, 1.0F, a0, WHITE.red(), WHITE.green(), WHITE.blue());
        }
        buffers.endBatch(solid);
    }

    /** Путь точки клинка на доле {@code k} от кисти, сглаженный сплайном (5 точек на отрезок). */
    private static void smoothPath(List<Sample> samples, double k, List<Vec3> out, List<Float> ages) {
        int n = samples.size();
        for (int i = 0; i < n - 1; i++) {
            Sample s1 = samples.get(i);
            Sample s2 = samples.get(i + 1);
            if (s1.age < 0.0F || s2.age < 0.0F) {
                out.add(Vec3.ZERO);
                ages.add(-1.0F);
                continue;
            }
            Sample s0 = i > 0 && samples.get(i - 1).age >= 0.0F ? samples.get(i - 1) : s1;
            Sample s3 = i + 2 < n && samples.get(i + 2).age >= 0.0F ? samples.get(i + 2) : s2;
            for (int j = 0; j < 5; j++) {
                double t = j / 5.0D;
                out.add(catmull(point(s0, k), point(s1, k), point(s2, k), point(s3, k), t));
                ages.add(Mth.lerp((float) t, s1.age, s2.age));
            }
        }
        Sample last = samples.get(n - 1);
        out.add(last.age < 0.0F ? Vec3.ZERO : point(last, k));
        ages.add(last.age);
    }

    private static Vec3 point(Sample s, double k) {
        return s.hand.add(s.dir.scale(k));
    }

    private static Vec3 catmull(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return p1.scale(2.0D).add(p2.subtract(p0).scale(t)).add(p0.scale(2.0D).subtract(p1.scale(5.0D)).add(p2.scale(4.0D)).subtract(p3).scale(t2))
                .add(p1.scale(3.0D).subtract(p0).subtract(p2.scale(3.0D)).add(p3).scale(t3)).scale(0.5D);
    }

    /** Отрезок полосы, развёрнутый к камере, с разной толщиной и непрозрачностью на концах. */
    private static void line(VertexConsumer c, PoseStack.Pose pose, Vec3 camera, Vec3 a, Vec3 b,
                             double wa, double wb, float aa, float ab) {
        Vec3 axis = b.subtract(a);
        if (axis.lengthSqr() < 1.0E-8D) {
            return;
        }
        Vec3 side = axis.cross(camera.subtract(a.add(b).scale(0.5D)));
        if (side.lengthSqr() < 1.0E-9D) {
            return;
        }
        side = side.normalize();
        Vec3 n = new Vec3(0.0D, 1.0D, 0.0D);
        VfxDraw.vertex(c, pose, a.subtract(side.scale(wa)), n, 0.0F, 0.0F, aa, WHITE.red(), WHITE.green(), WHITE.blue());
        VfxDraw.vertex(c, pose, b.subtract(side.scale(wb)), n, 1.0F, 0.0F, ab, WHITE.red(), WHITE.green(), WHITE.blue());
        VfxDraw.vertex(c, pose, b.add(side.scale(wb)), n, 1.0F, 1.0F, ab, WHITE.red(), WHITE.green(), WHITE.blue());
        VfxDraw.vertex(c, pose, a.add(side.scale(wa)), n, 0.0F, 1.0F, aa, WHITE.red(), WHITE.green(), WHITE.blue());
    }

    /**
     * Широкий разрез: три тонкие резкие дуги эллипсом на 320° вокруг бойца, наклон ~12°,
     * раскрываются за три тика и гаснут за полсекунды.
     */
    private static void wide(Form f, PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float t) {
        if (t > 12.0F) {
            return;
        }
        float sweep = Mth.clamp(t / 3.0F, 0.0F, 1.0F);
        float alpha = t < 3.0F ? 1.0F : (float) Math.pow(1.0F - (t - 3.0F) / 9.0F, 1.5D);
        double yaw = Math.toRadians(f.wideYaw);
        double span = Math.toRadians(320.0D) * sweep;
        double start = -span / 2.0D;
        int seg = 40;
        RenderType solid = MurimRenderTypes.solid();
        VertexConsumer c = buffers.getBuffer(solid);
        double[][] arcs = {{1.0D, 0.06D}, {0.86D, 0.03D}, {1.12D, 0.02D}};
        for (double[] arc : arcs) {
            double r = f.wideReach * arc[0];
            for (int i = 0; i < seg; i++) {
                double a0 = start + span * i / seg;
                double a1 = start + span * (i + 1) / seg;
                Vec3 p0 = f.wideCentre.add(dir(yaw, a0).scale(r)).add(0.0D, Math.sin(a0) * 0.22D * r, 0.0D);
                Vec3 p1 = f.wideCentre.add(dir(yaw, a1).scale(r)).add(0.0D, Math.sin(a1) * 0.22D * r, 0.0D);
                line(c, pose, camera, p0, p1, arc[1] * Math.sin(Math.PI * i / seg),
                        arc[1] * Math.sin(Math.PI * (i + 1) / seg), alpha, alpha);
            }
        }
        buffers.endBatch(solid);
    }

    private static Vec3 dir(double yaw, double a) {
        double ang = yaw + a;
        return new Vec3(-Math.sin(ang), 0.0D, Math.cos(ang));
    }

    /**
     * Борозда в плитах: тёмная полоса с рваными краями, прорезается вперёд за 4 тика и держится
     * несколько секунд; по краям — светло-серая крошка. Без свечения.
     */
    private static void groove(Form f, PoseStack.Pose pose, MultiBufferSource.BufferSource buffers, float t) {
        if (t > 100.0F) {
            return;
        }
        float open = Mth.clamp(t / 4.0F, 0.0F, 1.0F);
        float fade = Mth.clamp(1.0F - (t - 70.0F) / 30.0F, 0.0F, 1.0F);
        Random r = new Random(f.sourceId * 7L + f.startTick);
        Vec3 side = new Vec3(-f.grooveDir.z, 0.0D, f.grooveDir.x);
        RenderType solid = MurimRenderTypes.solid();
        VertexConsumer c = buffers.getBuffer(solid);
        int segs = 18;
        Vec3 at = f.grooveFrom.add(0.0D, 0.02D, 0.0D);
        for (int i = 0; i < segs && (float) i / segs < open; i++) {
            Vec3 next = f.grooveFrom.add(f.grooveDir.scale(f.crackLength * (i + 1) / segs))
                    .add(side.scale((r.nextDouble() - 0.5D) * 0.08D)).add(0.0D, 0.02D, 0.0D);
            double w = 0.28D * (1.0D - 0.4D * i / segs) * (0.85D + 0.3D * r.nextDouble());
            flat(c, pose, at, next, w + 0.07D, 0.8F * fade, GROOVE_EDGE);
            flat(c, pose, at.add(0.0D, 0.004D, 0.0D), next.add(0.0D, 0.004D, 0.0D), w, 0.95F * fade, GROOVE);
            at = next;
        }
        buffers.endBatch(solid);
    }

    private static void renderPuffs(Form f, PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float partial) {
        if (f.puffs.isEmpty()) {
            return;
        }
        RenderType dust = MurimRenderTypes.dustPuffs();
        VertexConsumer d = buffers.getBuffer(dust);
        for (Puff m : f.puffs) {
            if (m.stone) {
                continue;
            }
            float t = (m.age + partial) / m.life;
            // Клуб вырастает и тает только в последней трети: резкий спрайт, а не размытое пятно.
            float alpha = t < 0.65F ? 1.0F : Mth.clamp(1.0F - (t - 0.65F) / 0.35F, 0.0F, 1.0F);
            sprite(d, pose, camera, m.prev.lerp(m.pos, partial), m.size * (0.7F + 0.6F * t), m.cell, alpha);
        }
        buffers.endBatch(dust);
        RenderType solid = MurimRenderTypes.solid();
        VertexConsumer s = buffers.getBuffer(solid);
        for (Puff m : f.puffs) {
            if (!m.stone) {
                continue;
            }
            float t = (m.age + partial) / m.life;
            VfxDraw.billboard(s, pose, m.prev.lerp(m.pos, partial), camera, m.size,
                    Mth.clamp((1.0F - t) * 4.0F, 0.0F, 1.0F), STONE.red(), STONE.green(), STONE.blue());
        }
        buffers.endBatch(solid);
    }

    /** Квадрат к камере с ячейкой атласа 4×4. */
    private static void sprite(VertexConsumer c, PoseStack.Pose pose, Vec3 camera, Vec3 centre, double size, int cell, float alpha) {
        Vec3 forward = camera.subtract(centre);
        if (forward.lengthSqr() < 1.0E-6D) {
            return;
        }
        forward = forward.normalize();
        Vec3 reference = Math.abs(forward.y) > 0.95D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 right = forward.cross(reference).normalize().scale(size);
        Vec3 up = right.normalize().cross(forward).normalize().scale(size);
        float u0 = (cell % 4) / 4.0F, u1 = u0 + 0.25F;
        float v0 = (cell / 4) / 4.0F, v1 = v0 + 0.25F;
        Vec3 n = new Vec3(0.0D, 1.0D, 0.0D);
        VfxDraw.vertex(c, pose, centre.subtract(right).subtract(up), n, u0, v1, alpha, 1.0F, 1.0F, 1.0F);
        VfxDraw.vertex(c, pose, centre.add(right).subtract(up), n, u1, v1, alpha, 1.0F, 1.0F, 1.0F);
        VfxDraw.vertex(c, pose, centre.add(right).add(up), n, u1, v0, alpha, 1.0F, 1.0F, 1.0F);
        VfxDraw.vertex(c, pose, centre.subtract(right).add(up), n, u0, v0, alpha, 1.0F, 1.0F, 1.0F);
    }

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
