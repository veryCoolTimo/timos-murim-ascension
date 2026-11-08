package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What the player takes out of the sect's chests and cabinets (playtest 22.10). The containers carry loot tables by room
 * ({@code data/murim/loot_table/chests/sect/*}); when the player closes one with fewer items than he opened it with, the
 * sect remembers: a flag {@code pilfered.<kind>} and, in the journal, the game time {@code pilfer.<kind>}. A while later one
 * of the people nearby mentions it in a line over his head. No penalty (docs: sandbox, not strict).
 *
 * <p>Hook for the storeroom-thief quest: {@link #FLAG_STOREROOM} / {@link #took(ServerPlayer, String)}; the quest reads the
 * flag and the time, this class only records and speaks.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectPilfer {
    /** Flag set when the player took something out of a storeroom container. */
    public static final String FLAG_STOREROOM = "pilfered.supplies";
    /** Ticks between the taking and the remark. */
    static final long NOTICE_AFTER = 1200L;
    private static final String FLAG = "pilfered.";
    private static final String TIME = "pilfer.";
    private static final Map<UUID, Integer> OPENED = new HashMap<>();

    /** Server stopped: forget cross-world state. */
    public static void forget(UUID id) { OPENED.remove(id); }

    public static void clearOpened() { OPENED.clear(); }

    private SectPilfer() { }

    /** Loot kind of a room by function (the same table as tools/build/sect/patch_playtest_1022.py). Null outside the rooms. */
    static String kindOf(SectLayoutFile.Room room) {
        if (room == null) return null;
        return switch (room.function()) {
            case "dining_hall" -> "food";
            case "sleeping_room", "single_room" -> "room";
            case "pharmacy" -> "medicine";
            case "library", "study", "study_hall", "lecture_hall", "counting_room" -> "scrolls";
            default -> "supplies";
        };
    }

    /** The sect kind of the container at {@code pos}, or null when it is not in the sect. */
    static String kindAt(ServerLevel level, BlockPos pos) {
        SectLayout layout = SectLife.layout(level);
        SectLayoutFile file = layout == null ? null : layout.file();
        if (file == null) return null;
        SectLayoutFile.Room room = file.roomAt(pos);
        if (room == null) room = file.roomAt(pos.above());
        return kindOf(room);
    }

    static int count(Container c) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty()) n += s.getCount();
        }
        return n;
    }

    private static BlockPos posOf(ChestMenu menu) {
        return menu.getContainer() instanceof BlockEntity be ? be.getBlockPos() : null;
    }

    @SubscribeEvent
    static void open(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !(event.getContainer() instanceof ChestMenu menu)) return;
        if (posOf(menu) != null) OPENED.put(p.getUUID(), count(menu.getContainer()));
    }

    @SubscribeEvent
    static void close(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !(event.getContainer() instanceof ChestMenu menu)) return;
        Integer before = OPENED.remove(p.getUUID());
        BlockPos pos = posOf(menu);
        if (before == null || pos == null || count(menu.getContainer()) >= before) return;
        String kind = kindAt(p.serverLevel(), pos);
        if (kind != null) took(p, kind);
    }

    /** Records that the player took something from a container of this kind. */
    public static void took(ServerPlayer p, String kind) {
        SectState s = SectService.state(p).with(FLAG + kind).record(TIME + kind, p.level().getGameTime());
        p.setData(ModAttachments.SECT, s);
        io.github.verycooltimo.murim.advancement.MurimAdvancements.grant(p, "sticky_fingers");
    }

    /** True when the player has taken something from a container of this kind. */
    public static boolean has(ServerPlayer p, String kind) {
        return SectService.state(p).has(FLAG + kind);
    }

    @SubscribeEvent
    static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.tickCount % 100 != 0) return;
        for (String kind : new String[] {"food", "room", "scrolls", "supplies", "medicine"}) {
            long at = SectService.state(p).entry(TIME + kind, 0L);
            if (at == 0L || p.level().getGameTime() - at < NOTICE_AFTER) continue;
            SectDisciple who = p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(14.0D)).stream()
                    .min(java.util.Comparator.comparingDouble(d -> d.distanceToSqr(p))).orElse(null);
            if (who == null) continue;   // nobody near: the remark waits (the time stays recorded)
            who.queueBubble("pilfer." + kind, Component.translatable("murim.bubble.pilfer." + kind), 20);
            p.setData(ModAttachments.SECT, SectService.state(p).record(TIME + kind, 0L));   // said once per taking; the flag stays
            return;
        }
    }
}
