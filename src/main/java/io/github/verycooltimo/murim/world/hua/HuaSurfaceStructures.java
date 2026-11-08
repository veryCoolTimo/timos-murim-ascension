package io.github.verycooltimo.murim.world.hua;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/** Per-chunk placement suppression; never mutate starts shared by worldgen workers. See ADR-0001. */
final class HuaSurfaceStructures {
    private HuaSurfaceStructures() { }

    /**
     * API: Minecraft 1.21.1 ChunkAccess#getAllReferences/#setAllReferences and
     * StructureManager#forWorldGenRegion/#startsForStructure (official client mappings).
     * raw_generation precedes SURFACE_STRUCTURES in ChunkGenerator#applyBiomeDecoration.
     * Check the complete start box, including from chunks outside the mountain, so a portal or
     * village crossing the footprint cannot be half placed. Underground structures remain vanilla.
     */
    static void suppress(WorldGenLevel level, ChunkAccess chunk, MountHuaSite site) {
        if (!(level instanceof WorldGenRegion region) || chunk.getAllReferences().isEmpty()) return;
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        var manager = level.getLevel().structureManager().forWorldGenRegion(region);
        var starts = manager.startsForStructure(chunk.getPos(), structure -> {
            var key = registry.getKey(structure);
            return key != null && key.getNamespace().equals("minecraft")
                    && structure.step() == GenerationStep.Decoration.SURFACE_STRUCTURES;
        });
        var references = suppressReferences(chunk.getAllReferences(), site, starts);
        if (references != chunk.getAllReferences()) chunk.setAllReferences(references);
    }

    static Map<Structure, LongSet> suppressReferences(Map<Structure, LongSet> original, MountHuaSite site,
            List<StructureStart> starts) {
        var references = new HashMap<>(original);
        boolean changed = false;
        for (var start : starts) {
            if (!intersects(site, start.getBoundingBox())) continue;
            var structure = start.getStructure();
            if (!references.containsKey(structure)) continue;
            var remaining = new LongOpenHashSet(references.get(structure));
            remaining.remove(start.getChunkPos().toLong());
            references.put(structure, remaining);
            changed = true;
        }
        return changed ? references : original;
    }

    static boolean intersects(MountHuaSite site, BoundingBox box) {
        int minX = Math.max(site.minX(), box.minX());
        int maxX = Math.min(site.maxX(), box.maxX());
        int minZ = Math.max(site.minZ(), box.minZ());
        int maxZ = Math.min(site.maxZ(), box.maxZ());
        for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
            if (site.shape().blend(site.centerU(x, z), site.centerV(x, z)) > 0) return true;
        }
        return false;
    }
}
