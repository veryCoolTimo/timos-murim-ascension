package io.github.verycooltimo.murim.client.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.DialoguePayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
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
 * Кадр ставится от NPC на постоянном расстоянии ({@link DialogueShot}, автор 06.10): одинаковый, подошёл ли игрок
 * вплотную или стоит в четырёх блоках; заслоняющие люди на время разговора не рисуются.
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
    /** Кадр разговора (DialogueShot) и где стоял NPC, когда он считался. */
    private static DialogueShot.Shot shot;
    private static Vec3 shotNpc = Vec3.ZERO;

    /**
     * Newcomer tour (docs/design/39-player-path-v2.md): the current fixed shot of a building, or null (camera on the speaker).
     * A change of shot, or the return to the speaker, glides from the pose the camera has at that moment; {@code blendT} runs
     * 0..1 over {@code blendTicks}. Esc ends the whole thing at once ({@link #stop}).
     */
    private static DialoguePayloads.Shot tourShot;
    /** Share of the lens angle the view is tilted down in a tour shot (the dialogue scroll covers the lower ~45 % of the frame). */
    private static final float TOUR_TILT = 0.22F;
    private static boolean blending;
    private static float blendT;
    private static float blendTicks = 40.0F;
    private static Vec3 blendPos = Vec3.ZERO;
    private static float blendYaw;
    private static float blendPitch;
    private static float blendFov;
    private static int tourAge;
    /** Lens now, vertical degrees; follows the tour blend (the speaker frame uses {@link DialogueShot#FOV}). */
    private static float tourFov = (float) DialogueShot.FOV;

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

    /** The camera is gliding between two shots: the new line waits for it before it prints. */
    public static boolean settled() {
        return !blending || blendT > 0.55F;
    }

    public static boolean touring() {
        return cam != null && tourShot != null;
    }

    public static void start(int npcId, DialoguePayloads.Shot tour) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        npc = npcId;
        shot = null;
        leaving = false;
        if (cam != null) {
            changeShot(tour);
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
        tourShot = null;
        blending = false;
        tourFov = (float) DialogueShot.FOV;
        changeShot(tour);
        savedHideGui = mc.options.hideGui;
        mc.options.hideGui = true;
        // Своя камера — «глаза» стойки: от третьего лица движок отодвинул бы её ещё на 4 блока.
        savedType = mc.options.getCameraType();
        mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
        mc.setCameraEntity(cam);
    }

    /** A new line: same shot keeps the camera, another (or none) glides from where it is now. */
    private static void changeShot(DialoguePayloads.Shot next) {
        if (java.util.Objects.equals(next, tourShot)) {
            return;
        }
        boolean wasTour = tourShot != null;
        if (next == null && !wasTour) {
            return;
        }
        Vec3 eyeNow = cam.getEyePosition();
        blendPos = eyeNow;
        blendYaw = cam.getYRot();
        blendPitch = cam.getXRot();
        blendFov = wasTour ? tourFov : (float) (shot == null ? DialogueShot.FOV : shot.fov());
        Vec3 to = next != null ? next.camera() : shotNpc;
        blendTicks = Mth.clamp(20.0F + (float) eyeNow.distanceTo(to) * 0.8F, 30.0F, 56.0F);
        if (next == null) {
            // Back to the speaker (end of the tour or «skip»): a short glide, not the full flight between buildings.
            blendTicks = Math.min(blendTicks, 24.0F);
        }
        blendT = 0.0F;
        blending = true;
        tourShot = next;
        tourAge = 0;
    }

    public static void stop() {
        if (cam == null) {
            return;
        }
        // Esc or the end during the tour: back to the eyes at once, no glide across the sect.
        if (tourShot != null || blending) {
            reset();
            return;
        }
        leaving = true;
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
        shot = null;
        tourShot = null;
        blending = false;
    }

    /** Кадр по миру: стены режут луч от груди NPC к камере. */
    private static DialogueShot.Shot plan(Minecraft mc, Entity target) {
        Vec3 look = mc.player.getLookAngle();
        // API: reference/minecraft-src/net/minecraft/world/level/BlockGetter.java#clip
        return DialogueShot.plan(target.position(), target.getBbHeight(), mc.player.position(), look, (from, to) -> {
            var hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, target));
            return hit.getType() == HitResult.Type.MISS ? from.distanceTo(to) : from.distanceTo(hit.getLocation());
        });
    }

    /**
     * Кадр не заслоняют: другие люди между камерой и собеседником (или у самой камеры) на время разговора не рисуются;
     * игрок — тоже, если стоит вплотную к NPC и закрыл бы его.
     * API: reference/neoforge-src/net/neoforged/neoforge/client/event/RenderLivingEvent.java#Pre (cancelable)
     */
    @SubscribeEvent
    static void onRenderLiving(net.neoforged.neoforge.client.event.RenderLivingEvent.Pre<?, ?> event) {
        if (cam == null || shot == null || amount(0.0F) < 0.5F || tourShot != null) {
            return;
        }
        LivingEntity e = event.getEntity();
        if (e == cam || e.getId() == npc) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (e == mc.player) {
            if (shot.hidePlayer()) {
                event.setCanceled(true);
            }
            return;
        }
        Entity target = mc.level == null ? null : mc.level.getEntity(npc);
        if (target == null) {
            return;
        }
        Vec3 c = cam.getEyePosition();
        Vec3 t = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        Vec3 p = e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D);
        Vec3 seg = t.subtract(c);
        double len2 = seg.lengthSqr();
        double k = len2 < 1.0E-6D ? 0.0D : Mth.clamp(p.subtract(c).dot(seg) / len2, 0.0D, 1.0D);
        double off = p.distanceTo(c.add(seg.scale(k)));
        if (p.distanceTo(c) < 1.2D || k > 0.0D && k < 0.92D && off < e.getBbWidth() * 0.5D + 0.7D) {
            event.setCanceled(true);
        }
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
            // Кадр от собеседника, а не от игрока (автор 06.10: вплотную NPC был на весь экран). План считается
            // раз и держится, пока NPC стоит; глаза NPC — на ~28 % высоты, сбоку от центра (codex 03.10).
            if (shot == null || target.position().distanceToSqr(shotNpc) > 1.0D) {
                shot = plan(mc, target);
                shotNpc = target.position();
            }
            shotPos = shot.camera();
            float[] look = DialogueShot.look(shotPos, target.position().add(0.0D, target.getBbHeight() * DialogueShot.AIM, 0.0D), shot.side());
            shotYaw = look[0];
            shotPitch = look[1];
        }
        // Наезд — от камеры игрока на момент начала; возврат — в живые глаза игрока.
        Vec3 basePos = leaving ? eye : startPos;
        float baseYaw = leaving ? mc.player.getYRot() : startYaw;
        float basePitch = leaving ? mc.player.getXRot() : startPitch;
        Vec3 pos = basePos.lerp(shotPos, e);
        if (target != null) {
            // Дуга вправо на полпути: камера обходит голову игрока, а не проходит сквозь неё.
            Vec3 toNpc = target.position().subtract(mc.player.position());
            Vec3 side = new Vec3(-toNpc.z, 0.0D, toNpc.x).normalize().scale(shot == null ? 1.0D : shot.side());
            pos = pos.add(side.scale(1.2D * Mth.sin(e * Mth.PI)));
        }
        float yaw = baseYaw + Mth.wrapDegrees(shotYaw - baseYaw) * e;
        float pitch = Mth.lerp(e, basePitch, shotPitch);
        if (!leaving && (tourShot != null || blending)) {
            // Tour: the fixed shot (with a slow push-in) or the glide back to the speaker frame.
            Vec3 goalPos = shotPos;
            float goalYaw = shotYaw;
            float goalPitch = shotPitch;
            float goalFov = shot == null ? (float) TALK_FOV : (float) shot.fov();
            if (tourShot != null) {
                tourAge++;
                Vec3 camAt = tourShot.camera();
                Vec3 toAim = tourShot.aim().subtract(camAt);
                double dist = toAim.length();
                // Slow push-in along the view, up to 8 % of the distance, so the shot is not a dead still.
                goalPos = camAt.add(toAim.scale(Math.min(0.08D, tourAge * 0.0016D)));
                double flat = Math.sqrt(toAim.x * toAim.x + toAim.z * toAim.z);
                goalYaw = (float) (Mth.atan2(toAim.z, toAim.x) * Mth.RAD_TO_DEG) - 90.0F;
                goalPitch = (float) -(Mth.atan2(toAim.y, flat) * Mth.RAD_TO_DEG);
                goalFov = tourShot.fov();
                // The scroll covers the lower part of the frame: look a little lower so the building sits in the open part.
                goalPitch += TOUR_TILT * goalFov;
                if (dist < 1.0E-3D) {
                    goalPos = camAt;
                }
            }
            if (blending) {
                blendT = Math.min(1.0F, blendT + 1.0F / blendTicks);
                float b = ease(blendT);
                pos = blendPos.lerp(goalPos, b);
                // The glide arcs up over the roofs: a straight line between two buildings would run through the walls.
                pos = pos.add(0.0D, Math.sin(b * Mth.PI) * Math.min(7.0D, blendPos.distanceTo(goalPos) * 0.22D), 0.0D);
                yaw = blendYaw + Mth.wrapDegrees(goalYaw - blendYaw) * b;
                pitch = Mth.lerp(b, blendPitch, goalPitch);
                tourFov = Mth.lerp(b, blendFov, goalFov);
                if (blendT >= 1.0F) {
                    blending = false;
                }
            } else {
                pos = goalPos;
                yaw = goalYaw;
                pitch = goalPitch;
                tourFov = goalFov;
            }
        }
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

    /** Объектив разговора: поле зрения сужается вместе с наездом (план «по пояс»); у стены — шире (DialogueShot). */
    private static final double TALK_FOV = DialogueShot.FOV;

    @SubscribeEvent
    static void onFov(net.neoforged.neoforge.client.event.ViewportEvent.ComputeFov event) {
        if (cam != null && event.getCamera().getEntity() == cam) {
            float a = amount((float) event.getPartialTick());
            double talk = tourShot != null || blending ? tourFov : shot == null ? TALK_FOV : shot.fov();
            event.setFOV(Mth.lerp(a, event.getFOV(), talk));
        }
    }

    /** Плавный старт и мягкая посадка. */
    private static float ease(float t) {
        return t * t * (3.0F - 2.0F * t);
    }
}
