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
    private static final List<String> PLACES = List.of("approach", "approach_ridge", "cliff", "pillars", "stairs", "stairs_high", "gorge_start", "sect_below", "sect_aerial", "courtyard", "view", "aerial", "aerial_ne", "aerial_sw", "view_ne", "view_east", "view_west",
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
                        .then(Commands.literal("probe")
                                .then(Commands.argument("place", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(names, b))
                                        .executes(c -> probe(c, StringArgumentType.getString(c, "place")))))
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

    /** Dev check: planned vs actual ground height around a place (the column surface heightmap). */
    private static int probe(CommandContext<CommandSourceStack> context, String place) {
        MountHuaSite site = site(context);
        if (site == null) {
            return 0;
        }
        double[] t = viewpoint(site, place);
        if (t == null) {
            return 0;
        }
        ServerLevel level = context.getSource().getServer().overworld();
        StringBuilder out = new StringBuilder(place + ":");
        for (int dz = -8; dz <= 8; dz += 4) {
            for (int dx = -8; dx <= 8; dx += 4) {
                int x = (int) Math.floor(t[0]) + dx;
                int z = (int) Math.floor(t[2]) + dz;
                level.getChunk(x >> 4, z >> 4);
                int ground = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                double plan = site.worldY(site.shape().height(site.localU(x + 0.5, z + 0.5), site.localV(x + 0.5, z + 0.5)));
                out.append(String.format(" [%d,%d g%d p%.0f %s]", x, z, ground, plan,
                        level.getBlockState(new net.minecraft.core.BlockPos(x, ground, z)).getBlock().getName().getString()));
            }
        }
        int cx = (int) Math.floor(t[0]);
        int cz = (int) Math.floor(t[2]);
        out.append(String.format(" | blend %.2f column:", site.shape().blend(site.localU(cx + 0.5, cz + 0.5),
                site.localV(cx + 0.5, cz + 0.5))));
        for (int y = 60; y < 120; y++) {
            String n = level.getBlockState(new net.minecraft.core.BlockPos(cx, y, cz)).getBlock().getDescriptionId();
            out.append(' ').append(y).append('=').append(n.substring(n.lastIndexOf('.') + 1));
        }
        MurimMod.LOGGER.info("Mount Hua probe {}", out);
        context.getSource().sendSuccess(() -> Component.literal(out.toString()), false);
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
            // Views from the ground (the client builds terrain around a flying spectator poorly);
            // height = top of the ground or canopy there + offset, resolved on teleport.
            case "view" -> surface(site, -30, -480, 0.05, 1, -12, 14);
            // The approach: up the main valley, and from the ridge of the north-west satellite.
            case "approach" -> surface(site, -135, -730, 0.04, 1, -6, 4);
            case "approach_ridge" -> surface(site, -470, -215, 1, 0.45, 2, 16);
            case "cliff" -> surface(site, -45, -300, 1, -0.3, -25, 3);
            case "pillars" -> surface(site, 300, 520, -0.62, -0.78, 4, 50);
            case "stairs" -> onTrail(site, 0.62, -6);
            case "stairs_high" -> onTrail(site, 0.55, -12);
            case "gorge_start" -> onTrail(site, 0.012, -14);
            case "sect_below" -> surface(site, 27, -70, -0.25, 1, 12, 2);
            case "sect_aerial" -> surface(site, 30, -210, -0.1, 1, -22, 120);
            case "courtyard" -> surface(site, -20, -22, 0.15, 1, 2, 2);
            case "aerial" -> surface(site, -30, -500, 0.05, 1, 4, 70);
            case "aerial_ne" -> surface(site, 330, -300, -0.8, 1, 2, 80);
            case "aerial_sw" -> surface(site, -300, 330, 0.7, -1, 2, 80);
            case "view_ne" -> surface(site, 330, -420, -0.8, 1, -8, 24);
            case "view_east" -> surface(site, 175, 60, -1, 0.1, 6, 4);
            case "view_west" -> surface(site, -175, 85, 1, 0, 6, 4);
            case "summit" -> surface(site, 5, 125, 0, -1, 14, 4);
            case "north_peak" -> surface(site, -10, -335, 0, 1, 2, 4);
            case "golden_lock" -> surface(site, 22, -80, 0, 1, 0, 2);
            case "ridge" -> surface(site, 8, -262, -0.2, 1, -4, 2);
            case "gorge" -> surface(site, -108, -400, 0.15, 1, -10, 2);
            default -> null;
        };
    }

    /** Standing on the trail at a fraction of its length, looking up the stair. */
    private static double[] onTrail(MountHuaSite site, double fraction, double pitch) {
        MountHuaShape shape = site.shape();
        int i = (int) (shape.trailLength() * fraction);
        double[] a = shape.trailPoint(i);
        double[] b = shape.trailPoint(i + 12);
        int[] w = site.toWorld(a[0], a[1]);
        double y = site.worldY(shape.trailY(i)) + 2;
        return new double[] {w[0] + 0.5, y, w[1] + 0.5, yawTowards(site, b[0] - a[0], b[1] - a[1]), pitch};
    }

    /** On the ground or canopy (+ offset); y is resolved on teleport from the loaded chunk. */
    private static double[] surface(MountHuaSite site, double u, double v, double lu, double lv, double pitch,
            double offset) {
        int[] w = site.toWorld(u, v);
        return new double[] {w[0] + 0.5, Double.NaN, w[1] + 0.5, yawTowards(site, lu, lv), pitch, offset};
    }

    private static double[] ground(MountHuaSite site, double u, double v, double lu, double lv) {
        int[] w = site.toWorld(u, v);
        double y = site.worldY(site.shape().height(u + 0.5, v + 0.5)) + 2;
        return new double[] {w[0] + 0.5, y, w[1] + 0.5, yawTowards(site, lu, lv), 0};
    }

    /** Fills a NaN height with the ground/canopy height there plus the offset; loads the chunk. */
    public static void resolveSurface(ServerLevel level, double[] target) {
        if (Double.isNaN(target[1])) {
            int x = (int) Math.floor(target[0]);
            int z = (int) Math.floor(target[2]);
            level.getChunk(x >> 4, z >> 4);
            double offset = target.length > 5 ? target[5] : 2;
            target[1] = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z) + offset;
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
