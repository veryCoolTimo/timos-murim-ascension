package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** Server-only inactivity clock; persisted with the player, never sent to chat or HUD.
 * API: existing seal/PenanceEnforcement interaction handlers and SectApproach#occupied.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class GuidanceIdle {
    private static final String TAG = "murim_guidance_idle";
    private GuidanceIdle() { }

    public static void active(ServerPlayer p) {
        p.getPersistentData().putLong(TAG, p.level().getGameTime());
    }

    public static boolean idle(ServerPlayer p) {
        long now = p.level().getGameTime();
        var tag = p.getPersistentData();
        return tag.contains(TAG) && now >= tag.getLong(TAG) && now - tag.getLong(TAG) >= 100
                && !SectApproach.occupied(p) && p.hurtTime == 0;
    }

    @SubscribeEvent
    static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        var tag = p.getPersistentData();
        boolean moved = !tag.contains("murim_idle_x")
                || Math.abs(p.getX() - tag.getDouble("murim_idle_x")) > 0.001D
                || Math.abs(p.getY() - tag.getDouble("murim_idle_y")) > 0.001D
                || Math.abs(p.getZ() - tag.getDouble("murim_idle_z")) > 0.001D;
        if (moved || SectApproach.occupied(p) || p.hurtTime > 0) active(p);
        tag.putDouble("murim_idle_x", p.getX());
        tag.putDouble("murim_idle_y", p.getY());
        tag.putDouble("murim_idle_z", p.getZ());
    }

    @SubscribeEvent
    static void mine(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer p) active(p);
    }

    @SubscribeEvent
    static void build(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer p) active(p);
    }

    @SubscribeEvent
    static void fight(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) active(p);
    }

    @SubscribeEvent
    static void login(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) active(p);
    }
}
