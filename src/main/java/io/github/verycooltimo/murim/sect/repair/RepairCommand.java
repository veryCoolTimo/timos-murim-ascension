package io.github.verycooltimo.murim.sect.repair;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.verycooltimo.murim.Config;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /murim dev repairs} (operators, docs/design/39-player-path-v2.md, «Ремонт секты и казна»): the state in numbers;
 * {@code age <ticks>} makes every record older, the fast-forward of the grace period (a quarter of a day) for tests and filming;
 * {@code treasury <liang>} sets the treasury; {@code forget} drops all records and marks of player building.
 */
public final class RepairCommand {
    private RepairCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("repairs")
                .executes(c -> status(c.getSource()))
                .then(Commands.literal("age").then(Commands.argument("ticks", LongArgumentType.longArg(0L))
                        .executes(c -> age(c.getSource(), LongArgumentType.getLong(c, "ticks")))))
                .then(Commands.literal("treasury").then(Commands.argument("liang", IntegerArgumentType.integer(0))
                        .executes(c -> treasury(c.getSource(), IntegerArgumentType.getInteger(c, "liang")))))
                .then(Commands.literal("forget").executes(c -> forget(c.getSource())));
    }

    private static RepairData data(CommandSourceStack src) {
        return RepairData.get(src.getServer());
    }

    private static int status(CommandSourceStack src) {
        if (!Config.SECT_REPAIRS.get()) {
            src.sendSuccess(() -> Component.translatable("command.murim.sect.repairs.off"), false);
        }
        RepairData d = data(src);
        src.sendSuccess(() -> RepairService.describe(d), false);
        io.github.verycooltimo.murim.MurimMod.LOGGER.info("Sect repairs status: {} damaged blocks, treasury {} wen, job {}", d.damageCount(),
                d.treasury(), d.job() == null ? "none" : d.job().targets().size() + " blocks left, crew " + d.job().crew());
        return d.damageCount();
    }

    private static int age(CommandSourceStack src, long ticks) {
        RepairData d = data(src);
        int n = d.age(ticks);
        src.sendSuccess(() -> Component.translatable("command.murim.sect.repairs.aged", ticks, n), true);
        return n;
    }

    private static int treasury(CommandSourceStack src, int liang) {
        data(src).setTreasury((long) liang * RepairCosts.WEN_PER_LIANG);
        src.sendSuccess(() -> Component.translatable("command.murim.sect.repairs.treasury", liang), true);
        return liang;
    }

    private static int forget(CommandSourceStack src) {
        RepairData d = data(src);
        int n = d.damageCount();
        d.clear();
        src.sendSuccess(() -> Component.translatable("command.murim.sect.repairs.forgot", n), true);
        return n;
    }
}
