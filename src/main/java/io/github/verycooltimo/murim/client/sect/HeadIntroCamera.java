package io.github.verycooltimo.murim.client.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.camera.BreakthroughCamera;
import io.github.verycooltimo.murim.client.camera.CameraRig;
import io.github.verycooltimo.murim.client.camera.ShotPose;
import io.github.verycooltimo.murim.network.HeadIntroPayloads;
import io.github.verycooltimo.murim.sect.SectHeadIntro;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Client-only director. No Screen, dialogue choices or gameplay state; server owns the end. */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class HeadIntroCamera {
    private static ArmorStand camera;
    private static int head = -1;
    private static long started;
    private static CameraType savedType;
    private static boolean savedHud, jumpDown, skipSent;
    private static float fov = 1;
    private static float skipAt = -1;
    private static Vec3 skipPosition;
    private static float skipYaw, skipPitch, skipFov;
    private static Vec3 playerAt;
    private static float axis;
    private static Vec3 framedPosition;
    private static float framedYaw, framedPitch;
    private HeadIntroCamera() { }
    public static boolean active() { return camera != null; }
    public static int actor() { return head; }
    private static float age(float partial) {
        var level = Minecraft.getInstance().level;
        float raw = level == null ? 0 : level.getGameTime() - started + partial;
        return skipAt < 0 ? raw : 305 + raw - skipAt;
    }
    public static float auraScale(int entity, float partial) {
        return 1;
    }
    public static void accept(HeadIntroPayloads.View view) {
        if (view.head() < 0 && skipAt >= 0 && age(0) < 320) return;
        reset();
        Minecraft mc = Minecraft.getInstance();
        if (view.head() < 0 || mc.player == null || mc.level == null) return;
        DialogueCamera.reset();
        BreakthroughCamera.reset();
        head = view.head(); started = view.started();
        savedType = mc.options.getCameraType(); savedHud = mc.options.hideGui;
        playerAt = mc.player.position();
        var actor = mc.level.getEntity(head);
        axis = actor == null ? mc.player.getYRot() + 180 : actor.getYRot();
        mc.player.setYRot(axis + 180); mc.player.setYHeadRot(axis + 180); mc.player.setYBodyRot(axis + 180);
        camera = new ArmorStand(EntityType.ARMOR_STAND, mc.level);
        camera.setPos(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        mc.options.hideGui = true;
        mc.setCameraEntity(camera);
        jumpDown = mc.options.keyJump.isDown(); skipSent = false;
    }
    public static void reset() {
        Minecraft mc = Minecraft.getInstance();
        if (camera != null) {
            mc.options.hideGui = savedHud;
            mc.options.setCameraType(savedType);
            mc.setCameraEntity(mc.player);
        }
        io.github.verycooltimo.murim.client.camera.CinematicDof.off();
        camera = null; head = -1; skipSent = false; skipAt = -1; skipPosition = null;
        framedPosition = null; fov = 1;
    }
    @SubscribeEvent static void logout(ClientPlayerNetworkEvent.LoggingOut event) { reset(); }
    @SubscribeEvent static void clone(ClientPlayerNetworkEvent.Clone event) { reset(); }
    @SubscribeEvent static void tick(ClientTickEvent.Post event) {
        if (!active()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || camera.level() != mc.level || !mc.player.isAlive()) { reset(); return; }
        // A lost end packet cannot strand the HUD or camera. Normal completion is always server-driven.
        if (age(0) > SectHeadIntro.SCENE_TICKS + 40) { reset(); return; }
        if (skipAt >= 0 && age(0) >= 320) {
            PacketDistributor.sendToServer(new HeadIntroPayloads.Skip(started));
            reset(); return;
        }
        holdPlayer(mc);
        boolean down = mc.options.keyJump.isDown();
        if (down && !jumpDown && age(0) >= SectHeadIntro.SKIP_AFTER && !skipSent) {
            skipAt = mc.level.getGameTime() - started;
            skipPosition = framedPosition; skipYaw = framedYaw; skipPitch = framedPitch; skipFov = fov;
            skipSent = true;
        }
        jumpDown = down;
    }
    private static void holdPlayer(Minecraft mc) {
        while (mc.options.keyTogglePerspective.consumeClick()) { }
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        float yaw = axis + 180;
        mc.player.setYRot(yaw);
        mc.player.yRotO = yaw;
        mc.player.yBodyRot = mc.player.yBodyRotO = yaw;
        mc.player.yHeadRot = mc.player.yHeadRotO = yaw;
    }
    @SubscribeEvent static void movement(MovementInputUpdateEvent event) {
        if (!active()) return;
        var input = event.getInput();
        input.forwardImpulse = input.leftImpulse = 0;
        input.up = input.down = input.left = input.right = input.jumping = input.shiftKeyDown = false;
        event.getEntity().setSprinting(false);
    }
    @SubscribeEvent static void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        if (active()) { event.setCanceled(true); event.setSwingHand(false); }
    }
    // API: same ComputeCameraAngles positioning as client/camera/BreakthroughCamera; five-ray wall clearance.
    @SubscribeEvent static void angles(ViewportEvent.ComputeCameraAngles event) {
        if (!active() || event.getCamera().getEntity() != camera) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !(mc.level.getEntity(head) instanceof LivingEntity actor)) return;
        holdPlayer(mc);
        float partial = (float) event.getPartialTick(), t = age(partial);
        var shot = HeadIntroPlan.shot(t);
        ShotPose pose = shot.track().at((t - shot.from()) / (shot.until() - shot.from()));
        Vec3 feet = shot.player() ? playerAt : actor.position();
        float yaw = shot.player() ? axis + 180 : axis;
        Vec3 focus = CameraRig.focusPoint(feet, pose);
        var subject = shot.player() ? mc.player : actor;
        CameraRig.Clear clear = (from, to) -> {
            var hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, subject));
            double free = hit.getType() == HitResult.Type.MISS ? from.distanceTo(to) : from.distanceTo(hit.getLocation());
            // API: TangDagger uses AABB.inflate/clip for entity ray intersections.
            for (var entity : mc.level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class,
                    subject.getBoundingBox().inflate(pose.distance() + 1))) {
                if (entity == subject || entity == camera || entity == subject.getVehicle()) continue;
                var box = entity.getBoundingBox().inflate(CameraRig.MARGIN);
                if (box.contains(from)) return 0;
                var point = box.clip(from, to);
                if (point.isPresent()) free = Math.min(free, from.distanceTo(point.get()));
            }
            return free;
        };
        ShotPose safe = pose;
        float minimum = CameraRig.MIN_DISTANCE;
        float distance = CameraRig.allowed(focus, CameraRig.direction(yaw, safe), safe.distance(), clear);
        if (distance < minimum) {
            if (framedPosition != null) place(event);
            return; // Hold the last clear frame; keep subtitles, aura and skip alive.
        }
        Vec3 pos = focus.add(CameraRig.direction(yaw, safe).scale(distance));
        // Shoulder framing uses the actual locked player anchor, not an assumed hall spacing.
        if (shot.from() == 70) {
            float progress = HeadIntroPlan.smooth((t - 70) / 60);
            Vec3 forward = CameraRig.direction(axis, new ShotPose(0, 0, 1, 0, 0, 1));
            Vec3 side = CameraRig.direction(axis, new ShotPose(-90, 0, 1, 0, 0, 1));
            Vec3 wanted = playerAt.add(0, mc.player.getEyeHeight(), 0)
                    .add(forward.scale(1.6 - progress)).add(side.scale(.7));
            Vec3 ray = wanted.subtract(focus);
            float reach = CameraRig.allowed(focus, ray.normalize(), (float) ray.length(), clear);
            if (reach >= minimum) pos = focus.add(ray.normalize().scale(reach));
        }
        Vec3 previousPosition = framedPosition;
        framedPosition = pos;
        framedYaw = CameraRig.lookYaw(yaw, safe);
        framedPitch = CameraRig.lookPitch(safe);
        if (!shot.player()) {
            Vec3 view = focus.subtract(pos);
            framedYaw = (float) Math.toDegrees(Math.atan2(-view.x, view.z)) - 6;
            framedPitch = (float) -Math.toDegrees(Math.atan2(view.y, Math.sqrt(view.x*view.x + view.z*view.z))) - 3;
        }
        if (skipAt >= 0 && skipPosition != null) {
            float blend = HeadIntroPlan.smooth((t - 305) / 15);
            framedPosition = skipPosition.lerp(pos, blend);
            // Sweep from the previous clear frame too: a skip chord must not cross a beam or NPC.
            if (previousPosition != null) {
                Vec3 step = framedPosition.subtract(previousPosition);
                double length = step.length();
                if (length > 1.0E-6) {
                    double free = clear.free(previousPosition, framedPosition);
                    if (free < length) framedPosition = previousPosition.add(
                            step.scale(Math.max(0, free - CameraRig.MARGIN) / length));
                }
            }
            float delta = (framedYaw - skipYaw + 540) % 360 - 180;
            framedYaw = skipYaw + delta * blend;
            framedPitch = skipPitch + (framedPitch - skipPitch) * blend;
        }
        Vec3 focal = t >= 230 && t < 290 ? playerAt.add(0, mc.player.getEyeHeight(), 0)
                : actor.position().add(0, actor.getEyeHeight(), 0);
        io.github.verycooltimo.murim.client.camera.CinematicDof.focus(focal, framedPosition,
                HeadIntroPlan.blur(t), t < 130 ? 1.5F : .75F, HeadIntroPlan.envelope(t));
        fov = skipAt < 0 ? pose.fov() : skipFov + (pose.fov() - skipFov) * HeadIntroPlan.smooth((t - 305) / 15);
        place(event);
    }
    private static void place(ViewportEvent.ComputeCameraAngles event) {
        camera.setPos(framedPosition.x, framedPosition.y - camera.getEyeHeight(), framedPosition.z);
        camera.xo = camera.getX(); camera.yo = camera.getY(); camera.zo = camera.getZ();
        event.setYaw(framedYaw); event.setPitch(framedPitch); event.setRoll(0);
    }
    @SubscribeEvent static void lens(ViewportEvent.ComputeFov event) {
        if (active() && event.getCamera().getEntity() == camera) event.setFOV(event.getFOV() * fov);
    }
    // RenderGuiEvent surrounds Gui.render, so subtitles remain visible with hideGui=true.
    @SubscribeEvent static void subtitles(RenderGuiEvent.Post event) {
        if (!active()) return;
        Minecraft mc = Minecraft.getInstance();
        var g = event.getGuiGraphics();
        int w = g.guiWidth(), h = g.guiHeight();
        float t = age(event.getPartialTick().getGameTimeDeltaPartialTick(false));
        float bars = HeadIntroPlan.bars(t);
        g.fill(0, 0, w, Math.round(24 * bars), 0xFF000000);
        g.fill(0, h - Math.round(60 * bars), w, h, 0xFF000000);
        if (t >= 20 && t < 300) {
            int line = HeadIntroPlan.line(t);
            var lines = mc.font.split(Component.translatable("murim.sect.head_scene.line" + line), Math.max(40, w - 48));
            int y = h - 54;
            for (var text : lines) { g.drawString(mc.font, text, (w - mc.font.width(text)) / 2, y, 0xFFF2E8CE); y += 10; }
        }
        if (t >= SectHeadIntro.SKIP_AFTER && t < 305)
            g.drawCenteredString(mc.font, Component.translatable("murim.sect.head_scene.skip", mc.options.keyJump.getTranslatedKeyMessage()), w / 2, h - 13, 0xFFAAAAAA);
    }
}
