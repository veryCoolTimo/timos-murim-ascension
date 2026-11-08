package io.github.verycooltimo.murim.sect.vault;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * What the world remembers about the vault entrance: until when the tablets of the Ancestors' Hall stay silent after somebody was
 * caught at them (the sect «changes the lock»: no fine, the way is just shut for a day, doc 42).
 */
public final class VaultData extends SavedData {

    public static final Factory<VaultData> FACTORY = new Factory<>(VaultData::new, VaultData::load);
    private long lockedUntil = Long.MIN_VALUE / 2;
    private int caught;

    public VaultData() {
    }

    static VaultData load(CompoundTag tag, HolderLookup.Provider registries) {
        VaultData d = new VaultData();
        d.lockedUntil = tag.contains("locked") ? tag.getLong("locked") : d.lockedUntil;
        d.caught = tag.getInt("caught");
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("locked", lockedUntil);
        tag.putInt("caught", caught);
        return tag;
    }

    public static VaultData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, "murim_vault");
    }

    public boolean locked(long now) {
        return lockedUntil > now;
    }

    public void lock(long until) {
        lockedUntil = until;
        caught++;
        setDirty();
    }

    public void unlock() {
        lockedUntil = Long.MIN_VALUE / 2;
        setDirty();
    }

    public int caught() {
        return caught;
    }
}
