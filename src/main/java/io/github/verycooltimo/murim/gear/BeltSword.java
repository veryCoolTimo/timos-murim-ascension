package io.github.verycooltimo.murim.gear;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Belt sword (author 06.10: «если у игрока в инвентаре меч, то у него на поясе появляется он, и также вытаскивается,
 * если в руке»): a sword ({@code #minecraft:swords}) in the hotbar hangs on the left hip; a sword in
 * either hand empties the belt at once. Which one: the sword held last, if still carried, else the first in hotbar
 * order. The server picks every tick and sends a change to the player and their trackers
 * ({@link BeltSwordPayload}); the client layer only draws.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/event/tick/PlayerTickEvent.java#Post,
 * reference/neoforge-src/net/neoforged/neoforge/event/entity/player/PlayerEvent.java#StartTracking,
 * #PlayerChangedDimensionEvent; reference/neoforge-src/net/neoforged/neoforge/network/PacketDistributor.java
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class BeltSword {

    /** Per player, not saved: the sword item held last and the belt stack last sent. */
    public static final class State {
        Item lastHeld;
        ItemStack sent = ItemStack.EMPTY;

        public Item lastHeld() {
            return lastHeld;
        }

        public ItemStack sent() {
            return sent;
        }
    }

    private BeltSword() {
    }

    public static boolean isSword(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ItemTags.SWORDS);
    }

    /** The belt sword of {@code player} (not a copy), or empty: none carried, or one is drawn. */
    public static ItemStack pick(Player player, Item lastHeld) {
        if (isSword(player.getMainHandItem()) || isSword(player.getOffhandItem())) {
            return ItemStack.EMPTY;
        }
        ItemStack first = ItemStack.EMPTY;
        // Only the hotbar (author 07.10: «меч на поясе только если он лежит в первой строке инвентаря»):
        // Inventory#items slots 0..8; the main inventory above is ignored.
        for (int slot = 0; slot < net.minecraft.world.entity.player.Inventory.getSelectionSize(); slot++) {
            ItemStack stack = player.getInventory().items.get(slot);
            if (!isSword(stack)) {
                continue;
            }
            if (lastHeld != null && stack.is(lastHeld)) {
                return stack;
            }
            if (first.isEmpty()) {
                first = stack;
            }
        }
        return first;
    }

    /** Recomputes the belt; a change goes to the player and everyone tracking them. */
    public static void update(ServerPlayer player) {
        State state = player.getData(ModAttachments.BELT_SWORD);
        if (isSword(player.getMainHandItem())) {
            state.lastHeld = player.getMainHandItem().getItem();
        }
        ItemStack want = pick(player, state.lastHeld);
        if (ItemStack.isSameItemSameComponents(want, state.sent)) {
            return;
        }
        state.sent = want.copyWithCount(1);
        BeltSwordPayload payload = new BeltSwordPayload(player.getId(), state.sent);
        PacketDistributor.sendToPlayersTrackingEntity(player, payload);
        // GameTest players sit on an embedded connection without our channels (like TrainingNetwork#state).
        if (player.connection != null && player.connection.hasChannel(BeltSwordPayload.TYPE)) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    @SubscribeEvent
    static void onTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            update(player);
        }
    }

    @SubscribeEvent
    static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof ServerPlayer target && event.getEntity() instanceof ServerPlayer tracker) {
            ItemStack sent = target.getData(ModAttachments.BELT_SWORD).sent;
            if (!sent.isEmpty() && tracker.connection != null && tracker.connection.hasChannel(BeltSwordPayload.TYPE)) {
                PacketDistributor.sendToPlayer(tracker, new BeltSwordPayload(target.getId(), sent));
            }
        }
    }

    /** The client forgets belts on a new level (ClientBeltSwords): send ours again on the next tick. */
    @SubscribeEvent
    static void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            player.getData(ModAttachments.BELT_SWORD).sent = ItemStack.EMPTY;
        }
    }
}
