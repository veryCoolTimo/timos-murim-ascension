package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Пакеты основы меча (формы на обычной атаке). */
public final class FoundationPayloads {

    /** Клиент → сервер: сделан взмах формой {@code form} (номер в цепочке). Сервер проверяет. */
    public record Swing(int form) implements CustomPacketPayload {
        public static final Type<Swing> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "foundation_swing"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Swing> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Swing::form, Swing::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Сервер → наблюдателям: игрок {@code entityId} сделал форму — проиграть анимацию и эффект. */
    public record Form(int entityId, int form, int layer, float speed) implements CustomPacketPayload {
        public static final Type<Form> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "foundation_form"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Form> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Form::entityId,
                ByteBufCodecs.VAR_INT, Form::form,
                ByteBufCodecs.VAR_INT, Form::layer,
                ByteBufCodecs.FLOAT, Form::speed,
                Form::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private FoundationPayloads() {
    }
}
