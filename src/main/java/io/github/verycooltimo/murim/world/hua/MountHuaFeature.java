package io.github.verycooltimo.murim.world.hua;

import com.mojang.serialization.Codec;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * The Mount Hua terrain feature. Placed once per chunk (placed feature without placement
 * modifiers) in the {@code raw_generation} step of every overworld biome via a NeoForge biome
 * modifier ({@code data/murim/neoforge/biome_modifier/mount_hua.json}). Exits immediately for
 * chunks outside the mountain's bounding box.
 *
 * <p>Why a feature and not a chunk generator or density function: those replace the overworld
 * generator or its noise settings, which breaks worlds and modpacks with other terrain mods
 * (Terralith etc.). A feature runs on any overworld. Trade-offs (no vanilla caves inside the new
 * rock, structures planned on the old terrain) are recorded in docs/design/23-mount-hua-sect.md.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/levelgen/feature/Feature.java#place,
 * reference/minecraft-src/net/minecraft/world/level/chunk/ChunkGenerator.java#applyBiomeDecoration
 * (one placement per chunk and step at the chunk origin; biomes of the 3×3 area decide the list).
 */
public final class MountHuaFeature extends Feature<NoneFeatureConfiguration> {

    /** False: the terrain pass (raw_generation). True: the cleanup pass (top_layer_modification). */
    private final boolean cleanup;

    public MountHuaFeature(Codec<NoneFeatureConfiguration> codec, boolean cleanup) {
        super(codec);
        this.cleanup = cleanup;
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        ServerLevel serverLevel = level.getLevel();
        if (serverLevel.dimension() != Level.OVERWORLD) {
            return false;
        }
        MountHuaSite site = MountHuaSites.get(serverLevel.getServer());
        if (site == null) {
            return false;
        }
        ChunkPos chunkPos = new ChunkPos(context.origin());
        if (!site.touchesChunk(chunkPos.x, chunkPos.z)) {
            return false;
        }
        if (cleanup) {
            MountHuaChunkWriter.cleanup(level, level.getChunk(chunkPos.x, chunkPos.z), site);
            return true;
        }
        long start = System.nanoTime();
        boolean wrote = new MountHuaChunkWriter(level, level.getChunk(chunkPos.x, chunkPos.z), site).write();
        if (wrote) {
            MountHuaSites.recordChunk(serverLevel.getServer(), System.nanoTime() - start);
        }
        return wrote;
    }

    static void logTiming(int chunks, long totalNanos, long maxNanos) {
        MurimMod.LOGGER.info("Mount Hua: {} chunks, avg {} ms, max {} ms", chunks,
                String.format("%.1f", totalNanos / 1e6 / chunks), String.format("%.1f", maxNanos / 1e6));
    }
}
