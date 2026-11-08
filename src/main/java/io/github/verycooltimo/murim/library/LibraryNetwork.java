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

/** Server-authoritative book readers and the checked heretical practice request. */
public final class LibraryNetwork {

    /** Translatable components stay untranslated until the reader lays them out in the client's language. */
    public record OpenText(net.minecraft.network.chat.Component title,
                           java.util.List<net.minecraft.network.chat.Component> paragraphs) implements CustomPacketPayload {
        public OpenText { paragraphs = java.util.List.copyOf(paragraphs); }
        public static final Type<OpenText> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "text_book_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenText> STREAM_CODEC = StreamCodec.composite(
                net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC, OpenText::title,
                net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list(128)), OpenText::paragraphs,
                OpenText::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/network/codec/{StreamCodec,ByteBufCodecs}.java
    // and resources/ResourceLocation.java#STREAM_CODEC; neoforge-src/net/neoforged/neoforge/
    // network/registration/PayloadRegistrar.java#optional (returns a copy), #playToClient;
    // common/extensions/ICommonPacketListener.java#hasChannel(CustomPacketPayload).
    public record MethodState(ResourceLocation method, String status, String reasonKey, String replacesKey) implements CustomPacketPayload {
        public static final Type<MethodState> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "method_scroll_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MethodState> STREAM_CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, MethodState::method,
                ByteBufCodecs.STRING_UTF8, MethodState::status,
                ByteBufCodecs.STRING_UTF8, MethodState::reasonKey,
                ByteBufCodecs.STRING_UTF8, MethodState::replacesKey, MethodState::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record LearnMethod(ResourceLocation method) implements CustomPacketPayload {
        public static final Type<LearnMethod> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "method_scroll_learn"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LearnMethod> STREAM_CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, LearnMethod::method, LearnMethod::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Optional hanging-scroll reader; keys are translated only by the receiving client. */
    public record OpenMethodScroll(ResourceLocation method, String titleKey,
                                   java.util.List<String> textKeys, MethodState state) implements CustomPacketPayload {
        public OpenMethodScroll { textKeys = java.util.List.copyOf(textKeys); }
        public static final Type<OpenMethodScroll> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "method_scroll_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenMethodScroll> STREAM_CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, OpenMethodScroll::method,
                ByteBufCodecs.STRING_UTF8, OpenMethodScroll::titleKey,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(128)), OpenMethodScroll::textKeys,
                MethodState.STREAM_CODEC, OpenMethodScroll::state, OpenMethodScroll::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Negotiate before sending: older readers receive a read-only book. */
    public static void openMethodScroll(ServerPlayer player, ResourceLocation method, String titleKey, String... textKeys) {
        var payload = new OpenMethodScroll(method, titleKey, java.util.List.of(textKeys),
                io.github.verycooltimo.murim.item.MethodScrollItem.readerState(player, method));
        if (player.connection != null && player.connection.hasChannel(payload)) {
            io.github.verycooltimo.murim.network.ChannelSafe.send(player, payload);
        } else {
            TextBooks.open(player, titleKey, textKeys);
        }
    }

    public record OpenJunk(JunkBook book, boolean mainHand) implements CustomPacketPayload {
        public static final Type<OpenJunk> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "junk_book_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenJunk> STREAM_CODEC = StreamCodec.composite(
                JunkBook.STREAM_CODEC, OpenJunk::book, ByteBufCodecs.BOOL, OpenJunk::mainHand, OpenJunk::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

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
        registrar.optional().playToClient(OpenMethodScroll.TYPE, OpenMethodScroll.STREAM_CODEC,
                (payload, context) -> LibraryClientBridge.openMethodScroll(payload));
        registrar.optional().playToClient(MethodState.TYPE, MethodState.STREAM_CODEC,
                (payload, context) -> LibraryClientBridge.methodState(payload));
        registrar.optional().playToServer(LearnMethod.TYPE, LearnMethod.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                learnMethod(player, payload);
            }
        });
        registrar.playToClient(OpenText.TYPE, OpenText.STREAM_CODEC,
                (payload, context) -> LibraryClientBridge.openText(payload));
        registrar.playToClient(OpenJunk.TYPE, OpenJunk.STREAM_CODEC,
                (payload, context) -> LibraryClientBridge.openJunk(payload.book(), payload.mainHand()));
        registrar.playToServer(Practise.TYPE, Practise.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                JunkBookItem.practise(player, payload.mainHand() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
            }
        });
    }

    /** Shared packet entry point for the server handler and its GameTest. */
    public static void learnMethod(ServerPlayer player, LearnMethod request) {
        var state = io.github.verycooltimo.murim.item.MethodScrollItem.requestLearn(player, request.method());
        io.github.verycooltimo.murim.network.ChannelSafe.send(player, state);
    }

    private LibraryNetwork() {
    }
}
