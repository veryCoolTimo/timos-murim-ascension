package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the player's current goal and daily task are: a person (a live entity, or the place where he is expected) with his
 * name, role and the place in words. The client draws a quiet arrow and a mote over the head of the goal's person from it.
 * An empty list clears the guide (the reminder is switched off, or the player is no member).
 */
public record SectGuidePayload(List<Entry> spots) implements CustomPacketPayload {
    public static final Type<SectGuidePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sect_guide"));

    /**
     * One spot. {@code goal}: the main goal (false: a daily task); {@code id}: the goal or task id (a change shows the label
     * again); {@code entity}: the person's entity id on the server, -1 when he is not standing in a loaded place.
     */
    public record Entry(boolean goal, String id, Component what, Component name, Component role, Component where,
                        double x, double y, double z, int entity) {
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, SectGuidePayload> STREAM_CODEC = StreamCodec.of(
            (b, p) -> {
                b.writeVarInt(p.spots.size());
                for (Entry e : p.spots) {
                    b.writeBoolean(e.goal).writeUtf(e.id, 64);
                    ComponentSerialization.STREAM_CODEC.encode(b, e.what);
                    ComponentSerialization.STREAM_CODEC.encode(b, e.name);
                    ComponentSerialization.STREAM_CODEC.encode(b, e.role);
                    ComponentSerialization.STREAM_CODEC.encode(b, e.where);
                    b.writeDouble(e.x).writeDouble(e.y).writeDouble(e.z).writeVarInt(e.entity + 1);
                }
            },
            b -> {
                int n = b.readVarInt();
                List<Entry> out = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    boolean goal = b.readBoolean();
                    String id = b.readUtf(64);
                    Component what = ComponentSerialization.STREAM_CODEC.decode(b);
                    Component name = ComponentSerialization.STREAM_CODEC.decode(b);
                    Component role = ComponentSerialization.STREAM_CODEC.decode(b);
                    Component where = ComponentSerialization.STREAM_CODEC.decode(b);
                    out.add(new Entry(goal, id, what, name, role, where, b.readDouble(), b.readDouble(), b.readDouble(), b.readVarInt() - 1));
                }
                return new SectGuidePayload(List.copyOf(out));
            });

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
