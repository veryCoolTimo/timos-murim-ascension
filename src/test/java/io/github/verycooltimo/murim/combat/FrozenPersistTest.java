package io.github.verycooltimo.murim.combat;

import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.registry.ModAttachments;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** The airborne hold (deadline, gravity-ownership flag) survives a save, so the expiry can restore gravity after a reload. */
class FrozenPersistTest {

    @Test
    void deadlineAndGravityFlagRoundTrip() {
        long[] in = {123456L, 1L};
        var json = ModAttachments.FROZEN_CODEC.encodeStart(JsonOps.INSTANCE, in).getOrThrow();
        long[] out = ModAttachments.FROZEN_CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertArrayEquals(in, out);
    }

    @Test
    void shortListReadsAsZeros() {
        var json = ModAttachments.FROZEN_CODEC.encodeStart(JsonOps.INSTANCE, new long[] {0L, 0L}).getOrThrow();
        assertArrayEquals(new long[] {0L, 0L}, ModAttachments.FROZEN_CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
    }
}
