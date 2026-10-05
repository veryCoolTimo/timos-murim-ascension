package io.github.verycooltimo.murim.world.camp;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Единственный кусок структуры лагеря: зерно плана, центр и опорные высоты построек. Блоки
 * ставит {@link CampBuilder} — по чанку за вызов {@link #postProcess}.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/levelgen/structure/StructurePiece.java
 * (#postProcess, #addAdditionalSaveData, SHAPE_CHECK_BLOCKS → markPosForPostprocessing),
 * reference/minecraft-src/net/minecraft/world/level/levelgen/structure/structures/SwampHutPiece.java (образец),
 * reference/minecraft-src/net/minecraft/world/RandomizableContainer.java#setBlockEntityLootTable.
 */
public class BanditCampPiece extends StructurePiece {

    private final long seed;
    private final int cx;
    private final int cz;
    private final int[] heights;

    public BanditCampPiece(long seed, int cx, int cz, int[] heights, BoundingBox box) {
        super(BanditCamp.PIECE.get(), 0, box);
        this.seed = seed;
        this.cx = cx;
        this.cz = cz;
        this.heights = heights.clone();
    }

    public BanditCampPiece(StructurePieceSerializationContext context, CompoundTag tag) {
        super(BanditCamp.PIECE.get(), tag);
        this.seed = tag.getLong("Seed");
        this.cx = tag.getInt("CX");
        this.cz = tag.getInt("CZ");
        this.heights = tag.getIntArray("Heights");
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putLong("Seed", seed);
        tag.putInt("CX", cx);
        tag.putInt("CZ", cz);
        tag.putIntArray("Heights", heights);
    }

    public long seed() {
        return seed;
    }

    public int centreX() {
        return cx;
    }

    public int centreZ() {
        return cz;
    }

    /** Опорная высота постройки {@code index} из плана (последняя — ворота). */
    public int height(int index) {
        return heights[Math.min(index, heights.length - 1)];
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator,
                            RandomSource random, BoundingBox chunkBox, ChunkPos chunkPos, BlockPos pivot) {
        CampLayout plan = CampLayout.plan(seed);
        if (heights.length != plan.spots().size() + 1) {
            return;
        }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        new CampBuilder(plan, cx, cz, heights, new CampBuilder.Sink() {
            @Override
            public boolean owns(int x, int z) {
                return x >= chunkBox.minX() && x <= chunkBox.maxX() && z >= chunkBox.minZ() && z <= chunkBox.maxZ();
            }

            @Override
            public int ground(int x, int z) {
                return level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1;
            }

            @Override
            public BlockState get(int x, int y, int z) {
                return level.getBlockState(m.set(x, y, z));
            }

            @Override
            public void set(int x, int y, int z, BlockState state) {
                m.set(x, y, z);
                level.setBlock(m, state, 2);
                if (CampBuilder.shapeChecked(state)) {
                    level.getChunk(m).markPosForPostprocessing(m);
                }
            }

            @Override
            public void loot(int x, int y, int z, ResourceKey<LootTable> table) {
                RandomizableContainer.setBlockEntityLootTable(level, random, new BlockPos(x, y, z), table);
            }

            @Override
            public void frame(int x, int y, int z, Direction facing, ItemStack item) {
                // API: reference/minecraft-src/net/minecraft/world/entity/decoration/ItemFrame.java#<init>(Level, BlockPos, Direction)
                ItemFrame frame = new ItemFrame(level.getLevel(), new BlockPos(x, y, z), facing);
                frame.setItem(item, false);
                level.addFreshEntity(frame);
            }

            @Override
            public void display(double x, double y, double z, float yaw, ItemStack item, float[] scale, float roll) {
                net.minecraft.world.entity.Entity d = CampBuilder.itemDisplay(level.getLevel(), x, y, z, yaw, item, scale, roll);
                if (d != null) {
                    level.addFreshEntity(d);
                }
            }
        }).build();
    }
}
