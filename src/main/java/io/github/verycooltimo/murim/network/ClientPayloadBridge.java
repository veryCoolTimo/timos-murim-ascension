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
        io.github.verycooltimo.murim.client.vfx.PlumVfx.onTechniqueEvent(payload);
        io.github.verycooltimo.murim.client.vfx.WhirlVfx.onTechniqueEvent(payload);
        io.github.verycooltimo.murim.client.vfx.ExecVfx.onTechniqueEvent(payload);
        io.github.verycooltimo.murim.client.vfx.RushVfx.onTechniqueEvent(payload);
        io.github.verycooltimo.murim.client.vfx.RainVfx.onTechniqueEvent(payload);
        io.github.verycooltimo.murim.client.vfx.RiverVfx.onTechniqueEvent(payload);
        io.github.verycooltimo.murim.client.vfx.ScatterVfx.onTechniqueEvent(payload);
        io.github.verycooltimo.murim.client.vfx.DomeVfx.onTechniqueEvent(payload);
        io.github.verycooltimo.murim.client.vfx.FallingPetalVfx.onTechniqueEvent(payload);
        io.github.verycooltimo.murim.client.vfx.TangVfx.onTechniqueEvent(payload);
        io.github.verycooltimo.murim.client.vfx.ShowerVfx.onTechniqueEvent(payload);
        io.github.verycooltimo.murim.client.vfx.ExplosionVfx.onTechniqueEvent(payload);
    }

    public static void handleExplosion(ExplosionPayload payload) {
        io.github.verycooltimo.murim.client.vfx.ExplosionVfx.onExplosion(payload);
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

    public static void handleDome(DomePayload payload) {
        io.github.verycooltimo.murim.client.vfx.DomeVfx.onDome(payload);
    }

    /**
     * Принимает описания техник от сервера. На клиенте они кладутся в тот же реестр,
     * что на сервере наполняет загрузчик датапака, — рендер не должен знать,
     * откуда данные пришли.
     */
    public static void handleSyncTechniques(SyncTechniquesPayload payload) {
        io.github.verycooltimo.murim.technique.TechniqueLoader.replaceAll(payload.definitions());
    }

    /** Профиль владельца — для интерфейса. */
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

    public static void handleFallingPetal(FallingPetalPayload payload) {
        io.github.verycooltimo.murim.client.vfx.FallingPetalVfx.onPayload(payload);
    }

    public static void handleExec(ExecPayload payload) {
        io.github.verycooltimo.murim.client.vfx.ExecVfx.onExec(payload);
    }

    public static void handleWhirl(WhirlPayload payload) {
        io.github.verycooltimo.murim.client.vfx.WhirlVfx.onWhirl(payload);
    }

    public static void handleDash(TraversePayloads.Dash payload) {
        io.github.verycooltimo.murim.client.FootworkMotion.start(payload);
    }

    public static void handleTraverse(TraversePayloads.Event payload) {
        io.github.verycooltimo.murim.client.vfx.TraverseVfx.onEvent(payload);
    }

    public static void handleStep(StepPayload payload) {
        io.github.verycooltimo.murim.client.vfx.FootworkVfx.petalStep(payload);
    }

    public static void handleFoundationForm(FoundationPayloads.Form payload) {
        io.github.verycooltimo.murim.client.FoundationClient.onRemoteForm(payload);
    }

    public static void handleAuraGust(AuraGustPayload payload) {
        io.github.verycooltimo.murim.client.ClientAuraState.gust(payload.sourceId(), payload.victimId(),
                payload.strength(), payload.pull());
    }

    public static void handleSyncAura(SyncAuraPayload payload) {
        io.github.verycooltimo.murim.client.ClientAuraState.set(payload.entityId(), payload.aura());
    }

    /** Попадание техники: брызги возникают в точке контакта, а не из воздуха. */
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

    /** Озарение: вспышка в бою или чистый приём двойника в медитации. */
    public static void handleInsight(InsightPayload payload) {
        io.github.verycooltimo.murim.client.InsightEffects.onInsight(payload);
    }

    /** Медитация: поза, подсказка удержания, момент рождения семени. */
    public static void handlePill(PillPayloads.Sync payload) {
        io.github.verycooltimo.murim.client.ClientPillState.accept(payload);
    }

    public static void handleSyncMeditation(SyncMeditationPayload payload) {
        io.github.verycooltimo.murim.client.ClientMeditationState.accept(payload);
    }

    private ClientPayloadBridge() {
    }
}
