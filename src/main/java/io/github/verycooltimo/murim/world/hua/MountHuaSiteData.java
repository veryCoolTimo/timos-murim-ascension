package io.github.verycooltimo.murim.world.hua;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Persisted position of Mount Hua (overworld data storage, file {@code data/murim_mount_hua.dat}).
 * Written once when the site is chosen and never changed afterwards: the terrain already generated
 * depends on it.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/saveddata/SavedData.java#Factory,
 * reference/minecraft-src/net/minecraft/world/level/storage/DimensionDataStorage.java#computeIfAbsent
 */
public final class MountHuaSiteData extends SavedData {

    public static final String NAME = "murim_mount_hua";

    public static final SavedData.Factory<MountHuaSiteData> FACTORY =
            new SavedData.Factory<>(MountHuaSiteData::new, MountHuaSiteData::load);

    private boolean chosen;
    private int centerX;
    private int centerZ;
    private int baseY;
    private int rotation;
    /** Canonical mountain shape (fits the baked sect). Absent in data of older worlds = false: they keep their world seed. */
    private boolean canonical;

    public MountHuaSiteData() {
    }

    private static MountHuaSiteData load(CompoundTag tag, HolderLookup.Provider registries) {
        MountHuaSiteData data = new MountHuaSiteData();
        data.chosen = tag.getBoolean("chosen");
        data.centerX = tag.getInt("x");
        data.centerZ = tag.getInt("z");
        data.baseY = tag.getInt("base_y");
        data.rotation = tag.getInt("rotation");
        data.canonical = tag.getBoolean("canonical");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("chosen", chosen);
        tag.putInt("x", centerX);
        tag.putInt("z", centerZ);
        tag.putInt("base_y", baseY);
        tag.putInt("rotation", rotation);
        tag.putBoolean("canonical", canonical);
        return tag;
    }

    public boolean chosen() {
        return chosen;
    }

    public void choose(int x, int z, int baseY, int rotation) {
        this.chosen = true;
        this.centerX = x;
        this.centerZ = z;
        this.baseY = baseY;
        this.rotation = rotation;
        this.canonical = true; // every new world gets the canonical mountain and with it the baked sect
        setDirty();
    }

    public MountHuaSite toSite(long worldSeed) {
        return new MountHuaSite(centerX, centerZ, baseY, rotation, worldSeed, canonical);
    }
}
