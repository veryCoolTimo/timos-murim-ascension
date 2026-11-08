package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Eight-cup persistent jar and full/empty cup. Cup refills draw from nearby jars; no chat feedback. */
public final class WineBlock extends IntTabletopBlock {
    public static final net.minecraft.world.level.block.state.properties.IntegerProperty CUPS =
            net.minecraft.world.level.block.state.properties.IntegerProperty.create("cups", 0, 8);
    public static final BooleanProperty FULL = BooleanProperty.create("full");
    public static final MapCodec<WineBlock> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            propertiesCodec(), com.mojang.serialization.Codec.BOOL.fieldOf("cup").forGetter(b -> b.cup)
    ).apply(i, WineBlock::new));
    private final boolean cup;

    public WineBlock(Properties properties, boolean cup) {
        super(properties, Kind.JARS);
        this.cup = cup;
        registerDefaultState(defaultBlockState().setValue(FULL, true).setValue(CUPS, 8));
    }

    @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return CODEC; }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(FULL, CUPS);
    }

    // Immutable silhouettes sampled from the enlarged model's 25 ceramic tiers.
    private static final VoxelShape TIED_SHAPE = jarShape(false);
    private static final VoxelShape LOOSE_SHAPE = jarShape(true);

    private static VoxelShape jarShape(boolean empty) {
        double[][] tiers = {
                {0, 0.336, 3.36},
                {0.336, 0.72576, 4.343112},
                {0.72576, 1.002868, 4.851534},
                {1.002868, 1.279977, 5.146605},
                {1.279977, 1.557085, 5.441673},
                {1.557085, 1.834194, 5.736741},
                {1.834194, 2.111302, 6.031812},
                {2.111302, 2.388409, 6.27312},
                {2.388409, 2.665518, 6.362924},
                {2.665518, 2.942627, 6.452728},
                {2.942627, 3.219735, 6.542532},
                {3.219735, 3.496843, 6.632334},
                {3.496843, 3.773952, 6.72},
                {3.773952, 4.051061, 6.601788},
                {4.051061, 4.328169, 6.486326},
                {4.328169, 4.605277, 6.370866},
                {4.605277, 4.882386, 6.255404},
                {4.882386, 5.159495, 6.139942},
                {5.159495, 5.436602, 5.83296},
                {5.436602, 5.71371, 5.473746},
                {5.71371, 5.990819, 5.114532},
                {5.990819, 6.267927, 4.755316},
                {6.267927, 6.545035, 4.044219},
                {6.545035, 6.822144, 3.274474},
                {6.822144, 7.2576, 2.688}
        };
        VoxelShape shape = Shapes.empty();
        for (double[] tier : tiers) {
            double half = tier[2] / 2;
            shape = Shapes.or(shape, Block.box(8-half, tier[0], 8-half, 8+half, tier[1], 8+half));
        }
        // Conservative cloth envelope; loose fabric settles below the tied crown.
        return Shapes.or(shape, Block.box(6.0, 6.35, 6.0, 10.0, empty ? 7.35 : 7.594, 10.0));
    }

    // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/level/block/Block.java#box;
    // /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/phys/shapes/Shapes.java#or.
    // Stepped foot, belly, shoulder and cloth match the cuboid model envelope.
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (cup) return TabletopSupport.lower(Block.box(5, 0, 5, 11, 4, 11), state);
        return TabletopSupport.lower(state.getValue(CUPS) == 0 ? LOOSE_SHAPE : TIED_SHAPE, state);
    }

    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return cup ? super.getCollisionShape(state, level, pos, context) : getShape(state, level, pos, context);
    }

    // API: Minecraft BlockBehaviour#useItemOn, ItemInteractionResult (1.21.1 local sources).
    @Override protected net.minecraft.world.ItemInteractionResult useItemOn(net.minecraft.world.item.ItemStack stack,
            BlockState state, Level level, BlockPos pos, Player player, net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
        if (player.isShiftKeyDown()) return net.minecraft.world.ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        useWithoutItem(state, level, pos, player, hit);
        return net.minecraft.world.ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player.isShiftKeyDown()) return InteractionResult.PASS;
        if (!cup) {
            if (!level.isClientSide && state.getValue(CUPS) > 0) {
                int left = state.getValue(CUPS) - 1;
                level.setBlock(pos, state.setValue(CUPS, left).setValue(FULL, left > 0), Block.UPDATE_ALL);
                WineService.drink(player);
                level.playSound(null, pos, SoundEvents.GENERIC_DRINK, SoundSource.PLAYERS, .6F, 1.0F);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (state.getValue(FULL)) {
            level.setBlock(pos, state.setValue(FULL, false), Block.UPDATE_ALL);
            WineService.drink(player);
            level.playSound(null, pos, SoundEvents.PLAYER_BURP, SoundSource.PLAYERS, 0.35F, 1.35F);
        } else {
            for (BlockPos nearby : BlockPos.betweenClosed(pos.offset(-2, -1, -2), pos.offset(2, 1, 2))) {
                BlockState jar = level.getBlockState(nearby);
                if (jar.is(IntWorkshop.WINE_JAR.get()) && jar.getValue(CUPS) > 0) {
                    int left = jar.getValue(CUPS) - 1;
                    level.setBlock(nearby, jar.setValue(CUPS, left).setValue(FULL, left > 0), Block.UPDATE_ALL);
                    level.setBlock(pos, state.setValue(FULL, true), Block.UPDATE_ALL);
                    break;
                }
            }
        }
        return InteractionResult.CONSUME;
    }
}
