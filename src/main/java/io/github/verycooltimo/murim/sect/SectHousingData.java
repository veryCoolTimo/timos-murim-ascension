package io.github.verycooltimo.murim.sect;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/** World-side ownership includes offline players; personal destinations live in the sect attachment. */
public final class SectHousingData extends SavedData {
    public static final Factory<SectHousingData> FACTORY = new Factory<>(SectHousingData::new, SectHousingData::load);
    final Map<Long, String> beds = new HashMap<>();
    final Map<String, Integer> mats = new HashMap<>();
    record Newcomer(String name, long day) { }
    final Map<String, Newcomer> newcomers = new HashMap<>();
    long ceremonyDay = -1;

    public SectHousingData() { }

    static SectHousingData load(CompoundTag tag, HolderLookup.Provider registries) {
        SectHousingData data = new SectHousingData();
        for (Tag entry : tag.getList("beds", Tag.TAG_COMPOUND)) {
            CompoundTag bed = (CompoundTag) entry;
            data.beds.put(bed.getLong("pos"), bed.getString("owner"));
        }
        CompoundTag mats = tag.getCompound("mats");
        for (String owner : mats.getAllKeys()) data.mats.put(owner, mats.getInt(owner));
        CompoundTag newcomers = tag.getCompound("newcomers");
        for (String id : newcomers.getAllKeys()) {
            CompoundTag entry = newcomers.getCompound(id);
            data.newcomers.put(id, new Newcomer(entry.getString("name"), entry.getLong("day")));
        }
        if (tag.contains("ceremony_day")) data.ceremonyDay = tag.getLong("ceremony_day");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag bedsTag = new ListTag();
        beds.forEach((pos, owner) -> {
            CompoundTag bed = new CompoundTag();
            bed.putLong("pos", pos); bed.putString("owner", owner); bedsTag.add(bed);
        });
        tag.put("beds", bedsTag);
        CompoundTag matsTag = new CompoundTag();
        mats.forEach(matsTag::putInt);
        tag.put("mats", matsTag);
        CompoundTag newcomersTag = new CompoundTag();
        newcomers.forEach((id, newcomer) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("name", newcomer.name()); entry.putLong("day", newcomer.day());
            newcomersTag.put(id, entry);
        });
        tag.put("newcomers", newcomersTag);
        tag.putLong("ceremony_day", ceremonyDay);
        return tag;
    }
}
