package io.github.verycooltimo.murim.world.hua;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.verycooltimo.murim.MurimMod;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * {@code /murim mounthua} — where the mountain is (clickable teleports to the sect zones),
 * {@code /murim mounthua tp <place>} — teleport for testing, {@code /murim mounthua pregen
 * [margin]} — generate every chunk of the mountain (plus a margin in blocks) ahead of time (spread over ticks, 25 ms per tick).
 * Permission 2, like the rest of {@code /murim}. The in-game way to find the mountain (an echo)
 * comes later.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/event/RegisterCommandsEvent.java;
 * Brigadier merges this {@code murim} literal with the one in TechniqueCommand.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class MountHuaCommand {

    /** Places that {@code tp} knows besides the zones: local (u, v) and an extra height. */
    private static final List<String> PLACES = List.of("view", "view_ne", "view_east", "view_west",
            "summit", "north_peak", "golden_lock", "ridge", "gorge");

    private MountHuaCommand() {
    }

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        List<String> names = new ArrayList<>(PLACES);
        for (MountHuaPlan.Zone z : MountHuaPlan.ZONES) {
            names.add(z.id());
        }
        event.getDispatcher().register(Commands.literal("murim")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("mounthua")
                        .executes(MountHuaCommand::info)
                        .then(Commands.literal("tp")
                                .then(Commands.argument("place", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(names, b))
                                        .executes(c -> teleport(c, StringArgumentType.getString(c, "place")))))
                        .then(Commands.literal("pregen").executes(c -> pregen(c, 0))
                                .then(Commands.argument("margin", IntegerArgumentType.integer(0, 1024))
                                        .executes(c -> pregen(c, IntegerArgumentType.getInteger(c, "margin")))))));
    }

    private static MountHuaSite site(CommandContext<CommandSourceStack> context) {
        MountHuaSite site = MountHuaSites.get(context.getSource().getServer());
        if (site == null) {
            context.getSource().sendFailure(Component.translatable("command.murim.mounthua.none"));
        }
        return site;
    }

    private static int info(CommandContext<CommandSourceStack> context) {
        MountHuaSite site = site(context);
        if (site == null) {
            return 0;
        }
        int[] gate = zoneCenter(site, "gate");
        context.getSource().sendSuccess(() -> Component.translatable("command.murim.mounthua.info",
                site.centerX(), site.centerZ(), gate[0], gate[1], MountHuaSite.SUMMIT_Y), false);
        MutableComponent line = Component.translatable("command.murim.mounthua.places");
        List<String> all = new ArrayList<>(PLACES);
        for (MountHuaPlan.Zone z : MountHuaPlan.ZONES) {
            all.add(z.id());
        }
        for (String place : all) {
            line.append(" ").append(Component.literal("[" + place + "]").withStyle(Style.EMPTY
                    .withColor(0x9FD8A0)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/murim mounthua tp " + place))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            Component.literal("/murim mounthua tp " + place)))));
        }
        context.getSource().sendSuccess(() -> line, false);
        return 1;
    }

    private static int[] zoneCenter(MountHuaSite site, String id) {
        for (MountHuaPlan.Zone z : MountHuaPlan.ZONES) {
            if (z.id().equals(id)) {
                int[] w = site.toWorld(z.u(), z.v());
                return new int[] {w[0], (int) Math.round(site.worldY(z.y())) + 1, w[1]};
            }
        }
        return null;
    }

    private static int teleport(CommandContext<CommandSourceStack> context, String place) {
        MountHuaSite site = site(context);
        if (site == null) {
            return 0;
        }
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            return 0;
        }
        double[] target = viewpoint(site, place);
        if (target == null) {
            context.getSource().sendFailure(Component.translatable("command.murim.mounthua.unknown", place));
            return 0;
        }
        ServerLevel level = context.getSource().getServer().overworld();
        resolveSurface(level, target);
        player.teleportTo(level, target[0], target[1], target[2], (float) target[3], (float) target[4]);
        context.getSource().sendSuccess(() -> Component.translatable("command.murim.mounthua.teleported", place,
                (int) target[0], (int) target[1], (int) target[2]), false);
        return 1;
    }

    /**
     * World viewpoint {x, y, z, yaw, pitch} for a place name. Panorama viewpoints float in the air
     * (use creative/spectator); zone and trail points stand on the ground.
     */
    public static double[] viewpoint(MountHuaSite site, String place) {
        int[] zone = zoneCenter(site, place);
        if (zone != null) {
            return new double[] {zone[0] + 0.5, zone[1], zone[2] + 0.5, yawTowards(site, 0, 1), 0};
        }
        return switch (place) {
            // Views from the ground (the client builds terrain around a flying spectator poorly).
            case "view" -> surface(site, -30, -480, 0.05, 1, -12);
            case "view_ne" -> surface(site, 330, -420, -0.8, 1, -10);
            case "view_east" -> ground(site, 175, 60, -1, 0.1);
            case "view_west" -> ground(site, -175, 85, 1, 0);
            case "summit" -> ground(site, 5, 125, 0, -1);
            case "north_peak" -> ground(site, -10, -335, 0, 1);
            case "golden_lock" -> ground(site, 22, -80, 0, 1);
            case "ridge" -> ground(site, 8, -262, -0.2, 1);
            case "gorge" -> ground(site, -108, -400, 0.15, 1);
            default -> null;
        };
    }

    /** On the real ground (vanilla terrain outside the massif): y is resolved on teleport. */
    private static double[] surface(MountHuaSite site, double u, double v, double lu, double lv, double pitch) {
        int[] w = site.toWorld(u, v);
        return new double[] {w[0] + 0.5, Double.NaN, w[1] + 0.5, yawTowards(site, lu, lv), pitch};
    }

    private static double[] ground(MountHuaSite site, double u, double v, double lu, double lv) {
        int[] w = site.toWorld(u, v);
        double y = site.worldY(site.shape().height(u + 0.5, v + 0.5)) + 2;
        return new double[] {w[0] + 0.5, y, w[1] + 0.5, yawTowards(site, lu, lv), 0};
    }

    /** Fills a NaN height with the ground height there (+2): loads the chunk if needed. */
    public static void resolveSurface(ServerLevel level, double[] target) {
        if (Double.isNaN(target[1])) {
            target[1] = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                    (int) Math.floor(target[0]), (int) Math.floor(target[2])) + 2;
        }
    }

    /** Minecraft yaw for a local look direction (yaw 0 = +z, 90 = −x). */
    public static float yawTowards(MountHuaSite site, double lu, double lv) {
        int[] a = site.toWorld(0, 0);
        int[] b = site.toWorld(lu * 100, lv * 100);
        double dx = b[0] - a[0];
        double dz = b[1] - a[1];
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    // ---------------------------------------------------------------------------------------------
    // Pre-generation (dev/test tool; a server operator may also use it before players arrive).

    private record Pregen(MinecraftServer server, ArrayDeque<ChunkPos> queue, int total) {
    }

    private static volatile Pregen pregen;

    private static int pregen(CommandContext<CommandSourceStack> context, int margin) {
        MountHuaSite site = site(context);
        if (site == null) {
            return 0;
        }
        ArrayDeque<ChunkPos> queue = new ArrayDeque<>();
        for (int cx = (site.minX() - margin) >> 4; cx <= (site.maxX() + margin) >> 4; cx++) {
            for (int cz = (site.minZ() - margin) >> 4; cz <= (site.maxZ() + margin) >> 4; cz++) {
                queue.add(new ChunkPos(cx, cz));
            }
        }
        pregen = new Pregen(context.getSource().getServer(), queue, queue.size());
        context.getSource().sendSuccess(() -> Component.translatable("command.murim.mounthua.pregen", queue.size()), true);
        return 1;
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        Pregen p = pregen;
        if (p == null || p.server() != event.getServer()) {
            return;
        }
        ServerLevel level = p.server().overworld();
        long end = System.nanoTime() + 25_000_000L;
        int before = p.queue().size();
        while (!p.queue().isEmpty() && System.nanoTime() < end) {
            ChunkPos pos = p.queue().poll();
            level.getChunk(pos.x, pos.z, ChunkStatus.FULL, true);
        }
        int left = p.queue().size();
        int step = Math.max(1, p.total() / 20);
        if (left == 0) {
            pregen = null;
            MurimMod.LOGGER.info("Mount Hua pregen done: {} chunks", p.total());
            p.server().getPlayerList().broadcastSystemMessage(
                    Component.translatable("command.murim.mounthua.pregen_done", p.total()), false);
        } else if ((p.total() - left) / step != (p.total() - before) / step) {
            MurimMod.LOGGER.info("Mount Hua pregen: {}/{}", p.total() - left, p.total());
        }
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        pregen = null;
    }
}
