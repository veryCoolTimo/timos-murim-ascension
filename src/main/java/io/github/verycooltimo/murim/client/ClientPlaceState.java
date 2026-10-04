package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.world.PlaceRules;
import io.github.verycooltimo.murim.world.PlaceService;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Место силы рядом с игроком на клиенте (docs/design/19b §3): тот же поиск и тот же цикл
 * волн, что у сервера ({@link PlaceRules}), — для подписи у виджета медитации и предупреждения.
 * Пакетов не нужно: блоки и время мира клиент знает сам.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class ClientPlaceState {

    private static io.github.verycooltimo.murim.world.Place place;

    private ClientPlaceState() {
    }

    /** Якорь места силы рядом или {@code null}. */
    public static BlockPos node() {
        return place == null ? null : place.anchor();
    }

    public static io.github.verycooltimo.murim.world.Place place() {
        return place;
    }

    /** Предупреждение о волне 0..1 (0 — нет узла или далеко до волны). */
    public static float warning() {
        Minecraft mc = Minecraft.getInstance();
        return place == null || mc.level == null ? 0.0F : PlaceRules.warning(place.anchor().asLong(), mc.level.getGameTime());
    }

    /** Приток сбит волной. */
    public static boolean stunned() {
        Minecraft mc = Minecraft.getInstance();
        return place != null && mc.level != null
                && PlaceRules.sinceWave(place.anchor().asLong(), mc.level.getGameTime()) < PlaceRules.STUN;
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            place = null;
            return;
        }
        // Природное место видно только сидящему в медитации (автор 04.10: на Хуашань «куда ни
        // встанешь — всё горит»). Камни жилы рисуются своим блоком и от этого не зависят.
        if (!ClientMeditationState.state().active()) {
            place = null;
            return;
        }
        if (mc.player.tickCount % 20 != 0) {
            return;
        }
        place = PlaceService.findPlace(mc.level, mc.player.blockPosition()).orElse(null);
    }

    public static void reset() {
        place = null;
    }
}
