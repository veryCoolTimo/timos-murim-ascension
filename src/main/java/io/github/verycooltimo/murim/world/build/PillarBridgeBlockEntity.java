package io.github.verycooltimo.murim.world.build;

import io.github.verycooltimo.murim.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Bridge beams of a column (RedPillarBlock.Bridge) toward its +x and +z neighbour. Kept here instead of in two
 * blockstate properties: as properties they multiplied the column to 43,200 states, and 1.21.1 bakes a multipart
 * model per state — the client ran out of memory at model baking (07.10). Bridges are rare; the column has this
 * block entity only while {@link RedPillarBlock#BRIDGED} is true, and the client draws the beams with a renderer.
 * Synced with the vanilla block entity data packet.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/entity/BlockEntity.java (#saveAdditional,
 * #loadAdditional, #getUpdateTag, #getUpdatePacket), ClientboundBlockEntityDataPacket#create.
 */
public class PillarBridgeBlockEntity extends BlockEntity {

    private RedPillarBlock.Bridge bridgeX = RedPillarBlock.Bridge.NONE;
    private RedPillarBlock.Bridge bridgeZ = RedPillarBlock.Bridge.NONE;

    public PillarBridgeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.PILLAR_BRIDGE_ENTITY.get(), pos, state);
    }

    public RedPillarBlock.Bridge get(Direction.Axis axis) {
        return axis == Direction.Axis.X ? bridgeX : bridgeZ;
    }

    public boolean any() {
        return bridgeX != RedPillarBlock.Bridge.NONE || bridgeZ != RedPillarBlock.Bridge.NONE;
    }

    /** Sets a bridge; true when it changed. Syncs to clients. */
    public boolean set(Direction.Axis axis, RedPillarBlock.Bridge b) {
        if (get(axis) == b) {
            return false;
        }
        if (axis == Direction.Axis.X) {
            bridgeX = b;
        } else {
            bridgeZ = b;
        }
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        return true;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("bridge_x", bridgeX.getSerializedName());
        tag.putString("bridge_z", bridgeZ.getSerializedName());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        bridgeX = parse(tag.getString("bridge_x"));
        bridgeZ = parse(tag.getString("bridge_z"));
    }

    private static RedPillarBlock.Bridge parse(String s) {
        for (RedPillarBlock.Bridge b : RedPillarBlock.Bridge.values()) {
            if (b.getSerializedName().equals(s)) {
                return b;
            }
        }
        return RedPillarBlock.Bridge.NONE;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
