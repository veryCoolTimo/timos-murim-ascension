package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Stable dorm beds, including NPC ownership, shared by scans, sleep and walking escorts. */
public final class SectRooms {
    private static final List<String> DORMS = List.of("dorm_3rd", "dorm_2nd");
    private SectRooms() { }

    private static SectHousingData data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(SectHousingData.FACTORY, "murim_sect_rooms");
    }
    public static String owner(SectDisciple npc) {
        return npc.memberKey().isEmpty() ? "npc:" + npc.getUUID() : "npc:" + npc.memberKey();
    }
    private static String owner(ServerPlayer p) { return "player:" + p.getUUID(); }

    private static boolean bed(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.HEAD;
    }

    public static boolean available(ServerLevel level, BlockPos pos, String owner) {
        SectHousingData data = data(level);
        String held = data.beds.get(pos.asLong());
        return held == null || held.equals(owner);
    }

    public static boolean claim(ServerLevel level, BlockPos pos, String owner) {
        if (!available(level, pos, owner)) return false;
        SectHousingData data = data(level);
        if (owner.equals(data.beds.get(pos.asLong()))) return true;
        data.beds.entrySet().removeIf(e -> owner.equals(e.getValue()));
        data.beds.put(pos.asLong(), owner);
        data.setDirty();
        return true;
    }

    /**
     * The person's own bed from the layout: takes it over from a stale claim of another NPC (the old nearest-free-bed
     * scan), never from a player. The NPC's previous claim is dropped, so a bed changes owner and never doubles.
     */
    public static boolean claimOwn(ServerLevel level, BlockPos pos, String owner) {
        SectHousingData data = data(level);
        String held = data.beds.get(pos.asLong());
        if (held != null && !held.equals(owner)) {
            if (held.startsWith("player:")) {
                return false;
            }
            data.beds.remove(pos.asLong());
            data.setDirty();
        }
        return claim(level, pos, owner);
    }

    /** Heads of the beds that belong to players, online or not (the repair crew keeps away from their rooms, doc 39). */
    public static List<BlockPos> playerBeds(ServerLevel level) {
        List<BlockPos> out = new java.util.ArrayList<>();
        for (var e : data(level).beds.entrySet()) {
            if (e.getValue().startsWith("player:")) out.add(BlockPos.of(e.getKey()));
        }
        return out;
    }

    /** Some player sleeps on a bedroll in a dormitory: he has no room of his own to protect, the dormitory is his. */
    public static boolean playerMats(ServerLevel level) {
        for (String owner : data(level).mats.keySet()) {
            if (owner.startsWith("player:")) return true;
        }
        return false;
    }

    /** Dev command: gives the player's bed and bedroll back to the pool. */
    static void release(ServerPlayer p) {
        SectHousingData data = data(p.serverLevel());
        data.beds.entrySet().removeIf(e -> owner(p).equals(e.getValue()));
        data.mats.remove(owner(p));
        data.setDirty();
    }

    public static BlockPos bed(ServerPlayer p) {
        SectState state = SectService.state(p);
        return state.has("room.bed") ? BlockPos.of(state.entry("room.pos", 0)) : null;
    }

    public static Vec3 assign(ServerPlayer p, SectLayout layout, boolean hint) {
        if (layout == null || !SectService.state(p).member()) return null;
        SectState state = SectService.state(p);
        int desired = SectService.standing(p).atLeast(SectStanding.GRADUATE) ? 1 : 0;
        int oldZone = (int) state.entry("room.zone", desired);
        BlockPos old = bed(p);
        boolean keepOld = old != null && (!p.level().isLoaded(old) || bed(p.serverLevel(), old))
                && available(p.serverLevel(), old, owner(p));
        if (keepOld && (oldZone == desired || desired == 0)) {
            claim(p.serverLevel(), old, owner(p));
            SectNamePlates.sync(p, layout);
            return destination(p, layout);
        }
        for (int zone : desired == 0 ? new int[] {0, 1} : new int[] {1, 0}) {
            // An existing assignment is the fallback while promotion waits for a loaded better bed.
            if (keepOld && zone == oldZone) continue;
            // The layout file knows the spare beds at once; the block scan below needs many ticks per dormitory, and the
            // newcomer's bed must exist while the mentor still shows it (arrival tour, 18.10).
            List<BlockPos> candidates = new java.util.ArrayList<>(spare(p.level(), layout, DORMS.get(zone)));
            candidates.addAll(SectBuildings.beds(p.level(), layout, DORMS.get(zone)));
            for (BlockPos pos : candidates) {
                SectLayoutFile.Bed record = layout.file() == null ? null : layout.file().bedAt(pos);
                if (record != null && !DORMS.get(zone).equals(record.building())) continue;
                if (reserved(layout, pos)
                        || p.level().getBlockState(pos).getValue(BedBlock.OCCUPIED)
                        || approach(p, layout, pos) == null
                        || !claim(p.serverLevel(), pos, owner(p))) continue;
                p.setData(ModAttachments.SECT, state.with("room.assigned").with("room.bed")
                        .record("room.pos", pos.asLong()).record("room.zone", zone));
                if (state.has("room.assigned") && oldZone != zone && zone == 1) {
                    p.setRespawnPosition(p.level().dimension(), pos, 0.0F, false, false);
                    p.setData(ModAttachments.SECT, SectService.state(p).without(SectBedMark.SLEPT));
                }
                show(p, "murim.sect.room.bed", Vec3.atCenterOf(pos));
                SectTablet.touch(p);
                SectNamePlates.sync(p, layout);
                return destination(p, layout);
            }
        }
        // A promotion waits for a free better bed; it never evicts anybody or loses the old bed.
        if (keepOld) return destination(p, layout);
        SectHousingData data = data(p.serverLevel());
        data.beds.entrySet().removeIf(e -> owner(p).equals(e.getValue()));
        int slot = data.mats.computeIfAbsent(owner(p), key -> data.mats.values().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1);
        data.setDirty();
        boolean fresh = !state.has("room.assigned") || state.has("room.bed");
        p.setData(ModAttachments.SECT, state.with("room.assigned").without("room.bed")
                .record("room.slot", slot).record("room.zone", desired));
        SectNamePlates.sync(p, layout);
        Vec3 at = destination(p, layout);
        if ((fresh || hint) && at != null) show(p, "murim.sect.room.bedroll", at);
        return at;
    }

    /**
     * Where to stand beside a bed: the free cell next to the head block, else the cell the layout file recorded for it (many
     * dormitory beds stand against a wall or a neighbour and only their foot side is open).
     */
    static Vec3 approach(ServerPlayer p, SectLayout layout, BlockPos head) {
        Vec3 at = SectBuildings.approach(p.level(), p.position(), head);
        if (at != null || layout == null || layout.file() == null) return at;
        SectLayoutFile.Bed record = layout.file().bedAt(head);
        if (record == null || !p.level().isLoaded(record.approach()) || !SectLife.standable(p.level(), record.approach())) return null;
        return Vec3.atBottomCenterOf(record.approach());
    }

    /**
     * A bed the layout file keeps for somebody: it has an owner, or any role but "spare". The block scan below sees every
     * bed in the dormitory, claimed or not; one that belongs to a person who has not slept in it yet is never a newcomer's.
     * When the spare beds run out the newcomer sleeps on the bedroll.
     */
    static boolean reserved(SectLayout layout, BlockPos head) {
        SectLayoutFile file = layout == null ? null : layout.file();
        SectLayoutFile.Bed bed = file == null ? null : file.bedAt(head);
        return bed != null && (!"spare".equals(bed.role()) || bed.ownerKey() != null && !bed.ownerKey().isEmpty());
    }

    /** Heads of the layout file's spare beds in a building that really are loaded bed blocks (a stale record is skipped). */
    static List<BlockPos> spare(net.minecraft.world.level.Level level, SectLayout layout, String building) {
        SectLayoutFile file = layout == null ? null : layout.file();
        if (file == null) return List.of();
        return file.beds().stream().filter(b -> building.equals(b.building()) && "spare".equals(b.role())
                        && level.isLoaded(b.head()) && level.getBlockState(b.head()).getBlock() instanceof BedBlock
                        && level.getBlockState(b.head()).getValue(BedBlock.PART) == BedPart.HEAD)
                .map(SectLayoutFile.Bed::head).toList();
    }

    private static void show(ServerPlayer p, String key, Vec3 at) {
        BlockPos pos = BlockPos.containing(at);
        SectNotice.chat(p, Component.translatable(key, pos.getX(), pos.getY(), pos.getZ()));
    }

    public static Vec3 destination(ServerPlayer p, SectLayout layout) {
        BlockPos bed = bed(p);
        if (bed != null) {
            if (!p.level().isLoaded(bed)) return Vec3.atBottomCenterOf(bed);
            return approach(p, layout, bed);
        }
        int zone = (int) SectService.state(p).entry("room.zone", 0);
        int slot = (int) SectService.state(p).entry("room.slot", 0);
        String dorm = DORMS.get(Math.floorMod(zone, DORMS.size()));
        double[] half = layout.half(dorm);
        int columns = half == null ? 4 : Math.max(1, (int) ((half[0] * 2 - 6) / 1.5D));
        Vec3 at = layout.at(dorm, -((columns - 1) * 0.75D) + slot % columns * 1.5D,
                (half == null ? -3 : -Math.max(0, half[1] - 3)) + slot / columns * 1.5D);
        return at == null ? null : SectLife.stand(p.level(), at);
    }
}
