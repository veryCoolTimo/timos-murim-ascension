package io.github.verycooltimo.murim.world.hua;

import io.github.verycooltimo.murim.MurimMod;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import io.github.verycooltimo.murim.network.MountHuaSitePayload;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Chooses Mount Hua's position once per world and hands the immutable {@link MountHuaSite} to the
 * worldgen threads.
 *
 * <p><b>Why a static cache:</b> the feature runs on chunk-generation worker threads, and
 * {@code DimensionDataStorage} (a plain HashMap) must not be touched from there. The cache holds
 * one immutable object, keyed by the server instance (an integrated server restarted in the same
 * JVM never sees the previous world's site) and cleared on server stop. It is never read on the
 * client side. Source of truth: {@link MountHuaSiteData} in the overworld data storage.
 *
 * <p><b>When:</b> {@link ServerStartedEvent} — the spawn is known by then. Before it only the spawn
 * area is generated (≤ ~200 blocks from spawn), and the mountain is ≥ 2000 blocks away, so no
 * chunk of the mountain can be generated before the site exists.
 * [НЕПРОВЕРЕНО: a mod that pre-generates far chunks during server start; check with Chunky.]
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/event/server/ServerStartedEvent.java,
 * reference/minecraft-src/net/minecraft/world/level/chunk/ChunkGenerator.java#getBaseHeight,
 * reference/minecraft-src/net/minecraft/world/level/biome/BiomeSource.java#getNoiseBiome
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class MountHuaSites {

    /** Distance from spawn to the massif centre; foot ≈ centre − 600, far side ≈ centre + 600. */
    static final int MIN_DISTANCE = 2700;
    static final int MAX_DISTANCE = 4800;
    private static final int CANDIDATES = 28;

    private record Cached(MinecraftServer server, MountHuaSite site, Timing timing) {
    }

    /** Generation timing (diagnostics only). */
    private static final class Timing {
        private int chunks;
        private long total;
        private long max;
    }

    private static volatile Cached cached;

    private MountHuaSites() {
    }

    /** The site of this server's overworld, or null before the server has started. */
    public static MountHuaSite get(MinecraftServer server) {
        Cached c = cached;
        return c != null && c.server() == server ? c.site() : null;
    }

    static void recordChunk(MinecraftServer server, long nanos) {
        Cached c = cached;
        if (c == null || c.server() != server) {
            return;
        }
        Timing t = c.timing();
        synchronized (t) {
            t.chunks++;
            t.total += nanos;
            t.max = Math.max(t.max, nanos);
            if (t.chunks % 250 == 0) {
                MountHuaFeature.logTiming(t.chunks, t.total, t.max);
            }
        }
    }

    @SubscribeEvent
    static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        ServerLevel overworld = server.overworld();
        MountHuaSiteData data = overworld.getDataStorage().computeIfAbsent(MountHuaSiteData.FACTORY, MountHuaSiteData.NAME);
        if (!data.chosen()) {
            choose(server, overworld, data);
        }
        MountHuaSite site = data.toSite(overworld.getSeed());
        cached = new Cached(server, site, new Timing());
        MurimMod.LOGGER.info("Mount Hua at x={} z={} (foot y={}, rotation {}), spawn {}", site.centerX(),
                site.centerZ(), site.baseY(), site.rotation(), overworld.getSharedSpawnPos());
    }

    /** The client draws the mist over the massif; it only needs the placement. */
    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            MountHuaSite site = get(player.getServer());
            if (site != null) {
                PacketDistributor.sendToPlayer(player, new MountHuaSitePayload(site.centerX(), site.centerZ(),
                        site.baseY(), site.rotation()));
            }
        }
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        Cached c = cached;
        if (c != null && c.server() == event.getServer()) {
            cached = null;
        }
    }

    /**
     * Seeded candidates on a ring around spawn; each scored by the vanilla terrain it would sit on:
     * no ocean/river/beach, few village biomes (villages are planned on the old ground and would be
     * cut by the massif), flat ground near a sane height, and no region files on disk (a world that
     * already has explored chunks there would show seams).
     */
    private static void choose(MinecraftServer server, ServerLevel level, MountHuaSiteData data) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        BlockPos spawn = level.getSharedSpawnPos();
        Random random = new Random(level.getSeed() ^ 0x48756153686Al);
        Path regions = DimensionType.getStorageFolder(Level.OVERWORLD, server.getWorldPath(LevelResource.ROOT))
                .resolve("region");
        double bestScore = Double.MAX_VALUE;
        int bestX = 0;
        int bestZ = 0;
        int bestY = 70;
        int bestRot = 0;
        for (int c = 0; c < CANDIDATES; c++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = MIN_DISTANCE + random.nextDouble() * (MAX_DISTANCE - MIN_DISTANCE);
            int cx = spawn.getX() + (int) (Math.cos(angle) * dist);
            int cz = spawn.getZ() + (int) (Math.sin(angle) * dist);
            // Rotation: the trail foot (local north, -v) faces the spawn.
            int rot = rotationTowards(spawn.getX() - cx, spawn.getZ() - cz);
            MountHuaSite probe = new MountHuaSite(cx, cz, 70, rot, level.getSeed());
            double score = 0;
            int[] heights = new int[25];
            int n = 0;
            for (int ring = 0; ring <= 3; ring++) {
                int samples = ring == 0 ? 1 : 8;
                for (int k = 0; k < samples; k++) {
                    double a = k * Math.PI * 2 / samples;
                    double r = ring * 190;
                    int[] w = probe.toWorld(Math.cos(a) * r * 0.75, -80 + Math.sin(a) * r);
                    int hFloor = generator.getBaseHeight(w[0], w[1], Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
                    int hSurf = generator.getBaseHeight(w[0], w[1], Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
                    heights[n++] = hFloor;
                    if (hSurf > hFloor + 1) {
                        score += 40;
                    }
                    Holder<Biome> biome = generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(w[0]),
                            QuartPos.fromBlock(hFloor), QuartPos.fromBlock(w[1]), randomState.sampler());
                    if (biome.is(BiomeTags.IS_OCEAN)) {
                        score += 120;
                    } else if (biome.is(BiomeTags.IS_RIVER) || biome.is(BiomeTags.IS_BEACH)) {
                        score += 30;
                    }
                    if (biome.is(BiomeTags.IS_MOUNTAIN)) {
                        score += 12;
                    }
                    if (biome.is(BiomeTags.HAS_VILLAGE_PLAINS) || biome.is(BiomeTags.HAS_VILLAGE_DESERT)
                            || biome.is(BiomeTags.HAS_VILLAGE_SAVANNA) || biome.is(BiomeTags.HAS_VILLAGE_SNOWY)
                            || biome.is(BiomeTags.HAS_VILLAGE_TAIGA)) {
                        score += 4;
                    }
                }
            }
            int[] sorted = Arrays.copyOf(heights, n);
            Arrays.sort(sorted);
            int median = sorted[n / 2];
            double mean = Arrays.stream(sorted).average().orElse(median);
            double var = Arrays.stream(sorted).mapToDouble(h -> (h - mean) * (h - mean)).sum() / n;
            score += Math.sqrt(var) * 2 + Math.abs(median - 72) * 0.6;
            if (regionsExist(regions, probe)) {
                score += 1000;
            }
            score += 60 * settlements(level, generator, randomState, probe);
            if (score < bestScore) {
                bestScore = score;
                bestX = cx;
                bestZ = cz;
                bestY = Math.max(63, Math.min(100, median));
                bestRot = rot;
            }
        }
        data.choose(bestX, bestZ, bestY, bestRot);
        MurimMod.LOGGER.info("Mount Hua site chosen: x={} z={} y={} rot={} (score {})", bestX, bestZ, bestY,
                bestRot, String.format("%.1f", bestScore));
    }

    /**
     * Villages, outposts and mansions that would start inside the footprint: they are planned on the
     * old ground, and the massif would bury or cut them (seen on the stand 03.10: a village under the
     * talus at the gate). Counts placement chunks of those structure sets whose biome allows them.
     */
    private static int settlements(ServerLevel level, ChunkGenerator generator, RandomState randomState,
            MountHuaSite probe) {
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        int hits = 0;
        for (Holder<StructureSet> set : state.possibleStructureSets()) {
            List<Structure> settlements = new ArrayList<>();
            for (StructureSet.StructureSelectionEntry entry : set.value().structures()) {
                String id = entry.structure().unwrapKey().map(k -> k.location().getPath()).orElse("");
                if (id.contains("village") || id.contains("pillager_outpost") || id.contains("mansion")) {
                    settlements.add(entry.structure().value());
                }
            }
            if (settlements.isEmpty()) {
                continue;
            }
            StructurePlacement placement = set.value().placement();
            for (int cx = probe.minX() >> 4; cx <= probe.maxX() >> 4; cx++) {
                for (int cz = probe.minZ() >> 4; cz <= probe.maxZ() >> 4; cz++) {
                    if (!placement.isStructureChunk(state, cx, cz)) {
                        continue;
                    }
                    int bx = (cx << 4) + 8;
                    int bz = (cz << 4) + 8;
                    Holder<Biome> biome = generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(bx),
                            QuartPos.fromBlock(72), QuartPos.fromBlock(bz), randomState.sampler());
                    for (Structure structure : settlements) {
                        if (structure.biomes().contains(biome)) {
                            hits++;
                            break;
                        }
                    }
                }
            }
        }
        return hits;
    }

    private static boolean regionsExist(Path regions, MountHuaSite probe) {
        for (int rx = probe.minX() >> 9; rx <= probe.maxX() >> 9; rx++) {
            for (int rz = probe.minZ() >> 9; rz <= probe.maxZ() >> 9; rz++) {
                if (Files.exists(regions.resolve("r." + rx + "." + rz + ".mca"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Quarter turns that point local north (−v) along (dx, dz). */
    static int rotationTowards(int dx, int dz) {
        // Local north in world space per rotation: 0 → (0,−1), 1 → (1,0), 2 → (0,1), 3 → (−1,0).
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? 1 : 3;
        }
        return dz < 0 ? 0 : 2;
    }
}
