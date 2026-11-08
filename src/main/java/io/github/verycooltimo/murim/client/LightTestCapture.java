package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.vfx.TechniqueLight;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/** Dev-only off/on fixture; frame clock is owned by DevCaptureHandler, never TickTask. */
public final class LightTestCapture {
    private LightTestCapture() {}
    // API: supplied MC Entity#moveTo/getEyeHeight and Minecraft#getCameraEntity,
    // Options#setCameraType; fixed +X actor separation means +Z is the side view.
    static void camera(Minecraft mc) {
        if (mc.player == null || !(mc.getCameraEntity() instanceof net.minecraft.world.entity.decoration.ArmorStand stand)) return;
        Vec3 aim = mc.player.position().add(1, .8, 0);
        Vec3 eye = mc.player.position().add(1, 2.4, 5);
        Vec3 direction = aim.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z)));
        stand.moveTo(eye.x, eye.y - stand.getEyeHeight(), eye.z, yaw, pitch);
        mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
    }
    // API: supplied minecraft-src: Minecraft#getSingleplayerServer; ServerPlayer#serverLevel;
    // ServerLevel#setDayTime/addFreshEntity; EntityGetter#getEntitiesOfClass; Entity#moveTo/discard,
    // Mob#setNoAi/setPersistenceRequired. SectDisciple uses the registered production model.
    static void tick(Minecraft mc, int age) {
        if (!Boolean.getBoolean("murim.capture") || mc.player == null || mc.level == null) return;
        if (age == 0 && mc.getSingleplayerServer() != null) {
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                if (player == null) return;
                var level = player.serverLevel();
                boolean day = "day".equalsIgnoreCase(System.getenv("MURIM_CAPTURE_LIGHT_TIME"));
                level.setDayTime(day ? 6000 : 18000);
                for (var old : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(12))) old.discard();
                SectDisciple actor = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), level);
                actor.setNoAi(true);
                actor.setPersistenceRequired();
                actor.moveTo(player.getX() + 2, player.getY(), player.getZ(), player.getYRot(), 0);
                level.addFreshEntity(actor);
                MurimMod.LOGGER.info("CAPTURE-LIGHT fixture: player/disciple separation=2 time={} ticks={}",
                        day ? "day" : "night", day ? 6000 : 18000);
            });
        }
        if (age == 60) {
            Vec3 center = mc.player.position().add(1, 1, 0);
            TechniqueLight.add(center, 0xff6fa8, 4, 1F, 0, 60, 0);
            MurimMod.LOGGER.info("CAPTURE-LIGHT on tick=60 rgb=FF6FA8 radius=4 intensity=1.0 duration=60");
        }
        if (age == 0 || age == 120) MurimMod.LOGGER.info("CAPTURE-LIGHT {} tick={}", age == 0 ? "off" : "done", age);
    }
}
