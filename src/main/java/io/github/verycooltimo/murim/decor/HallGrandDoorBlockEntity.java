package io.github.verycooltimo.murim.decor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The swing of the grand door, kept at its origin part (COL 0 ROW 0).
 *
 * <p>Server: {@code busy} counts the swing down; at the end of an opening swing the parts get OPEN (passable).
 * {@code powered} remembers the last redstone level, as DoorBlock's POWERED does. Both are saved.
 *
 * <p>Client: {@code progress} 0 (closed) .. 1 (open) moves towards {@code target} by 1/{@link HallGrandDoorBlock#SWING_TICKS}
 * a tick. The target comes from the block event the server sends when a swing starts (chest-lid way); a gate that
 * loads, or whose event was missed, settles to its OPEN state instead. The settle waits a few ticks, because at the end
 * of an opening swing the event's target (open) is ahead of the state (OPEN arrives with the block update).
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/entity/ChestLidController.java (#tickLid, #getOpenness),
 * ChestBlockEntity.java#triggerEvent, BlockEntity.java (#loadAdditional, #saveAdditional).
 */
public class HallGrandDoorBlockEntity extends BlockEntity {

    private static final int SETTLE_TICKS = 10;

    private int busy;
    private boolean opening;
    private boolean powered;

    // client
    private float progress;
    private float prevProgress;
    private float target = -1.0F;
    private int settle;

    public HallGrandDoorBlockEntity(BlockPos pos, BlockState state) {
        super(HallGrandDoors.ENTITY.get(), pos, state);
    }

    public boolean busy() {
        return busy > 0;
    }

    /** True while an opening swing is under way (the parts are not OPEN yet). */
    public boolean opening() {
        return busy > 0 && opening;
    }

    public boolean powered() {
        return powered;
    }

    public void setPowered(boolean powered) {
        this.powered = powered;
        setChanged();
    }

    void startOpening() {
        busy = HallGrandDoorBlock.SWING_TICKS;
        opening = true;
        setChanged();
    }

    void startClosing() {
        busy = HallGrandDoorBlock.SWING_TICKS;
        opening = false;
        setChanged();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, HallGrandDoorBlockEntity be) {
        if (be.busy > 0 && --be.busy == 0) {
            if (be.opening && state.getBlock() instanceof HallGrandDoorBlock gate) {
                gate.setOpen(level, pos, true);
            }
            be.opening = false;
            be.setChanged();
        }
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, HallGrandDoorBlockEntity be) {
        float stateTarget = state.getValue(HallGrandDoorBlock.OPEN) ? 1.0F : 0.0F;
        if (be.target < 0.0F) {
            be.target = be.progress = be.prevProgress = stateTarget;
        }
        be.prevProgress = be.progress;
        if (be.progress != be.target) {
            float step = 1.0F / HallGrandDoorBlock.SWING_TICKS;
            be.progress = be.target > be.progress ? Math.min(be.target, be.progress + step) : Math.max(be.target, be.progress - step);
            be.settle = 0;
        } else if (be.target != stateTarget) {
            if (++be.settle >= SETTLE_TICKS) {
                be.target = stateTarget;
                be.settle = 0;
            }
        } else {
            be.settle = 0;
        }
    }

    @Override
    public boolean triggerEvent(int id, int param) {
        if (id == HallGrandDoorBlock.EVENT_SWING) {
            if (level != null && level.isClientSide) {
                if (target < 0.0F) {
                    // Loaded mid-swing: start from the other end.
                    progress = prevProgress = param == 1 ? 0.0F : 1.0F;
                }
                target = param == 1 ? 1.0F : 0.0F;
                settle = 0;
            }
            return true;
        }
        return super.triggerEvent(id, param);
    }

    /** Swing 0..1 eased (slow start, slow stop: a heavy leaf). */
    public float openness(float partialTick) {
        float t = Mth.lerp(partialTick, prevProgress, progress);
        if (target < 0.0F) {
            t = getBlockState().getValue(HallGrandDoorBlock.OPEN) ? 1.0F : 0.0F;
        }
        return t * t * (3.0F - 2.0F * t);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        busy = tag.getInt("busy");
        opening = tag.getBoolean("opening");
        powered = tag.getBoolean("powered");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("busy", busy);
        tag.putBoolean("opening", opening);
        tag.putBoolean("powered", powered);
    }
}
