package io.github.verycooltimo.murim.world.hua;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.sect.SectLayoutFile;
import net.minecraft.core.BlockPos;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * {@code /murim dev hua} — where the mountain is (clickable teleports to the sect zones),
 * {@code /murim dev hua tp <place>} — teleport for testing, {@code /murim dev hua pregen
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
    private static final List<String> PLACES = List.of("approach", "approach_ridge", "cliff", "pillars", "stairs", "stairs_high", "gorge_start", "sect_below", "sect_aerial", "courtyard", "courtyard_back", "saddle_w", "saddle_e", "shelf_front", "shelf_aerial", "under_sect", "view", "aerial", "aerial_ne", "aerial_sw", "view_ne", "view_east", "view_west",
            "summit", "north_peak", "golden_lock", "ridge", "gorge", "climb_up", "climb_mid", "climb_down", "climb_overview");

    private MountHuaCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        List<String> names = new ArrayList<>(PLACES);
        for (MountHuaPlan.Zone z : MountHuaPlan.ZONES) {
            names.add(z.id());
        }
        for (MountHuaPlan.Ledge l : MountHuaPlan.CLIMB) {
            if (l.onRoute()) {
                names.add(l.id());
            }
        }
        return Commands.literal("hua")
                        .executes(MountHuaCommand::info)
                        .then(Commands.literal("tp")
                                .then(Commands.argument("place", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(names, b))
                                        .executes(c -> teleport(c, StringArgumentType.getString(c, "place")))))
                        .then(Commands.literal("probe")
                                .then(Commands.argument("place", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(names, b))
                                        .executes(c -> probe(c, StringArgumentType.getString(c, "place")))))
                        .then(Commands.literal("lingzhi").executes(c -> lingzhi(c, "near"))
                                .then(Commands.literal("where").executes(c -> lingzhi(c, "where")))
                                .then(Commands.literal("check").executes(c -> lingzhi(c, "check")))
                                .then(Commands.literal("near").executes(c -> lingzhi(c, "near")))
                                .then(Commands.literal("far").executes(c -> lingzhi(c, "far")))
                                .then(Commands.literal("path").executes(c -> teleport(c, "pav_east"))))
                        .then(Commands.literal("mend").executes(MountHuaCommand::mendTrail))
                        .then(Commands.literal("pregen").executes(c -> pregen(c, 0))
                                .then(Commands.literal("cells").executes(MountHuaCommand::pregenCells))
                                .then(Commands.literal("sample")
                                        .then(Commands.argument("patches", IntegerArgumentType.integer(4, 400))
                                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 8))
                                                        .executes(c -> pregenSample(c, IntegerArgumentType.getInteger(c, "patches"),
                                                                IntegerArgumentType.getInteger(c, "radius"))))))
                                .then(Commands.literal("trail").executes(MountHuaCommand::pregenTrail))
                                .then(Commands.argument("margin", IntegerArgumentType.integer(0, 1024))
                                        .executes(c -> pregen(c, IntegerArgumentType.getInteger(c, "margin")))));
    }

    private static int lingzhi(CommandContext<CommandSourceStack> context, String view)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        MountHuaSite site = site(context);
        if (site == null) return 0;
        ServerLevel level = context.getSource().getServer().overworld();
        BlockPos column = HuaHerbs.blackLingzhiSpot(site);
        ChunkPos owner = new ChunkPos(column);
        level.getChunk(owner.x, owner.z);
        // Generate the neighbours through the level: includes the real feature order and later cross-border stamps.
        for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
            level.getChunk(owner.x + dx, owner.z + dz);
        }
        BlockPos at = HuaHerbs.blackLingzhiSpot(level, site);
        if (view.equals("check")) {
            int count = 0;
            BlockPos.MutableBlockPos sample = new BlockPos.MutableBlockPos();
            for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
                var chunk = level.getChunk(owner.x + dx, owner.z + dz);
                int minX = (owner.x + dx) * 16, minZ = (owner.z + dz) * 16;
                for (int z = minZ; z < minZ + 16; z++) for (int x = minX; x < minX + 16; x++) {
                    for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                        sample.set(x, y, z);
                        if (chunk.getBlockState(sample).is(
                                io.github.verycooltimo.murim.registry.ModBlocks.BLACK_LINGZHI_PLANT.get())) count++;
                    }
                }
            }
            boolean valid = count == 1 && level.getBlockState(at).is(
                    io.github.verycooltimo.murim.registry.ModBlocks.BLACK_LINGZHI_PLANT.get())
                    && level.getBlockState(at).canSurvive(level, at)
                    && level.getBlockState(at.above()).is(net.minecraft.world.level.block.Blocks.STONE);
            String result = "Black lingzhi generation check " + (valid ? "PASS" : "FAIL")
                    + ": " + at + "; matching plants in generated 5x5=" + count;
            if (!valid) {
                context.getSource().sendFailure(Component.literal(result));
                return 0;
            }
            context.getSource().sendSuccess(() -> Component.literal(result), false);
            return 1;
        }
        if (view.equals("where")) {
            context.getSource().sendSuccess(() -> Component.literal("Black lingzhi: " + at
                    + "; block=" + level.getBlockState(at) + "; real top="
                    + HuaHerbs.realTop(level, at.getX(), at.getZ())), false);
            return 1;
        }
        var direction = HuaHerbs.dropDirection(site);
        int start = view.equals("far") ? 10 : 1;
        int end = view.equals("far") ? 14 : 32;
        for (int distance = start; distance <= end; distance++) {
            for (int up = view.equals("far") ? 3 : 0; up <= (view.equals("far") ? 12 : 3); up++) {
                BlockPos feet = at.relative(direction, distance).above(up);
                if (!level.getBlockState(feet).isAir() || !level.getBlockState(feet.above()).isAir()) continue;
                ServerPlayer player = context.getSource().getPlayerOrException();
                double x = feet.getX() + 0.5, y = feet.getY(), z = feet.getZ() + 0.5;
                double dx = at.getX() + 0.5 - x, dz = at.getZ() + 0.5 - z;
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) -Math.toDegrees(Math.atan2(at.getY() + 0.25 - (y + 1.62), Math.hypot(dx, dz)));
                player.teleportTo(level, x, y, z, yaw, pitch);
                return 1;
            }
        }
        context.getSource().sendFailure(Component.literal("No free filming position on the outward side; use lingzhi where."));
        return 0;
    }

    /**
     * Repairs the trail of a world made before the chunk-border fix (drops of up to 6 blocks): runs the trail mend over every chunk the
     * trail crosses. It reads the bake and the plan, not the world, so it can run again and leaves other blocks alone.
     */
    private static int mendTrail(CommandContext<CommandSourceStack> context) {
        MountHuaSite site = site(context);
        if (site == null) {
            return 0;
        }
        ServerLevel level = context.getSource().getServer().overworld();
        java.util.Set<net.minecraft.world.level.ChunkPos> chunks = new java.util.TreeSet<>(java.util.Comparator
                .<net.minecraft.world.level.ChunkPos>comparingInt(c -> c.x).thenComparingInt(c -> c.z));
        for (int i = 0; i < site.shape().trailLength(); i++) {
            double[] p = site.shape().trailPoint(i);
            int[] w = site.toWorld(p[0], p[1]);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    chunks.add(new net.minecraft.world.level.ChunkPos((w[0] >> 4) + dx, (w[1] >> 4) + dz));
                }
            }
        }
        int mended = 0;
        for (net.minecraft.world.level.ChunkPos c : chunks) {
            level.getChunk(c.x, c.z);
            mended += HuaTrailMend.apply(level, c, site);
        }
        int total = mended;
        context.getSource().sendSuccess(() -> Component.literal("Trail mended: " + total + " columns in " + chunks.size() + " chunks"), true);
        return 1;
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
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/murim dev hua tp " + place))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            Component.literal("/murim dev hua tp " + place)))));
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
                double plan = site.worldY(site.shape().height(site.centerU(x, z), site.centerV(x, z)));
                out.append(String.format(" [%d,%d g%d p%.0f %s]", x, z, ground, plan,
                        level.getBlockState(new net.minecraft.core.BlockPos(x, ground, z)).getBlock().getName().getString()));
            }
        }
        int cx = (int) Math.floor(t[0]);
        int cz = (int) Math.floor(t[2]);
        out.append(String.format(" | blend %.2f column:", site.shape().blend(site.centerU(cx, cz),
                site.centerV(cx, cz))));
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
        placeInsideFreeSpace(level, site, place, target);
        player.teleportTo(level, target[0], target[1], target[2], (float) target[3], (float) target[4]);
        context.getSource().sendSuccess(() -> Component.translatable("command.murim.mounthua.teleported", place,
                (int) target[0], (int) target[1], (int) target[2]), false);
        return 1;
    }

    /**
     * A zone centre of the plan is a point on the plan, not a place a body fits: in the baked sect the main hall stands on a raised
     * floor and the plan point lies in its wall (23.10: {@code hua tp main_hall} suffocated the player in 40 s). A building of the
     * built sect is entered on the floor of its largest ground-floor room; anything else that is solid at the target moves to the
     * nearest free cell. A viewpoint in the air (panorama, creative) is left alone.
     */
    static void placeInsideFreeSpace(ServerLevel level, MountHuaSite site, String place, double[] target) {
        BlockPos at = BlockPos.containing(target[0], target[1], target[2]);
        level.getChunk(at.getX() >> 4, at.getZ() >> 4);
        if (level.noCollision(body(at))) {
            return;     // free space (or a viewpoint in the air): nothing to move
        }
        if (site.canonical()) {
            SectLayoutFile file = SectLayoutFile.anchored(site);
            SectLayoutFile.Room best = null;
            if (file != null) {
                for (SectLayoutFile.Room r : file.rooms()) {
                    if (r.building().equals(place) && r.floor() == 0 && !"corridor".equals(r.function())
                            && (best == null || r.walkable() > best.walkable())) {
                        best = r;
                    }
                }
            }
            if (best != null) {
                BlockPos centre = new BlockPos((best.bounds().x0() + best.bounds().x1()) / 2, best.y(), (best.bounds().z0() + best.bounds().z1()) / 2);
                level.getChunk(centre.getX() >> 4, centre.getZ() >> 4);
                BlockPos stand = clearStand(level, centre, 12, 1, 1);
                if (stand != null) {
                    setTarget(target, stand);
                    return;
                }
            }
        }
        BlockPos stand = clearStand(level, at, 10, 8, 4);
        if (stand != null) {
            setTarget(target, stand);
        }
    }

    private static void setTarget(double[] target, BlockPos stand) {
        target[0] = stand.getX() + 0.5;
        target[1] = stand.getY();
        target[2] = stand.getZ() + 0.5;
    }

    /** The body of a player fits at the feet cell, on a floor. */
    static boolean fits(ServerLevel level, BlockPos feet) {
        return level.noCollision(body(feet)) && level.getFluidState(feet).isEmpty()
                && !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty();
    }

    private static net.minecraft.world.phys.AABB body(BlockPos feet) {
        return new net.minecraft.world.phys.AABB(feet.getX() + 0.5 - 0.31, feet.getY() + 0.001, feet.getZ() + 0.5 - 0.31,
                feet.getX() + 0.5 + 0.31, feet.getY() + 1.8, feet.getZ() + 0.5 + 0.31);
    }

    /**
     * The free standing cell nearest to {@code around} (horizontal distance first) within {@code radius} blocks, {@code up} cells
     * above and {@code down} cells below it; null when there is none.
     */
    public static BlockPos clearStand(ServerLevel level, BlockPos around, int radius, int up, int down) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double flat = dx * dx + dz * dz;
                if (flat > radius * radius || flat >= bestD) {
                    continue;
                }
                for (int dy = -down; dy <= up; dy++) {
                    BlockPos p = around.offset(dx, dy, dz);
                    double d = flat + dy * dy * 0.25;
                    if (d < bestD && level.isLoaded(p) && fits(level, p)) {
                        best = p;
                        bestD = d;
                    }
                }
            }
        }
        return best;
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
        for (MountHuaSites.ClimbSite c : MountHuaSites.climbSites(site)) {
            if (c.id().equals(place)) {
                // On the ledge, facing up the face.
                return new double[] {c.feet().getX() + 0.5, c.feet().getY(), c.feet().getZ() + 0.5,
                        yawTowards(site, 0, 1), -20};
            }
        }
        return switch (place) {
            // The training climb: from the ancestors' terrace looking up the face, from a ledge in
            // the middle row along it, from the top ledge down onto the sect, and the whole face.
            case "climb_up" -> surface(site, 6, 86, 0, 1, -38, 2);
            case "climb_mid" -> surface(site, -26, 106.5, 1, 0.35, -8, 2);
            case "climb_down" -> surface(site, -23, 113, 0.35, -1, 38, 2);
            case "climb_overview" -> surface(site, 6, 40, 0, 1, -18, 40);
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
            case "courtyard" -> surface(site, -24, -20, 0.1, 1, 22, 2);
            case "courtyard_back" -> surface(site, -10, 60, 0.05, 1, 30, 2);
            case "saddle_w" -> surface(site, -70, 44, 1, -0.4, 6, 2);
            case "saddle_e" -> surface(site, 66, 16, -1, 0.1, 6, 2);
            case "shelf_front" -> surface(site, -56, -34, 0.8, 0.7, -14, 2);
            case "under_sect" -> surface(site, -92, -24, 1, 0.7, -12, 3);
            case "shelf_aerial" -> surface(site, -70, -60, 0.6, 0.8, 30, 70);
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

    /**
     * Dev/test tool: square patches of (2 radius + 1)^2 chunks spread over the zones of the mountain, in four kinds in equal numbers: the massif,
     * the foothills inside, the belt's outer band (weight 0..0.5, vanilla biome until 26.10) and its margin (weight 0, Hua since 26.10). A few
     * minutes of work instead of a quarter of an hour; enough to count what hangs in the air and which biome stands where. The centres are
     * logged ("Mount Hua pregen sample: ...") so that a frame can be taken at each.
     */
    private static int pregenSample(CommandContext<CommandSourceStack> context, int patches, int radius) {
        MountHuaSite site = site(context);
        if (site == null) {
            return 0;
        }
        List<List<ChunkPos>> kinds = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        String[] names = {"massif", "foothills", "band", "margin"};
        for (int cx = site.minX() >> 4; cx <= site.maxX() >> 4; cx++) {
            for (int cz = site.minZ() >> 4; cz <= site.maxZ() >> 4; cz++) {
                double u = site.centerU(cx * 16 + 8, cz * 16 + 8);
                double v = site.centerV(cx * 16 + 8, cz * 16 + 8);
                double w = site.shape().blend(u, v);
                if (w >= 1) {
                    kinds.get(site.shape().massif(u, v) > HuaBiomes.MASSIF ? 0 : 1).add(new ChunkPos(cx, cz));
                } else if (w > 0 && w < 0.5) {
                    kinds.get(2).add(new ChunkPos(cx, cz));
                } else if (w <= 0 && HuaBiomes.classify(site.shape(), u, v) != HuaBiomes.Zone.NONE) {
                    kinds.get(3).add(new ChunkPos(cx, cz));
                }
            }
        }
        LinkedHashSet<ChunkPos> chunks = new LinkedHashSet<>();
        int per = Math.max(1, patches / 4);
        for (int k = 0; k < 4; k++) {
            List<ChunkPos> list = kinds.get(k);
            for (int i = 0; i < per && !list.isEmpty(); i++) {
                ChunkPos c = list.get((int) ((i + 0.5) * list.size() / per));
                MurimMod.LOGGER.info("Mount Hua pregen sample: {} centre x={} z={}", names[k], c.getMiddleBlockX(), c.getMiddleBlockZ());
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        chunks.add(new ChunkPos(c.x + dx, c.z + dz));
                    }
                }
            }
        }
        ArrayDeque<ChunkPos> queue = new ArrayDeque<>(chunks);
        pregen = new Pregen(context.getSource().getServer(), queue, queue.size());
        context.getSource().sendSuccess(() -> Component.translatable("command.murim.mounthua.pregen", queue.size()), true);
        return 1;
    }

    /**
     * Dev/test tool: only the chunks that meet a cell of the baked sect (docs/adr/0004), about 8 % of the massif and a minute of
     * work instead of a quarter of an hour; enough to compare the stamped buildings with the world they were cut from.
     */
    private static int pregenCells(CommandContext<CommandSourceStack> context) {
        return pregenCellsAnd(context, false);
    }

    /**
     * Dev/test tool: what {@code pregen cells} makes plus a strip of two chunks to each side of the whole trail from the foot to the sect:
     * enough to walk and film the climb from the foot to the sect (the new-player walk audit) in minutes instead of a quarter of an hour.
     */
    private static int pregenTrail(CommandContext<CommandSourceStack> context) {
        return pregenCellsAnd(context, true);
    }

    private static int pregenCellsAnd(CommandContext<CommandSourceStack> context, boolean trailStrip) {
        MountHuaSite site = site(context);
        if (site == null) {
            return 0;
        }
        HuaBaked.Index index = HuaBaked.index(context.getSource().getServer());
        if (index == null) {
            context.getSource().sendFailure(Component.translatable("command.murim.mounthua.none"));
            return 0;
        }
        LinkedHashSet<ChunkPos> chunks = new LinkedHashSet<>();
        // the sect cells and the sparse pieces beside them (the author's climb, the foot lake, the dorm staircase: HuaBakedExtra)
        List<HuaBaked.Index> indexes = new ArrayList<>(List.of(index));
        HuaBaked.Index extra = HuaBakedExtra.index(context.getSource().getServer());
        if (extra != null) {
            indexes.add(extra);
        }
        for (HuaBaked.Index ix : indexes) {
            for (Map.Entry<HuaBaked.Group, HuaBaked.Cell> e : HuaBaked.cells(ix)) {
                BoundingBox box = HuaBaked.cellBox(ix, e.getKey(), e.getValue(), site);
                for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
                    for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                        chunks.add(new ChunkPos(cx, cz));
                    }
                }
            }
        }
        if (trailStrip) {
            List<MountHuaPlan.TrailPoint> trail = MountHuaPlan.TRAIL;
            for (int i = 0; i + 1 < trail.size(); i++) {
                MountHuaPlan.TrailPoint a = trail.get(i);
                MountHuaPlan.TrailPoint b = trail.get(i + 1);
                int steps = (int) Math.ceil(Math.hypot(b.u() - a.u(), b.v() - a.v()) / 8.0D);
                for (int k = 0; k <= steps; k++) {
                    int[] w = site.toWorld(a.u() + (b.u() - a.u()) * k / (double) steps, a.v() + (b.v() - a.v()) * k / (double) steps);
                    for (int cx = (w[0] >> 4) - 2; cx <= (w[0] >> 4) + 2; cx++) {
                        for (int cz = (w[1] >> 4) - 2; cz <= (w[1] >> 4) + 2; cz++) {
                            chunks.add(new ChunkPos(cx, cz));
                        }
                    }
                }
            }
        }
        ArrayDeque<ChunkPos> queue = new ArrayDeque<>(chunks);
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
