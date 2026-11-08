package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The sect notices what happens to the player (docs/design/39-player-path-v2.md, «Реплики: объём и частота»): a bout won or
 * lost today, a breakthrough to the next realm, a new star of a technique, a handed-in contract, a return after a long
 * absence; and the weather and the night. A person who is near, free and in sight says ONE short line over his head (a
 * bubble, never the chat).
 *
 * <p>Rules, all checked by {@code SectEventsGameTests}:
 * <ul>
 *   <li>each person reacts at most once to one occurrence of an event ({@link SectMemory#reacted});</li>
 *   <li>at most {@link #MAX_REACTORS} people per occurrence, and one reaction per {@link #PLAYER_GAP} ticks per player;</li>
 *   <li>an event lives a sect day ({@link #LIVE}); thunder and the night live while they last;</li>
 *   <li>the words follow the {@link SectVoice.Register}: nobody lectures, hurries or sizes up an elder or a first disciple,
 *       and a servant does not talk to a disciple the way the first generation does.</li>
 * </ul>
 * State is on the player ({@link SectMemory}), none in static fields.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectEvents {
    /** What can be reacted to; {@code variants} is the size of each register's pool. */
    public enum Event {
        REALM_UP(4, true), CONTRACT(6, true), BOUT_WON(6, true), BOUT_LOST(6, true), STAR_UP(6, true), RETURNED(4, true),
        /** An awake person meets a wanderer at night. */
        NIGHT_PASS(6, false),
        /** A storm is on: anyone near says what he thinks of it (same eight lines for every register, nothing is said to the player). */
        THUNDER(8, false);

        private final int variants;
        private final boolean recorded;

        Event(int variants, boolean recorded) {
            this.variants = variants;
            this.recorded = recorded;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public int variants() {
            return variants;
        }

        /** Stamped by the game when it happens (false: it is a state of the world). */
        public boolean recorded() {
            return recorded;
        }

        /** The lang stem of the pool this register uses. */
        public String pool(SectVoice.Register r) {
            return this == THUNDER ? "murim.bubble.thunder" : "murim.react." + id() + "." + r.id();
        }
    }

    /** An event lives one sect day. */
    public static final long LIVE = 24000L;
    /** The most people who answer one occurrence of an event. */
    public static final int MAX_REACTORS = 3;
    /** Ticks between two reactions to one player. */
    public static final long PLAYER_GAP = 400L;
    /** No reaction this long after a monster hurt the player. */
    static final int CALM_TICKS = 100;
    /** A person must have been quiet this long to speak. */
    static final long QUIET = 200L;
    /** He must be this close (blocks) and see the player. */
    public static final double RANGE = 9.0D;
    private static final String GAP = "gap";

    private SectEvents() {
    }

    /** Lang keys of every reaction pool (the data test checks them in both languages). */
    public static List<String> allKeys() {
        List<String> out = new ArrayList<>();
        for (Event e : Event.values()) {
            if (e == Event.THUNDER) {
                continue;
            }
            for (SectVoice.Register r : SectVoice.Register.values()) {
                out.addAll(SectLines.keys(e.pool(r), e.variants()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ what happened

    private static SectMemory memory(ServerPlayer p) {
        return p.getData(ModAttachments.SECT_MEMORY);
    }

    /** The event has just happened to this player: the clock starts and nobody has reacted to it yet. */
    public static void record(ServerPlayer p, Event e) {
        if (e.recorded() && p != null && !p.level().isClientSide) {
            p.setData(ModAttachments.SECT_MEMORY, memory(p).withEvent(e.id(), p.level().getGameTime()));
            SectSpeechWitnesses.record(p, e.id());
            if (e == Event.REALM_UP || e == Event.STAR_UP || e == Event.BOUT_WON) SectSpeechWitnesses.record(p, "progress", e.id());
            SectLessons.of(p.serverLevel()).progress(p, e);
        }
    }

    /** Episode id of a world event now, or -1 when it is not on. */
    static long episode(ServerPlayer p, Event e) {
        long day = SectSchedule.day(p.level().getDayTime());
        return switch (e) {
            case THUNDER -> p.serverLevel().isThundering() ? day * 10L + 2L : -1L;
            case NIGHT_PASS -> SectSchedule.at(p.level().getDayTime()) == SectSchedule.Period.NIGHT ? day * 10L + 3L : -1L;
            default -> -1L;
        };
    }

    /** The event is on for this player: recorded within the last day, or the weather / night is on. */
    public static boolean live(ServerPlayer p, Event e) {
        if (e.recorded()) {
            long stamp = memory(p).event(e.id());
            return stamp != Long.MIN_VALUE && p.level().getGameTime() - stamp < LIVE;
        }
        return episode(p, e) >= 0;
    }

    /** A world event starts a new occurrence when its episode changes (a new storm, the next night). */
    private static void sync(ServerPlayer p) {
        for (Event e : Event.values()) {
            if (e.recorded()) {
                continue;
            }
            long episode = episode(p, e);
            if (episode >= 0 && memory(p).event(e.id()) != episode) {
                p.setData(ModAttachments.SECT_MEMORY, memory(p).withEvent(e.id(), episode));
            }
        }
    }

    static long bit(SectDisciple npc) {
        return npc.member().map(m -> 1L << m.index()).orElse(0L);
    }

    /** This person already answered the current occurrence of the event. */
    public static boolean reacted(ServerPlayer p, Event e, SectDisciple npc) {
        long bit = bit(npc);
        return bit == 0L || (memory(p).reacted(e.id()) & bit) != 0L;
    }

    /** Count this person as having answered (also when he spoke in another way: the giver's thanks, the mentor's absence line). */
    public static void markReacted(ServerPlayer p, Event e, SectDisciple npc) {
        long bit = bit(npc);
        if (bit != 0L) {
            SectMemory m = memory(p);
            p.setData(ModAttachments.SECT_MEMORY, m.withReacted(e.id(), m.reacted(e.id()) | bit));
            SectLessons.of(npc).acknowledged(p, e, npc);
        }
    }

    // ------------------------------------------------------------------ who answers

    /** A person who may say a line to the player now: awake, free, near, in sight, quiet for a while. */
    static boolean ready(SectDisciple d, ServerPlayer p, long now) {
        if (!d.isAlive() || d.member().isEmpty() || d.layout() == null || d.level() != p.level() || !SectReactions.watches(d)
                || !d.free() || d.inBout() || d.blocking() || d.approachKind() != null || d.spar() != SectDisciple.Spar.NONE
                || SectLessons.of(d).engaged(d)) {
            return false;
        }
        SectSchedule.Kind k = d.doingKind();
        if (k == SectSchedule.Kind.FORM_ROW || k == SectSchedule.Kind.INSPECT || k == SectSchedule.Kind.MEDITATE
                || k == SectSchedule.Kind.SPAR) {
            return false;
        }
        return now - d.bubbleAt() > QUIET && Math.abs(d.getY() - p.getY()) <= 4.0D
                && d.distanceToSqr(p) <= RANGE * RANGE && d.getSensing().hasLineOfSight(p);
    }

    /**
     * One attempt: the nearest ready person who has not answered the freshest live event says his line. Returns him, or null.
     * Called every two seconds per player by the tick handler; GameTests call it directly.
     */
    public static SectDisciple react(ServerPlayer p) {
        SectState s = SectService.state(p);
        if (!s.member() || SectPositions.disciplined(s) || p.isSpectator() || !p.isAlive() || p.level().isClientSide) {
            return null;
        }
        long now = p.level().getGameTime();
        // Not in the middle of a fight with a monster: a «well fought» bubble would be a mockery (the event stays alive for a day).
        if (p.getLastHurtByMobTimestamp() > 0 && p.tickCount - p.getLastHurtByMobTimestamp() < CALM_TICKS) {
            return null;
        }
        sync(p);
        SectLessons.of(p.serverLevel()).ensureProgress(p);
        SectMemory m = memory(p);
        if (m.event(GAP) != Long.MIN_VALUE && now - m.event(GAP) < PLAYER_GAP) {
            return null;
        }
        // Freshest recorded event first; the weather and the night after them.
        List<Event> live = new ArrayList<>();
        for (Event e : Event.values()) {
            if (live(p, e) && Long.bitCount(m.reacted(e.id())) < MAX_REACTORS) {
                live.add(e);
            }
        }
        if (live.isEmpty()) {
            return null;
        }
        live.sort(Comparator.comparingLong((Event e) -> e.recorded() ? -m.event(e.id()) : Long.MAX_VALUE));
        List<SectDisciple> near = p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(RANGE),
                d -> ready(d, p, now));
        near.sort(Comparator.comparingDouble((SectDisciple d) -> d.distanceToSqr(p)).thenComparing(SectDisciple::memberKey));
        for (Event e : live) {
            for (SectDisciple d : near) {
                if (!reacted(p, e, d) && (!e.recorded() || d.speechGuard().witnessed(e.id(), now))) {
                    say(p, d, e, now);
                    return d;
                }
            }
        }
        return null;
    }

    static void say(ServerPlayer p, SectDisciple d, Event e, long now) {
        SectVoice.Register r = SectVoice.register(d, SectRank.of(p));
        String key = SectLines.pick(p, e.pool(r), e.variants(), d.getRandom());
        if (e != Event.THUNDER) {
            d.attend(p, 40);
        }
        if (!SectChatter.send(d, key, Component.translatable(key))) return;
        markReacted(p, e, d);
        p.setData(ModAttachments.SECT_MEMORY, memory(p).withStamp(GAP, now));
        MurimMod.LOGGER.info("Sect reaction: {} says {} to {} ({})", d.memberKey(), key, p.getName().getString(), e.id());
    }

    @SubscribeEvent
    static void tick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer p && p.tickCount % 40 == 31 && !p.isSpectator()) {
            react(p);
        }
    }
}
