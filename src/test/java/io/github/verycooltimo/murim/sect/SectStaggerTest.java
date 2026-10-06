package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.sect.SectSchedule.Period;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Разница во времени между людьми горы (автор 06.10). */
class SectStaggerTest {

    @Test
    @DisplayName("Конец ужина: ученики и слуги встают из-за стола в разное время, в пределах 5..40 с")
    void dinnerEndsStaggered() {
        long end = Period.EVENING.start();
        Set<Integer> lags = new HashSet<>();
        int staggered = 0;
        for (SectRoster m : SectRoster.ALL) {
            int lag = SectStagger.lag(m);
            if (!SectStagger.staggered(m)) {
                assertEquals(0, lag, m.key());
                assertEquals(end, SectStagger.scheduleTime(m, end));
                continue;
            }
            staggered++;
            assertTrue(lag >= SectStagger.LAG_MIN && lag <= SectStagger.LAG_MAX, m.key() + " " + lag);
            lags.add(lag / 20);
            // Сразу после колокола ещё ужинает, после своего опоздания — уже вечер.
            assertEquals(Period.DINNER, SectSchedule.at(SectStagger.scheduleTime(m, end + 1)));
            assertEquals(Period.EVENING, SectSchedule.at(SectStagger.scheduleTime(m, end + lag)));
        }
        assertTrue(staggered >= 10, "толпа сдвигается: " + staggered);
        // Не все в одну секунду: опоздания разные почти у всех.
        assertTrue(lags.size() >= staggered * 2 / 3, "разных секунд " + lags.size() + " из " + staggered);
    }

    @Test
    @DisplayName("Внутри части суток время распорядка — время мира; глава и старейшины — по колоколу")
    void insidePeriodNoLag() {
        for (SectRoster m : SectRoster.ALL) {
            long mid = Period.TRAINING.start() + 3000L;
            assertEquals(mid, SectStagger.scheduleTime(m, mid), m.key());
        }
        SectRoster leader = SectRoster.of(SectSchedule.LEADER).orElseThrow();
        assertEquals(0, SectStagger.lag(leader));
    }

    @Test
    @DisplayName("Темп шага — в пределах ±12 %, у одного и того же человека всегда один")
    void pace() {
        for (SectRoster m : SectRoster.ALL) {
            double f = SectStagger.factor(m.key(), "pace", 0.12D);
            assertTrue(f >= 0.88D && f <= 1.12D);
            assertEquals(f, SectStagger.factor(m.key(), "pace", 0.12D));
        }
    }
}
