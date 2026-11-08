package io.github.verycooltimo.murim.quest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Holds the spot id of a {@link ClueBlock}. */
public final class ClueBlockEntity extends BlockEntity {

    private String spot = "";

    public ClueBlockEntity(BlockPos pos, BlockState state) {
        super(ModQuests.CLUE_ENTITY.get(), pos, state);
    }

    public String spot() {
        return spot;
    }

    public void setSpot(String spot) {
        this.spot = spot;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("spot", spot);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        spot = tag.getString("spot");
    }
}
