package io.github.verycooltimo.murim.profile;

import io.github.verycooltimo.murim.network.SyncProfilePayload;
import io.github.verycooltimo.murim.network.SyncRitualPayload;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Отправка профиля и хода ритуала владельцу. Чужие профили клиенту не нужны. */
public final class ProfileNetwork {

    public static void sync(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player,
                new SyncProfilePayload(player.getData(ModAttachments.PROFILE)));
    }

    public static void syncRitual(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player,
                SyncRitualPayload.of(player.getData(ModAttachments.RITUAL)));
    }

    private ProfileNetwork() {
    }
}
