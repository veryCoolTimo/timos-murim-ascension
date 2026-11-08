package io.github.verycooltimo.murim.profile;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
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
        io.github.verycooltimo.murim.cultivation.MeditationService.idleWallHint(player);
        passiveCirculation(player);
    }

    /**
     * Урон срывает медитацию — но только настоящий.
     *
     * <p>Post сообщает потерю здоровья после щита, брони и сопротивления.
     * Нулевой итоговый урон медитацию не срывает.
     */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    static void onDamage(LivingDamageEvent.Post event) {
        if (event.getNewDamage() <= 0.0F) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // Искажение ци — отдача от удара, а не новый удар (docs/design/19b §10).
        if (event.getSource().is(io.github.verycooltimo.murim.registry.ModDamageTypes.QI_DEVIATION)) {
            return;
        }
        // Волна места силы бьёт по телу, но медитацию не срывает (docs/design/19b §3).
        if (io.github.verycooltimo.murim.cultivation.PillService.waveHit(player)) {
            return;
        }
        if (player.getData(ModAttachments.MEDITATION).active()) {
            io.github.verycooltimo.murim.cultivation.MeditationService.stop(player,
                    "murim.meditation.broken.hurt");
        }
    }

    /**
     * Выход, остановка сервера или смерть посреди поглощения: сессия (мини-игра, растворение) живёт только в памяти,
     * поэтому усвоенная доля выплачивается, а остаток уходит в сохраняемый резерв, и пилюля не пропадает.
     */
    @SubscribeEvent
    static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            settleAbsorption(player);
        }
    }

    @SubscribeEvent
    static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            settleAbsorption(player);
        }
    }

    @SubscribeEvent
    static void onDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            settleAbsorption(player);
        }
    }

    /** Закрывает идущее поглощение: усвоенное остаётся, остаток в резерв (idempotent). */
    public static void settleAbsorption(ServerPlayer player) {
        if (io.github.verycooltimo.murim.cultivation.PillService.absorbing(player)) {
            io.github.verycooltimo.murim.cultivation.PillService.onMeditationStop(player, false);
        }
    }

    /**
     * Пассивное восстановление циркулирующей ци из запаса.
     *
     * <p>Решение автора 2026-09-28 (docs/design/19 §2): боевая ци восстанавливается сама,
     * как мана, от пустой до полной примерно за 40–60 секунд; медитация и клавиша сбора
     * быстрее. Прежнее правило «только в ритуале» отменено.
     */
    public static void passiveCirculation(ServerPlayer player) {
        if (player.tickCount % PASSIVE_EVERY_TICKS != 0) {
            return;
        }
        DantianProfile profile = player.getData(ModAttachments.PROFILE);
        if (!profile.isAwakened() || profile.circulating() >= profile.maxCirculating()) {
            return;
        }
        DantianProfile updated = profile.circulateOnce(io.github.verycooltimo.murim.cultivation.PillEffects.qiRegenFactor(player));
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
            // The client clears mastery and loadout state with the new player object: send both again (also pills).
            io.github.verycooltimo.murim.cultivation.PillService.sync(player,
                    io.github.verycooltimo.murim.network.PillPayloads.Event.NONE);
            io.github.verycooltimo.murim.mastery.MasteryService.sync(player);
        }
    }

    @SubscribeEvent
    static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            migrateRankCapacity(player);
            ProfileNetwork.sync(player);
            io.github.verycooltimo.murim.cultivation.PillService.sync(player,
                    io.github.verycooltimo.murim.network.PillPayloads.Event.NONE);
            io.github.verycooltimo.murim.mastery.MasteryService.completeStyles(player);
            io.github.verycooltimo.murim.mastery.MasteryService.unlockStarForms(player);
            io.github.verycooltimo.murim.mastery.MasteryService.sync(player);
            // Сердца ранга — временный модификатор: ставится заново при каждом входе.
            io.github.verycooltimo.murim.cultivation.RankEffects.apply(player);
        }
    }

    /** Repair before the first profile sync, using the method that imprinted the saved centre. */
    public static void migrateRankCapacity(ServerPlayer player) {
        DantianProfile profile = player.getData(ModAttachments.PROFILE);
        var id = net.minecraft.resources.ResourceLocation.tryParse(profile.imprint());
        var method = id == null ? null : io.github.verycooltimo.murim.cultivation.MethodLoader.get(id);
        if (method == null) {
            method = player.getData(ModAttachments.CULTIVATION).method()
                    .map(io.github.verycooltimo.murim.cultivation.MethodLoader::get).orElse(null);
        }
        double seed = method == null ? DantianProfile.INITIAL.capacity() : method.capacity();
        DantianProfile repaired = io.github.verycooltimo.murim.cultivation.Realm.migrateCapacity(profile, seed);
        if (repaired != profile) player.setData(ModAttachments.PROFILE, repaired);
    }

    @SubscribeEvent
    static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Профиль переживает смерть, но клиент после респавна о нём не знает.
            ProfileNetwork.sync(player);
            io.github.verycooltimo.murim.cultivation.PillService.sync(player,
                    io.github.verycooltimo.murim.network.PillPayloads.Event.NONE);
            io.github.verycooltimo.murim.mastery.MasteryService.sync(player);
            io.github.verycooltimo.murim.cultivation.RankEffects.apply(player);
            player.setHealth(player.getMaxHealth());
        }
    }

    private ProfileEvents() {
    }
}
