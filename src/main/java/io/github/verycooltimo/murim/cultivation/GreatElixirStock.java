package io.github.verycooltimo.murim.cultivation;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** World-wide finite Soul Vitality supply, author 31.10. Other great elixirs already use finite world placements.
 * API: existing sect/vault/VaultData SavedData factory/load/save pattern (MC 1.21.1).
 * Server only; clients see the shop's existing server-built text and stock payloads.
 */
public final class GreatElixirStock extends SavedData {
    public static final int LIMIT = 3;
    private static final Factory<GreatElixirStock> FACTORY = new Factory<>(GreatElixirStock::new, GreatElixirStock::load);
    private int issued;

    public static GreatElixirStock get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, "murim_great_elixirs");
    }

    public static GreatElixirStock load(CompoundTag tag, HolderLookup.Provider registries) {
        GreatElixirStock stock = new GreatElixirStock();
        stock.issued = Math.max(0, Math.min(LIMIT, tag.getInt("soul_vitality_issued")));
        return stock;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("soul_vitality_issued", issued);
        return tag;
    }

    public int remaining() { return LIMIT - issued; }

    public boolean issue() {
        if (remaining() == 0) return false;
        issued++;
        setDirty();
        return true;
    }
}
