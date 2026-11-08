package io.github.verycooltimo.murim.trade;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Отладка торговца (оператор): {@code /murim dev peddler} — в деревне ли точка и когда следующий заход,
 * {@code /murim dev peddler spawn} — поставить деревенского торговца здесь.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/event/RegisterCommandsEvent.java.
 */
public final class PeddlerCommand {

    private PeddlerCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("peddler")
                .executes(PeddlerCommand::info)
                .then(Commands.literal("spawn").executes(PeddlerCommand::spawn));
    }

    private static int info(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        BlockPos pos = BlockPos.containing(c.getSource().getPosition());
        StructureStart start = PeddlerSpawns.village(level, pos);
        if (start == null) {
            c.getSource().sendSuccess(() -> Component.translatable("command.murim.peddler.none"), false);
            return 0;
        }
        Long next = PeddlerSpawns.data(level).villageNext.get(start.getChunkPos().toLong());
        long wait = next == null ? 0L : Math.max(0L, next - level.getGameTime());
        BlockPos centre = start.getPieces().get(0).getBoundingBox().getCenter();
        c.getSource().sendSuccess(() -> Component.translatable("command.murim.peddler.info",
                centre.toShortString(), wait / 20L), false);
        return 1;
    }

    private static int spawn(CommandContext<CommandSourceStack> c) {
        Peddler p = PeddlerSpawns.arrive(c.getSource().getLevel(), BlockPos.containing(c.getSource().getPosition()), Peddler.VILLAGE_STAY);
        return p == null ? 0 : 1;
    }
}
