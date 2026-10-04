package io.github.verycooltimo.murim.client.sect;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Камера разговора (автор 03.10: «как в играх — экран, и там NPC стоит»): из глаз игрока плавно
 * уходит за плечо и берёт игрока и NPC в один кадр, NPC — в верхних двух третях (снизу панель).
 * По окончании так же плавно возвращается в глаза.
 *
 * <p>Камера — невидимая клиентская стойка вне мира: {@code Minecraft#setCameraEntity}; позиция
 * и поворот задаются каждый тик, кадр интерполирует их сам (Camera#setup лерпит xo→x и yRotO→yRot).
 * NeoForge рисует игрока, когда камера — не он (LevelRenderer, «Neo: render local player»), рука
 * от первого лица скрыта вместе с интерфейсом ({@code hideGui}).
 * API: reference/minecraft-src/net/minecraft/client/Camera.java#setup,
 * reference/minecraft-src/net/minecraft/client/Minecraft.java#setCameraEntity
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class DialogueCamera {

    /** Наезд и возврат, тиков. */
    private static final float IN_TICKS = 16.0F;
    private static final float OUT_TICKS = 12.0F;

    private static ArmorStand cam;
    private static int npc = -1;
    private static float progress;
    private static boolean leaving;
    private static boolean savedHideGui;
    private static net.minecraft.client.CameraType savedType;
    /** Откуда начался наезд: камера игрока (в том числе от третьего лица). */
    private static Vec3 startPos;
    private static float startYaw;
    private static float startPitch;

    private DialogueCamera() {
    }

    public static boolean active() {
        return cam != null;
    }

    /** Насколько камера дошла до кадра разговора, 0..1 (для полос и панели). */
    public static float amount(float partial) {
        if (cam == null) {
            return 0.0F;
        }
        float p = progress + (leaving ? -partial / OUT_TICKS : partial / IN_TICKS);
        return ease(Mth.clamp(p, 0.0F, 1.0F));
    }

    public static void start(int npcId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        npc = npcId;
        leaving = false;
        if (cam != null) {
            return;
        }
        cam = new ArmorStand(EntityType.ARMOR_STAND, mc.level);
        progress = 0.0F;
        // Игрок поворачивается к собеседнику: в кадре он смотрит на NPC.
        Entity target = mc.level.getEntity(npcId);
        if (target != null) {
            Vec3 d = target.position().subtract(mc.player.position());
            float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F;
            mc.player.setYRot(yaw);
            mc.player.setYHeadRot(yaw);
            mc.player.setYBodyRot(yaw);
            mc.player.setXRot(0.0F);
        }
        net.minecraft.client.Camera now = mc.gameRenderer.getMainCamera();
        startPos = now.isInitialized() ? now.getPosition() : eye(mc);
        startYaw = now.isInitialized() ? now.getYRot() : mc.player.getYRot();
        startPitch = now.isInitialized() ? now.getXRot() : mc.player.getXRot();
        place(startPos, startYaw, startPitch, true);
        savedHideGui = mc.options.hideGui;
        mc.options.hideGui = true;
        // Своя камера — «глаза» стойки: от третьего лица движок отодвинул бы её ещё на 4 блока.
        savedType = mc.options.getCameraType();
        mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
        mc.setCameraEntity(cam);
    }

    public static void stop() {
        if (cam != null) {
            leaving = true;
        }
    }

    /** Сразу в глаза (выход из мира, смерть). */
    public static void reset() {
        Minecraft mc = Minecraft.getInstance();
        if (cam != null) {
            mc.options.hideGui = savedHideGui;
            if (savedType != null) {
                mc.options.setCameraType(savedType);
            }
            if (mc.player != null) {
                mc.setCameraEntity(mc.player);
            }
        }
        cam = null;
        npc = -1;
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (cam == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || cam.level() != mc.level || !mc.player.isAlive()) {
            reset();
            return;
        }
        Entity target = mc.level.getEntity(npc);
        if (target == null) {
            leaving = true;
        }
        progress = Mth.clamp(progress + (leaving ? -1.0F / OUT_TICKS : 1.0F / IN_TICKS), 0.0F, 1.0F);
        // Последний отрезок возврата — склейка: камера рядом с глазами видела бы голову игрока изнутри.
        if (leaving && progress <= 0.3F) {
            reset();
            return;
        }
        Vec3 eye = eye(mc);
        float e = ease(progress);
        Vec3 shotPos = eye;
        float shotYaw = mc.player.getYRot();
        float shotPitch = mc.player.getXRot();
        if (target != null) {
            Vec3 head = target.getEyePosition();
            Vec3 d = new Vec3(head.x - eye.x, 0.0D, head.z - eye.z);
            double dist = Math.max(0.5D, d.length());
            d = d.scale(1.0D / dist);
            Vec3 right = new Vec3(-d.z, 0.0D, d.x);
            // За правым плечом: голова игрока — край кадра слева, NPC — справа от центра.
            double back = Mth.clamp(dist * 0.28D, 0.8D, 1.4D);
            Vec3 want = eye.subtract(d.scale(back)).add(right.scale(1.4D)).add(0.0D, 0.05D, 0.0D);
            // Камера не уходит в стену за спиной.
            var hit = mc.level.clip(new ClipContext(eye, want, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() != HitResult.Type.MISS) {
                want = hit.getLocation().add(eye.subtract(want).normalize().scale(0.25D));
            }
            shotPos = want;
            // Смотрим чуть ниже глаз NPC: его голова — в верхней трети кадра, низ занимает панель.
            // Узкий объектив (см. onFov) — NPC крупно, по пояс, в верхней половине кадра (низ — свиток).
            // codex 03.10: глаза NPC — правее центра (~55 % ширины) и на ~28 % высоты; плечо игрока — край слева.
            Vec3 aim = head.subtract(right.scale(0.3D)).add(0.0D, -0.78D, 0.0D);
            Vec3 look = aim.subtract(shotPos);
            shotYaw = (float) (Mth.atan2(look.z, look.x) * Mth.RAD_TO_DEG) - 90.0F;
            shotPitch = (float) (-Mth.atan2(look.y, Math.sqrt(look.x * look.x + look.z * look.z)) * Mth.RAD_TO_DEG);
        }
        // Наезд — от камеры игрока на момент начала; возврат — в живые глаза игрока.
        Vec3 basePos = leaving ? eye : startPos;
        float baseYaw = leaving ? mc.player.getYRot() : startYaw;
        float basePitch = leaving ? mc.player.getXRot() : startPitch;
        Vec3 pos = basePos.lerp(shotPos, e);
        if (target != null) {
            // Дуга вправо на полпути: камера обходит голову игрока, а не проходит сквозь неё.
            Vec3 toNpc = target.position().subtract(mc.player.position());
            Vec3 side = new Vec3(-toNpc.z, 0.0D, toNpc.x).normalize();
            pos = pos.add(side.scale(1.2D * Mth.sin(e * Mth.PI)));
        }
        float yaw = baseYaw + Mth.wrapDegrees(shotYaw - baseYaw) * e;
        float pitch = Mth.lerp(e, basePitch, shotPitch);
        place(pos, yaw, pitch, false);
    }

    private static Vec3 eye(Minecraft mc) {
        return mc.player.getEyePosition();
    }

    private static void place(Vec3 eyePos, float yaw, float pitch, boolean snap) {
        double y = eyePos.y - cam.getEyeHeight();
        if (snap) {
            cam.setPos(eyePos.x, y, eyePos.z);
            cam.xo = cam.getX();
            cam.yo = cam.getY();
            cam.zo = cam.getZ();
            cam.setYRot(yaw);
            cam.setXRot(pitch);
            cam.yRotO = yaw;
            cam.xRotO = pitch;
            // Стойка — LivingEntity: камера берёт поворот головы (LivingEntity#getViewYRot → yHeadRot).
            cam.yHeadRot = yaw;
            cam.yHeadRotO = yaw;
            return;
        }
        cam.xo = cam.getX();
        cam.yo = cam.getY();
        cam.zo = cam.getZ();
        cam.yRotO = cam.getYRot();
        cam.xRotO = cam.getXRot();
        cam.setPos(eyePos.x, y, eyePos.z);
        // Без разрыва через ±180: новый угол — ближайший к прошлому.
        cam.setYRot(cam.yRotO + Mth.wrapDegrees(yaw - cam.yRotO));
        cam.setXRot(pitch);
        cam.yHeadRotO = cam.yHeadRot;
        cam.yHeadRot = cam.getYRot();
    }

    /** Объектив разговора: поле зрения сужается вместе с наездом (план «по пояс»). */
    private static final double TALK_FOV = 36.0D;

    @SubscribeEvent
    static void onFov(net.neoforged.neoforge.client.event.ViewportEvent.ComputeFov event) {
        if (cam != null && event.getCamera().getEntity() == cam) {
            float a = amount((float) event.getPartialTick());
            event.setFOV(Mth.lerp(a, event.getFOV(), TALK_FOV));
        }
    }

    /** Плавный старт и мягкая посадка. */
    private static float ease(float t) {
        return t * t * (3.0F - 2.0F * t);
    }
}
