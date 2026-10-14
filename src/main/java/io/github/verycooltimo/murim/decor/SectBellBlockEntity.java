package io.github.verycooltimo.murim.decor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The swing of a {@link SectBellBlock}. Server: the game time of the last strike (cooldown only, not saved). Client:
 * ticks since the ring block event and the swing direction, read by the renderer; a swing lasts {@link #SWING_TICKS}
 * like the vanilla bell's.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/entity/BellBlockEntity.java (#triggerEvent, #clientTick,
 * ticks/shaking/clickDirection).
 */
public class SectBellBlockEntity extends BlockEntity {

    public static final int SWING_TICKS = 50;
    private static final int COOLDOWN = 8;

    private long lastRing = Long.MIN_VALUE / 2;
    // client
    private boolean shaking;
    private int ticks;
    private Direction towards = Direction.NORTH;

    public SectBellBlockEntity(BlockPos pos, BlockState state) {
        super(HallFurniture.SECT_BELL_ENTITY.get(), pos, state);
    }

    boolean mayRing(long now) {
        return now - lastRing >= COOLDOWN;
    }

    void rang(long now) {
        lastRing = now;
    }

    @Override
    public boolean triggerEvent(int id, int param) {
        if (id == SectBellBlock.EVENT_RING) {
            towards = Direction.from2DDataValue(param);
            ticks = 0;
            shaking = true;
            return true;
        }
        return super.triggerEvent(id, param);
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, SectBellBlockEntity be) {
        if (be.shaking && ++be.ticks >= SWING_TICKS) {
            be.shaking = false;
            be.ticks = 0;
        }
    }

    public boolean shaking() {
        return shaking;
    }

    public int ticks() {
        return ticks;
    }

    public Direction towards() {
        return towards;
    }
}
