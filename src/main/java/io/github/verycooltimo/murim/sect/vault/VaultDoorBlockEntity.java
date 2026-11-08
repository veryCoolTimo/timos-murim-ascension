package io.github.verycooltimo.murim.sect.vault;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The look of the vault door, kept at its controller cell (col 0, row 0). The transient trial progress is synced from the server; the remaining fields are client side: the swing of the two leaves
 * (they turn inward on their hinges about 96 degrees in 2 seconds and never vanish), how bright the white and the pink seal lines
 * burn, and the grey smoke that billows from the seam and the edges (author 24.10, round 4).
 *
 * <p>The block state is the truth (set by the server); the numbers here only chase it, so a door that loads open stands open.
 * API: reference/minecraft-src/net/minecraft/world/level/block/entity/BlockEntity.java, ChestLidController.java (the chase idea).
 */
public class VaultDoorBlockEntity extends BlockEntity {

    /** How far the leaves turn (degrees) and how long the swing takes (ticks) opening and closing. */
    public static final float OPEN_DEGREES = 96.0F;
    public static final int OPEN_TICKS = 40, CLOSE_TICKS = 30;

    // Transient trial state: synced for tracking clients, deliberately not saved across a reload.
    private float progress, visualProgress, previousProgress;

    public void setProgress(float value) {
        value = Mth.clamp(value, 0, 1);
        if (progress == value) return;
        progress = value;
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
    }

    public float progress(float partial) { return Mth.lerp(partial, previousProgress, visualProgress); }

    // API: same update-tag/packet contract as world/build/PillarBridgeBlockEntity.
    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putFloat("progress", progress);
        return tag;
    }

    @Override
    protected void loadAdditional(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        progress = Mth.clamp(tag.getFloat("progress"), 0, 1);
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    private boolean seen;
    private float swing, prevSwing;
    private float white, prevWhite, pink, prevPink;

    public VaultDoorBlockEntity(BlockPos pos, BlockState state) {
        super(VaultRegistry.VAULT_DOOR_ENTITY.get(), pos, state);
    }

    /** 0 closed .. 1 fully open, eased, for the renderer. */
    public float swing(float partial) {
        float t = Mth.lerp(partial, prevSwing, swing);
        return t * t * (3.0F - 2.0F * t);
    }

    public float white(float partial) {
        return Mth.lerp(partial, prevWhite, white);
    }

    public float pink(float partial) {
        return Mth.lerp(partial, prevPink, pink);
    }

    public boolean moving() {
        return swing > 0.0F && swing < 1.0F;
    }

    static float whiteOf(VaultDoorBlock.State s) {
        return switch (s) {
            case CLOSED -> 0.0F;
            case GLOW1 -> 0.50F;
            case GLOW2 -> 0.80F;
            case GLOW3, GLOW4, PULSE -> 1.0F;
            case OPEN -> 0.45F;
            case DIM -> 0.0F;
        };
    }

    static float pinkOf(VaultDoorBlock.State s) {
        return switch (s) {
            case CLOSED, GLOW1, GLOW2, GLOW3 -> 0.0F;
            case GLOW4 -> 0.45F;
            case PULSE, OPEN -> 1.0F;
            case DIM -> 0.35F;
        };
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, VaultDoorBlockEntity be) {
        be.previousProgress = be.visualProgress;
        be.visualProgress += Mth.clamp(be.progress - be.visualProgress, -0.05F, 0.1F);
        VaultDoorBlock.State st = state.getValue(VaultDoorBlock.STATE);
        boolean open = st == VaultDoorBlock.State.OPEN || st == VaultDoorBlock.State.DIM;
        float tw = st == VaultDoorBlock.State.CLOSED ? Math.min(1.0F, be.visualProgress * 4) : whiteOf(st), tp = st == VaultDoorBlock.State.CLOSED ? Math.max(0, be.visualProgress * 2 - 1) : pinkOf(st), ts = open ? 1.0F : 0.0F;
        if (!be.seen) {
            be.seen = true;
            be.swing = be.prevSwing = ts;
            be.white = be.prevWhite = tw;
            be.pink = be.prevPink = tp;
        }
        be.prevSwing = be.swing;
        be.prevWhite = be.white;
        be.prevPink = be.pink;
        float step = open ? 1.0F / OPEN_TICKS : 1.0F / CLOSE_TICKS;
        be.swing = be.swing < ts ? Math.min(ts, be.swing + step) : Math.max(ts, be.swing - step);
        be.white += Mth.clamp(tw - be.white, -0.08F, 0.18F);
        be.pink += Mth.clamp(tp - be.pink, -0.06F, 0.12F);
        smoke(level, pos, state, be, st);
    }

    /** Grey smoke from the seam and the edges while the lines wake and the leaves part. */
    private static void smoke(Level level, BlockPos pos, BlockState state, VaultDoorBlockEntity be, VaultDoorBlock.State st) {
        RandomSource r = level.random;
        int n = switch (st) {
            case GLOW4 -> r.nextInt(4) == 0 ? 1 : 0;
            case PULSE -> r.nextInt(2);
            case OPEN -> be.moving() ? r.nextInt(2) : r.nextInt(5) == 0 ? 1 : 0;
            default -> 0;
        };
        Direction facing = state.getValue(VaultDoorBlock.FACING);
        double th = Math.toRadians(-facing.toYRot());
        double c = Math.cos(th), s = Math.sin(th);
        for (int i = 0; i < n; i++) {
            // a few soft puffs rolling out of the seam (low) and along the bottom edge, never up over the seal
            double lx, ly;
            if (r.nextBoolean()) {
                lx = 3.0 + (r.nextDouble() - 0.5) * 0.3;
                ly = r.nextDouble() * 0.9;
            } else {
                lx = 0.4 + r.nextDouble() * 5.2;
                ly = r.nextDouble() * 0.4;
            }
            double lz = 0.55 + r.nextDouble() * 0.3;   // inside the thickness of the leaves: the smoke comes OUT of the seam and the edges, in front of nothing
            double dz = lz - 0.5;
            // the controller cell is column 0: local x runs from its west corner; rotate about the cell centre
            double rx = (lx - 0.5), rz = dz;
            double wx = pos.getX() + 0.5 + rx * c + rz * s;
            double wz = pos.getZ() + 0.5 - rx * s + rz * c;
            double out = 0.015 + r.nextDouble() * 0.02;
            level.addParticle(VaultRegistry.VAULT_SMOKE.get(), true, wx, pos.getY() + ly, wz,
                    facing.getStepX() * out, 0.004 + r.nextDouble() * 0.008, facing.getStepZ() * out);
        }
    }
}
