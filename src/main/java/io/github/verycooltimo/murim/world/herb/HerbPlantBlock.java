package io.github.verycooltimo.murim.world.herb;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Found, never grown or replanted. Drops are unconditional item loot, including Silk Touch.
 * Block states and removal use vanilla block synchronization; there is no extra saved state.
 * API: project IntTabletopBlock (support/updateShape), PillBoxBlock (server useWithoutItem),
 * vanilla 1.21.1 BlockBehaviour (onRemove) and LevelEvent 1505 (green compost particles).
 */
public class HerbPlantBlock extends Block {
    public enum Kind { GINSENG, LINGZHI, BLACK }
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final MapCodec<HerbPlantBlock> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.STRING.xmap(Kind::valueOf, Kind::name).fieldOf("kind").forGetter(b -> b.kind),
            propertiesCodec()).apply(i, HerbPlantBlock::new));
    private final Kind kind;

    public HerbPlantBlock(Kind kind, Properties properties) {
        super(properties);
        this.kind = kind;
        // DOWN means a floor plant; horizontal values point towards the supporting log.
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.DOWN));
    }

    @Override protected MapCodec<? extends Block> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override protected BlockState rotate(BlockState state, net.minecraft.world.level.block.Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }
    @Override protected BlockState mirror(BlockState state, net.minecraft.world.level.block.Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }

    public static boolean soil(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.PODZOL) || state.is(Blocks.ROOTED_DIRT);
    }

    @Override protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction support = state.getValue(FACING);
        BlockPos base = pos.relative(support);
        BlockState floor = level.getBlockState(base);
        if (kind == Kind.BLACK) {
            return support == Direction.DOWN && floor.isFaceSturdy(level, base, Direction.UP);
        }
        if (support == Direction.DOWN) return soil(floor);
        return kind == Kind.LINGZHI && support.getAxis().isHorizontal()
                && (floor.is(Blocks.DARK_OAK_LOG) || floor.is(Blocks.SPRUCE_LOG));
    }

    @Override protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour,
            LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        return !state.canSurvive(level, pos) ? Blocks.AIR.defaultBlockState()
                : super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
    }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case NORTH -> box(2, 3, 0, 14, 11, 7);
            case SOUTH -> box(2, 3, 9, 14, 11, 16);
            case WEST -> box(0, 3, 2, 7, 11, 14);
            case EAST -> box(9, 3, 2, 16, 11, 14);
            default -> box(2, 0, 2, 14, 14, 14);
        };
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockState(pos).is(this)) level.destroyBlock(pos, true, player);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        super.onRemove(state, level, pos, next, moving);
        if (!level.isClientSide && !next.is(this)) {
            level.playSound(null, pos, SoundEvents.GRASS_BREAK, SoundSource.BLOCKS, 0.45F, 1.1F);
            // Vanilla event supplies a small green puff on clients, with no custom particle loop.
            level.levelEvent(1505, pos, 0);
        }
    }
}
