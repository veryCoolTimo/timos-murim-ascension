package io.github.verycooltimo.murim.dev;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.datafixers.util.Pair;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.ScrollShelfBlock;
import io.github.verycooltimo.murim.decor.TallLibraryBookcaseBlock;
import io.github.verycooltimo.murim.entity.Bandit;
import io.github.verycooltimo.murim.entity.boss.FortressMaster;
import io.github.verycooltimo.murim.library.ArchiveShelfBlock;
import io.github.verycooltimo.murim.library.LibraryPiece;
import io.github.verycooltimo.murim.library.ModLibrary;
import io.github.verycooltimo.murim.sect.SectService;
import io.github.verycooltimo.murim.world.camp.BanditCampCommand;
import io.github.verycooltimo.murim.world.camp.BanditCampData;
import io.github.verycooltimo.murim.world.camp.BanditCamps;
import io.github.verycooltimo.murim.world.fortress.FortressCommand;
import io.github.verycooltimo.murim.world.fortress.FortressData;
import io.github.verycooltimo.murim.world.fortress.Fortresses;
import io.github.verycooltimo.murim.world.hua.HuaBaked;
import io.github.verycooltimo.murim.world.hua.HuaBakedExtra;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;

/**
 * Nightly smoke probes (tools/smoke/smoke.sh phase p, zero model tokens): {@code /murim dev probe <camp|library|cave|fortress> go}
 * finds the thing nearest to the player, logs where it is (SMOKE-PROBE-LOC) and moves the player there (creative, so nobody is hurt);
 * after the smoke script has waited a few seconds {@code ... check} asserts that it is alive and logs ONE line
 * {@code SMOKE-PROBE <name> PASS|FAIL <details>}, which tools/smoke/analyze.py and smoke.sh turn into {@code PROBE ...} lines.
 * Camp: bandits of the camp stand in it and a loot chest is in the yard. Library: bookcases and a loot container stand in the
 * archive. Cave: the baked cave signatures are in the world at the site offset, the mouth is open to the sky (no wall) and its height over the sect gate is logged. Fortress: found beyond the Mount Hua margin and its master is alive on the throne.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/chunk/ChunkGenerator.java#findNearestMapStructure,
 * reference/minecraft-src/net/minecraft/world/level/block/state/BlockBehaviour.java#getCollisionShape.
 */
public final class SmokeProbe {
    private static final List<String> NAMES = List.of("camp", "library", "cave", "fortress");
    /** findNearestMapStructure counts spacing cells, not chunks: 5 library cells = 3800 blocks round the mountain (the camp and the fortress commands search 100 cells and answer at once). */
    private static final int LIBRARY_CELLS = 5;
    private static final int FORTRESS_CELLS = 8;
    private static final int FLOOD_CELLS = 60000;

    private SmokeProbe() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("probe").then(Commands.literal("diag").executes(c -> diag(c.getSource().getServer().overworld(), c.getSource()))
                .then(Commands.literal("refs").then(Commands.argument("cx", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                        .then(Commands.argument("cz", com.mojang.brigadier.arguments.IntegerArgumentType.integer()).executes(c -> {
                            ServerLevel lv = c.getSource().getServer().overworld();
                            ChunkPos cp = new ChunkPos(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "cx"),
                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "cz"));
                            for (var key : List.of(io.github.verycooltimo.murim.world.camp.BanditCamp.KEY, ModLibrary.STRUCTURE, io.github.verycooltimo.murim.world.fortress.Fortress.KEY)) {
                                MurimMod.LOGGER.info("SMOKE-DIAG-REFS {} {}", key.location(), diag(lv, cp, lv.registryAccess().registryOrThrow(Registries.STRUCTURE).get(key)));
                            }
                            return 1;
                        })))))
                .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(NAMES, b))
                .then(Commands.literal("go").executes(c -> run(c, Step.GO)))
                .then(Commands.literal("prep").executes(c -> run(c, Step.PREP)))
                .then(Commands.literal("check").executes(c -> run(c, Step.CHECK))));
    }

    /** Console-only help for a FAIL that needs explaining: where the three world structures are and what their start chunks hold. */
    private static int diag(ServerLevel level, CommandSourceStack src) {
        BlockPos from = level.getSharedSpawnPos();
        MountHuaSite site = site(level);
        if (site != null) {
            from = new BlockPos(site.centerX(), 64, site.centerZ());
        }
        for (var key : List.of(io.github.verycooltimo.murim.world.camp.BanditCamp.KEY, ModLibrary.STRUCTURE, io.github.verycooltimo.murim.world.fortress.Fortress.KEY)) {
            BlockPos at = locate(level, key, from, key.equals(ModLibrary.STRUCTURE) ? 2 : 8);
            Structure st = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(key);
            if (at == null) {
                MurimMod.LOGGER.info("SMOKE-DIAG {} none", key.location());
                continue;
            }
            ChunkPos cp = new ChunkPos(at);
            int refsAround = 0;
            for (int pass = 0; pass < 2; pass++) {
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        level.getChunk(cp.x + dx, cp.z + dz);
                    }
                }
            }
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    refsAround += level.getChunk(cp.x + dx, cp.z + dz).getReferencesForStructure(st).size();
                }
            }
            MurimMod.LOGGER.info("SMOKE-DIAG {} at {} chunk {},{} refs_in_7x7={} {}", key.location(), at.toShortString(), cp.x, cp.z, refsAround, diag(level, cp, st));
        }
        return 1;
    }

    private enum Step { GO, PREP, CHECK }

    /** Where the structure of each probe was located (the go step) and whether the world built it itself or the probe did (prep). */
    private static final java.util.Map<String, BlockPos> LOCATED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, String> SOURCE = new java.util.concurrent.ConcurrentHashMap<>();

    private static int run(CommandContext<CommandSourceStack> c, Step step) {
        boolean check = step == Step.CHECK;
        String name = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "name");
        ServerLevel level = c.getSource().getServer().overworld();
        ServerPlayer player = c.getSource().getPlayer();
        if (player == null || !NAMES.contains(name)) {
            c.getSource().sendFailure(Component.literal("probe: a player and one of " + NAMES + " needed"));
            return 0;
        }
        try {
            String verdict;
            if (step == Step.PREP) {
                verdict = prep(level, name);
            } else {
                verdict = switch (name) {
                    case "camp" -> camp(level, player, check);
                    case "library" -> library(level, player, check);
                    case "cave" -> cave(level, player, check);
                    default -> fortress(level, player, check);
                };
            }
            if (check) {
                boolean ok = verdict.startsWith("PASS");
                MurimMod.LOGGER.info("SMOKE-PROBE {} {}", name, verdict);
                c.getSource().sendSuccess(() -> Component.literal("probe " + name + " " + verdict), false);
                return ok ? 1 : 0;
            }
            c.getSource().sendSuccess(() -> Component.literal("probe " + name + " " + step.name().toLowerCase(Locale.ROOT) + ": " + verdict), false);
            return 1;
        } catch (RuntimeException e) {
            MurimMod.LOGGER.info("SMOKE-PROBE {} FAIL exception {}", name, e.toString());
            return 0;
        }
    }

    private static void loc(String name, String what) {
        MurimMod.LOGGER.info("SMOKE-PROBE-LOC {} {}", name, what);
    }

    private static void arrive(ServerPlayer p, ServerLevel level, double x, double y, double z) {
        p.setGameMode(GameType.CREATIVE);
        level.getChunk((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
        p.teleportTo(level, x, y, z, 0.0F, 0.0F);
    }

    /** The nearest one is searched from the mountain (the same place whatever the probe did before), else from the player. */
    private static BlockPos origin(ServerLevel level, ServerPlayer p) {
        MountHuaSite s = site(level);
        return s == null ? p.blockPosition() : new BlockPos(s.centerX(), 64, s.centerZ());
    }

    /**
     * Where the nearest structure of this kind is, WITHOUT touching its chunks beyond what the locate does: the player is moved by it and
     * the world generates the place the way it does for a walker. (A locate followed by a forced read of the start chunk left the
     * structure start with no references and the structure unbuilt, so the go step stays this plain.)
     */
    private static BlockPos locate(ServerLevel level, net.minecraft.resources.ResourceKey<Structure> key, BlockPos from, int cells) {
        Holder<Structure> holder = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getHolder(key).orElse(null);
        if (holder == null) {
            return null;
        }
        Pair<BlockPos, Holder<Structure>> found = level.getChunkSource().getGenerator()
                .findNearestMapStructure(level, HolderSet.direct(holder), from, cells, false);
        return found == null ? null : found.getFirst();
    }

    /** The library locate takes a minute or more (every candidate samples 961 ground columns): it runs beside the other probes. */
    private static final java.util.Map<String, java.util.concurrent.CompletableFuture<BlockPos>> ASYNC = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * {@code async}: the first go starts the locate on its own thread (the chunks it asks for are made by the server thread, which goes on
     * ticking) and returns false; the next go waits for the answer and moves the player. false with LOCATED unset is "not moved yet".
     */
    private static boolean goTo(ServerLevel level, ServerPlayer p, String name, net.minecraft.resources.ResourceKey<Structure> key, int cells, boolean async) {
        BlockPos from = origin(level, p);
        BlockPos at;
        if (async) {
            java.util.concurrent.CompletableFuture<BlockPos> f = ASYNC.get(name);
            if (f == null) {
                java.util.concurrent.CompletableFuture<BlockPos> fresh = new java.util.concurrent.CompletableFuture<>();
                ASYNC.put(name, fresh);
                Thread t = new Thread(() -> {
                    try {
                        fresh.complete(locate(level, key, from, cells));
                    } catch (Throwable e) {
                        fresh.completeExceptionally(e);
                    }
                }, "murim-probe-locate-" + name);
                t.setDaemon(true);
                t.start();
                loc(name, "locate started");
                return false;
            }
            try {
                at = f.get(300, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                loc(name, "locate failed " + e);
                return false;
            }
        } else {
            at = locate(level, key, from, cells);
        }
        if (at == null) {
            loc(name, "none found");
            return false;
        }
        LOCATED.put(name, at);
        loc(name, "at " + at.getX() + "," + at.getZ() + " " + fromMountain(level, at.getX(), at.getZ()));
        level.getChunk(at.getX() >> 4, at.getZ() >> 4);
        arrive(p, level, at.getX() + 0.5D, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()) + 1.0D, at.getZ() + 0.5D);
        return true;
    }

    private static MountHuaSite site(ServerLevel level) {
        return MountHuaSites.get(level.getServer());
    }

    private static String fromMountain(ServerLevel level, int x, int z) {
        MountHuaSite s = site(level);
        return s == null ? "mountain=?" : "mountain_dist=" + (int) Math.sqrt(Math.pow(x - s.centerX(), 2) + Math.pow(z - s.centerZ(), 2));
    }

    private static final java.util.Map<String, net.minecraft.resources.ResourceKey<Structure>> KEYS = java.util.Map.of(
            "camp", io.github.verycooltimo.murim.world.camp.BanditCamp.KEY, "library", ModLibrary.STRUCTURE,
            "fortress", io.github.verycooltimo.murim.world.fortress.Fortress.KEY);

    private static StructureStart startAt(ServerLevel level, net.minecraft.resources.ResourceKey<Structure> key, BlockPos at) {
        Structure st = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(key);
        ChunkPos cp = new ChunkPos(at);
        StructureStart s = level.getChunk(cp.x, cp.z, ChunkStatus.STRUCTURE_STARTS, true).getStartForStructure(st);
        return s != null && s.isValid() ? s : null;
    }

    /**
     * The prep step. The world is meant to build a camp, library or fortress where the start of the structure is. In the generated test
     * worlds the starts are there but the chunks carry no structure references, so nothing is built (not even a vanilla village; the
     * cause is not found). The probe then places the very start the world made with ForcedStructure, which registers it the way worldgen
     * does, and says so in the PROBE line (source=forced). source=natural means the world built it itself.
     */
    private static String prep(ServerLevel level, String name) {
        net.minecraft.resources.ResourceKey<Structure> key = KEYS.get(name);
        BlockPos at = LOCATED.get(name);
        if (key == null || at == null) {
            return "nothing located";
        }
        StructureStart start = startAt(level, key, at);
        if (start == null) {
            SOURCE.put(name, "none");
            return "no valid start at " + at.toShortString();
        }
        Structure st = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(key);
        ChunkPos cp = start.getChunkPos();
        if (!level.getChunk(cp.x, cp.z).getReferencesForStructure(st).isEmpty()) {
            SOURCE.put(name, "natural");
            return "natural";
        }
        io.github.verycooltimo.murim.world.location.ForcedStructure.place(level, key, new ArrayList<>(start.getPieces()));
        SOURCE.put(name, "forced");
        return "forced";
    }

    private static String source(String name) {
        return " source=" + SOURCE.getOrDefault(name, "unprepared");
    }

    // ------------------------------------------------------------------ camp

    private static String camp(ServerLevel level, ServerPlayer p, boolean check) {
        if (!check) {
            return goTo(level, p, "camp", io.github.verycooltimo.murim.world.camp.BanditCamp.KEY, 100, false) ? "moved" : "none found";
        }
        BanditCampCommand.Found f = BanditCampCommand.nearest(level, LOCATED.get("camp") != null ? LOCATED.get("camp") : p.blockPosition());
        if (f == null) {
            return "FAIL no bandit camp structure within the search radius";
        }
        int cx = f.piece().centreX(), cz = f.piece().centreZ();
        String at = "at " + cx + "," + cz + " " + fromMountain(level, cx, cz);
        // the locate position can lie in another chunk than the start of the structure: the camp is the registered one nearest to the piece
        BanditCampData.Camp camp = null;
        for (BanditCampData.Camp k : BanditCamps.data(level).all()) {
            if (Math.abs(k.centre.getX() - cx) <= 8 && Math.abs(k.centre.getZ() - cz) <= 8) {
                camp = k;
            }
        }
        if (camp == null) {
            // diagnosis for the report: does the loop of BanditCamps.onLevelTick see this camp from where the player stands?
            net.minecraft.world.level.levelgen.structure.Structure st = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(io.github.verycooltimo.murim.world.camp.BanditCamp.KEY);
            int refs = 0, loadedChunks = 0;
            ChunkPos pc = p.chunkPosition();
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    if (level.hasChunk(pc.x + dx, pc.z + dz)) {
                        loadedChunks++;
                        refs += level.getChunk(pc.x + dx, pc.z + dz).getReferencesForStructure(st).size();
                    }
                }
            }
            return "FAIL " + at + " camp never registered; player=" + p.blockPosition().toShortString() + " gm=" + p.gameMode.getGameModeForPlayer().getName()
                    + " difficulty=" + level.getDifficulty().getKey() + " known_camps=" + java.util.stream.StreamSupport.stream(BanditCamps.data(level).all().spliterator(), false).count()
                    + " loaded_chunks_4=" + loadedChunks + " refs=" + refs + " start_chunk=" + new ChunkPos(f.key()).x + "," + new ChunkPos(f.key()).z
                    + " player_chunk=" + pc.x + "," + pc.z + " " + diag(level, new ChunkPos(f.key()), st);
        }
        long key = camp.key;
        int bandits = 0;
        for (Bandit b : level.getEntitiesOfClass(Bandit.class, new AABB(camp.centre).inflate(64.0D), b -> b.isAlive() && b.campKey() == key)) {
            bandits++;
        }
        int chests = 0;
        int loaded = 0;
        for (BlockPos pos : BlockPos.betweenClosed(camp.centre.offset(-24, -4, -24), camp.centre.offset(24, 10, 24))) {
            if (!level.isLoaded(pos)) {
                continue;
            }
            loaded++;
            BlockState s = level.getBlockState(pos);
            if (s.is(Blocks.CHEST) || s.is(Blocks.BARREL)) {
                BlockEntity be = level.getBlockEntity(pos);
                if ((be instanceof ChestBlockEntity ch && (ch.getLootTable() != null || !ch.isEmpty()))
                        || (be instanceof BarrelBlockEntity br && (br.getLootTable() != null || !br.isEmpty()))) {
                    chests++;
                }
            }
        }
        boolean ok = camp.populated && bandits >= 3 && chests >= 1;
        return (ok ? "PASS " : "FAIL ") + at + " bandits=" + bandits + " registry=" + camp.alive.size() + " populated=" + camp.populated
                + " cleared=" + camp.cleared + " loot_chests=" + chests + " scanned=" + loaded + source("camp");
    }

    // ------------------------------------------------------------------ library

    /** The library piece of the start at (or, with no start there, nearest to) a located place. */
    private static LibraryPiece libraryPiece(ServerLevel level, BlockPos at) {
        StructureStart s = startAt(level, ModLibrary.STRUCTURE, at);
        if (s == null) {
            return null;
        }
        for (StructurePiece piece : s.getPieces()) {
            if (piece instanceof LibraryPiece lp) {
                return lp;
            }
        }
        return null;
    }

    private static String library(ServerLevel level, ServerPlayer p, boolean check) {
        if (!check) {
            return goTo(level, p, "library", ModLibrary.STRUCTURE, LIBRARY_CELLS, true) ? "moved" : "not moved (locate running, or nothing found)";
        }
        LibraryPiece piece = libraryPiece(level, LOCATED.get("library") != null ? LOCATED.get("library") : origin(level, p));
        if (piece == null) {
            return "FAIL no ruined library structure within the search radius";
        }
        BoundingBox box = piece.getBoundingBox();
        int cx = (box.minX() + box.maxX()) / 2, cz = (box.minZ() + box.maxZ()) / 2;
        String at = "at " + cx + "," + cz + " " + fromMountain(level, cx, cz);
        int solid = 0, shelves = 0, loot = 0, missing = 0, murim = 0, air = 0;
        java.util.Map<String, Integer> kinds = new java.util.TreeMap<>();
        for (int chx = box.minX() >> 4; chx <= box.maxX() >> 4; chx++) {
            for (int chz = box.minZ() >> 4; chz <= box.maxZ() >> 4; chz++) {
                if (!level.hasChunk(chx, chz)) {
                    missing++;
                }
            }
        }
        for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            if (!level.hasChunkAt(pos)) {
                continue;
            }
            BlockState s = level.getBlockState(pos);
            if (s.isAir()) {
                air++;
                continue;
            }
            solid++;
            Block b = s.getBlock();
            if (BuiltInRegistries.BLOCK.getKey(b).getNamespace().equals("murim")) {
                murim++;
                kinds.merge(BuiltInRegistries.BLOCK.getKey(b).getPath(), 1, Integer::sum);
            }
            if (b instanceof ScrollShelfBlock || b instanceof TallLibraryBookcaseBlock || b instanceof ArchiveShelfBlock) {
                shelves++;
            }
            if (s.is(Blocks.BARREL) && level.getBlockEntity(pos) instanceof BarrelBlockEntity br && (br.getLootTable() != null || !br.isEmpty())) {
                loot++;
            } else if (b instanceof io.github.verycooltimo.murim.library.ProppedShelfBlock) {
                loot++;
            }
        }
        boolean ok = missing == 0 && solid > 500 && shelves >= 8 && loot >= 1;
        String why = ok ? "" : " " + diag(level, new ChunkPos(piece.getBoundingBox().minX() >> 4, piece.getBoundingBox().minZ() >> 4),
                level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(ModLibrary.STRUCTURE));
        return (ok ? "PASS " : "FAIL ") + at + " blocks=" + solid + " air=" + air + " samples=" + sample(level, piece.archive(13, 0, 13)) + "," + sample(level, piece.archive(13, 8, 13)) + ","
                + sample(level, piece.archive(13, io.github.verycooltimo.murim.library.LibraryPlan.TOP, 13)) + ","
                + sample(level, piece.world(11, io.github.verycooltimo.murim.library.LibraryPlan.TOP + piece.steps(), piece.mouthZ())) + ","
                + sample(level, piece.world(11, io.github.verycooltimo.murim.library.LibraryPlan.TOP, 3)) + " bookcases=" + shelves + source("library") + " loot_containers=" + loot + " unloaded_chunks=" + missing
                + why + " murim_blocks=" + murim + kinds.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(4)
                        .map(e -> " " + e.getKey() + "x" + e.getValue()).reduce("", String::concat);
    }

    /** What the start chunk of a structure holds, for a FAIL line that has to be understood without a rerun. */
    private static String diag(ServerLevel level, ChunkPos start, Structure st) {
        ChunkAccess c = level.getChunk(start.x, start.z);
        StructureStart ss = c.getStartForStructure(st);
        return "start_chunk[status=" + c.getPersistedStatus() + " start=" + (ss == null ? "none" : ss.isValid() + " box=" + ss.getBoundingBox().toString())
                + " refs_here=" + c.getReferencesForStructure(st).size() + " all_starts=" + c.getAllStarts().size()
                + " manager_starts_here=" + level.structureManager().startsForStructure(start, x -> true).size()
                + " manager_start_at_ref=" + level.structureManager().getStructureAt(new BlockPos(start.getMiddleBlockX(), 70, start.getMiddleBlockZ()), st).isValid()
                + " all_refs=" + c.getAllReferences().entrySet().stream().map(e -> level.registryAccess().registryOrThrow(Registries.STRUCTURE).getKey(e.getKey()) + "x" + e.getValue().size())
                        .collect(java.util.stream.Collectors.joining(",")) + " start_structs=" + c.getAllStarts().keySet().stream().map(k -> level.registryAccess().registryOrThrow(Registries.STRUCTURE).getKey(k).toString())
                        .collect(java.util.stream.Collectors.joining(",")) + "]";
    }

    private static String sample(ServerLevel level, BlockPos p) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).getPath();
    }

    // ------------------------------------------------------------------ cave

    private record Sig(String name, int u, int y, int v, BlockState state) {
    }

    private static List<Sig> caveSignatures() {
        List<Sig> out = new ArrayList<>();
        try (Reader r = new InputStreamReader(SmokeProbe.class.getResourceAsStream("/data/murim/hua_baked/extra/signatures.json"), StandardCharsets.UTF_8)) {
            JsonArray arr = new Gson().fromJson(r, JsonArray.class);
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                if (!o.get("group").getAsString().equals("cave")) {
                    continue;
                }
                BlockState state = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), new StringReader(o.get("state").getAsString()), false).blockState();
                out.add(new Sig(o.get("name").getAsString(), o.get("u").getAsInt(), o.get("y").getAsInt(), o.get("v").getAsInt(), state));
            }
        } catch (Exception e) {
            throw new IllegalStateException("cave signatures: " + e);
        }
        return out;
    }

    private static boolean free(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    private static boolean standable(ServerLevel level, BlockPos p) {
        return free(level, p) && free(level, p.above()) && level.getFluidState(p).isEmpty()
                && !level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty();
    }

    private static boolean climbable(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        String path = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        return s.is(BlockTags.CLIMBABLE) || path.contains("rope") || path.contains("climbing");
    }

    /**
     * Flood fill through free cells (no collision, six neighbours) from the cave: is there no solid wall between the cave and the open
     * sky? The cave of the author opens on a cliff face high over the sect, so a walking route is a climb and is not asserted.
     * 1 = open sky reached, 0 = closed (the whole free space was filled), -1 = cell budget used.
     */
    private static int openToSky(ServerLevel level, BlockPos start) {
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start.asLong());
        while (!queue.isEmpty()) {
            if (seen.size() > FLOOD_CELLS) {
                return -1;
            }
            BlockPos cur = queue.poll();
            if (level.canSeeSky(cur) && Math.abs(cur.getX() - start.getX()) + Math.abs(cur.getZ() - start.getZ()) > 6) {
                return 1;
            }
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                BlockPos n = cur.relative(d);
                if (free(level, n) && seen.add(n.asLong())) {
                    queue.add(n);
                }
            }
        }
        return 0;
    }

    private static BlockPos nearStand(ServerLevel level, BlockPos around, int r) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(around.offset(-r, -r, -r), around.offset(r, r, r))) {
            if (standable(level, p) && p.distSqr(around) < bd) {
                bd = p.distSqr(around);
                best = p.immutable();
            }
        }
        return best;
    }

    private static String cave(ServerLevel level, ServerPlayer p, boolean check) {
        MountHuaSite site = site(level);
        HuaBaked.Index index = HuaBakedExtra.index(level.getServer());
        if (site == null || index == null || !site.canonical()) {
            return "FAIL no canonical Mount Hua site or no extra bake index";
        }
        HuaBaked.Group group = index.groups().stream().filter(g -> g.id().equals("cave")).findFirst().orElse(null);
        if (group == null) {
            return "FAIL cave group missing from the extra bake";
        }
        int shift = HuaBaked.shift(index, group, site);
        Rotation rotation = Rotation.values()[site.rotation()];
        List<Sig> sigs = caveSignatures();
        BlockPos rug = null;
        int matched = 0;
        boolean crate = false, jar = false, chest = false, bed = false;
        for (Sig s : sigs) {
            int[] d = site.rotateDir(s.u(), s.v());
            BlockPos pos = new BlockPos(site.centerX() + d[0], s.y() + shift, site.centerZ() + d[1]);
            BlockState actual = level.getBlockState(pos);
            BlockState expected = s.state().rotate(rotation);
            boolean same = expected.isAir() ? actual.isAir() : actual.is(expected.getBlock());
            if (same) {
                matched++;
            }
            String n = BuiltInRegistries.BLOCK.getKey(expected.getBlock()).getPath();
            if (same) {
                crate |= n.equals("pill_crate");
                jar |= n.equals("wine_jar");
                chest |= n.equals("sto_chest");
                bed |= n.startsWith("sto_bed");
            }
            if (n.startsWith("sto_rug") && rug == null) {
                rug = pos;
            }
        }
        BlockPos any = rug != null ? rug : new BlockPos(site.centerX(), 200, site.centerZ());
        BlockPos stand = standable(level, any) ? any : standable(level, any.above()) ? any.above() : nearStand(level, any, 3);
        int[] cxz = site.rotateDir(62, 157);
        String at = "at " + (site.centerX() + cxz[0]) + "," + (site.centerZ() + cxz[1]) + " " + fromMountain(level, site.centerX() + cxz[0], site.centerZ() + cxz[1]);
        if (!check) {
            loc("cave", at + " stand=" + (stand == null ? "none" : stand.toShortString()));
            if (stand != null) {
                arrive(p, level, stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D);
            }
            return at;
        }
        if (stand == null) {
            return "FAIL " + at + " no standing place beside the cave rugs; signatures=" + matched + "/" + sigs.size();
        }
        int mouth = openToSky(level, stand);
        int[] gate = SectService.zone(site, "sect_gate");
        String rise = gate == null ? "?" : (stand.getY() - gate[1]) + "";
        String mouthText = mouth == 1 ? "open" : mouth == 0 ? "closed" : "budget";
        boolean ok = crate && jar && chest && bed && matched * 10 >= sigs.size() * 9 && mouth == 1;
        return (ok ? "PASS " : "FAIL ") + at + " signatures=" + matched + "/" + sigs.size() + " crate=" + crate + " jar=" + jar + " chest=" + chest
                + " bed=" + bed + " mouth=" + mouthText + " above_sect_gate=" + rise;
    }

    // ------------------------------------------------------------------ fortress

    private static String fortress(ServerLevel level, ServerPlayer p, boolean check) {
        if (!check) {
            return goTo(level, p, "fortress", io.github.verycooltimo.murim.world.fortress.Fortress.KEY, FORTRESS_CELLS, false) ? "moved" : "none found";
        }
        FortressData.Entry e = FortressCommand.find(level, LOCATED.get("fortress") != null ? LOCATED.get("fortress") : p.blockPosition());
        if (e == null) {
            return "FAIL no fortress structure within the search radius";
        }
        String at = "at " + e.yard.getX() + "," + e.yard.getZ() + " " + fromMountain(level, e.yard.getX(), e.yard.getZ());
        FortressMaster m = Fortresses.master(level, e);
        MountHuaSite site = site(level);
        boolean beyondMargin = site == null || !site.near(e.yard.getX(), e.yard.getZ(), 160);
        boolean ok = m != null && m.isAlive() && beyondMargin;
        return (ok ? "PASS " : "FAIL ") + at + " master=" + (m == null ? "none" : BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).getPath() + " hp=" + (int) m.getHealth())
                + " beyond_hua_margin=" + beyondMargin + " defeated_before=" + (e.defeatedAt != 0L) + source("fortress");
    }
}
