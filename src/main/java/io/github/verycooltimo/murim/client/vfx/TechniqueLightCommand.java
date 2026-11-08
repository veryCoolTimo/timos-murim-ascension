package io.github.verycooltimo.murim.client.vfx;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.command.MurimCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/** Local operator tools; registration/permission pattern matches FaceDevCommand. */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class TechniqueLightCommand {
    private TechniqueLightCommand() {}
    // API: supplied neoforge RegisterClientCommandsEvent#getDispatcher; minecraft Commands,
    // SharedSuggestionProvider#hasPermission, Minecraft#hitResult, HitResult#getLocation/getType.
    @SubscribeEvent
    static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("murim").then(Commands.literal("dev")
                .requires(s -> MurimCommands.devEnabled() && s.hasPermission(2))
                .then(Commands.literal("glow")
                        .then(Commands.literal("off").executes(c -> glow(false)))
                        .then(Commands.literal("on").executes(c -> glow(true))))
                .then(Commands.literal("light")
                        .then(Commands.argument("r", IntegerArgumentType.integer(0, 255))
                        .then(Commands.argument("g", IntegerArgumentType.integer(0, 255))
                        .then(Commands.argument("b", IntegerArgumentType.integer(0, 255))
                        .then(Commands.argument("radius", FloatArgumentType.floatArg(.5F, 16F))
                        .then(Commands.argument("intensity", FloatArgumentType.floatArg(0F, 1F))
                                .executes(c -> light(IntegerArgumentType.getInteger(c, "r") << 16
                                                | IntegerArgumentType.getInteger(c, "g") << 8 | IntegerArgumentType.getInteger(c, "b"),
                                        FloatArgumentType.getFloat(c, "radius"), FloatArgumentType.getFloat(c, "intensity")))))))))));
    }
    private static int glow(boolean on) {
        TechniqueGlow.enabled(on);
        say("command.murim.glow", on ? "on" : "off");
        return 1;
    }
    private static int light(int rgb, float radius, float intensity) {
        var mc = Minecraft.getInstance();
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            say("command.murim.light.aim"); return 0;
        }
        var result = TechniqueLight.add(hit.getLocation().add(0, .05, 0), rgb, radius, intensity, 0, 194, 6);
        say(result == null ? "command.murim.light.full" : "command.murim.light.placed");
        return result == null ? 0 : 1;
    }
    private static void say(String key, Object... args) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.translatable(key, args), false);
    }
}
