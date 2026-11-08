package io.github.verycooltimo.murim.mastery;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A small in-memory ring of the last {@link #SIZE} mastery gains of each player, for {@code /murim progress}: where the
 * experience came from, how much, and (when it was small) why. Not saved and not synchronised; it is cleared on logout.
 * Architecture: docs/adr/progress-command.md. Fed from the few points where experience is awarded ({@link MasteryService}).
 */
public final class ProgressLog {

    public static final int SIZE = 20;

    /** Where a gain came from. {@code key} names the line in the language files ({@code command.murim.progress.src.*}). */
    public enum Origin {
        FIGHT, SPARRING, TRAINING, DUMMY, PAGE, LESSON, READING, GUIDANCE, RUSH, ECHO, SLEEP, METHOD;

        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Why a gain was small or nothing. {@code NONE} — nothing wrong. */
    public enum Why {
        NONE, WEAK_TARGET, FATIGUE, MONOTONY, POOL_FULL, NOTHING_TO_PROCESS, COMPLETE;

        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** One gain: game time, the art, origin, amount in experience units and the reason for a small one. */
    public record Entry(long tick, ResourceLocation art, Origin origin, double amount, Why why) {
    }

    private final Deque<Entry> ring = new ArrayDeque<>();

    public ProgressLog() {
    }

    public static void record(ServerPlayer player, ResourceLocation formOrArt, Origin origin, double amount, Why why) {
        Deque<Entry> ring = player.getData(io.github.verycooltimo.murim.registry.ModAttachments.PROGRESS_LOG).ring;
        synchronized (ring) {
            ring.addFirst(new Entry(player.level().getGameTime(), Arts.masteryId(formOrArt), origin, amount, why));
            while (ring.size() > SIZE) {
                ring.removeLast();
            }
        }
    }

    /** Newest first. */
    public static List<Entry> entries(ServerPlayer player) {
        if (!player.hasData(io.github.verycooltimo.murim.registry.ModAttachments.PROGRESS_LOG)) {
            return List.of();
        }
        Deque<Entry> ring = player.getData(io.github.verycooltimo.murim.registry.ModAttachments.PROGRESS_LOG).ring;
        synchronized (ring) {
            return new ArrayList<>(ring);
        }
    }

    public static void clear(ServerPlayer player) {
        player.removeData(io.github.verycooltimo.murim.registry.ModAttachments.PROGRESS_LOG);
    }

    @EventBusSubscriber(modid = MurimMod.MODID)
    static final class Events {
        @SubscribeEvent
        static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                clear(player);
            }
        }
    }
}
