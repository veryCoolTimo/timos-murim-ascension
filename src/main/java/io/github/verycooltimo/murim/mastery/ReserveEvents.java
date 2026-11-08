package io.github.verycooltimo.murim.mastery;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.sect.PlayerJournal;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerWakeUpEvent;

/**
 * A night's sleep processes the reserve of EVERY art (hua-main-1810 §1: "30 s of the Echo, or sleeping in a bed, processes
 * the whole reserve"), without a multiplier. The other way is the Echo of one chosen art
 * ({@link io.github.verycooltimo.murim.cultivation.MeditationService#ECHO_TICKS}).
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/event/entity/player/PlayerWakeUpEvent.java (fired before the sleep
 * counter resets, so {@code isSleepingLongEnough} still tells whether the night was a real one — the same check as the
 * body training of {@code TrainingService}).
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class ReserveEvents {

    private ReserveEvents() {
    }

    @SubscribeEvent
    static void onWake(PlayerWakeUpEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !player.isSleepingLongEnough()) {
            return;
        }
        if (MasteryService.sleep(player) > 0) {
            PlayerJournal.record(player, Component.translatable("murim.mastery.slept").withStyle(ChatFormatting.AQUA));
        }
    }
}
