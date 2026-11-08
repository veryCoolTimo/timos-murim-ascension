package io.github.verycooltimo.murim.quest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlumRestorationTest {

    @Test
    @DisplayName("Fragments restore Rainfall only")
    void forms() {
        assertEquals(List.of(), PlumRestoration.restored(n -> false));
        for (int i = 0; i < PlumRestoration.FRAGMENTS; i++) {
            final int fragment = i;
            assertEquals(List.of(0), PlumRestoration.restored(n -> n == fragment));
        }
        assertEquals(List.of(0), PlumRestoration.restored(Set.of(1, 3, 5)::contains), "no order, no deduction");
        assertEquals(List.of(0), PlumRestoration.restored(Set.of(0, 1, 3, 5)::contains), "the four that can be found today");
        assertEquals(List.of(0), PlumRestoration.restored(Set.of(2, 4)::contains), "the stele pieces");
    }

    @Test
    @DisplayName("Fragments deepen Rainfall from two to five stars")
    void cap() {
        assertEquals(0, PlumRestoration.cap(n -> false));
        assertEquals(2, PlumRestoration.cap(n -> n == 1));
        assertEquals(5, PlumRestoration.cap(Set.of(0, 1, 3, 5)::contains));
        assertEquals(3, PlumRestoration.cap(Set.of(0, 1)::contains));
        assertEquals(4, PlumRestoration.cap(Set.of(0, 1, 3)::contains));
        assertEquals(5, PlumRestoration.cap(n -> true));
    }
}
