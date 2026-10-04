package io.github.verycooltimo.murim.trade;

import com.mojang.brigadier.context.CommandContext;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Отладка торговца (оператор): {@code /murim peddler} — в деревне ли точка и когда следующий заход,
 * {@code /murim peddler spawn} — поставить деревенского торговца здесь.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/event/RegisterCommandsEvent.java.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class PeddlerCommand {

    private PeddlerCommand() {
    }

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("murim")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("peddler")
                        .executes(PeddlerCommand::info)
                        .then(Commands.literal("spawn").executes(PeddlerCommand::spawn))));
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
