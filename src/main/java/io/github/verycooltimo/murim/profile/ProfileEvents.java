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
 * Жизненный цикл профиля и медитации на сервере.
 *
 * <p>Восстановление циркулирующей ци: пассивное (как мана) плюс ускоренное в медитации —
 * решение автора 2026-09-28, docs/design/19 §2.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class ProfileEvents {

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // Медитация (docs/design/19) исключает пассивное восстановление: у неё своё, быстрее.
        // Ошибка в её тике прерывает медитацию, а не роняет мир краш-репортом.
        if (player.getData(ModAttachments.MEDITATION).active()) {
            try {
                io.github.verycooltimo.murim.cultivation.MeditationService.tick(player);
            } catch (RuntimeException exception) {
                MurimMod.LOGGER.error("Ошибка в тике медитации у {}, медитация прервана",
                        player.getGameProfile().getName(), exception);
                io.github.verycooltimo.murim.cultivation.MeditationService.stop(player, null);
            }
            return;
        }
        passiveCirculation(player);
    }

    /**
     * Урон срывает медитацию — но только настоящий.
     *
     * <p>Приоритет низший и проверка отмены обязательны: событие приходит ДО щита, до кадров
     * неуязвимости и до брони. Без них медитация срывалась бы от полностью заблокированного удара,
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
        if (player.getData(ModAttachments.MEDITATION).active()) {
            io.github.verycooltimo.murim.cultivation.MeditationService.stop(player,
                    "murim.meditation.broken.hurt");
        }
    }

    /**
     * Пассивное восстановление циркулирующей ци из запаса.
     *
     * <p>Решение автора 2026-09-28 (docs/design/19 §2): боевая ци восстанавливается сама,
     * как мана, от пустой до полной примерно за 40–60 секунд; медитация и клавиша сбора
     * быстрее. Прежнее правило «только в ритуале» отменено.
     */
    private static void passiveCirculation(ServerPlayer player) {
        if (player.tickCount % PASSIVE_EVERY_TICKS != 0) {
            return;
        }
        DantianProfile profile = player.getData(ModAttachments.PROFILE);
        if (!profile.isAwakened() || profile.circulating() >= profile.maxCirculating()) {
            return;
        }
        DantianProfile updated = profile.circulateOnce();
        if (updated != profile) {
            player.setData(ModAttachments.PROFILE, updated);
            if (player.tickCount % SYNC_PASSIVE_TICKS == 0 || updated.circulating() >= updated.maxCirculating()) {
                ProfileNetwork.sync(player);
            }
        }
    }

    /** Пассивный перелив раз в два тика: при скорости каналов ~0,06 за шаг полный центр ~40–60 с. */
    private static final int PASSIVE_EVERY_TICKS = 2;

    /** Как часто пассивный прирост уезжает на клиент. */
    private static final int SYNC_PASSIVE_TICKS = 20;

    /**
     * Смена измерения: клиент пересоздаёт игрока и сбрасывает своё зеркало профиля,
     * а сервер без этой подписки ничего не присылал — интерфейс показывал нули.
     */
    @SubscribeEvent
    static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ProfileNetwork.sync(player);
            io.github.verycooltimo.murim.cultivation.MeditationService.sync(player,
                    io.github.verycooltimo.murim.network.SyncMeditationPayload.Event.NONE);
        }
    }

    @SubscribeEvent
    static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ProfileNetwork.sync(player);
            io.github.verycooltimo.murim.mastery.MasteryService.completeStyles(player);
            io.github.verycooltimo.murim.mastery.MasteryService.sync(player);
            // Сердца ранга — временный модификатор: ставится заново при каждом входе.
            io.github.verycooltimo.murim.cultivation.RankEffects.apply(player);
        }
    }

    @SubscribeEvent
    static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Профиль переживает смерть, но клиент после респавна о нём не знает.
            ProfileNetwork.sync(player);
            io.github.verycooltimo.murim.mastery.MasteryService.sync(player);
            io.github.verycooltimo.murim.cultivation.RankEffects.apply(player);
            player.setHealth(player.getMaxHealth());
        }
    }

    private ProfileEvents() {
    }
}
