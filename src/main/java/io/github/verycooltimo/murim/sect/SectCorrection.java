package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.FoundationForms;
import io.github.verycooltimo.murim.combat.SwordBasicCombo;
import io.github.verycooltimo.murim.combat.TechniqueState;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.Comparator;
import java.util.Optional;
import java.util.Set;

/**
 * The master's correction (Track C, docs/design/research/hua-main-1810.md section 6, T2): after a round the mentor who ran it
 * names <b>one</b> mistake read from the fight log, in a bubble over his head. The player who fixes it in the next round
 * gets «lesson learned»: practice counts {@link #LESSON_FACTOR} times for {@link #LESSON_TICKS} ticks (ten minutes).
 *
 * <p>This widens the defeat analysis ({@link SectDefeat}), which spoke only after a lost bout: a lost bout still gets the
 * mentor's walk-up and drill, and the reason he names there is the mistake to fix; every other round gets the bubble. Nothing is
 * punished; the word is an invitation, and a lesson is earned at most once a sect day.
 *
 * <p>Pure rules ({@link Log}, {@link #pick}, {@link #present}) are unit tested; the glue reads the bout the player just fought
 * ({@link SectBouts.Bout}) and keeps the state in the player's {@link SectState} journal under three fixed keys
 * ({@link #NAMED}, {@link #UNTIL}, {@link #DAY}).
 *
 * <p>The lesson is help from the mentor, and all help is under one shared cap of a fifth of every star
 * ({@link io.github.verycooltimo.murim.mastery.MasteryRules#HELP_CAP}): for ten minutes the player's own practice counts one
 * and a half times, but the extra half is given through {@code MasteryService.help} and takes its share of that pool (it gives
 * nothing when the pool of the star is full, and nothing when he does not practise). A lesson is earned at most once a sect day.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectCorrection {

    /** Saved ordinals are frozen: append only. The mistakes the mentor can name, most instructive first: {@link #pick} takes the first that is in the log. */
    public enum Mistake {
        /** Struck while a technique was still winding up. */
        WINDUP_HIT,
        /** Swung just as the partner struck, and was hit for it. */
        PUNISHED,
        /** Swung and missed again and again: forms dropped before they landed. */
        WHIFF,
        /** The same form over and over although others were open. */
        ONE_FORM,
        /** Spent the round at the edge of the ring or past it. */
        EDGE,
        /** The qi ran dry. */
        QI,
        /** Stood too close to cut or to step. */
        CLOSE,
        /** Took far more blows than gave. */
        HITS,
        /** Stayed far away and never closed in. */
        FAR,
        /** Hardly struck at all. */
        PASSIVE;

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        /** The mistake of a journal value ({@code ordinal + 1}, 0 is none), or null. */
        static Mistake at(long value) {
            return value >= 1 && value <= values().length ? values()[(int) value - 1] : null;
        }
    }

    /**
     * What one bout recorded. {@code mostOfOneForm} is how many swings the most used form had, {@code unlockedForms} how many
     * forms the player could change between, {@code windupHits} the blows taken while a technique was winding up,
     * {@code punished} the blows that came after a swing begun into the partner's strike, {@code outTicks} the time out of the ring.
     */
    public record Log(int taken, int dealt, double meanDistance, double qiStart, double qiLow, int swings, int mostOfOneForm,
                      int unlockedForms, int windupHits, int punished, int outTicks, long fightTicks) {
    }

    // ------------------------------------------------------------------ the rules (pure)

    /** A bout counts as a real round, for naming and for fixing: ten seconds of fight, or three blows between them. */
    public static final int SUBSTANTIAL_TICKS = 200;
    public static final int SUBSTANTIAL_BLOWS = 3;

    /** Is this mistake in the log of the bout. */
    public static boolean present(Mistake m, Log l) {
        return switch (m) {
            case WINDUP_HIT -> l.windupHits() >= 2;
            case PUNISHED -> l.punished() >= 2;
            case WHIFF -> l.swings() >= 5 && (l.swings() - l.dealt()) * 100 >= l.swings() * 60;
            case ONE_FORM -> l.unlockedForms() >= 2 && l.swings() >= 6 && l.mostOfOneForm() * 100 >= l.swings() * 80;
            case EDGE -> l.outTicks() >= 20;
            case QI -> l.qiStart() >= 0.3D && l.qiLow() < 0.12D;
            case CLOSE -> l.meanDistance() < 1.6D && l.taken() >= 2;
            case HITS -> l.taken() >= 3 && l.taken() >= l.dealt() + 2;
            case FAR -> l.meanDistance() > 4.5D && l.dealt() <= 2;
            case PASSIVE -> l.dealt() == 0 && l.fightTicks() >= SUBSTANTIAL_TICKS;
        };
    }

    /** The one mistake to name, or null when the log shows none. */
    public static Mistake pick(Log l) {
        for (Mistake m : Mistake.values()) {
            if (present(m, l)) {
                return m;
            }
        }
        return null;
    }

    public static boolean substantial(Log l) {
        return l.fightTicks() >= SUBSTANTIAL_TICKS || l.taken() + l.dealt() >= SUBSTANTIAL_BLOWS;
    }

    /** The mistake named before is fixed: a real round in which it is not to be found. */
    public static boolean fixed(Mistake named, Log l) {
        return named != null && substantial(l) && !present(named, l);
    }

    // ------------------------------------------------------------------ journal keys and numbers

    /** Journal: the mistake the mentor named last ({@code ordinal + 1}), absent or 0 when none is open. */
    static final String NAMED = "correct.named";
    /** Journal: game time until which the lesson learned counts. */
    static final String UNTIL = "correct.until";
    /** Journal: the sect day of the last lesson learned (one a day). */
    static final String DAY = "correct.day";

    /** «Lesson learned»: ten minutes of practice at one and a half. */
    public static final int LESSON_TICKS = 12000;
    public static final double LESSON_FACTOR = 1.5D;

    /** The words wait for the bow to end; the speaker walks up for this long and then simply stays where he is. */
    static final int AFTER_BOW = 80;
    static final int COME_TICKS = 600;
    static final int GIVE_UP_TICKS = 2400;
    /** A swing begun into the partner's strike and a blow this soon after it are one mistake. */
    static final int INTO_STRIKE_WINDOW = 12;
    /** Who may name a mistake after a round he fought himself: the mentors and the senior of the second generation. */
    static final Set<String> MENTORS = Set.of("gyeong_pil", "tae_seong", "seo_rang");
    /** The pool of the confirmation bubbles. */
    static final int LEARNED_VARIANTS = 6;

    /** What is to be said: {@code mistake} 0 is the confirmation of a lesson learned. */
    record Pending(String speaker, int mistake, long due, long since) {
    }

    private SectCorrection() {
    }

    /** The factor of practice now: {@link #LESSON_FACTOR} while a lesson learned lasts, else 1. */
    public static double lessonFactor(ServerPlayer p) {
        return lessonLeft(p) > 0L ? LESSON_FACTOR : 1.0D;
    }

    /** Ticks of the lesson learned that are left; 0 when there is none. */
    public static long lessonLeft(ServerPlayer p) {
        return Math.max(0L, SectService.state(p).entry(UNTIL, -1L) - p.level().getGameTime());
    }

    // ------------------------------------------------------------------ what the bout shows

    /**
     * A blow of the partner on the player in a bout: struck while a technique was winding up, or soon after a swing begun
     * into the partner's strike. Before the technique code sees the blow (it may break the windup), hence the high priority.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onIncoming(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || event.getSource().getEntity() == null) {
            return;
        }
        SectBouts.Bout b = SectBouts.active(p);
        if (b == null || b.fightStart < 0 || !event.getSource().getEntity().getUUID().equals(b.npc)) {
            return;
        }
        TechniqueState state = p.getData(ModAttachments.TECHNIQUE_STATE);
        if (state.isActive() && !state.impactDone()) {
            b.windupHits++;
        } else if (p.level().getGameTime() - b.intoStrikeAt <= INTO_STRIKE_WINDOW) {
            b.punished++;
            b.intoStrikeAt = Long.MIN_VALUE / 2;
        }
    }

    /** How many forms the player can change between with his foundation now. */
    static int unlockedForms(ServerPlayer p) {
        Optional<ResourceLocation> foundation = p.getData(ModAttachments.LOADOUT).foundation();
        if (foundation.isEmpty()) {
            return 1;
        }
        if (SwordBasicCombo.isBasic(foundation.get())) {
            return 3;
        }
        return FoundationForms.unlocked(Math.max(0, MasteryService.executionTier(p, foundation.get())));
    }

    /** Who says it: the partner when he is a mentor (he ran the round), else the player's own teacher. */
    static String speakerFor(ServerPlayer p, SectDisciple npc) {
        return MENTORS.contains(npc.memberKey()) ? npc.memberKey() : SectDefeat.teacher(SectProgression.state(p));
    }

    // ------------------------------------------------------------------ after the round

    /**
     * From {@link SectBouts#ended}: fix what was named before, then name one mistake of this round.
     *
     * @param lost the reason the defeat analysis gave when the round was lost, else null
     */
    static void afterBout(ServerPlayer p, SectDisciple npc, SectBouts.Bout b, SectDefeat.Reason lost) {
        SectState s = SectProgression.state(p);
        if (!s.member()) {
            return;
        }
        long now = p.level().getGameTime();
        long today = SectProgression.day(p);
        SectCorrection.Log log = b.log(now, unlockedForms(p));
        Mistake named = Mistake.at(s.entry(NAMED, 0L));
        String speaker = speakerFor(p, npc);
        SectBoutSession session = SectBoutSession.of(p);
        // This round supersedes a word of the last one that still waits for its speaker: one word at a time, and it is the one
        // the journal expects to be fixed.
        session.corrections.remove(p.getUUID());
        boolean learned = fixed(named, log) && s.entry(DAY, -1L) < today;
        if (learned) {
            SectSpeechWitnesses.record(p, "lesson");
            s = s.record(UNTIL, now + LESSON_TICKS).record(DAY, today).record(NAMED, 0L);
            MurimMod.LOGGER.info("Sect: {} fixed {}: lesson learned", p.getName().getString(), named.id());
        }
        if (lost != null) {
            // The mentor says the reason himself (SectDefeat); it is also the mistake to fix.
            Mistake mistake = io.github.verycooltimo.murim.compat.SavedEnums.read(Mistake.class, lost.name()).orElse(null);
            SectProgression.save(p, mistake == null ? s : s.record(NAMED, mistake.ordinal() + 1L));
            if (learned) {
                SectNotice.bar(p, Component.translatable("murim.correction.learned"));
            }
            return;
        }
        if (learned) {
            SectProgression.save(p, s);
            session.corrections.put(p.getUUID(), new Pending(speaker, 0, now + AFTER_BOW, now));
            return;
        }
        Mistake m = pick(log);
        if (m == null || !substantial(log)) {
            return;
        }
        SectProgression.save(p, s.record(NAMED, m.ordinal() + 1L));
        session.corrections.put(p.getUUID(), new Pending(speaker, m.ordinal() + 1, now + AFTER_BOW, now));
        MurimMod.LOGGER.info("Sect: {} after a round with {}: {}", p.getName().getString(), npc.memberKey(), m.id());
    }

    /** One tick of the player: the word waiting for its speaker. */
    static void tick(ServerPlayer p) {
        if (p.tickCount % 10 != 4) {
            return;
        }
        SectBoutSession session = SectBoutSession.of(p);
        Pending pending = session.corrections.get(p.getUUID());
        if (pending != null) {
            deliver(p, session, pending);
        }
    }

    private static void deliver(ServerPlayer p, SectBoutSession session, Pending pending) {
        long now = p.level().getGameTime();
        if (now - pending.since() > (pending.mistake() == 0 ? SectSpeechGuard.DEED_TICKS : GIVE_UP_TICKS)) {
            // The player never had a free moment: the word is dropped, nothing is owed.
            session.corrections.remove(p.getUUID());
            return;
        }
        if (now < pending.due() || p.getData(ModAttachments.DIALOGUE).active() || SectBouts.active(p) != null) {
            return;
        }
        SectDisciple who = p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(64.0D),
                        d -> d.isAlive() && pending.speaker().equals(d.memberKey()) && !d.dormant())
                .stream().min(Comparator.comparingDouble(d -> d.distanceToSqr(p))).orElse(null);
        if (who == null) {
            session.corrections.remove(p.getUUID());
            return;
        }
        if (!who.free()) {
            // Still bowing out of the round, or busy: the next beat.
            return;
        }
        if (who.distanceToSqr(p) > 4.5D * 4.5D && now - pending.since() < COME_TICKS) {
            if (!who.blocking()) {
                who.approach(p, "correction", (int) Math.max(40L, COME_TICKS - (now - pending.since())));
            }
            return;
        }
        session.corrections.remove(p.getUUID());
        say(p, who, pending.mistake());
    }

    /** The bubble over the speaker and the line over the hotbar; never the chat. */
    static void say(ServerPlayer p, SectDisciple who, int mistake) {
        who.faceEntity(p, 60.0F);
        Component name = SectNames.of(who.memberKey());
        if (mistake == 0) {
            String key = SectLines.pick(p, "murim.bubble.lesson", LEARNED_VARIANTS, who.getRandom());
            SectChatter.send(who, key, Component.translatable(key));
            who.gesture("nod");
            SectNotice.bar(p, Component.translatable("murim.correction.learned"));
            return;
        }
        Mistake m = Mistake.at(mistake);
        if (m == null) {
            return;
        }
        // An office holder gets an observation between colleagues, not a lesson (docs/design/39, «Обращение по положению»).
        String key = "murim.correction." + (SectRank.of(p).office() ? "peer." : "say.") + m.id();
        Component line = Component.translatable(key);
        SectChatter.send(who, key, line);
        who.gesture("explain");
        SectNotice.bar(p, Component.translatable("murim.correction.said", name, line));
    }

    /** The mistake the mentor named and the player has not fixed yet, or null (for tests and the journal). */
    public static Mistake named(ServerPlayer p) {
        return Mistake.at(SectService.state(p).entry(NAMED, 0L));
    }
}
