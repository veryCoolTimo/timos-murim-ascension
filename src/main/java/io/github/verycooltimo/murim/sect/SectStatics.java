package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Clears the statics that outlive a world (a singleplayer session opens several worlds in one JVM). */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectStatics {
    private SectStatics() { }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        SectKnock.clearLater();
        SectBoardPlacement.clearGaveUp();
        SectYard.clearBroken();
        SectTablet.clearMentorCache();
        SectLife.clearPopBlocked();
        SectPilfer.clearOpened();
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SectPilfer.forget(event.getEntity().getUUID());
    }
}
