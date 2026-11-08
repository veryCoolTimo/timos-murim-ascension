package io.github.verycooltimo.murim.sect;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What the sect remembers about blood spilled on the mountain (docs/design/50-sect-war.md): who is at war with it, who was warned,
 * which people of the roster are dead (their slots stay empty until a successor arrives) and the new names the slots now carry.
 * All times are sect day time ({@code ServerLevel#getDayTime}), so sleeping through a night counts.
 */
public final class SectWarData extends SavedData {

    public static final String NAME = "murim_sect_war";
    public static final SavedData.Factory<SectWarData> FACTORY = new SavedData.Factory<>(SectWarData::new, SectWarData::load);

    /** One player the whole mountain is against. */
    public static final class War {
        public long since;
        public long lastKill;
        public int kills;
        public boolean permanent;
        public boolean wasMember;
        public boolean messenger;
        public UUID messengerId;
    }

    /** A slot of the roster whose person died; {@code due} is when the successor arrives. */
    public record Dead(long diedAt, long due) { }

    public final Map<UUID, War> wars = new HashMap<>();
    /** Player -> day time of the last warning. */
    public final Map<UUID, Long> warnings = new HashMap<>();
    public final Map<String, Dead> dead = new LinkedHashMap<>();
    /** Roster key -> the number of the name (pool entry) the slot's current person carries. */
    public final Map<String, Integer> names = new HashMap<>();
    /** The last day a disciple-class successor arrived (they come one by one, a couple of days apart). */
    public long lastArrival = Long.MIN_VALUE / 2;
    public int cursor;

    public SectWarData() { }

    static SectWarData load(CompoundTag tag, HolderLookup.Provider registries) {
        SectWarData d = new SectWarData();
        for (Tag t : tag.getList("wars", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            War w = new War();
            w.since = c.getLong("since");
            w.lastKill = c.getLong("last_kill");
            w.kills = c.getInt("kills");
            w.permanent = c.getBoolean("permanent");
            w.wasMember = c.getBoolean("was_member");
            w.messenger = c.getBoolean("messenger");
            if (c.hasUUID("messenger_id")) w.messengerId = c.getUUID("messenger_id");
            d.wars.put(c.getUUID("player"), w);
        }
        for (Tag t : tag.getList("warnings", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            d.warnings.put(c.getUUID("player"), c.getLong("at"));
        }
        for (Tag t : tag.getList("dead", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            d.dead.put(c.getString("key"), new Dead(c.getLong("died"), c.getLong("due")));
        }
        for (Tag t : tag.getList("names", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            d.names.put(c.getString("key"), c.getInt("n"));
        }
        d.lastArrival = tag.contains("last_arrival") ? tag.getLong("last_arrival") : Long.MIN_VALUE / 2;
        d.cursor = tag.getInt("cursor");
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag wl = new ListTag();
        wars.forEach((id, w) -> {
            CompoundTag c = new CompoundTag();
            c.putUUID("player", id);
            c.putLong("since", w.since);
            c.putLong("last_kill", w.lastKill);
            c.putInt("kills", w.kills);
            c.putBoolean("permanent", w.permanent);
            c.putBoolean("was_member", w.wasMember);
            c.putBoolean("messenger", w.messenger);
            if (w.messengerId != null) c.putUUID("messenger_id", w.messengerId);
            wl.add(c);
        });
        tag.put("wars", wl);
        ListTag nl = new ListTag();
        warnings.forEach((id, at) -> {
            CompoundTag c = new CompoundTag();
            c.putUUID("player", id);
            c.putLong("at", at);
            nl.add(c);
        });
        tag.put("warnings", nl);
        ListTag dl = new ListTag();
        dead.forEach((key, v) -> {
            CompoundTag c = new CompoundTag();
            c.putString("key", key);
            c.putLong("died", v.diedAt());
            c.putLong("due", v.due());
            dl.add(c);
        });
        tag.put("dead", dl);
        ListTag names = new ListTag();
        this.names.forEach((key, n) -> {
            CompoundTag c = new CompoundTag();
            c.putString("key", key);
            c.putInt("n", n);
            names.add(c);
        });
        tag.put("names", names);
        tag.putLong("last_arrival", lastArrival);
        tag.putInt("cursor", cursor);
        return tag;
    }

    /** For tests: forget everything. */
    public void reset() {
        wars.clear();
        warnings.clear();
        dead.clear();
        names.clear();
        lastArrival = Long.MIN_VALUE / 2;
        cursor = 0;
        setDirty();
    }
}
