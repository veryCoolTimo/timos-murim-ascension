package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.mastery.MasteryRules;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

/**
 * Defeat analysis (docs/design/39-player-path-v2.md item 5). After a lost bout the player's teacher (the direct
 * master after the discipleship, otherwise Gyeong Pil) comes over, or calls the player when he is not near, and says
 * one concrete reason read from the bout data ({@link #diagnose}) and gives one drill for today. Doing the drill gives
 * a mastery bonus ({@link #reward}). Nothing is punished; the drill is an invitation.
 */
public final class SectDefeat {

    /** Why the bout was lost, from what the bout recorded. */
    public enum Reason {
        /** The qi ran dry in the bout. */
        QI,
        /** Too many hits taken. */
        HITS,
        /** Stayed far away and never closed in. */
        FAR,
        /** Stood too close, no room to strike or to evade. */
        CLOSE,
        /** Hardly struck at all. */
        PASSIVE;

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** The one exercise of the day and how much of it is needed. */
    public enum Drill {
        /** Ticks of meditation. */
        MEDITATE(600),
        /** Hits on any target. */
        STRIKES(20),
        /** Blocks walked on the ground. */
        FOOTWORK(250);

        private final int need;

        Drill(int need) {
            this.need = need;
        }

        public int need() {
            return need;
        }

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    static final String DRILL = "defeat.drill";
    static final String DAY = "defeat.day";
    static final String DONE = "defeat.done";
    static final String REASON = "defeat.reason";
    /** Fraction of the next star's need the finished drill gives. */
    static final double BONUS = 0.08D;
    /** The mentor walks over for this long, then simply calls. */
    static final int COME_TICKS = 600;
    static final String DEFAULT_TEACHER = "gyeong_pil";

    /** The analysis waiting to be said. */
    record Pending(String teacher, Reason reason, Drill drill, int taken, int dealt, double distance, int qiPercent,
                   long due, long since) {
    }

    /** The drill in progress this session. */
    static final class Run {
        final Drill drill;
        double progress;
        Vec3 last;

        Run(Drill drill) {
            this.drill = drill;
        }
    }

    private SectDefeat() {
    }

    // ------------------------------------------------------------------ the diagnosis (pure)

    /**
     * One reason from the bout data.
     *
     * @param qiStart share of the qi pool full at the start of the bout (0..1)
     * @param qiLow   the lowest share during the bout
     */
    public static Reason diagnose(int taken, int dealt, double meanDistance, double qiStart, double qiLow) {
        if (qiStart >= 0.3D && qiLow < 0.12D) {
            return Reason.QI;
        }
        if (meanDistance > 4.5D && dealt <= 2) {
            return Reason.FAR;
        }
        if (meanDistance < 1.6D && taken >= 2) {
            return Reason.CLOSE;
        }
        if (taken >= 3 && taken >= dealt + 2) {
            return Reason.HITS;
        }
        return Reason.PASSIVE;
    }

    public static Drill drillFor(Reason reason) {
        return switch (reason) {
            case QI -> Drill.MEDITATE;
            case HITS, CLOSE -> Drill.FOOTWORK;
            case FAR, PASSIVE -> Drill.STRIKES;
        };
    }

    /** The teacher of the player: the master after discipleship, otherwise the sect mentor. */
    public static String teacher(SectState s) {
        return s.has(SectProgression.DIRECT) ? SectProgression.MENTOR : DEFAULT_TEACHER;
    }

    // ------------------------------------------------------------------ after a lost bout

    /** From {@link SectBouts#ended}: choose the reason and the drill and queue the mentor. Returns the reason named. */
    static Reason analyse(ServerPlayer p, SectDisciple npc, SectBouts.Bout b) {
        Reason reason = diagnose(b.taken, b.dealt, b.meanDistance(), b.qiStart, b.qiLow);
        SectState s = SectProgression.state(p);
        long today = SectProgression.day(p);
        Drill drill = drillFor(reason);
        boolean open = s.entry(DRILL, 0) > 0 && s.entry(DAY, -1) == today && s.entry(DONE, -1) != today;
        if (open) {
            drill = Drill.values()[(int) s.entry(DRILL, 1) - 1];
        } else {
            s = s.record(DRILL, drill.ordinal() + 1).record(DAY, today).record(REASON, reason.ordinal() + 1);
            SectProgression.save(p, s);
            SectBoutSession.of(p).drills.put(p.getUUID(), new Run(drill));
            SectTablet.touch(p);
        }
        int qi = (int) Math.round(Math.max(0.0D, Math.min(1.0D, b.qiLow)) * 100.0D);
        long now = p.level().getGameTime();
        SectBoutSession.of(p).pending.put(p.getUUID(), new Pending(teacher(s), reason, drill, b.taken, b.dealt,
                b.meanDistance(), qi, now + 60L, now + 60L));
        MurimMod.LOGGER.info("Sect: {} lost to {}: {} -> {}", p.getName().getString(), npc.memberKey(), reason.id(), drill.id());
        return reason;
    }

    /** One tick of the player: the waiting analysis and the drill of the day. */
    static void tick(ServerPlayer p) {
        SectBoutSession session = SectBoutSession.of(p);
        if (p.tickCount % 20 == 3) {
            syncRun(p, session);
        }
        Pending pending = session.pending.get(p.getUUID());
        if (pending != null && p.tickCount % 10 == 0) {
            deliver(p, session, pending);
        }
        Run run = session.drills.get(p.getUUID());
        if (run == null) {
            return;
        }
        switch (run.drill) {
            case MEDITATE -> {
                if (p.getData(ModAttachments.MEDITATION).active()) {
                    add(p, run, 1.0D);
                }
            }
            case FOOTWORK -> {
                if (p.tickCount % 5 == 0) {
                    Vec3 here = p.position();
                    if (run.last != null && p.onGround() && !p.isPassenger()) {
                        double moved = Math.hypot(here.x - run.last.x, here.z - run.last.z);
                        add(p, run, moved <= 3.0D ? moved : 0.0D);
                    }
                    run.last = here;
                }
            }
            default -> {
            }
        }
    }

    /** A hit of the player on a target other than a player (the strikes drill). */
    static void struck(ServerPlayer p) {
        Run run = SectBoutSession.of(p).drills.get(p.getUUID());
        if (run != null && run.drill == Drill.STRIKES) {
            add(p, run, 1.0D);
        }
    }

    /** The journal says which drill is open today; the session run follows it (a new session, a new day). */
    private static void syncRun(ServerPlayer p, SectBoutSession session) {
        SectState s = SectProgression.state(p);
        boolean open = s.entry(DRILL, 0) > 0 && s.entry(DAY, -1) == SectProgression.day(p) && s.entry(DONE, -1) != s.entry(DAY, -1);
        Run run = session.drills.get(p.getUUID());
        if (!open) {
            if (run != null) {
                session.drills.remove(p.getUUID());
            }
            return;
        }
        Drill drill = Drill.values()[(int) s.entry(DRILL, 1) - 1];
        if (run == null || run.drill != drill) {
            session.drills.put(p.getUUID(), new Run(drill));
        }
    }

    /** Add progress; the drill is done at its need. */
    static void add(ServerPlayer p, Run run, double amount) {
        run.progress += amount;
        if (run.progress >= run.drill.need()) {
            SectBoutSession.of(p).drills.remove(p.getUUID());
            SectProgression.save(p, SectProgression.state(p).record(DONE, SectProgression.day(p)));
            reward(p);
            SectNotice.bar(p, Component.translatable("murim.sect.defeat.drill_done").withStyle(ChatFormatting.GOLD));
            SectTablet.touch(p);
        }
    }

    /** The mastery bonus of the finished drill; without a known foundation it is one merit. */
    static void reward(ServerPlayer p) {
        ResourceLocation technique = SectProgression.lessonTechnique(p);
        if (MasteryService.knows(p, technique) && !p.getData(ModAttachments.MASTERY).techniques().get(technique).atCap()) {
            MasteryService.help(p, technique, MasteryRules.HelpSource.LESSON, MasteryService.nextCost(p, technique) * BONUS, true);
        } else {
            SectService.contribute(p, 1);
        }
    }

    // ------------------------------------------------------------------ the mentor comes or calls

    private static void deliver(ServerPlayer p, SectBoutSession session, Pending pending) {
        long now = p.level().getGameTime();
        if (now - pending.since() > 2400L) {
            // The player never had a free moment: the lesson is dropped, nothing is owed.
            session.pending.remove(p.getUUID());
            return;
        }
        if (now < pending.due() || p.getData(ModAttachments.DIALOGUE).active() || SectBouts.active(p) != null) {
            return;
        }
        List<SectDisciple> near = p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(64.0D),
                d -> d.isAlive() && pending.teacher().equals(d.memberKey()) && d.free() && !d.dormant());
        SectDisciple teacher = near.stream().min(Comparator.comparingDouble(d -> d.distanceToSqr(p))).orElse(null);
        if (teacher != null && teacher.distanceToSqr(p) > 4.5D * 4.5D && now - pending.since() < COME_TICKS) {
            // The same walk-up as every other approach (SectApproach): it keeps going to the player on its own. The old
            // "walk once when game time is a multiple of 40" never met this method's own every-tenth-tick beat.
            if (!teacher.blocking()) {
                teacher.approach(p, "defeat", (int) Math.max(40L, COME_TICKS - (now - pending.since())));
            }
            return;
        }
        session.pending.remove(p.getUUID());
        Component name = SectNames.of(pending.teacher());
        // An office holder gets an observation between colleagues, not a lesson (docs/design/39 «Обращение по положению»).
        boolean office = SectRank.of(p).office();
        Component reason = Component.translatable("murim.sect.defeat.reason." + (office ? "peer." : "") + pending.reason().id(),
                pending.taken(), pending.dealt(), String.format(java.util.Locale.ROOT, "%.1f", pending.distance()), pending.qiPercent());
        Component drill = Component.translatable("murim.sect.defeat.drill." + pending.drill().id(), pending.drill().need());
        SectNotice.bar(p, Component.translatable(teacher != null ? "murim.sect.defeat.come" : "murim.sect.defeat.call", name)
                .withStyle(ChatFormatting.GRAY));
        SectNotice.chat(p, Component.translatable("murim.sect.defeat.say", name, reason).withStyle(ChatFormatting.YELLOW));
        SectNotice.chat(p, Component.translatable(office ? "murim.sect.defeat.drill_say_peer" : "murim.sect.defeat.drill_say", drill)
                .withStyle(ChatFormatting.GOLD));
        if (teacher != null) {
            teacher.faceEntity(p, 60.0F);
            teacher.gesture("explain");
            SectChatter.send(teacher, "murim.sect.defeat.reason." + (office ? "peer." : "") + pending.reason().id(), reason);
        }
    }

    /** Today's drill as the guidance, the tablet and the teacher's words read it. */
    public record Open(Drill drill, int progress, String teacher) {
        public int need() {
            return drill.need();
        }
    }

    /** The drill the teacher gave today and the player has not finished, or null. */
    public static Open open(ServerPlayer p) {
        SectState s = SectProgression.state(p);
        if (!s.member() || s.entry(DRILL, 0) <= 0 || s.entry(DAY, -1) != SectProgression.day(p) || s.entry(DONE, -1) == s.entry(DAY, -1)) {
            return null;
        }
        int at = (int) s.entry(DRILL, 1) - 1;
        if (at < 0 || at >= Drill.values().length) return null;
        Run run = SectBoutSession.of(p).drills.get(p.getUUID());
        Drill drill = Drill.values()[at];
        return new Open(drill, run != null && run.drill == drill ? (int) Math.min(run.progress, drill.need()) : 0, teacher(s));
    }

    /** The drill given today, finished or not, or null. */
    public static Drill given(ServerPlayer p) {
        SectState s = SectProgression.state(p);
        if (!s.member() || s.entry(DRILL, 0) <= 0 || s.entry(DAY, -1) != SectProgression.day(p)) return null;
        int at = (int) s.entry(DRILL, 1) - 1;
        return at < 0 || at >= Drill.values().length ? null : Drill.values()[at];
    }

    /** True if today's drill is done (it was given and finished). */
    public static boolean doneToday(ServerPlayer p) {
        SectState s = SectProgression.state(p);
        return s.entry(DRILL, 0) > 0 && s.entry(DONE, -1) == SectProgression.day(p);
    }

    /** Journal line: today's drill and how far it is, or null. */
    public static Component line(ServerPlayer p) {
        Run run = SectBoutSession.of(p).drills.get(p.getUUID());
        SectState s = SectProgression.state(p);
        if (run == null || !s.member() || s.entry(DAY, -1) != SectProgression.day(p)) {
            return null;
        }
        return Component.translatable("murim.sect.defeat.progress", Component.translatable("murim.sect.defeat.name." + run.drill.id()),
                (int) run.progress, run.drill.need());
    }

    /** The drill in progress, for tests. */
    static Run run(ServerPlayer p) {
        return SectBoutSession.of(p).drills.get(p.getUUID());
    }
}
