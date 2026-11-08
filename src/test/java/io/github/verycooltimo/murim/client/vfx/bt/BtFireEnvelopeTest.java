package io.github.verycooltimo.murim.client.vfx.bt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BtFireEnvelopeTest {
    @Test void tonguesFitTheLabEnvelopeAtBothRanks() {
        assertEquals(16,BtFireEnvelope.count(true));
        assertEquals(18,BtFireEnvelope.count(false));
        for(double kneeSpan:new double[]{.7,1,1.2,1.6}) {
            assertTrue(2*BtFireEnvelope.radius(kneeSpan)<=1.9);
            assertTrue(2*BtFireEnvelope.radius(kneeSpan)<=1.6*kneeSpan);
        }
        for(int i=0;i<=100;i++) {
            double seed=i/100.0;
            assertTrue(2*BtFireEnvelope.halfWidth(true,seed)>=.10);
            assertTrue(2*BtFireEnvelope.halfWidth(true,seed)<=.24+1e-9);
            assertTrue(2*BtFireEnvelope.halfWidth(false,seed)>=.12);
            assertTrue(2*BtFireEnvelope.halfWidth(false,seed)<=.28+1e-9);
            assertTrue(BtFireEnvelope.height(true,seed,1,1.1,.025)<=.4);
            assertTrue(BtFireEnvelope.height(false,seed,1,.7,.2)+.2<=1.3*.7);
        }
    }
    @Test void firstRankRecedesToEmbersThenDisappears() {
        assertEquals(1,BtFireEnvelope.recession(true,9));
        assertTrue(1.1*BtFireEnvelope.recession(true,10.5)<=.25);
        assertEquals(0,BtFireEnvelope.recession(true,11));
        assertEquals(0,BtFireEnvelope.recession(true,12.5));
    }
}
