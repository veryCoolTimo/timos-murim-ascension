package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.Comparator;

/**
 * Absence lines (docs/design/39-player-path-v2.md, author 18.10): a member who was away from the sect for a Minecraft day or
 * more (outside the sect land, or logged out that long) is asked «Where have you been, disciple?» once, the first time the
 * mentor or a senior meets him after he is back. No penalty of any kind: one line, variants by how long he was gone
 * (1+, 3+, 7+ days). Fixed journal keys of {@link SectState}, no new save format.
 *
 * <p>Away time is the longer of the game-time gap and the wall-clock gap (20 ticks per second), so a long real-world break
 * counts although game time stood still.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectAbsence {

    /** Journal keys: game tick and wall-clock millisecond of the last check inside the sect land; the tier waiting to be said. */
    public static final String SEEN = "presence.seen";
    public static final String SEEN_WALL = "presence.seen_wall";
    public static final String PENDING = "presence.pending";

    static final long DAY = 24000L;
    /** The mentor or a senior within this many blocks, in sight, says the line. */
    static final double MEET_RANGE = 7.0D;
    static final int PERIOD = 40;

    private SectAbsence() {
    }

    /** Tier of an absence in ticks: 0 (not long enough), 1, 3 or 7 days. */
    public static int tier(long gapTicks) {
        long days = gapTicks / DAY;
        return days >= 7 ? 7 : days >= 3 ? 3 : days >= 1 ? 1 : 0;
    }

    /** Who may scold: the mentor, the head, an elder, the senior disciple. */
    public static boolean senior(SectDisciple npc) {
        SectRole r = npc.role();
        return r == SectRole.MENTOR || r == SectRole.LEADER || r == SectRole.ELDER || r == SectRole.SENIOR;
    }

    /**
     * One check of a member: inside the sect land the gap since the last check is judged and the time stamp renewed; outside
     * nothing changes, so the gap keeps growing. A return after at least a day leaves the tier pending (replacing an older one).
     *
     * @return the tier that became pending, 0 if none
     */
    public static int track(ServerPlayer p, boolean inside, long now, long wallMillis) {
        SectState state = SectService.state(p);
        if (!state.member() || !inside) {
            return 0;
        }
        int found = 0;
        SectState next = state;
        if (state.journal().containsKey(SEEN)) {
            long gap = Math.max(now - state.entry(SEEN, now), (wallMillis - state.entry(SEEN_WALL, wallMillis)) / 50L);
            found = tier(gap);
            if (found > 0) {
                next = next.record(PENDING, found);
                SectEvents.record(p, SectEvents.Event.RETURNED);
            }
        }
        next = next.record(SEEN, now).record(SEEN_WALL, wallMillis);
        p.setData(ModAttachments.SECT, next);
        return found;
    }

    /** Lines per tier and register. */
    public static final int VARIANTS = 4;

    /** The line key of a tier and variant; the argument is the player's name. */
    public static String key(int tier, int variant) {
        return "murim.sect.absence." + tier + "." + (1 + Math.floorMod(variant, VARIANTS));
    }

    /** Lang stem of the lines this player hears for this tier: an office holder's differ (docs/design/39 «Обращение по положению»). */
    public static String stem(SectRank rank, int tier) {
        return rank.office() ? "murim.sect.absence." + (rank == SectRank.ELDER ? "elder" : "first") + "." + tier
                : "murim.sect.absence." + tier;
    }

    /**
     * The line for this player: an elder or a first disciple is welcomed back by his office, never asked «where have you
     * been, disciple» (docs/design/39 «Обращение по положению»).
     */
    public static String key(SectRank rank, int tier, int variant) {
        return stem(rank, tier) + "." + (1 + Math.floorMod(variant, VARIANTS));
    }

    /**
     * A mentor or senior meets the member: if a tier is pending he says the line (a bubble over his head, no chat) and the tier is
     * cleared, so it is said once per return. Returns true when the line was said.
     */
    public static boolean meet(ServerPlayer p, SectDisciple npc) {
        SectState state = SectService.state(p);
        int tier = (int) state.entry(PENDING, 0);
        if (tier <= 0 || !state.member() || !senior(npc) || npc.isSleeping()) {
            return false;
        }
        p.setData(ModAttachments.SECT, state.record(PENDING, 0));
        String key = SectLines.pick(p, stem(SectRank.of(state, p.getData(ModAttachments.PROFILE).rank()), tier), VARIANTS, npc.getRandom());
        Component line = Component.translatable(key, p.getName());
        SectChatter.send(npc, key, line);
        // He has welcomed the player back: the others' «welcome back» bubbles do not repeat it from him (SectEvents).
        SectEvents.markReacted(p, SectEvents.Event.RETURNED, npc);
        return true;
    }

    @SubscribeEvent
    static void playerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.tickCount % PERIOD != 23 || p.isSpectator()) {
            return;
        }
        if (!SectService.state(p).member()) {
            return;
        }
        MountHuaSite site = p.level().dimension() == Level.OVERWORLD ? MountHuaSites.get(p.server) : null;
        boolean inside = site != null && SectTerritory.contains(site, p.getX(), p.getY(), p.getZ());
        track(p, inside, p.level().getGameTime(), System.currentTimeMillis());
        if (SectService.state(p).entry(PENDING, 0) <= 0) {
            return;
        }
        SectDisciple who = p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(MEET_RANGE),
                        d -> d.isAlive() && senior(d) && d.free() && !d.isSleeping() && d.getSensing().hasLineOfSight(p))
                .stream().min(Comparator.comparingDouble(d -> d.distanceToSqr(p))).orElse(null);
        if (who != null) {
            meet(p, who);
        }
    }
}
