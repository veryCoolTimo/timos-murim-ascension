package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Пакеты цингуна (Шаг Молнии). */
public final class TraversePayloads {

    /**
     * Клиент → сервер: нажат прыжок во время бега. Что именно выйдет — решает сервер.
     *
     * @param grounded клиент видел опору в момент нажатия (сервер может отставать на тик)
     */
    public record Jump(boolean grounded) implements CustomPacketPayload {
        public static final Type<Jump> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "traverse_jump"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Jump> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Jump::grounded, Jump::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Клиент → сервер: R на технике шага с контекстом ввода — биты
     * {@link io.github.verycooltimo.murim.combat.FootworkService#SPRINT} и соседние.
     */
    public record Request(ResourceLocation technique, int input) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "footwork_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> STREAM_CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, Request::technique,
                ByteBufCodecs.VAR_INT, Request::input,
                Request::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Сервер → самому игроку: рывок шага — пройти {@code distance} блоков по (dx, dz) за
     * {@code ticks} тиков. Двигает клиент своей физикой: камера проходит путь, а не прыгает
     * (автор 02.10: «от первого лица это телепорт — должно быть, что ты быстро промчался»).
     */
    /** {@code dy} ≠ 0 — рывок в воздух к цели (03.10: приёмы по врагу в небе), потом обычное падение. */
    public record Dash(float dx, float dy, float dz, float distance, int ticks) implements CustomPacketPayload {
        public static final Type<Dash> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "footwork_dash"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Dash> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.FLOAT, Dash::dx,
                ByteBufCodecs.FLOAT, Dash::dy,
                ByteBufCodecs.FLOAT, Dash::dz,
                ByteBufCodecs.FLOAT, Dash::distance,
                ByteBufCodecs.VAR_INT, Dash::ticks,
                Dash::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Сервер → наблюдателям: состояние бега игрока {@code entityId} или толчок.
     *
     * @param kind 0 — бег выключен, 1 — бег включён (dirX — семейство), 2 — перелёт, 3 — от стены,
     *             4 — поворот в воздухе, 5 — Шаг Мига (dir — смещение рывка),
     *             6/7 — Тень вкл/выкл, 8 — Шаг Смерти, 9 — рывок Хуашань (Аромат за спиной)
     */
    public record Event(int entityId, int kind, int layer, float dirX, float dirZ) implements CustomPacketPayload {
        public static final Type<Event> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "traverse_event"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Event> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Event::entityId,
                ByteBufCodecs.VAR_INT, Event::kind,
                ByteBufCodecs.VAR_INT, Event::layer,
                ByteBufCodecs.FLOAT, Event::dirX,
                ByteBufCodecs.FLOAT, Event::dirZ,
                Event::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private TraversePayloads() {
    }
}
