package io.github.verycooltimo.murim.training;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Route runs: start, order, falls back to the rest ledge, finish, void, abandon. */
class RouteRunTest {

    /** Six ledges climbing 4 per step, the third is a rest ledge. */
    private static List<RouteRun.Point> ledges() {
        List<RouteRun.Point> out = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            out.add(new RouteRun.Point(i * 10.0D, 100.0D + i * 4.0D, 0.0D, 3.0D, i == 2));
        }
        return out;
    }

    private static RouteRun climb() {
        return new RouteRun(ledges(), TrainingBalance.CLIMB_FALL, 40.0D, 2000);
    }

    @Test
    @DisplayName("Встал на первый уступ — пошёл отсчёт; уступы засчитываются только по порядку; вершина — время")
    void climbInOrder() {
        RouteRun r = climb();
        assertNull(r.step(30, 112, 0, 0, true, false), "standing on ledge 3 does not start the run");
        assertEquals(RouteRun.Kind.START, r.step(0, 100, 0, 10, true, false).kind());
        assertNull(r.step(20, 108, 0, 20, true, false), "skipping ledge 1 does not count");
        assertEquals(RouteRun.Kind.REACH, r.step(10, 104, 0, 30, true, false).kind());
        for (int i = 2; i < 5; i++) {
            assertEquals(RouteRun.Kind.REACH, r.step(i * 10, 100 + i * 4, 0, 30 + i * 10, true, false).kind());
        }
        RouteRun.Event finish = r.step(50, 120, 0, 110, true, false);
        assertEquals(RouteRun.Kind.FINISH, finish.kind());
        assertEquals(100, finish.index(), "elapsed ticks");
        assertFalse(r.active());
    }

    @Test
    @DisplayName("Сорвался — прогресс к последнему уступу отдыха, часы идут")
    void fallBackToRest() {
        RouteRun r = climb();
        r.step(0, 100, 0, 0, true, false);
        for (int i = 1; i <= 4; i++) {
            r.step(i * 10, 100 + i * 4, 0, i * 10, true, false);
        }
        assertEquals(5, r.next());
        RouteRun.Event fell = r.step(38, 110, 0, 60, true, false);
        assertEquals(RouteRun.Kind.FELL, fell.kind());
        assertEquals(2, fell.index(), "back to the rest ledge");
        assertEquals(3, r.next());
        assertTrue(r.active());
        assertNull(r.step(38, 105, 0, 61, true, false), "falling further below the rest ledge: no second event");
    }

    @Test
    @DisplayName("Ци, крылья, телепорт — забег не в счёт; ушёл с маршрута — брошен")
    void voidAndAbandon() {
        RouteRun r = climb();
        r.step(0, 100, 0, 0, true, false);
        assertEquals(RouteRun.Kind.VOID, r.step(5, 102, 0, 5, true, true).kind());
        assertFalse(r.active());
        RouteRun r2 = climb();
        r2.step(0, 100, 0, 0, true, false);
        assertEquals(RouteRun.Kind.ABANDON, r2.step(0, 100, 80, 5, true, false).kind());
        RouteRun r3 = new RouteRun(ledges(), 0.0D, 40.0D, 50);
        r3.step(0, 100, 0, 0, true, false);
        assertEquals(RouteRun.Kind.ABANDON, r3.step(2, 100, 0, 60, true, false).kind(), "timeout");
    }

    @Test
    @DisplayName("Тропа без срывов: падение ниже уступа не откатывает; без спринта не стартует")
    void trailHasNoFalls() {
        RouteRun r = new RouteRun(ledges(), 0.0D, 60.0D, 5000);
        assertNull(r.step(0, 100, 0, 0, false, false), "walking onto the start is not a sprint");
        assertEquals(RouteRun.Kind.START, r.step(0, 100, 0, 1, true, false).kind());
        r.step(10, 104, 0, 5, true, false);
        r.step(20, 108, 0, 9, true, false);
        assertNull(r.step(25, 90, 0, 10, true, false));
        assertEquals(3, r.next());
    }
}
