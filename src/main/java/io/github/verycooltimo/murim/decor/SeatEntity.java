package io.github.verycooltimo.murim.decor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Invisible seat on a {@link MeditationCushionBlock}: no physics, not saved (EntityType noSave), drawn by a no-op
 * renderer (client/decor/HallFurnitureClient). The server removes it as soon as nobody sits on it or the cushion is
 * gone. The rider is placed by the type's passenger attachment (HallFurniture#SEAT). No synced state.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/entity/Entity.java (#defineSynchedData, #getDismountLocationForPassenger),
 * world/entity/vehicle/AbstractMinecart.java (a ridden non-living entity).
 */
public class SeatEntity extends Entity {

    public SeatEntity(EntityType<? extends SeatEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && (!isVehicle() || !level().getBlockState(blockPosition()).is(HallFurniture.MEDITATION_CUSHION.get()))) {
            ejectPassengers();
            discard();
        }
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
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
