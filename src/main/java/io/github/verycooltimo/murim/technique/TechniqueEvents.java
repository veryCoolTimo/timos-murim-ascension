package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.SyncTechniquesPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Подключение загрузчика техник и рассылка описаний на клиенты.
 *
 * <p>{@link OnDatapackSyncEvent} приходит и при входе игрока, и после {@code /reload},
 * поэтому одна подписка закрывает оба случая: автор правит JSON, выполняет {@code /reload}
 * и сразу видит изменения, не перезапуская игру. Это и есть горячая перезагрузка,
 * которую требует этап 1.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class TechniqueEvents {

    @SubscribeEvent
    static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new TechniqueLoader());
    }

    @SubscribeEvent
    static void onDatapackSync(OnDatapackSyncEvent event) {
        SyncTechniquesPayload payload = new SyncTechniquesPayload(TechniqueLoader.all());
        ServerPlayer target = event.getPlayer();
        if (target != null) {
            PacketDistributor.sendToPlayer(target, payload);
            return;
        }
        // Игрок не указан — это /reload: описания уехали всем сразу.
        PacketDistributor.sendToAllPlayers(payload);
    }

    private TechniqueEvents() {
    }
}
