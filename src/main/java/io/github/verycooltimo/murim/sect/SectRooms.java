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
        if (old != null && (!p.level().isLoaded(old) || bed(p.serverLevel(), old))
                && available(p.serverLevel(), old, owner(p)) && (oldZone == desired || desired == 0)) {
            claim(p.serverLevel(), old, owner(p));
            return destination(p, layout);
        }
        for (int zone : desired == 0 ? new int[] {0, 1} : new int[] {1, 0}) {
            for (BlockPos pos : SectBuildings.beds(p.level(), layout, DORMS.get(zone))) {
                if (p.level().getBlockState(pos).getValue(BedBlock.OCCUPIED)
                        || SectBuildings.approach(p.level(), p.position(), pos) == null
                        || !claim(p.serverLevel(), pos, owner(p))) continue;
                p.setData(ModAttachments.SECT, state.with("room.assigned").with("room.bed")
                        .record("room.pos", pos.asLong()).record("room.zone", zone));
                show(p, "murim.sect.room.bed", Vec3.atCenterOf(pos));
                return destination(p, layout);
            }
        }
        // A promotion waits for a free better bed; it never evicts anybody or loses the old bed.
        if (old != null && p.level().isLoaded(old) && bed(p.serverLevel(), old)
                && available(p.serverLevel(), old, owner(p))) return destination(p, layout);
        SectHousingData data = data(p.serverLevel());
        data.beds.entrySet().removeIf(e -> owner(p).equals(e.getValue()));
        int slot = data.mats.computeIfAbsent(owner(p), key -> data.mats.values().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1);
        data.setDirty();
        boolean fresh = !state.has("room.assigned") || state.has("room.bed");
        p.setData(ModAttachments.SECT, state.with("room.assigned").without("room.bed")
                .record("room.slot", slot).record("room.zone", desired));
        Vec3 at = destination(p, layout);
        if ((fresh || hint) && at != null) show(p, "murim.sect.room.bedroll", at);
        return at;
    }

    private static void show(ServerPlayer p, String key, Vec3 at) {
        BlockPos pos = BlockPos.containing(at);
        p.displayClientMessage(Component.translatable(key, pos.getX(), pos.getY(), pos.getZ()), false);
    }

    public static Vec3 destination(ServerPlayer p, SectLayout layout) {
        BlockPos bed = bed(p);
        if (bed != null) {
            if (!p.level().isLoaded(bed)) return Vec3.atBottomCenterOf(bed);
            return SectBuildings.approach(p.level(), p.position(), bed);
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
