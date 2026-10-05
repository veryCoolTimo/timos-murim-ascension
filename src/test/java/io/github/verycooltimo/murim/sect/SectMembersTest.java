package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.sect.SectAttendance.Activity;
import io.github.verycooltimo.murim.sect.SectAttendance.Log;
import io.github.verycooltimo.murim.sect.SectSchedule.Kind;
import io.github.verycooltimo.murim.sect.SectSchedule.Period;
import io.github.verycooltimo.murim.sect.SectSchedule.Task;
import io.github.verycooltimo.murim.world.hua.MountHuaPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Члены секты за делом (автор 05.10): совет старейшин, доклады главе, смены охраны, дела старейшин и лекаря; журнал
 * секты игрока — перекличка, полный день, усердие, пропуски и наряд.
 */
class SectMembersTest {

    private static SectRoster m(String key) {
        return SectRoster.of(key).orElseThrow();
    }

    private static MountHuaPlan.Zone zone(String id) {
        return MountHuaPlan.ZONES.stream().filter(z -> z.id().equals(id)).findFirst().orElse(null);
    }

    private static void insideZone(Task t, String who) {
        MountHuaPlan.Zone z = zone(t.zone());
        assertNotNull(z, who + " → " + t.zone());
        assertTrue(Math.abs(t.du()) <= z.width() / 2.0D && Math.abs(t.dv()) <= z.depth() / 2.0D,
                who + ": " + t.kind() + " вне площадки " + t.zone() + " (" + t.du() + ", " + t.dv() + ")");
    }

    @Test
    @DisplayName("Совет: глава и четверо старейшин в главном зале в середине дня, места разные, лицом друг к другу")
    void council() {
        long noon = SectSchedule.COUNCIL_FROM + 300;
        Set<String> seats = new HashSet<>();
        for (String key : SectSchedule.COUNCIL) {
            Task t = SectSchedule.task(m(key), noon);
            assertEquals(Kind.COUNCIL, t.kind(), key);
            assertEquals("main_hall", t.zone());
            insideZone(t, key);
            assertTrue(seats.add(t.du() + "," + t.dv()), "место совета занято дважды: " + key);
        }
        // Ряды старейшин смотрят друг на друга, глава — к входу.
        assertEquals(-SectSchedule.task(m("hyun_young"), noon).faceU(), SectSchedule.task(m("hyun_sang"), noon).faceU(), 1e-9);
        // Вне совета — каждый за своим делом.
        assertEquals(Kind.RECEIVE, SectSchedule.task(m("hyun_jong"), 4000).kind());
        assertEquals(Kind.COUNT, SectSchedule.task(m("hyun_young"), 4000).kind());
        assertEquals(Kind.READ, SectSchedule.task(m("hyun_sang"), 5000).kind());
        assertEquals(Kind.LECTURE, SectSchedule.task(m("hyun_sang"), 3000).kind());
        assertEquals(Kind.BREW, SectSchedule.task(m("un_gak"), 3000).kind());
        assertEquals(Kind.GRIND, SectSchedule.task(m("un_gak"), 5000).kind());
        assertEquals(Kind.HEAL_POST, SectSchedule.task(m("un_gak"), 8000).kind());
        assertEquals(Kind.REVERE, SectSchedule.task(m("hyun_jong"), 12000).kind());
    }

    @Test
    @DisplayName("Доклады главе: наставник после строя, Ун Ам в завтрак, Хён Ён с книгой — в шаге перед главой, лицом к нему")
    void reports() {
        for (Object[] r : new Object[][] {{"un_geom", 800L}, {"un_am", 1500L}, {"hyun_young", 2600L}}) {
            long time = (Long) r[1];
            Task rep = SectSchedule.task(m((String) r[0]), time);
            Task leader = SectSchedule.task(m("hyun_jong"), time);
            assertEquals(Kind.REPORT, rep.kind(), (String) r[0]);
            assertEquals(SectSchedule.LEADER, rep.partner());
            assertEquals(leader.zone(), rep.zone());
            double d = Math.hypot(rep.du() - leader.du(), rep.dv() - leader.dv());
            assertTrue(d > 1.0D && d < 2.5D, r[0] + ": далеко от главы " + d);
            // Докладчик лицом к главе, глава — к докладчику.
            assertEquals(-leader.faceU(), rep.faceU(), 1e-9);
            assertEquals(-leader.faceV(), rep.faceV(), 1e-9);
            insideZone(rep, (String) r[0]);
        }
    }

    @Test
    @DisplayName("Охрана: каждый пост занят в любую часть суток; дневная смена ночью спит, ночная днём спит, потом отдыхает")
    void guardShifts() {
        assertEquals(6, SectRoster.DAY_WATCH.size());
        assertEquals(SectRoster.DAY_WATCH.size(), SectRoster.NIGHT_WATCH.size());
        for (Period p : Period.values()) {
            for (int post = 0; post < SectRoster.DAY_WATCH.size(); post++) {
                SectRoster day = m(SectRoster.DAY_WATCH.get(post));
                SectRoster night = m(SectRoster.NIGHT_WATCH.get(post));
                assertEquals(day.home(), night.home(), "пост " + post);
                assertEquals(night, day.relief().orElseThrow());
                assertEquals(day, night.relief().orElseThrow());
                Task a = SectSchedule.task(day, p, 0);
                Task b = SectSchedule.task(night, p, 0);
                assertTrue(a.kind() == Kind.GUARD ^ b.kind() == Kind.GUARD, "пост " + post + " в " + p + ": " + a.kind() + "/" + b.kind());
                Task on = a.kind() == Kind.GUARD ? a : b;
                assertEquals(SectSchedule.post(day), on, "сменщик стоит не на том же месте");
                Task off = a.kind() == Kind.GUARD ? b : a;
                insideZone(off, (a.kind() == Kind.GUARD ? night : day).key());
            }
        }
        assertEquals(Kind.SLEEP, SectSchedule.task(m("baek_un"), 3000).kind(), "ночная смена спит до полудня");
        assertEquals(Kind.REST, SectSchedule.task(m("baek_un"), 8000).kind());
        assertEquals(Kind.SLEEP, SectSchedule.task(m("baek_mu"), 15000).kind());
    }

    // ------------------------------------------------------------------ журнал секты

    private static Log mark(Log l, long day, Activity a, boolean expect) {
        return SectAttendance.mark(l, day, a, expect);
    }

    @Test
    @DisplayName("Журнал: полный день — заслуги и усердие; три дня подряд — прибавка; дни вне горы ничего не меняют")
    void fullDaysAndStreak() {
        Log l = Log.NONE;
        int bonus = 0;
        for (long day = 10; day < 13; day++) {
            SectAttendance.Settled s = SectAttendance.roll(l, day);
            bonus += s.bonus();
            l = s.log();
            l = mark(l, day, Activity.FORMATION, true);
            l = mark(l, day, Activity.LESSON, true);
            l = mark(l, day, Activity.FORMATION, false);
            l = mark(l, day, Activity.LESSON, false);
            l = mark(l, day, Activity.MEAL, false);
        }
        SectAttendance.Settled s = SectAttendance.roll(l, 13);
        bonus += s.bonus();
        assertTrue(s.full());
        assertEquals(3, s.log().streak());
        // 1 + 1 + (1 + 1 за третий день подряд)
        assertEquals(SectAttendance.DAY_BONUS * 3 + 1, bonus);
        assertTrue(s.log().last().did(Activity.MEAL));
        // Ушёл в поход: пустые дни не сбрасывают усердие и не считаются пропуском.
        Log away = SectAttendance.roll(SectAttendance.roll(s.log(), 14).log(), 15).log();
        assertEquals(3, away.streak());
        assertEquals(0, away.missedRow());
        assertFalse(away.chores());
    }

    @Test
    @DisplayName("Журнал: два пропущенных строя подряд — наряд; пока наряд, полный день без заслуг; отработал — снят")
    void missedFormationsGiveChores() {
        Log l = Log.NONE;
        for (long day = 1; day <= 2; day++) {
            l = SectAttendance.roll(l, day).log();
            l = mark(l, day, Activity.FORMATION, true);
            l = mark(l, day, Activity.LESSON, true);
            l = mark(l, day, Activity.LESSON, false);
            assertTrue(l.today().missed(Activity.FORMATION));
        }
        SectAttendance.Settled s = SectAttendance.roll(l, 3);
        assertTrue(s.choresGiven(), "наряд не назначен");
        assertTrue(s.log().chores());
        assertEquals(0, s.log().streak());
        l = s.log();
        l = mark(l, 3, Activity.FORMATION, true);
        l = mark(l, 3, Activity.FORMATION, false);
        SectAttendance.Settled withChores = SectAttendance.roll(l, 4);
        assertTrue(withChores.full());
        assertEquals(0, withChores.bonus(), "наряд не отработан, а заслуги за день даны");
        l = withChores.log().choresDone();
        assertFalse(l.chores());
        assertEquals(0, l.missedRow());
    }

    @Test
    @DisplayName("Журнал: перекличка — без неё занятие не пропуск; сделанное без переклички засчитано")
    void rollCall() {
        Log l = mark(Log.NONE, 5, Activity.MEAL, false);
        assertFalse(l.today().missed(Activity.FORMATION));
        assertTrue(l.today().present());
        assertFalse(l.today().full(), "день без ожиданий не «полный»");
        l = mark(l, 5, Activity.FORMATION, true);
        assertTrue(l.today().missed(Activity.FORMATION));
        l = mark(l, 5, Activity.FORMATION, false);
        assertFalse(l.today().missed(Activity.FORMATION));
        assertTrue(l.today().full());
        assertEquals(Activity.LESSON, Activity.of("lesson").orElseThrow());
        assertTrue(Activity.of("pushups").isEmpty());
    }
}
