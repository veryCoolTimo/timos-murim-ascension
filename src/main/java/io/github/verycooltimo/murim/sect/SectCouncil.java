package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.sect.SectEventsFile.Council;

/**
 * The clock of the council of elders (docs/design/38-sect-ai-locations.md, «Совет и приглашения»): a few days a week the head
 * gathers the elders in the council hall on the second storey of the Main Hall. Pure functions of the day time, so the whole
 * meeting can be checked in a unit test; the walking, the sitting and the tea are done by {@code ScheduleGoal}, the places by
 * {@link SectPlaces}, the elder player's invitation by {@link SectOffices}.
 */
public final class SectCouncil {

    /** Where the meeting is: the members walk (GATHER), sit and talk (SESSION), stand up and bow (LEAVE). */
    public enum Phase {
        NONE, GATHER, SESSION, LEAVE;

        /** The members are on their way or at the table. */
        public boolean on() {
            return this != NONE;
        }

        /** The table is full of people: they have sat down and have not yet stood up. */
        public boolean seated() {
            return this == SESSION;
        }
    }

    private SectCouncil() {
    }

    public static Council config() {
        return SectEventsFile.shipped().council();
    }

    public static Phase phase(long dayTime) {
        return phase(config(), dayTime);
    }

    public static Phase phase(Council c, long dayTime) {
        if (!c.onDay(SectSchedule.day(dayTime))) {
            return Phase.NONE;
        }
        int t = tick(dayTime);
        if (t < c.gather() || t >= c.to()) {
            return Phase.NONE;
        }
        return t < c.from() ? Phase.GATHER : t < c.leave() ? Phase.SESSION : Phase.LEAVE;
    }

    /**
     * This person is bound for the council (or at it): the council meets today and he has set out, which for each is a different
     * moment (the way from the treasury is longer than from the head's own hall), up to the end of the meeting.
     */
    public static boolean goes(String key, long dayTime) {
        return goes(config(), key, dayTime);
    }

    public static boolean goes(Council c, String key, long dayTime) {
        if (stays(key) || !c.onDay(SectSchedule.day(dayTime))) {
            return false;
        }
        int t = tick(dayTime);
        return t >= c.from() - c.leadOf(key) && t < c.to();
    }

    /**
     * The elder who keeps his post while the council sits (author 21.10): the gate elder, giver of the camp contracts, stays at
     * his gate and the board, so contracts can still be taken and handed in. His seat at the table stays empty.
     */
    public static final String POST = "gyeong_tak";

    public static boolean stays(String key) {
        return POST.equals(key);
    }

    /** Tick of the day. */
    public static int tick(long dayTime) {
        return (int) Math.floorMod(dayTime, (long) SectSchedule.DAY);
    }

    /** The council is meeting today and an elder may be called to it: from the call to the end. */
    public static boolean called(long dayTime) {
        Council c = config();
        int t = tick(dayTime);
        return c.onDay(SectSchedule.day(dayTime)) && t >= c.call() && t < c.to();
    }

    /** The tray round of the servant has begun and the meeting still sits. */
    public static boolean teaServed(long dayTime) {
        Council c = config();
        return phase(c, dayTime).on() && tick(dayTime) >= c.tea() && phase(c, dayTime) != Phase.GATHER;
    }

    /** Place of the member at the table: 0 is the head, 1.. the elders in the order of the file; -1 for everybody else. */
    public static int seat(String key) {
        return config().members().indexOf(key);
    }

    public static boolean member(String key) {
        return seat(key) >= 0;
    }

    /** The servant who brings the tea. */
    public static boolean servant(String key) {
        return config().servant().equals(key);
    }

    /** Ticks since the members sat down (negative before): drives the order of the speakers and of the tea. */
    public static int sinceSat(long dayTime) {
        return tick(dayTime) - config().from();
    }
}
