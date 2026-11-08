package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** The jade sect tablet: the server's snapshot to the client, and the screen's one button press back. */
public final class SectTabletPayload {
    private SectTabletPayload() { }

    /** Server to client: open the screen (or refresh it when it is already open) with this snapshot. */
    public record Open(SectTabletData data) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sect_tablet_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> STREAM_CODEC = StreamCodec.composite(
                SectTabletData.CODEC, Open::data, Open::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Server to client: open the contract board screen (or refresh it when it is already open) with this snapshot. */
    public record OpenBoard(SectTabletData data) implements CustomPacketPayload {
        public static final Type<OpenBoard> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sect_board_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenBoard> STREAM_CODEC = StreamCodec.composite(
                SectTabletData.CODEC, OpenBoard::data, OpenBoard::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Server to client: new data for the tablet and the current-contract HUD cache. */
    public record Update(SectTabletData data) implements CustomPacketPayload {
        public static final Type<Update> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sect_tablet_update"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Update> STREAM_CODEC = StreamCodec.composite(
                SectTabletData.CODEC, Update::data, Update::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Client to server: a contract button of the board or of the tablet. {@code op}: take, handin, invite, accept, decline or
     * leave; {@code slot} for take, {@code target} (a player id from the snapshot) for invite. The server checks everything
     * again; the commands {@code /contract ...} stay for admins and tests.
     */
    public record Contract(String op, int slot, String target) implements CustomPacketPayload {
        public static final Type<Contract> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sect_contract_act"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Contract> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Contract::op, ByteBufCodecs.VAR_INT, Contract::slot,
                ByteBufCodecs.STRING_UTF8, Contract::target, Contract::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client to server: {@code review_signup}, {@code refresh}, {@code track}, {@code close}. The contract buttons use {@link Contract}. */
    public record Act(String action) implements CustomPacketPayload {
        public static final Type<Act> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sect_tablet_act"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Act> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Act::action, Act::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
