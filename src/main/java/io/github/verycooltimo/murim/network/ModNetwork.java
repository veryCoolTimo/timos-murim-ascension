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
    private static final String VERSION = "56"; // Mastery stars in TechniqueEvent (Bamboo + 24 Plum looks) and clocked footwork routes (06.11)

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.payloads(registrar);
        registrar.playToServer(SwordGuardPayloads.Input.TYPE, SwordGuardPayloads.Input.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player)
                        io.github.verycooltimo.murim.combat.SwordGuard.input(player, payload.action());
                });
        registrar.playToClient(SwordGuardPayloads.View.TYPE, SwordGuardPayloads.View.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleSwordGuard(payload));
        // Ruined library: «Попробовать» on a heretical junk method (docs/design/25-ruined-library.md).
        io.github.verycooltimo.murim.library.LibraryNetwork.register(registrar);
        // Body training (docs/design/27-body-training.md): server → client state and body.
        io.github.verycooltimo.murim.training.TrainingNetwork.register(registrar);

        registrar.playToServer(
                StartTechniquePayload.TYPE,
                StartTechniquePayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        if (payload.release()) io.github.verycooltimo.murim.technique.BambooExecutor.release(serverPlayer);
                        else TechniqueService.tryStart(serverPlayer, payload.techniqueId(), payload.side());
                    }
                });

        registrar.playToClient(
                TechniqueEventPayload.TYPE,
                TechniqueEventPayload.STREAM_CODEC,
                ClientPayloadBridge::handleTechniqueEvent);

        registrar.playToClient(FoundationPayloads.Hit.TYPE, FoundationPayloads.Hit.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleFoundationHit(payload));

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
        // Belt sword (gear/BeltSword): other players' inventories are not synced, the belt is.
        registrar.playToClient(io.github.verycooltimo.murim.gear.BeltSwordPayload.TYPE,
                io.github.verycooltimo.murim.gear.BeltSwordPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleBeltSword(payload));

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
                        io.github.verycooltimo.murim.combat.SwordBasicAttack.input(serverPlayer, payload.form(), payload.targetId());
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
                                payload.mainHand() ? net.minecraft.world.InteractionHand.MAIN_HAND : net.minecraft.world.InteractionHand.OFF_HAND,
                                payload.technique());
                    }
                });
        registrar.playToClient(ManualPayloads.Open.TYPE, ManualPayloads.Open.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleManual(payload));
        registrar.playToClient(HeadIntroPayloads.View.TYPE, HeadIntroPayloads.View.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleHeadIntro(payload));
        registrar.playToServer(HeadIntroPayloads.Skip.TYPE, HeadIntroPayloads.Skip.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer p)
                        io.github.verycooltimo.murim.sect.SectHeadIntro.skip(p, payload.started());
                });
        // Диалоги NPC секты: сервер выбирает реплику, клиент показывает и шлёт номер варианта.
        registrar.playToClient(DialoguePayloads.Open.TYPE, DialoguePayloads.Open.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleDialogue(payload));
        registrar.playToClient(DialoguePayloads.Close.TYPE, DialoguePayloads.Close.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleDialogueClose());
        // Реплики над головой людей горы (живая гора, автор 05.10): сервер выбирает строку, клиент рисует пузырь.
        registrar.playToClient(SectGoalPayload.TYPE, SectGoalPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleSectGoal(payload));
        registrar.playToClient(SectGuidePayload.TYPE, SectGuidePayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleSectGuide(payload));
        registrar.playToClient(SectTabletPayload.Open.TYPE, SectTabletPayload.Open.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleSectTablet(payload));
        registrar.playToClient(SectMapPayload.TYPE, SectMapPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleSectMap(payload));
        registrar.playToClient(SectTabletPayload.Update.TYPE, SectTabletPayload.Update.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleSectTabletUpdate(payload));
        registrar.playToServer(SectTabletPayload.Act.TYPE, SectTabletPayload.Act.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        io.github.verycooltimo.murim.sect.SectTablet.act(serverPlayer, payload.action());
                    }
                });
        registrar.playToClient(SectTabletPayload.OpenBoard.TYPE, SectTabletPayload.OpenBoard.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleSectBoard(payload));
        registrar.playToServer(SectTabletPayload.Contract.TYPE, SectTabletPayload.Contract.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        io.github.verycooltimo.murim.sect.SectContracts.act(serverPlayer, payload.op(), payload.slot(), payload.target());
                    }
                });
        registrar.playToClient(SectGoalPulsePayload.TYPE, SectGoalPulsePayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleSectGoalPulse());
        registrar.playToClient(SectBubblePayload.TYPE, SectBubblePayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleSectBubble(payload));
        registrar.playToServer(DialoguePayloads.Choose.TYPE, DialoguePayloads.Choose.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        io.github.verycooltimo.murim.sect.DialogueService.choose(serverPlayer, payload.index());
                    }
                });
        // Пилюли (docs/design/19b; 25.10): the server sends the seed of the stream, the client plays and reports how it ended,
        // the server works the quality out itself and applies the qi.
        registrar.playToClient(PillPayloads.Sync.TYPE, PillPayloads.Sync.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handlePill(payload));
        registrar.playToClient(PillPayloads.FlowStart.TYPE, PillPayloads.FlowStart.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handlePillFlow(payload));
        registrar.playToServer(PillPayloads.FlowReport.TYPE, PillPayloads.FlowReport.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        PillPayloads.Outcome[] all = PillPayloads.Outcome.values();
                        io.github.verycooltimo.murim.cultivation.PillService.report(serverPlayer, payload.id(),
                                all[Math.max(0, Math.min(all.length - 1, payload.outcome()))], payload.progress(), payload.lost());
                    }
                });
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
                        io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.footworkRequest(serverPlayer, payload.technique(), payload.input());
                    }
                });
        // API: /tmp/mr-ref-1121/neoforge-src/network/registration/PayloadRegistrar.java#playToClient.
        registrar.playToClient(TraversePayloads.HeldFacing.TYPE, TraversePayloads.HeldFacing.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleHeldFacing(payload));
        registrar.playToClient(TraversePayloads.SevenRoute.TYPE, TraversePayloads.SevenRoute.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleSevenRoute(payload));
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
        registrar.playToClient(ExplosionPayload.TYPE, ExplosionPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleExplosion(payload));
        registrar.playToClient(RiverPayload.TYPE, RiverPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleRiver(payload));
        registrar.playToClient(ScatterPayload.TYPE, ScatterPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleScatter(payload));
        registrar.playToClient(BloomPayload.TYPE, BloomPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleBloom(payload));
        registrar.playToClient(DomePayload.TYPE, DomePayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleDome(payload));
        registrar.playToClient(SeaPayload.TYPE, SeaPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleSea(payload));
        // Море Цветущей Сливы: R зажат — намерение продлить, решает сервер.
        registrar.playToServer(SeaHoldPayload.TYPE, SeaHoldPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        io.github.verycooltimo.murim.technique.SeaExecutor.hold(serverPlayer, payload.held());
                        io.github.verycooltimo.murim.technique.FullBloomExecutor.hold(serverPlayer, payload.held());
                    }
                });
        registrar.playToClient(TangPayload.TYPE, TangPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleTang(payload));
        registrar.playToClient(BambooPayload.TYPE, BambooPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleBamboo(payload));
        registrar.playToClient(SixFormPayload.TYPE, SixFormPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleSixForm(payload));
        registrar.playToClient(FallingPetalPayload.TYPE, FallingPetalPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleFallingPetal(payload));
        registrar.playToClient(ShowerPayload.TYPE, ShowerPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleShower(payload));
        registrar.playToClient(StepPayload.TYPE, StepPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleStep(payload));

        registrar.playToClient(PenancePayload.TYPE, PenancePayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handlePenance(payload));

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
                            case BLOCKED_ACTION -> io.github.verycooltimo.murim.cultivation.MeditationService
                                    .blockedAction(serverPlayer);
                            case HOLD_OFF -> io.github.verycooltimo.murim.cultivation.MeditationService
                                    .setHolding(serverPlayer, false);
                        }
                    }
                });

        // The pill box in an inventory screen: the client names the slot, the server opens the pouch of the real stack.
        registrar.playToServer(
                PillBoxOpenPayload.TYPE,
                PillBoxOpenPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        io.github.verycooltimo.murim.decor.PillBoxItem.open(serverPlayer, payload.inventorySlot());
                    }
                });

        // Раскладка техник: клиент присылает намерение, сервер проверяет и рассылает итог.
        registrar.playToServer(LoadoutPayloads.SelectForm.TYPE, LoadoutPayloads.SelectForm.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        io.github.verycooltimo.murim.mastery.LoadoutService.selectForm(
                                player, payload.slot(), payload.technique(), payload.activate());
                    }
                });
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

        registrar.playToClient(QiSwordHitPayload.TYPE, QiSwordHitPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleQiSwordHit(payload));

        registrar.playToClient(
                SyncMasteryPayload.TYPE,
                SyncMasteryPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleSyncMastery(payload));

        // Combat HUD: what only the server knows (sect bouts and duels, a mob hunting the player).
        registrar.playToClient(CombatPulsePayload.TYPE, CombatPulsePayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleCombatPulse());

        registrar.playToClient(
                InsightPayload.TYPE,
                InsightPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleInsight(payload));

        registrar.playToClient(
                SyncMeditationPayload.TYPE,
                SyncMeditationPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleSyncMeditation(payload));

        registrar.playToClient(
                MountHuaSitePayload.TYPE,
                MountHuaSitePayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadBridge.handleMountHua(payload));
    }

    private ModNetwork() {
    }
}
