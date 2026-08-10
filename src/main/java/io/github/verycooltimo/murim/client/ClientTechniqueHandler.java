package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.vfx.BladeTrailRenderer;
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
                playAnimation(payload);
                BladeTrailRenderer.start(payload.sourceId());
            }
            case HIT -> {
                // Hit stop только тому, кто ударил. Заморозка чужого экрана из-за попадания
                // соседа — это гриферство с обычного клиента, а не эффект.
                if (isLocalPlayer(payload.sourceId())) {
                    HitStopHandler.request(payload.hitStopTicks());
                    CameraShakeHandler.request(1.0F);
                }
            }
            case CANCELLED -> {
                // Прерванная техника не должна оставлять после себя висящий след.
                BladeTrailRenderer.cancel(payload.sourceId());
                MurimMod.LOGGER.debug("Техника {} прервана", payload.techniqueId());
            }
            case FINISHED -> MurimMod.LOGGER.debug("Техника {} завершена", payload.techniqueId());
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
