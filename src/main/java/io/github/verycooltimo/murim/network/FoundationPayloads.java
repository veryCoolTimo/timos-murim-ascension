package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Пакеты основы меча (формы на обычной атаке). */
public final class FoundationPayloads {

    /** Client → server: swing intent and aimed entity (-1 for air). The server validates the target and owns the combo. */
    public record Swing(int form, int targetId) implements CustomPacketPayload {
        public Swing(int form) { this(form, -1); }

        public static final Type<Swing> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "foundation_swing"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Swing> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Swing::form, ByteBufCodecs.VAR_INT, Swing::targetId, Swing::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Сервер → наблюдателям: игрок {@code entityId} сделал форму — проиграть анимацию и эффект. */
    public record Form(int entityId, int form, int executionTier, float speed, boolean plum) implements CustomPacketPayload {
        public Form(int entityId, int form, int executionTier, float speed) {
            this(entityId, form, executionTier, speed, false);
        }

        public static final Type<Form> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "foundation_form"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Form> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Form::entityId,
                ByteBufCodecs.VAR_INT, Form::form,
                ByteBufCodecs.VAR_INT, Form::executionTier,
                ByteBufCodecs.FLOAT, Form::speed,
                ByteBufCodecs.BOOL, Form::plum,
                Form::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A successful vanilla style hit. Does not carry client-authored damage or targets. */
    public record Hit(int entityId, int targetId, boolean finisher, net.minecraft.world.phys.Vec3 contact) implements CustomPacketPayload {
        /** A finisher here closes an ordinary combo; it is not a big technique finale. */
        public io.github.verycooltimo.murim.combat.StrikeKind kind() {
            return io.github.verycooltimo.murim.combat.StrikeKind.FOUNDATION;
        }

        public static final Type<Hit> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "foundation_hit"));
        private static final StreamCodec<RegistryFriendlyByteBuf, net.minecraft.world.phys.Vec3> VEC = StreamCodec.composite(
                ByteBufCodecs.DOUBLE, net.minecraft.world.phys.Vec3::x,
                ByteBufCodecs.DOUBLE, net.minecraft.world.phys.Vec3::y,
                ByteBufCodecs.DOUBLE, net.minecraft.world.phys.Vec3::z, net.minecraft.world.phys.Vec3::new);
        public static final StreamCodec<RegistryFriendlyByteBuf, Hit> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Hit::entityId, ByteBufCodecs.VAR_INT, Hit::targetId,
                ByteBufCodecs.BOOL, Hit::finisher, VEC, Hit::contact, Hit::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private FoundationPayloads() {
    }
}
