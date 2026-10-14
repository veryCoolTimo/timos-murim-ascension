package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Sixteen states, connecting across facing like a fence. End posts disappear at joints. */
public final class FoldingScreenBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<FoldingScreenBlock> CODEC = simpleCodec(FoldingScreenBlock::new);
    public static final BooleanProperty LEFT = BooleanProperty.create("left");
    public static final BooleanProperty RIGHT = BooleanProperty.create("right");
    public FoldingScreenBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LEFT,false).setValue(RIGHT,false));
    }
    @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return CODEC; }
    private boolean joins(BlockState state, BlockGetter level, BlockPos pos, Direction dir) {
        BlockState n = level.getBlockState(pos.relative(dir));
        return n.is(this) && n.getValue(FACING).getAxis() == state.getValue(FACING).getAxis();
    }
    private BlockState connect(BlockState s, BlockGetter level, BlockPos pos) {
        Direction f=s.getValue(FACING);
        return s.setValue(LEFT,joins(s,level,pos,f.getCounterClockWise())).setValue(RIGHT,joins(s,level,pos,f.getClockWise()));
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext c) {
        BlockState s=defaultBlockState().setValue(FACING,c.getHorizontalDirection().getOpposite());
        return s.canSurvive(c.getLevel(),c.getClickedPos()) ? connect(s,c.getLevel(),c.getClickedPos()) : null;
    }
    @Override protected boolean canSurvive(BlockState s, LevelReader level, BlockPos pos) {
        return level.getBlockState(pos.below()).isFaceSturdy(level,pos.below(),Direction.UP);
    }
    @Override protected BlockState updateShape(BlockState s, Direction d, BlockState n, LevelAccessor level, BlockPos p, BlockPos np) {
        return s.canSurvive(level,p) ? connect(s,level,p) : Blocks.AIR.defaultBlockState();
    }
    @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
        return s.getValue(FACING).getAxis()==Direction.Axis.Z ? Block.box(0,0,5,16,16,11) : Block.box(5,0,0,11,16,16);
    }
    @Override protected BlockState rotate(BlockState s, Rotation r) { return s.setValue(FACING,r.rotate(s.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState s, Mirror m) {
        return m==Mirror.NONE ? s : s.setValue(FACING,m.mirror(s.getValue(FACING))).setValue(LEFT,s.getValue(RIGHT)).setValue(RIGHT,s.getValue(LEFT));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b) { b.add(FACING,LEFT,RIGHT); }
}
