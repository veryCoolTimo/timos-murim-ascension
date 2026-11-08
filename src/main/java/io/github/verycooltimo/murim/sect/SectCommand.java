package io.github.verycooltimo.murim.sect;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * The sect nodes of the command tree (docs/design/44-commands.md). {@link #journal()} is the operator journal,
 * {@code /murim dev sect log [hud]}. The operator groups of {@code /murim dev} are {@link #member()} ({@code dev sect}),
 * {@link #schedule()} ({@code dev sched}), {@link #mapNode()} ({@code dev map}), and {@code dev repairs} and
 * {@code dev yard}, which {@link SectAdminCommand} and {@link SectYardCommand} own.
 */
public final class SectCommand {

    private SectCommand() {
    }

    /** {@code /murim dev sect log [hud]}: the operator journal; guarded by the dev root. */
    public static LiteralArgumentBuilder<CommandSourceStack> journal() {
        return Commands.literal("log").executes(c -> {
            // Журнал секты: что ждали и что сделано сегодня, итог словами наставника.
            ServerPlayer p = c.getSource().getPlayerOrException();
            c.getSource().sendSuccess(() -> SectAttendance.line(p), false);
            c.getSource().sendSuccess(() -> SectAttendance.verdict(p), false);
            c.getSource().sendSuccess(() -> SectJourney.line(p), false);
            return 1;
        }).then(Commands.literal("hud").executes(c -> SectJourney.toggleHud(c.getSource().getPlayerOrException()) ? 1 : 0));
    }

    /** {@code /murim dev sect ...}: membership, standing, merit and the other state of one player. */
    public static LiteralArgumentBuilder<CommandSourceStack> member() {
        return SectAdminCommand.node(Commands.literal("sect")).then(journal())
                .then(Commands.literal("state").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    SectState s = p.getData(ModAttachments.SECT);
                    c.getSource().sendSuccess(() -> Component.literal("member=" + s.member() + " generation=" + s.generation()
                            + " standing=" + SectService.standing(p).id() + " contribution=" + s.contribution() + " merit=" + s.merit()
                            + " flags=" + s.flags().stream().sorted().toList()), false);
                    return 1;
                }))
                .then(Commands.literal("buy").then(Commands.argument("offer", StringArgumentType.word()).executes(c -> {
                    // Обмен заслуг без разговора (проверка цен).
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    var r = MeritShop.buy(p, StringArgumentType.getString(c, "offer"));
                    c.getSource().sendSuccess(() -> Component.literal("merit buy: " + r), false);
                    return 1;
                })))
                .then(Commands.literal("flag").then(Commands.argument("flag", StringArgumentType.string()).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    String f = StringArgumentType.getString(c, "flag");
                    p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).with(f));
                    c.getSource().sendSuccess(() -> Component.literal("+" + f), false);
                    return 1;
                })))
                // Dev/playtest exit from the penance cave (author 10.10).
                .then(Commands.literal("release").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    if (!io.github.verycooltimo.murim.sect.seal.PenanceService.sentenced(p)) {
                        c.getSource().sendSuccess(() -> Component.literal("not sentenced"), false);
                        return 0;
                    }
                    io.github.verycooltimo.murim.sect.seal.PenanceService.release(p);
                    return 1;
                }));
    }

    /** {@code /murim dev sched ...}: who is where, the clock of the sect day, the review. */
    public static LiteralArgumentBuilder<CommandSourceStack> schedule() {
        return Commands.literal("sched")
                .then(Commands.literal("spawn").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    int n = SectService.spawnAround(p);
                    c.getSource().sendSuccess(() -> Component.translatable("command.murim.sect.spawned", n), false);
                    return n;
                }))
                .then(Commands.literal("life").executes(c -> {
                    // Кто где и что делает (проверка распорядка без похода по горе).
                    for (String line : SectLife.report(c.getSource().getServer().overworld())) {
                        c.getSource().sendSuccess(() -> Component.literal(line), false);
                        MurimMod.LOGGER.info("Секта: {}", line);
                    }
                    return 1;
                }))
                .then(Commands.literal("audit").executes(c -> {
                    // Every hop of the waypoint graph asked of the real pathfinder (SectRouteAudit; lines "Sect-audit:" in the log).
                    var layout = SectLife.layout(c.getSource().getServer().overworld());
                    var report = SectRouteAudit.run(c.getSource().getServer().overworld(), layout == null ? null : layout.file());
                    if (report == null) {
                        c.getSource().sendFailure(Component.literal("no built sect in this world"));
                        return 0;
                    }
                    c.getSource().sendSuccess(() -> Component.literal(report.lines().get(report.lines().size() - 1)), false);
                    return report.unreachable() + report.detours();
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
                .then(Commands.literal("review").executes(c -> {
                    // Смотр учеников (SectReview): перемотка к началу ближайшего смотра; начнётся сам, если рядом игрок.
                    var level = c.getSource().getServer().overworld();
                    long day = SectSchedule.day(level.getDayTime()) + SectReview.daysUntil(level.getDayTime());
                    if (SectReview.state(level) != null) {
                        c.getSource().sendSuccess(() -> Component.literal("review running: " + SectReview.state(level).bouts().size() + " bouts"), false);
                        return 1;
                    }
                    for (var l : c.getSource().getServer().getAllLevels()) {
                        l.setDayTime(day * 24000L + SectReview.FROM + 5);
                    }
                    c.getSource().sendSuccess(() -> Component.literal("sect review day " + day), false);
                    return 1;
                }));
    }

    /** {@code /murim dev map ...}: the sect map state and rebuild, the layout sketch boxes. */
    public static LiteralArgumentBuilder<CommandSourceStack> mapNode() {
        return SectAdminCommand.mapNode()
                .then(Commands.literal("boxes")
                        .executes(c -> SectLayoutBoxes.place(c.getSource()))
                        .then(Commands.literal("clear").executes(c -> SectLayoutBoxes.clear(c.getSource()))));
    }
}
