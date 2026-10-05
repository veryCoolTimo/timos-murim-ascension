package io.github.verycooltimo.murim.training;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /murim body} — body level, points, fatigue, best times; {@code /murim body points <n>} — set points
 * (testing levels); {@code /murim body fatigue <0..1>}; {@code /murim body reset}.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/event/RegisterCommandsEvent.java
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class TrainingCommand {

    private TrainingCommand() {
    }

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("murim")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("body")
                        .executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            for (String line : TrainingService.report(p)) {
                                c.getSource().sendSuccess(() -> Component.literal(line), false);
                                MurimMod.LOGGER.info("Body: {}", line);
                            }
                            return 1;
                        })
                        .then(Commands.literal("points").then(Commands.argument("n", DoubleArgumentType.doubleArg(0.0D)).executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            TrainingService.setPoints(p, DoubleArgumentType.getDouble(c, "n"));
                            c.getSource().sendSuccess(() -> Component.literal("body level " + TrainingService.level(p)), false);
                            return 1;
                        })))
                        .then(Commands.literal("fatigue").then(Commands.argument("f", DoubleArgumentType.doubleArg(0.0D, 1.0D)).executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            p.setData(TrainingRegistry.BODY, p.getData(TrainingRegistry.BODY).withFatigue(DoubleArgumentType.getDouble(c, "f")));
                            TrainingNetwork.body(p);
                            return 1;
                        })))
                        .then(Commands.literal("reset").executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            p.setData(TrainingRegistry.BODY, BodyState.NONE);
                            TrainingService.applyEffects(p);
                            TrainingNetwork.body(p);
                            c.getSource().sendSuccess(() -> Component.literal("body reset"), false);
                            return 1;
                        }))));
    }
}
