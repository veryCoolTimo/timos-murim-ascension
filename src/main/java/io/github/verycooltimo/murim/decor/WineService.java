package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerWakeUpEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** Server authority; effect instances save and synchronize without an additional player save field.
 * API verified in /tmp/mr-ref-1121: Minecraft LivingEntity (effects), Entity#push/hurtMarked,
 * CollisionGetter#noCollision, BlockState#isFaceSturdy/getFluidState, Player#isSleepingLongEnough;
 * NeoForge LivingIncomingDamageEvent, PlayerTickEvent.Post, PlayerWakeUpEvent.
 */
@EventBusSubscriber(modid = MurimMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class WineService {
    private WineService() { }

    public static void drink(Player player) {
        if (player.level().isClientSide) return;
        var old = player.getEffect(IntWorkshop.TIPSY);
        int amplifier = WineStacking.amplifier(old == null ? -1 : old.getAmplifier());
        int duration = WineStacking.duration(old == null ? 0 : old.getDuration());
        // API: reference/minecraft-src/net/minecraft/world/effect/MobEffectInstance.java#MobEffectInstance
        // Six arguments preserve the HUD icon independently of particle visibility.
        // Replace rather than retain a hidden lower-level tail after the stronger effect expires.
        player.removeEffect(IntWorkshop.TIPSY);
        player.addEffect(new MobEffectInstance(IntWorkshop.TIPSY, duration, amplifier, false, false, true));
    }

    public static int level(LivingEntity entity) {
        var effect = entity.getEffect(IntWorkshop.TIPSY);
        return effect == null ? 0 : Math.min(3, effect.getAmplifier() + 1);
    }

    public static void cure(Player player) {
        if (player.level().isClientSide) return;
        player.removeEffect(IntWorkshop.TIPSY);
        if (player.hasEffect(IntWorkshop.HANGOVER)) {
            player.removeEffect(IntWorkshop.HANGOVER);
            player.removeEffect(MobEffects.DIG_SLOWDOWN);
            player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        }
    }

    @SubscribeEvent
    public static void damage(LivingIncomingDamageEvent event) {
        if (event.getEntity().level().isClientSide) return;
        float factor = WineStacking.damageFactor(level(event.getEntity()));
        if (event.getSource().getEntity() instanceof LivingEntity attacker) {
            factor *= WineStacking.damageFactor(level(attacker));
        }
        event.setAmount(event.getAmount() * factor);
    }

    @SubscribeEvent
    public static void wake(PlayerWakeUpEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide || !player.isSleepingLongEnough() || level(player) < 3) return;
        player.removeEffect(IntWorkshop.TIPSY);
        player.addEffect(new MobEffectInstance(IntWorkshop.HANGOVER, 2400, 0, false, false, true));
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 2400, 0, false, false, true));
        player.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 2400, 0, false, false, true));
    }

    @SubscribeEvent
    public static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || level(player) < 2) return;
        if (level(player) == 3 && player.tickCount % 240 == 0) {
            player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_BURP,
                    SoundSource.PLAYERS, .18F, 1.6F);
        }
        // Two alternating impulses per 32-tick pair: each gap is 12..20 ticks.
        // UUID/pair hash varies timing without a new saved or static player state.
        int phase = player.tickCount % 32;
        int second = 12 + Math.floorMod(player.getUUID().hashCode() ^ (player.tickCount / 32 * 73428767), 9);
        if ((phase != 0 && phase != second) || !player.onGround() || player.isSleeping()
                || player.isPassenger() || player.getAbilities().flying || player.isShiftKeyDown()
                || player.getDeltaMovement().horizontalDistanceSqr() < .001) return;
        double yaw = Math.toRadians(player.getYRot());
        double amount = (phase == 0 ? 1 : -1) * (.05 + player.getRandom().nextDouble() * .02);
        Vec3 drift = new Vec3(Math.cos(yaw) * amount, 0, Math.sin(yaw) * amount);
        applyStagger(player, drift);
    }

    /** API: reference/minecraft-src/net/minecraft/world/entity/Entity.java#push and hurtMarked.
     * push adds velocity; hurtMarked requests the vanilla server-to-client motion update. */
    public static boolean applyStagger(ServerPlayer player, Vec3 impulse) {
        // Check the walk plus a conservative four-tick impulse travel envelope, not just its first tick.
        Vec3 travel = player.getDeltaMovement().multiply(4, 0, 4).add(impulse.scale(4));
        if (!safeDrift(player, impulse) || !safePath(player, travel)) return false;
        player.push(impulse.x, 0, impulse.z);
        player.hurtMarked = true;
        return true;
    }

    /** Full swept body clearance and supported dry corners; rejects even one-block ledges. */
    public static boolean safeDrift(ServerPlayer player, Vec3 drift) {
        if (Math.abs(drift.y) > 0 || drift.horizontalDistanceSqr() > .071 * .071) return false;
        return safePath(player, drift);
    }

    private static boolean safePath(ServerPlayer player, Vec3 drift) {
        var world = player.level();
        var box = player.getBoundingBox().minmax(player.getBoundingBox().move(drift));
        if (!world.noCollision(player, box)) return false;
        int y = (int) Math.floor(box.minY - .01);
        for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX); x++) {
            for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ); z++) {
                BlockPos floor = new BlockPos(x, y, z);
                if (!world.hasChunkAt(floor) || !world.getBlockState(floor).isFaceSturdy(world, floor, Direction.UP)
                        || !world.getFluidState(floor).isEmpty()) return false;
                for (int bodyY = y + 1; bodyY <= (int) Math.floor(box.maxY); bodyY++) {
                    if (!world.getFluidState(new BlockPos(x, bodyY, z)).isEmpty()) return false;
                }
            }
        }
        return true;
    }
}
