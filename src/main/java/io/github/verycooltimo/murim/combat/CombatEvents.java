package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Подписки боевой системы на игровую шину. Только серверная сторона.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class CombatEvents {

    /**
     * Полученный урон сбивает технику, если это разрешено её описанием.
     *
     * <p>Решение по правилам прерывания: до фазы удара технику можно сбить, с удара она
     * обязана доиграться, рассеивание не сбивается вовсе — это уже послесвечение, а не
     * действие. Порог урона задаётся данными: слабый тычок не должен ломать трёхсекундный
     * ритуал, иначе любая техника становится неприменимой в бою.
     */
    @SubscribeEvent
    static void onIncomingDamage(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            return;
        }
        TechniqueService.onDamaged(player, event.getAmount());
    }

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            TechniqueService.tick(serverPlayer);
        }
    }

    /**
     * Гасим технику по смерти, а не по респавну.
     *
     * <p>Респавн для этого не годится: там создаётся <b>новый</b> объект игрока, а Data Attachment
     * без сериализатора на него не копируется. Обработчик на респавне видел бы уже пустое
     * состояние и молча ничего не делал — трекеры остались бы с недоигранной анимацией.
     */
    @SubscribeEvent
    static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            TechniqueService.cancel(serverPlayer);
        }
    }

    /**
     * Смена измерения: объект игрока тот же, состояние переживает переход, но сущности вокруг
     * уже другие — доигрывать технику в новом мире бессмысленно.
     */
    @SubscribeEvent
    static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            TechniqueService.cancel(serverPlayer);
        }
    }

    /** Выход из игры: иначе наблюдатели останутся с недоигранной анимацией. */
    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            TechniqueService.cancel(serverPlayer);
        }
    }

    private CombatEvents() {
    }
}
