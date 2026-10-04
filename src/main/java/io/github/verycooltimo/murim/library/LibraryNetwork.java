package io.github.verycooltimo.murim.library;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Library payloads: the client opens junk books by itself, the server only hears «Попробовать» on a heretical method. */
public final class LibraryNetwork {

    /** Client → server: try the heretical method of the book in hand. */
    public record Practise(boolean mainHand) implements CustomPacketPayload {
        public static final Type<Practise> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "junk_practise"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Practise> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Practise::mainHand, Practise::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Called from ModNetwork with its registrar (one protocol version for the whole mod). */
    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(Practise.TYPE, Practise.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                JunkBookItem.practise(player, payload.mainHand() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
            }
        });
    }

    private LibraryNetwork() {
    }
}
