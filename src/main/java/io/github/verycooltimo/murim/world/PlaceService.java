package io.github.verycooltimo.murim.world;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.PillService;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.profile.ProfileNetwork;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.Optional;

/**
 * Места силы на сервере (docs/design/19b §3, автор 03.10): медитация у камня жилы идёт ×2,
 * но у узла свой цикл волн — каждые 30–45 с удар по сидящему, вид волны — по виду места.
 *
 * <p>Волна бьёт по телу, но медитацию НЕ срывает (флаг {@link PillService#setWaveHit}) и
 * сбивает приток на 3 с. Узел ищется рядом с медитирующим раз в секунду.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class PlaceService {

    private PlaceService() {
    }

    /** Ближайший камень жилы в радиусе (сканирует куб 13×7×13). */
    public static Optional<BlockPos> findNode(Level level, BlockPos around) {
        int r = (int) Math.ceil(PlaceRules.RADIUS);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(around.offset(-r, -3, -r), around.offset(r, 3, r))) {
            if (level.getBlockState(p).getBlock() instanceof SpiritVeinBlock) {
                double d = p.distSqr(around);
                if (d < bestD && d <= PlaceRules.RADIUS * PlaceRules.RADIUS) {
                    bestD = d;
                    best = p.immutable();
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** Найденный узел у игрока: живёт в слоте сессии ({@link PillService.Slot}), не сохраняется. */
    private static BlockPos node(ServerPlayer player) {
        return player.getData(ModAttachments.ABSORB).placeNode;
    }

    private static void setNode(ServerPlayer player, BlockPos pos) {
        player.getData(ModAttachments.ABSORB).placeNode = pos;
    }

    /** Множитель прироста медитации: ×2 у узла, 0 — сразу после волны (приток сбит). */
    public static double gainFactor(ServerPlayer player) {
        BlockPos node = node(player);
        if (node == null) {
            return 1.0D;
        }
        if (PlaceRules.sinceWave(node.asLong(), player.level().getGameTime()) < PlaceRules.STUN) {
            return 0.0D;
        }
        return PlaceRules.GAIN;
    }

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        boolean meditating = player.getData(ModAttachments.MEDITATION).active()
                && player.getData(ModAttachments.CULTIVATION).seeded();
        if (!meditating) {
            setNode(player, null);
            return;
        }
        if (player.tickCount % 20 == 0) {
            setNode(player, findNode(player.level(), player.blockPosition()).orElse(null));
        }
        BlockPos node = node(player);
        if (node == null || !PlaceRules.waveNow(node.asLong(), player.level().getGameTime())) {
            return;
        }
        BlockState state = player.level().getBlockState(node);
        if (!(state.getBlock() instanceof SpiritVeinBlock)) {
            setNode(player, null);
            return;
        }
        wave(player, node, state.getValue(SpiritVeinBlock.KIND));
    }

    /** Волна места силы по сидящему у узла. */
    static void wave(ServerPlayer player, BlockPos node, PlaceKind kind) {
        ServerLevel level = player.serverLevel();
        PillService.setWaveHit(player, true);
        try {
            switch (kind) {
                case PEAK -> {
                    // Небесный разряд: молния рядом (только вид), ударная волна — урон.
                    // API: reference/minecraft-src/net/minecraft/world/entity/LightningBolt.java#setVisualOnly
                    LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
                    if (bolt != null) {
                        double a = player.getRandom().nextDouble() * Math.PI * 2;
                        double d = 2.0D + player.getRandom().nextDouble() * 2.0D;
                        bolt.moveTo(player.getX() + Math.cos(a) * d, player.getY(), player.getZ() + Math.sin(a) * d);
                        bolt.setVisualOnly(true);
                        level.addFreshEntity(bolt);
                    }
                    player.hurt(level.damageSources().lightningBolt(), 3.0F);
                }
                case WATER -> {
                    player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 120, 2));
                    player.hurt(level.damageSources().freeze(), 2.0F);
                }
                case FOREST -> {
                    AABB box = player.getBoundingBox().inflate(24.0D);
                    for (Mob mob : level.getEntitiesOfClass(Mob.class, box, m -> m instanceof Enemy && m.isAlive())) {
                        mob.setTarget(player);
                    }
                    if (level.isNight()) {
                        var zombie = EntityType.ZOMBIE.create(level);
                        if (zombie != null) {
                            double a = player.getRandom().nextDouble() * Math.PI * 2;
                            BlockPos at = BlockPos.containing(player.getX() + Math.cos(a) * 7, player.getY(), player.getZ() + Math.sin(a) * 7);
                            at = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at);
                            zombie.moveTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, player.getRandom().nextFloat() * 360F, 0F);
                            zombie.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
                            zombie.setTarget(player);
                            level.addFreshEntity(zombie);
                        }
                    }
                }
                case ALTAR -> {
                    player.hurt(level.damageSources().magic(), 4.0F);
                    DantianProfile p = player.getData(ModAttachments.PROFILE);
                    player.setData(ModAttachments.PROFILE, p.withCirculating(p.circulating() * 0.7D));
                    ProfileNetwork.sync(player);
                }
            }
        } finally {
            PillService.setWaveHit(player, false);
        }
    }
}
