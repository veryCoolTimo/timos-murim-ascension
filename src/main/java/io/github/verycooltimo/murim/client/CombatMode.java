package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;

/**
 * Режим боя (автор 01.10): применил технику, ударил или получил удар — включается, и на
 * экране появляются слоты техник; десять секунд тишины — гаснет. Вне боя экран чистый.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class CombatMode {

    /** Сколько тиков бой держится после последнего действия. */
    private static final int HOLD_TICKS = 200;

    /** Плавное проявление и угасание, тики. */
    private static final int FADE_TICKS = 8;

    private static int remaining;
    private static int shown;

    public static void engage() {
        remaining = HOLD_TICKS;
    }

    /** Насколько проявлен интерфейс боя, 0..1. */
    public static float presence(float partial) {
        return Mth.clamp((shown + (remaining > 0 ? partial : -partial)) / FADE_TICKS, 0.0F, 1.0F);
    }

    public static boolean active() {
        return remaining > 0;
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            remaining = 0;
            shown = 0;
            return;
        }
        // Получил удар — тоже бой.
        if (player.hurtTime > 0) {
            engage();
        }
        // Кольцо и экран раскладки держат бой открытым: выбираешь — значит, собираешься драться.
        if (TechniqueWheel.open()) {
            engage();
        }
        if (remaining > 0) {
            remaining--;
        }
        shown = remaining > 0 ? Math.min(FADE_TICKS, shown + 1) : Math.max(0, shown - 1);
    }

    /** Удар рукой или оружием — тоже вход в бой. */
    @SubscribeEvent
    static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (event.isAttack()) {
            engage();
        }
    }

    public static void reset() {
        remaining = 0;
        shown = 0;
    }

    private CombatMode() {
    }
}
