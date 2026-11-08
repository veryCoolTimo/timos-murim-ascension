package io.github.verycooltimo.murim.sect.vault;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** Server-only proximity opening; block states synchronize through UPDATE_ALL, with no saved flags or chat.
 * API patterns: sect/seal/VaultTrial#onPlayerTick and VaultFloorBlock#open.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class VaultExit {
    private VaultExit() {}

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer p && HuaVault.near(p, 3)) approach(p);
    }

    static void approach(ServerPlayer player) {
        if (player.isSpectator()) return;
        ServerLevel level = (ServerLevel) player.level();
        BlockPos feet = player.blockPosition();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = 0; dy <= 3; dy++) {
            BlockPos at = feet.offset(dx, dy, dz);
            var state = level.getBlockState(at);
            if (state.getBlock() instanceof VaultDoorBlock) VaultDoorBlock.exit(level, at, player);
            if (state.getBlock() instanceof VaultFloorBlock && state.getValue(VaultFloorBlock.HATCH))
                VaultFloorBlock.exit(level, at, player);
        }
    }
}
