package io.github.verycooltimo.murim.training;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Training packets, server → client only: input is the vanilla crouch key and the view, the server reads them itself
 * ({@link SetMachine}), so there is nothing to send up.
 *
 * <p>API: reference/minecraft-src/net/minecraft/network/codec/StreamCodec.java#of (composite takes at most six
 * fields in 1.21.1 — the codecs are written by hand, as in {@code SyncMeditationPayload}).
 */
public final class TrainingPayloads {

    private TrainingPayloads() {
    }

    /** What happened this tick (for the clip and the HUD flash). */
    public enum Beat { NONE, START, GOOD, FAIR, OFF, RUSHED, HOLD, END, REACH, FELL, FINISH, VOID, SWITCH }

    /**
     * A player's training, to the player and everyone tracking them (the pose is seen from the side too).
     *
     * @param entity   player entity id
     * @param exercise {@link Exercise} ordinal, −1 — none
     * @param beat     event of this update
     * @param reps     reps / seconds / blocks / checkpoint reached
     * @param good     reps on the beat; routes: checkpoints in all
     * @param stamina  set stamina 0..1
     * @param origin   beat origin (game tick) for the rhythm marker; routes: start tick
     * @param gain     tempering gained in this set or run so far
     */
    public record State(int entity, int exercise, Beat beat, int reps, int good, float stamina, long origin, float gain)
            implements CustomPacketPayload {

        public static final Type<State> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "training_state"));

        public static final StreamCodec<RegistryFriendlyByteBuf, State> STREAM_CODEC = StreamCodec.of((buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.entity());
            ByteBufCodecs.VAR_INT.encode(buf, p.exercise() + 1);
            ByteBufCodecs.VAR_INT.encode(buf, p.beat().ordinal());
            ByteBufCodecs.VAR_INT.encode(buf, p.reps());
            ByteBufCodecs.VAR_INT.encode(buf, p.good());
            ByteBufCodecs.FLOAT.encode(buf, p.stamina());
            ByteBufCodecs.VAR_LONG.encode(buf, p.origin());
            ByteBufCodecs.FLOAT.encode(buf, p.gain());
        }, buf -> new State(ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf) - 1,
                Beat.values()[Math.min(Beat.values().length - 1, ByteBufCodecs.VAR_INT.decode(buf))],
                ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.FLOAT.decode(buf),
                ByteBufCodecs.VAR_LONG.decode(buf), ByteBufCodecs.FLOAT.decode(buf)));

        public Exercise exerciseOrNull() {
            return Exercise.byIndex(exercise);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * The owner's body, for the HUD: level, progress to the next, fatigue, today's diminishing factor, best times.
     */
    public record Body(int level, float progress, float fatigue, float daily, int bestClimb, int bestTrail)
            implements CustomPacketPayload {

        public static final Type<Body> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "training_body"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Body> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Body::level,
                ByteBufCodecs.FLOAT, Body::progress,
                ByteBufCodecs.FLOAT, Body::fatigue,
                ByteBufCodecs.FLOAT, Body::daily,
                ByteBufCodecs.VAR_INT, Body::bestClimb,
                ByteBufCodecs.VAR_INT, Body::bestTrail,
                Body::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
