package io.github.verycooltimo.murim.sect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

/** Undo journal in the overworld, including label chunks so clear also works after a restart. */
public final class SectLayoutData extends SavedData {
    public static final String NAME = "murim_sect_layout_boxes";
    public static final SavedData.Factory<SectLayoutData> FACTORY =
            new SavedData.Factory<>(SectLayoutData::new, SectLayoutData::load);

    record Placed(BlockState before, BlockState marker) {
    }

    final Map<BlockPos, Placed> blocks = new LinkedHashMap<>();
    final Map<UUID, BlockPos> labels = new LinkedHashMap<>();
    boolean clearingLabels;

    public SectLayoutData() {
    }

    public static SectLayoutData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public boolean active() {
        return !blocks.isEmpty() || !labels.isEmpty();
    }

    void remember(BlockPos pos, BlockState before, BlockState marker) {
        // If outlines cross, keep the original world state, but track the last marker colour.
        Placed previous = blocks.get(pos);
        blocks.put(pos.immutable(), new Placed(previous == null ? before : previous.before(), marker));
        setDirty();
    }

    void remember(UUID id, BlockPos pos) {
        labels.put(id, pos.immutable());
        setDirty();
    }

    static SectLayoutData load(CompoundTag tag, HolderLookup.Provider registries) {
        SectLayoutData data = new SectLayoutData();
        data.clearingLabels = tag.getBoolean("clearing_labels");
        var blocks = registries.lookupOrThrow(Registries.BLOCK);
        for (Tag t : tag.getList("blocks", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) t;
            data.blocks.put(BlockPos.of(e.getLong("pos")), new Placed(
                    NbtUtils.readBlockState(blocks, e.getCompound("before")),
                    NbtUtils.readBlockState(blocks, e.getCompound("marker"))));
        }
        for (Tag t : tag.getList("labels", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) t;
            data.labels.put(e.getUUID("uuid"), BlockPos.of(e.getLong("pos")));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("clearing_labels", clearingLabels);
        ListTag placed = new ListTag();
        blocks.forEach((pos, entry) -> {
            CompoundTag e = new CompoundTag();
            e.putLong("pos", pos.asLong());
            e.put("before", NbtUtils.writeBlockState(entry.before()));
            e.put("marker", NbtUtils.writeBlockState(entry.marker()));
            placed.add(e);
        });
        tag.put("blocks", placed);
        ListTag displays = new ListTag();
        labels.forEach((id, pos) -> {
            CompoundTag e = new CompoundTag();
            e.putUUID("uuid", id);
            e.putLong("pos", pos.asLong());
            displays.add(e);
        });
        tag.put("labels", displays);
        return tag;
    }
}
