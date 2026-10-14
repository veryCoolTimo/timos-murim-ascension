package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/** Small decorative props. Two variants selected by position; Shift places the mirrored lion.
 * Each concrete class has its own codec, preserving its shape and mounting rule on decode.
 * Models and resources: tools/art/sect_ornaments.py. Eight states per prop. */
public abstract class SectOrnamentBlock extends HorizontalDirectionalBlock {
    public static final IntegerProperty VARIANT = IntegerProperty.create("variant", 0, 1);
    private final VoxelShape northShape;
    private final int mounting; // 0 floor, 1 wall (behind FACING), 2 ceiling

    protected SectOrnamentBlock(Properties properties, VoxelShape shape, int mounting) {
        super(properties);
        this.northShape = shape;
        this.mounting = mounting;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(VARIANT, 0));
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction f = context.getHorizontalDirection().getOpposite();
        if (mounting == 1) {
            if (!context.getClickedFace().getAxis().isHorizontal()) return null;
            f = context.getClickedFace();
        }
        if (mounting == 2) {
            if (context.getClickedFace() != Direction.DOWN) return null;
            BlockState above = context.getLevel().getBlockState(context.getClickedPos().above());
            if (above.getBlock() instanceof io.github.verycooltimo.murim.world.build.RedBeamBlock) {
                Direction.Axis axis = above.getValue(io.github.verycooltimo.murim.world.build.RedBeamBlock.AXIS);
                if (f.getAxis() == axis) f = f.getClockWise();
            }
        }
        int variant = this instanceof Lion ? (context.isSecondaryUseActive() ? 1 : 0)
                : (int) Math.floorMod(net.minecraft.util.Mth.getSeed(context.getClickedPos()), 2L);
        BlockState state = defaultBlockState().setValue(FACING, f).setValue(VARIANT, variant);
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
    }

    @Override
    protected boolean canSurvive(BlockState state, net.minecraft.world.level.LevelReader level, BlockPos pos) {
        Direction support = mounting == 1 ? state.getValue(FACING).getOpposite() : mounting == 2 ? Direction.UP : Direction.DOWN;
        if (mounting == 2) return Block.canSupportCenter(level, pos.above(), Direction.DOWN);
        return level.getBlockState(pos.relative(support)).isFaceSturdy(level, pos.relative(support), support.getOpposite());
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level,
                                     BlockPos pos, BlockPos neighbourPos) {
        return state.canSurvive(level, pos) ? state : Blocks.AIR.defaultBlockState();
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Direction f = state.getValue(FACING);
        VoxelShape[] rotated = {net.minecraft.world.phys.shapes.Shapes.empty()};
        northShape.forAllBoxes((x0,y0,z0,x1,y1,z1) -> {
            VoxelShape box = switch (f) {
                case EAST -> Block.box((1-z1)*16,y0*16,x0*16,(1-z0)*16,y1*16,x1*16);
                case SOUTH -> Block.box((1-x1)*16,y0*16,(1-z1)*16,(1-x0)*16,y1*16,(1-z0)*16);
                case WEST -> Block.box(z0*16,y0*16,(1-x1)*16,z1*16,y1*16,(1-x0)*16);
                default -> Block.box(x0*16,y0*16,z0*16,x1*16,y1*16,z1*16);
            };
            rotated[0] = net.minecraft.world.phys.shapes.Shapes.or(rotated[0], box);
        });
        return rotated[0];
    }

    @Override protected boolean isPathfindable(BlockState state, PathComputationType type) { return false; }
    @Override protected BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state, Mirror mirror) {
        BlockState out = state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
        return this instanceof Lion && mirror != Mirror.NONE ? out.setValue(VARIANT, 1-state.getValue(VARIANT)) : out;
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING, VARIANT); }

    public static final class Lion extends SectOrnamentBlock {
        public static final MapCodec<Lion> CODEC = simpleCodec(Lion::new);
        public Lion(Properties properties) { super(properties, Block.box(1,0,1,15,16,15), 0); }
        @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return CODEC; }
    }

    public static final class Altar extends SectOrnamentBlock {
        public static final MapCodec<Altar> CODEC = simpleCodec(Altar::new);
        public Altar(Properties properties) { super(properties, Block.box(1,0,2,15,16,14), 0); }
        @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return CODEC; }
    }

    public static final class TeaTable extends SectOrnamentBlock {
        public static final MapCodec<TeaTable> CODEC = simpleCodec(TeaTable::new);
        public TeaTable(Properties properties) { super(properties, Block.box(1,0,2,15,9,14), 0); }
        @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return CODEC; }
    }

    public static final class Scroll extends SectOrnamentBlock {
        public static final MapCodec<Scroll> CODEC = simpleCodec(Scroll::new);
        public Scroll(Properties properties) { super(properties, Block.box(2,1.5,14.4,14,16,16), 1); }
        @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return CODEC; }
    }

    public static final class LanternString extends SectOrnamentBlock {
        public static final MapCodec<LanternString> CODEC = simpleCodec(LanternString::new);
        public LanternString(Properties properties) { super(properties, Block.box(0,6,6.3,16,16,9.7), 2); }
        @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return CODEC; }
    }

    public static final class Vat extends SectOrnamentBlock {
        public static final MapCodec<Vat> CODEC = simpleCodec(Vat::new);
        public Vat(Properties properties) { super(properties, Block.box(1.5,0,1.5,14.5,12.5,14.5), 0); }
        @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return CODEC; }
    }

    public static final class Stele extends SectOrnamentBlock {
        public static final MapCodec<Stele> CODEC = simpleCodec(Stele::new);
        public Stele(Properties properties) { super(properties, Block.box(2,0,3,14,15.5,13), 0); }
        @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return CODEC; }
    }

    public static final class Drum extends SectOrnamentBlock {
        public static final MapCodec<Drum> CODEC = simpleCodec(Drum::new);
        public Drum(Properties properties) { super(properties, Block.box(2.5,0,3,13.5,15.5,13), 0); }
        @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return CODEC; }
    }
}
