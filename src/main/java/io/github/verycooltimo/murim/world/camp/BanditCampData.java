package io.github.verycooltimo.murim.world.camp;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Состояние лагерей бандитов одного измерения (файл {@code data/murim_bandit_camps.dat}): заселён
 * ли, разгромлен ли, кто из состава жив, когда была последняя потеря. Клиенту не синхронизируется —
 * клиент видит только самих бандитов.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/saveddata/SavedData.java#Factory
 * (как {@link io.github.verycooltimo.murim.world.hua.MountHuaSiteData}).
 */
public final class BanditCampData extends SavedData {

    public static final String NAME = "murim_bandit_camps";

    public static final SavedData.Factory<BanditCampData> FACTORY = new SavedData.Factory<>(BanditCampData::new, BanditCampData::load);

    /** Один лагерь. */
    public static final class Camp {
        public final long key;
        public final BlockPos centre;
        public final long seed;
        public boolean populated;
        public boolean cleared;
        /** Живые члены состава: место → UUID. */
        public final Map<Integer, UUID> alive = new HashMap<>();
        public long lastLoss;
        public long lastAlarm = Long.MIN_VALUE / 2;
        /**
         * Бой (не сохраняется, {@link CampFight}): кто сейчас дерётся вблизи, кто стреляет, до какого
         * тика банда гонится за бежавшим игроком и на каком тике порядок боя пересчитан.
         */
        public final java.util.Set<UUID> melee = new java.util.LinkedHashSet<>();
        public final java.util.Set<UUID> shooters = new java.util.LinkedHashSet<>();
        public long pursuitUntil = Long.MIN_VALUE / 2;
        public long fightTick = Long.MIN_VALUE / 2;
        /**
         * Лагерь из шаблона автора (docs/design/28-location-capture.md): посты, точки появления и состав из знаков
         * {@code spawn:*} шаблона. Не сохраняются — собираются заново при первом посещении после загрузки
         * ({@link CampTemplate#attach}); null у процедурного лагеря.
         */
        public java.util.List<CampLayout.Post> templatePosts;
        public java.util.List<BlockPos> templateFeet;
        public java.util.List<CampRoster.Member> templateRoster;

        Camp(long key, BlockPos centre, long seed) {
            this.key = key;
            this.centre = centre;
            this.seed = seed;
        }
    }

    private final Map<Long, Camp> camps = new HashMap<>();

    public Camp get(long key) {
        return camps.get(key);
    }

    public Camp getOrCreate(long key, BlockPos centre, long seed) {
        Camp c = camps.get(key);
        if (c == null) {
            c = new Camp(key, centre, seed);
            camps.put(key, c);
            setDirty();
        }
        return c;
    }

    public Iterable<Camp> all() {
        return camps.values();
    }

    private static BanditCampData load(CompoundTag tag, HolderLookup.Provider registries) {
        BanditCampData data = new BanditCampData();
        ListTag list = tag.getList("camps", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            Camp c = new Camp(t.getLong("key"), BlockPos.of(t.getLong("centre")), t.getLong("seed"));
            c.populated = t.getBoolean("populated");
            c.cleared = t.getBoolean("cleared");
            c.lastLoss = t.getLong("last_loss");
            CompoundTag alive = t.getCompound("alive");
            for (String k : alive.getAllKeys()) {
                c.alive.put(Integer.parseInt(k), alive.getUUID(k));
            }
            data.camps.put(c.key, c);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Camp c : camps.values()) {
            CompoundTag t = new CompoundTag();
            t.putLong("key", c.key);
            t.putLong("centre", c.centre.asLong());
            t.putLong("seed", c.seed);
            t.putBoolean("populated", c.populated);
            t.putBoolean("cleared", c.cleared);
            t.putLong("last_loss", c.lastLoss);
            CompoundTag alive = new CompoundTag();
            c.alive.forEach((slot, id) -> alive.putUUID(Integer.toString(slot), id));
            t.put("alive", alive);
            list.add(t);
        }
        tag.put("camps", list);
        return tag;
    }
}
