package io.github.verycooltimo.murim.profile;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Жизненный цикл профиля и ритуала на сервере.
 *
 * <p>Восстановление циркулирующей ци идёт ТОЛЬКО в ритуале. Пассивная регенерация свела бы
 * всю систему к ожиданию: зачем садиться, если и так натечёт.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class ProfileEvents {

    /** Как часто состояние ритуала уезжает на клиент. Каждый тик — избыточно для полосы. */
    private static final int SYNC_EVERY_TICKS = 4;

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // Церемония создания даньтяня тикает ПЕРЕД циркуляцией и исключает её: две сцены
        // одновременно не бывает, а её собственный tick сам решает, когда закончиться.
        if (player.getData(ModAttachments.AWAKENING).active()) {
            try {
                AwakeningService.tick(player);
            } catch (RuntimeException exception) {
                MurimMod.LOGGER.error("Ошибка в тике церемонии у {}, церемония прервана",
                        player.getGameProfile().getName(), exception);
                AwakeningService.interrupt(player, "murim.awakening.broken.error");
            }
            return;
        }
        RitualState state = player.getData(ModAttachments.RITUAL);
        if (!state.active()) {
            return;
        }
        // Исключение в тике летит из Player#tick внутри guardEntityTick и превращается
        // в краш-репорт сервера. Порченый профиль из старого сейва или ошибка в формуле
        // должны прерывать медитацию, а не ронять мир.
        boolean alive;
        try {
            alive = RitualService.tick(player);
        } catch (RuntimeException exception) {
            MurimMod.LOGGER.error("Ошибка в тике ритуала у {}, медитация прервана",
                    player.getGameProfile().getName(), exception);
            player.setData(ModAttachments.RITUAL, RitualState.IDLE);
            alive = false;
        }
        if (!alive || player.tickCount % SYNC_EVERY_TICKS == 0) {
            ProfileNetwork.syncRitual(player);
        }
    }

    /**
     * Урон срывает медитацию — но только настоящий.
     *
     * <p>Приоритет низший и проверка отмены обязательны: событие приходит ДО щита, до кадров
     * неуязвимости и до брони. Без них ритуал срывался бы от полностью заблокированного удара,
     * от урона в кадрах неуязвимости и даже от снежка с нулевым уроном.
     */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.isCanceled() || event.getAmount() <= 0.0F) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (player.getData(ModAttachments.AWAKENING).active()) {
            AwakeningService.onDamage(player, event.getAmount());
            return;
        }
        if (player.getData(ModAttachments.RITUAL).active()) {
            RitualService.stop(player, false);
            ProfileNetwork.syncRitual(player);
        }
    }

    /**
     * Смена измерения: клиент пересоздаёт игрока и сбрасывает своё зеркало профиля,
     * а сервер без этой подписки ничего не присылал — интерфейс показывал нули.
     */
    @SubscribeEvent
    static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ProfileNetwork.sync(player);
            ProfileNetwork.syncRitual(player);
        }
    }

    @SubscribeEvent
    static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ProfileNetwork.sync(player);
        }
    }

    @SubscribeEvent
    static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Профиль переживает смерть, но клиент после респавна о нём не знает.
            ProfileNetwork.sync(player);
        }
    }

    private ProfileEvents() {
    }
}
