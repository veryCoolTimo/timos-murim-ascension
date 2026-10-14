package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Training-ground weapon stands (refs docs/design/reference/decor/weapon_rack_1.png, weapon_rack_2.png): the
 * training sword rack (3 swords upright) and the single sword stand (1 sword lying on forks above its scabbard).
 * The swords are real items in a {@link WeaponStandBlockEntity} (author 06.10: «мечи лежать должны наши, и их
 * вытаскивать должна быть возможность»): right-click with a sword puts it in the aimed slot or the nearest free one,
 * right-click with an empty hand takes the aimed one or the nearest stored one; breaking drops them.
 * FACING is the side the swords show to; models are drawn facing north and turned by the blockstate.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/ChiseledBookShelfBlock.java (#useItemOn,
 * #useWithoutItem, #onRemove), CampfireBlock.java#useItemOn; Containers.java#dropContents.
 */
public class WeaponStandBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public enum Kind {
        /** Vertical rack 1.5 blocks tall (real-size swords); 3 swords stand tip down at these x centres (model facing
         * north), broad side to the front. The shape reaches y 24 like a fence. */
        RACK(Block.box(0, 0, 4.5, 16, 24, 11.5), new float[] {3.5F, 8.0F, 12.5F}),
        /** Low stand; one real-size sword lies along x on the forks, its scabbard (part of the model) on the hooks
         * below. The model reaches past the block on both sides (about 2 blocks long); the shape stays in the block. */
        STAND(Block.box(0, 0, 4.5, 16, 8, 10.5), new float[] {8.0F});

        /** Shape for FACING = north; other facings are turned about the block centre. */
        final VoxelShape north;
        /** Slot x centres in model pixels (facing north). */
        final float[] slotX;

        Kind(VoxelShape north, float[] slotX) {
            this.north = north;
            this.slotX = slotX;
        }

        public int slots() {
            return slotX.length;
        }

        public float slotX(int slot) {
            return slotX[slot];
        }
    }

    public static final MapCodec<WeaponStandBlock> RACK_CODEC = simpleCodec(p -> new WeaponStandBlock(p, Kind.RACK));
    public static final MapCodec<WeaponStandBlock> STAND_CODEC = simpleCodec(p -> new WeaponStandBlock(p, Kind.STAND));

    private final Kind kind;
    private final VoxelShape[] shapes = new VoxelShape[4];

    public WeaponStandBlock(Properties properties, Kind kind) {
        super(properties);
        this.kind = kind;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            shapes[d.get2DDataValue()] = turn(kind.north, d);
        }
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public Kind kind() {
        return kind;
    }

    /** Swords only: everything in #minecraft:swords (our wooden and Huashan swords are in it). */
    public static boolean holds(ItemStack stack) {
        return stack.is(ItemTags.SWORDS);
    }

    /** The north box turned to face {@code d} (both shapes are single boxes). */
    private static VoxelShape turn(VoxelShape north, Direction d) {
        var b = north.bounds();
        double x0 = b.minX * 16, x1 = b.maxX * 16, y0 = b.minY * 16, y1 = b.maxY * 16, z0 = b.minZ * 16, z1 = b.maxZ * 16;
        return switch (d) {
            case SOUTH -> Block.box(16 - x1, y0, 16 - z1, 16 - x0, y1, 16 - z0);
            case EAST -> Block.box(16 - z1, y0, x0, 16 - z0, y1, x1);
            case WEST -> Block.box(z0, y0, 16 - x1, z1, y1, 16 - x0);
            default -> north;
        };
    }

    /** Model x (pixels, facing north) of a world point: the inverse of the blockstate turn (see {@link #turn}). */
    public static float modelX(BlockState state, BlockPos pos, Vec3 at) {
        double wx = (at.x - pos.getX()) * 16.0D;
        double wz = (at.z - pos.getZ()) * 16.0D;
        return (float) switch (state.getValue(FACING)) {
            case EAST -> wz;
            case SOUTH -> 16.0D - wx;
            case WEST -> 16.0D - wz;
            default -> wx;
        };
    }

    /** Slot under the crosshair: the one whose x centre is nearest the hit. */
    int aimedSlot(BlockState state, BlockPos pos, BlockHitResult hit) {
        float x = modelX(state, pos, hit.getLocation());
        int best = 0;
        for (int i = 1; i < kind.slots(); i++) {
            if (Math.abs(kind.slotX(i) - x) < Math.abs(kind.slotX(best) - x)) {
                best = i;
            }
        }
        return best;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (!holds(stack)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!(level.getBlockEntity(pos) instanceof WeaponStandBlockEntity stand)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        int slot = stand.freeSlotNear(aimedSlot(state, pos, hit));
        if (slot < 0) {
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide) {
            stand.put(slot, stack.split(1));
            level.playSound(null, pos, SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.BLOCKS, 0.8F, 1.1F);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        // Only an empty main hand takes a sword (useWithoutItem also runs after any non-sword item passes).
        if (!player.getMainHandItem().isEmpty() || !(level.getBlockEntity(pos) instanceof WeaponStandBlockEntity stand)) {
            return InteractionResult.PASS;
        }
        int slot = stand.storedSlotNear(aimedSlot(state, pos, hit));
        if (slot < 0) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            ItemStack taken = stand.take(slot);
            player.setItemInHand(InteractionHand.MAIN_HAND, taken);
            level.playSound(null, pos, SoundEvents.ARMOR_EQUIP_GENERIC.value(), SoundSource.BLOCKS, 0.8F, 1.2F);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof WeaponStandBlockEntity stand) {
            Containers.dropContents(level, pos, stand.items());
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WeaponStandBlockEntity(pos, state);
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return kind == Kind.RACK ? RACK_CODEC : STAND_CODEC;
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes[state.getValue(FACING).get2DDataValue()];
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
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }
}
