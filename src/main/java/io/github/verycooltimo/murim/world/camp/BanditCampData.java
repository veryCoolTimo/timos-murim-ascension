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
        // Optional additive fields: old saves retain their roster and acquire timers on first tick.
        public long nextTrip;
        public long tripUntil;
        public BlockPos tripTarget;
        public final java.util.Set<Integer> travellers = new java.util.HashSet<>();
        public final java.util.Set<Integer> returning = new java.util.HashSet<>();
        public long nextCamp;
        public long rebuildRetry;
        public long successor = Long.MIN_VALUE;
        public long refillDay = -1;

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
         * {@code spawn:*} шаблона. Сохраняются для пополнения на рассвете без игрока рядом;
         * обновляются при посещении ({@link CampTemplate#attach}); null у процедурного лагеря.
         */
        // Derived once from seed; never saved or synchronized.
        public CampLayout proceduralLayout;
        public java.util.List<CampRoster.Member> proceduralRoster;
        public java.util.List<BlockPos> activityPoints;
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

    /** Removes saved fixture state; normal camp lifecycle retains its history. */
    public void remove(long key) {
        if (camps.remove(key) != null) setDirty();
    }

    public Iterable<Camp> all() {
        return camps.values();
    }

    static BanditCampData load(CompoundTag tag, HolderLookup.Provider registries) {
        BanditCampData data = new BanditCampData();
        ListTag list = tag.getList("camps", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            Camp c = new Camp(t.getLong("key"), BlockPos.of(t.getLong("centre")), t.getLong("seed"));
            c.populated = t.getBoolean("populated");
            c.cleared = t.getBoolean("cleared");
            c.lastLoss = t.getLong("last_loss");
            c.nextTrip = t.getLong("next_trip");
            c.tripUntil = t.getLong("trip_until");
            if (t.contains("trip_target")) c.tripTarget = BlockPos.of(t.getLong("trip_target"));
            for (int slot : t.getIntArray("travellers")) c.travellers.add(slot);
            for (int slot : t.getIntArray("returning")) c.returning.add(slot);
            c.nextCamp = t.getLong("next_camp");
            c.rebuildRetry = t.getLong("rebuild_retry");
            c.successor = t.contains("successor") ? t.getLong("successor") : Long.MIN_VALUE;
            c.refillDay = t.contains("refill_day") ? t.getLong("refill_day") : -1;

            if (t.contains("template")) {
                c.templatePosts = new java.util.ArrayList<>();
                c.templateRoster = new java.util.ArrayList<>();
                c.templateFeet = t.getBoolean("template_feet") ? new java.util.ArrayList<>() : null;
                ListTag template = t.getList("template", Tag.TAG_COMPOUND);
                for (int j = 0; j < template.size(); j++) {
                    CompoundTag p = template.getCompound(j);
                    c.templatePosts.add(new CampLayout.Post(p.getInt("x"), p.getInt("z"), p.getBoolean("tower"), p.getBoolean("chief")));
                    if (c.templateFeet != null) c.templateFeet.add(BlockPos.of(p.getLong("feet")));
                }
                ListTag roster = t.getList("template_roster", Tag.TAG_COMPOUND);
                for (int j = 0; j < roster.size(); j++) {
                    CompoundTag m = roster.getCompound(j);
                    c.templateRoster.add(new CampRoster.Member(m.getInt("slot"), m.getBoolean("archer"), m.getBoolean("qi"), m.getBoolean("chief"), m.getInt("post")));
                }
            }
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
            t.putLong("next_trip", c.nextTrip);
            t.putLong("trip_until", c.tripUntil);
            if (c.tripTarget != null) t.putLong("trip_target", c.tripTarget.asLong());
            t.putIntArray("travellers", c.travellers.stream().mapToInt(Integer::intValue).toArray());
            t.putIntArray("returning", c.returning.stream().mapToInt(Integer::intValue).toArray());
            t.putLong("next_camp", c.nextCamp);
            t.putLong("rebuild_retry", c.rebuildRetry);
            t.putLong("successor", c.successor);
            t.putLong("refill_day", c.refillDay);

            if (c.templatePosts != null && c.templateRoster != null) {
                ListTag template = new ListTag();
                for (int i = 0; i < c.templatePosts.size(); i++) {
                    CampLayout.Post post = c.templatePosts.get(i);
                    CompoundTag p = new CompoundTag();
                    p.putInt("x", post.dx()); p.putInt("z", post.dz());
                    p.putBoolean("tower", post.onTower()); p.putBoolean("chief", post.chief());
                    if (c.templateFeet != null) p.putLong("feet", c.templateFeet.get(i).asLong());
                    template.add(p);
                }
                ListTag roster = new ListTag();
                for (CampRoster.Member member : c.templateRoster) {
                    CompoundTag m = new CompoundTag();
                    m.putInt("slot", member.slot()); m.putInt("post", member.post());
                    m.putBoolean("archer", member.archer()); m.putBoolean("qi", member.qi());
                    m.putBoolean("chief", member.chief());
                    roster.add(m);
                }
                t.put("template_roster", roster);
                t.put("template", template);
                t.putBoolean("template_feet", c.templateFeet != null);
            }
            CompoundTag alive = new CompoundTag();
            c.alive.forEach((slot, id) -> alive.putUUID(Integer.toString(slot), id));
            t.put("alive", alive);
            list.add(t);
        }
        tag.put("camps", list);
        return tag;
    }
}
