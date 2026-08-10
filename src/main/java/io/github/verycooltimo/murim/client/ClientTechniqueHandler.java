package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.vfx.BladeTrailRenderer;
import io.github.verycooltimo.murim.client.vfx.ImpactScreenLayer;
import io.github.verycooltimo.murim.client.vfx.TechniqueNameLayer;
import io.github.verycooltimo.murim.combat.TechniquePhase;
import io.github.verycooltimo.murim.combat.Techniques;
import io.github.verycooltimo.murim.network.StartTechniquePayload;
import io.github.verycooltimo.murim.network.TechniqueEventPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Клиентская сторона техники: чтение клавиши и реакция на события от сервера.
 *
 * <p>Здесь нет ни одного игрового решения. Нажатие превращается в запрос к серверу, а вся картинка
 * рисуется по ответу — правило 03.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class ClientTechniqueHandler {

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }

        // consumeClick вычитывает накопленные нажатия. Вычерпываем очередь полностью, но
        // отправляем не более одного запроса за тик: иначе при лагах уходит пачка пакетов,
        // из которых сервер всё равно примет первый — остальные упрутся в кулдаун.
        tickPending();

        boolean pressed = false;
        while (ModKeyMappings.TECHNIQUE.consumeClick()) {
            pressed = true;
        }
        if (pressed) {
            PacketDistributor.sendToServer(new StartTechniquePayload(Techniques.CEREMONIAL_DRAW.id()));
        }
    }

    /**
     * Реакция на серверное событие техники. Вызывается из сетевого моста уже в главном потоке.
     */
    public static void onTechniqueEvent(TechniqueEventPayload payload) {
        switch (payload.event()) {
            case STARTED -> {
                scheduleAnimation(payload);
                BladeTrailRenderer.start(payload.sourceId());
                // Название объявляет только тот, кто применяет: чужие имена техник поверх
                // своего экрана — это шум, а не постановка.
                if (isLocalPlayer(payload.sourceId())) {
                    // Имя вспыхивает к концу ритуала, а не в его начале: на пике, как
                    // в раскадровке. Задержка берётся из данных техники, а не зашита числом.
                    int ritual = Techniques.CEREMONIAL_DRAW.ticksOf(
                            io.github.verycooltimo.murim.combat.TechniquePhase.RITUAL);
                    TechniqueNameLayer.show(net.minecraft.network.chat.Component.translatable(
                            "technique." + payload.techniqueId().getNamespace()
                                    + "." + payload.techniqueId().getPath()),
                            Math.max(0, ritual - 12));
                }
            }
            case HIT -> {
                // Hit stop только тому, кто ударил. Заморозка чужого экрана из-за попадания
                // соседа — это гриферство с обычного клиента, а не эффект.
                if (isLocalPlayer(payload.sourceId())) {
                    HitStopHandler.request(payload.hitStopTicks());
                    CameraShakeHandler.request(1.0F);
                    ImpactScreenLayer.trigger();
                }
            }
            case CANCELLED -> {
                // Прерванная техника не должна оставлять после себя ни висящий след,
                // ни отложенный взмах, который выстрелит уже после отмены.
                PENDING.remove(payload.sourceId());
                BladeTrailRenderer.cancel(payload.sourceId());
                MurimMod.LOGGER.debug("Техника {} прервана", payload.techniqueId());
            }
            case FINISHED -> MurimMod.LOGGER.debug("Техника {} завершена", payload.techniqueId());
        }
    }

    /**
     * Отложенные запуски анимации: идентификатор сущности и сколько тиков ждать.
     *
     * <p>Анимация тела обязана начинаться на замахе, а не на старте техники. Событие
     * {@code STARTED} приходит в первый тик, но перед замахом идут три секунды ритуала,
     * и запуск по событию проигрывал взмах во время концентрации, задолго до удара.
     * Поймано на кадрах 2026-08-10: на пятом тике персонаж уже махал мечом.
     */
    private static final java.util.Map<Integer, Integer> PENDING = new java.util.HashMap<>();

    /** Снимает отложенные запуски: смена мира не должна выстрелить анимацией в новом. */
    public static void reset() {
        PENDING.clear();
    }

    private static void scheduleAnimation(TechniqueEventPayload payload) {
        // Задержка берётся из данных техники, а не зашита числом: правится длина ритуала —
        // анимация едет следом.
        int delay = Techniques.CEREMONIAL_DRAW.startTickOf(TechniquePhase.WINDUP);
        if (delay <= 0) {
            playAnimation(payload);
            return;
        }
        PENDING.put(payload.sourceId(), delay);
    }

    private static void tickPending() {
        if (PENDING.isEmpty()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        var iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            int left = entry.getValue() - 1;
            if (left > 0) {
                entry.setValue(left);
                continue;
            }
            iterator.remove();
            if (level != null
                    && level.getEntity(entry.getKey()) instanceof AbstractClientPlayer player) {
                MurimPlayerAnimations.play(player, MurimPlayerAnimations.CEREMONIAL_DRAW);
            }
        }
    }

    /**
     * Запускает анимацию у того, кто применил технику. Пакет приходит и наблюдателям, поэтому
     * анимация проигрывается у чужих игроков тоже — иначе техника была бы видна только себе.
     */
    private static void playAnimation(TechniqueEventPayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        if (level.getEntity(payload.sourceId()) instanceof AbstractClientPlayer player) {
            MurimPlayerAnimations.play(player, MurimPlayerAnimations.CEREMONIAL_DRAW);
        }
    }

    private static boolean isLocalPlayer(int entityId) {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.getId() == entityId;
    }

    private ClientTechniqueHandler() {
    }
}
