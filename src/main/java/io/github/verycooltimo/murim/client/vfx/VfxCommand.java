package io.github.verycooltimo.murim.client.vfx;

import io.github.verycooltimo.murim.MurimMod;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import io.github.verycooltimo.murim.client.vfx.preset.PresetLibrary;
import io.github.verycooltimo.murim.client.vfx.preset.PresetRuntime;
import io.github.verycooltimo.murim.command.MurimCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/** Local dev commands ({@code /murim dev vfx ...}); operator permission, no keybind or network payload; hidden with the dev tree. */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class VfxCommand {
    @SubscribeEvent
    static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("murim")
                .then(Commands.literal("dev").requires(s -> MurimCommands.devEnabled() && s.hasPermission(2))
                        .then(Commands.literal("vfx")
                                .then(benchCommand())
                                .then(Commands.literal("play").then(Commands.argument("name",StringArgumentType.word())
                                        .executes(ctx->PresetRuntime.play(StringArgumentType.getString(ctx,"name"),0))
                                        .then(Commands.argument("seconds",DoubleArgumentType.doubleArg(.1,300))
                                                .executes(ctx->PresetRuntime.play(StringArgumentType.getString(ctx,"name"),DoubleArgumentType.getDouble(ctx,"seconds"))))))
                                .then(Commands.literal("reload").executes(ctx -> PresetRuntime.reload()))
                                .then(Commands.literal("preset")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .suggests((ctx, builder) -> {
                                                    // Lazy resource loading stays in play/reload, never in suggestions.
                                                    var names = PresetLibrary.loaded() ? PresetLibrary.names()
                                                            : java.util.Set.of("main", "fist", "arc", "green", "silk");
                                                    names.stream().sorted().filter(n -> n.startsWith(builder.getRemaining()))
                                                            .forEach(builder::suggest);
                                                    return builder.buildFuture();
                                                })
                                                .executes(ctx -> PresetRuntime.play(StringArgumentType.getString(ctx, "name"), 0))
                                                .then(Commands.argument("seconds", DoubleArgumentType.doubleArg(.1, 300))
                                                        .executes(ctx -> PresetRuntime.play(StringArgumentType.getString(ctx, "name"),
                                                                DoubleArgumentType.getDouble(ctx, "seconds"))))))
                                .then(Commands.literal("shockwave").executes(ctx -> shockwave()))
                                .then(Commands.literal("rainfall").executes(ctx -> rainfall())))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> benchCommand() {
        var loop=Commands.argument("loop",com.mojang.brigadier.arguments.BoolArgumentType.bool())
                .executes(ctx->bench(ctx,DoubleArgumentType.getDouble(ctx,"from_s"),DoubleArgumentType.getDouble(ctx,"to_s"),com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx,"loop")));
        var to=Commands.argument("to_s",DoubleArgumentType.doubleArg(0,300))
                .executes(ctx->bench(ctx,DoubleArgumentType.getDouble(ctx,"from_s"),DoubleArgumentType.getDouble(ctx,"to_s"),false)).then(loop);
        var from=Commands.argument("from_s",DoubleArgumentType.doubleArg(0,300))
                .executes(ctx->bench(ctx,DoubleArgumentType.getDouble(ctx,"from_s"),-1,false)).then(to);
        return Commands.literal("bench").then(Commands.argument("element",StringArgumentType.word())
                .suggests((ctx,b)->{java.util.List.of("line","line_thin","essences","fire1","alpha_test","preset:fist").forEach(b::suggest);return b.buildFuture();})
                .executes(ctx->bench(ctx,-1,-1,false)).then(from));
    }

    private static int bench(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx,double from,double to,boolean loop) {
        return io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.startBench(StringArgumentType.getString(ctx,"element"),from,to,loop);
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
