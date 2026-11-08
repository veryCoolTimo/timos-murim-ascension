package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/** Table decorations follow the support height, with empty collision and a separate selection shape. */
public class IntTabletopBlock extends HorizontalDirectionalBlock {
    public enum Kind implements StringRepresentable {
        TABLEWARE, TEA, ABACUS, SCALES, JARS, PILL_BOX;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static final MapCodec<IntTabletopBlock> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            propertiesCodec(), StringRepresentable.fromEnum(Kind::values).fieldOf("kind").forGetter(b -> b.kind)
    ).apply(i, IntTabletopBlock::new));
    protected final Kind kind;

    public IntTabletopBlock(Properties properties, Kind kind) {
        super(properties);
        this.kind = kind;
        BlockState state = stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(TabletopSupport.SINK, 0);
        IntegerProperty variant = variantProperty();
        registerDefaultState(variant == null ? state : state.setValue(variant, 0));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    protected @Nullable IntegerProperty variantProperty() {
        return null;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = TabletopSupport.onSupport(defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite()), context.getLevel(), context.getClickedPos());
        IntegerProperty variant = variantProperty();
        return variant == null ? state : state.setValue(variant,
                Math.floorMod(net.minecraft.util.Mth.getSeed(context.getClickedPos()), variant.getPossibleValues().size()));
    }

    // No use method on purpose (author 19.10): the look of a tea tray, tableware or jars is chosen when it is placed (a seed of the
    // position, see getStateForPlacement) and stays. A right click used to cycle the variants; now it does nothing at all.

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Direction f = state.getValue(FACING);
        return TabletopSupport.lower(switch (kind) {
            case TABLEWARE -> StoShapes.turn(1, 0, 1.8, 15, 3.2, 14, f);
            case TEA -> StoShapes.turn(1, 0, 2, 15, 6, 14, f);
            case ABACUS -> StoShapes.turn(1, 0, 1, 15, 3, 15, f);
            case SCALES -> Shapes.or(StoShapes.turn(1, 0, 3, 10, 10.7, 12, f),
                    StoShapes.turn(10, 0, 3, 15.5, 6, 11, f));
            case JARS -> StoShapes.turn(1, 0, 2, 15, 8, 14, f);
            // Black lacquer pill box, closed (models/block/int_pill_box.json + the lid): 6.2 x 5.7 px, 5 px to the top of the medallion.
            case PILL_BOX -> StoShapes.turn(4.9, 0, 5.1, 11.1, 5.0, 10.8, f);
        }, state);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos) {
        return dir == Direction.DOWN ? TabletopSupport.onSupport(state, level, pos)
                : super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return true;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, TabletopSupport.SINK);
        IntegerProperty variant = variantProperty();
        if (variant != null) {
            builder.add(variant);
        }
    }

    public static final class ThreeVariants extends IntTabletopBlock {
        public static final IntegerProperty VARIANT = IntegerProperty.create("variant", 0, 2);
        public static final MapCodec<ThreeVariants> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                propertiesCodec(), StringRepresentable.fromEnum(Kind::values).fieldOf("kind").forGetter(b -> b.kind)
        ).apply(i, ThreeVariants::new));

        public ThreeVariants(Properties properties, Kind kind) {
            super(properties, kind);
        }

        @Override
        protected IntegerProperty variantProperty() {
            return VARIANT;
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }
    }

    public static final class TeaTray extends IntTabletopBlock {
        public static final IntegerProperty VARIANT = IntegerProperty.create("variant", 0, 1);
        public static final MapCodec<TeaTray> CODEC = simpleCodec(TeaTray::new);

        public TeaTray(Properties properties) {
            super(properties, Kind.TEA);
        }

        /** int_tea_table and its round twin already carry a tea set in their model: a tray on top would float a second one. */
        public static boolean ownsTeaSet(BlockState support) {
            return support.is(IntSeating.TEA_TABLE.get()) || support.is(IntSeating.TEA_TABLE_ROUND.get());
        }

        @Override
        protected boolean canSurvive(BlockState state, net.minecraft.world.level.LevelReader level, BlockPos pos) {
            return !ownsTeaSet(level.getBlockState(pos.below()));
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            BlockState state = super.getStateForPlacement(context);
            return state == null || !canSurvive(state, context.getLevel(), context.getClickedPos()) ? null : state;
        }

        @Override
        protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                         BlockPos pos, BlockPos neighbourPos) {
            if (dir == Direction.DOWN && ownsTeaSet(neighbour)) {
                return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            }
            return super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
        }

        @Override
        protected IntegerProperty variantProperty() {
            return VARIANT;
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }
    }
}
