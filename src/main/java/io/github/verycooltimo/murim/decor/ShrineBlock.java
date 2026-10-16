package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * One-block shrine decor (altar, candle stand, incense tripod, dragon pillar segments, portrait frame, offering table).
 * FACING is where the front looks; wall items (portrait frame) take the clicked wall side. Smoke and flame particles are
 * ambient client decor drawn in {@link #animateTick} like the vanilla torch and campfire. Models: tools/art/shrine_set.py.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/Block.java#animateTick, TorchBlock.java#animateTick.
 */
public class ShrineBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<ShrineBlock> CODEC = simpleCodec(p -> new ShrineBlock(p, ShrineSpec.DUMMY));

    protected final ShrineSpec spec;
    private final VoxelShape[][][] shapes;

    public ShrineBlock(Properties properties, ShrineSpec spec) {
        super(properties);
        this.spec = spec;
        this.shapes = buildShapes(spec);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    /** [col][row][facing 2D index]: the model-wide boxes cut per block and turned for each facing. */
    static VoxelShape[][][] buildShapes(ShrineSpec spec) {
        VoxelShape[][][] out = new VoxelShape[spec.width()][spec.height()][4];
        for (int c = 0; c < spec.width(); c++) {
            for (int r = 0; r < spec.height(); r++) {
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    VoxelShape s = Shapes.empty();
                    for (double[] b : spec.boxes()) {
                        double x0 = Math.max(b[0], 16 * c) - 16 * c, x1 = Math.min(b[3], 16 * c + 16) - 16 * c;
                        double y0 = Math.max(b[1], 16 * r) - 16 * r, y1 = Math.min(b[4], 16 * r + 16) - 16 * r;
                        if (x1 - x0 > 1e-6 && y1 - y0 > 1e-6) {
                            s = Shapes.or(s, HallFurniture.turn(x0, y0, b[2], x1, y1, b[5], d));
                        }
                    }
                    out[c][r][d.get2DDataValue()] = s;
                }
            }
        }
        return out;
    }

    /** Ambient particles for the block whose cell row is {@code row} (the one holding the emission height). */
    static void ambient(ShrineSpec spec, int row, Level level, BlockPos pos, RandomSource random) {
        if (spec.fx() == ShrineSpec.Fx.NONE || (int) Math.floor(spec.fxY() / 16.0 - 1e-6) != row) {
            return;
        }
        double x = pos.getX() + 0.5, z = pos.getZ() + 0.5, y = pos.getY() + (spec.fxY() - 16 * row) / 16.0;
        switch (spec.fx()) {
            case CANDLE -> {
                if (random.nextInt(3) == 0) {
                    level.addParticle(ParticleTypes.SMALL_FLAME, x, y - 0.03, z, 0, 0, 0);
                }
                if (random.nextInt(14) == 0) {
                    level.addParticle(ParticleTypes.SMOKE, x, y + 0.05, z, 0, 0.01, 0);
                }
            }
            case INCENSE -> {
                for (int i = 0; i < 3; i++) {
                    if (random.nextInt(3) == 0) {
                        level.addParticle(ParticleTypes.WHITE_SMOKE, x + (random.nextDouble() - 0.5) * 0.3, y,
                                z + (random.nextDouble() - 0.5) * 0.3, (random.nextDouble() - 0.5) * 0.004D,
                                0.012D + random.nextDouble() * 0.01D, (random.nextDouble() - 0.5) * 0.004D);
                    }
                }
            }
            default -> { }
        }
    }

    static Direction placeFacing(ShrineSpec spec, BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        if (spec.wall() && face.getAxis().isHorizontal()) {
            return face;
        }
        return context.getHorizontalDirection().getOpposite();
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, placeFacing(spec, context));
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        ambient(spec, 0, level, pos, random);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes[0][0][state.getValue(FACING).get2DDataValue()];
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }
}
