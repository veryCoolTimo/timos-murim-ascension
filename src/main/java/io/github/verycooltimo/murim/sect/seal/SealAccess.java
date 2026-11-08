package io.github.verycooltimo.murim.sect.seal;

import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/** Cold iron doors near the penance stone admit free members; the main vault has its own door trial. */
public final class SealAccess {

    /** Performed the forms before the vault door at least once (author 23.10); the head's Divine Art may be read only with it. */
    public static final String VAULT_FLAG = "vault.opened";
    /** A seal belongs to a door within this many blocks (horizontally; half as much vertically). */
    public static final int RANGE = 12;

    private SealAccess() {
    }

    public static boolean mayOpen(ServerPlayer p, BlockPos door) {
        BlockPos seal = nearest(p.level(), door, RANGE, SealRegistry.PENANCE_SEAL.get());
        if (seal == null) {
            return false;
        }
        // Penance cave: the sentenced never open it (the elders do, at the sentence and the release), but a free member
        // or anyone already inside the cave may, so a released player is never walled in (holes review npc-1).
        return !PenanceService.sentenced(p) && (p.getData(ModAttachments.SECT).member() || insideCave(p, seal));
    }

    /** Within the seal's own room: the cave volume around the cell. */
    static boolean insideCave(ServerPlayer p, BlockPos seal) {
        return p.blockPosition().distSqr(seal) <= (double) (SealAccess.RANGE * SealAccess.RANGE);
    }

    /** Message for a door that did not open. */
    public static String lockedKey(Level level, BlockPos door) {
        BlockPos seal = nearest(level, door, RANGE, SealRegistry.PENANCE_SEAL.get());
        if (seal == null) {
            return "murim.seal.door.locked";
        }
        return "murim.seal.door.penance";
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
