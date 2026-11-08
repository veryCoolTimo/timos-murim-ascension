package io.github.verycooltimo.murim.sect.repair;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the sect repairs remember (docs/design/39-player-path-v2.md, «Ремонт секты и казна»), saved with the
 * overworld: where the baked sect lost a block and when, which positions a player built into (never repaired), the
 * blocks a player placed that must stay untouched together with their surroundings, the treasury and the chronicle of big
 * repairs. The crew's job and the event bookkeeping are transient: after a restart the damage list is all that is left,
 * and the crew starts afresh.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/saveddata/SavedData.java#Factory,
 * reference/minecraft-src/net/minecraft/nbt/LongArrayTag.java.
 */
public final class RepairData extends SavedData {
    public static final String NAME = "murim_sect_repairs";
    public static final SavedData.Factory<RepairData> FACTORY = new SavedData.Factory<>(RepairData::new, RepairData::load);

    /** Most damaged positions kept: a world-sized explosion must not make the save grow without end. */
    public static final int MAX_DAMAGE = 60000;
    /** Most lines of the chronicle. */
    public static final int MAX_CHRONICLE = 8;

    /** One big repair as the tablet lists it. {@code place} is a place function ({@code main_hall}), "" when unknown. */
    public record Entry(long day, String place, int blocks, int wen) {
    }

    /** Position (long) to the game time it was first seen empty. */
    final Long2LongOpenHashMap damage = new Long2LongOpenHashMap();
    /** Positions a player built into (the block placed there is his, whatever it was). */
    final LongOpenHashSet placed = new LongOpenHashSet();
    /** Positions of player-placed containers, beds and workstations: they and their surroundings are never repaired. */
    final LongOpenHashSet guards = new LongOpenHashSet();
    /** Treasury in wen; negative until first read (then it starts at the table's start). */
    long treasury = -1L;
    /** Last sect day the daily income was paid for. */
    long incomeDay = Long.MIN_VALUE;
    final List<Entry> chronicle = new ArrayList<>();

    // ---- transient: not saved
    /** The crew at work, or null. */
    RepairJob job;
    /** Positions a creative player is breaking this tick: his edits are the author's building, not damage. */
    final LongOpenHashSet creativeBreaks = new LongOpenHashSet();
    long creativeTick = Long.MIN_VALUE;
    /** Game time of the last command: whatever a command changes this tick (fill, setblock, clone) is not damage. */
    long commandTick = Long.MIN_VALUE;
    /** Seeds the crew could not reach, until when. */
    final Long2LongOpenHashMap banned = new Long2LongOpenHashMap();
    /** The treasury has been told to be empty (one line in the log, not one per pass). */
    boolean brokeNoted;
    /** Cached environment of the running server and its key (the site frame it was built for). */
    RepairEnv env;
    String envKey = "";
    int scanCursor;
    /** Test hook: when set, the events and the service use this environment instead of the live sect. */
    RepairEnv envOverride;

    public RepairData() {
    }

    /** The data of the overworld: the sect lives there only. */
    public static RepairData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public static RepairData get(ServerLevel level) {
        return get(level.getServer());
    }

    static RepairData load(CompoundTag tag, HolderLookup.Provider registries) {
        RepairData d = new RepairData();
        long[] dmg = tag.getLongArray("damage");
        for (int i = 0; i + 1 < dmg.length; i += 2) {
            d.damage.put(dmg[i], dmg[i + 1]);
        }
        for (long p : tag.getLongArray("placed")) {
            d.placed.add(p);
        }
        for (long p : tag.getLongArray("guards")) {
            d.guards.add(p);
        }
        d.treasury = tag.contains("treasury") ? tag.getLong("treasury") : -1L;
        d.incomeDay = tag.contains("income_day") ? tag.getLong("income_day") : Long.MIN_VALUE;
        for (Tag t : tag.getList("chronicle", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) t;
            d.chronicle.add(new Entry(e.getLong("day"), e.getString("place"), e.getInt("blocks"), e.getInt("wen")));
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        long[] dmg = new long[damage.size() * 2];
        int i = 0;
        for (Long2LongOpenHashMap.Entry e : damage.long2LongEntrySet()) {
            dmg[i++] = e.getLongKey();
            dmg[i++] = e.getLongValue();
        }
        tag.put("damage", new LongArrayTag(dmg));
        tag.put("placed", new LongArrayTag(placed.toLongArray()));
        tag.put("guards", new LongArrayTag(guards.toLongArray()));
        tag.putLong("treasury", treasury);
        if (incomeDay != Long.MIN_VALUE) {
            tag.putLong("income_day", incomeDay);
        }
        ListTag list = new ListTag();
        for (Entry e : chronicle) {
            CompoundTag c = new CompoundTag();
            c.putLong("day", e.day());
            c.putString("place", e.place());
            c.putInt("blocks", e.blocks());
            c.putInt("wen", e.wen());
            list.add(c);
        }
        tag.put("chronicle", list);
        return tag;
    }

    // ------------------------------------------------------------------ read access (tests, the tablet, the command)

    /** Treasury in wen. */
    public long treasury() {
        if (treasury < 0L) {
            treasury = RepairCosts.shipped().startWen();
            setDirty();
        }
        return treasury;
    }

    public void setTreasury(long wen) {
        treasury = Math.max(0L, wen);
        brokeNoted = false;
        setDirty();
    }

    public void credit(long wen) {
        if (wen > 0L) {
            setTreasury(treasury() + wen);
        }
    }

    /** Takes {@code wen} if the treasury holds that much; false (and nothing taken) otherwise. */
    public boolean debit(long wen) {
        if (treasury() < wen) {
            return false;
        }
        treasury -= wen;
        setDirty();
        return true;
    }

    /** Lines of the chronicle, oldest first. */
    public List<Entry> chronicle() {
        return List.copyOf(chronicle);
    }

    void log(Entry entry) {
        chronicle.add(entry);
        while (chronicle.size() > MAX_CHRONICLE) {
            chronicle.remove(0);
        }
        setDirty();
    }

    public int damageCount() {
        return damage.size();
    }

    public boolean damaged(long pos) {
        return damage.containsKey(pos);
    }

    /** Game time the position was first seen empty; Long.MIN_VALUE when it is not damaged. */
    public long damagedSince(long pos) {
        return damage.containsKey(pos) ? damage.get(pos) : Long.MIN_VALUE;
    }

    public boolean playerPlaced(long pos) {
        return placed.contains(pos);
    }

    public boolean guarded(long pos) {
        return guards.contains(pos);
    }

    /** GameTests: run the events and the service against this environment (null: the live sect again). Never set in play. */
    public void useEnv(RepairEnv env) {
        this.envOverride = env;
    }

    /** The crew's current job, or null. */
    public RepairJob job() {
        return job;
    }

    /** Dev and tests: every record becomes {@code ticks} older (the fast-forward of the grace period). */
    public int age(long ticks) {
        for (Long2LongOpenHashMap.Entry e : damage.long2LongEntrySet()) {
            e.setValue(e.getLongValue() - ticks);
        }
        setDirty();
        return damage.size();
    }

    /** Forget all damage and player marks (tests, the reset command). */
    public void clear() {
        damage.clear();
        placed.clear();
        guards.clear();
        banned.clear();
        job = null;
        setDirty();
    }
}
