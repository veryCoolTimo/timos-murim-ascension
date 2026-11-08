package io.github.verycooltimo.murim.sect;

import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Runtime state of the bouts of this server session: bouts in progress, defeat analyses waiting for the mentor, the
 * drill of the day, duels and duel requests. Not saved (a restart ends every bout; the drill and the bout wins live
 * in the {@link SectState} journal). Lives in {@link SectSiteData}, not in static fields.
 */
final class SectBoutSession {
    final Map<UUID, SectBouts.Bout> active = new HashMap<>();
    final Map<UUID, SectBouts.Mode> request = new HashMap<>();
    /** The quest trial a bout about to begin is for (set only while {@code SectBouts#beginRule} opens it). */
    final Map<UUID, String> trials = new HashMap<>();
    final Map<UUID, SectDefeat.Pending> pending = new HashMap<>();
    final Map<UUID, SectDefeat.Run> drills = new HashMap<>();
    /** The master's word on the round just fought, waiting for the speaker to be near ({@link SectCorrection}). */
    final Map<UUID, SectCorrection.Pending> corrections = new HashMap<>();
    final Map<UUID, SectDuel.Duel> duels = new HashMap<>();
    final Map<UUID, SectDuel.Request> requests = new HashMap<>();
    /** The review day whose morning reminder was sent. */
    long remindedDay = Long.MIN_VALUE;

    static SectBoutSession of(ServerPlayer p) {
        return SectLife.data(p.serverLevel()).session;
    }
}
