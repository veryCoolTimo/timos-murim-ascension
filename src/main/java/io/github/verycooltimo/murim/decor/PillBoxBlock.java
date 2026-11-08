package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The lacquer pill box on a table (author 24.10): a tabletop prop (facing, sink, no collision) with a hinged lid and six compartments.
 * Use opens selection; sneak-use toggles the saved, synchronized OPEN block state. The lid is
 * drawn by the block entity renderer (client/decor/PillBoxClient) so that it swings smoothly; {@code open} is the state it follows.
 */
public class PillBoxBlock extends IntTabletopBlock implements EntityBlock {

    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
    public static final MapCodec<PillBoxBlock> CODEC = simpleCodec(PillBoxBlock::new);

    public PillBoxBlock(Properties properties) {
        super(properties, Kind.PILL_BOX);
        registerDefaultState(defaultBlockState().setValue(OPEN, false));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(OPEN);
    }

    /** Closed: the box; open: the box and the standing lid behind it (the lid swings to about 105 degrees about the back hinge). */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape box = super.getShape(state, level, pos, context);
        if (!state.getValue(OPEN)) {
            return box;
        }
        return Shapes.or(box, TabletopSupport.lower(StoShapes.turn(4.9, 2.6, 10.6, 11.1, 7.95, 13.5, state.getValue(FACING)), state));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof PillBoxBlockEntity box) {
            if (player.isShiftKeyDown()) {
                box.toggleLid();
            } else if (player instanceof net.minecraft.server.level.ServerPlayer server) {
                PillBoxMenu.open(server, new net.minecraft.world.SimpleMenuProvider(
                        (id, inventory, p) -> new PillBoxMenu(id, inventory, box, -1),
                        net.minecraft.network.chat.Component.translatable(state.is(IntWorkshop.PILL_CRATE.get())
                                ? "block.murim.pill_crate" : "block.murim.int_pill_box")), -1, box.isCrate());
            }
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PillBoxBlockEntity(pos, state);
    }
}
