package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.entity.SectDisciple;
import net.minecraft.core.BlockPos;

import java.util.Set;

/**
 * Lead time (docs/adr/0017, outside playtest 19.10: "they are late to the dining hall"): a person leaves for the place of the next
 * part of the day early enough to be there when it starts, instead of leaving when it starts.
 *
 * <p>The schedule says what a person does at a time of day. It does not know that the dining hall is 100 cells from the
 * training square, so everyone used to leave for the table at the bell and sat down 30 to 90 seconds late. Here the walk is
 * measured on the route the walker will take ({@link SectRouter}), and the change of task is handed over that many ticks
 * before the bell, with route-proportional queue slack and a small personal delay ({@link #JITTER}) so that the crowd still does not arrive in one tick. The
 * person aims to arrive before the start of the period (the stated tolerance is 200 ticks after it), when the way is
 * free; a queue at a narrow stair adds to that.
 *
 * <p>The state is per person and not saved: after a reload the look-ahead starts again from the world time.
 */
public final class SectLead {

    /** How far ahead the next change of task is looked for, ticks (the longest walk of the sect is about 2000: the dormitory to the training square). */
    public static final int MAX_LEAD = 3600;
    /** Step of the search for the change of task. */
    static final int STEP = 20;
    /** Spread of the arrivals around the start of the part of the day: each person is planned to arrive within half of this either side (6 s). */
    public static final int JITTER = 120;
    /** The least a diner eats before he may get up to walk to what comes next, ticks (12 s). */
    public static final int MIN_EAT = 240;
    /** Safety margin added to the estimated walk, ticks. */
    static final int MARGIN = 60;
    /** Queue/stair allowance grows with the journey; the third dorm needs about one extra minute. */
    static int travelMargin(int walk) {
        return MARGIN + (int) Math.ceil(walk * 0.65D);
    }
    /**
     * Ticks one cell of the route takes at full speed (speed factor 1): measured by the day simulation as "free walking ticks per
     * planned cell" divided by the speed factor of the walk (tools/build/sect/sim).
     */
    public static final double TICKS_PER_CELL_FULL = 9.0D;
    /** A walk of more than this many blocks is a trip, not a hop between two desks: it is done at least at {@link #TRANSIT}. */
    public static final double TRIP_BLOCKS = 20.0D;
    /** The brisk walk of a trip (speed factor); a hop keeps the stroll of its task (0.5 to 0.8). */
    public static final double TRANSIT = 1.0D;
    /** Speed factor of the stroll that the last {@link #TRIP_BLOCKS} of a trip are walked at, and of a hop. */
    static final double STROLL = 0.6D;
    /** Latest hurry factor of a person who is behind. */
    public static final double HURRY = 1.5D;
    /** The estimate is renewed after this many ticks or when he has moved this far. */
    private static final int REPLAN_TICKS = 100;

    /**
     * Off with {@code -Dmurim.sect.lead=off}: the day simulation runs the routine of before the lead time to measure against
     * (tools/build/sect/sim/run_sim.sh, LEAD=off).
     */
    public static final boolean ENABLED = !"off".equals(System.getProperty("murim.sect.lead"));

    private SectLead() {
    }

    /** What the look-ahead remembers about one person. */
    public static final class State {
        /** World day time of the next change of task found, {@link Long#MIN_VALUE}: none searched for yet. */
        long boundary = Long.MIN_VALUE;
        /** No search before this world time (nothing changed within {@link #MAX_LEAD} the last time). */
        long searchedTo = Long.MIN_VALUE;
        /** Estimated walk to the next place in ticks, -1 unknown. */
        int walk = -1;
        long walkAt = Long.MIN_VALUE;
        BlockPos walkFrom;
        /** The task of {@link #boundary} has been handed over (he is on his way) until the world time reaches it. */
        boolean latched;
        /** World day time at which the task he walks to now is due, -1 none (he is not walking to a deadline). */
        long dueAt = -1L;
    }

    /** A change of task as the person sees it: the kind and the place of the part of the day. */
    private static String key(SectLayoutFile file, SectRoster m, boolean events, long time) {
        long sched = SectPlaces.time(file, m, time);
        SectSchedule.Task t = SectPlaces.adjust(m, SectSchedule.task(m, sched, events), time);
        return t.kind() + "|" + t.zone() + "|" + t.du() + "|" + t.dv() + "|" + t.partner();
    }

    /** First tick after {@code time} at which the task of the person differs from his task now, within {@link #MAX_LEAD}; -1 none. */
    static long nextChange(SectLayoutFile file, SectRoster m, boolean events, long time) {
        String now = key(file, m, events, time);
        for (long t = time + STEP; t <= time + MAX_LEAD; t += STEP) {
            if (!now.equals(key(file, m, events, t))) {
                long lo = t - STEP;
                long hi = t;
                while (hi - lo > 1) {
                    long mid = (lo + hi) >>> 1;
                    if (now.equals(key(file, m, events, mid))) {
                        lo = mid;
                    } else {
                        hi = mid;
                    }
                }
                return hi;
            }
        }
        return -1L;
    }

    /** The personal delay after the start of the part of the day, 0..{@link #JITTER}. */
    static int jitter(SectRoster m, long boundary) {
        return SectStagger.ticks(m.key(), "arrive" + Math.floorMod(boundary, (long) SectSchedule.DAY), 0, JITTER);
    }

    /** Ticks the walk from {@code from} to the place of the task at {@code boundary} takes; 0 when there is no place. */
    static int walkTicks(SectLayoutFile file, SectRoster m, boolean events, BlockPos from, long boundary) {
        long sched = SectPlaces.time(file, m, boundary);
        SectSchedule.Task task = SectPlaces.adjust(m, SectSchedule.task(m, sched, events), boundary);
        // A scratch book of spots: the real one belongs to the person's task of now and is not touched by a look-ahead.
        SectPlaces.Pick pick = SectPlaces.pick(file, m, task, boundary, new SectSpots(), 0L, java.util.Map.of());
        if (pick == null) {
            return 0;
        }
        BlockPos to = pick.cell();
        double flat = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        // As the walker does (SectWalking#newGoal): a short walk or one inside a room is not routed over the graph.
        var here = file.roomAt(from);
        if (flat < 8.0D && Math.abs(to.getY() - from.getY()) < 2 || here != null && here == file.roomAt(to)) {
            return ticksFor(m, 1.2D * flat);
        }
        SectRouter.Plan plan = SectRouter.plan(file, from, to, Set.of());
        return ticksFor(m, plan != null ? plan.cost() : 1.4D * flat);
    }

    /** Ticks a person needs for {@code cells} cells of route: a stroll for the last {@link #TRIP_BLOCKS}, brisk before that. */
    static int ticksFor(SectRoster m, double cells) {
        double pace = SectStagger.factor(m.key(), "pace", 0.12D);
        double stroll = Math.min(cells, TRIP_BLOCKS);
        double brisk = Math.max(0.0D, cells - TRIP_BLOCKS);
        return (int) Math.ceil((stroll / STROLL + brisk / TRANSIT) * TICKS_PER_CELL_FULL / pace);
    }

    /**
     * The time the routine of this person is resolved for: the world time, or the start of the next part of the day once the
     * walk to its place has to begin.
     */
    public static long time(SectDisciple npc, SectLayoutFile file, SectRoster m, boolean events, long time) {
        // Watch rounds and meal relief use the actual clock: look-ahead must not send a guard
        // away from the post early, or advance to the next guard's meal turn.
        if (SectRota.onDuty(m, time) || m.role() == SectRole.GATEKEEPER) return time;
        return ENABLED ? time(npc.leadState(), npc.blockPosition(), file, m, events, time) : time;
    }

    /** As {@link #time(SectDisciple, SectLayoutFile, SectRoster, boolean, long)} for a person standing at {@code at}, with his state. */
    static long time(State s, BlockPos at, SectLayoutFile file, SectRoster m, boolean events, long time) {
        // The clock went back (/time set, a test): what was found for the future is stale.
        if (s.searchedTo > time + MAX_LEAD || s.boundary != Long.MIN_VALUE && s.boundary > time + MAX_LEAD + STEP) {
            s.boundary = Long.MIN_VALUE;
            s.searchedTo = Long.MIN_VALUE;
            s.latched = false;
            s.walk = -1;
        }
        if (s.latched) {
            if (time < s.boundary) {
                return s.boundary;
            }
            s.latched = false;
            s.boundary = Long.MIN_VALUE;
            s.walk = -1;
        }
        if (s.boundary == Long.MIN_VALUE) {
            if (time < s.searchedTo) {
                return time;
            }
            s.boundary = nextChange(file, m, events, time);
            s.walk = -1;
            if (s.boundary < 0) {
                // Nothing changes for MAX_LEAD ticks: look again in a sixth of that (the walk of the longest way is a good half of the look-ahead).
                s.boundary = Long.MIN_VALUE;
                s.searchedTo = time + MAX_LEAD / 6;
                return time;
            }
        } else if (time >= s.boundary) {
            // The boundary has passed without a hand-over (he was busy, or the sect was unloaded): look for the next one.
            s.boundary = Long.MIN_VALUE;
            s.searchedTo = Long.MIN_VALUE;
            return time(s, at, file, m, events, time);
        }
        if (time + MAX_LEAD < s.boundary) {
            return time;
        }
        if (s.walk < 0 || time - s.walkAt >= REPLAN_TICKS || s.walkFrom == null || s.walkFrom.distSqr(at) > 36.0D) {
            s.walk = walkTicks(file, m, events, at, s.boundary);
            s.walkAt = time;
            s.walkFrom = at;
            if (SectTimings.ON) {
                io.github.verycooltimo.murim.MurimMod.LOGGER.info("Sect-lead: member={} now={} boundary={} walk={} from={}", m.key(),
                        Math.floorMod(time, (long) SectSchedule.DAY), Math.floorMod(s.boundary, (long) SectSchedule.DAY), s.walk, at.toShortString());
            }
        }
        if (s.walk <= 2 * MARGIN) {
            // He is at the place already, or a few steps from it: nothing to gain by setting off early.
            return time;
        }
        long depart = s.boundary + jitter(m, s.boundary) - JITTER / 2 - s.walk - travelMargin(s.walk);
        if (SectSchedule.task(m, SectPlaces.time(file, m, time), events).kind() == SectSchedule.Kind.EAT) {
            // A diner whose next place is far stands up before the end of the meal, but only after he has eaten for {@link #MIN_EAT}
            // ticks (docs/adr/0018): he is seen to finish and bow out ({@code ScheduleGoal} nods as he rises), not pulled from the bowl.
            SectSchedule.Period p = SectSchedule.at(time);
            long eatFrom = time - SectSchedule.sincePeriodStart(time) + (SectPlaces.secondWave(m) ? SectPlaces.mealLength(p) / 2 : 0);
            depart = Math.max(depart, eatFrom + MIN_EAT + jitter(m, eatFrom) / 2);
        }
        if (time >= depart) {
            if (SectTimings.ON) {
                io.github.verycooltimo.murim.MurimMod.LOGGER.info("Sect-lead: member={} sets off now={} boundary={} walk={}", m.key(),
                        Math.floorMod(time, (long) SectSchedule.DAY), Math.floorMod(s.boundary, (long) SectSchedule.DAY), s.walk);
            }
            s.latched = true;
            s.dueAt = s.boundary;
            return s.boundary;
        }
        return time;
    }

    /**
     * The speed factor of a walk: the task's own stroll, raised to {@link #TRANSIT} while he is more than {@link #TRIP_BLOCKS}
     * from his place, and by a hurry when he is behind.
     */
    public static double speed(SectDisciple npc, double base, double flatBlocks) {
        double route = npc.walking().remainingCells();
        return speed(npc, base, flatBlocks, route > flatBlocks ? route : 1.2D * flatBlocks);
    }

    /** As above with the cells of route that are left (the straight run is no measure of a way round a stair). */
    static double speed(SectDisciple npc, double base, double flatBlocks, double cellsLeft) {
        if (!ENABLED) {
            return base;
        }
        double v = flatBlocks > TRIP_BLOCKS ? Math.max(base, TRANSIT) : base;
        State s = npc.leadState();
        if (s.dueAt < 0L) {
            return v;
        }
        long left = s.dueAt - npc.level().getDayTime();
        if (left < -3 * SectSchedule.DAY / 24) {
            s.dueAt = -1L;      // long past: whatever kept him is not a late start any more
            return v;
        }
        // The walk that is left at this speed (1.2 cells to a block) against the time that is left: a person who would arrive late hurries
        // by exactly what he is short, up to HURRY; one who is late already walks as fast as he can.
        double need = cellsLeft * TICKS_PER_CELL_FULL / (v * SectStagger.factor(npc.memberKey(), "pace", 0.12D));
        if (left <= 0L) {
            return v * HURRY;
        }
        return need > left ? v * Math.min(HURRY, need / left) : v;
    }

    /** He has reached the place: nothing is due any more. */
    public static void arrived(SectDisciple npc) {
        npc.leadState().dueAt = -1L;
    }
}
