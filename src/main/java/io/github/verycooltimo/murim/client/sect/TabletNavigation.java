package io.github.verycooltimo.murim.client.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.library.JunkBookScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Navigation only: the journal's book rendering is deliberately unchanged in this round.
 * API: reference/neoforge-src/net/neoforged/neoforge/client/event/ScreenEvent.java#KeyPressed.Pre, #Opening. */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class TabletNavigation {
    private static SectTabletScreen parent;
    private static JunkBookScreen journal;
    private TabletNavigation() { }
    static void remember(SectTabletScreen screen) { parent = screen; journal = null; }
    @SubscribeEvent static void opening(ScreenEvent.Opening event) {
        if (parent == null) return;
        if (journal == null && event.getNewScreen() instanceof JunkBookScreen book) journal = book;
        else if (event.getNewScreen() != parent && event.getNewScreen() != journal) { parent = null; journal = null; }
    }
    @SubscribeEvent static void key(ScreenEvent.KeyPressed.Pre event) {
        if (event.getScreen() == journal && parent != null && event.getKeyCode() == 256) {
            SectTabletScreen back = parent;
            parent = null; journal = null;
            Minecraft.getInstance().setScreen(back);
            back.resume();
            event.setCanceled(true);
        }
    }
    @SubscribeEvent static void logout(ClientPlayerNetworkEvent.LoggingOut event) { parent = null; journal = null; }
}
