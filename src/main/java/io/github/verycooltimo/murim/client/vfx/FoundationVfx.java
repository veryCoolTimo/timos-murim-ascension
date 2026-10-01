package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.FoundationForms;
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
import java.util.Random;

/**
 * Эффекты форм основы меча (Меч Шести Равновесий на ЛКМ).
 *
 * <p>Базовая техника — аккуратно и методично (автор 01.10): никаких облаков и огромных
 * разрезов. Мастерство видно в точности, а не в количестве эффектов. Эффекты растут со слоем:
 * <ol start="0">
 *   <li>ничего — только движение;</li>
 *   <li>тонкий белый серп по настоящему пути клинка, живёт три тика;</li>
 *   <li>+ два-три коротких штриха воздуха у голени на шаге и крошечный завиток пыли у стопы
 *       (референсы: штрихи у голени, завиток у поставленной стопы);</li>
 *   <li>+ короткие линии в точке попадания — удар пришёлся ровно туда;</li>
 *   <li>единение с мечом: на ударе сверху — небольшая волна пыли и борозда в плитах
 *       (книга, гл. 298: простой удар мастера поднимает пыль; кадр 8 «единения»).</li>
 * </ol>
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class FoundationVfx {

    private static final VfxColour WHITE = hex(0xF4F5F2);
    private static final VfxColour GROOVE = hex(0x1E2026);
    private static final VfxColour GROOVE_EDGE = hex(0x6B6E76);

    /** Доля анимации, на которой приходится удар (0,12 с из 0,625). */
    private static final float HIT_FRACTION = 0.19F;
    /** Сколько тиков держится серп. */
    private static final float CRESCENT_LIFE = 3.0F;

    private static final List<Swing> ACTIVE = new ArrayList<>();
    private static int clientTicks;

    private record Sample(float age, Vec3 hand, Vec3 dir) {
    }

    private static final class Puff {
        Vec3 pos;
        Vec3 prev;
        final Vec3 vel;
        int age;
        final int life;
        final float size;
        final int cell;

        Puff(Vec3 pos, Vec3 vel, int life, float size, int cell) {
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.life = life;
            this.size = size;
            this.cell = cell;
        }
    }

    private static final class Swing {
        final int entityId;
        final FoundationForms.Form form;
        final int layer;
        final int startTick;
        final float duration;
        final Vec3 hit;
        final Random random;
        final List<Sample> samples = new ArrayList<>();
        final List<Puff> puffs = new ArrayList<>();
        boolean fired;
        Vec3 feet;
        Vec3 look;
        Vec3 grooveFrom;

        Swing(int entityId, FoundationForms.Form form, int layer, float duration, Vec3 hit) {
            this.entityId = entityId;
            this.form = form;
            this.layer = layer;
            this.duration = Math.max(4.0F, duration);
            this.hit = hit;
            this.startTick = clientTicks;
            this.random = new Random(entityId * 31L + startTick);
        }

        float age(float partial) {
            return clientTicks - startTick + partial;
        }

        float hitAge() {
            return duration * HIT_FRACTION;
        }

        boolean unity() {
            return layer >= 4 && form == FoundationForms.Form.OVERHEAD;
        }

        boolean done() {
            return age(0) > (unity() ? 80.0F : duration + 8.0F) && puffs.isEmpty();
        }
    }

    /** Начать эффект формы. Нулевой слой — ничего. */
    public static void start(int entityId, FoundationForms.Form form, int layer, float duration, Vec3 hit) {
        if (layer <= 0) {
            return;
        }
        ACTIVE.add(new Swing(entityId, form, layer, duration, hit));
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
        Iterator<Swing> it = ACTIVE.iterator();
        while (it.hasNext()) {
            Swing s = it.next();
            Entity e = minecraft.level.getEntity(s.entityId);
            if (!s.fired && s.age(0) >= s.hitAge() && e != null) {
                s.fired = true;
                fire(s, e);
            }
            Iterator<Puff> pi = s.puffs.iterator();
            while (pi.hasNext()) {
                Puff p = pi.next();
                p.prev = p.pos;
                if (++p.age >= p.life) {
                    pi.remove();
                    continue;
                }
                double drag = Math.pow(0.86D, p.age);
                p.pos = p.pos.add(p.vel.scale(drag));
            }
            if (s.done()) {
                it.remove();
            }
        }
    }

    /** Момент удара: шаг, пыль у стопы, единение. */
    private static void fire(Swing s, Entity e) {
        s.look = Vec3.directionFromRotation(0.0F, e.getYRot());
        s.feet = e.position();
        Random r = s.random;
        if (s.layer >= 2) {
            Vec3 foot = s.feet.add(s.look.scale(0.4D));
            int n = 1 + r.nextInt(2);
            for (int i = 0; i < n; i++) {
                double a = r.nextDouble() * Math.PI * 2.0D;
                Vec3 d = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
                s.puffs.add(new Puff(foot.add(d.scale(0.15D)).add(0.0D, 0.12D, 0.0D), d.scale(0.025D).add(0.0D, 0.006D, 0.0D),
                        10 + r.nextInt(4), (float) (0.13D + 0.06D * r.nextDouble()), r.nextInt(4)));
            }
        }
        if (s.unity()) {
            Vec3 at = s.hit != null ? new Vec3(s.hit.x, s.feet.y, s.hit.z) : s.feet.add(s.look.scale(1.6D));
            s.grooveFrom = at;
            for (int i = 0; i < 9; i++) {
                double a = (r.nextDouble() - 0.5D) * Math.toRadians(200.0D);
                Vec3 d = new Vec3(s.look.x * Math.cos(a) - s.look.z * Math.sin(a), 0.0D, s.look.x * Math.sin(a) + s.look.z * Math.cos(a));
                s.puffs.add(new Puff(at.add(d.scale(0.4D)).add(0.0D, 0.25D, 0.0D), d.scale(0.1D + 0.06D * r.nextDouble()),
                        16 + r.nextInt(8), (float) (0.32D + 0.14D * r.nextDouble()), 8 + r.nextInt(4)));
            }
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
            RenderType solid = MurimRenderTypes.solid();
            for (Swing s : ACTIVE) {
                float age = s.age(partial);
                if (minecraft.level.getEntity(s.entityId) instanceof AbstractClientPlayer player) {
                    sample(s, player, age);
                }
                if (s.grooveFrom != null) {
                    VertexConsumer c = buffers.getBuffer(solid);
                    groove(s, pose, c, age - s.hitAge());
                    buffers.endBatch(solid);
                }
                VertexConsumer c = buffers.getBuffer(solid);
                crescent(s, pose, c, age);
                if (s.fired && s.layer >= 2) {
                    shinAir(s, pose, camera, c, age - s.hitAge());
                }
                if (s.fired && s.layer >= 3 && s.hit != null) {
                    impactLines(s, pose, camera, c, age - s.hitAge());
                }
                buffers.endBatch(solid);
                puffs(s, pose, camera, buffers, partial);
            }
        } finally {
            poseStack.popPose();
        }
    }

    /** Снимает кисть и клинок от начала взмаха до ранней фиксации. */
    private static void sample(Swing s, AbstractClientPlayer player, float age) {
        if (age > s.duration * 0.42F) {
            return;
        }
        Vec3 hand = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.RIGHT_HAND);
        Vec3 tip = BoneAnchorLayer.position(player, BoneAnchorLayer.Bone.BLADE_TIP);
        if (hand == null || tip == null) {
            return;
        }
        Sample last = s.samples.isEmpty() ? null : s.samples.get(s.samples.size() - 1);
        if (last == null || age - last.age > 0.05F) {
            s.samples.add(new Sample(age, hand, tip.subtract(hand)));
        }
    }

    /** Тонкий сплошной серп: от середины клинка до чуть вынесенного кончика, хвост острый. */
    private static void crescent(Swing s, PoseStack.Pose pose, VertexConsumer c, float age) {
        if (s.samples.size() < 2) {
            return;
        }
        List<Vec3> outer = new ArrayList<>();
        List<Vec3> inner = new ArrayList<>();
        List<Float> ages = new ArrayList<>();
        smooth(s.samples, 1.8D, outer, ages);
        smooth(s.samples, 1.1D, inner, new ArrayList<>());
        Vec3 n = new Vec3(0.0D, 1.0D, 0.0D);
        for (int i = 1; i < outer.size(); i++) {
            float u0 = Mth.clamp((age - ages.get(i - 1)) / CRESCENT_LIFE, 0.0F, 1.0F);
            float u1 = Mth.clamp((age - ages.get(i)) / CRESCENT_LIFE, 0.0F, 1.0F);
            if (u0 >= 1.0F && u1 >= 1.0F) {
                continue;
            }
            Vec3 o0 = outer.get(i - 1), o1 = outer.get(i);
            Vec3 i0 = inner.get(i - 1).lerp(o0, Math.pow(u0, 0.6D));
            Vec3 i1 = inner.get(i).lerp(o1, Math.pow(u1, 0.6D));
            float a0 = 1.0F - u0 * u0 * u0;
            float a1 = 1.0F - u1 * u1 * u1;
            VfxDraw.vertex(c, pose, i0, n, 0.0F, 0.0F, a0, WHITE.red(), WHITE.green(), WHITE.blue());
            VfxDraw.vertex(c, pose, i1, n, 1.0F, 0.0F, a1, WHITE.red(), WHITE.green(), WHITE.blue());
            VfxDraw.vertex(c, pose, o1, n, 1.0F, 1.0F, a1, WHITE.red(), WHITE.green(), WHITE.blue());
            VfxDraw.vertex(c, pose, o0, n, 0.0F, 1.0F, a0, WHITE.red(), WHITE.green(), WHITE.blue());
        }
    }

    /** Два-три коротких штриха воздуха у голени — вслед за шагом, тянутся назад и гаснут. */
    private static void shinAir(Swing s, PoseStack.Pose pose, Vec3 camera, VertexConsumer c, float t) {
        if (t < 0.0F || t > 4.0F || s.feet == null) {
            return;
        }
        float k = t / 4.0F;
        Vec3 side = new Vec3(-s.look.z, 0.0D, s.look.x);
        Random r = new Random(s.entityId * 13L + s.startTick);
        int n = 2 + r.nextInt(2);
        for (int i = 0; i < n; i++) {
            double h = 0.18D + 0.14D * i + 0.05D * r.nextDouble();
            Vec3 a = s.feet.add(s.look.scale(0.25D - 0.25D * k)).add(side.scale(0.12D + 0.06D * r.nextDouble())).add(0.0D, h, 0.0D);
            Vec3 b = a.subtract(s.look.scale(0.28D + 0.12D * r.nextDouble()));
            line(c, pose, camera, a, b, 0.012D, 0.002D, 0.9F * (1.0F - k), 0.0F);
        }
    }

    /** Короткие линии в точке попадания: 4 штуки, 3 тика. */
    private static void impactLines(Swing s, PoseStack.Pose pose, Vec3 camera, VertexConsumer c, float t) {
        if (t < 0.0F || t > 3.0F) {
            return;
        }
        float k = t / 3.0F;
        Random r = new Random(s.entityId * 17L + s.startTick);
        Vec3 toCam = camera.subtract(s.hit).normalize();
        Vec3 u = toCam.cross(new Vec3(0.0D, 1.0D, 0.0D));
        u = u.lengthSqr() < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D) : u.normalize();
        Vec3 v = u.cross(toCam).normalize();
        for (int i = 0; i < 4; i++) {
            double a = Math.PI * 2.0D * i / 4.0D + r.nextDouble() * 0.8D;
            Vec3 d = u.scale(Math.cos(a)).add(v.scale(Math.sin(a)));
            double len = 0.3D + 0.15D * r.nextDouble();
            Vec3 from = s.hit.add(d.scale(0.12D + 0.15D * k));
            line(c, pose, camera, from, from.add(d.scale(len * (1.0D - 0.4D * k))), 0.018D, 0.002D, 1.0F - k, 0.0F);
        }
    }

    /** Борозда единения: тёмная полоса 5 блоков вперёд, прорезается за 3 тика, держится 3 с. */
    private static void groove(Swing s, PoseStack.Pose pose, VertexConsumer c, float t) {
        if (t < 0.0F || t > 70.0F) {
            return;
        }
        float open = Mth.clamp(t / 3.0F, 0.0F, 1.0F);
        float fade = Mth.clamp(1.0F - (t - 50.0F) / 20.0F, 0.0F, 1.0F);
        Random r = new Random(s.entityId * 7L + s.startTick);
        Vec3 side = new Vec3(-s.look.z, 0.0D, s.look.x);
        int segs = 12;
        double length = 5.0D;
        Vec3 at = s.grooveFrom.add(0.0D, 0.02D, 0.0D);
        for (int i = 0; i < segs && (float) i / segs < open; i++) {
            Vec3 next = s.grooveFrom.add(s.look.scale(length * (i + 1) / segs))
                    .add(side.scale((r.nextDouble() - 0.5D) * 0.06D)).add(0.0D, 0.02D, 0.0D);
            double w = 0.18D * (1.0D - 0.5D * i / segs) * (0.85D + 0.3D * r.nextDouble());
            flat(c, pose, at, next, w + 0.05D, 0.75F * fade, GROOVE_EDGE);
            flat(c, pose, at.add(0.0D, 0.004D, 0.0D), next.add(0.0D, 0.004D, 0.0D), w, 0.92F * fade, GROOVE);
            at = next;
        }
    }

    private static void puffs(Swing s, PoseStack.Pose pose, Vec3 camera, MultiBufferSource.BufferSource buffers, float partial) {
        if (s.puffs.isEmpty()) {
            return;
        }
        RenderType dust = MurimRenderTypes.dustPuffs();
        VertexConsumer d = buffers.getBuffer(dust);
        for (Puff p : s.puffs) {
            float t = (p.age + partial) / p.life;
            float alpha = t < 0.6F ? 1.0F : Mth.clamp(1.0F - (t - 0.6F) / 0.4F, 0.0F, 1.0F);
            sprite(d, pose, camera, p.prev.lerp(p.pos, partial), p.size * (0.8F + 0.5F * t), p.cell, alpha);
        }
        buffers.endBatch(dust);
    }

    private static void smooth(List<Sample> samples, double k, List<Vec3> out, List<Float> ages) {
        int n = samples.size();
        for (int i = 0; i < n - 1; i++) {
            Sample s1 = samples.get(i);
            Sample s2 = samples.get(i + 1);
            Sample s0 = i > 0 ? samples.get(i - 1) : s1;
            Sample s3 = i + 2 < n ? samples.get(i + 2) : s2;
            for (int j = 0; j < 5; j++) {
                double t = j / 5.0D;
                out.add(catmull(point(s0, k), point(s1, k), point(s2, k), point(s3, k), t));
                ages.add(Mth.lerp((float) t, s1.age, s2.age));
            }
        }
        out.add(point(samples.get(n - 1), k));
        ages.add(samples.get(n - 1).age);
    }

    private static Vec3 point(Sample s, double k) {
        return s.hand.add(s.dir.scale(k));
    }

    private static Vec3 catmull(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return p1.scale(2.0D).add(p2.subtract(p0).scale(t)).add(p0.scale(2.0D).subtract(p1.scale(5.0D)).add(p2.scale(4.0D)).subtract(p3).scale(t2))
                .add(p1.scale(3.0D).subtract(p0).subtract(p2.scale(3.0D)).add(p3).scale(t3)).scale(0.5D);
    }

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

    private FoundationVfx() {
    }
}
