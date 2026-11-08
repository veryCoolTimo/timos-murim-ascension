package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.entity.SectDisciple;
import net.minecraft.server.level.ServerPlayer;

/**
 * The lesson is a sanctioned fight (playtest 19.10: «Когда первый урок с учителем начинается, нужно с ним на Шесть Гармоний
 * завязать. Я его бью, и мне пишут: вы нарушили закон секты, и меня отправляют в тюрьму»).
 *
 * <p>The mentor's first lesson sends the pupil to use the Six Elements on a partner, and the second one to land three clean
 * strikes on a senior. A blow on the instructor of an <b>open</b> lesson is practice, never an assault: it opens a safe bout
 * with him ({@link SectDisciple#hurt}) and is not counted by the penance code ({@code PenanceEnforcement#assault}), whatever
 * the pupil does around it. The sanction lasts as long as the lesson does (from the word at the mentor until the lesson is
 * handed in: the journal flags below) and covers the instructor himself and the partner of the lesson; the same holds for the
 * teacher of the strikes drill that follows a lost bout, for that day; after it, and for everyone else, a blow outside a bout
 * is what it always was.
 *
 * <p>The bout itself (a spar with the instructor) is sanctioned on its own for its whole length and a moment after the bows
 * ({@link SectDisciple#recentPartner}); this class adds the lesson around it.
 */
public final class SectLessonSanction {

    /** The first lesson was taken (the mentor's word); it is closed by {@link SectStanding#LESSON_ONE}. */
    public static final String SIX_TAKEN = "lesson.six";

    private SectLessonSanction() {
    }

    /**
     * Does the open lesson of this journal make the person in this role its instructor or partner? Pure, so a unit test
     * walks every lesson and every role.
     *
     * <ul>
     *   <li>first lesson (Six): the mentor;</li>
     *   <li>second lesson (three clean strikes): the mentor who set it and the partners (senior, second generation).</li>
     * </ul>
     */
    public static boolean teaches(SectState s, SectRole role) {
        if (!s.member()) {
            return false;
        }
        if (s.has(SIX_TAKEN) && !s.has(SectStanding.LESSON_ONE) && role == SectRole.MENTOR) {
            return true;
        }
        return s.has(SectService.LESSON_SPAR) && !s.has(SectStanding.LESSON_TWO)
                && (role == SectRole.MENTOR || role == SectRole.SENIOR || role == SectRole.SECOND);
    }

    /**
     * The strikes drill of a lost bout is open today ({@link SectDefeat}: «twenty hits on any target») and this person is the
     * teacher who set it: he stands next to the pupil to watch, and a hit on him is part of the drill, not a quarrel.
     */
    public static boolean drillTeaches(SectState s, long day, String memberKey) {
        if (!s.member() || s.entry(SectDefeat.DRILL, 0) != SectDefeat.Drill.STRIKES.ordinal() + 1) {
            return false;
        }
        return s.entry(SectDefeat.DAY, -1) == day && s.entry(SectDefeat.DONE, -1) != day && SectDefeat.teacher(s).equals(memberKey);
    }

    /** A blow of this player on this person is practice: the lesson's instructor, the drill's teacher or the partner of the bout just fought. */
    public static boolean covers(ServerPlayer p, SectDisciple npc) {
        SectState s = SectService.state(p);
        return npc.recentPartner(p) || teaches(s, npc.role()) || drillTeaches(s, SectProgression.day(p), npc.memberKey());
    }
}
