package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.world.PlaceKind;
import io.github.verycooltimo.murim.world.PlaceRules;
import io.github.verycooltimo.murim.world.PlaceService;
import io.github.verycooltimo.murim.world.SpiritVeinBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
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

    private static BlockPos node;
    private static PlaceKind kind = PlaceKind.ALTAR;

    private ClientPlaceState() {
    }

    public static BlockPos node() {
        return node;
    }

    public static PlaceKind kind() {
        return kind;
    }

    /** Предупреждение о волне 0..1 (0 — нет узла или далеко до волны). */
    public static float warning() {
        Minecraft mc = Minecraft.getInstance();
        return node == null || mc.level == null ? 0.0F : PlaceRules.warning(node.asLong(), mc.level.getGameTime());
    }

    /** Приток сбит волной. */
    public static boolean stunned() {
        Minecraft mc = Minecraft.getInstance();
        return node != null && mc.level != null
                && PlaceRules.sinceWave(node.asLong(), mc.level.getGameTime()) < PlaceRules.STUN;
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            node = null;
            return;
        }
        if (mc.player.tickCount % 20 != 0) {
            return;
        }
        node = PlaceService.findNode(mc.level, mc.player.blockPosition()).orElse(null);
        if (node != null) {
            BlockState s = mc.level.getBlockState(node);
            kind = s.getBlock() instanceof SpiritVeinBlock ? s.getValue(SpiritVeinBlock.KIND) : PlaceKind.ALTAR;
        }
    }

    public static void reset() {
        node = null;
    }
}
