package io.github.verycooltimo.murim.training;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /murim dev body} — body level, points, today's work by kind, best times; {@code /murim dev body points <n>} —
 * set points (testing levels); {@code /murim dev body scan} — look at the vanilla statistics now;
 * {@code /murim dev body reset} — forget the body (the statistics as they stand become the new baseline).
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/event/RegisterCommandsEvent.java
 */
public final class TrainingCommand {

    private TrainingCommand() {
    }

    /** {@code /murim dev body}: the body report; {@code MurimCommands} adds {@code qi} and {@code wall} to it. */
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("body")
                        .executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            for (Component line : TrainingService.report(p)) {
                                c.getSource().sendSuccess(() -> line, false);
                                MurimMod.LOGGER.info("Body: {}", line.getString());
                            }
                            return 1;
                        })
                        .then(Commands.literal("points").then(Commands.argument("n", DoubleArgumentType.doubleArg(0.0D)).executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            TrainingService.setPoints(p, DoubleArgumentType.getDouble(c, "n"));
                            c.getSource().sendSuccess(() -> Component.translatable("command.murim.dev.body_level", TrainingService.level(p)), false);
                            return 1;
                        })))
                        .then(Commands.literal("scan").executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            TrainingService.look(p);
                            for (Component line : TrainingService.report(p)) {
                                c.getSource().sendSuccess(() -> line, false);
                            }
                            return 1;
                        }))
                        .then(Commands.literal("reset").executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            TrainingService.reset(p);
                            c.getSource().sendSuccess(() -> Component.translatable("command.murim.dev.body_reset"), false);
                            return 1;
                        }));
    }
}
