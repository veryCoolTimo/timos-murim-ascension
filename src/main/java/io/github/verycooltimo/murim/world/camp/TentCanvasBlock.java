package io.github.verycooltimo.murim.world.camp;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Полотно шатра (docs/design/24-bandit-camp.md §2): тонкий скат под 45°, конёк, торец-треугольник и
 * стенка. Ванильные ступени давали «ступенчатую горку» (замечание автора и codex 04.10), поэтому свой блок
 * с повёрнутыми на 45° элементами модели ({@code tools/art/tent_canvas.py}).
 *
 * <p>{@code facing} — куда смотрит низ ската (для конька и стенки — поперёк оси шатра). Базовая модель —
 * юг: конёк вдоль x, низ ската у +z. Столкновение — ступенчатое приближение тонкого полотна, не полный блок.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/HorizontalDirectionalBlock.java,
 * reference/minecraft-src/net/minecraft/world/level/block/CarpetBlock.java (тонкая форма),
 * reference/minecraft-src/net/minecraft/client/renderer/block/model/BlockElement.java (повороты ±45°).
 */
public class TentCanvasBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<TentCanvasBlock> CODEC = simpleCodec(TentCanvasBlock::new);

    /** Часть полотна. */
    public enum Part implements StringRepresentable {
        SLOPE, RIDGE, GABLE_SLOPE, GABLE_RIDGE, WALL;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Ткань: небелёный холст, белёный, багровый (шатёр главаря). */
    public enum Cloth implements StringRepresentable {
        LINEN, WHITE, CRIMSON;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    public static final EnumProperty<Cloth> CLOTH = EnumProperty.create("cloth", Cloth.class);

    private static final Map<Part, Map<Direction, VoxelShape>> SHAPES = new EnumMap<>(Part.class);

    static {
        for (Part p : Part.values()) {
            Map<Direction, VoxelShape> m = new EnumMap<>(Direction.class);
            for (Direction d : Direction.Plane.HORIZONTAL) {
                m.put(d, rotate(base(p), d));
            }
            SHAPES.put(p, m);
        }
    }

    public TentCanvasBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH).setValue(PART, Part.SLOPE).setValue(CLOTH, Cloth.LINEN));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART, CLOTH);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(PART)).get(state.getValue(FACING));
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
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

    /** Форма для фасада «юг» (низ ската у +z): ступеньки по 4 px вдоль диагонали. */
    private static VoxelShape base(Part p) {
        VoxelShape slope = Shapes.empty();
        for (int i = 0; i < 4; i++) {
            slope = Shapes.or(slope, Block.box(0, 12 - 4 * i, 4 * i, 16, 16 - 4 * i, 4 * i + 4));
        }
        VoxelShape ridge = Shapes.or(Block.box(0, 0, 0, 16, 4, 4), Block.box(0, 4, 4, 16, 8, 12), Block.box(0, 0, 12, 16, 4, 16));
        VoxelShape wall = Block.box(7, 0, 0, 9, 16, 16);
        return switch (p) {
            case SLOPE -> slope;
            case RIDGE -> ridge;
            case GABLE_SLOPE -> Shapes.or(slope, Block.box(7, 0, 0, 9, 12, 12));
            case GABLE_RIDGE -> Shapes.or(ridge, Block.box(7, 0, 4, 9, 4, 12));
            case WALL -> wall;
        };
    }

    /** Повернуть форму «юг» к фасаду {@code d} (по часовой, как y-поворот модели в blockstate). */
    private static VoxelShape rotate(VoxelShape shape, Direction d) {
        int turns = switch (d) {
            case WEST -> 1;
            case NORTH -> 2;
            case EAST -> 3;
            default -> 0;
        };
        VoxelShape out = shape;
        for (int t = 0; t < turns; t++) {
            VoxelShape[] acc = {Shapes.empty()};
            // По часовой сверху: (x, z) -> (1 - z, x).
            out.forAllBoxes((x0, y0, z0, x1, y1, z1) -> acc[0] = Shapes.or(acc[0], Shapes.box(1 - z1, y0, x0, 1 - z0, y1, x1)));
            out = acc[0];
        }
        return out;
    }
}
