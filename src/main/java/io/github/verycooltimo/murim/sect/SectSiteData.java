package io.github.verycooltimo.murim.sect;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;

/**
 * На каких площадках горы NPC секты уже поставлены (ставятся один раз, при первом приходе игрока).
 * API: reference/minecraft-src/net/minecraft/world/level/saveddata/SavedData.java#Factory
 */
public final class SectSiteData extends SavedData {

    public static final String NAME = "murim_sect_npcs";

    public static final SavedData.Factory<SectSiteData> FACTORY = new SavedData.Factory<>(SectSiteData::new, SectSiteData::load);

    private final Set<String> placed = new HashSet<>();

    public SectSiteData() {
    }

    private static SectSiteData load(CompoundTag tag, HolderLookup.Provider registries) {
        SectSiteData data = new SectSiteData();
        for (Tag t : tag.getList("placed", Tag.TAG_STRING)) {
            data.placed.add(t.getAsString());
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        placed.stream().sorted().forEach(z -> list.add(StringTag.valueOf(z)));
        tag.put("placed", list);
        return tag;
    }

    public boolean placed(String zone) {
        return placed.contains(zone);
    }

    public void place(String zone) {
        if (placed.add(zone)) {
            setDirty();
        }
    }

    /** Забыть (человек секты погиб — встанет снова, когда рядом никого не будет). */
    public void unplace(String zone) {
        if (placed.remove(zone)) {
            setDirty();
        }
    }

    // Не сохраняется: состояние текущего сеанса сервера (живёт в экземпляре, а не в static).

    /** Чужак у ворот секты — глава выходит навстречу. */
    boolean outsiderAtGate;
    /** Последний удар колокола: часть суток × 4 + номер удара, и день — чтобы не звонить дважды. */
    long lastRing = Long.MIN_VALUE;

    /** Нарушители закрытых мест: игрок → что было (предупреждения, толчки, поединок). Только этот сеанс. */
    final java.util.Map<java.util.UUID, SectWatch.Trespass> trespass = new java.util.HashMap<>();
}
