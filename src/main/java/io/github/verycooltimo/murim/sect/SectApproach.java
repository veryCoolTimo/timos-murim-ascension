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
import java.util.Set;

/**
 * People of the sect walk up to the player by themselves (doc 39 item 7): a greeting by standing, a rumour, a spar
 * offer, a reminder of the current goal, an invitation to the formation or the table, a request for help (it hands
 * out a sect request). One approach per {@link #INTERVAL} ticks per player, never during combat, meditation, a
 * dialogue or a bout. The person stops {@link SectDisciple#APPROACH_STANDOFF} blocks away and shows a short bubble over his
 * head (the chat stays empty, doc 39 «Чат по минимуму»); if the player turns to him, the words he came to say are the first
 * line of the talk ({@link #takeOpeningLine}). He holds a few seconds and goes back to work; ignored, he gives up after
 * {@link #PATIENCE} ticks. Nothing here is a duty: ignoring an approach costs nothing.
 *
 * <p>What he says depends on who he is ({@link SectVoice.Voice}: servants are humble, the third generation talks as peers,
 * the second as brisk seniors, the first generation is strict and dry, the elders are formal) and on the rank of the player
 * ({@link SectRank}). Every pool has {@link #VARIANTS} lines and goes through the player's memory ({@link SectLines}), so
 * the same words are not repeated within a day.
 *
 * <p>An office holder ({@link SectRank#office}) gets no newcomer talk: no welcome, no goal reminder, no ladder favour,
 * no «come to the line» for an elder. His approaches are greetings by office, a bout asked by a junior, the table, a
 * rumour, congratulations or comfort after a bout, each in the words of {@link #officeLine} (docs/design/39).
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectApproach {
    /** Journal key (fixed, overwritten): game time of the last approach to this player. */
    public static final String LAST = "approach.last";
    /** Longest gap between two approaches to one player (five minutes); each gap is drawn between three and five. */
    public static final long INTERVAL = 6000L;
    public static final long INTERVAL_MIN = 3600L;
    /** Ticks the same person is not sent again. */
    public static final long PERSON_PAUSE = 12000L;
    /** Ticks a person tries to reach the player before giving up. */
    public static final int PATIENCE = 600;
    static final double MIN_RANGE = 6.0D;
    static final double MAX_RANGE = 24.0D;
    /** Fixed journal keys (bounded by the roster): when this person last came, and until which day he was sent away. */
    private static final String AT = "approach.at.";
    private static final String NO = "approach.no.";
    private static final String GAP = "approach.gap";
    /** Lines in every pool of every voice and kind (data test {@code SectLinesTest}). */
    public static final int VARIANTS = SectLines.MIN_VARIANTS;
    /** People with greetings of their own, added to the pool of their voice. */
    static final Set<String> PERSONAL = Set.of("tae_gyun", "tae_rok", "tae_seong", "gyeong_pil", "gyeong_cho", "gyeong_tak");

    public enum Kind {
        GREET, RUMOUR, SPAR, GOAL, FORMATION, TABLE, HELP, WELCOME, CONGRATS, CONSOLE,
        /** An invitation of the office (docs/design/38): the council of elders, an audit, the morning line; {@code request} is its id. */
        INVITE,
        /** Reserved lesson progress; the shared lesson executor owns walking and event identity. */
        TEACHER;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What is decided for one approach: who, and what for (help: which request). */
    public record Plan(SectDisciple npc, Kind kind, String request) { }

    private SectApproach() { }

    // ------------------------------------------------------------------ when

    /** The player is in the middle of something an approach must not interrupt (also used to cancel one on the way). */
    public static boolean occupied(ServerPlayer p) {
        if (!p.isAlive() || p.isSpectator() || p.isSleeping() || p.isFallFlying() || p.isPassenger() || p.isUsingItem() || p.isSprinting()) return true;
        if (p.tickCount - p.getLastHurtByMobTimestamp() < 300 && p.getLastHurtByMobTimestamp() > 0) return true;
        if (p.tickCount - p.getLastHurtMobTimestamp() < 300 && p.getLastHurtMobTimestamp() > 0) return true;
        return p.getData(ModAttachments.MEDITATION).active() || p.getData(ModAttachments.DIALOGUE).active();
    }

    /** Occupied, or already in a bout, or somebody is on the way to him. */
    public static boolean busy(ServerPlayer p) {
        if (occupied(p)) return true;
        for (SectDisciple d : p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(40.0D))) {
            if (p.getUUID().equals(d.partner()) || d.approachKind() != null && d.blockTarget() == p.getId()) return true;
        }
        return false;
    }

    /** The rate limit alone: one approach per {@link #INTERVAL} per player. */
    public static boolean due(ServerPlayer p) {
        SectState s = SectService.state(p);
        return p.level().getGameTime() - s.entry(LAST, Long.MIN_VALUE / 2) >= s.entry(GAP, INTERVAL);
    }

    private static boolean willing(SectDisciple d, ServerPlayer p) {
        return d.isAlive() && d.free() && !d.dormant() && !d.escorting() && !d.blocking() && !d.wounded() && !d.isSleeping()
                && !d.resting() && !SectLessons.of(p.serverLevel()).engaged(d) && d.member().isPresent() && d.level() == p.level()
                && d.role() != SectRole.LEADER && d.role() != SectRole.GATEKEEPER && d.role() != SectRole.GUARD
                && !sentAway(p, d) && p.level().getGameTime() - SectService.state(p).entry(AT + d.memberKey(), Long.MIN_VALUE / 2) >= PERSON_PAUSE
                && Math.abs(d.getY() - p.getY()) <= 4.0D;
    }

    /** "Not now": this person does not come to this player again until the next sect day. */
    static boolean sentAway(ServerPlayer p, SectDisciple d) {
        return SectService.state(p).entry(NO + d.memberKey(), -1L) >= SectSchedule.day(p.level().getDayTime());
    }

    public static void decline(ServerPlayer p, SectDisciple d) {
        SectLessons.of(p.serverLevel()).decline(p,d);
        p.setData(ModAttachments.SECT, SectService.state(p).record(NO + d.memberKey(), SectSchedule.day(p.level().getDayTime())));
        if (d.approachKind() != null) d.clearBlock();
        PlayerJournal.refuse(p, Component.translatable("murim.approach.declined", d.getName()));
    }

    /** People who could walk up now: awake, free, in range but not already next to the player. */
    static List<SectDisciple> candidates(ServerPlayer p) {
        List<SectDisciple> out = new ArrayList<>(p.level().getEntitiesOfClass(SectDisciple.class,
                p.getBoundingBox().inflate(MAX_RANGE), d -> willing(d, p)));
        out.removeIf(d -> d.distanceToSqr(p) < MIN_RANGE * MIN_RANGE || d.distanceToSqr(p) > MAX_RANGE * MAX_RANGE);
        out.sort(Comparator.comparingDouble((SectDisciple d) -> d.distanceToSqr(p)).thenComparing(SectDisciple::memberKey));
        return out;
    }

    /**
     * What to do now, or null. {@code roll} (non-negative) only decides between several fitting kinds, so a test can
     * walk through all of them.
     */
    public static Plan choose(ServerPlayer p, int roll) {
        SectLessons lessons=SectLessons.of(p.serverLevel());
        SectLessons.Request request=lessons.requests.get(p.getUUID());
        if(request!=null && request.event!=null && !occupied(p)) {
            for(SectDisciple d:lessons.people()) if(SectLessons.teacher(d) && SectLessons.available(d) && !lessons.engaged(d)
                    && !sentAway(p,d) && d.distanceToSqr(p)<=SectLessons.n("progressRange",25)*SectLessons.n("progressRange",25)) return new Plan(d,Kind.TEACHER,request.event.id());
        }
        SectState s = SectService.state(p);
        if (!s.member() || SectPositions.disciplined(s) || busy(p) || !due(p)) return null;
        SectSchedule.Period period = SectSchedule.at(p.level().getDayTime());
        if (period == SectSchedule.Period.NIGHT) return null;
        List<SectDisciple> people = candidates(p);
        if (people.isEmpty()) return null;
        SectRank rank = SectRank.of(s, p.getData(ModAttachments.PROFILE).rank());
        List<Plan> options = new ArrayList<>();
        SectAttendance.Day today = SectAttendance.current(p).today();
        // An elder is never called to the line; the first disciple of Yul leads it, so his generation may ask him.
        if (period == SectSchedule.Period.FORMATION && !today.did(SectAttendance.Activity.FORMATION) && rank != SectRank.ELDER) {
            options.add(new Plan(people.get(0), Kind.FORMATION, ""));
        }
        if (period.meal() && !SectMeals.served(p) && !today.did(SectAttendance.Activity.MEAL)) {
            options.add(new Plan(people.get(0), Kind.TABLE, ""));
        }
        if (period != SectSchedule.Period.FORMATION && !period.meal()) {
            // Recent events come first and are listed twice: the news is why people come over.
            SectDisciple kin = people.get(0);
            if (!rank.office() && s.entry("arrival.day", -100L) >= SectSchedule.day(p.level().getDayTime()) - 1L) {
                options.add(new Plan(kin, Kind.WELCOME, ""));
                options.add(new Plan(kin, Kind.WELCOME, ""));
            }
            // A bout won or lost in the last day (SectEvents), and only if this person has not answered it from afar yet.
            if (SectEvents.live(p, SectEvents.Event.BOUT_WON) && !SectEvents.reacted(p, SectEvents.Event.BOUT_WON, kin)) {
                options.add(new Plan(kin, Kind.CONGRATS, ""));
                options.add(new Plan(kin, Kind.CONGRATS, ""));
            }
            if (SectEvents.live(p, SectEvents.Event.BOUT_LOST) && !SectEvents.reacted(p, SectEvents.Event.BOUT_LOST, kin)) {
                options.add(new Plan(kin, Kind.CONSOLE, ""));
                options.add(new Plan(kin, Kind.CONSOLE, ""));
            }
            options.add(new Plan(people.get(0), Kind.GREET, ""));
            SectDisciple talker = people.stream().filter(d -> d.member().map(m -> m.disciple() || m.firstRank()).orElse(false))
                    .findFirst().orElse(null);
            if (talker != null) options.add(new Plan(talker, Kind.RUMOUR, ""));
            SectDisciple sparrer = people.stream().filter(d -> d.spars() && d.role() != SectRole.MENTOR).findFirst().orElse(null);
            if (sparrer != null && rank.atLeast(SectRank.DISCIPLE)
                    && p.getHealth() >= p.getMaxHealth() * 0.7F) options.add(new Plan(sparrer, Kind.SPAR, ""));
            // Goal reminders and the ladder favours are for those still climbing; an office holder has climbed.
            if (!rank.office()) {
                SectJourney.Goal goal = SectJourney.next(p);
                SectDisciple contact = people.stream().filter(d -> goal.person().equals(d.memberKey())).findFirst().orElseGet(() -> people.stream()
                        .filter(d -> d.role() == SectRole.SENIOR || d.role() == SectRole.MENTOR).findFirst().orElse(null));
                // Only the people involved know the next step (author 22.10); a random disciple does not announce it.
                if (contact != null) options.add(new Plan(contact, Kind.GOAL, ""));
                for (String id : SectRequests.IDS) {
                    for (SectDisciple d : people) {
                        if (SectRequests.canStart(p, d, id) && d.role() != SectRole.LEADER) {
                            options.add(new Plan(d, Kind.HELP, id));
                            break;
                        }
                    }
                }
            }
        }
        if (options.isEmpty()) return null;
        return options.get(Math.floorMod(roll, options.size()));
    }

    /** Sends the person; saves the time even when he is slow to arrive, so the player is never swarmed. */
    public static boolean begin(ServerPlayer p, Plan plan) {
        if(plan!=null && plan.kind()==Kind.TEACHER) return SectLessons.of(p.serverLevel()).beginTeacher(p,plan.npc());
        if (plan == null || !plan.npc().approach(p, plan.kind().id() + (plan.request().isEmpty() ? "" : ":" + plan.request()), PATIENCE)) {
            return false;
        }
        long now = p.level().getGameTime();
        long gap = INTERVAL_MIN + p.getRandom().nextInt((int) (INTERVAL - INTERVAL_MIN) + 1);
        p.setData(ModAttachments.SECT, SectService.state(p).record(LAST, now).record(GAP, gap)
                .record(AT + plan.npc().memberKey(), now));
        MurimMod.LOGGER.info("Sect approach: {} walks up to {} ({})", plan.npc().memberKey(), p.getName().getString(), plan.kind().id());
        return true;
    }

    // ------------------------------------------------------------------ what is said

    /** The kind in an approach spec ({@code kind} or {@code kind:request}), or null. */
    static Kind kindOf(String spec) {
        int colon = spec.indexOf(':');
        try {
            return Kind.valueOf((colon < 0 ? spec : spec.substring(0, colon)).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String requestOf(String spec) {
        int colon = spec.indexOf(':');
        return colon < 0 ? "" : spec.substring(colon + 1);
    }

    /**
     * Has this person words for this kind at all? An office holder gets no goal reminder, welcome or ladder favour, only a
     * junior asks him for a bout, only the first disciple is called to the line. Touches nothing, unlike {@link #line}.
     */
    static boolean speaks(SectDisciple npc, ServerPlayer p, Kind kind, String request) {
        if(kind==Kind.TEACHER) return SectLessons.teacher(npc) || SectSeniors.senior(npc);
        SectRank rank = SectRank.of(p);
        if (rank.office()) {
            return switch (kind) {
                case GREET, TABLE, CONGRATS, CONSOLE, RUMOUR, INVITE, TEACHER -> true;
                case SPAR -> SectSocial.junior(npc);
                case FORMATION -> rank == SectRank.FIRST;
                case GOAL, HELP, WELCOME -> false;
            };
        }
        return kind != Kind.HELP || SectRequests.IDS.contains(request);
    }

    /** He stands in front of the player and shows a short bubble over his head (the words themselves: {@link #takeOpeningLine}). */
    public static void arrive(SectDisciple npc, ServerPlayer p, String spec) {
        Kind kind = kindOf(spec);
        String request = requestOf(spec);
        if (kind == null || !speaks(npc, p, kind, request)) return;
        if(kind==Kind.TEACHER) {
            SectLessons.say(npc,p,SectLessons.text("ackPool","lesson_ack")); return;
        }
        if (SectSocial.mayBow(npc, p, kind == Kind.CONGRATS || kind == Kind.CONSOLE))
            npc.holdPose(io.github.verycooltimo.murim.entity.SectPose.BOW, 30);
        else npc.gesture("nod");
        SectRank rank = SectRank.of(p);
        String bubble = SectLines.pick(p, kind == Kind.INVITE ? "murim.invite.bubble." + request
                : (rank.office() ? "murim.approach.bubble.office." : "murim.approach.bubble.") + kind.id(), VARIANTS, npc.getRandom());
        if (!SectChatter.send(npc, bubble, Component.translatable(bubble))) return;
        // He has said his word about the bout: a passer-by's bubble would repeat it (SectEvents).
        if (kind == Kind.CONGRATS) SectEvents.markReacted(p, SectEvents.Event.BOUT_WON, npc);
        if (kind == Kind.CONSOLE) SectEvents.markReacted(p, SectEvents.Event.BOUT_LOST, npc);
        if (kind == Kind.HELP) SectRequests.start(p, npc, request);
    }

    /**
     * The player turns to the person who walked up to him: what he came to say is the first line of the talk. Said once; the
     * approach ends (he is in the talk now). Null when nobody walked up to this player or the person has nothing to say.
     */
    public static Component takeOpeningLine(ServerPlayer p, SectDisciple npc) {
        String spec = npc.approachKind();
        if (spec == null || npc.blockTarget() != p.getId()) return null;
        Kind kind = kindOf(spec);
        if (kind == null) return null;
        Component line = speaks(npc, p, kind, requestOf(spec)) ? line(npc, p, kind, requestOf(spec)) : null;
        if (line != null) {
            npc.clearBlock();
        }
        return line;
    }

    /** The line {@code stem.N} that this player did not hear lately (SectLines). */
    static Component pickLine(ServerPlayer p, String stem, int roll, Object... args) {
        return Component.translatable(SectLines.pick(p, stem, VARIANTS, roll), args);
    }

    /** The voice a bout is asked in: only the second and the third generation spar. */
    static SectVoice.Voice sparVoice(SectVoice.Voice v) {
        return v == SectVoice.Voice.THIRD || v == SectVoice.Voice.SECOND ? v : v == SectVoice.Voice.LAY ? SectVoice.Voice.THIRD : SectVoice.Voice.SECOND;
    }

    /** Lang stem of the pool of this kind in this voice. */
    public static String stem(Kind kind, SectVoice.Voice voice, boolean evening) {
        return "murim.approach." + (kind == Kind.GREET && evening ? "greet_evening" : kind.id()) + "." + voice.id();
    }

    /** The voice pool plus what this person adds to it: his own greetings, the lines of his temperament. */
    static List<String> keys(SectDisciple npc, Kind kind, SectVoice.Voice voice, boolean evening) {
        List<String> keys = new ArrayList<>(SectLines.keys(stem(kind, voice, evening), VARIANTS));
        boolean day = kind == Kind.GREET && !evening;
        if (day && PERSONAL.contains(npc.memberKey())) {
            keys.addAll(SectLines.keys("murim.approach.greet.person." + npc.memberKey(), 2));
        }
        if ((day || kind == Kind.SPAR) && voice.temperament() && npc.member().map(SectRoster::disciple).orElse(false)) {
            keys.addAll(SectLines.keys("murim.approach.trait." + kind.id() + "." + SectTalk.trait(npc.memberKey()).id(), 2));
        }
        return keys;
    }

    /**
     * What a person says to an elder or a first disciple. {@code up} (a junior asks, bows, offers) or {@code peer} (the
     * first generation and the council speak as to a colleague); never a drill, a reminder or a welcome. Null: no such line.
     */
    static Component officeLine(SectDisciple npc, ServerPlayer p, SectRank rank, Kind kind, int roll) {
        String stem = "murim.approach.office." + (rank == SectRank.ELDER ? "elder" : "first") + "."
                + (SectSocial.junior(npc) ? "up" : "peer") + ".";
        return switch (kind) {
            case GREET, TABLE, CONGRATS, CONSOLE -> pickLine(p, stem + kind.id(), roll);
            case RUMOUR -> DialogueService.talk(p, npc, SectTalk.RUMOUR);
            // Only juniors ask for a bout; the first generation and the council do not challenge an office.
            case SPAR -> SectSocial.junior(npc) ? pickLine(p, stem + "spar", roll) : null;
            case FORMATION -> rank == SectRank.FIRST ? pickLine(p, stem + "formation", roll) : null;
            case GOAL, HELP, WELCOME, INVITE, TEACHER -> null;
        };
    }

    static Component line(SectDisciple npc, ServerPlayer p, Kind kind, String request) {
        if(kind==Kind.TEACHER) {
            if(!SectLessons.teacher(npc) && !SectSeniors.senior(npc)) return null;
            String stem=SectRank.of(p).office()?"murim.bubble.social."+SectSocial.officeGroup(SectRank.of(p),npc):"murim.bubble.lesson_ack";
            return Component.translatable(SectLines.pick(p,stem,6,npc.getRandom()));
        }
        if (kind == Kind.INVITE) {
            // The words he came with: where, and that nobody is forced (docs/design/38).
            return Component.translatable("murim.invite.line." + request);
        }
        int roll = npc.getRandom().nextInt(1 << 16);
        SectRank rank = SectRank.of(p);
        if (rank.office()) {
            return officeLine(npc, p, rank, kind, roll);
        }
        boolean evening = SectSchedule.at(p.level().getDayTime()) == SectSchedule.Period.EVENING;
        SectVoice.Voice voice = SectVoice.of(npc);
        Component standing = Component.translatable(SectService.standing(p).nameKey());
        return switch (kind) {
            case GREET -> Component.translatable(SectLines.pick(p, stem(kind, voice, evening), keys(npc, kind, voice, evening), roll), standing);
            case SPAR -> {
                SectVoice.Voice v = sparVoice(voice);
                yield Component.translatable(SectLines.pick(p, stem(kind, v, false), keys(npc, kind, v, false), roll));
            }
            case RUMOUR -> DialogueService.talk(p, npc, SectTalk.RUMOUR);
            case GOAL -> pickLine(p, stem(kind, voice, false), roll, SectJourney.line(p));
            case WELCOME -> pickLine(p, stem(kind, voice, false), roll, p.getName());
            case FORMATION, TABLE, CONGRATS, CONSOLE -> pickLine(p, stem(kind, voice, false), roll);
            case HELP -> SectRequests.IDS.contains(request) ? Component.translatable("murim.approach.help." + request) : null;
            case INVITE, TEACHER -> null;
        };
    }

    // ------------------------------------------------------------------ tick

    @SubscribeEvent
    static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.tickCount % 60 != 29 || p.isSpectator()) return;
        if (!SectService.state(p).member() || SectLife.layout(p.serverLevel()) == null || !due(p)) return;
        // About one check in three succeeds, so the five minutes are a minimum, not a metronome.
        if (p.getRandom().nextInt(3) != 0) return;
        begin(p, choose(p, p.getRandom().nextInt(1 << 16)));
    }
}
