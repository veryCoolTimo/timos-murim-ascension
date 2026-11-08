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

    static void handleHeadIntro(HeadIntroPayloads.View payload) {
        if (FMLEnvironment.dist == Dist.CLIENT)
            io.github.verycooltimo.murim.client.sect.HeadIntroCamera.accept(payload);
    }

    static void handleSwordGuard(SwordGuardPayloads.View payload) {
        if (FMLEnvironment.dist == Dist.CLIENT)
            io.github.verycooltimo.murim.client.SwordGuardClient.accept(payload);
    }

    static void handleTechniqueEvent(TechniqueEventPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        if (payload.event() == TechniqueEventPayload.Event.COOLDOWN_RESET) {
            io.github.verycooltimo.murim.client.ClientTechniqueHandler.onTechniqueEvent(payload); return;
        }
        io.github.verycooltimo.murim.client.vfx.BambooVfx.technique(payload);
        io.github.verycooltimo.murim.client.SwordGuardClient.technique(payload);
        io.github.verycooltimo.murim.client.ClientTechniqueHandler.onTechniqueEvent(payload);
        io.github.verycooltimo.murim.client.vfx.PlumVfx.onTechniqueEvent(payload);
        io.github.verycooltimo.murim.client.vfx.WhirlVfx.onTechniqueEvent(payload);
        io.github.verycooltimo.murim.client.vfx.ExecVfx.onTechniqueEvent(payload);
        io.github.verycooltimo.murim.client.vfx.RushVfx.onTechniqueEvent(payload);
        io.github.verycooltimo.murim.client.vfx.RainVfx.onTechniqueEvent(payload);
        io.github.verycooltimo.murim.client.vfx.RiverVfx.onTechniqueEvent(payload);
        io.github.verycooltimo.murim.client.vfx.ScatterVfx.onTechniqueEvent(payload);
        io.github.verycooltimo.murim.client.vfx.DomeVfx.onTechniqueEvent(payload);
        io.github.verycooltimo.murim.client.vfx.SeaVfx.onTechniqueEvent(payload);
        io.github.verycooltimo.murim.client.vfx.FullBloomVfx.onTechniqueEvent(payload);
        io.github.verycooltimo.murim.client.vfx.FallingPetalVfx.onTechniqueEvent(payload);
        io.github.verycooltimo.murim.client.vfx.TangVfx.onTechniqueEvent(payload);
        io.github.verycooltimo.murim.client.vfx.ShowerVfx.onTechniqueEvent(payload);
        io.github.verycooltimo.murim.client.vfx.ExplosionVfx.onTechniqueEvent(payload);
    }

    public static void handleMountHua(MountHuaSitePayload payload) {
        io.github.verycooltimo.murim.client.vfx.HuaMist.onSite(payload);
    }

    public static void handleExplosion(ExplosionPayload payload) {
        io.github.verycooltimo.murim.client.vfx.ExplosionVfx.onExplosion(payload);
    }

    public static void handleDialogue(DialoguePayloads.Open payload) {
        io.github.verycooltimo.murim.client.sect.DialogueScreen.open(payload);
    }

    public static void handleSectGoal(SectGoalPayload payload) {
        io.github.verycooltimo.murim.client.sect.SectJourneyHud.accept(payload);
    }

    public static void handleSectGuide(SectGuidePayload payload) {
        io.github.verycooltimo.murim.client.sect.SectGuideHud.accept(payload);
    }

    public static void handleSectTablet(SectTabletPayload.Open payload) {
        io.github.verycooltimo.murim.client.sect.SectTabletScreen.open(payload.data());
    }

    public static void handleSectBoard(SectTabletPayload.OpenBoard payload) {
        io.github.verycooltimo.murim.client.sect.SectTabletScreen.openBoard(payload.data());
    }

    public static void handleSectGoalPulse() {
        io.github.verycooltimo.murim.client.sect.SectJourneyHud.pulse();
    }

    public static void handleSectMap(SectMapPayload payload) {
        io.github.verycooltimo.murim.client.sect.SectMapClient.accept(payload);
    }

    public static void handleSectTabletUpdate(SectTabletPayload.Update payload) {
        io.github.verycooltimo.murim.client.sect.SectTabletScreen.update(payload.data());
    }

    public static void handleSectBubble(SectBubblePayload payload) {
        io.github.verycooltimo.murim.client.sect.SpeechBubbles.onBubble(payload);
    }

    public static void handleDialogueClose() {
        io.github.verycooltimo.murim.client.sect.DialogueScreen.closeFromServer();
    }

    public static void handleManual(ManualPayloads.Open payload) {
        // Только статический вызов: new ManualScreen здесь заставил бы сервер грузить клиентский Screen.
        io.github.verycooltimo.murim.client.ManualScreen.open(payload);
    }

    public static void handleShower(ShowerPayload payload) {
        io.github.verycooltimo.murim.client.vfx.ShowerVfx.onShower(payload);
    }

    public static void handleRain(RainPayload payload) {
        io.github.verycooltimo.murim.client.vfx.RainVfx.onRain(payload);
    }

    public static void handleRiver(RiverPayload payload) {
        io.github.verycooltimo.murim.client.vfx.RiverVfx.onRiver(payload);
    }

    public static void handleScatter(ScatterPayload payload) {
        io.github.verycooltimo.murim.client.vfx.ScatterVfx.onScatter(payload);
    }

    public static void handleBloom(BloomPayload payload) {
        io.github.verycooltimo.murim.client.vfx.FullBloomVfx.accept(payload);
    }

    public static void handleDome(DomePayload payload) {
        io.github.verycooltimo.murim.client.vfx.DomeVfx.onDome(payload);
    }

    public static void handleSea(SeaPayload payload) {
        io.github.verycooltimo.murim.client.vfx.SeaVfx.onSea(payload);
    }

    /**
     * Принимает описания техник от сервера. На клиенте они кладутся в тот же реестр,
     * что на сервере наполняет загрузчик датапака, — рендер не должен знать,
     * откуда данные пришли.
     */
    public static void handleSyncTechniques(SyncTechniquesPayload payload) {
        io.github.verycooltimo.murim.technique.TechniqueLoader.replaceAll(payload.definitions());
    }

    public static void handleSyncProfile(SyncProfilePayload payload) {
        io.github.verycooltimo.murim.client.ClientProfileState.setProfile(payload.profile());
    }

    public static void handlePlumSlash(PlumSlashPayload payload) {
        io.github.verycooltimo.murim.client.vfx.PlumVfx.onSlash(payload);
    }

    public static void handleRush(RushPayload payload) {
        io.github.verycooltimo.murim.client.vfx.RushVfx.onRush(payload);
    }

    public static void handleTang(TangPayload payload) {
        io.github.verycooltimo.murim.client.vfx.TangVfx.onTang(payload);
    }

    public static void handleBamboo(BambooPayload payload) {
        if (FMLEnvironment.dist == Dist.CLIENT) io.github.verycooltimo.murim.client.vfx.BambooVfx.accept(payload);
    }

    public static void handleSixForm(SixFormPayload payload) {
        if (FMLEnvironment.dist == Dist.CLIENT) io.github.verycooltimo.murim.client.vfx.SixFormVfx.accept(payload);
    }

    public static void handleFallingPetal(FallingPetalPayload payload) {
        io.github.verycooltimo.murim.client.vfx.FallingPetalVfx.onPayload(payload);
    }

    public static void handleExec(ExecPayload payload) {
        io.github.verycooltimo.murim.client.vfx.ExecVfx.onExec(payload);
    }

    public static void handleWhirl(WhirlPayload payload) {
        io.github.verycooltimo.murim.client.vfx.WhirlVfx.onWhirl(payload);
    }

    public static void handleHeldFacing(TraversePayloads.HeldFacing payload) {
        io.github.verycooltimo.murim.client.FootworkMotion.holdFacing(payload);
    }

    public static void handleSevenRoute(TraversePayloads.SevenRoute payload) {
        io.github.verycooltimo.murim.client.FootworkMotion.acceptRoute(payload);
        if (payload.actor() >= 0) io.github.verycooltimo.murim.client.vfx.FootworkVfx.behindRoute(payload);
    }

    public static void handleDash(TraversePayloads.Dash payload) {
        io.github.verycooltimo.murim.client.FootworkMotion.start(payload);
    }

    public static void handleTraverse(TraversePayloads.Event payload) {
        io.github.verycooltimo.murim.client.FootworkMotion.runAnimation(payload);
        io.github.verycooltimo.murim.client.vfx.TraverseVfx.onEvent(payload);
    }

    public static void handleStep(StepPayload payload) {
        io.github.verycooltimo.murim.client.vfx.FootworkVfx.petalStep(payload);
    }

    public static void handleFoundationForm(FoundationPayloads.Form payload) {
        io.github.verycooltimo.murim.client.FoundationClient.onRemoteForm(payload);
    }

    public static void handleFoundationHit(FoundationPayloads.Hit payload) {
        io.github.verycooltimo.murim.client.vfx.FoundationVfx.confirmHit(payload);
    }

    public static void handleQiSwordHit(QiSwordHitPayload payload) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level != null && level.getEntity(payload.sourceId()) instanceof net.minecraft.world.entity.player.Player player) {
            io.github.verycooltimo.murim.client.QiSwordClient.mark(player);
        }
    }

    public static void handlePenance(PenancePayload payload) {
        io.github.verycooltimo.murim.client.ClientPenanceState.set(payload.pinned(), payload.sourceId());
    }

    public static void handleAuraGust(AuraGustPayload payload) {
        io.github.verycooltimo.murim.client.ClientAuraState.gust(payload.sourceId(), payload.victimId(),
                payload.strength(), payload.pull());
    }

    public static void handleBeltSword(io.github.verycooltimo.murim.gear.BeltSwordPayload payload) {
        io.github.verycooltimo.murim.client.gear.ClientBeltSwords.set(payload.entityId(), payload.stack());
    }

    public static void handleSyncAura(SyncAuraPayload payload) {
        io.github.verycooltimo.murim.client.ClientAuraState.set(payload.entityId(), payload.aura());
    }

    public static void handleTechniqueHit(TechniqueHitPayload payload) {
        io.github.verycooltimo.murim.client.vfx.PalmVfxRenderer.recordHit(
                payload.sourceId(), payload.x(), payload.y(), payload.z(), payload.height());
    }

    /** Раскладка техник по слотам — для кольца, слотов боя и экрана раскладки. */
    public static void handleLoadout(LoadoutPayloads.Sync payload) {
        io.github.verycooltimo.murim.client.ClientLoadoutState.accept(payload);
    }

    /** Освоение техник — для двойника медитации и будущего списка техник. */
    public static void handleSyncMastery(SyncMasteryPayload payload) {
        io.github.verycooltimo.murim.client.ClientMasteryState.accept(payload);
    }

    /** The server sees a fight the client cannot (sect bout or duel, a mob hunting the player). */
    public static void handleCombatPulse() {
        io.github.verycooltimo.murim.client.CombatMode.pulse();
    }

    /** Озарение: вспышка в бою или чистый приём двойника в медитации. */
    public static void handleInsight(InsightPayload payload) {
        io.github.verycooltimo.murim.client.InsightEffects.onInsight(payload);
    }

    /** Медитация: поза, подсказка удержания, момент рождения семени. */
    public static void handlePill(PillPayloads.Sync payload) {
        io.github.verycooltimo.murim.client.ClientPillState.accept(payload);
    }

    /** The game «Провести поток» begins: the stream's seed and pills. */
    public static void handlePillFlow(PillPayloads.FlowStart payload) {
        io.github.verycooltimo.murim.client.flow.FlowClient.start(payload);
    }

    public static void handleSyncMeditation(SyncMeditationPayload payload) {
        io.github.verycooltimo.murim.client.ClientMeditationState.accept(payload);
    }

    private ClientPayloadBridge() {
    }
}
