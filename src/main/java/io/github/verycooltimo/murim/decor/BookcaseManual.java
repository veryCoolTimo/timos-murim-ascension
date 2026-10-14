package io.github.verycooltimo.murim.decor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

/** Shared lifecycle for the two/three-high racks; contents live independently in each part. */
final class BookcaseManual {
    private BookcaseManual() {}

    static BlockPos bottom(BlockPos pos, BlockState state) {
        return state.hasProperty(TallLibraryBookcaseBlock.PART)
                ? pos.below(state.getValue(TallLibraryBookcaseBlock.PART).ordinal())
                : state.getValue(StoneLanternBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
    }

    static InteractionResult use(Level level, BlockPos pos, BlockState state) {
        BlockPos bottom = bottom(pos, state);
        int height = state.hasProperty(TallLibraryBookcaseBlock.PART) ? 3 : 2;
        for (int i = 0; i < height; i++) {
            BlockPos at = bottom.above(i);
            BlockState part = level.getBlockState(at);
            if (!part.is(state.getBlock()) || !part.getValue(LibraryBookcaseBlock.HAS_BOOK)) continue;
            if (!level.isClientSide && level.getBlockEntity(at) instanceof BookcaseManualBlockEntity be) {
                // Clear persistent contents and the visible slot before dropping: a second click cannot duplicate it.
                ItemStack stack = be.takeManual();
                if (!stack.isEmpty()) {
                    Block.popResource(level, at.relative(state.getValue(LibraryBookcaseBlock.FACING)), stack);
                    level.playSound(null, at, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, .7F, 1F);
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }

    static void removed(Level level, BlockPos pos) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof BookcaseManualBlockEntity be) {
            // Do not rewrite the old blockstate while it is being replaced.
            ItemStack stack = be.manual();
            if (!stack.isEmpty()) Block.popResource(level, pos, stack);
        }
    }

    static void clearCreative(Level level, BlockPos pos, BlockState state) {
        BlockPos bottom = bottom(pos, state);
        for (int i = 0; i < (state.hasProperty(TallLibraryBookcaseBlock.PART) ? 3 : 2); i++) {
            BlockPos at = bottom.above(i);
            if (level.getBlockState(at).is(state.getBlock()) && level.getBlockEntity(at) instanceof BookcaseManualBlockEntity be) {
                be.setManual(ItemStack.EMPTY);
            }
        }
    }

    static void particle(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!level.isClientSide || !state.getValue(LibraryBookcaseBlock.HAS_BOOK)
                || level.getNearestPlayer(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, 8.0D, false) == null
                || !(level.getBlockEntity(pos) instanceof BookcaseManualBlockEntity be)
                || !be.particleDue(level.getGameTime(), random)) return;
        Direction facing = state.getValue(LibraryBookcaseBlock.FACING);
        int slot = state.getValue(LibraryBookcaseBlock.bookSlotProperty(state));
        double[] centers = {3.9, 7.8, 12.2, 4.0, 11.0, 8.0};
        double dx = centers[slot] / 16.0 - .5;
        double dz = (slot == 2 || slot == 5 ? 16.3 : 16.0) / 16.0 - .5;
        Vec3 offset = switch (facing) {
            case NORTH -> new Vec3(-dx, 0, -dz);
            case EAST -> new Vec3(dz, 0, -dx);
            case WEST -> new Vec3(-dz, 0, dx);
            default -> new Vec3(dx, 0, dz);
        };
        boolean lower = state.hasProperty(TallLibraryBookcaseBlock.PART)
                ? state.getValue(TallLibraryBookcaseBlock.PART) == TallLibraryBookcaseBlock.Part.LOWER
                : state.getValue(StoneLanternBlock.HALF) == DoubleBlockHalf.LOWER;
        double shelf = (lower ? 2 : 1) + (slot % 2 == 1 ? 7.5 : 0);
        Vec3 at = Vec3.atCenterOf(pos).add(offset);
        // One quiet vanilla enchant mote, no emissive texture or block light.
        level.addParticle(ParticleTypes.ENCHANT, at.x, pos.getY() + (shelf + (slot == 1 || slot == 4 ? 2.6 : 3.0)) / 16.0, at.z, 0, .02, 0);
    }
}
