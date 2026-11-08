package io.github.verycooltimo.murim.quest;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * One readable or interactive spot of the world: a paper on a table, a carved stone, a lid, a box, a marked post, a training
 * target. The block only looks and answers a right click; what the spot is, says and gives is {@link ClueSpots}, found by the
 * spot id that the block entity holds. A quest places these where the story is (by worldgen or by {@code ClueSites}).
 */
public final class ClueBlock extends Block implements EntityBlock {

    public enum Kind implements StringRepresentable {
        PAPER, CARVED, LID, BOX, MARK, TARGET;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static final EnumProperty<Kind> KIND = EnumProperty.create("kind", Kind.class);
    /** TARGET: the side target is open and may be struck; BOX: opened. Open spots glow a little. */
    public static final BooleanProperty OPEN = BooleanProperty.create("open");
    public static final MapCodec<ClueBlock> CODEC = simpleCodec(ClueBlock::new);

    private static final VoxelShape PAPER = Block.box(2, 0, 2, 14, 0.6, 14);
    private static final VoxelShape LID = Block.box(1, 0, 1, 15, 3, 15);
    private static final VoxelShape BOX = Block.box(3, 0, 4, 13, 7, 12);
    private static final VoxelShape FULL = Block.box(0, 0, 0, 16, 16, 16);

    public ClueBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(KIND, Kind.PAPER).setValue(OPEN, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(KIND, OPEN);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return switch (state.getValue(KIND)) {
            case PAPER -> PAPER;
            case LID -> LID;
            case BOX -> BOX;
            default -> FULL;
        };
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return state.getValue(KIND) == Kind.PAPER ? net.minecraft.world.phys.shapes.Shapes.empty() : getShape(state, level, pos, ctx);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ClueBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer sp && level.getBlockEntity(pos) instanceof ClueBlockEntity be) {
            ClueSpots.use(sp, pos, be.spot());
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Puts a spot into the world with the kind its quest data gives; works in worldgen (a WorldGenLevel) too. */
    public static void place(LevelAccessor level, BlockPos pos, String spot) {
        place(level, pos, QuestLoader.kind(spot).orElse(Kind.CARVED), spot);
    }


    public static void place(LevelAccessor level, BlockPos pos, Kind kind, String spot) {
        level.setBlock(pos, ModQuests.CLUE_BLOCK.get().defaultBlockState().setValue(KIND, kind), 2);
        if (level.getBlockEntity(pos) instanceof ClueBlockEntity be) {
            be.setSpot(spot);
        }
    }
}
