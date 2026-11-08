package io.github.verycooltimo.murim.world.fortress;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Состояние крепостей уровня (docs/design/26-boss.md §2): где плац, кто хозяин, когда повержен.
 * Клиенту не синхронизируется — клиент видит только самого хозяина.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/saveddata/SavedData.java#Factory
 * (образец — world/camp/BanditCampData).
 */
public final class FortressData extends SavedData {

    public static final String NAME = "murim_fortresses";

    public static final SavedData.Factory<FortressData> FACTORY = new SavedData.Factory<>(FortressData::new, FortressData::load);

    /** Одна крепость. Ключ — чанк начала структуры (в тестах — любое число). */
    public static final class Entry {
        public final long key;
        public final BlockPos yard;
        public final Rotation rotation;
        public final long seed;
        public UUID master;
        /** Игровое время последней победы над хозяином, 0 — не повержен. */
        public long defeatedAt;
        public int defeats;

        public Entry(long key, BlockPos yard, Rotation rotation, long seed) {
            this.key = key;
            this.yard = yard;
            this.rotation = rotation;
            this.seed = seed;
        }

        /** Кресло хозяина в мире. */
        public BlockPos throne() {
            return FortressBuilder.world(yard.getX(), yard.getY() + 1, yard.getZ(), rotation, 0, FortressBuilder.THRONE_Z);
        }

        /** Сундук сокровищницы в мире. */
        public BlockPos vaultChest() {
            return FortressBuilder.world(yard.getX(), yard.getY() + 1, yard.getZ(), rotation, 0, FortressBuilder.VAULT_Z);
        }
    }

    // World-wide treasure claims live in the overworld copy. Additive keys default to false on old saves.
    // Server-only: clients see the resulting chest/items, no custom synchronization needed.
    boolean greatRestorationPlaced;
    boolean ascendingDragonPlaced;

    private final Map<Long, Entry> entries = new HashMap<>();

    public Entry get(long key) {
        return entries.get(key);
    }

    public Entry getOrCreate(long key, BlockPos yard, Rotation rotation, long seed) {
        Entry e = entries.get(key);
        if (e == null) {
            e = new Entry(key, yard.immutable(), rotation, seed);
            entries.put(key, e);
            setDirty();
        }
        return e;
    }

    /** Крепость, построенная командой или тестом (заменяет прежнюю с тем же ключом). */
    public void put(Entry e) {
        entries.put(e.key, e);
        setDirty();
    }

    /** Forget a fortress (a test must not leave a live record that later spawns a master near other tests). */
    public void remove(long key) {
        if (entries.remove(key) != null) {
            setDirty();
        }
    }

    public Collection<Entry> all() {
        return entries.values();
    }

    public void changed() {
        setDirty();
    }

    static FortressData load(CompoundTag tag, HolderLookup.Provider registries) {
        FortressData data = new FortressData();
        data.greatRestorationPlaced = tag.getBoolean("GreatRestorationPlaced");
        data.ascendingDragonPlaced = tag.getBoolean("AscendingDragonPlaced");
        for (Tag t : tag.getList("Fortresses", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            int[] y = c.getIntArray("Yard");
            if (y.length != 3) {
                continue;
            }
            Entry e = new Entry(c.getLong("Key"), new BlockPos(y[0], y[1], y[2]),
                    Rotation.values()[Math.floorMod(c.getInt("Rot"), 4)], c.getLong("Seed"));
            if (c.hasUUID("Master")) {
                e.master = c.getUUID("Master");
            }
            e.defeatedAt = c.getLong("DefeatedAt");
            e.defeats = c.getInt("Defeats");
            data.entries.put(e.key, e);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("GreatRestorationPlaced", greatRestorationPlaced);
        tag.putBoolean("AscendingDragonPlaced", ascendingDragonPlaced);
        ListTag list = new ListTag();
        for (Entry e : entries.values()) {
            CompoundTag c = new CompoundTag();
            c.putLong("Key", e.key);
            c.putIntArray("Yard", new int[] {e.yard.getX(), e.yard.getY(), e.yard.getZ()});
            c.putInt("Rot", e.rotation.ordinal());
            c.putLong("Seed", e.seed);
            if (e.master != null) {
                c.putUUID("Master", e.master);
            }
            c.putLong("DefeatedAt", e.defeatedAt);
            c.putInt("Defeats", e.defeats);
            list.add(c);
        }
        tag.put("Fortresses", list);
        return tag;
    }
}
