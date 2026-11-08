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
 * (Terralith etc.). A feature runs on any overworld. Interior caves and suppression of vanilla surface structures are handled by the terrain
 * pass; see docs/adr/0001-mount-hua-terrain-feature.md.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/levelgen/feature/Feature.java#place,
 * reference/minecraft-src/net/minecraft/world/level/chunk/ChunkGenerator.java#applyBiomeDecoration
 * (one placement per chunk and step at the chunk origin; biomes of the 3×3 area decide the list).
 */
public final class MountHuaFeature extends Feature<NoneFeatureConfiguration> {

    /** Which pass of the mountain this feature instance is. */
    public enum Pass {
        /** The terrain pass (raw_generation). */
        TERRAIN,
        /** Paints the Mount Hua biomes before the vanilla vegetation is placed (fluid_springs, after the ores). */
        PAINT,
        /** The cleanup pass (top_layer_modification). */
        CLEANUP
    }

    private final Pass pass;
    private final boolean cleanup;

    public MountHuaFeature(Codec<NoneFeatureConfiguration> codec, boolean cleanup) {
        this(codec, cleanup ? Pass.CLEANUP : Pass.TERRAIN);
    }

    public MountHuaFeature(Codec<NoneFeatureConfiguration> codec, Pass pass) {
        super(codec);
        this.pass = pass;
        this.cleanup = pass == Pass.CLEANUP;
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
        if (pass == Pass.PAINT) {
            if (site.touchesChunk(chunkPos.x, chunkPos.z)) {
                HuaBiomes.paint(level, level.getChunk(chunkPos.x, chunkPos.z), site);
            }
            return false;
        }
        if (!cleanup) {
            HuaSurfaceStructures.suppress(level, level.getChunk(chunkPos.x, chunkPos.z), site);
        }
        if (!site.touchesChunk(chunkPos.x, chunkPos.z)) {
            return false;
        }
        if (cleanup) {
            MountHuaChunkWriter.cleanup(level, level.getChunk(chunkPos.x, chunkPos.z), site);
            // The author's finished sect over the shelf: the baked pieces on a canonical mountain (docs/adr/0004), else
            // the older captured location; then the Mount Hua biomes (docs/design/28-location-capture.md).
            if (!HuaBaked.apply(level, chunkPos, site)) {
                MountHuaOverlay.apply(level, chunkPos, site);
            }
            // The story places of the side quests (quest 1: Dry Stream Cave, quest 6: the ancestors' stele).
            // The baked trail keeps the old profile; its columns over the step limit are cut down (no rise a player cannot walk).
            HuaTrailMend.apply(level, chunkPos, site);
            // The head's main vault under the Main Hall, after the baked sect that it cuts into (same clip, docs/adr/0018).
            io.github.verycooltimo.murim.sect.vault.HuaVault.apply(level, chunkPos, site);
            io.github.verycooltimo.murim.quest.world.HuaDryStreamCave.apply(level, chunkPos, site);
            io.github.verycooltimo.murim.quest.world.HuaStele.apply(level, chunkPos, site);
            // What the vanilla features left hanging over the reshaped ground (bamboo, vines, trees without a trunk on the ground, snow).
            HuaFloaters.sweep(level, chunkPos, site);
            // The author's climb, the foot lake and the dorm staircase (docs/adr/0021): sparse pieces, the last word over their blocks.
            HuaBakedExtra.apply(level, chunkPos, site);
            HuaBiomes.paint(level, level.getChunk(chunkPos.x, chunkPos.z), site);
            MountHuaChunkWriter.clearSnow(level, level.getChunk(chunkPos.x, chunkPos.z), site);
            HuaHerbs.apply(level, chunkPos, site);
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
