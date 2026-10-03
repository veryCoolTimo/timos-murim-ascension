package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Книга-манускрипт (03.10): сервер открывает книгу у клиента, клиент нажимает «Изучить». */
public final class ManualPayloads {

    /** Сервер → клиент: открыть книгу техники; {@code depth} — предел рваного манускрипта (0 — полный). */
    public record Open(ResourceLocation technique, int depth, String tier, boolean mainHand) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "manual_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> STREAM_CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, Open::technique,
                ByteBufCodecs.VAR_INT, Open::depth,
                ByteBufCodecs.STRING_UTF8, Open::tier,
                ByteBufCodecs.BOOL, Open::mainHand,
                Open::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Клиент → сервер: «Изучить» книгу в руке. */
    /** Клиент → сервер: «Изучить» форму {@code technique} из книги в руке. */
    public record Learn(boolean mainHand, ResourceLocation technique) implements CustomPacketPayload {
        public static final Type<Learn> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "manual_learn"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Learn> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Learn::mainHand, ResourceLocation.STREAM_CODEC, Learn::technique, Learn::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private ManualPayloads() {
    }
}
