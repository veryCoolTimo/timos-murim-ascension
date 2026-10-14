package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** Two-tick contact, followed by the real vanilla attack (cooldown, enchants, crits and hooks).
 * The packet is an intent, never client-authored damage. The same delay on every attack keeps
 * attack intervals unchanged: inputs 0/13/26 land at 2/15/28.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SwordBasicAttack {
    public record Pending(ResourceLocation basic, int targetId, long due) {
        public static final Pending IDLE = new Pending(null, -1, Long.MIN_VALUE);
    }

    public static void input(ServerPlayer player, int form, int targetId) {
        tick(player); // A due hit must finish before another intent can replace it.
        if (player.getData(ModAttachments.BASIC_ATTACK).basic() != null) return;
        var id = FoundationService.active(player);
        if (id.isEmpty() || !SwordBasicCombo.isBasic(id.get())) {
            // Ordinary Six Harmonies still accompanies the normal vanilla interaction packet.
            FoundationService.onSwing(player, form);
            return;
        }
        if (targetId >= 0 && !validTarget(player, player.serverLevel().getEntity(targetId))) return;
        var previous = player.getData(ModAttachments.BASIC_COMBO);
        FoundationService.onSwing(player, form);
        var accepted = player.getData(ModAttachments.BASIC_COMBO);
        if (accepted.equals(previous)) return;
        player.setData(ModAttachments.BASIC_ATTACK, new Pending(id.get(), targetId,
                player.level().getGameTime() + SwordBasicCombo.CONTACT_TICK));
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                io.github.verycooltimo.murim.registry.ModSounds.SWORD_SWING.get(),
                net.minecraft.sounds.SoundSource.PLAYERS, 0.35F, 1.25F);
    }

    private static boolean validTarget(ServerPlayer player, Entity target) {
        if (target == null || target == player || !target.isAlive() || !target.isAttackable()
                || target instanceof net.minecraft.world.entity.item.ItemEntity
                || target instanceof net.minecraft.world.entity.ExperienceOrb
                || target instanceof net.minecraft.world.entity.projectile.AbstractArrow) return false;
        if (target instanceof Player other && !player.canHarmPlayer(other)) return false;
        return player.canInteractWithEntity(target, 0.0D) && player.hasLineOfSight(target);
    }

    public static void tick(ServerPlayer player) {
        Pending pending = player.getData(ModAttachments.BASIC_ATTACK);
        if (pending.basic() == null || player.level().getGameTime() < pending.due()) return;
        // Clear before attack: hurt hooks, thorns or cancellation cannot duplicate a queued hit.
        player.setData(ModAttachments.BASIC_ATTACK, Pending.IDLE);
        if (!player.isAlive() || player.isSpectator()
                || player.getData(ModAttachments.TECHNIQUE_STATE).isActive()
                || !FoundationService.active(player).filter(pending.basic()::equals).isPresent()) return;
        if (pending.targetId() < 0) {
            player.resetAttackStrengthTicker();
            return;
        }
        Entity target = player.serverLevel().getEntity(pending.targetId());
        // Recheck after windup: a wall, retreat or stance change can turn the cut into a miss.
        if (validTarget(player, target)) {
            player.attack(target);
        } else {
            player.resetAttackStrengthTicker();
        }
    }

    @SubscribeEvent
    static void onTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) tick(player);
    }

    private SwordBasicAttack() {}
}
