package io.github.verycooltimo.murim.library;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The archive plan and the 1000-archive model of a first visit (docs/design/25-ruined-library.md §3, §5). */
class LibraryPlanTest {

    @Test
    @DisplayName("План детерминирован; подпёртый стеллаж — 12..20 (чаще 19), записка смотрителя называет его номер")
    void plan() {
        int nineteen = 0;
        for (long seed = 0; seed < 1000; seed++) {
            LibraryPlan a = LibraryPlan.plan(seed);
            LibraryPlan b = LibraryPlan.plan(seed);
            assertEquals(a.books(), b.books());
            assertEquals(a.proppedNumber(), b.proppedNumber());
            int n = a.proppedNumber();
            assertTrue(n >= JunkText.FIRST_ORDINAL && n <= 20, "propped " + n);
            nineteen += n == 19 ? 1 : 0;
            assertEquals(n, a.deskClue().hint());
            assertEquals(JunkKind.CLUE, a.deskClue().kind());
            int[] c = a.proppedCell();
            int side = a.proppedSide();
            int p = side == 0 || side == 2 ? c[1] : c[0];
            assertEquals(LibraryPlan.Cell.PROPPED, a.cell(0, side, p, 0), "seed " + seed);
            // Exactly one propped cell in the archive.
            int propped = 0;
            for (int t = 0; t < 4; t++) {
                for (int s = 0; s < 4; s++) {
                    for (int q = 1; q <= LibraryPlan.WALL_HI; q++) {
                        for (int row = 0; row < 3; row++) {
                            propped += a.cell(t, s, q, row) == LibraryPlan.Cell.PROPPED ? 1 : 0;
                        }
                    }
                }
            }
            assertEquals(1, propped, "seed " + seed);
            // Shelf notes on the shelves name the same shelf.
            for (LibraryPlan.Shelf s : a.shelves()) {
                for (LibraryPlan.Slot slot : s.slots()) {
                    if (slot != null && slot.junk() != null && slot.junk().kind() == JunkKind.CLUE) {
                        assertEquals(n, slot.junk().hint());
                    }
                }
            }
        }
        assertTrue(nineteen > 300 && nineteen < 550, "19 should be the most common number: " + nineteen);
    }

    @Test
    @DisplayName("Порядок счёта стеллажей: 24 разных номера на ярус, первый — у подножия нижней лестницы")
    void ring() {
        Set<Integer> seen = new HashSet<>();
        for (int side = 0; side < 4; side++) {
            for (int u = 0; u < LibraryPlan.UNITS.length; u++) {
                seen.add(LibraryPlan.ringUnit(side, u));
            }
        }
        assertEquals(24, seen.size());
        assertTrue(seen.contains(1) && seen.contains(24));
        int[] first = LibraryPlan.unitCentre(0, 1);
        assertEquals(LibraryPlan.WALL_LO, first[0]);
        // The bottom stairs land at z = STAIR_WEST_Z0 + 4; the first counted unit starts just north of it.
        assertTrue(first[1] > LibraryPlan.STAIR_WEST_Z0 + 4 && first[1] <= LibraryPlan.STAIR_WEST_Z0 + 8, "unit 1 at z " + first[1]);
    }

    @Test
    @DisplayName("1000 архивов: полки почти сплошь мусор, но находка гарантирована и обычно приходит за минуты")
    void simulation() {
        LibrarySim.Summary s = LibrarySim.run(20261004L, 1000, 0.04D);
        System.out.println("[library-sim] " + s.describe());
        assertTrue(s.booksMean() > 150, "too few volumes: " + s.booksMean());
        assertTrue(s.shelfGenuineMean() < 2.0D, "shelves are no longer 98 % junk: " + s.shelfGenuineMean());
        assertEquals(0.0D, s.sourceShare()[LibrarySim.Source.NONE.ordinal()], 1e-9, "an archive without a find");
        assertTrue(s.p50() < 300.0D, "median time to the first useful find " + s.p50());
        assertTrue(s.p90() < 900.0D, "p90 time to the first useful find " + s.p90());
    }
}
