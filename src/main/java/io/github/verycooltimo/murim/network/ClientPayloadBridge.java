package io.github.verycooltimo.murim.network;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Мост между регистрацией пакетов в общем коде и клиентским обработчиком.
 *
 * <p>Зачем нужен отдельный класс: если бы {@link ModNetwork} ссылался на клиентский обработчик
 * напрямую, JVM при загрузке класса регистрации подтянула бы client-only типы и уронила бы
 * dedicated server. Здесь клиентский класс упоминается только внутри тела метода, за проверкой
 * стороны, поэтому на сервере он не загружается.
 */
final class ClientPayloadBridge {

    static void handleTechniqueEvent(TechniqueEventPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        io.github.verycooltimo.murim.client.ClientTechniqueHandler.onTechniqueEvent(payload);
    }

    private ClientPayloadBridge() {
    }
}
