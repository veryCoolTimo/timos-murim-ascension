package io.github.verycooltimo.murim.sect.vault;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The props of the pit (author 26.10, ref «두둥»): sacks (0, 1), a stack of boxes (2), bundles of paper (3), spears (4) and swords (5) leaning
 * against the wall behind them. {@code facing} is the side that looks into the room; the wall is on the opposite side. Models by tools/art/vault_pit.py.
 */
public class VaultPropBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<VaultPropBlock> CODEC = simpleCodec(VaultPropBlock::new);
    public static final IntegerProperty KIND = IntegerProperty.create("kind", 0, 5);
    /** For facing south: x0, y0, z0, x1, y1, z1 in px. */
    private static final int[][] BOXES = {{1, 0, 1, 15, 9, 15}, {1, 0, 1, 15, 10, 15}, {0, 0, 1, 16, 15, 15}, {0, 0, 1, 16, 10, 15}, {2, 0, 0, 14, 16, 5}, {2, 0, 0, 14, 16, 5}};
    private static final VoxelShape[][] SHAPES = new VoxelShape[6][4];

    static {
        for (int k = 0; k < BOXES.length; k++) {
            int[] b = BOXES[k];
            for (Direction d : Direction.Plane.HORIZONTAL) {
                VoxelShape s = Block.box(b[0], b[1], b[2], b[3], b[4], b[5]);
                for (int i = 0; i < d.get2DDataValue(); i++) {
                    VoxelShape next = Shapes.empty();
                    for (net.minecraft.world.phys.AABB a : s.toAabbs()) {
                        next = Shapes.or(next, Shapes.create(1 - a.maxZ, a.minY, a.minX, 1 - a.minZ, a.maxY, a.maxX));
                    }
                    s = next;
                }
                SHAPES[k][d.get2DDataValue()] = s;
            }
        }
    }

    public VaultPropBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH).setValue(KIND, 0));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, KIND);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state;
    }

    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, net.minecraft.world.level.Level level,
            BlockPos pos, net.minecraft.world.entity.player.Player player, net.minecraft.world.phys.BlockHitResult hit) {
        return VaultHarvest.use(level, pos, player);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(KIND)][state.getValue(FACING).get2DDataValue()];
    }
}
