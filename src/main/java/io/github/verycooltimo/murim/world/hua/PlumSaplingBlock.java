package io.github.verycooltimo.murim.world.hua;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Plum sapling, one per tree variant: grows like a vanilla sapling (two stages on random ticks in
 * light, or bone meal) into a {@link PlumTree} of its variant. The cliff sapling also takes root
 * on bare stone and leans its tree toward the lowest side.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/SaplingBlock.java (same flow:
 * STAGE, randomTick, advanceTree, bone meal 45%).
 */
public class PlumSaplingBlock extends BushBlock implements BonemealableBlock {

    public static final MapCodec<PlumSaplingBlock> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            PlumTree.Variant.CODEC.fieldOf("variant").forGetter(PlumSaplingBlock::variant), propertiesCodec())
            .apply(i, PlumSaplingBlock::new));
    public static final IntegerProperty STAGE = BlockStateProperties.STAGE;
    private static final VoxelShape SHAPE = Block.box(2.0, 0.0, 2.0, 14.0, 12.0, 14.0);

    private final PlumTree.Variant variant;

    public PlumSaplingBlock(PlumTree.Variant variant, BlockBehaviour.Properties properties) {
        super(properties);
        this.variant = variant;
        registerDefaultState(stateDefinition.any().setValue(STAGE, 0));
    }

    public PlumTree.Variant variant() {
        return variant;
    }

    @Override
    protected MapCodec<? extends BushBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
        return super.mayPlaceOn(state, level, pos)
                || (variant == PlumTree.Variant.CLIFF && state.is(BlockTags.BASE_STONE_OVERWORLD));
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!level.isAreaLoaded(pos, 1)) {
            return;
        }
        if (level.getMaxLocalRawBrightness(pos.above()) >= 9 && random.nextInt(7) == 0) {
            advance(level, pos, state, random);
        }
    }

    /** Stage 0 → 1, then the tree. Returns true when a tree grew. */
    public boolean advance(ServerLevel level, BlockPos pos, BlockState state, RandomSource random) {
        if (state.getValue(STAGE) == 0) {
            level.setBlock(pos, state.cycle(STAGE), 4);
            return false;
        }
        Direction lean = variant == PlumTree.Variant.CLIFF ? lowestSide(level, pos) : null;
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 4);
        if (!PlumTree.grow(level, pos, variant, variant.defaultPalette(), random.nextLong(), lean, 3)) {
            level.setBlock(pos, state, 4);
            return false;
        }
        return true;
    }

    /** The horizontal side whose ground three blocks out lies lowest (over the drop). */
    static Direction lowestSide(LevelReader level, BlockPos pos) {
        Direction best = Direction.NORTH;
        int deepest = -1;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos.MutableBlockPos p = pos.relative(d, 3).mutable();
            int drop = 0;
            while (drop < 12 && level.getBlockState(p.below()).isAir()) {
                p.move(Direction.DOWN);
                drop++;
            }
            if (drop > deepest) {
                deepest = drop;
                best = d;
            }
        }
        return best;
    }

    @Override
    public boolean isValidBonemealTarget(LevelReader level, BlockPos pos, BlockState state) {
        return true;
    }

    @Override
    public boolean isBonemealSuccess(Level level, RandomSource random, BlockPos pos, BlockState state) {
        return level.random.nextFloat() < 0.45F;
    }

    @Override
    public void performBonemeal(ServerLevel level, RandomSource random, BlockPos pos, BlockState state) {
        advance(level, pos, state, random);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(STAGE);
    }
}
