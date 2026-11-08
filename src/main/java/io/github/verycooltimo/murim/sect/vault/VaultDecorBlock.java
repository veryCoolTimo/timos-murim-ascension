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
 * The relic decor of the pit hall (round 9; models and textures by tools/art/vault_relics.py): {@code kind} 0 and 1 the black lacquer sword racks with
 * three relic Mount Hua swords each, 2 the plaque above a rack, 3 to 6 relic swords leaning on the wall, 7 and 8 the red cloth banners that hang from the
 * beams. Models may reach beyond the cell (a real-size sword is two blocks long); the shape stays inside it. {@code facing} is the side that looks into
 * the room.
 */
public class VaultDecorBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<VaultDecorBlock> CODEC = simpleCodec(VaultDecorBlock::new);
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty TAKEN =
            net.minecraft.world.level.block.state.properties.BooleanProperty.create("taken");
    public static final IntegerProperty KIND = IntegerProperty.create("kind", 0, 9);
    public static final int RACK_A = 0, RACK_B = 1, PLAQUE = 2, LEAN_0 = 3, BANNER_RED = 7, BANNER_MAROON = 8, HERO = 9;
    /** South-facing boxes in px: x0, y0, z0, x1, y1, z1. */
    private static final int[][] BOXES = {{0, 0, 1, 16, 16, 9}, {0, 0, 1, 16, 16, 9}, {0, 4, 0, 16, 12, 2}, {4, 0, 2, 12, 16, 8}, {4, 0, 2, 12, 16, 8}, {4, 0, 2, 12, 16, 8},
            {4, 0, 2, 12, 16, 8}, {3, 0, 0, 13, 16, 2}, {3, 0, 0, 13, 16, 2}, {2, 0, 2, 14, 16, 14}};
    private static final VoxelShape[][] SHAPES = new VoxelShape[BOXES.length][4];

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

    public VaultDecorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH).setValue(KIND, 0).setValue(TAKEN, false));
    }

    public static BlockState of(Block block, int kind, Direction facing) {
        return block.defaultBlockState().setValue(KIND, kind).setValue(FACING, facing);
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, KIND, TAKEN);
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

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        int k = state.getValue(KIND);
        return k == PLAQUE || k >= BANNER_RED ? Shapes.empty() : getShape(state, level, pos, context);
    }
}
