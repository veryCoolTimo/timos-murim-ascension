package io.github.verycooltimo.murim.cultivation;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** N05: six health over thirty seconds, damage ends care; no qi. Design pills-plants-1029 §2.
 * API: SectBouts LivingDamageEvent.Post and MeditationService PlayerTickEvent.Post.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class MendingCare {
    private static final String LEFT = "murim_mending_left";
    private MendingCare() { }
    public static boolean usable(net.minecraft.world.entity.player.Player p) {
        return p.getHealth() < p.getMaxHealth() && p.hurtTime <= 0
                && !(p.getLastHurtByMobTimestamp() > 0 && p.tickCount - p.getLastHurtByMobTimestamp() < 100)
                && !(p.getLastHurtMobTimestamp() > 0 && p.tickCount - p.getLastHurtMobTimestamp() < 100)
                && p.getPersistentData().getInt(LEFT) <= 0;
    }
    public static void start(ServerPlayer p) {
        if (!usable(p)) return;
        p.getPersistentData().putInt(LEFT, 600);
    }
    @SubscribeEvent
    static void damaged(LivingDamageEvent.Post event) {
        if (event.getNewDamage() > 0 && event.getEntity() instanceof ServerPlayer p) p.getPersistentData().remove(LEFT);
    }
    @SubscribeEvent
    static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        int left = p.getPersistentData().getInt(LEFT);
        if (left <= 0) return;
        p.getPersistentData().putInt(LEFT, left - 1);
        if ((left - 1) % 100 == 0) p.heal(1);
    }
}
