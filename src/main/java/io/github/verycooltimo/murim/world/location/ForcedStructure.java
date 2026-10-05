package io.github.verycooltimo.murim.world.location;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;

/**
 * A structure placed at a chosen spot of an already generated world (showcase world, tests), registered the way
 * world generation registers it: the start in its chunk, a reference in every chunk it covers. Then
 * {@code /locate}-free lookups that walk chunk references — camp life, fortress life, {@code /murim capture} —
 * find it like any generated one. Unlike vanilla {@code /place structure} (which only stamps blocks).
 *
 * <p>API: reference/minecraft-src/net/minecraft/server/commands/PlaceCommand.java#placeStructure (per-chunk
 * placeInChunk with a full-height box), reference/minecraft-src/net/minecraft/world/level/chunk/ChunkAccess.java
 * (#setStartForStructure, #addReferenceForStructure — both mark the chunk unsaved).
 */
public final class ForcedStructure {

    private ForcedStructure() {
    }

    /** Builds and registers a start made of {@code pieces}; returns it. */
    public static StructureStart place(ServerLevel level, ResourceKey<Structure> key, List<StructurePiece> pieces) {
        Holder<Structure> structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getHolderOrThrow(key);
        BoundingBox first = pieces.get(0).getBoundingBox();
        ChunkPos startChunk = new ChunkPos(new BlockPos(first.getCenter().getX(), 0, first.getCenter().getZ()));
        StructureStart start = new StructureStart(structure.value(), startChunk, 0, new PiecesContainer(pieces));
        BoundingBox box = start.getBoundingBox();
        long ref = startChunk.toLong();
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                ChunkPos cp = new ChunkPos(cx, cz);
                LevelChunk chunk = level.getChunk(cx, cz);
                start.placeInChunk(level, level.structureManager(), level.getChunkSource().getGenerator(), level.getRandom(),
                        new BoundingBox(cp.getMinBlockX(), level.getMinBuildHeight(), cp.getMinBlockZ(), cp.getMaxBlockX(),
                                level.getMaxBuildHeight() - 1, cp.getMaxBlockZ()), cp);
                chunk.addReferenceForStructure(structure.value(), ref);
            }
        }
        level.getChunk(startChunk.x, startChunk.z).setStartForStructure(structure.value(), start);
        return start;
    }
}
