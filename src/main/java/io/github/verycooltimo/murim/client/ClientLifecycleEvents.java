package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.vfx.BladeTrailRenderer;
import io.github.verycooltimo.murim.client.vfx.ImpactScreenLayer;
import io.github.verycooltimo.murim.client.vfx.TechniqueNameLayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * Единая точка сброса клиентских эффектов при выходе из мира.
 *
 * <p>Раньше каждый эффект обещал такой сброс в своём javadoc, но методы {@code reset()}
 * не вызывались ниоткуда — инвариант держался случайно, на том, что клиентские тики
 * продолжают идти в главном меню и счётчики успевают дотикать до нуля. Достаточно было
 * добавить в тик-обработчик ранний выход по {@code level == null} — типовая правка, —
 * и вспышка с тряской замерли бы на полной силе навсегда.
 *
 * <p>{@code Clone} обрабатывается наравне с выходом: смена измерения пересоздаёт игрока,
 * а идентификаторы сущностей в новом мире принадлежат другим существам.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class ClientLifecycleEvents {

    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        resetAll();
    }

    @SubscribeEvent
    static void onClone(ClientPlayerNetworkEvent.Clone event) {
        resetAll();
    }

    private static void resetAll() {
        BladeTrailRenderer.clear();
        CameraShakeHandler.reset();
        ImpactScreenLayer.reset();
        TechniqueNameLayer.reset();
        ClientTechniqueHandler.reset();
        HitStopHandler.reset();
        DevCaptureHandler.reset();
    }

    private ClientLifecycleEvents() {
    }
}
