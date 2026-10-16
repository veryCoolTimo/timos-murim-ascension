package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Pose;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;

/** Input feedback only. The server owns the anchor, restrictions, duration and persisted state. */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class ClientPenanceState {
    private static boolean pinned;
    private static int source = -1;
    private ClientPenanceState() { }
    public static boolean pinned() { return pinned; }
    public static int source() { return source; }
    public static void set(boolean value, int elder) {
        pinned = value;
        source = elder;
        var p = Minecraft.getInstance().player;
        if (p != null) p.setForcedPose(value ? Pose.CROUCHING : null);
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void movement(MovementInputUpdateEvent event) {
        if (!pinned) return;
        var input = event.getInput();
        input.forwardImpulse = input.leftImpulse = 0;
        input.up = input.down = input.left = input.right = input.jumping = false;
        input.shiftKeyDown = true;
        event.getEntity().setSprinting(false);
        event.getEntity().setForcedPose(Pose.CROUCHING);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        if (pinned) { event.setCanceled(true); event.setSwingHand(false); }
    }
    @SubscribeEvent static void logout(ClientPlayerNetworkEvent.LoggingOut event) { set(false, -1); }
    @SubscribeEvent static void clone(ClientPlayerNetworkEvent.Clone event) { set(false, -1); }
}
