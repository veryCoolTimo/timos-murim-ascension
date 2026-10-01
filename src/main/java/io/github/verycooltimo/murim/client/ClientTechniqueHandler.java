package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.vfx.BladeTrailRenderer;
import io.github.verycooltimo.murim.client.vfx.ImpactScreenLayer;
import io.github.verycooltimo.murim.client.vfx.TechniqueNameLayer;
import io.github.verycooltimo.murim.combat.TechniquePhase;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
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

    /**
     * Техника, привязанная к клавише на этапе 1.
     *
     * <p>Временно и осознанно: единый язык управления для десятков техник — отдельное
     * решение (ADR-80), и до него клавиша запускает одну технику по идентификатору,
     * а не по зашитому описанию. Идентификатор строкой, потому что описание живёт в датапаке
     * и на клиенте появляется только после синхронизации.
     */

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
            // Применяется техника выбранного слота (кольцо выбора, автор 01.10). Пустой слот —
            // подсказка, где разложить техники, а не тишина.
            java.util.Optional<net.minecraft.resources.ResourceLocation> active = ClientLoadoutState.activeTechnique();
            if (active.isPresent()) {
                PacketDistributor.sendToServer(new StartTechniquePayload(active.get()));
                CombatMode.engage();
            } else if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "murim.loadout.empty", ModKeyMappings.LOADOUT.getTranslatedKeyMessage()), true);
            }
        }
    }

    /**
     * Реакция на серверное событие техники. Вызывается из сетевого моста уже в главном потоке.
     */
    public static void onTechniqueEvent(TechniqueEventPayload payload) {
        switch (payload.event()) {
            case STARTED -> {
                scheduleAnimation(payload);
                TechniqueDefinition started = TechniqueLoader.get(payload.techniqueId());
                // У ладони собственный набор слоёв: общая схема дуги её не описывает.
                //
                // Рендереры ВЗАИМОИСКЛЮЧАЮЩИЕ. Раньше общая дуга запускалась и для ладони
                // тоже — вопреки этому самому комментарию, — и размашистый веер накладывался
                // поверх сбора в ладони. Отсюда и «эффекты вышли за орбиты», и «ураган в
                // руке»: две разные постановки в одном кадре. Заодно это делало правки
                // ладони невидимыми для измерения — большую часть энергии давала дуга.
                boolean palm = started != null && started.behavior().type().equals(
                        io.github.verycooltimo.murim.technique.TechniqueBehavior.PALM_BLAST);
                if (palm) {
                    io.github.verycooltimo.murim.client.vfx.PalmVfxRenderer.start(
                            payload.sourceId(), started);
                } else {
                    BladeTrailRenderer.start(payload.sourceId(), started);
                }
                // Название объявляет только тот, кто применяет: чужие имена техник поверх
                // своего экрана — это шум, а не постановка.
                if (isLocalPlayer(payload.sourceId())) {
                    // Имя вспыхивает к концу ритуала, а не в его начале: на пике, как
                    // в раскадровке. Задержка берётся из данных техники, а не зашита числом.
                    TechniqueDefinition definition = TechniqueLoader.get(payload.techniqueId());
                    int ritual = definition == null ? 0 : definition.ticksOf(TechniquePhase.RITUAL);
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
                io.github.verycooltimo.murim.client.vfx.PalmVfxRenderer.cancel(payload.sourceId());
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
    private static final java.util.Map<Integer, Pending> PENDING = new java.util.HashMap<>();

    /**
     * Отложенный запуск анимации.
     *
     * <p>Хранится идентификатор анимации, а не только задержка: техник стало несколько,
     * и раньше здесь была зашита анимация выхвата — ладонь махала бы мечом.
     */
    private record Pending(int ticksLeft, net.minecraft.resources.ResourceLocation animation) {
    }

    /** Снимает отложенные запуски: смена мира не должна выстрелить анимацией в новом. */
    public static void reset() {
        PENDING.clear();
    }

    private static void scheduleAnimation(TechniqueEventPayload payload) {
        // Задержка берётся из данных техники, а не зашита числом: правится длина ритуала —
        // анимация едет следом.
        TechniqueDefinition definition = TechniqueLoader.get(payload.techniqueId());
        if (definition == null) {
            return;
        }
        int delay = definition.startTickOf(TechniquePhase.WINDUP);
        net.minecraft.resources.ResourceLocation animation = definition.animation();
        if (delay <= 0) {
            playAnimation(payload, animation);
            return;
        }
        PENDING.put(payload.sourceId(), new Pending(delay, animation));
    }

    private static void tickPending() {
        if (PENDING.isEmpty()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        var iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Pending pending = entry.getValue();
            int left = pending.ticksLeft() - 1;
            if (left > 0) {
                entry.setValue(new Pending(left, pending.animation()));
                continue;
            }
            iterator.remove();
            if (level != null
                    && level.getEntity(entry.getKey()) instanceof AbstractClientPlayer player) {
                MurimPlayerAnimations.play(player, pending.animation());
            }
        }
    }

    /**
     * Запускает анимацию у того, кто применил технику. Пакет приходит и наблюдателям, поэтому
     * анимация проигрывается у чужих игроков тоже — иначе техника была бы видна только себе.
     */
    private static void playAnimation(TechniqueEventPayload payload,
                                      net.minecraft.resources.ResourceLocation animation) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        if (level.getEntity(payload.sourceId()) instanceof AbstractClientPlayer player) {
            MurimPlayerAnimations.play(player, animation);
        }
    }

    private static boolean isLocalPlayer(int entityId) {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.getId() == entityId;
    }

    private ClientTechniqueHandler() {
    }
}
