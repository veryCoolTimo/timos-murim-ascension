package io.github.verycooltimo.murim.client.vfx;

import io.github.verycooltimo.murim.network.TechniqueEventPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** API: read-only minecraft-src RegistryFriendlyByteBuf#constructor, RegistryAccess#EMPTY,
 * ResourceLocation#fromNamespaceAndPath; StreamCodec#encode/decode. No world or game startup. */
class BambooStarPayloadTest {
    @Test void wirePreservesActualStarsWithoutChangingExecutionTierOrAnimationFields() {
        for (int stars : new int[]{11, 12}) {
            var p = new TechniqueEventPayload(TechniqueEventPayload.Event.STARTED,
                    ResourceLocation.fromNamespaceAndPath("murim", "bamboo_sword_fingers"), 42, 2, 4, false, stars);
            var bytes = Unpooled.buffer();
            try {
                var buf = new RegistryFriendlyByteBuf(bytes, RegistryAccess.EMPTY);
                TechniqueEventPayload.STREAM_CODEC.encode(buf, p);
                var copy = TechniqueEventPayload.STREAM_CODEC.decode(buf);
                assertEquals(p, copy);
                assertEquals(4, copy.executionTier());
                assertEquals(2, copy.hitStopTicks());
                assertEquals(stars == 12, BambooLook.mastered(copy.masteryStars()));
            } finally { bytes.release(); }
        }
    }
    @Test void existingStepSideAndFiveArgumentCallersKeepTheirMeaning() {
        var id = ResourceLocation.fromNamespaceAndPath("murim", "seven_stars_shift");
        var p = new TechniqueEventPayload(TechniqueEventPayload.Event.STARTED, id, 7, 0, 3, true);
        assertTrue(p.rightStep());
        assertEquals(3, p.executionTier());
        assertEquals(0, p.masteryStars());
        assertFalse(new TechniqueEventPayload(TechniqueEventPayload.Event.HIT, id, 7, 2, 3).rightStep());
    }
}
