package io.github.verycooltimo.murim.decor;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Invisible seat on a {@link SeatBlock} (meditation cushion, chairs, benches...): no physics, not saved (EntityType
 * noSave), drawn by a no-op renderer (client/decor/HallFurnitureClient). The server removes it as soon as nobody sits
 * on it or the seat block is gone. The rider is placed by the type's passenger attachment (HallFurniture#SEAT); the
 * entity stands {@code seatTop - 0.225} above the block floor, so a 3.6 px cushion keeps the old height. No synced state.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/entity/Entity.java (#defineSynchedData, #getDismountLocationForPassenger),
 * world/entity/vehicle/AbstractMinecart.java (a ridden non-living entity).
 */
public class SeatEntity extends Entity {

    /** Hip height of a sitter above the entity's seat surface reference (the cushion calibration). */
    private static final double CUSHION_TOP = 0.225D;

    public SeatEntity(EntityType<? extends SeatEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
    }

    /** Seats {@code player} on the seat block at {@code pos}; false when it is taken, not a seat, or the player rides already. */
    public static boolean sit(Level level, BlockPos pos, Player player) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof SeatBlock seat) || seat.seatTop(state) <= 0.0D) {
            return false;
        }
        if (player.isPassenger() || player.isShiftKeyDown()) {
            return false;
        }
        List<SeatEntity> seats = level.getEntitiesOfClass(SeatEntity.class, new AABB(pos).inflate(0.2D));
        if (!seats.isEmpty()) {
            return false;
        }
        SeatEntity entity = new SeatEntity(HallFurniture.SEAT.get(), level);
        entity.setPos(pos.getX() + 0.5D, pos.getY() + seat.seatTop(state) - CUSHION_TOP, pos.getZ() + 0.5D);
        level.addFreshEntity(entity);
        return player.startRiding(entity);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    private BlockState seatState() {
        return level().getBlockState(BlockPos.containing(getX(), getY() + 0.4D, getZ()));
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide) {
            BlockState state = seatState();
            if (!isVehicle() || !(state.getBlock() instanceof SeatBlock seat) || seat.seatTop(state) <= 0.0D) {
                ejectPassengers();
                discard();
            }
        }
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        BlockPos pos = BlockPos.containing(getX(), getY() + 0.4D, getZ());
        BlockState state = level().getBlockState(pos);
        Vec3 centre = new Vec3(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
        if (state.getBlock() instanceof SeatBlock seat && seat.seatTop(state) > 0.3D) {
            // A chair: step out to a free side (front first, then the sides and the back), else on top of the seat.
            Direction front = seat.seatFront(state);
            Direction[] order = front == null
                    ? new Direction[] {Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST}
                    : new Direction[] {front, front.getClockWise(), front.getCounterClockWise(), front.getOpposite()};
            for (Direction d : order) {
                Vec3 spot = centre.add(d.getStepX(), 0.0D, d.getStepZ());
                AABB box = passenger.getBoundingBox().move(spot.subtract(passenger.position()));
                if (level().noCollision(passenger, box) && !level().noCollision(box.move(0.0D, -0.6D, 0.0D).inflate(-0.05D, 0.0D, -0.05D))) {
                    return spot;
                }
            }
            return centre.add(0.0D, 1.0D, 0.0D);
        }
        // Stand up on the cushion (its top is 3.6 px).
        return new Vec3(getX(), Math.floor(getY()) + 0.25D, getZ());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
