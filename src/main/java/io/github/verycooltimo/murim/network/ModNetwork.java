package io.github.verycooltimo.murim.network;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.TechniqueService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Регистрация сетевых пакетов.
 *
 * <p>Обработчик клиентского пакета живёт в отдельном классе внутри {@code client/} и передаётся
 * method-reference-ом: прямая ссылка из общего кода на клиентский тип уронила бы dedicated server
 * при загрузке класса — правило 03.
 */
@EventBusSubscriber(modid = MurimMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModNetwork {

    /** Версия протокола. Поднимать при любом несовместимом изменении пакетов. */
    private static final String VERSION = "1";

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);

        registrar.playToServer(
                StartTechniquePayload.TYPE,
                StartTechniquePayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        TechniqueService.tryStart(serverPlayer, payload.techniqueId());
                    }
                });

        registrar.playToClient(
                TechniqueEventPayload.TYPE,
                TechniqueEventPayload.STREAM_CODEC,
                ClientPayloadBridge::handleTechniqueEvent);

        // Описания техник: клиенту нужна форма дуги, цвета и тайминги, а датапак живёт
        // на сервере. Рассылается при входе и после /reload — см. TechniqueEvents.
        registrar.playToClient(
                SyncTechniquesPayload.TYPE,
                SyncTechniquesPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleSyncTechniques(payload));

        registrar.playToClient(
                SyncProfilePayload.TYPE,
                SyncProfilePayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleSyncProfile(payload));

        registrar.playToClient(
                SyncRitualPayload.TYPE,
                SyncRitualPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleSyncRitual(payload));

        // Попадание: без него клиент рисовал брызги всегда, даже когда удар прошёл мимо.
        registrar.playToClient(
                TechniqueHitPayload.TYPE,
                TechniqueHitPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleTechniqueHit(payload));

        registrar.playToClient(
                SyncAwakeningPayload.TYPE,
                SyncAwakeningPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleSyncAwakening(payload));

        registrar.playToServer(
                StartAwakeningPayload.TYPE,
                StartAwakeningPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        io.github.verycooltimo.murim.profile.AwakeningService.start(serverPlayer);
                    }
                });

        registrar.playToServer(
                HoldFlowPayload.TYPE,
                HoldFlowPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        io.github.verycooltimo.murim.profile.AwakeningService.hold(serverPlayer);
                    }
                });

        // Медитация: клиент присылает только нажатия, время и окна считает сервер.
        registrar.playToServer(
                MeditationInputPayload.TYPE,
                MeditationInputPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        switch (payload.action()) {
                            case TOGGLE -> io.github.verycooltimo.murim.cultivation.MeditationService
                                    .toggle(serverPlayer, payload.filter());
                            case HOLD_ON -> io.github.verycooltimo.murim.cultivation.MeditationService
                                    .setHolding(serverPlayer, true);
                            case HOLD_OFF -> io.github.verycooltimo.murim.cultivation.MeditationService
                                    .setHolding(serverPlayer, false);
                        }
                    }
                });

        registrar.playToClient(
                SyncMeditationPayload.TYPE,
                SyncMeditationPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleSyncMeditation(payload));

        // Выбор основания приходит от клиента как НАМЕРЕНИЕ: фазу церемонии и то, что
        // даньтянь ещё не создан, проверяет сервер.
        registrar.playToServer(
                ChooseFoundationPayload.TYPE,
                ChooseFoundationPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        io.github.verycooltimo.murim.profile.AwakeningService.choose(
                                serverPlayer,
                                io.github.verycooltimo.murim.profile.Foundation.byId(
                                        payload.foundation()));
                    }
                });
    }

    private ModNetwork() {
    }
}
