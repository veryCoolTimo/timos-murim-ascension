package io.github.verycooltimo.murim.sect.seal;

import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * Who a cold iron door lets through: the nearest seal decides. A door by a vault seal opens for the one who passed
 * the trial (flag {@link #VAULT_FLAG}, kept for good); a door by a penance seal opens only by the elders (the
 * sentence and the release open it); a door without a seal nearby opens for nobody in survival.
 */
public final class SealAccess {

    /** Passed the vault trial (or let in by the leader): the same pass {@code SectAccess} reads. */
    public static final String VAULT_FLAG = "vault.permitted";
    /** A seal belongs to a door within this many blocks (horizontally; half as much vertically). */
    public static final int RANGE = 12;

    private SealAccess() {
    }

    public static boolean mayOpen(ServerPlayer p, BlockPos door) {
        BlockPos seal = nearest(p.level(), door, RANGE, SealRegistry.VAULT_SEAL.get(), SealRegistry.PENANCE_SEAL.get());
        if (seal == null) {
            return false;
        }
        if (p.level().getBlockState(seal).is(SealRegistry.VAULT_SEAL.get())) {
            return p.getData(ModAttachments.SECT).has(VAULT_FLAG);
        }
        return false;
    }

    /** Message for a door that did not open. */
    public static String lockedKey(Level level, BlockPos door) {
        BlockPos seal = nearest(level, door, RANGE, SealRegistry.VAULT_SEAL.get(), SealRegistry.PENANCE_SEAL.get());
        if (seal == null) {
            return "murim.seal.door.locked";
        }
        return level.getBlockState(seal).is(SealRegistry.VAULT_SEAL.get()) ? "murim.seal.door.vault" : "murim.seal.door.penance";
    }

    /** Nearest block of the given kinds within {@code r} (horizontal) and {@code r / 2} (vertical), or null. */
    public static BlockPos nearest(Level level, BlockPos at, int r, Block... kinds) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        int ry = Math.max(2, r / 2);
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-r, -ry, -r), at.offset(r, ry, r))) {
            if (!level.isLoaded(p)) {
                continue;
            }
            var state = level.getBlockState(p);
            for (Block k : kinds) {
                if (state.is(k)) {
                    double d = p.distSqr(at);
                    if (d < bestD) {
                        bestD = d;
                        best = p.immutable();
                    }
                }
            }
        }
        return best;
    }

    /** Every cold iron door (lower half) within {@code r} of {@code at}. */
    public static java.util.List<BlockPos> doors(Level level, BlockPos at, int r) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        int ry = Math.max(2, r / 2);
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-r, -ry, -r), at.offset(r, ry, r))) {
            var state = level.isLoaded(p) ? level.getBlockState(p) : null;
            if (state != null && state.getBlock() instanceof ColdIronDoorBlock
                    && state.getValue(ColdIronDoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) {
                out.add(p.immutable());
            }
        }
        return out;
    }

    /** Opens every cold iron door near {@code at} (they shut themselves after a while). */
    public static int openDoors(Level level, BlockPos at, int r, int ticks) {
        int n = 0;
        for (BlockPos d : doors(level, at, r)) {
            var state = level.getBlockState(d);
            if (state.getBlock() instanceof ColdIronDoorBlock door) {
                door.open(level, d, state, true, ticks);
                n++;
            }
        }
        return n;
    }
}
