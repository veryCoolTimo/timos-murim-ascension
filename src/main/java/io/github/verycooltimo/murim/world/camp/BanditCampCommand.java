package io.github.verycooltimo.murim.world.camp;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.datafixers.util.Pair;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.Bandit;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Отладка лагерей бандитов (оператор): {@code /murim camp} — ближайший лагерь и его состояние,
 * {@code tp} — к воротам снаружи, {@code reset} — убрать банду и забыть состояние (заселится
 * заново), {@code lootsim [N]} — добыча N походов на настоящих таблицах (docs/design/24 §4).
 * Обычный поиск — ванильный {@code /locate structure murim:bandit_camp}.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/chunk/ChunkGenerator.java#findNearestMapStructure,
 * reference/neoforge-src/net/neoforged/neoforge/event/RegisterCommandsEvent.java.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class BanditCampCommand {

    private BanditCampCommand() {
    }

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("murim")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("camp")
                        .executes(BanditCampCommand::info)
                        .then(Commands.literal("tp").executes(BanditCampCommand::teleport))
                        .then(Commands.literal("reset").executes(BanditCampCommand::reset))
                        .then(Commands.literal("lootsim").executes(c -> lootsim(c, 1000))
                                .then(Commands.argument("runs", IntegerArgumentType.integer(1, 20000))
                                        .executes(c -> lootsim(c, IntegerArgumentType.getInteger(c, "runs")))))));
    }

    /** Ближайший лагерь: кусок структуры и ключ (чанк начала). */
    public record Found(BanditCampPiece piece, long key) {
    }

    public static Found nearest(ServerLevel level, BlockPos from) {
        Holder<Structure> holder = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getHolder(BanditCamp.KEY).orElse(null);
        if (holder == null) {
            return null;
        }
        Pair<BlockPos, Holder<Structure>> found = level.getChunkSource().getGenerator()
                .findNearestMapStructure(level, HolderSet.direct(holder), from, 100, false);
        if (found == null) {
            return null;
        }
        long key = new ChunkPos(found.getFirst()).toLong();
        BanditCampPiece piece = BanditCamps.piece(level, holder.value(), key);
        return piece == null ? null : new Found(piece, key);
    }

    private static Found find(CommandContext<CommandSourceStack> c) {
        Found f = nearest(c.getSource().getLevel(), BlockPos.containing(c.getSource().getPosition()));
        if (f == null) {
            c.getSource().sendFailure(Component.translatable("command.murim.camp.none"));
        }
        return f;
    }

    private static int info(CommandContext<CommandSourceStack> c) {
        Found f = find(c);
        if (f == null) {
            return 0;
        }
        BanditCampData.Camp camp = BanditCamps.data(c.getSource().getLevel()).get(f.key());
        BanditCampPiece p = f.piece();
        String state = camp == null || !camp.populated ? "untouched" : camp.cleared ? "cleared" : "alive " + camp.alive.size();
        c.getSource().sendSuccess(() -> Component.translatable("command.murim.camp.info", p.centreX(), p.height(0), p.centreZ(), state)
                .append(" ").append(Component.literal("[tp]").withStyle(Style.EMPTY.withColor(0x9FD8A0)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/murim camp tp"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("/murim camp tp"))))), false);
        return 1;
    }

    /** К воротам снаружи, лицом в лагерь. */
    private static int teleport(CommandContext<CommandSourceStack> c) {
        Found f = find(c);
        if (f == null || c.getSource().getPlayer() == null) {
            return 0;
        }
        ServerLevel level = c.getSource().getLevel();
        CampLayout plan = CampLayout.plan(f.piece().seed());
        double[] g = plan.gateDirection();
        double dist = plan.radius() + 9.0D;
        int x = (int) Math.round(f.piece().centreX() + g[0] * dist);
        int z = (int) Math.round(f.piece().centreZ() + g[1] * dist);
        level.getChunk(x >> 4, z >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        Vec3 look = new Vec3(f.piece().centreX() - x, 0.0D, f.piece().centreZ() - z);
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        c.getSource().getPlayer().teleportTo(level, x + 0.5D, y, z + 0.5D, yaw, 10.0F);
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> c) {
        Found f = find(c);
        if (f == null) {
            return 0;
        }
        ServerLevel level = c.getSource().getLevel();
        BanditCampData.Camp camp = BanditCamps.data(level).get(f.key());
        if (camp != null) {
            for (Bandit b : level.getEntitiesOfClass(Bandit.class, new AABB(camp.centre).inflate(48.0D), b -> b.campKey() == f.key())) {
                b.discard();
            }
            camp.alive.clear();
            camp.populated = false;
            camp.cleared = false;
            BanditCamps.data(level).setDirty();
        }
        c.getSource().sendSuccess(() -> Component.translatable("command.murim.camp.reset"), true);
        return 1;
    }

    private static int lootsim(CommandContext<CommandSourceStack> c, int runs) {
        for (CampLootSim.Result r : CampLootSim.run(c.getSource().getLevel(), runs, c.getSource().getLevel().getGameTime())) {
            c.getSource().sendSuccess(() -> Component.literal(r.toString()), false);
        }
        return 1;
    }
}
