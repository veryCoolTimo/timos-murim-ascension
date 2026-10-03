package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

/** Пакеты раскладки техник по слотам (решение автора 01.10). */
public final class LoadoutPayloads {

    /** Клиент → сервер: положить технику в слот или очистить его; слот −1 — ячейка основы меча. */
    public record SetSlot(int slot, Optional<ResourceLocation> technique) implements CustomPacketPayload {
        public static final Type<SetSlot> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "loadout_set"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SetSlot> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, SetSlot::slot,
                ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC), SetSlot::technique,
                SetSlot::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Клиент → сервер: выбрать слот (из кольца). */
    public record Select(int slot) implements CustomPacketPayload {
        public static final Type<Select> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "loadout_select"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Select> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Select::slot, Select::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Сервер → клиент: раскладка и сколько слотов открыто.
     *
     * @param open сколько слотов доступно сейчас (растёт с прогрессией)
     */
    public record Sync(List<Optional<ResourceLocation>> slots, int active, int open,
                       Optional<ResourceLocation> foundation) implements CustomPacketPayload {
        public static final Type<Sync> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "loadout_sync"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC).apply(ByteBufCodecs.list()), Sync::slots,
                ByteBufCodecs.VAR_INT, Sync::active,
                ByteBufCodecs.VAR_INT, Sync::open,
                ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC), Sync::foundation,
                Sync::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private LoadoutPayloads() {
    }
}
