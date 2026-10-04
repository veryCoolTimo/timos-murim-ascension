package io.github.verycooltimo.murim.world.fortress;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.datafixers.util.Pair;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.boss.BossRegistry;
import io.github.verycooltimo.murim.entity.boss.FortressMaster;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Отладка крепостей (оператор, docs/design/26-boss.md): {@code /murim fortress} — ближайшая
 * крепость и её состояние; {@code tp} — к воротам снаружи; {@code reset} — убрать хозяина и
 * забыть победу (заселится заново); {@code place} — построить крепость под игроком (стенд, тесты);
 * {@code flag on|off} — флаг победы игрока (условие прорыва 2). Обычный поиск —
 * {@code /locate structure murim:green_forest_fortress}.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/event/RegisterCommandsEvent.java,
 * reference/minecraft-src/net/minecraft/world/level/chunk/ChunkGenerator.java#findNearestMapStructure.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class FortressCommand {

    private FortressCommand() {
    }

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("murim")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("fortress")
                        .executes(FortressCommand::info)
                        .then(Commands.literal("tp").executes(FortressCommand::teleport))
                        .then(Commands.literal("reset").executes(FortressCommand::reset))
                        .then(Commands.literal("place").executes(FortressCommand::place))
                        .then(Commands.literal("flag")
                                .then(Commands.literal("on").executes(c -> flag(c, true)))
                                .then(Commands.literal("off").executes(c -> flag(c, false))))));
    }

    /** Ближайшая крепость: сначала структура мира, иначе — построенная командой. */
    public static FortressData.Entry find(ServerLevel level, BlockPos from) {
        Holder<Structure> holder = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getHolder(Fortress.KEY).orElse(null);
        FortressData.Entry placed = Fortresses.nearest(level, from);
        if (placed != null && placed.yard.distSqr(from) < 200 * 200) {
            return placed;
        }
        if (holder != null) {
            Pair<BlockPos, Holder<Structure>> found = level.getChunkSource().getGenerator()
                    .findNearestMapStructure(level, HolderSet.direct(holder), from, 100, false);
            if (found != null) {
                long key = new ChunkPos(found.getFirst()).toLong();
                FortressPiece piece = Fortresses.piece(level, holder.value(), key);
                if (piece != null) {
                    return Fortresses.entry(level, key, piece);
                }
            }
        }
        return placed;
    }

    private static FortressData.Entry find(CommandContext<CommandSourceStack> c) {
        FortressData.Entry e = find(c.getSource().getLevel(), BlockPos.containing(c.getSource().getPosition()));
        if (e == null) {
            c.getSource().sendFailure(Component.translatable("command.murim.fortress.none"));
        }
        return e;
    }

    private static int info(CommandContext<CommandSourceStack> c) {
        FortressData.Entry e = find(c);
        if (e == null) {
            return 0;
        }
        FortressMaster m = Fortresses.master(c.getSource().getLevel(), e);
        String state = m != null ? "master alive" : e.defeatedAt != 0L ? "defeated x" + e.defeats : "untouched";
        c.getSource().sendSuccess(() -> Component.translatable("command.murim.fortress.info",
                e.yard.getX(), e.yard.getY(), e.yard.getZ(), state), false);
        return 1;
    }

    /** Ворота снаружи: игрок смотрит на плац. */
    public static void gate(ServerLevel level, ServerPlayer p, FortressData.Entry e, int distance) {
        BlockPos at = FortressBuilder.world(e.yard.getX(), e.yard.getY(), e.yard.getZ(), e.rotation, 0, FortressBuilder.MIN_Z + 1 - distance);
        level.getChunk(at.getX() >> 4, at.getZ() >> 4);
        float yaw = io.github.verycooltimo.murim.entity.boss.BossRules.yawTo(e.yard.getX() - at.getX(), e.yard.getZ() - at.getZ());
        p.teleportTo(level, at.getX() + 0.5D, e.yard.getY() + 0.1D, at.getZ() + 0.5D, yaw, 8.0F);
    }

    private static int teleport(CommandContext<CommandSourceStack> c) {
        FortressData.Entry e = find(c);
        if (e == null || c.getSource().getPlayer() == null) {
            return 0;
        }
        gate(c.getSource().getLevel(), c.getSource().getPlayer(), e, 0);
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> c) {
        FortressData.Entry e = find(c);
        if (e == null) {
            return 0;
        }
        ServerLevel level = c.getSource().getLevel();
        FortressMaster m = Fortresses.master(level, e);
        if (m != null) {
            m.discard();
        }
        e.master = null;
        e.defeatedAt = 0L;
        Fortresses.data(level).changed();
        FortressBuilder.vaultDoor(Fortresses.liveSink(level), e.yard.getX(), e.yard.getY(), e.yard.getZ(), e.rotation, false);
        c.getSource().sendSuccess(() -> Component.translatable("command.murim.fortress.reset"), true);
        return 1;
    }

    /** Построить крепость под игроком (пол плаца — на уровне ног) и посадить хозяина. */
    private static int place(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        BlockPos at = BlockPos.containing(c.getSource().getPosition());
        FortressData.Entry e = placeAt(level, at, Rotation.NONE, at.asLong());
        Fortresses.spawnMaster(level, e);
        c.getSource().sendSuccess(() -> Component.translatable("command.murim.fortress.placed", at.getX(), at.getY(), at.getZ()), true);
        return 1;
    }

    /** Построить крепость в живом мире: центр плаца {@code at}, пол на высоте {@code at.y}. */
    public static FortressData.Entry placeAt(ServerLevel level, BlockPos at, Rotation rot, long seed) {
        new FortressBuilder(Fortresses.liveSink(level), at.getX(), at.getY(), at.getZ(), rot, seed).build();
        long key = at.asLong() ^ 0x5F0F7E55L;
        FortressData.Entry e = new FortressData.Entry(key, at, rot, seed);
        Fortresses.data(level).put(e);
        return e;
    }

    private static int flag(CommandContext<CommandSourceStack> c, boolean on) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null) {
            return 0;
        }
        p.setData(BossRegistry.BOSS_DEFEATED, on);
        c.getSource().sendSuccess(() -> Component.literal("boss_defeated = " + on), false);
        return 1;
    }
}
