package io.github.verycooltimo.murim.sect;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SectSpeechGuardTest {
    @Test void repeatBudgetBelongsToEachSpeakerAndEachKey() {
        SectSpeechGuard a = new SectSpeechGuard();
        SectSpeechGuard b = new SectSpeechGuard();
        String key = "murim.bubble.carry.4";
        assertTrue(a.allow(key, 0));
        assertTrue(a.allow("murim.bubble.carry.5", 1));
        assertFalse(a.allow(key, 2399));
        assertTrue(b.allow(key, 2399));
        assertTrue(a.allow(key, 2400));
    }

    @Test void changingTheGreetingDoesNotEvadeTheMinuteBudget() {
        SectSpeechGuard guard = new SectSpeechGuard();
        assertTrue(guard.allow("murim.bubble.wander_greet.1", 0));
        assertFalse(guard.allow("murim.bubble.wander_recruit.2", 1199));
        assertTrue(guard.allow("murim.bubble.wander_recruit.2", 1200));
        assertFalse(guard.allow("murim.bubble.wander_greet.1", 1201));
    }

    @Test void praiseNeedsAFreshUnconsumedWitnessReceipt() {
        SectSpeechGuard guard = new SectSpeechGuard();
        String key = "murim.bubble.lesson.1";
        assertFalse(guard.allow(key, 0));
        guard.witness("lesson", 20);
        assertFalse(guard.allow(key, 19));
        assertTrue(guard.allow(key, 1220));
        assertFalse(guard.allow("murim.bubble.lesson.2", 1220));
        guard.witness("lesson", 1300);
        assertFalse(guard.allow("murim.bubble.lesson.2", 2501));
        assertFalse(new SectSpeechGuard().allow("murim.bubble.lesson.2", 1300));
    }

    @Test void oneEventCannotEarnBothAnAcknowledgementAndPraise() {
        SectSpeechGuard guard = new SectSpeechGuard();
        guard.witness("progress", 10, "player:realm_up:10");
        guard.witness("realm_up", 10, "player:realm_up:10");
        assertTrue(guard.allow("murim.bubble.lesson_ack.2", 20));
        assertFalse(guard.allow("murim.react.realm_up.peer.1", 21));
    }

    @Test void allPraiseFamiliesAreClosedWithoutADeed() {
        for (String key : java.util.List.of("murim.bubble.lesson.3", "murim.bubble.lesson_ack.3",
                "murim.bubble.bow_winner.1", "murim.bubble.social.acknowledge.1",
                "murim.bubble.council_review.1", "murim.react.bout_won.junior.1",
                "murim.bubble.contract.done.gyeong_cho.2", "murim.approach.bubble.congrats.1")) {
            assertFalse(SectSpeechGuard.deed(key).isEmpty(), key);
            assertFalse(new SectSpeechGuard().allow(key, 100), key);
        }
    }

    @Test void neutralOfficeGreetingsNeedNoDeedButShareTheGreetingBudget() {
        SectSpeechGuard guard = new SectSpeechGuard();
        assertTrue(guard.allow("murim.bubble.social.elder_peer.1", 0));
        assertFalse(guard.allow("murim.bubble.social.first_peer.1", 1199));
        assertTrue(guard.allow("murim.bubble.social.first_peer.1", 1200));
    }

    @Test void boutApproachAndReactionConsumeTheSameWitnessReceipt() {
        SectSpeechGuard guard = new SectSpeechGuard();
        assertFalse(guard.allow("murim.approach.bubble.congrats.1", 0));
        guard.witness("bout_won", 10);
        assertTrue(guard.allow("murim.approach.bubble.congrats.1", 20));
        assertFalse(guard.allow("murim.react.bout_won.peer.1", 21));
        SectSpeechGuard office = new SectSpeechGuard();
        office.witness("bout_won", 10);
        assertTrue(office.allow("murim.approach.bubble.office.congrats.1", 20));
        assertFalse(office.allow("murim.react.bout_won.peer.1", 21));
    }

    @Test void questRumoursLeaveRepeatTimingToTheirFiniteListenerSchedule() {
        SectSpeechGuard guard = new SectSpeechGuard();
        assertTrue(guard.allow("murim.bubble.quest.red_vein_rumour", 0));
        assertTrue(guard.allow("murim.bubble.quest.red_vein_rumour", 121));
        assertTrue(guard.allow("murim.bubble.quest.red_vein_rumour", 242));
    }

    @Test void removedKeysCannotBePickedOrSent() {
        assertEquals(50, SectSpeechGuard.RETIRED.size());
        assertEquals(java.util.List.of(), SectLines.keys("murim.bubble.wander_tip", 6));
        assertEquals(java.util.List.of("murim.bubble.guard_cover.1", "murim.bubble.guard_cover.5"),
                SectLines.keys("murim.bubble.guard_cover", 6));
        for (String key : SectSpeechGuard.RETIRED) assertFalse(new SectSpeechGuard().allow(key, 10000), key);
        for (int roll = -20; roll < 20; roll++) assertFalse(SectSpeechGuard.RETIRED.contains(SectBubbles.Group.WATCH.pick(roll)));
    }
}
