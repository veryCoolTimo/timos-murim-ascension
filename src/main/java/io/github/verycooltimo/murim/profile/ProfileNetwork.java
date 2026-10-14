package io.github.verycooltimo.murim.profile;

import io.github.verycooltimo.murim.network.SyncProfilePayload;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Отправка профиля владельцу. Чужие профили клиенту не нужны. */
public final class ProfileNetwork {

    public static void sync(ServerPlayer player) {
        SyncProfilePayload payload = new SyncProfilePayload(player.getData(ModAttachments.PROFILE));
        // GameTest mock players have no mod channel.
        // API: reference/neoforge-src/net/neoforged/neoforge/common/extensions/ICommonPacketListener.java#hasChannel
        if (player.connection != null && player.connection.hasChannel(payload)) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    private ProfileNetwork() {
    }
}
