package io.github.verycooltimo.murim.world.location;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.neoforged.neoforge.common.world.PieceBeardifierModifier;

/**
 * A captured location as a structure piece: the whole template set at one origin and rotation, stamped chunk by
 * chunk ({@link LocationPlacer}). The terrain around it is pulled to the captured ground level by the beardifier
 * ({@link TerrainAdjustment#BEARD_THIN} on this piece only; the procedural pieces of the same structures keep
 * {@code NONE}), the footing fills what is left under the bottom layer.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/common/world/PieceBeardifierModifier.java,
 * reference/minecraft-src/net/minecraft/world/level/levelgen/Beardifier.java#forStructuresInChunk (per-piece box,
 * adjustment and ground delta), reference/minecraft-src/net/minecraft/world/level/levelgen/structure/StructurePiece.java.
 */
public class LocationTemplatePiece extends StructurePiece implements PieceBeardifierModifier {

    private final String location;
    private final BlockPos origin;
    private final Rotation rotation;
    private final int groundY;

    public LocationTemplatePiece(CapturedLocation loc, BlockPos origin, Rotation rotation) {
        super(ModLocations.TEMPLATE_PIECE.get(), 0, loc.box(origin, rotation));
        this.location = loc.id();
        this.origin = origin.immutable();
        this.rotation = rotation;
        this.groundY = loc.groundY();
    }

    public LocationTemplatePiece(StructurePieceSerializationContext context, CompoundTag tag) {
        super(ModLocations.TEMPLATE_PIECE.get(), tag);
        this.location = tag.getString("Location");
        this.origin = new BlockPos(tag.getInt("OX"), tag.getInt("OY"), tag.getInt("OZ"));
        this.rotation = Rotation.values()[Math.floorMod(tag.getInt("Rot"), 4)];
        this.groundY = tag.getInt("Ground");
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putString("Location", location);
        tag.putInt("OX", origin.getX());
        tag.putInt("OY", origin.getY());
        tag.putInt("OZ", origin.getZ());
        tag.putInt("Rot", rotation.ordinal());
        tag.putInt("Ground", groundY);
    }

    public String location() {
        return location;
    }

    public BlockPos origin() {
        return origin;
    }

    public Rotation placementRotation() {
        return rotation;
    }

    /** The manifest this piece was made from (null if the capture was deleted since). */
    public CapturedLocation captured() {
        return LocationTemplates.get(location);
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator,
            RandomSource random, BoundingBox chunkBox, ChunkPos chunkPos, BlockPos pivot) {
        CapturedLocation loc = LocationTemplates.get(level.getServer(), location);
        if (loc == null) {
            return;
        }
        LocationPlacer.place(level.getServer(), level, loc, origin, rotation, chunkBox, random, 2);
    }

    @Override
    public BoundingBox getBeardifierBox() {
        return boundingBox;
    }

    @Override
    public TerrainAdjustment getTerrainAdjustment() {
        return TerrainAdjustment.BEARD_THIN;
    }

    @Override
    public int getGroundLevelDelta() {
        return groundY;
    }
}
