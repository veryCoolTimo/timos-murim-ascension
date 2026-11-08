package io.github.verycooltimo.murim.sect.vault;

import com.mojang.serialization.MapCodec;
import io.github.verycooltimo.murim.decor.ShrineBlock;
import io.github.verycooltimo.murim.decor.ShrineSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A tablet of the Ancestors' Hall that looks exactly like the others (same model) but answers a touch: it is one of the four
 * of the founders' rite ({@link #ORDER} 1..4 is its place in the rite). See {@link VaultMechanism#tablet}.
 */
public class LockTabletBlock extends ShrineBlock {

    public static final MapCodec<LockTabletBlock> CODEC = simpleCodec(LockTabletBlock::new);
    public static final IntegerProperty ORDER = IntegerProperty.create("order", 1, 4);

    /** The geometry of {@code shr_ancestor_tablets} (decor/ShrineDecor). */
    static final ShrineSpec SPEC = ShrineSpec.of(1, 1, false, ShrineSpec.Fx.NONE, 0, new double[] {1.5, 0, 5.6, 14.5, 11.4, 10.4});

    public LockTabletBlock(Properties properties) {
        super(properties, SPEC);
        registerDefaultState(defaultBlockState().setValue(ORDER, 1));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(ORDER);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer sp && level instanceof net.minecraft.server.level.ServerLevel sl) {
            VaultMechanism.tablet(sl, sp, pos, state.getValue(ORDER));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
