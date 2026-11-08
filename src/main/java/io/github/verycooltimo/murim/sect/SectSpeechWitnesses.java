package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.entity.SectDisciple;
import net.minecraft.server.level.ServerPlayer;

final class SectSpeechWitnesses {
    private SectSpeechWitnesses() {}

    static void record(ServerPlayer player, String deed) {
        record(player, deed, deed);
    }

    static void record(ServerPlayer player, String deed, String event) {
        // API: Minecraft 1.21.1 EntityGetter#getEntitiesOfClass, Entity#getBoundingBox/distanceToSqr,
        // AABB#inflate, Mob#getSensing and Sensing#hasLineOfSight (read-only /tmp/mr-ref-1121 sources).
        long now = player.level().getGameTime();
        for (SectDisciple npc : player.level().getEntitiesOfClass(SectDisciple.class,
                player.getBoundingBox().inflate(16), d -> d.distanceToSqr(player) <= 256
                        && d.isAlive() && !d.isSleeping() && !d.dormant() && d.getSensing().hasLineOfSight(player))) {
            npc.speechGuard().witness(deed, now, player.getUUID() + ":" + event + ":" + now);
        }
    }
}
