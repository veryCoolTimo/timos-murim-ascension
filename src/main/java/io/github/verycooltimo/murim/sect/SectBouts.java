package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.List;
import java.util.UUID;

/**
 * Bouts of one player against sect people, in a ring (docs/design/39-player-path-v2.md items 4-5).
 *
 * <ul>
 *   <li>Training bout: any free disciple, the existing safe spar ({@link SectDisciple#startSpar}).</li>
 *   <li>Challenge: a senior (second generation: {@link SectRole#SENIOR}, {@link SectRole#SECOND}). A win is respect and
 *       a rumour that the sect passes on; {@link #NEEDED} wins (one counted win per senior per sect day) open a short
 *       ceremony with the leader, and the ceremony raises {@link SectStanding} by one step (up to
 *       {@link SectStanding#TRUSTED}; the step to trusted counts only seniors stronger than the player by rank). Losses never punish.</li>
 *   <li>Mentors (first generation, {@link #gyeong}): a challenge only from a player at the Peak who has beaten every
 *       strong second-generation senior ({@link #strongSecond}). Elders and the leader cannot be challenged.</li>
 * </ul>
 *
 * <p>Progress is in the player's {@link SectState} journal (fixed keys, so the save does not grow). The bout data
 * for the defeat analysis ({@link SectDefeat}) is collected here for every spar the player starts. No new keys: the
 * bouts are dialogue options.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectBouts {

    /** Journal: the standing step granted by challenges (read by {@link SectStanding#of}). */
    public static final String GRANTED = "challenge.rank";
    public static final String WINS = "challenge.wins";
    public static final String TOTAL = "challenge.total";
    public static final String LOSSES = "challenge.losses";
    /** Flag: five wins are collected, the ceremony with the leader is open. */
    public static final String CEREMONY = "challenge.ceremony";
    /** Journal: game time the ceremony was begun, absent (-1) before. */
    public static final String CEREMONY_AT = "challenge.ceremony.at";
    /** Journal: the sect day of the last counted win over a senior ({@code challenge.day.<key>}). */
    public static final String DAY_PREFIX = "challenge.day.";
    /** Wins over seniors for one step. */
    public static final int NEEDED = 5;
    /** Ticks the ceremony needs between the bow and the confirmation. */
    public static final int CEREMONY_TICKS = 100;
    /** Horizontal reach from a ring centre that still counts as the ring. */
    public static final double RING_REACH = 6.0D;
    /** The highest step the challenges give. The step to trusted counts only wins over seniors stronger than the player. */
    public static final SectStanding CAP = SectStanding.TRUSTED;
    /** Journal: {@code challenge.beat.<key>} = 1 once the player has beaten that strong second-generation senior. */
    public static final String BEAT_PREFIX = "challenge.beat.";
    /** Rumour tag on a person: who beat whom, until when. */
    public static final String RUMOUR = "murim_bout_rumour";
    private static final int RUMOUR_TICKS = 48000;
    /** The gong count before the first bow (ticks, 3-2-1) and the longest fight (60 s). */
    public static final int COUNTDOWN = 60;
    public static final int FIGHT_LIMIT = 1200;
    /** Farther than this from the ring centre is out of the ring. */
    public static final double OUT_OF_RING = RING_REACH + 3.0D;
    private static final java.util.Set<SectSchedule.Kind> BUSY = java.util.EnumSet.of(SectSchedule.Kind.SLEEP, SectSchedule.Kind.EAT,
            SectSchedule.Kind.GUARD, SectSchedule.Kind.FORM_ROW, SectSchedule.Kind.COUNCIL, SectSchedule.Kind.REPORT,
            SectSchedule.Kind.TEA, SectSchedule.Kind.ARGUE, SectSchedule.Kind.COOK, SectSchedule.Kind.SERVE, SectSchedule.Kind.CARRY, SectSchedule.Kind.MEDITATE);
    /** Lines of every bout banter pool (data test: at least {@link SectLines#MIN_VARIANTS}). */
    private static final int VARIANTS = 6;

    /**
     * TRIAL is the rank-two skills test: the mentor always wins, with no defeat penalty. RULE is a spar with a condition offered
     * by a senior (the quest step {@code trial}, {@link SectTrials}): a tracked bout like training, judged by its rule at the end.
     */
    public enum Mode { SPAR, TRAIN, CHALLENGE, TRIAL, RULE }

    /** The bout in progress and what the defeat analysis reads from it. */
    static final class Bout {
        final UUID npc;
        final String key;
        final Mode mode;
        final long start;
        double qiStart;
        int taken;
        int dealt;
        double distance;
        int samples;
        double qiLow = 1.0D;
        /** Game time the fight began (after the count and the bows), -1 before. */
        long fightStart = -1L;
        /** Ticks the player has spent outside the ring during the fight. */
        int outside;
        /** Last second shown in the count (3, 2, 1). */
        int shown = 4;
        /** Centre of the ring the bout began in (null: no ring, a spar anywhere): leaving it is leaving the ring, whatever other ring is next to it. */
        Vec3 ring;
        // What the master's correction reads (SectCorrection): swings and their forms, blows taken in the wind-up or into a strike, time out of the ring.
        int swings;
        final java.util.Map<Integer, Integer> forms = new java.util.HashMap<>();
        int windupHits;
        int punished;
        /** Game time of the last swing begun while the partner was already striking, or a time long past. */
        long intoStrikeAt = Long.MIN_VALUE / 2;
        int outTotal;
        /** The quest trial this bout is for ({@link Mode#RULE}), else null; and what the rules of trials read from it. */
        String trial;
        /** Strikes of the partner the player parried in this bout. */
        int parries;
        String lesson;
        boolean lessonParried;
        /** The player began a technique or a footwork form in this bout. */
        boolean cast;
        /** The first touch of a trial about touches has been told. */
        boolean told;
        long ruleBrokenAt;

        Bout(UUID npc, String key, Mode mode, long start, double qiStart) {
            this.npc = npc;
            this.key = key;
            this.mode = mode;
            this.start = start;
            this.qiStart = qiStart;
        }

        double meanDistance() {
            return samples == 0 ? 3.0D : distance / samples;
        }

        /** What this bout recorded, for the master's correction; {@code unlocked} is how many forms the player could change between. */
        SectCorrection.Log log(long now, int unlocked) {
            int most = forms.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            return new SectCorrection.Log(taken, dealt, meanDistance(), qiStart, qiLow, swings, most, unlocked, windupHits, punished, outTotal,
                    fightStart < 0 ? 0L : now - fightStart);
        }
    }

    private SectBouts() {
    }

    /** Session state of the bouts (a server session, not static: see {@link SectSiteData#session}). */
    static SectBoutSession session(ServerPlayer p) {
        return SectLife.data(p.serverLevel()).session;
    }

    // ------------------------------------------------------------------ rings and seniors (pure parts)

    /** Centre of the ring {@code pos} stands in, or null (no layout, no training square, not in a ring). */
    public static Vec3 ringCentre(SectLayout layout, Vec3 pos) {
        if (layout == null || pos == null) {
            return null;
        }
        for (double[][] rings : new double[][][] {SectSchedule.RINGS, SectSchedule.DAILY_RINGS}) {
            for (double[] r : rings) {
                Vec3 c = layout.at("training", r[0], r[1]);
                if (c != null && Math.hypot(pos.x - c.x, pos.z - c.z) <= RING_REACH && Math.abs(pos.y - c.y) <= 4.0D) {
                    return c;
                }
            }
        }
        return null;
    }

    /** A senior for the challenge: second generation (the elder brothers), not the leader, elders or servants. */
    public static boolean senior(SectDisciple npc) {
        return npc.member().isPresent() && (npc.role() == SectRole.SENIOR || npc.role() == SectRole.SECOND);
    }

    /** A mentor of the first generation (Gyeong) who can be challenged at the Peak: the rank-and-file first disciples and the mentor. */
    public static boolean gyeong(SectDisciple npc) {
        return npc.member().isPresent() && npc.member().get().generation() == 1
                && (npc.role() == SectRole.FIRST || npc.role() == SectRole.MENTOR);
    }

    /** Anyone a challenge may be addressed to (elders and the leader are not in it). */
    public static boolean challengeable(SectDisciple npc) {
        return senior(npc) || gyeong(npc);
    }

    /** Takes part in ring bouts at all: the usual sparring roles and the first generation. */
    static boolean boutPartner(SectDisciple npc) {
        return npc.spars() || gyeong(npc);
    }

    /** The strong second generation the player has to beat before a mentor accepts: seniors and the second-class disciples. */
    public static List<SectRoster> strongSecond() {
        return SectRoster.ALL.stream().filter(m -> m.generation() == 2 && (m.role() == SectRole.SENIOR || m.role() == SectRole.SECOND)).toList();
    }

    /** How many strong second-generation seniors the player has not beaten yet. */
    public static int strongLeft(SectState s) {
        return (int) strongSecond().stream().filter(m -> s.entry(BEAT_PREFIX + m.key(), 0) <= 0).count();
    }

    private static boolean atPeak(ServerPlayer p) {
        return p.getData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE).rank() >= io.github.verycooltimo.murim.cultivation.Realm.PEAK;
    }

    /** Why a first-generation mentor does not take the challenge now: "not_peak", "elders_first", or null when he does. */
    static String gyeongRefusal(ServerPlayer p) {
        if (!atPeak(p)) {
            return "not_peak";
        }
        return strongLeft(SectProgression.state(p)) > 0 ? "elders_first" : null;
    }

    /** Wins collected towards the next step (capped by {@link #NEEDED}). */
    public static int wins(SectState s) {
        return (int) Math.min(NEEDED, s.entry(WINS, 0));
    }

    // ------------------------------------------------------------------ availability

    /** No bouts at night, at meals or in the morning line: the invitation waits "until after dinner". */
    static boolean restTime(ServerPlayer p) {
        return switch (SectSchedule.at(p.level().getDayTime())) {
            case NIGHT, DINNER, BREAKFAST, FORMATION -> true;
            default -> false;
        };
    }

    /** Free: not asleep, eating, on post, in a line, wounded or in another fight. */
    static boolean freeNpc(SectDisciple npc) {
        return !npc.isSleeping() && !npc.wounded() && (npc.doingKind() == null || !BUSY.contains(npc.doingKind()));
    }

    private static boolean room(ServerPlayer p, SectDisciple npc) {
        return SectProgression.ready(p) && SectProgression.available(p, npc) && boutPartner(npc) && npc.member().isPresent()
                && !npc.role().lay() && !session(p).active.containsKey(p.getUUID()) && !SectDuel.inDuel(p)
                && p.getHealth() > p.getMaxHealth() * 0.5F && !restTime(p) && freeNpc(npc);
    }

    /** The training square is shared: several pairs spar at once (author 22.10), only a crowd makes it full. */
    static final int MAX_PAIRS = 4;

    /** Fights going on in the ring centred at {@code centre} (a ring is used by one fight; the rest wait by the edge). */
    static int ringFights(ServerPlayer p, SectDisciple npc, Vec3 centre) {
        int n = 0;
        for (SectDisciple d : p.level().getEntitiesOfClass(SectDisciple.class, new net.minecraft.world.phys.AABB(centre, centre).inflate(RING_REACH + 2.0D),
                d -> d != npc && d.isAlive() && d.spar() != SectDisciple.Spar.NONE && d.spar() != SectDisciple.Spar.BOW_OUT)) {
            n++;
        }
        for (SectDuel.Duel d : session(p).duels.values()) {
            if (d.centre.distanceToSqr(centre) < 0.25D && d.a.equals(d.pa.getUUID())) {
                n += 2;
            }
        }
        // Two fighters make one fight.
        return (n + 1) / 2;
    }

    public static boolean inRing(ServerPlayer p, SectDisciple npc) {
        return ringCentre(npc.layout(), p.position()) != null;
    }

    public static boolean canTrain(ServerPlayer p, SectDisciple npc) {
        Vec3 centre = ringCentre(npc.layout(), p.position());
        return room(p, npc) && centre != null && ringFights(p, npc, centre) < MAX_PAIRS;
    }

    /** Author 21.10: every third-rank disciple may ask the teacher for a skills test, in any difficulty. */
    public static boolean trialOpen(ServerPlayer p) {
        var profile = p.getData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE);
        return SectProgression.ready(p) && profile.isAwakened()
                && profile.rank() == io.github.verycooltimo.murim.cultivation.Realm.THIRD
                && !SectProgression.state(p).has(SectProgression.SECOND_TEST);
    }

    /** The trial is held by the mentor, in a free ring, like any training bout. */
    public static boolean canTrial(ServerPlayer p, SectDisciple npc) {
        return trialOpen(p) && npc.role() == SectRole.MENTOR && canTrain(p, npc);
    }

    public static boolean canChallenge(ServerPlayer p, SectDisciple npc) {
        return canTrain(p, npc) && challengeable(npc) && (!gyeong(npc) || gyeongRefusal(p) == null)
                && SectProgression.state(p).entry(DAY_PREFIX + npc.memberKey(), -1) < SectProgression.day(p);
    }

    /** The reason the menu line gives when a bout cannot begin now (and how strong the partner is). */
    static Component menuLine(ServerPlayer p, SectDisciple npc) {
        if (restTime(p) || npc.wounded() || npc.isSleeping() || npc.doingKind() != null && BUSY.contains(npc.doingKind())) {
            String when = switch (SectSchedule.at(p.level().getDayTime())) {
                case NIGHT -> "night";
                case DINNER -> "dinner";
                case BREAKFAST, FORMATION -> "morning";
                default -> "busy";
            };
            return Component.translatable("dialogue.murim.bout.menu.later." + when);
        }
        if (!room(p, npc)) {
            return Component.translatable("dialogue.murim.bout.menu.busy");
        }
        Vec3 centre = ringCentre(npc.layout(), p.position());
        if (centre == null) {
            return Component.translatable("dialogue.murim.bout.menu.no_ring");
        }
        int fights = ringFights(p, npc, centre);
        if (fights >= MAX_PAIRS) {
            return Component.translatable("dialogue.murim.bout.menu.ring_busy", fights);
        }
        int mine = p.getData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE).rank();
        Component strength = Component.translatable(npc.rank() > mine ? "dialogue.murim.bout.strength.stronger"
                : npc.rank() == mine ? "dialogue.murim.bout.strength.equal" : "dialogue.murim.bout.strength.weaker");
        if (gyeong(npc) && gyeongRefusal(p) != null) {
            String why = gyeongRefusal(p);
            Component line = "not_peak".equals(why) ? Component.translatable("dialogue.murim.bout.menu.not_peak")
                    : Component.translatable("dialogue.murim.bout.menu.elders_first", strongLeft(SectProgression.state(p)));
            return line.copy().append(" ").append(strength);
        }
        if (challengeable(npc) && !canChallenge(p, npc)) {
            return Component.translatable("dialogue.murim.bout.menu.counted_today").append(" ").append(strength);
        }
        String key = gyeong(npc) ? "mentor" : senior(npc) ? "senior" : npc.role().third() ? "junior" : "free";
        // An office holder climbs no ladder: the promotion talk of the senior and mentor lines is not for him.
        if (SectRank.of(p).office() && !"free".equals(key)) {
            key += "_office";
        }
        return Component.translatable("dialogue.murim.bout.menu." + key).append(" ").append(strength);
    }

    /** Shown number of the shared «Challenge to a spar» row (below {@link SectGeneralTalk#BASE}, past any node's own rows). */
    public static final int SHARED = 900;

    private static final String SHARED_JSON = "{\"text\":\"dialogue.murim.bout.opt.ring\",\"when\":[{\"social\":\"progression:bout_menu\"}],"
            + "\"actions\":[{\"type\":\"progression_menu\",\"value\":\"bouts\"}]}";
    private static Dialogue.Option sharedOption;

    /** The one shared row, from the same JSON shape as the dialogue files. */
    static Dialogue.Option sharedOption() {
        if (sharedOption == null) {
            sharedOption = Dialogue.Option.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
                    com.google.gson.JsonParser.parseString(SHARED_JSON)).getOrThrow();
        }
        return sharedOption;
    }

    /**
     * Every member who can spar offers «Challenge to a spar» in his opening menu, whether or not his own file lists it (hitting
     * no longer starts a bout). The menu behind it ({@code bouts}) gives the polite refusal when the rules say not now.
     */
    static boolean sharedRow(ServerPlayer p, SectDisciple npc, Dialogue d, String nodeId) {
        if (d != DialogueLoader.get(npc.dialogue()) || !SectGoalTalk.hub(p, npc, d, nodeId) || !matches(p, npc, "bout_menu")) {
            return false;
        }
        Dialogue.Node node = DialogueService.nodeOf(d, nodeId);
        if (node != null) {
            for (Dialogue.Option o : node.options()) {
                if (o.text().equals("dialogue.murim.bout.opt.ring") || o.text().equals("dialogue.murim.bout.opt.menu")) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Dialogue condition values {@code bout_menu}, {@code train}, {@code challenge}, {@code promo}, {@code promo_confirm}. */
    static boolean matches(ServerPlayer p, SectDisciple npc, String value) {
        return switch (value) {
            case "bout_menu" -> SectProgression.calm(p) && SectProgression.available(p, npc) && boutPartner(npc) && npc.member().isPresent();
            case "train" -> canTrain(p, npc);
            case "trial" -> canTrial(p, npc);
            case "challenge" -> canChallenge(p, npc);
            case "promo_any" -> canPromo(p, npc);
            case "promo" -> canPromo(p, npc) && !ceremonyReady(p);
            case "promo_confirm" -> canPromo(p, npc) && ceremonyReady(p);
            default -> false;
        };
    }

    static boolean act(ServerPlayer p, SectDisciple npc, String value) {
        return switch (value) {
            case "train" -> begin(p, npc, Mode.TRAIN);
            case "trial" -> begin(p, npc, Mode.TRIAL);
            case "challenge" -> begin(p, npc, Mode.CHALLENGE);
            case "promo" -> promoBegin(p, npc);
            case "promo_confirm" -> promoConfirm(p, npc);
            default -> false;
        };
    }

    // ------------------------------------------------------------------ the bout

    /** Open the bout: the partner bows, then the safe spar. False if it cannot begin now. */
    public static boolean begin(ServerPlayer p, SectDisciple npc, Mode mode) {
        if (SectNightWatch.refuse(p, npc, "bout")) return false;
        if (mode == Mode.CHALLENGE ? !canChallenge(p, npc) : mode == Mode.TRIAL ? !canTrial(p, npc) : !canTrain(p, npc)) {
            return false;
        }
        session(p).request.put(p.getUUID(), mode);
        DialogueService.close(p, npc);
        npc.startSpar(p, COUNTDOWN);
        session(p).request.remove(p.getUUID());
        if (npc.spar() == SectDisciple.Spar.NONE) {
            return false;
        }
        SectProgression.say(npc, mode == Mode.CHALLENGE ? "murim.bubble.bout.accept_challenge"
                : mode == Mode.TRIAL ? "murim.bubble.bout.accept_trial" : "murim.bubble.bout.accept_train");
        return true;
    }

    /** Start the quest lesson through the ordinary tracked training path. */
    public static boolean beginLesson(ServerPlayer p, SectDisciple npc, String lesson) {
        if (!begin(p, npc, Mode.TRAIN)) return false;
        Bout b = session(p).active.get(p.getUUID());
        if (b == null) return false;
        b.lesson = lesson;
        return true;
    }

    /**
     * Open a bout with a condition (a quest trial): the partner bows, then the safe spar in a ring, judged by the rule of the
     * trial when it ends. The caller has checked the trial; False if the bout cannot begin now.
     */
    public static boolean beginRule(ServerPlayer p, SectDisciple npc, String trial) {
        if (SectNightWatch.refuse(p, npc, "bout")) return false;
        if (!canTrain(p, npc)) {
            return false;
        }
        session(p).request.put(p.getUUID(), Mode.RULE);
        session(p).trials.put(p.getUUID(), trial);
        DialogueService.close(p, npc);
        npc.startSpar(p, COUNTDOWN);
        session(p).request.remove(p.getUUID());
        session(p).trials.remove(p.getUUID());
        if (npc.spar() == SectDisciple.Spar.NONE) {
            return false;
        }
        SectProgression.say(npc, "murim.bubble.bout.accept_train");
        return true;
    }

    /** From {@link SectProgression#sparStarted}: every spar of a player is tracked, the request says what it is for. */
    static void started(ServerPlayer p, SectDisciple npc) {
        Mode mode = session(p).request.getOrDefault(p.getUUID(), Mode.SPAR);
        double max = Math.max(1.0D, p.getData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE).maxCirculating());
        Bout bout = new Bout(npc.getUUID(), npc.memberKey(), mode, p.level().getGameTime(),
                p.getData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE).circulating() / max);
        bout.ring = npc.layout() == null ? null : ringOrSelf(npc, p);
        session(p).active.put(p.getUUID(), bout);
        if (mode == Mode.TRIAL) SectNotice.chat(p, SectProgression.secondChecklist(p));
        if (mode == Mode.RULE) {
            bout.trial = session(p).trials.get(p.getUUID());
            SectTrials.announce(p, bout);
        }
    }

    public static Bout active(ServerPlayer p) {
        return session(p).active.get(p.getUUID());
    }

    /** A bout (spar or challenge) is on for this player: the combat HUD stays up for it. */
    public static boolean inBout(ServerPlayer p) {
        return active(p) != null;
    }

    @SubscribeEvent
    static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) {
            return;
        }
        SectDefeat.tick(p);
        SectCorrection.tick(p);
        Bout b = session(p).active.get(p.getUUID());
        if (b == null) {
            return;
        }
        supervise(p, b);
    }

    /** One tick of a tracked bout: the count, the ring, the time limit, the stats. Tests call it directly. */
    static void supervise(ServerPlayer p, Bout b) {
        long now = p.level().getGameTime();
        Entity e = p.serverLevel().getEntity(b.npc);
        if (!(e instanceof SectDisciple npc) || !npc.isAlive() || !p.isAlive()
                || npc.spar() == SectDisciple.Spar.NONE && now - b.start > 100) {
            session(p).active.remove(p.getUUID());
            return;
        }
        boolean tracked = b.mode != Mode.SPAR;
        // Measured from the ring the bout began in: stepping into the next ring over is still leaving this one.
        Vec3 centre = b.ring != null ? b.ring : npc.layout() == null ? null : ringOrSelf(npc, p);
        boolean inRing = centre == null || p.position().distanceToSqr(centre) <= OUT_OF_RING * OUT_OF_RING;
        SectDisciple.Spar phase = npc.spar();
        if (tracked && (phase == SectDisciple.Spar.WAIT || phase == SectDisciple.Spar.BOW_IN)) {
            if (!inRing) {
                // Left the ring during the count: a refusal, no penalty and no analysis.
                npc.cancelBout(p);
                session(p).active.remove(p.getUUID());
                PlayerJournal.refuse(p, Component.translatable("murim.sect.bout.refused").withStyle(ChatFormatting.GRAY));
                return;
            }
            int left = (int) Math.ceil((COUNTDOWN - (now - b.start)) / 20.0D);
            if (phase == SectDisciple.Spar.WAIT && left >= 1 && left < b.shown) {
                b.shown = left;
                p.serverLevel().playSound(null, p.blockPosition(), net.minecraft.sounds.SoundEvents.BELL_BLOCK,
                        net.minecraft.sounds.SoundSource.PLAYERS, 0.8F, 0.8F);
            }
        }
        if (phase != SectDisciple.Spar.FIGHT) {
            return;
        }
        if (b.fightStart < 0) {
            b.fightStart = now;
            if (tracked) {
                p.serverLevel().playSound(null, p.blockPosition(), net.minecraft.sounds.SoundEvents.BELL_RESONATE,
                        net.minecraft.sounds.SoundSource.PLAYERS, 0.9F, 1.0F);
            }
        }
        if (b.mode == Mode.TRIAL && inRing && trialSatisfied(p, npc, b)) {
            b.outside = 0;
            npc.endSpar(false); // The mentor ends the demonstration as the winner.
            return;
        }
        if (tracked) {
            b.outside = inRing ? 0 : b.outside + 1;
            if (!inRing) {
                b.outTotal++;
            }
            if (b.outside >= 20) {
                // Out of the ring is a defeat.
                npc.endSpar(false);
                return;
            }
            if (b.trial != null && SectTrials.expired(b, now)) {
                // The time of a trial is up: the senior has won the bout.
                SectTrials.timeUp(p, b);
                // Keep the accepted trial state visible for one second before its existing defeat.
                if (now - b.ruleBrokenAt >= 20) npc.endSpar(false);
                return;
            }
            if (now - b.fightStart >= FIGHT_LIMIT) {
                // 60 seconds: by points (hits landed); equal is a draw.
                if (b.dealt == b.taken) {
                    npc.cancelBout(p);
                    session(p).active.remove(p.getUUID());
                    SectNotice.bar(p, Component.translatable("murim.sect.bout.draw").withStyle(ChatFormatting.GRAY));
                } else {
                    npc.endSpar(b.dealt > b.taken);
                }
                return;
            }
        }
        if (p.tickCount % 5 == 0) {
            b.distance += npc.distanceTo(p);
            b.samples++;
            var profile = p.getData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE);
            double frac = profile.circulating() / Math.max(1.0D, profile.maxCirculating());
            b.qiLow = Math.min(b.qiLow, frac);
        }
    }

    /** The centre of the ring the player is in, else of the nearest ring of the square (the ring check then measures from it). */
    private static Vec3 ringOrSelf(SectDisciple npc, ServerPlayer p) {
        Vec3 c = ringCentre(npc.layout(), p.position());
        if (c != null) {
            return c;
        }
        Vec3 best = null;
        for (double[][] rings : new double[][][] {SectSchedule.RINGS, SectSchedule.DAILY_RINGS}) {
            for (double[] r : rings) {
                Vec3 at = npc.layout().at("training", r[0], r[1]);
                if (at != null && (best == null || at.distanceToSqr(p.position()) < best.distanceToSqr(p.position()))) {
                    best = at;
                }
            }
        }
        return best == null ? p.position() : best;
    }

    /** A third party strikes a fighter: the bout stops with no result. */
    @SubscribeEvent
    static void onIncoming(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || event.getSource().getEntity() == null) {
            return;
        }
        Bout b = session(p).active.get(p.getUUID());
        if (b == null || b.mode == Mode.SPAR || event.getSource().getEntity().getUUID().equals(b.npc)) {
            return;
        }
        if (p.serverLevel().getEntity(b.npc) instanceof SectDisciple npc) {
            npc.cancelBout(p);
        }
        session(p).active.remove(p.getUUID());
        SectNotice.bar(p, Component.translatable("murim.sect.bout.interrupted").withStyle(ChatFormatting.GRAY));
    }

    @SubscribeEvent
    static void onLogout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            Bout b = session(p).active.remove(p.getUUID());
            if (b != null && p.serverLevel().getEntity(b.npc) instanceof SectDisciple npc) {
                npc.cancelBout(p);
            }
            session(p).pending.remove(p.getUUID());
            session(p).request.remove(p.getUUID());
            session(p).trials.remove(p.getUUID());
            session(p).drills.remove(p.getUUID());
            session(p).corrections.remove(p.getUUID());
        }
    }

    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
    static void onDamaged(LivingDamageEvent.Post event) {
        if (event.getNewDamage() <= 0.0F || event.getEntity() instanceof io.github.verycooltimo.murim.entity.TrainingDummy) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer victim && event.getSource().getEntity() != null) {
            Bout b = session(victim).active.get(victim.getUUID());
            if (b != null && event.getSource().getEntity().getUUID().equals(b.npc)) {
                b.taken++;
                b.lessonParried = false;
                if (b.trial != null) {
                    SectTrials.touched(victim, b);
                }
            }
        }
        if (event.getSource().getEntity() instanceof ServerPlayer attacker) {
            Bout b = session(attacker).active.get(attacker.getUUID());
            if (b != null && event.getEntity().getUUID().equals(b.npc)) {
                b.dealt++;
                if (b.trial != null) SectTrials.struck(attacker, b, event.getSource());
                if (b.lesson != null && b.lessonParried && b.fightStart >= 0) {
                    io.github.verycooltimo.murim.quest.QuestLessons.practiced(attacker, b.lesson);
                    b.lessonParried = false;
                }
            }
            if (!(event.getEntity() instanceof ServerPlayer)) {
                SectDefeat.struck(attacker);
            }
        }
    }

    /** One swing of a foundation form by the player (from FoundationService): counted into the bout's log while it is a fight. */
    public static void onSwing(ServerPlayer p, int form) {
        Bout b = session(p).active.get(p.getUUID());
        if (b == null || b.fightStart < 0) {
            return;
        }
        b.swings++;
        b.forms.merge(form, 1, Integer::sum);
        if (p.serverLevel().getEntity(b.npc) instanceof SectDisciple npc && npc.attacking()) {
            b.intoStrikeAt = p.level().getGameTime();
        }
    }

    /** From {@code SwordGuard}: the player parried a strike of {@code by}; counted when it is the partner of his bout. */
    public static void onParry(ServerPlayer p, Entity by) {
        Bout b = session(p).active.get(p.getUUID());
        if (b == null || b.fightStart < 0 || !by.getUUID().equals(b.npc)) {
            return;
        }
        b.parries++;
        if (b.lesson != null) b.lessonParried = true;
        if (b.trial != null) {
            SectTrials.parried(p, b);
        }
    }

    /** From {@code TechniqueService} and {@code FootworkService}: the player began a technique or a footwork form. */
    public static void onCast(ServerPlayer p) {
        Bout b = session(p).active.get(p.getUUID());
        if (b == null || b.fightStart < 0) {
            return;
        }
        b.cast = true;
        if (b.trial != null) {
            SectTrials.cast(p, b);
        }
    }

    /** From {@link SectService#onSparEnd}: the bout is over, {@code won} is the player's result. */
    static void ended(ServerPlayer p, SectDisciple npc, boolean won) {
        Bout b = session(p).active.remove(p.getUUID());
        SectGuide.sync(p);
        if (b != null) {
            MurimMod.LOGGER.info("Sect bout ended: {} vs {} mode={} won={} fightTicks={} dealt={} taken={} parries={} cast={} trial={}",
                    p.getName().getString(), npc.memberKey(), b.mode, won, b.fightStart < 0 ? -1 : p.level().getGameTime() - b.fightStart,
                    b.dealt, b.taken, b.parries, b.cast, b.trial);
        }
        if (b == null || !SectProgression.state(p).member()) {
            return;
        }
        if (b.fightStart >= 0 && (b.mode == Mode.TRAIN || b.mode == Mode.SPAR)) {
            io.github.verycooltimo.murim.quest.QuestLessons.afterBout(p, won);
        }
        if (b.mode == Mode.CHALLENGE && challengeable(npc)) {
            if (won) {
                challengeWon(p, npc);
            } else {
                SectProgression.save(p, SectProgression.state(p).record(LOSSES, SectProgression.state(p).entry(LOSSES, 0) + 1));
            }
        }
        if (b.mode != Mode.SPAR) {
            // Both recover outside the ring: the player is at least at half health.
            if (p.getHealth() < p.getMaxHealth() * 0.5F) {
                p.setHealth(p.getMaxHealth() * 0.5F);
            }
            if (won && b.mode == Mode.TRAIN) {
                String key = SectLines.pick(p, "murim.bubble.bout.loser", VARIANTS, p.getRandom());
                SectChatter.send(npc, key, Component.translatable(key));
            }
        }
        if (b.mode == Mode.RULE) {
            SectTrials.ended(p, npc, b, won);
        }
        if (b.mode == Mode.TRIAL) {
            if (trialSatisfied(p, npc, b) && b.outside == 0
                    && (b.ring == null || p.position().distanceToSqr(b.ring) <= OUT_OF_RING * OUT_OF_RING)) {
                SectProgression.save(p, SectProgression.state(p).with(SectProgression.SECOND_TEST));
                SectProgression.say(npc, "murim.bubble.bout.trial_won");
            } else {
                SectProgression.say(npc, "murim.bubble.bout.trial_lost");
            }
            return; // No defeat analysis, drill, penalty, boss reward or qi refill.
        }
        SectDefeat.Reason lost = null;
        if (!won) {
            lost = SectDefeat.analyse(p, npc, b);
        }
        // The master's word on the round, whatever its end (Track C): after a defeat it is the reason the mentor already gives.
        SectCorrection.afterBout(p, npc, b, lost);
    }

    /** Author 21.10: 30 seconds of actual fighting OR three clean recovery-window hits. */
    public static final int TEACHER_TICKS = 30 * 20;
    public static final int TEACHER_HITS = 3;

    static boolean trialSatisfied(ServerPlayer p, SectDisciple npc, Bout b) {
        return b.fightStart >= 0 && (p.level().getGameTime() - b.fightStart >= TEACHER_TICKS
                || npc.cleanHits() >= TEACHER_HITS);
    }

    /** Called at the entity's result boundary, before any win event can escape. */
    public static boolean teacherTest(ServerPlayer p, SectDisciple npc) {
        Bout b = active(p);
        return b != null && b.mode == Mode.TRIAL && b.npc.equals(npc.getUUID());
    }

    // ------------------------------------------------------------------ challenge result

    private static void challengeWon(ServerPlayer p, SectDisciple npc) {
        SectState s = SectProgression.state(p);
        long today = SectProgression.day(p);
        if (s.entry(DAY_PREFIX + npc.memberKey(), -1) >= today) {
            return;
        }
        SectStanding standing = SectService.standing(p);
        s = s.record(TOTAL, s.entry(TOTAL, 0) + 1).record(DAY_PREFIX + npc.memberKey(), today);
        if (npc.member().isPresent() && strongSecond().contains(npc.member().get())) {
            s = s.record(BEAT_PREFIX + npc.memberKey(), 1);
        }
        boolean climbs = standing.ordinal() < CAP.ordinal() && !s.has(CEREMONY);
        // The step to trusted needs seniors at least as strong as the player by rank (equal counts: a strictly-stronger
        // rule left rank 2-3 players no one to beat); a weaker one is respect only.
        boolean weaker = climbs && standing.ordinal() + 1 >= SectStanding.TRUSTED.ordinal()
                && npc.rank() < p.getData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE).rank();
        int wins = wins(s);
        if (climbs && !weaker) {
            wins = Math.min(NEEDED, wins + 1);
            s = s.record(WINS, wins);
            if (wins >= NEEDED) {
                s = s.with(CEREMONY);
            }
        }
        SectProgression.save(p, s);
        SectService.contribute(p, 1);
        // «Well fought, junior» is not said to an elder or a first disciple.
        String respect = SectLines.pick(p, SectRank.of(p).office() ? "murim.bubble.bout.respect_office" : "murim.bubble.bout.respect",
                VARIANTS, p.getRandom());
        SectChatter.send(npc, respect, Component.translatable(respect));
        if (weaker) {
            SectNotice.bar(p, Component.translatable("murim.sect.bout.won_weaker", npc.getDisplayName()).withStyle(ChatFormatting.GOLD));
        } else if (!climbs) {
            SectNotice.bar(p, Component.translatable("murim.sect.bout.won_plain", npc.getDisplayName()).withStyle(ChatFormatting.GOLD));
        } else if (wins >= NEEDED) {
            SectNotice.chat(p, Component.translatable("murim.sect.bout.won_ceremony", npc.getDisplayName()).withStyle(ChatFormatting.LIGHT_PURPLE));
        } else {
            SectNotice.bar(p, Component.translatable("murim.sect.bout.won", npc.getDisplayName(), wins, NEEDED).withStyle(ChatFormatting.GOLD));
        }
        spread(p, npc);
        MurimMod.LOGGER.info("Sect: {} beat senior {} ({}/{})", p.getName().getString(), npc.memberKey(), wins, NEEDED);
    }

    /** The rumour: everyone within sight and hearing of the square gets it, the nearest few say it aloud now. */
    private static void spread(ServerPlayer p, SectDisciple senior) {
        long until = p.level().getGameTime() + RUMOUR_TICKS;
        List<SectDisciple> people = p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(64.0D),
                d -> d != senior && d.isAlive() && d.member().isPresent());
        people.sort(java.util.Comparator.comparingDouble(d -> d.distanceToSqr(p)));
        int spoken = 0;
        for (SectDisciple d : people) {
            CompoundTag tag = new CompoundTag();
            tag.putString("player", p.getName().getString());
            tag.putString("senior", senior.memberKey());
            tag.putLong("until", until);
            d.getPersistentData().put(RUMOUR, tag);
            if (spoken < 3 && d.distanceToSqr(p) <= 24.0D * 24.0D && !d.role().lay()) {
                spoken++;
                // The third variant calls the winner «a junior»: not for an office holder.
                List<String> keys = new java.util.ArrayList<>(SectLines.keys("murim.bubble.bout.rumour", VARIANTS));
                if (SectRank.of(p).office()) keys.remove("murim.bubble.bout.rumour.3");
                String key = SectLines.pick(p, "murim.bubble.bout.rumour", keys, p.getRandom().nextInt(1 << 16));
                SectChatter.send(d, key, Component.translatable(key, p.getName(), senior.getDisplayName()));
            }
        }
    }

    /** The dialogue line of a person who heard the rumour, or null. */
    static Component rumourLine(ServerPlayer p, SectDisciple npc) {
        CompoundTag tag = npc.getPersistentData().getCompound(RUMOUR);
        if (tag.getLong("until") <= p.level().getGameTime() || tag.getString("player").isEmpty()) {
            return null;
        }
        return Component.translatable("dialogue.murim.bout.rumour." + (1 + Math.floorMod(npc.getId(), VARIANTS)),
                Component.literal(tag.getString("player")), SectNames.of(tag.getString("senior")));
    }

    // ------------------------------------------------------------------ ceremony with the leader

    static boolean canPromo(ServerPlayer p, SectDisciple npc) {
        return SectProgression.ready(p) && SectProgression.available(p, npc) && npc.role() == SectRole.LEADER
                && SectProgression.state(p).has(CEREMONY);
    }

    private static boolean ceremonyReady(ServerPlayer p) {
        long at = SectProgression.state(p).entry(CEREMONY_AT, -1);
        return at >= 0 && p.level().getGameTime() - at >= CEREMONY_TICKS;
    }

    private static boolean promoBegin(ServerPlayer p, SectDisciple npc) {
        if (!canPromo(p, npc)) {
            return false;
        }
        if (SectProgression.state(p).entry(CEREMONY_AT, -1) < 0) {
            SectProgression.save(p, SectProgression.state(p).record(CEREMONY_AT, p.level().getGameTime()));
        }
        return true;
    }

    private static boolean promoConfirm(ServerPlayer p, SectDisciple npc) {
        if (!canPromo(p, npc) || !ceremonyReady(p)) {
            return false;
        }
        SectStanding before = SectService.standing(p);
        int target = Math.min(CAP.ordinal(), before.ordinal() + 1);
        SectState s = SectProgression.state(p).without(CEREMONY).record(WINS, 0).record(CEREMONY_AT, -1);
        if (target > before.ordinal()) {
            s = s.record(GRANTED, target);
        }
        SectProgression.save(p, s);
        SectService.announceStanding(p, before);
        SectProgression.say(npc, "murim.bubble.bout.promoted");
        for (SectDisciple d : p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(16.0D),
                d -> d != npc && d.isAlive() && d.member().isPresent())) {
            d.gesture("nod");
        }
        if (p.level() instanceof ServerLevel level) {
            for (ServerPlayer other : level.players()) {
                if (other != p && SectService.state(other).member()) {
                    SectNotice.bar(other, Component.translatable("murim.sect.bout.promoted_news", p.getName(),
                            Component.translatable(SectService.standing(p).nameKey())).withStyle(ChatFormatting.AQUA));
                }
            }
        }
        MurimMod.LOGGER.info("Sect: {} promoted by challenges to {}", p.getName().getString(), SectService.standing(p).id());
        return true;
    }

    // ------------------------------------------------------------------ journal

    /** The journal line "Challenges to seniors: 3/5", or null before the player can use it. */
    public static Component progress(ServerPlayer p) {
        SectState s = SectService.state(p);
        if (!s.member() || SectPositions.disciplined(s)) {
            return null;
        }
        if (s.has(CEREMONY)) {
            return Component.translatable("murim.sect.bout.progress_ceremony", NEEDED, NEEDED);
        }
        if (SectService.standing(p).ordinal() >= CAP.ordinal()) {
            return s.entry(TOTAL, 0) == 0 ? null : Component.translatable("murim.sect.bout.progress_total", s.entry(TOTAL, 0));
        }
        return Component.translatable("murim.sect.bout.progress", wins(s), NEEDED);
    }
}
