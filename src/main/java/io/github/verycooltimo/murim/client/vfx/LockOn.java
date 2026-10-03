package io.github.verycooltimo.murim.client.vfx;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.ModKeyMappings;
import io.github.verycooltimo.murim.client.TechniqueWheel;
import io.github.verycooltimo.murim.network.LockPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Захват цели (автор 03.10, «как в Devil May Cry»): Z — захватить ближайшего к прицелу врага до
 * 24 блоков в прямой видимости, ещё раз Z — снять. Камера мягко доворачивается на цель, над ней —
 * метка; все приёмы на сервере целятся в захваченного. Снимается сам: цель умерла, дальше 32 блоков,
 * пропала из виду на 2 с.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class LockOn {

    private static final double PICK_RANGE = 24.0D;
    private static int targetId = -1;
    private static int unseen;
    private static int age;

    public static int target() {
        return targetId;
    }

    public static LivingEntity entity() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && targetId >= 0 && mc.level.getEntity(targetId) instanceof LivingEntity t && t.isAlive() ? t : null;
    }

    /** Захватить сейчас (стенд съёмки): ближайший к прицелу, а если в конусе никого — ближайший вообще. */
    public static void lockNow() {
        LocalPlayer me = Minecraft.getInstance().player;
        if (me == null) {
            return;
        }
        LivingEntity t = pick(me);
        if (t == null) {
            t = me.level().getEntitiesOfClass(LivingEntity.class, me.getBoundingBox().inflate(PICK_RANGE),
                    e -> e != me && e.isAlive() && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand))
                    .stream().min(java.util.Comparator.comparingDouble(me::distanceToSqr)).orElse(null);
        }
        if (t != null) {
            set(t.getId());
        }
    }

    private static void set(int id) {
        if (id >= 0 && id != targetId) {
            io.github.verycooltimo.murim.client.Sfx.ui(io.github.verycooltimo.murim.registry.ModSounds.LOCK_ON, 0.25F, 1.0F);
        }
        targetId = id;
        unseen = 0;
        age = 0;
        PacketDistributor.sendToServer(new LockPayload(id));
    }

    /** Ближайший к прицелу живой противник (угол важнее расстояния). */
    public static LivingEntity pick(LocalPlayer me) {
        Vec3 eye = me.getEyePosition();
        Vec3 look = me.getLookAngle();
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (LivingEntity t : me.level().getEntitiesOfClass(LivingEntity.class, me.getBoundingBox().inflate(PICK_RANGE),
                e -> e != me && e.isAlive() && !e.isSpectator() && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand))) {
            Vec3 to = t.position().add(0.0D, t.getBbHeight() * 0.5D, 0.0D).subtract(eye);
            double d = to.length();
            double cos = to.normalize().dot(look);
            if (d > PICK_RANGE || cos < Math.cos(Math.toRadians(40.0D)) || !me.hasLineOfSight(t)) {
                continue;
            }
            double score = (1.0D - cos) * 40.0D + d * 0.15D;
            if (score < bestScore) {
                bestScore = score;
                best = t;
            }
        }
        return best;
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer me = mc.player;
        if (me == null) {
            targetId = -1;
            return;
        }
        while (ModKeyMappings.LOCK.consumeClick()) {
            if (targetId >= 0) {
                set(-1);
            } else {
                LivingEntity t = pick(me);
                if (t != null) {
                    set(t.getId());
                }
            }
        }
        if (targetId < 0) {
            return;
        }
        age++;
        LivingEntity t = entity();
        unseen = t != null && me.hasLineOfSight(t) ? 0 : unseen + 1;
        if (t == null || t.distanceTo(me) > 32.0D || unseen > 40) {
            set(-1);
            return;
        }
    }

    private static long lastFrameNs;

    /**
     * Доворот камеры на цель — каждый кадр, плавно (автор 03.10: «Z рваное, глаза болят»):
     * экспоненциальное сближение по реальному времени кадра, без ступенек по тикам.
     * API: build/moddev/artifacts/neoforge-21.1.248.jar#RenderFrameEvent.Pre
     */
    @SubscribeEvent
    static void onFrame(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
        long now = System.nanoTime();
        float dt = lastFrameNs == 0L ? 0.0F : Math.min(0.1F, (now - lastFrameNs) / 1.0E9F);
        lastFrameNs = now;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer me = mc.player;
        LivingEntity t = entity();
        if (me == null || t == null || mc.screen != null || TechniqueWheel.open() || mc.isPaused()) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 to = t.getPosition(partial).add(0.0D, t.getBbHeight() * 0.6D, 0.0D).subtract(me.getEyePosition(partial));
        float wantYaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
        float wantPitch = (float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
        // Полузатухание ~0,07 с по горизонтали, ~0,1 с по вертикали — жёстко, но гладко.
        float ky = 1.0F - (float) Math.exp(-dt * 10.0F);
        float kp = 1.0F - (float) Math.exp(-dt * 7.0F);
        float dy = Mth.wrapDegrees(wantYaw - me.getYRot()) * ky;
        float dp = (wantPitch - me.getXRot()) * kp;
        me.setYRot(me.getYRot() + dy);
        me.yRotO += dy;
        me.setXRot(Mth.clamp(me.getXRot() + dp, -90.0F, 90.0F));
        me.xRotO = Mth.clamp(me.xRotO + dp, -90.0F, 90.0F);
    }

    /** Метка захвата: розовый ромб над целью, медленно вращается и пульсирует. */
    @SubscribeEvent
    static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        LivingEntity t = entity();
        if (t == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        Vec3 at = t.getPosition(partial).add(0.0D, t.getBbHeight() + 0.55D, 0.0D);
        com.mojang.blaze3d.vertex.PoseStack ps = event.getPoseStack();
        net.minecraft.client.renderer.MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        ps.pushPose();
        try {
            ps.translate(-camera.x, -camera.y, -camera.z);
            net.minecraft.client.renderer.RenderType rt = MurimRenderTypes.airBand();
            com.mojang.blaze3d.vertex.VertexConsumer v = buffers.getBuffer(rt);
            double spin = (age + partial) * 0.08D;
            double r = 0.32D + 0.04D * Math.sin((age + partial) * 0.25D);
            Vec3[] p = new Vec3[5];
            for (int i = 0; i <= 4; i++) {
                double a = spin + i * Math.PI / 2.0D;
                p[i] = at.add(Math.cos(a) * r, i % 2 == 0 ? 0.0D : 0.0D, Math.sin(a) * r).add(0.0D, i % 2 == 0 ? r * 0.6D : -r * 0.6D, 0.0D);
            }
            double[] w = {0.05D, 0.05D, 0.05D, 0.05D, 0.05D};
            PlumVfx.strip(v, ps.last(), camera, p, w, 0.95F,
                    new VfxColour(1.0F, 0.55F, 0.75F));
            buffers.endBatch(rt);
        } finally {
            ps.popPose();
        }
    }

    private LockOn() {
    }
}
