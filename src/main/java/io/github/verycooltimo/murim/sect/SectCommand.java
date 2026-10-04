package io.github.verycooltimo.murim.sect;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /murim sect spawn} — NPC секты у игрока (тест), {@code /murim sect state} — положение и флаги,
 * {@code /murim sect reset} — забыть секту, {@code /murim sect flag <флаг>} — поставить флаг (тест уроков).
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectCommand {

    private SectCommand() {
    }

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("murim")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("sect")
                        .then(Commands.literal("spawn").executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            int n = SectService.spawnAround(p);
                            c.getSource().sendSuccess(() -> Component.translatable("command.murim.sect.spawned", n), false);
                            return n;
                        }))
                        .then(Commands.literal("state").executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            SectState s = p.getData(ModAttachments.SECT);
                            c.getSource().sendSuccess(() -> Component.literal("member=" + s.member() + " generation=" + s.generation()
                                    + " flags=" + s.flags().stream().sorted().toList()), false);
                            return 1;
                        }))
                        .then(Commands.literal("reset").executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            p.setData(ModAttachments.SECT, SectState.NONE);
                            c.getSource().sendSuccess(() -> Component.translatable("command.murim.sect.reset"), false);
                            return 1;
                        }))
                        .then(Commands.literal("life").executes(c -> {
                            // Кто где и что делает (проверка распорядка без похода по горе).
                            for (String line : SectLife.report(c.getSource().getServer().overworld())) {
                                c.getSource().sendSuccess(() -> Component.literal(line), false);
                                MurimMod.LOGGER.info("Секта: {}", line);
                            }
                            return 1;
                        }))
                        .then(Commands.literal("settle").executes(c -> {
                            // Проверка: все люди секты — на местах текущей части суток.
                            int n = SectLife.settleAll(c.getSource().getServer().overworld());
                            c.getSource().sendSuccess(() -> Component.literal("sect settled: " + n), false);
                            return n;
                        }))
                        .then(Commands.literal("time").then(Commands.argument("period", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    for (SectSchedule.Period p : SectSchedule.Period.values()) {
                                        b.suggest(p.id());
                                    }
                                    return b.buildFuture();
                                })
                                .executes(c -> {
                                    // Перемотка к началу части суток: строй, завтрак, занятия, ужин, вечер, ночь.
                                    var period = SectSchedule.Period.of(StringArgumentType.getString(c, "period"));
                                    if (period.isEmpty()) {
                                        return 0;
                                    }
                                    var level = c.getSource().getServer().overworld();
                                    long day = Math.floorDiv(level.getDayTime(), 24000L) * 24000L;
                                    for (var l : c.getSource().getServer().getAllLevels()) {
                                        l.setDayTime(day + period.get().start() + 5);
                                    }
                                    c.getSource().sendSuccess(() -> Component.literal("sect time -> " + period.get().id()), false);
                                    return 1;
                                })))
                        .then(Commands.literal("flag").then(Commands.argument("flag", StringArgumentType.string()).executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            String f = StringArgumentType.getString(c, "flag");
                            p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).with(f));
                            c.getSource().sendSuccess(() -> Component.literal("+" + f), false);
                            return 1;
                        })))));
    }
}
