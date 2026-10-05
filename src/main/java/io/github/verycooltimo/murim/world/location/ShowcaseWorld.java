package io.github.verycooltimo.murim.world.location;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.library.LibraryPiece;
import io.github.verycooltimo.murim.library.ModLibrary;
import io.github.verycooltimo.murim.library.RuinedLibraryStructure;
import io.github.verycooltimo.murim.world.camp.BanditCamp;
import io.github.verycooltimo.murim.world.camp.BanditCampStructure;
import io.github.verycooltimo.murim.world.fortress.Fortress;
import io.github.verycooltimo.murim.world.fortress.FortressStructure;
import io.github.verycooltimo.murim.world.hua.MountHuaOverlay;
import io.github.verycooltimo.murim.world.hua.MountHuaPlan;
import io.github.verycooltimo.murim.world.hua.MountHuaShape;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The showcase world (author 05.10: «мир, где есть гора и все новые локации… я бы на месте подправил, а ты бы
 * забрал»; docs/design/28-location-capture.md §1): in a fresh world, Mount Hua is where the world put it, and the
 * spawn moves to the foot of its trail; a bandit camp, a ruined archive and a boss fortress are built on flat
 * spots of the approach valley 80–260 blocks from the spawn. Each gets a sign; a row of signs at the spawn lists
 * all of them; the chat lists them with teleports at every login. Creative, peaceful, eternal noon — a workshop.
 *
 * <p>Locations are real structure starts ({@link ForcedStructure}), built from the same pieces as world
 * generation — and from the author's capture if one exists, so the next edit starts from the last one.
 *
 * <p>Start: {@code /murim showcase} in a new world, or a dedicated server with {@code MURIM_SHOWCASE=1}
 * ({@code tools/showcase/make_showcase.sh}) — it builds, saves and stops by itself. Chunks are generated over
 * ticks (40 ms per tick), never all at once: the mountain's chunks are heavy.
 *
 * <p>API: reference/minecraft-src/net/minecraft/server/level/ServerLevel.java#setDefaultSpawnPos,
 * reference/minecraft-src/net/minecraft/world/level/storage/WorldData.java#setGameType,
 * reference/minecraft-src/net/minecraft/server/MinecraftServer.java (#setDifficulty, #saveEverything, #halt),
 * reference/minecraft-src/net/minecraft/world/level/block/entity/SignBlockEntity.java#setText.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class ShowcaseWorld {

    /** Environment flag of the dedicated-server run: build, save, stop. */
    static final boolean AUTOSTART = System.getenv("MURIM_SHOWCASE") != null;
    static final long BUDGET_NANOS = 40_000_000L;
    /** Dev: create the world, save and stop at once (a plain world for the round-trip stand). */
    static final boolean HALT_ON_START = System.getenv("MURIM_HALT_ON_START") != null;

    /** Footprint radius of each location (blocks) and how flat its spot must be (world blocks). */
    private record Spec(String id, int radius, int relief) {
    }

    private static final List<Spec> SPECS = List.of(new Spec(ModLocations.FORTRESS, 38, 10),
            new Spec(ModLocations.CAMP, 26, 6), new Spec(ModLocations.ARCHIVE, 22, 8));

    private record Job(MinecraftServer server, Map<String, BlockPos> spots, BlockPos spawn, float spawnYaw,
            ArrayDeque<ChunkPos> queue, int total, CommandSourceStack source) {
    }

    private static volatile Job job;
    private static volatile boolean autostartDone;

    private ShowcaseWorld() {
    }

    // ------------------------------------------------------------------ commands

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("murim")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("showcase")
                        .executes(ShowcaseWorld::command)
                        .then(Commands.literal("tp")
                                .then(Commands.argument("place", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("spawn", "hua", "sect",
                                                ModLocations.CAMP, ModLocations.ARCHIVE, ModLocations.FORTRESS), b))
                                        .executes(c -> teleport(c, StringArgumentType.getString(c, "place")))))));
    }

    private static int command(CommandContext<CommandSourceStack> c) {
        MinecraftServer server = c.getSource().getServer();
        Data data = data(server);
        if (!data.places.isEmpty()) {
            for (Component line : listing(data)) {
                c.getSource().sendSuccess(() -> line, false);
            }
            return 1;
        }
        if (job != null) {
            c.getSource().sendFailure(Component.translatable("command.murim.showcase.busy"));
            return 0;
        }
        return start(server, c.getSource()) ? 1 : 0;
    }

    private static int teleport(CommandContext<CommandSourceStack> c, String place) {
        ServerPlayer player = c.getSource().getPlayer();
        Data data = data(c.getSource().getServer());
        Data.Place p = data.places.get(place);
        if (player == null || p == null) {
            c.getSource().sendFailure(Component.translatable("command.murim.showcase.unknown", place));
            return 0;
        }
        ServerLevel level = c.getSource().getServer().overworld();
        level.getChunk(p.pos().getX() >> 4, p.pos().getZ() >> 4);
        int y = Math.max(p.pos().getY(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.pos().getX(), p.pos().getZ()));
        player.teleportTo(level, p.pos().getX() + 0.5, y, p.pos().getZ() + 0.5, p.yaw(), 15.0F);
        return 1;
    }

    // ------------------------------------------------------------------ planning

    /** Plans the showcase and queues its chunks; false if Mount Hua has no site yet. */
    static boolean start(MinecraftServer server, CommandSourceStack source) {
        MountHuaSite site = MountHuaSites.get(server);
        if (site == null) {
            if (source != null) {
                source.sendFailure(Component.translatable("command.murim.showcase.no_hua"));
            }
            return false;
        }
        Map<String, BlockPos> spots = plan(site);
        int[] sp = site.toWorld(-106, -446);
        int spawnY = (int) Math.round(site.worldY(site.shape().height(-106, -446))) + 1;
        BlockPos spawn = new BlockPos(sp[0], spawnY, sp[1]);
        // Face the gate: local south (+v).
        int[] dir = site.rotateDir(0, 1);
        float yaw = (float) (Mth.atan2(dir[1], dir[0]) * Mth.RAD_TO_DEG) - 90.0F;
        ArrayDeque<ChunkPos> queue = new ArrayDeque<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        addArea(queue, seen, spawn, 48);
        for (Map.Entry<String, BlockPos> e : spots.entrySet()) {
            int r = SPECS.stream().filter(s -> s.id().equals(e.getKey())).findFirst().map(Spec::radius).orElse(30);
            addArea(queue, seen, e.getValue(), r + 24);
        }
        var sect = MountHuaOverlay.defaultBox(site);
        for (int cx = sect.minX() >> 4; cx <= sect.maxX() >> 4; cx++) {
            for (int cz = sect.minZ() >> 4; cz <= sect.maxZ() >> 4; cz++) {
                if (seen.add(ChunkPos.asLong(cx, cz))) {
                    queue.add(new ChunkPos(cx, cz));
                }
            }
        }
        job = new Job(server, spots, spawn, yaw, queue, queue.size(), source);
        MurimMod.LOGGER.info("Showcase: spawn {}, spots {}, {} chunks to generate", spawn.toShortString(), spots, queue.size());
        if (source != null) {
            source.sendSuccess(() -> Component.translatable("command.murim.showcase.started", queue.size()), true);
        }
        return true;
    }

    private static void addArea(ArrayDeque<ChunkPos> queue, java.util.Set<Long> seen, BlockPos c, int r) {
        for (int cx = (c.getX() - r) >> 4; cx <= (c.getX() + r) >> 4; cx++) {
            for (int cz = (c.getZ() - r) >> 4; cz <= (c.getZ() + r) >> 4; cz++) {
                if (seen.add(ChunkPos.asLong(cx, cz))) {
                    queue.add(new ChunkPos(cx, cz));
                }
            }
        }
    }

    /**
     * Flat spots of the approach valley for the three locations, from the mountain's own height function (the
     * terrain there is the mountain's, the vanilla generator does not know it): away from the trail and the
     * stream, inside the belt, 80–260 blocks from the spawn, apart from each other. The fortress (largest) first.
     */
    static Map<String, BlockPos> plan(MountHuaSite site) {
        MountHuaShape shape = site.shape();
        double spawnU = -106;
        double spawnV = -446;
        double scale = (MountHuaSite.SUMMIT_Y - site.baseY()) / MountHuaPlan.SUMMIT;
        Map<String, BlockPos> out = new LinkedHashMap<>();
        List<double[]> taken = new ArrayList<>();
        taken.add(new double[] {spawnU, spawnV, 20});
        for (int pass = 0; pass < 2 && out.size() < SPECS.size(); pass++) {
            for (Spec spec : SPECS) {
                if (out.containsKey(spec.id())) {
                    continue;
                }
                double best = Double.MAX_VALUE;
                double[] at = null;
                for (double u = -420; u <= 240; u += 6) {
                    for (double v = -820; v <= -420; v += 6) {
                        double dist = Math.hypot(u - spawnU, v - spawnV);
                        if (dist < 80 || dist > 260) {
                            continue;
                        }
                        boolean clear = true;
                        for (double[] t : taken) {
                            if (Math.hypot(u - t[0], v - t[1]) < spec.radius() + t[2] + 14) {
                                clear = false;
                                break;
                            }
                        }
                        if (!clear || shape.streamDistance(u, v) < spec.radius() + 6) {
                            continue;
                        }
                        double relief = relief(shape, u, v, spec.radius(), scale);
                        if (relief < 0 || relief > spec.relief() * (pass + 1)) {
                            continue;
                        }
                        double score = relief * 4 + dist / 25;
                        if (score < best) {
                            best = score;
                            at = new double[] {u, v};
                        }
                    }
                }
                if (at != null) {
                    int[] w = site.toWorld(at[0], at[1]);
                    int y = (int) Math.round(site.worldY(shape.height(at[0], at[1]))) + 1;
                    out.put(spec.id(), new BlockPos(w[0], y, w[1]));
                    taken.add(new double[] {at[0], at[1], spec.radius()});
                }
            }
        }
        return out;
    }

    /** Height spread (world blocks) over a footprint; −1 if it touches the trail or leaves the belt. */
    private static double relief(MountHuaShape shape, double u, double v, int r, double scale) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int i = -2; i <= 2; i++) {
            for (int j = -2; j <= 2; j++) {
                double pu = u + i * r / 2.0;
                double pv = v + j * r / 2.0;
                if (shape.blend(pu, pv) < 0.99) {
                    return -1;
                }
                double[] t = shape.trailAt(pu, pv);
                if (t != null && t[0] < 8) {
                    return -1;
                }
                double h = shape.height(pu, pv) * scale;
                min = Math.min(min, h);
                max = Math.max(max, h);
            }
        }
        return max - min;
    }

    // ------------------------------------------------------------------ ticking

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (HALT_ON_START && !autostartDone && MountHuaSites.get(server) != null) {
            autostartDone = true;
            server.saveEverything(false, true, true);
            MurimMod.LOGGER.info("Showcase: plain world created, stopping");
            server.halt(false);
            return;
        }
        if (AUTOSTART && !autostartDone && job == null && MountHuaSites.get(server) != null) {
            autostartDone = true;
            if (data(server).places.isEmpty()) {
                start(server, null);
            } else {
                MurimMod.LOGGER.info("Showcase: already built, stopping");
                server.halt(false);
            }
        }
        Job j = job;
        if (j == null || j.server() != server) {
            return;
        }
        ServerLevel level = server.overworld();
        long t0 = System.nanoTime();
        while (!j.queue().isEmpty() && System.nanoTime() - t0 < BUDGET_NANOS) {
            ChunkPos p = j.queue().poll();
            level.getChunk(p.x, p.z, ChunkStatus.FULL, true);
        }
        int left = j.queue().size();
        if (left > 0) {
            if (server.getTickCount() % 100 == 0) {
                MurimMod.LOGGER.info("Showcase: {}/{} chunks", j.total() - left, j.total());
                if (j.source() != null) {
                    j.source().sendSuccess(() -> Component.translatable("command.murim.showcase.progress", j.total() - left, j.total()), false);
                }
            }
            return;
        }
        job = null;
        try {
            build(level, j);
        } catch (RuntimeException e) {
            MurimMod.LOGGER.error("Showcase build failed", e);
        }
        if (AUTOSTART) {
            server.saveEverything(false, true, true);
            MurimMod.LOGGER.info("Showcase: saved, stopping");
            server.halt(false);
        }
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        Job j = job;
        if (j != null && j.server() == event.getServer()) {
            job = null;
        }
    }

    // ------------------------------------------------------------------ building

    private static void build(ServerLevel level, Job j) {
        MinecraftServer server = level.getServer();
        MountHuaSite site = MountHuaSites.get(server);
        Ground ground = Ground.live(level);
        Data data = data(server);
        long seed = level.getSeed() ^ 0x5C0CA5EL;
        BlockPos spawn = surface(level, j.spawn());
        Map<String, BlockPos> signs = new LinkedHashMap<>();
        for (Map.Entry<String, BlockPos> e : j.spots().entrySet()) {
            BlockPos at = e.getValue();
            String id = e.getKey();
            BlockPos entrance;
            switch (id) {
                case ModLocations.CAMP -> {
                    List<StructurePiece> pieces = BanditCampStructure.pieces(seed, at.getX(), at.getZ(), ground,
                            LocationTemplates.get(server, ModLocations.CAMP), Rotation.NONE);
                    ForcedStructure.place(level, BanditCamp.KEY, pieces);
                    entrance = at.offset(0, 0, -30);
                }
                case ModLocations.FORTRESS -> {
                    int[] h = new int[9];
                    int k = 0;
                    for (int dx = -14; dx <= 14; dx += 14) {
                        for (int dz = -14; dz <= 14; dz += 14) {
                            h[k++] = ground.floor(at.getX() + dx, at.getZ() + dz) + 1;
                        }
                    }
                    Arrays.sort(h);
                    List<StructurePiece> pieces = FortressStructure.pieces(seed + 1, at.getX(), at.getZ(), h[4] + 1, h[0], ground,
                            LocationTemplates.get(server, ModLocations.FORTRESS), Rotation.NONE);
                    ForcedStructure.place(level, Fortress.KEY, pieces);
                    entrance = at.offset(0, 0, -40);
                }
                default -> {
                    CapturedLocation tpl = LocationTemplates.get(server, ModLocations.ARCHIVE);
                    StructurePiece piece = tpl != null
                            ? RuinedLibraryStructure.templatePiece(tpl, at.getX(), at.getZ(), Rotation.NONE, ground)
                            : RuinedLibraryStructure.piece(seed + 2, at.getX(), at.getZ(), Direction.NORTH, ground,
                                    level.getSeaLevel(), level.getMinBuildHeight(), true);
                    ForcedStructure.place(level, ModLibrary.STRUCTURE, List.of(piece));
                    entrance = piece instanceof LibraryPiece lib ? lib.world(11, 0, Math.max(0, lib.mouthZ() - 3)) : at.offset(0, 0, -26);
                }
            }
            BlockPos signAt = surface(level, entrance);
            sign(level, signAt, spawn, Component.translatable("murim.showcase.place." + id),
                    Component.literal("/murim capture"), Component.literal(id));
            signs.put(id, signAt);
            data.places.put(id, new Data.Place(signAt.above(), yawTo(signAt, at)));
        }
        // Spawn row: the mountain, then the three locations with coordinates.
        Direction side = Direction.fromYRot(j.spawnYaw()).getClockWise();
        BlockPos row = surface(level, spawn.relative(Direction.fromYRot(j.spawnYaw()), 3));
        int i = 0;
        sign(level, surface(level, row), spawn, Component.translatable("murim.showcase.title"),
                Component.translatable("murim.showcase.place.hua"), Component.literal("/murim showcase"));
        for (Map.Entry<String, BlockPos> e : j.spots().entrySet()) {
            i++;
            BlockPos at = e.getValue();
            sign(level, surface(level, row.relative(side, i * 2)), spawn, Component.translatable("murim.showcase.place." + e.getKey()),
                    Component.literal("x " + at.getX()), Component.literal("z " + at.getZ()));
        }
        // World settings for building.
        level.setDefaultSpawnPos(spawn, j.spawnYaw());
        GameRules rules = level.getGameRules();
        rules.getRule(GameRules.RULE_SPAWN_RADIUS).set(0, server);
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        rules.getRule(GameRules.RULE_DO_PATROL_SPAWNING).set(false, server);
        rules.getRule(GameRules.RULE_DO_TRADER_SPAWNING).set(false, server);
        rules.getRule(GameRules.RULE_DOINSOMNIA).set(false, server);
        rules.getRule(GameRules.RULE_KEEPINVENTORY).set(true, server);
        level.setDayTime(6000L);
        level.setWeatherParameters(120000, 0, false, false);
        server.setDifficulty(Difficulty.PEACEFUL, true);
        server.getWorldData().setGameType(GameType.CREATIVE);
        data.places.put("spawn", new Data.Place(spawn, j.spawnYaw()));
        int[] gate = site.toWorld(-104, -420);
        data.places.put("hua", new Data.Place(new BlockPos(gate[0], spawn.getY(), gate[1]), j.spawnYaw()));
        int[] sect = site.toWorld(6, -10);
        data.places.put("sect", new Data.Place(new BlockPos(sect[0], MountHuaOverlay.refY(site) + 1, sect[1]), j.spawnYaw()));
        data.setDirty();
        MurimMod.LOGGER.info("Showcase built: {}", data.places);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.setGameMode(GameType.CREATIVE);
            listing(data).forEach(p::sendSystemMessage);
        }
        if (j.source() != null) {
            j.source().sendSuccess(() -> Component.translatable("command.murim.showcase.done"), true);
        }
    }

    private static BlockPos surface(ServerLevel level, BlockPos p) {
        level.getChunk(p.getX() >> 4, p.getZ() >> 4);
        return new BlockPos(p.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()), p.getZ());
    }

    private static float yawTo(BlockPos from, BlockPos to) {
        return (float) (Mth.atan2(to.getZ() - from.getZ(), to.getX() - from.getX()) * Mth.RAD_TO_DEG) - 90.0F;
    }

    /** A standing oak sign at {@code at} facing {@code faceTo}, with up to three lines. */
    private static void sign(ServerLevel level, BlockPos at, BlockPos faceTo, Component a, Component b, Component c) {
        float yaw = yawTo(at, faceTo);
        // The sign's front faces the reader: rotation of a sign placed by someone looking at yaw+180.
        int rot = RotationSegment.convertToSegment(yaw + 180.0F);
        level.setBlock(at, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, rot), 3);
        if (level.getBlockEntity(at) instanceof SignBlockEntity sign) {
            SignText text = new SignText().setMessage(0, a.copy().withStyle(ChatFormatting.BOLD)).setMessage(1, b).setMessage(2, c);
            sign.setText(text, true);
            sign.setWaxed(true);
            sign.setChanged();
            level.sendBlockUpdated(at, sign.getBlockState(), sign.getBlockState(), 3);
        }
    }

    // ------------------------------------------------------------------ listing

    static List<Component> listing(Data data) {
        List<Component> out = new ArrayList<>();
        out.add(Component.translatable("murim.showcase.chat_title").withStyle(ChatFormatting.GOLD));
        MutableComponent line = Component.empty();
        for (Map.Entry<String, Data.Place> e : data.places.entrySet()) {
            String cmd = "/murim showcase tp " + e.getKey();
            line.append(Component.literal("[").append(Component.translatable("murim.showcase.place." + e.getKey())).append("] ")
                    .withStyle(Style.EMPTY.withColor(0x9FD8A0).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(cmd + "  "
                                    + e.getValue().pos().toShortString())))));
        }
        out.add(line);
        out.add(Component.translatable("murim.showcase.hint").withStyle(ChatFormatting.GRAY));
        return out;
    }

    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && player.getServer() != null) {
            Data data = data(player.getServer());
            if (!data.places.isEmpty()) {
                listing(data).forEach(player::sendSystemMessage);
            }
        }
    }

    // ------------------------------------------------------------------ data

    static Data data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(Data.FACTORY, Data.NAME);
    }

    /** Where the showcase put things (overworld data {@code murim_showcase}); empty in an ordinary world. */
    public static final class Data extends SavedData {
        static final String NAME = "murim_showcase";
        static final SavedData.Factory<Data> FACTORY = new SavedData.Factory<>(Data::new, Data::load);

        record Place(BlockPos pos, float yaw) {
        }

        final Map<String, Place> places = new LinkedHashMap<>();

        private static Data load(CompoundTag tag, HolderLookup.Provider registries) {
            Data d = new Data();
            CompoundTag p = tag.getCompound("places");
            for (String k : p.getAllKeys()) {
                CompoundTag t = p.getCompound(k);
                d.places.put(k, new Place(BlockPos.of(t.getLong("pos")), t.getFloat("yaw")));
            }
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            CompoundTag p = new CompoundTag();
            places.forEach((k, v) -> {
                CompoundTag t = new CompoundTag();
                t.putLong("pos", v.pos().asLong());
                t.putFloat("yaw", v.yaw());
                p.put(k, t);
            });
            tag.put("places", p);
            return tag;
        }

        /** Position of a showcase place, or null. */
        public BlockPos pos(String id) {
            Place p = places.get(id);
            return p == null ? null : p.pos();
        }

        /** Facing of a showcase place (towards the location), or null. */
        public Float yaw(String id) {
            Place p = places.get(id);
            return p == null ? null : p.yaw();
        }
    }

    /** Showcase data of a server (for the capture stand). */
    public static Data showcase(MinecraftServer server) {
        return data(server);
    }
}
