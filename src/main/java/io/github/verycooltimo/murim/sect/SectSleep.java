package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.SleepFinishedTimeEvent;

/**
 * Sleeping through the night must not skip the morning formation (holes review npc-4). Vanilla sets the day time to the
 * next multiple of 24000, which is already 1000 ticks into the formation (it runs 23000 to 1000): everyone in the dorm gets
 * "go to the square" at once and the yard stays empty while the player is awake. When a sect member sleeps in a sect bed ({@link #inSectBed}), the sleep ends
 * {@link #BEFORE_DAWN} ticks earlier, so the lead-time logic ({@link SectLead}, the walk from the dorm is up to 2000 ticks)
 * has the sect on its way to the square before the bell and in line when the player gets there.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/event/level/SleepFinishedTimeEvent.java (#setTimeAddition refuses a
 * time before the present one).
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectSleep {

    /** The player wakes this many ticks before the day that vanilla would start (the bell rings 1000 before it). */
    public static final long BEFORE_DAWN = 2000L;

    private SectSleep() {
    }

    /** Day time the sleep ends at: the vanilla one, earlier by {@link #BEFORE_DAWN}, never before {@code now}. */
    public static long wakeTime(long vanilla, long now) {
        long t = vanilla - BEFORE_DAWN;
        return t > now ? t : vanilla;
    }

    /**
     * True when the player lies in a Mount Hua sect bed: the bed assigned to him, or any bed the sect layout file records (a
     * dormitory bed). Any other bed (a camp, a house far away) gives the vanilla wake-up (author 21.10).
     */
    public static boolean inSectBed(ServerLevel level, ServerPlayer p) {
        net.minecraft.core.BlockPos pos = p.getSleepingPos().orElse(null);
        if (pos == null) {
            return false;
        }
        if (SectBedMark.sleepingInOwnBed(p)) {
            return true;
        }
        SectLayout layout = SectLife.layout(level);
        return layout != null && layout.file() != null && layout.file().bedAt(pos) != null;
    }

    @SubscribeEvent
    static void onSleepFinished(SleepFinishedTimeEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
            return;
        }
        for (ServerPlayer p : level.players()) {
            if (p.getData(ModAttachments.SECT).member() && inSectBed(level, p)) {
                event.setTimeAddition(wakeTime(event.getNewTime(), level.getDayTime()));
                return;
            }
        }
    }
}
