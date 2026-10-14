package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModCreativeTabs;
import io.github.verycooltimo.murim.world.build.RedPillarBlock;
import io.github.verycooltimo.murim.world.build.TimberOffset;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Missing pieces of the three-bay memorial gate (build2/ref_07); art: tools/art/pailou.py. */
public final class Pailou {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MurimMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MurimMod.MODID);

    public static final DeferredBlock<RoofCap> PAILOU_ROOF_CAP = BLOCKS.registerBlock(
            "pailou_roof_cap", RoofCap::new, stone(MapColor.DEEPSLATE));
    public static final DeferredBlock<RoofEnd> PAILOU_ROOF_END = BLOCKS.registerBlock(
            "pailou_roof_end", RoofEnd::new, stone(MapColor.DEEPSLATE));
    public static final DeferredBlock<ArchitraveBand> PAILOU_ARCHITRAVE_BAND = BLOCKS.registerBlock(
            "pailou_architrave_band", ArchitraveBand::new, BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_RED).instrument(NoteBlockInstrument.BASS)
                    .strength(2.0F, 3.0F).sound(SoundType.WOOD).noOcclusion().ignitedByLava());
    public static final DeferredBlock<PostBase> PAILOU_POST_BASE = BLOCKS.registerBlock(
            "pailou_post_base", PostBase::new, stone(MapColor.TERRACOTTA_WHITE));

    public static final DeferredItem<BlockItem> PAILOU_ROOF_CAP_ITEM = ITEMS.registerSimpleBlockItem(PAILOU_ROOF_CAP);
    public static final DeferredItem<BlockItem> PAILOU_ROOF_END_ITEM = ITEMS.registerSimpleBlockItem(PAILOU_ROOF_END);
    public static final DeferredItem<BlockItem> PAILOU_ARCHITRAVE_BAND_ITEM = ITEMS.registerSimpleBlockItem(PAILOU_ARCHITRAVE_BAND);
    public static final DeferredItem<BlockItem> PAILOU_POST_BASE_ITEM = ITEMS.registerSimpleBlockItem(PAILOU_POST_BASE);

    private Pailou() {
    }

    private static BlockBehaviour.Properties stone(MapColor colour) {
        return BlockBehaviour.Properties.of().mapColor(colour).instrument(NoteBlockInstrument.BASEDRUM)
                .requiresCorrectToolForDrops().strength(1.5F, 6.0F).sound(SoundType.STONE).noOcclusion();
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(Pailou::tabs);
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(ModCreativeTabs.MURIM.getKey())) {
            event.accept(PAILOU_ROOF_CAP_ITEM.get());
            event.accept(PAILOU_ROOF_END_ITEM.get());
            event.accept(PAILOU_ARCHITRAVE_BAND_ITEM.get());
            event.accept(PAILOU_POST_BASE_ITEM.get());
        }
    }

    /** Full-length cross-sections meet at the cell boundary, with no joint rings or extra states. */
    public abstract static class RowPiece extends Block {
        public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;

        protected RowPiece(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.X));
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            Direction face = context.getClickedFace();
            BlockState support = context.getLevel().getBlockState(context.getClickedPos().relative(face.getOpposite()));
            // Same idiom as RedBeamBlock: side clicks extend along the clicked axis.
            Direction.Axis axis = face.getAxis().isHorizontal() ? face.getAxis()
                    : support.getBlock() == this ? support.getValue(AXIS)
                    : context.getHorizontalDirection().getClockWise().getAxis();
            return defaultBlockState().setValue(AXIS, axis);
        }

        @Override
        protected BlockState rotate(BlockState state, Rotation rotation) {
            return state.setValue(AXIS, RoofBlocks.rotate(state.getValue(AXIS), rotation));
        }

        @Override
        protected boolean skipRendering(BlockState state, BlockState neighbour, Direction face) {
            return (face.getAxis() == state.getValue(AXIS) && neighbour.getBlock() == this
                    && neighbour.getValue(AXIS) == state.getValue(AXIS))
                    || super.skipRendering(state, neighbour, face);
        }

        @Override
        protected boolean isPathfindable(BlockState state, PathComputationType type) {
            return false;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(AXIS);
        }
    }

    /** Conservative shell/bracket selection and collision, including the visible overhang.
     * Coordinates match tools/art/pailou.py: 32 px deep, rounded ridge at 24 px.
     * Keep the attic hollow instead of using a solid cube under the slopes.
     */
    private static VoxelShape roofShape(Direction facing, boolean end) {
        double[] xs = end ? new double[]{0, 5, 8.75, 12.5, 16.25, 20} : new double[]{0, 16};
        VoxelShape shape = Shapes.empty();
        for (int i = 0; i < xs.length - 1; i++) {
            for (int z = -8; z < 24; z += 4) {
                double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
                for (double x : new double[]{xs[i], xs[i + 1]}) {
                    for (double cross : new double[]{z, z + 4}) {
                        double t = Math.abs(cross - 8) / 16;
                        double hip = end ? Math.max(0, (x - 5) / 15) : 0;
                        double top = 8 + 13 * Math.pow(1 - Math.max(t, hip), 2)
                                + (end ? 10 * Math.pow(x / 20, 2.5) * Math.pow(t, 3) : 0);
                        low = Math.min(low, top - (end ? 3.1 : 2.1));
                        high = Math.max(high, top);
                    }
                }
                shape = Shapes.or(shape, roofBox(facing, xs[i], low, z, xs[i + 1], high, z + 4));
            }
        }
        shape = Shapes.or(shape, roofBox(facing, 0, 20, 6, end ? 5 : 16, end ? 24.6 : 24, 10));
        if (end) {
            shape = Shapes.or(shape, roofBox(facing, 0, 0.5, 6, 7, 7, 10));
        } else {
            shape = Shapes.or(shape, roofBox(facing, 0, 0, 6, 16, 4, 10),
                    roofBox(facing, 6, 0, 4.5, 10, 3, 11.5),
                    roofBox(facing, 6, 1, -7, 10, 6.4, 23));
        }
        return shape.optimize();
    }

    private static VoxelShape roofBox(Direction facing, double x0, double y0, double z0,
                                      double x1, double y1, double z1) {
        return switch (facing) {
            case WEST -> Block.box(16 - x1, y0, 16 - z1, 16 - x0, y1, 16 - z0);
            case SOUTH -> Block.box(16 - z1, y0, x0, 16 - z0, y1, x1);
            case NORTH -> Block.box(z0, y0, 16 - x1, z1, y1, 16 - x0);
            default -> Block.box(x0, y0, z0, x1, y1, z1);
        };
    }

    /** Both concave slopes, deep soffit and rounded ridge; one anchor cell along AXIS. */
    public static class RoofCap extends RowPiece {
        public static final MapCodec<RoofCap> CODEC = simpleCodec(RoofCap::new);
        private static final VoxelShape X = roofShape(Direction.EAST, false);
        private static final VoxelShape Z = roofShape(Direction.SOUTH, false);

        public RoofCap(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return CODEC;
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return state.getValue(AXIS) == Direction.Axis.X ? X : Z;
        }
    }

    /** Hipped end with two tiled flying corners. Two ends complete all four corners.
     * Clicking the end of a cap row picks the outward FACING from that face.
     */
    public static class RoofEnd extends HorizontalDirectionalBlock {
        public static final MapCodec<RoofEnd> CODEC = simpleCodec(RoofEnd::new);
        private static final VoxelShape[] SHAPES = new VoxelShape[4];

        static {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                SHAPES[direction.get2DDataValue()] = roofShape(direction, true);
            }
        }

        public RoofEnd(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.EAST));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
            return CODEC;
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            Direction face = context.getClickedFace();
            BlockState support = context.getLevel().getBlockState(context.getClickedPos().relative(face.getOpposite()));
            Direction outward = context.getHorizontalDirection().getOpposite();
            if (face.getAxis().isHorizontal() && support.getBlock() instanceof RoofCap
                    && support.getValue(RowPiece.AXIS) == face.getAxis()) {
                outward = face;
            }
            return defaultBlockState().setValue(FACING, outward);
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return SHAPES[state.getValue(FACING).get2DDataValue()];
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

    /** Thick red beam, shallow teal-and-gold field on both long faces. */
    public static class ArchitraveBand extends RowPiece {
        public static final MapCodec<ArchitraveBand> CODEC = simpleCodec(ArchitraveBand::new);
        public static final BooleanProperty NEGATIVE = BooleanProperty.create("negative");
        public static final BooleanProperty POSITIVE = BooleanProperty.create("positive");
        private static final VoxelShape X = box(0, 0, 4, 16, 16, 12);
        private static final VoxelShape Z = box(4, 0, 0, 12, 16, 16);
        private static final VoxelShape X_NEG = box(-5.5, 0, 4, 0, 16, 12);
        private static final VoxelShape X_POS = box(16, 0, 4, 21.5, 16, 12);
        private static final VoxelShape Z_NEG = box(4, 0, -5.5, 12, 16, 0);
        private static final VoxelShape Z_POS = box(4, 0, 16, 12, 16, 21.5);

        public ArchitraveBand(Properties properties) {
            super(properties);
            registerDefaultState(defaultBlockState().setValue(NEGATIVE, false).setValue(POSITIVE, false));
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return CODEC;
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            boolean x = state.getValue(AXIS) == Direction.Axis.X;
            VoxelShape shape = x ? X : Z;
            if (state.getValue(NEGATIVE)) {
                shape = Shapes.or(shape, x ? X_NEG : Z_NEG);
            }
            if (state.getValue(POSITIVE)) {
                shape = Shapes.or(shape, x ? X_POS : Z_POS);
            }
            return shape;
        }

        private static boolean pillar(BlockState state) {
            return state.getBlock() instanceof RedPillarBlock && state.getValue(RedPillarBlock.AXIS) == Direction.Axis.Y
                    && state.getValue(RedPillarBlock.OFFSET) == TimberOffset.CENTER;
        }

        private BlockState connect(BlockState state, BlockGetter level, BlockPos pos) {
            Direction positive = state.getValue(AXIS) == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
            return state.setValue(NEGATIVE, pillar(level.getBlockState(pos.relative(positive.getOpposite()))))
                    .setValue(POSITIVE, pillar(level.getBlockState(pos.relative(positive))));
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            return connect(super.getStateForPlacement(context), context.getLevel(), context.getClickedPos());
        }

        @Override
        protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbour,
                                         LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
            return connect(state, level, pos);
        }

        @Override
        protected BlockState rotate(BlockState state, Rotation rotation) {
            BlockState rotated = super.rotate(state, rotation);
            boolean swap = rotation == Rotation.CLOCKWISE_180
                    || rotation == Rotation.CLOCKWISE_90 && state.getValue(AXIS) == Direction.Axis.Z
                    || rotation == Rotation.COUNTERCLOCKWISE_90 && state.getValue(AXIS) == Direction.Axis.X;
            return swap ? swapped(rotated) : rotated;
        }

        private static BlockState swapped(BlockState state) {
            return state.setValue(NEGATIVE, state.getValue(POSITIVE)).setValue(POSITIVE, state.getValue(NEGATIVE));
        }

        @Override
        protected BlockState mirror(BlockState state, Mirror mirror) {
            return mirror == Mirror.LEFT_RIGHT && state.getValue(AXIS) == Direction.Axis.Z
                    || mirror == Mirror.FRONT_BACK && state.getValue(AXIS) == Direction.Axis.X ? swapped(state) : state;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(NEGATIVE, POSITIVE);
        }
    }

    /** Octagonal stone drum and narrow stone socket; a centred red/white pillar sits directly on it. */
    public static class PostBase extends Block {
        public static final MapCodec<PostBase> CODEC = simpleCodec(PostBase::new);
        private static final VoxelShape SHAPE = Shapes.or(box(2, 0, 2, 14, 8, 14), box(5.5, 8, 5.5, 10.5, 16, 10.5));

        public PostBase(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return CODEC;
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return SHAPE;
        }

        @Override
        protected boolean isPathfindable(BlockState state, PathComputationType type) {
            return false;
        }
    }
}
