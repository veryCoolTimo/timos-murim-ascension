package io.github.verycooltimo.murim.sect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Social rank: the office if held, otherwise the standing (docs/design/39 «Обращение по положению»). */
class SectRankTest {

    @Test
    void promotionPartnersAreUnionedByMember() {
        var s = SectState.NONE.joined().with("rank2.win.a").with("rank2.win.b")
                .with("mentor.partner.b").with("mentor.partner.c");
        assertEquals(3, SectProgression.partners(s));
        assertEquals(2, SectProgression.secondWins(s));
        var ready = s.with(SectStanding.LESSON_TWO).with(SectRequests.flag("training", "done"));
        assertTrue(SectProgression.directEligible(ready));
        assertFalse(SectProgression.directEligible(ready.without("mentor.partner.c")));
    }

    @Test
    @DisplayName("Должность важнее положения: старейшина с положением новичка всё равно старейшина")
    void officeBeatsStanding() {
        SectState novice = SectState.NONE.joined();
        assertEquals(SectRank.NOVICE, SectRank.of(novice, 0));
        assertEquals(SectRank.FIRST, SectRank.of(novice.with(SectPositions.FIRST), 0));
        // `/murim dev sect position elder` sets both flags, and the standing stays what it was
        SectState elder = novice.with(SectPositions.FIRST).with(SectPositions.ELDER);
        assertEquals(SectRank.ELDER, SectRank.of(elder, 0));
        assertEquals(SectStanding.NOVICE, SectStanding.of(elder, 0));
        assertTrue(SectRank.of(elder, 0).office());
        assertFalse(SectRank.of(novice, 4).office());
        assertEquals(SectRank.OUTSIDER, SectRank.of(SectState.NONE, 4));
    }

    @Test
    @DisplayName("Без должности ранг равен ступени положения; должность выше любой ступени")
    void ladderThenOffices() {
        for (SectStanding s : SectStanding.values()) {
            assertEquals(s.id(), SectRank.ofStanding(s).id());
            assertEquals(s, SectRank.ofStanding(s).standing());
        }
        assertTrue(SectRank.FIRST.atLeast(SectRank.TRUSTED) && SectRank.ELDER.atLeast(SectRank.FIRST));
        assertFalse(SectRank.TRUSTED.atLeast(SectRank.FIRST));
        assertEquals(SectRank.ELDER, SectRank.parse("elder").orElseThrow());
        assertTrue(SectRank.parse("newcomer").isEmpty());
        assertEquals(SectStanding.TRUSTED, SectRank.ELDER.standing());
    }

    @Test
    @DisplayName("Возвращение после отсутствия: старейшину и первого ученика не спрашивают «где ты пропадал, ученик»")
    void absenceLineByOffice() {
        assertEquals("murim.sect.absence.3.1", SectAbsence.key(SectRank.TRUSTED, 3, 0));
        assertEquals("murim.sect.absence.elder.7.2", SectAbsence.key(SectRank.ELDER, 7, 1));
        assertEquals("murim.sect.absence.first.1.3", SectAbsence.key(SectRank.FIRST, 1, 2));
    }
}
