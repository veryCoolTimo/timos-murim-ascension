package io.github.verycooltimo.murim.client.decor;

import io.github.verycooltimo.murim.MurimMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

/** API: NeoForge RegisterClientReloadListenersEvent#registerReloadListener, Minecraft ResourceManagerReloadListener. */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class TipsyResources {
    private TipsyResources() { }
    @SubscribeEvent
    static void reload(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> {
            TipsyCamera.resetDoubleVision();
            io.github.verycooltimo.murim.client.camera.CinematicDof.close();
        });
    }
}
