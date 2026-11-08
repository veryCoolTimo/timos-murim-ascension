package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.network.ChannelSafe;
import io.github.verycooltimo.murim.network.TraversePayloads;
import net.minecraft.server.level.ServerPlayer;

/** Exceptional collision stop; the client blends disagreement above .75 over three frames.
 * API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/entity/Entity.java#position;
 * net/minecraft/world/level/Level.java#getGameTime. No vanilla teleport bypasses the blend. */
final class FootworkCorrections {
    static void blocked(ServerPlayer player) {
        ChannelSafe.send(player, new TraversePayloads.SevenRoute(
                java.util.List.of(player.position(), player.position()), 1, -1, player.level().getGameTime()));
        // The stop payload owns the three-frame client blend. A following vanilla teleport
        // would bypass it and snap both interpolation endpoints in the same frame.
    }
    private FootworkCorrections() { }
}
