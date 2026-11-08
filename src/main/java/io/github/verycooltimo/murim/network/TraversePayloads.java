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

    /** Server-approved Shift/Follow, Dipper, Behind or Rising polyline; client predicts the same collision-aware motion.
     * API: /tmp/mr-ref-1121/minecraft-src/network/codec/StreamCodec.java#of;
     * FriendlyByteBuf#readVarInt/writeVarInt/readDouble/writeDouble/readLong/writeLong (also inherited by RegistryFriendlyByteBuf). */
    public record SevenRoute(java.util.List<net.minecraft.world.phys.Vec3> points, int ticks, int target, long startTick, int actor, int stars) implements CustomPacketPayload {
        public SevenRoute(java.util.List<net.minecraft.world.phys.Vec3> points, int ticks, int target, long startTick) {
            this(points, ticks, target, startTick, -1, 0);
        }
        public long ageAt(long serverGameTime) { return serverGameTime - startTick; }
        public SevenRoute {
            points = java.util.List.copyOf(points);
            if (points.size() < 2 || points.size() > 64 || (ticks != 1 && ticks != 3 && ticks != 4 && ticks != 5 && ticks != 6 && ticks != 20))
                throw new IllegalArgumentException("Invalid footwork route");
        }
        public static final Type<SevenRoute> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "seven_route"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SevenRoute> STREAM_CODEC = StreamCodec.of((buf, value) -> {
            buf.writeVarInt(value.points().size());
            for (var point : value.points()) { buf.writeDouble(point.x); buf.writeDouble(point.y); buf.writeDouble(point.z); }
            buf.writeVarInt(value.ticks()); buf.writeVarInt(value.target()); buf.writeLong(value.startTick()); buf.writeVarInt(value.actor()); buf.writeVarInt(value.stars());
        }, buf -> {
            int count = buf.readVarInt();
            if (count < 2 || count > 64) throw new IllegalArgumentException("Invalid Seven Stars route");
            var points = new java.util.ArrayList<net.minecraft.world.phys.Vec3>();
            for (int i = 0; i < count; i++) points.add(new net.minecraft.world.phys.Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()));
            return new SevenRoute(points, buf.readVarInt(), buf.readVarInt(), buf.readLong(), buf.readVarInt(), buf.readVarInt());
        });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Explicit presentation lock: vanilla client BodyRotationControl can turn the body even with NoAI.
     * API: MC StreamCodec#of, FriendlyByteBuf#read/writeVarInt/read/writeFloat (1.21.1 reference sources). */
    public record HeldFacing(int entityId, float yaw, float bodyYaw, float headYaw, int ticks) implements CustomPacketPayload {
        public static final Type<HeldFacing> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "dipper_facing"));
        public static final StreamCodec<RegistryFriendlyByteBuf, HeldFacing> STREAM_CODEC = StreamCodec.of((buf, value) -> {
            buf.writeVarInt(value.entityId()); buf.writeFloat(value.yaw()); buf.writeFloat(value.bodyYaw());
            buf.writeFloat(value.headYaw()); buf.writeVarInt(value.ticks());
        }, buf -> new HeldFacing(buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readVarInt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Сервер → наблюдателям: состояние бега игрока {@code entityId} или толчок.
     *
     * @param kind 0 — бег выключен, 1 — бег включён (dirX — семейство), 2 — перелёт, 3 — от стены,
     *             4 — поворот в воздухе, 5 — Шаг Мига (dir — смещение рывка),
     *             6/7 — Тень вкл/выкл, 8 — Шаг Смерти, 9 — рывок Хуашань (Аромат за спиной),
     *             10 — Death contact (executionTier carries victim entity ID), 11 — Death wind-up,
     *             12 — decoy dissolution, 13 — Exposed seal (executionTier: remaining ticks, 0 = 60),
     *             14 — Chasing Step arrival dust (dir: absolute ground X/Z), 15 — scuff, 16 — confirmed timed melee miss (dir: offset to push-off position),
     *             17 — Weight Shift ground dust wave
     */
    // Dipper 18: grounded stop, executionTier = point index, dirX/Z = absolute contact X/Z.
    // Dipper 19: confirmed cut, executionTier = point index, dirX/Z = absolute foe X/Z.
    // Both reuse the actor's replicated ground height; never feed these coordinates into generic bursts.
    public record Event(int entityId, int kind, int executionTier, float dirX, float dirZ) implements CustomPacketPayload {
        public static final Type<Event> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "traverse_event"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Event> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Event::entityId,
                ByteBufCodecs.VAR_INT, Event::kind,
                ByteBufCodecs.VAR_INT, Event::executionTier,
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
