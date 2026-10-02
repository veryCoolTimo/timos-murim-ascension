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

    public static void handleStep(StepPayload payload) {
        io.github.verycooltimo.murim.client.vfx.StepVfx.start(payload);
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
    public static void handleSyncMeditation(SyncMeditationPayload payload) {
        io.github.verycooltimo.murim.client.ClientMeditationState.accept(payload);
    }

    private ClientPayloadBridge() {
    }
}
