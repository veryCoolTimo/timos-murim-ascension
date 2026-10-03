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
    private static final String VERSION = "13";

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

        // Аура существ: рисуется на клиенте и задаёт экранное давление.
        registrar.playToClient(
                SyncAuraPayload.TYPE,
                SyncAuraPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleSyncAura(payload));

        // Основа меча: клиент сообщает о взмахе формой, сервер раздаёт его наблюдателям.
        registrar.playToServer(
                FoundationPayloads.Swing.TYPE,
                FoundationPayloads.Swing.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        io.github.verycooltimo.murim.combat.FoundationService.onSwing(serverPlayer, payload.form());
                    }
                });
        registrar.playToClient(
                FoundationPayloads.Form.TYPE,
                FoundationPayloads.Form.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleFoundationForm(payload));

        // Шаги: клиент шлёт только нажатия с контекстом ввода, подтехнику выбирает сервер.
        registrar.playToServer(ManualPayloads.Learn.TYPE, ManualPayloads.Learn.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        io.github.verycooltimo.murim.item.TechniqueManualItem.learnFromHand(serverPlayer,
                                payload.mainHand() ? net.minecraft.world.InteractionHand.MAIN_HAND : net.minecraft.world.InteractionHand.OFF_HAND);
                    }
                });
        registrar.playToClient(ManualPayloads.Open.TYPE, ManualPayloads.Open.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleManual(payload));
        registrar.playToServer(LockPayload.TYPE, LockPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        io.github.verycooltimo.murim.combat.TargetLock.set(serverPlayer, payload.entityId());
                    }
                });
        registrar.playToServer(TraversePayloads.Jump.TYPE, TraversePayloads.Jump.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        io.github.verycooltimo.murim.combat.FootworkService.jump(serverPlayer, payload.grounded());
                    }
                });
        registrar.playToServer(TraversePayloads.Request.TYPE, TraversePayloads.Request.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        io.github.verycooltimo.murim.combat.FootworkService.request(serverPlayer, payload.technique(), payload.input());
                    }
                });
        registrar.playToClient(TraversePayloads.Dash.TYPE, TraversePayloads.Dash.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleDash(payload));
        registrar.playToClient(TraversePayloads.Event.TYPE, TraversePayloads.Event.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleTraverse(payload));
        registrar.playToClient(PlumSlashPayload.TYPE, PlumSlashPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handlePlumSlash(payload));
        registrar.playToClient(WhirlPayload.TYPE, WhirlPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleWhirl(payload));
        registrar.playToClient(ExecPayload.TYPE, ExecPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleExec(payload));
        registrar.playToClient(RushPayload.TYPE, RushPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleRush(payload));
        registrar.playToClient(RainPayload.TYPE, RainPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleRain(payload));
        registrar.playToClient(RiverPayload.TYPE, RiverPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleRiver(payload));
        registrar.playToClient(FallingPetalPayload.TYPE, FallingPetalPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleFallingPetal(payload));
        registrar.playToClient(StepPayload.TYPE, StepPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleStep(payload));

        // Порыв давления: тряска, волна и наклон пламени в момент толчка.
        registrar.playToClient(
                AuraGustPayload.TYPE,
                AuraGustPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleAuraGust(payload));

        // Попадание: без него клиент рисовал брызги всегда, даже когда удар прошёл мимо.
        registrar.playToClient(
                TechniqueHitPayload.TYPE,
                TechniqueHitPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleTechniqueHit(payload));

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

        // Раскладка техник: клиент присылает намерение, сервер проверяет и рассылает итог.
        registrar.playToServer(
                LoadoutPayloads.SetSlot.TYPE,
                LoadoutPayloads.SetSlot.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        io.github.verycooltimo.murim.mastery.LoadoutService.setSlot(
                                serverPlayer, payload.slot(), payload.technique());
                    }
                });
        registrar.playToServer(
                LoadoutPayloads.Select.TYPE,
                LoadoutPayloads.Select.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        io.github.verycooltimo.murim.mastery.LoadoutService.select(serverPlayer, payload.slot());
                    }
                });
        registrar.playToClient(
                LoadoutPayloads.Sync.TYPE,
                LoadoutPayloads.Sync.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleLoadout(payload));

        registrar.playToClient(
                SyncMasteryPayload.TYPE,
                SyncMasteryPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleSyncMastery(payload));

        registrar.playToClient(
                InsightPayload.TYPE,
                InsightPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleInsight(payload));

        registrar.playToClient(
                SyncMeditationPayload.TYPE,
                SyncMeditationPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleSyncMeditation(payload));
    }

    private ModNetwork() {
    }
}
