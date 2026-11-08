package io.github.verycooltimo.murim.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlumLookCodecTest {
    // API: supplied RegistryFriendlyByteBuf(ByteBuf,RegistryAccess,ConnectionType), RegistryAccess.EMPTY.
    private static <T> void roundTrip(StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.NEOFORGE);
        try {
            codec.encode(buf, value);
            assertEquals(value, codec.decode(buf));
            assertEquals(0, buf.readableBytes());
        } finally { buf.release(); }
    }

    @Test
    void exactStarsSurviveEveryPayloadIncludingSharedExecutionTiers() {
        var id = ResourceLocation.fromNamespaceAndPath("murim", "twenty_four_plum_rainfall");
        for (int stars = 1; stars <= 12; stars++) {
            int tier = io.github.verycooltimo.murim.mastery.Arts.executionTier(id, stars);
            boolean full = stars == 12;
            roundTrip(TechniqueEventPayload.STREAM_CODEC,
                    new TechniqueEventPayload(TechniqueEventPayload.Event.STARTED, id, 42, 0, tier, false, stars));
            roundTrip(RainPayload.STREAM_CODEC, new RainPayload(42, Vec3.ZERO, new Vec3(1, 2, 3), 17, tier, RainPayload.RELEASE, 21, full, stars));
            roundTrip(RiverPayload.STREAM_CODEC, new RiverPayload(42, Vec3.ZERO, new Vec3(1, 2, 3), tier, RiverPayload.AIM, 21, full, stars));
            roundTrip(ScatterPayload.STREAM_CODEC, new ScatterPayload(42, Vec3.ZERO, new Vec3(1, 2, 3), 17, tier, ScatterPayload.RELEASE, 3, 21, full, stars));
            roundTrip(SeaPayload.STREAM_CODEC, new SeaPayload(42, Vec3.ZERO, new Vec3(1, 2, 3), 21, tier, SeaPayload.BEGIN, full, stars));
            roundTrip(BloomPayload.STREAM_CODEC, new BloomPayload(42, Vec3.ZERO, new Vec3(1, 2, 3), 4, tier, BloomPayload.HOLD, full, stars));
        }
    }
}
