package io.github.verycooltimo.murim.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;

/** Small entity notifications, including GameTest actors whose connections have no mod channels. */
public final class ChannelSafe {
    private ChannelSafe() { }
    public static void send(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection != null && player.connection.hasChannel(payload)) PacketDistributor.sendToPlayer(player, payload);
    }
    /** Clients ignore an entity notification if that entity has not been tracked yet. */
    public static void nearEntity(Entity entity, CustomPacketPayload payload, boolean includeSelf) {
        if (!(entity.level() instanceof ServerLevel level)) return;
        double range = entity.getType().clientTrackingRange() * 16.0D;
        for (ServerPlayer player : level.players()) {
            if (player != entity && player.distanceToSqr(entity) <= range * range) send(player, payload);
        }
        if (includeSelf && entity instanceof ServerPlayer player) send(player, payload);
    }
}
