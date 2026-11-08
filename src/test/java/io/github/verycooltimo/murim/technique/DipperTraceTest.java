package io.github.verycooltimo.murim.technique;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DipperTraceTest {
    @Test void sevenPointsFollowHandleIntoBowlAndCloseOnlyAtSeventhStop() {
        assertEquals(List.of(new DipperTrace.Point(-2, -1.5), new DipperTrace.Point(-1.4, -.8),
                new DipperTrace.Point(-.7, -1), new DipperTrace.Point(.4, -1.2), new DipperTrace.Point(1.7, -.8),
                new DipperTrace.Point(1.5, 1.2), new DipperTrace.Point(.2, 1.3)), DipperTrace.POINTS);
        assertEquals(7, DipperTrace.POINTS.stream().distinct().count());
        for (int reached = 1; reached < 7; reached++) {
            assertEquals(reached - 1, DipperTrace.edges(reached).size());
            for (int i = 0; i < reached - 1; i++) assertEquals(new DipperTrace.Edge(i, i + 1), DipperTrace.edges(reached).get(i));
        }
        assertEquals(List.of(new DipperTrace.Edge(0, 1), new DipperTrace.Edge(1, 2), new DipperTrace.Edge(2, 3),
                new DipperTrace.Edge(3, 4), new DipperTrace.Edge(4, 5), new DipperTrace.Edge(5, 6),
                new DipperTrace.Edge(6, 3)), DipperTrace.edges(7));
    }
    @Test void entireFigureWaitsForLastCutThenFadesForOneAndAHalfSeconds() {
        assertEquals(.7F, DipperTrace.alpha(0, 26), .0001);
        assertEquals(.7F, DipperTrace.alpha(26, 26), .0001);
        assertEquals(.35F, DipperTrace.alpha(41, 26), .0001);
        assertTrue(DipperTrace.alpha(55.9, 26) > 0);
        assertEquals(0, DipperTrace.alpha(56, 26));
        assertEquals(0, DipperTrace.alpha(100, 26));
    }
}
