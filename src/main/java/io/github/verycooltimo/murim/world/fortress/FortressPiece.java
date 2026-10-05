package io.github.verycooltimo.murim.world.fortress;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.neoforged.neoforge.common.world.PieceBeardifierModifier;

/**
 * Единственный кусок крепости: центр плаца, высота пола, зерно. Блоки ставит
 * {@link FortressBuilder} — по чанку за вызов {@link #postProcess}.
 *
 * <p>API: как у лагеря (world/camp/BanditCampPiece) —
 * reference/minecraft-src/net/minecraft/world/level/levelgen/structure/StructurePiece.java#postProcess.
 */
public class FortressPiece extends StructurePiece implements PieceBeardifierModifier {

    private final long seed;
    private final int cx;
    private final int y0;
    private final int cz;
    /**
     * Оболочка шаблонной крепости (docs/design/28-location-capture.md): блоки ставит шаблон автора, кусок хранит
     * плац и поворот для жизни крепости. null — процедурная крепость, поворот из зерна.
     */
    private final Rotation shellRotation;

    public FortressPiece(long seed, int cx, int y0, int cz, BoundingBox box) {
        this(seed, cx, y0, cz, box, null);
    }

    private FortressPiece(long seed, int cx, int y0, int cz, BoundingBox box, Rotation shellRotation) {
        super(Fortress.PIECE.get(), 0, box);
        this.seed = seed;
        this.cx = cx;
        this.y0 = y0;
        this.cz = cz;
        this.shellRotation = shellRotation;
    }

    /** Оболочка шаблонной крепости: плац (пол), поворот захваченной крепости с поворотом шаблона. */
    public static FortressPiece shell(long seed, BlockPos yard, Rotation rotation, BoundingBox box) {
        return new FortressPiece(seed, yard.getX(), yard.getY(), yard.getZ(), box, rotation);
    }

    public FortressPiece(StructurePieceSerializationContext context, CompoundTag tag) {
        super(Fortress.PIECE.get(), tag);
        this.seed = tag.getLong("Seed");
        this.cx = tag.getInt("CX");
        this.y0 = tag.getInt("Y0");
        this.cz = tag.getInt("CZ");
        this.shellRotation = tag.contains("ShellRot") ? Rotation.values()[Math.floorMod(tag.getInt("ShellRot"), 4)] : null;
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putLong("Seed", seed);
        tag.putInt("CX", cx);
        tag.putInt("Y0", y0);
        tag.putInt("CZ", cz);
        if (shellRotation != null) {
            tag.putInt("ShellRot", shellRotation.ordinal());
        }
    }

    /** Крепость из шаблона автора (кусок без блоков). */
    public boolean shell() {
        return shellRotation != null;
    }

    @Override
    public BoundingBox getBeardifierBox() {
        return boundingBox;
    }

    @Override
    public TerrainAdjustment getTerrainAdjustment() {
        return TerrainAdjustment.NONE;
    }

    @Override
    public int getGroundLevelDelta() {
        return 0;
    }

    public long seed() {
        return seed;
    }

    public int centreX() {
        return cx;
    }

    public int floorY() {
        return y0;
    }

    public int centreZ() {
        return cz;
    }

    public Rotation rotation() {
        return shellRotation != null ? shellRotation : FortressBuilder.rotation(seed);
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator,
                            RandomSource random, BoundingBox chunkBox, ChunkPos chunkPos, BlockPos pivot) {
        if (shellRotation != null) {
            return;
        }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        new FortressBuilder(new FortressBuilder.Sink() {
            @Override
            public boolean owns(int x, int z) {
                return x >= chunkBox.minX() && x <= chunkBox.maxX() && z >= chunkBox.minZ() && z <= chunkBox.maxZ();
            }

            @Override
            public BlockState get(int x, int y, int z) {
                return level.getBlockState(m.set(x, y, z));
            }

            @Override
            public void set(int x, int y, int z, BlockState state) {
                m.set(x, y, z);
                level.setBlock(m, state, 2);
                // Заборы и решётки смыкаются с соседями после генерации.
                if (state.getBlock() instanceof net.minecraft.world.level.block.FenceBlock
                        || state.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock) {
                    level.getChunk(m).markPosForPostprocessing(m);
                }
            }
        }, cx, y0, cz, rotation(), seed).build();
    }
}
