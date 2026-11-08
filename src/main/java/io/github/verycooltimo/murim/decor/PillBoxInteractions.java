package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Let the existing block interaction handle a sneak click even with something held. */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class PillBoxInteractions {
    private PillBoxInteractions() { }

    // API: /tmp/mr-ref-1121/neoforge-src/net/neoforged/neoforge/event/entity/player/PlayerInteractEvent.java
    // #RightClickBlock#setUseBlock/setUseItem; common/util/TriState.java#TRUE/FALSE.
    // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/server/level/ServerPlayerGameMode.java#useItemOn
    // TRUE bypasses the held-item sneak check; FALSE prevents onItemUseFirst stealing the lid click.
    // Runs on both logical sides for prediction; only PillBoxBlock changes the server's saved OPEN state.
    @SubscribeEvent
    static void sneakUse(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || !event.getEntity().isShiftKeyDown()) return;
        var state = event.getLevel().getBlockState(event.getPos());
        if (state.is(IntWorkshop.PILL_BOX.get()) || state.is(IntWorkshop.PILL_CRATE.get())) {
            event.setUseBlock(TriState.TRUE);
            event.setUseItem(TriState.FALSE);
        }
    }
}
