package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.sect.SectSchedule.Kind;
import io.github.verycooltimo.murim.sect.SectSchedule.Period;
import io.github.verycooltimo.murim.sect.SectSchedule.Task;
import io.github.verycooltimo.murim.world.hua.MountHuaPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Распорядок секты (план §4.2): части суток, люди, строй, пары поединков, места. */
class SectScheduleTest {

    @Test
    @DisplayName("Части суток по времени мира: рассвет — строй, днём занятия, ночью сон")
    void periods() {
        assertEquals(Period.FORMATION, SectSchedule.at(23000));
        assertEquals(Period.FORMATION, SectSchedule.at(0));
        assertEquals(Period.FORMATION, SectSchedule.at(999));
        assertEquals(Period.BREAKFAST, SectSchedule.at(1000));
        assertEquals(Period.TRAINING, SectSchedule.at(2000));
        assertEquals(Period.TRAINING, SectSchedule.at(6000));
        assertEquals(Period.DINNER, SectSchedule.at(9000));
        assertEquals(Period.EVENING, SectSchedule.at(11000));
        assertEquals(Period.NIGHT, SectSchedule.at(13000));
        assertEquals(Period.NIGHT, SectSchedule.at(22999));
        // Время мира растёт без конца: сутки по модулю.
        assertEquals(Period.TRAINING, SectSchedule.at(24000L * 50 + 3000));
        assertEquals(0, SectSchedule.sincePeriodStart(23000));
        assertEquals(1500, SectSchedule.sincePeriodStart(500));
        // Строй в 23000 открывает следующий день секты.
        assertEquals(SectSchedule.day(24000), SectSchedule.day(23000));
        assertEquals(SectSchedule.day(24000), SectSchedule.day(30000));
    }

    @Test
    @DisplayName("Состав 05.10: глава, 3 старейшины Хён, 3 Ун, 8 Пэк, 17 Чхон, 6 слуг, управляющий, привратник — 40; без стражи и Чхон Мёна")
    void roster() {
        assertEquals(8, SectRoster.generation(2).size());
        assertEquals(17, SectRoster.generation(3).size());
        assertEquals(25, SectRoster.ALL.stream().filter(SectRoster::disciple).count());
        assertEquals(40, SectRoster.ALL.size());
        assertEquals(4, SectRoster.ALL.stream().filter(m -> m.generation() == 0).count(), "глава и три старейшины Хён");
        assertEquals(3, SectRoster.ALL.stream().filter(m -> m.generation() == 1).count(), "три Ун");
        assertEquals(6, SectRoster.ALL.stream().filter(m -> m.lay() && m.role() != SectRole.STEWARD).count(), "шесть слуг");
        assertEquals(1, SectRoster.ALL.stream().filter(m -> m.role() == SectRole.STEWARD).count());
        assertEquals(1, SectRoster.ALL.stream().filter(m -> m.role() == SectRole.GATEKEEPER).count());
        assertEquals(0, SectRoster.ALL.stream().filter(m -> m.role() == SectRole.GUARD).count(), "отдельной стражи нет");
        assertTrue(SectRoster.of("chung_myung").isEmpty() && SectRoster.of("cheong_myeong").isEmpty(), "Чхон Мён — это игрок");
        assertEquals(SectRole.SENIOR, SectRoster.of("baek_cheon").orElseThrow().role(), "старший — Пэк Чхон");
        for (String gone : SectRoster.RETIRED) {
            assertTrue(SectRoster.of(gone).isEmpty(), "ушедший в составе: " + gone);
        }
        Set<String> keys = new HashSet<>();
        for (SectRoster m : SectRoster.ALL) {
            assertTrue(keys.add(m.key()), "дубль " + m.key());
        }
        assertEquals(1, SectRoster.ALL.stream().filter(m -> m.role() == SectRole.LEADER).count());
        assertEquals(1, SectRoster.ALL.stream().filter(m -> m.role() == SectRole.MENTOR).count());
        assertTrue(SectRoster.ALL.stream().filter(m -> m.role() == SectRole.ELDER).count() >= 3);
        // Старые NPC первой версии становятся этими людьми, а не дублями.
        for (SectRole r : List.of(SectRole.LEADER, SectRole.MENTOR, SectRole.SENIOR, SectRole.GATEKEEPER, SectRole.DISCIPLE_A, SectRole.DISCIPLE_B)) {
            assertTrue(SectRoster.legacy(r).isPresent(), r.id());
        }
    }

    @Test
    @DisplayName("Строй: все ученики в рядах, места не совпадают, в каждом ряду свободное место для игрока")
    void formationSlots() {
        Set<String> used = new HashSet<>();
        for (long day = 0; day < 7; day++) {
            Set<String> today = new HashSet<>();
            int rowed = 0;
            for (SectRoster m : SectRoster.ALL) {
                if (!m.disciple()) {
                    continue;
                }
                // Строй дня: 23500 предыдущих суток открывают день секты {@code day}.
                Task t = SectSchedule.task(m, day * 24000L - 500L);
                if (m.generation() == 3) {
                    assertEquals(Kind.FORM_ROW, t.kind(), m.key());
                }
                if (t.kind() != Kind.FORM_ROW) {
                    // Второе поколение: дежурный на посту или отсыпается после ночи.
                    assertTrue(SectRota.onDuty(m, day * 24000L - 500L) || SectRota.afterNight(m, day * 24000L - 500L), m.key());
                    continue;
                }
                rowed++;
                double[] s = SectSchedule.formationSlot(m, day);
                assertNotNull(s, m.key());
                assertTrue(today.add(s[0] + "," + s[1]), "место занято дважды: " + m.key());
                assertEquals("training", t.zone());
            }
            assertEquals(17 + 4, rowed, "день " + day + ": в строю всё третье поколение и четверо второго");
            used.addAll(today);
        }
        for (int row = 0; row < SectSchedule.rows(); row++) {
            double[] free = SectSchedule.slot(row, SectSchedule.COLUMNS - 1);
            assertFalse(used.contains(free[0] + "," + free[1]), "ряд " + row + " без места для игрока");
        }
        // Строй помещается на площади тренировок (48 × 32) с запасом.
        MountHuaPlan.Zone training = zone("training");
        for (String u : used) {
            String[] p = u.split(",");
            assertTrue(Math.abs(Double.parseDouble(p[0])) < training.width() / 2.0D - 2.0D);
            assertTrue(Math.abs(Double.parseDouble(p[1])) < training.depth() / 2.0D - 2.0D);
        }
        // Наставник ходит вдоль рядов, глава смотрит.
        SectRoster mentor = SectRoster.of("un_geom").orElseThrow();
        assertEquals(Kind.INSPECT, SectSchedule.task(mentor, Period.FORMATION, 0).kind());
        assertEquals(Kind.WATCH, SectSchedule.task(SectRoster.of("hyun_jong").orElseThrow(), Period.FORMATION, 0).kind());
    }

    @Test
    @DisplayName("Днём пары поединков взаимны, в разных рингах; группы меняются по дням")
    void sparPairs() {
        for (long day = 0; day < 20; day++) {
            Map<String, Task> spar = new HashMap<>();
            Map<String, Kind> kinds = new HashMap<>();
            for (SectRoster m : SectRoster.ALL) {
                Task t = SectSchedule.task(m, Period.TRAINING, day);
                kinds.put(m.key(), t.kind());
                if (t.kind() == Kind.SPAR) {
                    spar.put(m.key(), t);
                }
            }
            Set<String> spots = new HashSet<>();
            for (Map.Entry<String, Task> e : spar.entrySet()) {
                Task t = e.getValue();
                Task other = spar.get(t.partner());
                assertNotNull(other, "день " + day + ": у " + e.getKey() + " партнёр " + t.partner() + " не на поединке");
                assertEquals(e.getKey(), other.partner(), "день " + day + ": пара не взаимна");
                assertTrue(spots.add(t.du() + "," + t.dv()), "день " + day + ": место в ринге занято");
                // Лицом друг к другу.
                assertEquals(-t.faceU(), other.faceU(), 1e-9);
            }
            assertEquals(10, spar.size(), "день " + day + ": пять пар");
            assertTrue(kinds.containsValue(Kind.POLES));
            assertTrue(kinds.containsValue(Kind.CHORE));
            assertEquals(Kind.SPAR, kinds.get("baek_cheon"), "старший всегда на площадке поединков");
        }
        // Состав групп третьего поколения меняется от дня к дню.
        SectRoster m = SectRoster.of("jo_gol").orElseThrow();
        Set<Kind> seen = new HashSet<>();
        for (long day = 0; day < 15; day++) {
            seen.add(SectSchedule.task(m, Period.TRAINING, day).kind());
        }
        assertTrue(seen.size() >= 3, "у ученика одно занятие на всю жизнь: " + seen);
    }

    @Test
    @DisplayName("Трапеза, вечер, сон: места не совпадают и лежат в пределах своих площадок")
    void seatsInsideZones() {
        for (Period p : List.of(Period.BREAKFAST, Period.EVENING, Period.NIGHT, Period.TRAINING)) {
            Set<String> spots = new HashSet<>();
            for (SectRoster m : SectRoster.ALL) {
                Task t = SectSchedule.task(m, p, 0);
                MountHuaPlan.Zone z = zone(t.zone());
                assertNotNull(z, m.key() + " → " + t.zone());
                if (t.kind() == Kind.CHORE || t.kind() == Kind.WORK) {
                    continue;
                }
                assertTrue(Math.abs(t.du()) <= z.width() / 2.0D, p + " " + m.key() + " du " + t.du());
                assertTrue(Math.abs(t.dv()) <= z.depth() / 2.0D, p + " " + m.key() + " dv " + t.dv());
                assertTrue(spots.add(t.zone() + t.du() + "," + t.dv()), p + ": место занято дважды — " + m.key());
            }
        }
    }

    @Test
    @DisplayName("Такт строя: одна форма за такт, удар игрока в окне около удара строя")
    void beat() {
        assertTrue(SectSchedule.beatStarts(SectSchedule.BEAT * 7L));
        assertTrue(SectSchedule.onBeat(SectSchedule.BEAT * 7L + SectSchedule.BEAT_STRIKE));
        assertFalse(SectSchedule.onBeat(SectSchedule.BEAT * 7L + SectSchedule.BEAT - 2));
        assertEquals(SectSchedule.beatForm(0), SectSchedule.beatForm(SectSchedule.BEAT - 1));
        assertEquals((SectSchedule.beatForm(0) + 1) % 6, SectSchedule.beatForm(SectSchedule.BEAT));
    }

    private static MountHuaPlan.Zone zone(String id) {
        for (MountHuaPlan.Zone z : MountHuaPlan.ZONES) {
            if (z.id().equals(id)) {
                return z;
            }
        }
        return null;
    }
}
