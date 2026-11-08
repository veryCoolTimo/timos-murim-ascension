package io.github.verycooltimo.murim.client.vfx;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GlowFrameTest {
    @Test void breakthroughAndTechniquePrepareAndCompositeOnlyOnce() {
        GlowFrame frame = new GlowFrame();
        frame.begin();
        int preparations = 0, composites = 0;
        if (frame.request(.208F)) preparations++; // meditation/breakthrough
        assertTrue(frame.collecting()); // legacy composite is deferred while collecting
        if (frame.request(.8F)) preparations++; // technique later in the same AFTER_PARTICLES
        if (frame.request(.3F)) preparations++;
        assertEquals(.8F, frame.strength());
        if (frame.finish()) composites++;
        if (frame.finish()) composites++;
        assertEquals(1, preparations);
        assertEquals(1, composites);
        assertFalse(frame.request(1F));
        frame.begin();
        assertTrue(frame.request(.1F));
        assertEquals(.1F, frame.strength());
        assertTrue(frame.finish());
    }
    @Test void emptyFrameNeverComposites() {
        GlowFrame frame = new GlowFrame();
        frame.begin();
        assertFalse(frame.request(Float.NaN));
        assertFalse(frame.request(0));
        assertFalse(frame.finish());
    }
}
