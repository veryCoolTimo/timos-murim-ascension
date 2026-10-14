package io.github.verycooltimo.murim.client.gear;

import io.github.verycooltimo.murim.MurimMod;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

/**
 * Belt swords of players by entity id, as the server sent them (gear/BeltSword). Forgotten on logout, on a new
 * level of our own player (respawn, dimension change — the server sends again) and when a player leaves the level.
 * API: reference/neoforge-src/net/neoforged/neoforge/client/event/ClientPlayerNetworkEvent.java#LoggingOut, #Clone;
 * reference/neoforge-src/net/neoforged/neoforge/event/entity/EntityLeaveLevelEvent.java
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class ClientBeltSwords {

    private static final Int2ObjectMap<ItemStack> BELTS = new Int2ObjectOpenHashMap<>();

    private ClientBeltSwords() {
    }

    public static void set(int entityId, ItemStack stack) {
        if (stack.isEmpty()) {
            BELTS.remove(entityId);
        } else {
            BELTS.put(entityId, stack);
        }
    }

    public static ItemStack of(Entity entity) {
        return BELTS.getOrDefault(entity.getId(), ItemStack.EMPTY);
    }

    @SubscribeEvent
    static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        BELTS.clear();
    }

    @SubscribeEvent
    static void onClone(ClientPlayerNetworkEvent.Clone event) {
        BELTS.clear();
    }

    @SubscribeEvent
    static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() && event.getEntity() instanceof net.minecraft.world.entity.player.Player player
                && player != net.minecraft.client.Minecraft.getInstance().player) {
            BELTS.remove(player.getId());
        }
    }
}
