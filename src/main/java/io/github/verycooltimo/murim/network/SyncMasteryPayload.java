package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Освоение техник для клиента: что выучено, на каком слое и что ждёт осмысления.
 * Мудрость не передаётся — игрок её не видит (автор 30.09: «косвенно»).
 *
 * @param entries выученные техники
 */
public record SyncMasteryPayload(List<Entry> entries) implements CustomPacketPayload {

    /**
     * @param technique техника
     * @param layer     пройденный слой
     * @param cap       предел манускрипта
     * @param progress  доля пути к следующему слою, 0..1
     * @param pending   неосмысленное пережитое — его показывает двойник в медитации
     */
    public record Entry(ResourceLocation technique, int layer, int cap, float progress, float pending) {

        public static final StreamCodec<ByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, Entry::technique,
                ByteBufCodecs.VAR_INT, Entry::layer,
                ByteBufCodecs.VAR_INT, Entry::cap,
                ByteBufCodecs.FLOAT, Entry::progress,
                ByteBufCodecs.FLOAT, Entry::pending,
                Entry::new);
    }

    public static final Type<SyncMasteryPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sync_mastery"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncMasteryPayload> STREAM_CODEC =
            StreamCodec.composite(
                    Entry.STREAM_CODEC.apply(ByteBufCodecs.list()).cast(), SyncMasteryPayload::entries,
                    SyncMasteryPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
