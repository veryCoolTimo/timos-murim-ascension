package io.github.verycooltimo.murim.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SelectionHistoryTest {
    private record Pair(int slot, String form) {}
    private final Pair a = new Pair(0, "strike");
    private final Pair b = new Pair(0, "chain");

    @Test void togglesFormsAndSlotsAndIgnoresEchoes() {
        var history = new SelectionHistory<Pair>();
        history.observe(a);
        history.observe(b);
        history.observe(b);
        assertEquals(a, history.previous(p -> true).orElseThrow());
        history.observe(history.previous(p -> true).orElseThrow());
        assertEquals(b, history.previous(p -> true).orElseThrow());
        var otherArt = new Pair(2, "palm");
        history.observe(otherArt);
        assertEquals(a, history.previous(p -> true).orElseThrow());
        history.observe(a);
        assertEquals(otherArt, history.previous(p -> true).orElseThrow());
    }

    @Test void invalidPreviousDoesNothingAndResetDropsHistory() {
        var history = new SelectionHistory<Pair>();
        history.observe(a);
        history.observe(b);
        assertTrue(history.previous(p -> false).isEmpty());
        assertEquals(a, history.previous(p -> true).orElseThrow());
        history.clear();
        assertTrue(history.previous(p -> true).isEmpty());
    }

    @Test void shortTapOnlyOnce() {
        var press = new WheelPress();
        assertFalse(press.hold());
        assertTrue(press.release());
        assertFalse(press.release());
    }

    @Test void subTickTapTogglesHistoryOnce() {
        var history = new SelectionHistory<Pair>();
        history.observe(a);
        history.observe(b);
        var press = new WheelPress();
        press.click(); // V pressed and released before any hold tick.
        if (press.release()) history.observe(history.previous(p -> true).orElseThrow());
        assertFalse(press.release()); // The second finishSelectionIfReleased in this tick.
        assertEquals(b, history.previous(p -> true).orElseThrow());
    }

    @Test void subTickChordDoesNotToggleHistory() {
        var history = new SelectionHistory<Pair>();
        history.observe(a);
        history.observe(b);
        var press = new WheelPress();
        press.click();
        press.cancel(); // R is drained before the wheel can finish the sub-tick release.
        if (press.release()) history.observe(history.previous(p -> true).orElseThrow());
        assertFalse(press.release());
        assertEquals(a, history.previous(p -> true).orElseThrow());
        press.click(); // Cancellation must not swallow the next gesture.
        assertTrue(press.release());
    }

    @Test void clickedHoldStillRequiresFourTicksAndNeverTaps() {
        var press = new WheelPress();
        press.click();
        for (int i = 0; i < 3; i++) assertFalse(press.hold());
        assertTrue(press.hold());
        assertFalse(press.release());
        assertFalse(press.release());
    }

    @Test void chordDoesNotToggle() {
        var press = new WheelPress();
        press.cancel(); // R may run before the wheel subscriber in the same tick.
        assertFalse(press.hold());
        assertFalse(press.release());
        assertFalse(press.hold());
        assertTrue(press.release());
    }

    @Test void holdOpensRingAndDoesNotToggleEvenIfCancelledLater() {
        var press = new WheelPress();
        for (int i = 0; i < 3; i++) assertFalse(press.hold());
        assertTrue(press.hold());
        assertFalse(press.release());
        for (int i = 0; i < 4; i++) press.hold();
        press.cancel();
        assertFalse(press.release());
    }
}
