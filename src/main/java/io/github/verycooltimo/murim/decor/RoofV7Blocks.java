package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Independent profiles for the measured 17 x 15 roof; existing roof states stay intact.
 * Exact placements: tools/art/roof_blocks.py#roof_v7, generated roof_v7.mcfunction.
 * Courses have 220 states (including two diagonal seam profiles); panels 88; horn parts 8.
 * The OBJ generator shares seam sections; these shapes are coarse selection bounds.
 */
public final class RoofV7Blocks {
    private RoofV7Blocks() { }

    public enum SupportProfile implements StringRepresentable {
        RAFTER("rafter"), BRACKET("bracket"), CORNER("corner"), BEAM("beam");

        private final String name;
        SupportProfile(String name) { this.name = name; }
        @Override
        public String getSerializedName() { return name; }
    }

    /** Simple continuous timber members; four profiles, sixteen baked states. */
    public static class Support extends RoofBlocks.Facing {
        public static final MapCodec<Support> CODEC = simpleCodec(Support::new);
        public static final EnumProperty<SupportProfile> PROFILE = EnumProperty.create("profile", SupportProfile.class);

        public Support(Properties properties) {
            super(properties);
            registerDefaultState(defaultBlockState().setValue(PROFILE, SupportProfile.RAFTER));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return CODEC; }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(PROFILE);
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return Block.box(0, state.getValue(PROFILE) == SupportProfile.RAFTER ? 12 : 2, 0, 16, 16, 16);
        }

        @Override
        protected BlockState rotate(BlockState state, Rotation rotation) {
            return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
        }

        @Override
        protected BlockState mirror(BlockState state, Mirror mirror) {
            Direction facing = state.getValue(FACING);
            if (state.getValue(PROFILE) == SupportProfile.CORNER && mirror != Mirror.NONE) {
                facing = facing.getClockWise();
            }
            return state.setValue(FACING, mirror.mirror(facing));
        }
    }

    /** Final course datum y=10; thin cream section, axis x or z. */
    public static class Ridge extends RoofBlocks.Tiles {
        public static final MapCodec<Ridge> CODEC = simpleCodec(Ridge::new);

        public Ridge(Properties properties) { super(properties); }

        @Override
        protected MapCodec<? extends Block> codec() { return CODEC; }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            VoxelShape bed = Block.box(0, 8.5, 0, 16, 10, 16);
            VoxelShape cap = state.getValue(RoofBlocks.AXIS) == Direction.Axis.X
                    ? Block.box(0, 10, 6.2, 16, 18.1, 9.8) : Block.box(6.2, 10, 0, 9.8, 18.1, 16);
            return net.minecraft.world.phys.shapes.Shapes.or(bed, cap);
        }
    }

    public enum HornPart implements StringRepresentable {
        ROOT("root"), TIP("tip");
        private final String name;
        HornPart(String name) { this.name = name; }
        @Override
        public String getSerializedName() { return name; }
    }

    public static class RidgeEnd extends RoofBlocks.Facing {
        public static final MapCodec<RidgeEnd> CODEC = simpleCodec(RidgeEnd::new);
        public static final EnumProperty<HornPart> PART = EnumProperty.create("part", HornPart.class);
        public RidgeEnd(Properties properties) {
            super(properties);
            registerDefaultState(defaultBlockState().setValue(PART, HornPart.ROOT));
        }
        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(PART);
        }
        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return CODEC; }
        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            if (state.getValue(PART) == HornPart.TIP) {
                return state.getValue(FACING).getAxis() == Direction.Axis.X
                        ? Block.box(0, 27.5, 6.2, 16, 69, 9.8) : Block.box(6.2, 27.5, 0, 9.8, 69, 16);
            }
            VoxelShape cap = switch (state.getValue(FACING)) {
                // Horn skin is shared with the adjacent peak panel, without
                // duplicated cross-cell faces. Bounds include its silhouette.
                case EAST -> Block.box(0, 8.5, 6.2, 16, 39, 9.8);
                case WEST -> Block.box(0, 8.5, 6.2, 16, 39, 9.8);
                case SOUTH -> Block.box(6.2, 8.5, 0, 9.8, 39, 16);
                default -> Block.box(6.2, 8.5, 0, 9.8, 39, 16);
            };
            VoxelShape root = switch (state.getValue(FACING)) {
                case EAST -> Block.box(0, 9, 4.5, 2, 19, 11.5);
                case WEST -> Block.box(14, 9, 4.5, 16, 19, 11.5);
                case SOUTH -> Block.box(4.5, 9, 0, 11.5, 19, 2);
                default -> Block.box(4.5, 9, 14, 11.5, 19, 16);
            };
            return net.minecraft.world.phys.shapes.Shapes.or(cap, root);
        }
        @Override
        protected BlockState rotate(BlockState state, Rotation rotation) {
            return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
        }
        @Override
        protected BlockState mirror(BlockState state, Mirror mirror) {
            return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
        }
    }

    public static class Corner extends RoofBlocks.Facing {
        public static final MapCodec<Corner> CODEC = simpleCodec(Corner::new);
        public Corner(Properties properties) { super(properties); }
        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return CODEC; }
        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return switch (state.getValue(FACING)) {
                case EAST -> Block.box(-6, -4, -6, 16, 27, 16);
                case SOUTH -> Block.box(0, -4, -6, 22, 27, 16);
                case WEST -> Block.box(0, -4, 0, 22, 27, 22);
                default -> Block.box(-6, -4, 0, 16, 27, 22);
            };
        }
        private static boolean edge(BlockState state, Direction facing) {
            return (state.getBlock() instanceof RoofBlocks.Eave && !(state.getBlock() instanceof RoofBlocks.Corner)
                    || state.getBlock() instanceof Course) && state.getValue(FACING) == facing;
        }
        private static BlockState orient(BlockState state, BlockGetter level, BlockPos pos) {
            Direction best = state.getValue(FACING);
            int score = 0;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                Direction cw = d.getClockWise();
                int n = (edge(level.getBlockState(pos.relative(d)), cw) ? 1 : 0)
                        + (edge(level.getBlockState(pos.relative(cw)), d) ? 1 : 0);
                if (n > score) { best = d; score = n; }
            }
            return state.setValue(FACING, best);
        }
        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            Direction f = context.getPlayer() == null ? context.getHorizontalDirection()
                    : RoofBlocks.Corner.facingFor(context.getPlayer().getLookAngle());
            return orient(defaultBlockState().setValue(FACING, f), context.getLevel(), context.getClickedPos());
        }
        @Override
        protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbour,
                                         LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
            return orient(state, level, pos);
        }
        @Override
        protected BlockState rotate(BlockState state, Rotation rotation) {
            return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
        }
        @Override
        protected BlockState mirror(BlockState state, Mirror mirror) {
            if (mirror == Mirror.NONE) return state;
            return state.setValue(FACING, mirror.mirror(state.getValue(FACING).getClockWise()));
        }
    }

    public enum CourseProfile implements StringRepresentable {
        // Integer floors 0,0,16,32,32,48; local course endpoints.
        // Rebuild with the generated roof_v7 function after changing datums.
        ROW1("row1", 2, 10), ROW2("row2", 10, 22), ROW3("row3", 6, 16),
        ROW4("row4", 0, 12), ROW5("row5", 12, 26), ROW6("row6", 10, 26),
        ROW1_JOIN_LEFT("row1_join_left", 2, 10), ROW1_JOIN_RIGHT("row1_join_right", 2, 10),
        EAVE("eave", -4, 3), EAVE_LEFT("eave_left", -4, 12), EAVE_RIGHT("eave_right", -4, 12);

        private final String name;
        final int low, high;

        CourseProfile(String name, int low, int high) {
            this.name = name;
            this.low = low;
            this.high = high;
        }

        @Override
        public String getSerializedName() { return name; }
    }

    public static class Course extends RoofBlocks.Facing {
        public static final MapCodec<Course> CODEC = simpleCodec(Course::new);
        public static final EnumProperty<CourseProfile> PROFILE = EnumProperty.create("profile", CourseProfile.class);
        public static final EnumProperty<StairsShape> SHAPE = net.minecraft.world.level.block.state.properties.BlockStateProperties.STAIRS_SHAPE;

        public Course(Properties properties) {
            super(properties);
            registerDefaultState(defaultBlockState().setValue(PROFILE, CourseProfile.ROW3).setValue(SHAPE, StairsShape.STRAIGHT));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return CODEC; }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            CourseProfile p = state.getValue(PROFILE);
            if (p.low < 0) {
                return switch (state.getValue(FACING)) {
                    case EAST -> Block.box(-6, p.low, 0, 16, p.high, 16);
                    case SOUTH -> Block.box(0, p.low, -6, 16, p.high, 16);
                    case WEST -> Block.box(0, p.low, 0, 22, p.high, 16);
                    default -> Block.box(0, p.low, 0, 16, p.high, 22);
                };
            }
            return Block.box(0, p.low < 0 ? p.low : Math.max(0, p.low - 2), 0, 16, p.high, 16);
        }

        private static boolean partner(BlockState self, BlockState other) {
            if (!(other.getBlock() instanceof Course)) return false;
            CourseProfile a = self.getValue(PROFILE), b = other.getValue(PROFILE);
            return a == b || row1(a) && row1(b);
        }

        private static boolean row1(CourseProfile profile) {
            return profile == CourseProfile.ROW1 || profile == CourseProfile.ROW1_JOIN_LEFT
                    || profile == CourseProfile.ROW1_JOIN_RIGHT;
        }

        /** Same-profile neighbours form hips/valleys as RoofBlocks.Course does. */
        private static StairsShape shapeFor(BlockState state, BlockGetter level, BlockPos pos) {
            Direction f = state.getValue(FACING);
            BlockState behind = level.getBlockState(pos.relative(f));
            if (partner(state, behind)) {
                Direction d = behind.getValue(FACING);
                BlockState side = level.getBlockState(pos.relative(d.getOpposite()));
                if (d.getAxis() != f.getAxis() && !(partner(state, side) && side.getValue(FACING) == f)) {
                    return d == f.getCounterClockWise() ? StairsShape.OUTER_LEFT : StairsShape.OUTER_RIGHT;
                }
            }
            BlockState front = level.getBlockState(pos.relative(f.getOpposite()));
            if (partner(state, front)) {
                Direction d = front.getValue(FACING);
                BlockState side = level.getBlockState(pos.relative(d));
                if (d.getAxis() != f.getAxis() && !(partner(state, side) && side.getValue(FACING) == f)) {
                    return d == f.getCounterClockWise() ? StairsShape.INNER_LEFT : StairsShape.INNER_RIGHT;
                }
            }
            return StairsShape.STRAIGHT;
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            BlockState state = defaultBlockState().setValue(FACING, context.getHorizontalDirection());
            return state.setValue(SHAPE, shapeFor(state, context.getLevel(), context.getClickedPos()));
        }

        @Override
        protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbour,
                                         LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
            return state.setValue(SHAPE, shapeFor(state, level, pos));
        }

        @Override
        protected BlockState rotate(BlockState state, Rotation rotation) {
            return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
        }

        @Override
        protected BlockState mirror(BlockState state, Mirror mirror) {
            if (mirror == Mirror.NONE) return state;
            StairsShape shape = switch (state.getValue(SHAPE)) {
                case OUTER_LEFT -> StairsShape.OUTER_RIGHT;
                case OUTER_RIGHT -> StairsShape.OUTER_LEFT;
                case INNER_LEFT -> StairsShape.INNER_RIGHT;
                case INNER_RIGHT -> StairsShape.INNER_LEFT;
                default -> StairsShape.STRAIGHT;
            };
            return state.setValue(FACING, mirror.mirror(state.getValue(FACING))).setValue(SHAPE, shape);
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(PROFILE, SHAPE);
        }
    }

    public enum PanelProfile implements StringRepresentable {
        EDGE3("edge3"), EDGE4("edge4"), EDGE5("edge5"), EDGE6("edge6"),
        FULL("full"), BASE("base"), BASE_EDGE4("base_edge4"), FULL_EDGE6("full_edge6"), PEAK("peak"), SILL("sill"), EMBLEM("emblem");

        private final String name;
        PanelProfile(String name) { this.name = name; }
        @Override
        public String getSerializedName() { return name; }
    }

    public static class Panel extends RoofBlocks.Facing {
        public static final MapCodec<Panel> CODEC = simpleCodec(Panel::new);
        public static final EnumProperty<RoofBlocks.Side> SIDE = RoofBlocks.EaveRise.SIDE;
        public static final EnumProperty<PanelProfile> PROFILE = EnumProperty.create("profile", PanelProfile.class);

        public Panel(Properties properties) {
            super(properties);
            registerDefaultState(defaultBlockState().setValue(SIDE, RoofBlocks.Side.LEFT).setValue(PROFILE, PanelProfile.FULL));
        }

        @Override
        protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return CODEC; }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockState n = context.getLevel().getBlockState(context.getClickedPos().relative(d));
                if (n.getBlock() instanceof Course && n.getValue(FACING).getAxis() != d.getAxis()) {
                    Direction facing = n.getValue(FACING);
                    PanelProfile profile = switch (n.getValue(Course.PROFILE)) {
                        case ROW3 -> PanelProfile.EDGE3;
                        case ROW4 -> PanelProfile.EDGE4;
                        case ROW5 -> PanelProfile.EDGE5;
                        case ROW6 -> PanelProfile.EDGE6;
                        default -> PanelProfile.FULL;
                    };
                    return defaultBlockState().setValue(FACING, facing).setValue(PROFILE, profile)
                            .setValue(SIDE, facing.getCounterClockWise() == d ? RoofBlocks.Side.LEFT : RoofBlocks.Side.RIGHT);
                }
            }
            return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            Direction facing = state.getValue(FACING);
            Direction plate = state.getValue(SIDE) == RoofBlocks.Side.LEFT ? facing.getCounterClockWise() : facing.getClockWise();
            VoxelShape wall = switch (plate) {
                case NORTH -> Block.box(0, 0, 0, 16, 16, 1);
                case SOUTH -> Block.box(0, 0, 15, 16, 16, 16);
                case WEST -> Block.box(0, 0, 0, 1, 16, 16);
                default -> Block.box(15, 0, 0, 16, 16, 16);
            };
            if (state.getValue(PROFILE) == PanelProfile.PEAK) {
                // The middle horn skin is in this panel's cell. Include it
                // in selection/collision, preserving its thin blade depth.
                VoxelShape blade = facing.getAxis() == Direction.Axis.Z
                        ? Block.box(0, 8.9, 6.2, 16, 47, 9.8) : Block.box(6.2, 8.9, 0, 9.8, 47, 16);
                return net.minecraft.world.phys.shapes.Shapes.or(wall, blade);
            }
            return wall;
        }

        @Override
        protected BlockState rotate(BlockState state, Rotation rotation) {
            return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
        }

        @Override
        protected BlockState mirror(BlockState state, Mirror mirror) {
            if (mirror == Mirror.NONE) return state;
            return state.setValue(FACING, mirror.mirror(state.getValue(FACING)))
                    .setValue(SIDE, state.getValue(SIDE) == RoofBlocks.Side.LEFT ? RoofBlocks.Side.RIGHT : RoofBlocks.Side.LEFT);
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(SIDE, PROFILE);
        }
    }
}
