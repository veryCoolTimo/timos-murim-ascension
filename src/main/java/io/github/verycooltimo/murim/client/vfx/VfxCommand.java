package io.github.verycooltimo.murim.client.vfx;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/** Local dev commands; no permissions, config, keybind or network payload. */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class VfxCommand {
    @SubscribeEvent
    static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("murim")
                .then(Commands.literal("vfx")
                        .then(Commands.literal("shockwave").executes(ctx -> shockwave()))
                        .then(Commands.literal("rainfall").executes(ctx -> rainfall()))));
    }

    private static int select(String id) { return VfxBackends.select(id) ? report() : 0; }

    private static int report() {
        var player = Minecraft.getInstance().player;
        if (player == null) { return 0; }
        player.displayClientMessage(Component.translatable("command.murim.vfx.backend", VfxBackends.activeId()), false);
        return 1;
    }

    private static int shockwave() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) { return 0; }
        Vec3 forward = Vec3.directionFromRotation(0.0F, mc.player.getYRot());
        Vec3 at = QiShockwaveVfx.ground(mc.level, mc.player.position().add(forward.scale(4.0D)));
        VfxBackends.shockwave(mc.level, at);
        return 1;
    }

    private static int rainfall() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) { return 0; }
        Vec3 forward = Vec3.directionFromRotation(0.0F, mc.player.getYRot());
        Vec3 at = QiShockwaveVfx.ground(mc.level, mc.player.position().add(forward.scale(8.0D)));
        VfxBackends.rainfall(mc.level, at);
        mc.player.displayClientMessage(Component.translatable("command.murim.vfx.rainfall", VfxBackends.activeId()), false);
        return 1;
    }

    private VfxCommand() { }
}
