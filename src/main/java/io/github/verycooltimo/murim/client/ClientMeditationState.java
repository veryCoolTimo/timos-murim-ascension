package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.cultivation.MeditationService;
import io.github.verycooltimo.murim.network.MeditationInputPayload;
import io.github.verycooltimo.murim.network.SyncMeditationPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Клиентская сторона медитации: клавиши, поза лотоса, блокировка движения.
 *
 * <p>Клиент присылает только нажатия; время, окно удержания и засчитанные тики считает
 * сервер (docs/design/19 §3а). Здесь живёт последнее присланное состояние — для позы
 * и подсказок {@link MeditationHud}.
 */
@EventBusSubscriber(modid = io.github.verycooltimo.murim.MurimMod.MODID, value = Dist.CLIENT)
public final class ClientMeditationState {

    private static SyncMeditationPayload state =
            new SyncMeditationPayload(false, 0, 0, 0, SyncMeditationPayload.Event.NONE);

    /** Последнее, что клиент сообщил серверу про клавишу удержания. */
    private static boolean holdSent;

    /** Сколько тиков ещё показывать «внутренний взгляд» после рождения семени. */
    private static int seedFlashTicks;

    public static SyncMeditationPayload state() {
        return state;
    }

    public static int seedFlashTicks() {
        return seedFlashTicks;
    }

    public static void accept(SyncMeditationPayload payload) {
        boolean wasActive = state.active();
        state = payload;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            if (payload.active() && !wasActive) {
                MurimPlayerAnimations.play(player, MurimPlayerAnimations.LOTUS);
            } else if (!payload.active() && wasActive) {
                MurimPlayerAnimations.stop(player);
            }
        }
        if (!payload.active()) {
            holdSent = false;
        }
        if (payload.event() == SyncMeditationPayload.Event.SEED) {
            seedFlashTicks = SEED_FLASH_TICKS;
        }
    }

    /** «Внутренний взгляд» при рождении семени: 12 секунд (автор: 10–15). */
    public static final int SEED_FLASH_TICKS = 240;

    /** Окно удержания открыто сейчас: второй такт и нужная секунда сессии. */
    public static boolean ringWindowOpen() {
        return state.active() && state.beats() == 1 && MeditationService.isRingWindow(state.ticks());
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (seedFlashTicks > 0) {
            seedFlashTicks--;
        }
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        while (ModKeyMappings.MEDITATION.consumeClick()) {
            // На корточках — «брать всё»: быстрее, но мутнее.
            boolean filter = !minecraft.player.isShiftKeyDown();
            PacketDistributor.sendToServer(new MeditationInputPayload(
                    MeditationInputPayload.Action.TOGGLE, filter));
        }
        // Кольцо держится клавишей прыжка: во время медитации она всё равно заблокирована.
        boolean holding = state.active() && minecraft.options.keyJump.isDown();
        if (holding != holdSent) {
            holdSent = holding;
            PacketDistributor.sendToServer(new MeditationInputPayload(holding
                    ? MeditationInputPayload.Action.HOLD_ON : MeditationInputPayload.Action.HOLD_OFF, true));
        }
    }

    /** Во время медитации тело неподвижно: ввод движения гасится до того, как уйдёт серверу. */
    @SubscribeEvent
    static void onMovementInput(MovementInputUpdateEvent event) {
        if (!state.active()) {
            return;
        }
        var input = event.getInput();
        input.forwardImpulse = 0.0F;
        input.leftImpulse = 0.0F;
        input.up = false;
        input.down = false;
        input.left = false;
        input.right = false;
        input.jumping = false;
        input.shiftKeyDown = false;
    }

    /** Сброс при выходе из мира: иначе в новом мире тело осталось бы «сидящим». */
    public static void reset() {
        state = new SyncMeditationPayload(false, 0, 0, 0, SyncMeditationPayload.Event.NONE);
        holdSent = false;
        seedFlashTicks = 0;
    }

    private ClientMeditationState() {
    }
}
