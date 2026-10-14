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

    private static final VfxColour WHITE_SIX = hex(0xF4F5F2);
    /** Серп основы Семи Цветков: бело-розовый. */
    private static final VfxColour PLUM = hex(0xFFC9DE);
    private static VfxColour WHITE = WHITE_SIX;
    private static final VfxColour GROOVE = hex(0x1E2026);
    private static final VfxColour GROOVE_EDGE = hex(0x6B6E76);

    /** Доля анимации, на которой приходится удар (0,12 с из 0,625). */
    private static final float HIT_FRACTION = 0.19F;
    /** Сколько тиков держится серп. */
    private static final float CRESCENT_LIFE = 3.0F;

    private static final List<Swing> ACTIVE = new ArrayList<>();
    private static final List<Contact> CONTACTS = new ArrayList<>();
    private static final VfxColour FINISHER_BODY = hex(0xCDEFE1);
    private static final VfxColour FINISHER_INNER = hex(0x7FC4A6);
    private static final float CONTACT_LIFE = 5.0F;
    private static AbstractClientPlayer renderedPlayer;
    private static float renderedPartial;
    private static float handPartial;
    private static final VfxColour FINISHER_EDGE = hex(0xFFFFFF);
    private static int clientTicks;
    private static net.minecraft.client.multiplayer.ClientLevel effectLevel;

    private record Contact(Vec3 point, int born) {}

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
        final int executionTier;
        final int startTick;
        final float duration;
        Vec3 hit;
        float hitEffectAge;
        final Random random;
        final List<Sample> samples = new ArrayList<>();
        final List<Sample> handSamples = new ArrayList<>();
        final List<Puff> puffs = new ArrayList<>();
        boolean fired;
        Vec3 feet;
        Vec3 look;
        Vec3 grooveFrom;

        Swing(int entityId, FoundationForms.Form form, int executionTier, float duration, Vec3 hit) {
            this.entityId = entityId;
            this.form = form;
            this.executionTier = executionTier;
            this.duration = Math.max(4.0F, duration);
            this.hit = hit;
            this.startTick = clientTicks;
            this.random = new Random(entityId * 31L + startTick);
        }

        float age(float partial) {
            return clientTicks - startTick + partial;
        }

        float hitAge() {
            return plum ? io.github.verycooltimo.murim.combat.SwordBasicCombo.CONTACT_TICK : duration * HIT_FRACTION;
        }

        boolean plum;
        boolean finisher() {
            return plum && io.github.verycooltimo.murim.combat.SwordBasicCombo.isFinisher(form);
        }
        final List<Vec3[]> petals = new ArrayList<>();

        boolean unity() {
            return !plum && executionTier >= 4 && form == FoundationForms.Form.OVERHEAD;
        }

        boolean done() {
            return age(0) > (unity() ? 80.0F : duration + 8.0F) && puffs.isEmpty();
        }
    }

    /** Начать эффект формы. Нулевой слой — ничего. */
    public static void start(int entityId, FoundationForms.Form form, int executionTier, float duration, Vec3 hit) {
        start(entityId, form, executionTier, duration, hit, false);
    }

    /** {@code plum} — основа Семи Цветков: розовый серп, с 3-го слоя лепестки на ударе. */
    public static void start(int entityId, FoundationForms.Form form, int executionTier, float duration, Vec3 hit, boolean plum) {
        checkLevel(Minecraft.getInstance());
        if (executionTier <= 0) {
            return;
        }
        Swing s = new Swing(entityId, form, executionTier, duration, hit);
        s.plum = plum;
        s.hitEffectAge = s.hitAge();
        ACTIVE.add(s);
    }

    public static void confirmHit(io.github.verycooltimo.murim.network.FoundationPayloads.Hit payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        checkLevel(minecraft);
        int sourceId = payload.entityId();
        // Independent of the swing history/target tracking: late packets still show a contact.
        if (payload.finisher()) {
            CONTACTS.add(new Contact(payload.contact(), clientTicks));
            // FOUNDATION includes the third LMB cut: only a local contact accent.
            // The strike kind is independent of the combo's finisher flag and client config.
            if (minecraft.player != null && minecraft.player.getId() == sourceId) {
                if (payload.kind().allowsImpactFrames()) ImpactFrames.trigger(payload.contact());
                if (payload.kind().allowsHitStop()) io.github.verycooltimo.murim.client.HitStopHandler.request(1);
            }
        }
        for (int i = ACTIVE.size() - 1; i >= 0; i--) {
            Swing swing = ACTIVE.get(i);
            if (swing.entityId == sourceId && swing.plum && swing.age(0) <= swing.duration) {
                swing.hitEffectAge = Math.max(swing.hitAge(), swing.age(0));
                swing.hit = payload.contact();
                // A late confirmation still gets a brief contact effect.
                if (swing.fired) fire(swing, minecraft.level.getEntity(sourceId));
                break;
            }
        }
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        checkLevel(minecraft);
        if (minecraft.level == null) {
            ACTIVE.clear();
            return;
        }
        if (minecraft.isPaused()) {
            return;
        }
        clientTicks++;
        CONTACTS.removeIf(contact -> clientTicks - contact.born() >= CONTACT_LIFE);
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
            for (Vec3[] pt : s.petals) {
                pt[1] = new Vec3(pt[1].x * 0.92D, pt[1].y * 0.92D - 0.003D, pt[1].z * 0.92D);
                pt[0] = pt[0].add(pt[1]);
                pt[2] = pt[2].add(0.0D, 0.0D, 1.0D);
            }
            s.petals.removeIf(pt -> pt[2].z > 30.0D);
            if (s.done() && s.petals.isEmpty()) {
                it.remove();
            }
        }
    }

    private static void checkLevel(Minecraft minecraft) {
        if (effectLevel != minecraft.level) {
            ACTIVE.clear();
            CONTACTS.clear();
            renderedPlayer = null;
            effectLevel = minecraft.level;
        }
    }

    /** Момент удара: шаг, пыль у стопы, единение. */
    private static void fire(Swing s, Entity e) {
        if (e == null) return;
        s.look = Vec3.directionFromRotation(0.0F, e.getYRot());
        s.feet = e.position();
        Random r = s.random;
        if (s.executionTier >= 2 && (!s.plum || s.puffs.isEmpty())) {
            Vec3 foot = s.feet.add(s.look.scale(0.4D));
            int n = 1 + r.nextInt(2);
            for (int i = 0; i < n; i++) {
                double a = r.nextDouble() * Math.PI * 2.0D;
                Vec3 d = new Vec3(Math.cos(a), 0.0D, Math.sin(a));
                s.puffs.add(new Puff(foot.add(d.scale(0.15D)).add(0.0D, 0.12D, 0.0D), d.scale(0.025D).add(0.0D, 0.006D, 0.0D),
                        10 + r.nextInt(4), (float) (0.13D + 0.06D * r.nextDouble()), r.nextInt(4)));
            }
        }
        if (s.plum && s.hit != null && s.executionTier >= 3 && s.petals.isEmpty()) {
            // Лепестки срываются с удара во все стороны: {позиция, скорость}.
            Vec3 at = s.hit != null ? s.hit : s.feet.add(s.look.scale(1.4D)).add(0.0D, 1.2D, 0.0D);
            for (int i = 0; i < Math.min(6, 2 + s.executionTier); i++) {
                Vec3 v = new Vec3(r.nextGaussian(), 0.4D + r.nextDouble(), r.nextGaussian()).normalize().scale(0.08D + 0.08D * r.nextDouble());
                s.petals.add(new Vec3[] {at, v, new Vec3(0, r.nextInt(4), 0)});
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
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || ACTIVE.isEmpty() && CONTACTS.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        checkLevel(minecraft);
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
                if (minecraft.level.getEntity(s.entityId) instanceof net.minecraft.world.entity.LivingEntity caster) {
                    sample(s, caster, age);
                }
                if (s.grooveFrom != null) {
                    VertexConsumer c = buffers.getBuffer(solid);
                    groove(s, pose, c, age - s.hitAge());
                    buffers.endBatch(solid);
                }
                VertexConsumer c = buffers.getBuffer(solid);
                WHITE = s.plum ? PLUM : WHITE_SIX;
                if (!s.finisher()) crescent(s, pose, c, age);
                if (s.fired && s.executionTier >= 2) {
                    shinAir(s, pose, camera, c, age - s.hitAge());
                }
                if (!s.finisher() && s.fired && (s.plum || s.executionTier >= 3) && s.hit != null) {
                    impactLines(s, pose, camera, c, age - s.hitEffectAge);
                }
                buffers.endBatch(solid);
                if (s.finisher() && (minecraft.player == null || s.entityId != minecraft.player.getId()
                        || !minecraft.options.getCameraType().isFirstPerson())) {
                    RenderType ink = MurimRenderTypes.swordFinisher();
                    finisherCrescent(s.samples, pose, buffers.getBuffer(ink), age, false);
                    buffers.endBatch(ink);
                }
                puffs(s, pose, camera, buffers, partial);
                if (!s.petals.isEmpty()) {
                    RenderType pt = MurimRenderTypes.plumPetals();
                    VertexConsumer pc = buffers.getBuffer(pt);
                    for (Vec3[] p : s.petals) {
                        float a = Mth.clamp((30.0F - (float) p[2].z) / 10.0F, 0.0F, 1.0F);
                        PlumVfx.petal(pc, pose, camera, p[0], 0.13D, (int) p[2].y, (float) p[2].z * 0.3F, a, 1.0F, 0.75F, 0.8F);
                    }
                    buffers.endBatch(pt);
                }
            }
            if (!CONTACTS.isEmpty()) {
                RenderType ink = MurimRenderTypes.swordFinisher();
                VertexConsumer c = buffers.getBuffer(ink);
                for (Contact contact : CONTACTS) {
                    sharpContact(contact, pose, camera, c, clientTicks - contact.born() + partial);
                }
                buffers.endBatch(ink);
            }
        } finally {
            poseStack.popPose();
        }
    }

    /** Снимает кисть и клинок от начала взмаха до ранней фиксации. */
    private static void sample(Swing s, net.minecraft.world.entity.LivingEntity player, float age) {
        // Finishers are recorded during the current rendered item/layer, not from a stale cache.
        if (s.finisher()) return;
        // First person does not render this model; cached third-person anchors are stale.
        Minecraft minecraft = Minecraft.getInstance();
        if (player == minecraft.player && minecraft.options.getCameraType().isFirstPerson()) return;
        if (age > (s.plum ? 5.0F : s.duration * 0.42F)) {
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

    /** Tip-only crescent: outer 45% at mid-sweep, pinched to the tip at both authored endpoints. */
    private static double finisherWidth(Sample sample, float start, float end) {
        float progress = Mth.clamp((sample.age - start) / (end - start), 0, 1);
        return 0.45D * Math.sin(Math.PI * progress);
    }

    private static void finisherCrescent(List<Sample> samples, PoseStack.Pose pose, VertexConsumer c,
                                         float age, boolean firstPerson) {
        int count = samples.size();
        if (count < 2) return;
        float start = firstPerson ? 0 : io.github.verycooltimo.murim.combat.SwordBasicCombo.FINISHER_SWEEP_START;
        float end = firstPerson ? 6 : io.github.verycooltimo.murim.combat.SwordBasicCombo.FINISHER_SWEEP_END;
        // Keep the body readable throughout the cut; fade the completed crescent as a whole.
        float sinceBlade = Math.max(0, age - samples.get(count - 1).age);
        float fade = Mth.clamp(1.0F - sinceBlade / 4.0F, 0, 1);
        float edge = Mth.clamp(1.0F - sinceBlade / 2.0F, 0, 1);
        if (fade <= 0) return;
        for (int i = 1; i < count; i++) {
            Sample a = samples.get(i - 1), b = samples.get(i);
            Vec3 oa = point(a, 1), ob = point(b, 1);
            Vec3 normal = ob.subtract(oa).cross(a.hand.subtract(oa)).normalize();
            // Frames need not land exactly on ticks 1/3: close the captured tail,
            // and the completed front, at their tips as well.
            double wa = i == 1 ? 0 : finisherWidth(a, start, end);
            double wb = i == count - 1 && age >= end ? 0 : finisherWidth(b, start, end);
            Vec3 ia = point(a, 1 - wa), ib = point(b, 1 - wb);
            Vec3 innerA = point(a, 1 - wa * 0.88D), innerB = point(b, 1 - wb * 0.88D);
            // About 1–2 screen pixels at normal combat distance; never extends beyond the tip.
            Vec3 edgeA = point(a, 1 - Math.min(wa * 0.18D, 0.025D / Math.max(1.0E-6D, a.dir.length())));
            Vec3 edgeB = point(b, 1 - Math.min(wb * 0.18D, 0.025D / Math.max(1.0E-6D, b.dir.length())));
            colouredQuad(c, pose, ia, ib, innerB, innerA, normal, fade * 0.65F, fade * 0.65F, FINISHER_INNER);
            colouredQuad(c, pose, innerA, innerB, edgeB, edgeA, normal, fade * 0.60F, fade * 0.60F, FINISHER_BODY);
            colouredQuad(c, pose, edgeA, edgeB, ob, oa, normal, edge, edge, FINISHER_EDGE);
            if (i == count - 1 && wb > 1.0E-6D) {
                // Close the growing leading edge, restricted to the same tip band.
                int previous = i - 1;
                while (previous > 0 && ob.subtract(point(samples.get(previous), 1)).lengthSqr() < 0.025D * 0.025D) previous--;
                Sample previousBlade = samples.get(previous);
                Vec3 startTip = point(previousBlade, 1);
                double width = Math.min(1.0D, 0.025D / Math.max(1.0E-6D, ob.subtract(startTip).length()));
                colouredQuad(c, pose, ib.lerp(point(previousBlade, 1 - wb), width), ib, ob, ob.lerp(startTip, width),
                        normal, edge, edge, FINISHER_EDGE);
            }
        }
    }

    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    static void onRenderPlayer(net.neoforged.neoforge.client.event.RenderPlayerEvent.Pre event) {
        renderedPlayer = !event.isCanceled() && event.getEntity() instanceof AbstractClientPlayer player ? player : null;
        renderedPartial = event.getPartialTick();
    }

    @SubscribeEvent
    static void onRenderPlayerPost(net.neoforged.neoforge.client.event.RenderPlayerEvent.Post event) {
        renderedPlayer = null;
    }

    /** Called inside the actual held-item renderer, after ALL hand/display/centering transforms.
     * Raw mesh endpoints are transformed once: no second display transform or guessed arm offset. */
    public static void recordRenderedBlade(net.minecraft.world.item.ItemStack stack,
                                           net.minecraft.world.item.ItemDisplayContext context,
                                           PoseStack itemPose, MultiBufferSource buffers, Vec3[] endpoints) {
        Minecraft mc = Minecraft.getInstance();
        boolean first = context == net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                || context == net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
        boolean third = context == net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                || context == net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
        AbstractClientPlayer player = first ? mc.player : third ? renderedPlayer : null;
        if (player == null || stack != player.getMainHandItem() || mc.isPaused()) return;
        boolean right = player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT;
        if (context != (first
                ? (right ? net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_RIGHT_HAND : net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_LEFT_HAND)
                : (right ? net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_RIGHT_HAND : net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_LEFT_HAND))) return;
        for (int i = ACTIVE.size() - 1; i >= 0; i--) {
            Swing s = ACTIVE.get(i);
            if (s.entityId != player.getId() || !s.finisher()) continue;
            float age = s.age(first ? handPartial : renderedPartial);
            // First person uses vanilla's six-tick hand swing, third person the authored 1..3 sweep.
            float end = first ? 6.0F : io.github.verycooltimo.murim.combat.SwordBasicCombo.FINISHER_SWEEP_END;
            List<Sample> samples = first ? s.handSamples : s.samples;
            if (age >= (first ? 0 : io.github.verycooltimo.murim.combat.SwordBasicCombo.FINISHER_SWEEP_START) && age <= end) {
                Vec3 base = handPoint(itemPose, endpoints[0]), tip = handPoint(itemPose, endpoints[1]);
                if (!first) {
                    Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
                    base = base.add(camera); tip = tip.add(camera);
                }
                addSample(samples, age, base, tip);
            }
            if (first) {
                // Captured points already include the hand view/bob pose. Draw in that same view space.
                finisherCrescent(samples, new PoseStack().last(), buffers.getBuffer(MurimRenderTypes.swordFinisher()), age, true);
            }
            break;
        }
    }

    /** Vanilla sprite/Qi blade: called by the current posed player layer. */
    public static void recordThirdPersonBlade(AbstractClientPlayer player, float partial, PoseStack pose, Vec3 base, Vec3 tip) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isPaused() || player == mc.player && mc.options.getCameraType().isFirstPerson()) return;
        for (int i = ACTIVE.size() - 1; i >= 0; i--) {
            Swing s = ACTIVE.get(i);
            if (s.entityId != player.getId() || !s.finisher()) continue;
            float age = s.age(partial);
            if (age >= io.github.verycooltimo.murim.combat.SwordBasicCombo.FINISHER_SWEEP_START
                    && age <= io.github.verycooltimo.murim.combat.SwordBasicCombo.FINISHER_SWEEP_END) {
                Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
                addSample(s.samples, age, handPoint(pose, base).add(camera), handPoint(pose, tip).add(camera));
            }
            break;
        }
    }

    private static void addSample(List<Sample> samples, float age, Vec3 base, Vec3 tip) {
        samples.removeIf(sample -> age - sample.age > 4.0F);
        Sample last = samples.isEmpty() ? null : samples.get(samples.size() - 1);
        if (last == null || age > last.age) samples.add(new Sample(age, base, tip.subtract(base)));
        if (samples.size() > 256) samples.remove(0);
    }

    /** The local hand isn't a rendered player model. Use the same vanilla item transforms
     * as QiSwordClient, plus the held item's own first-person display transform. */
    @SubscribeEvent
    static void onRenderHand(net.neoforged.neoforge.client.event.RenderHandEvent event) {
        Minecraft mc = Minecraft.getInstance();
        handPartial = event.getPartialTick();
        if (mc.player == null || mc.isPaused() || event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND
                || mc.player.isUsingItem()) return;
        Swing s = null;
        for (int i = ACTIVE.size() - 1; i >= 0; i--) {
            Swing candidate = ACTIVE.get(i);
            if (candidate.entityId == mc.player.getId()) { s = candidate; break; }
        }
        if (s == null || !s.finisher()) return;
        // Bedrock swords supply exact endpoints later, inside renderByItem.
        if (io.github.verycooltimo.murim.client.bedrock.BedrockItemRenderer.swordEndpoints(event.getItemStack()) != null) return;
        float age = s.age(event.getPartialTick());
        if (age >= 0 && age <= 6.0F) {
            boolean right = mc.player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT;
            int side = right ? 1 : -1;
            float swing = event.getSwingProgress(), root = Mth.sqrt(swing);
            PoseStack item = new PoseStack();
            item.translate(side * -0.4F * Mth.sin(root * Mth.PI), 0.2F * Mth.sin(root * Mth.TWO_PI), -0.2F * Mth.sin(swing * Mth.PI));
            item.translate(side * 0.56F, -0.52F - event.getEquipProgress() * 0.6F, -0.72F);
            item.mulPose(com.mojang.math.Axis.YP.rotationDegrees(side * (45 - Mth.sin(swing * swing * Mth.PI) * 20)));
            item.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(side * Mth.sin(root * Mth.PI) * -20));
            item.mulPose(com.mojang.math.Axis.XP.rotationDegrees(Mth.sin(root * Mth.PI) * -80));
            item.mulPose(com.mojang.math.Axis.YP.rotationDegrees(side * -45));
            Vec3 hand, tip;
            if (event.getItemStack().isEmpty()) {
                if (!io.github.verycooltimo.murim.client.QiSwordClient.visible(mc.player)) return;
                hand = handPoint(item, new Vec3(side * 0.02D, 0.06D, -0.02D));
                tip = handPoint(item, new Vec3(side * 0.02D, 0.06D, -0.02D).add(
                        new Vec3(side * -0.18D, 0.86D, -0.48D).normalize().scale(1.5D)));
            } else {
                if (!io.github.verycooltimo.murim.combat.QiSword.holdsSword(event.getItemStack())) return;
                var model = mc.getItemRenderer().getModel(event.getItemStack(), mc.level, mc.player, 0);
                if (model.isCustomRenderer()) return;
                net.neoforged.neoforge.client.ClientHooks.handleCameraTransforms(item, model,
                        right ? net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                                : net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_LEFT_HAND, !right);
                // Centred sprite coords: blade starts above the guard, not at the pommel.
                hand = handPoint(item, new Vec3(-0.125D, -0.125D, 0));
                tip = handPoint(item, new Vec3(0.4375D, 0.4375D, 0));
            }
            addSample(s.handSamples, age, hand, tip);
        }
        finisherCrescent(s.handSamples, event.getPoseStack().last(),
                event.getMultiBufferSource().getBuffer(MurimRenderTypes.swordFinisher()), age, true);
    }

    private static Vec3 handPoint(PoseStack pose, Vec3 point) {
        org.joml.Vector3f result = pose.last().pose().transformPosition((float) point.x, (float) point.y, (float) point.z, new org.joml.Vector3f());
        return new Vec3(result.x, result.y, result.z);
    }

    private static void colouredQuad(VertexConsumer c, PoseStack.Pose pose, Vec3 a, Vec3 b, Vec3 d, Vec3 e,
                                      Vec3 n, float aa, float ab, VfxColour colour) {
        VfxDraw.vertex(c, pose, a, n, 0, 0, aa, colour.red(), colour.green(), colour.blue());
        VfxDraw.vertex(c, pose, b, n, 1, 0, ab, colour.red(), colour.green(), colour.blue());
        VfxDraw.vertex(c, pose, d, n, 1, 1, ab, colour.red(), colour.green(), colour.blue());
        VfxDraw.vertex(c, pose, e, n, 0, 1, aa, colour.red(), colour.green(), colour.blue());
    }

    /** Diamond flash and four long shards at the server-confirmed contact; no soft halo. */
    private static void sharpContact(Contact contact, PoseStack.Pose pose, Vec3 camera, VertexConsumer c, float age) {
        if (age < 0 || age >= CONTACT_LIFE) return;
        Vec3 n = camera.subtract(contact.point()).normalize();
        // A small surface bias keeps the flash above the struck face, with normal depth testing.
        Vec3 at = contact.point().add(n.scale(0.025D));
        Vec3 u = n.cross(Math.abs(n.y) > 0.95D ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
        Vec3 v = u.cross(n).normalize();
        float fade = 1 - age / CONTACT_LIFE;
        double size = 0.24D * (0.75D + 0.25D * fade);
        colouredQuad(c, pose, at.add(u.scale(size)), at.add(v.scale(size * 1.5D)),
                at.subtract(u.scale(size)), at.subtract(v.scale(size * 1.5D)), n, fade, fade, FINISHER_EDGE);
        for (int i = 0; i < 4; i++) {
            double angle = i * Math.PI / 2 + 0.35D;
            Vec3 dir = u.scale(Math.cos(angle)).add(v.scale(Math.sin(angle)));
            Vec3 side = n.cross(dir).scale(0.055D * fade);
            Vec3 root = at.add(dir.scale(0.12D + age * 0.07D));
            Vec3 tip = root.add(dir.scale((i % 2 == 0 ? 0.65D : 0.45D) * fade));
            colouredQuad(c, pose, root.subtract(side), tip, tip, root.add(side), n, fade, fade, FINISHER_EDGE);
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
