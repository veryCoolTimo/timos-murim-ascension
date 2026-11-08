package io.github.verycooltimo.murim.client.vfx;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FootworkRibbonLayersTest {
    @Test void airSheetDrawsPinkAccentBeforeReturning() {
        var drawn = new java.util.ArrayList<String>();
        assertTrue(FootworkRibbonLayers.sheet(.20f, () -> drawn.add("sheet"),
                () -> drawn.add("edge"), () -> drawn.add("pink")));
        assertEquals(java.util.List.of("sheet", "edge", "pink"), drawn);
    }
    @Test void ordinaryRibbonUsesItsOwnPaintPath() {
        assertFalse(FootworkRibbonLayers.sheet(0, () -> fail("sheet"), () -> fail("edge"), () -> fail("pink")));
    }
}
