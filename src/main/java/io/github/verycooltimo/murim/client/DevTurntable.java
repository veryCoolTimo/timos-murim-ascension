package io.github.verycooltimo.murim.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Film stand only: MURIM_CAPTURE_TURNTABLE=N turns the nearest living subject once around its vertical axis every N ticks,
 * starting facing the camera at the first recorded frame (bandit silhouette films 10.11). Client-side rotation only.
 */
@EventBusSubscriber(modid = io.github.verycooltimo.murim.MurimMod.MODID, value = Dist.CLIENT)
public final class DevTurntable {
    private static final int TICKS = parse(System.getenv("MURIM_CAPTURE_TURNTABLE"));

    private static int parse(String raw) {
        try {
            return raw == null ? 0 : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (TICKS <= 0 || mc.level == null || mc.player == null || !Boolean.getBoolean("murim.capture")) return;
        var cam = mc.getCameraEntity() == null ? mc.player : mc.getCameraEntity();
        LivingEntity subject = null;
        double best = 1e9;
        for (var e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || e instanceof Player || e instanceof ArmorStand || !e.isAlive()) continue;
            double d = e.distanceToSqr(cam);
            if (d < best) { best = d; subject = le; }
        }
        if (subject == null || best > 400) return;
        double dx = cam.getX() - subject.getX(), dz = cam.getZ() - subject.getZ();
        float base = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float yaw = base + 360.0F * DevCaptureHandler.frameIndexNow() / TICKS;
        subject.setYRot(yaw);
        subject.yRotO = yaw;
        subject.setYHeadRot(yaw);
        subject.yHeadRotO = yaw;
        subject.yBodyRot = yaw;
        subject.yBodyRotO = yaw;
    }

    private DevTurntable() {
    }
}
