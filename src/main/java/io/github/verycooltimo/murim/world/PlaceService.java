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

    /**
     * Место силы рядом: сначала камень жилы, иначе природное — пик (высота ≥ 110 и открытое небо),
     * вода (текущая вода рядом — водопад, стремнина), старое дерево (ствол 2×2 рядом).
     * Автор 03.10: «также должно работать около деревьев, гор и т. д.».
     */
    public static Optional<Place> findPlace(Level level, BlockPos around) {
        Optional<BlockPos> stone = findNode(level, around);
        if (stone.isPresent()) {
            BlockState s = level.getBlockState(stone.get());
            return Optional.of(new Place(stone.get(), s.getValue(SpiritVeinBlock.KIND), true));
        }
        if (around.getY() >= PlaceRules.PEAK_HEIGHT && level.canSeeSky(around.above()) && solidBelow(level, around) >= 4
                && localSummit(level, around)) {
            // Якорь — сетка 4×4: сидящий не сдвигается, и цикл волн не скачет.
            return Optional.of(new Place(new BlockPos(around.getX() & ~3, around.getY(), around.getZ() & ~3),
                    PlaceKind.PEAK, false));
        }
        int flowing = 0;
        BlockPos water = null;
        BlockPos log = null;
        for (BlockPos p : BlockPos.betweenClosed(around.offset(-5, -3, -5), around.offset(5, 8, 5))) {
            var fluid = level.getFluidState(p);
            if (fluid.is(net.minecraft.tags.FluidTags.WATER) && !fluid.isSource() && p.getY() <= around.getY() + 3) {
                flowing++;
                if (water == null) {
                    water = p.immutable();
                }
            }
            if (log == null && bigTrunk(level, p)) {
                log = p.immutable();
            }
        }
        if (flowing >= PlaceRules.WATER_FLOWING) {
            return Optional.of(new Place(water, PlaceKind.WATER, false));
        }
        if (log != null) {
            return Optional.of(new Place(log, PlaceKind.FOREST, false));
        }
        return Optional.empty();
    }

    /**
     * Старое большое дерево — ствол 2×2 высотой {@link PlaceRules#BIG_TRUNK} (тёмный дуб,
     * огромная ель, тропическое). Счёт брёвен рядом (было ≥ 14) зажигал любой густой лес:
     * на Хуашань сосны по 4–13 брёвен стоят тесно, и место «старого дерева» было у трети
     * точек под кронами (стенд placecheck, 04.10).
     */
    static boolean bigTrunk(Level level, BlockPos base) {
        for (int dy = 0; dy < PlaceRules.BIG_TRUNK; dy++) {
            for (int i = 0; i < 4; i++) {
                if (!level.getBlockState(base.offset(i & 1, dy, i >> 1)).is(net.minecraft.tags.BlockTags.LOGS)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Под тобой гора, а не помост: из пяти блоков вниз твёрдые хотя бы четыре. Без этого
     * съёмочная площадка в небе (y = 120) везде считалась пиком.
     */
    static int solidBelow(Level level, BlockPos around) {
        int solid = 0;
        for (int dy = 1; dy <= 5; dy++) {
            if (level.getBlockState(around.below(dy)).isSolid()) {
                solid++;
            }
        }
        return solid;
    }

    /**
     * Настоящая вершина, а не любой склон выше {@link PlaceRules#PEAK_HEIGHT}: в радиусе
     * {@link PlaceRules#SUMMIT_RADIUS} нет земли выше ног больше чем на 2 блока. Гора Хуашань
     * почти вся выше 110, и без этого пиком считался каждый её блок (автор 04.10).
     *
     * <p>Колонка просматривается по блокам сверху вниз, а не по карте высот
     * {@code MOTION_BLOCKING_NO_LEAVES}: клиенту сервер шлёт только {@code MOTION_BLOCKING} и
     * {@code WORLD_SURFACE}, и на клиенте та карта пустая — любой склон был «вершиной», аура
     * горела у медитирующего на склоне (стенд placecheck, 04.10). Листва и стволы — не земля.
     * API: reference/minecraft-src/net/minecraft/world/level/levelgen/Heightmap.java#Types (sendToClient)
     */
    static boolean localSummit(Level level, BlockPos around) {
        int r = PlaceRules.SUMMIT_RADIUS;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx += 2) {
            for (int dz = -r; dz <= r; dz += 2) {
                int x = around.getX() + dx;
                int z = around.getZ() + dz;
                int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
                for (int y = top - 1; y > around.getY() + 1; y--) {
                    BlockState s = level.getBlockState(p.set(x, y, z));
                    if (s.blocksMotion() && !s.is(net.minecraft.tags.BlockTags.LEAVES)
                            && !s.is(net.minecraft.tags.BlockTags.LOGS)) {
                        return false;
                    }
                }
            }
        }
        return true;
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
    private static Place place(ServerPlayer player) {
        return player.getData(ModAttachments.ABSORB).place;
    }

    private static void setPlace(ServerPlayer player, Place place) {
        player.getData(ModAttachments.ABSORB).place = place;
    }

    /** Множитель прироста медитации: ×2 у камня, ×1,5 у природного места, 0 — сразу после волны. */
    public static double gainFactor(ServerPlayer player) {
        Place place = place(player);
        if (place == null) {
            return 1.0D;
        }
        if (PlaceRules.sinceWave(place.anchor().asLong(), player.level().getGameTime()) < PlaceRules.STUN) {
            return 0.0D;
        }
        return place.gain();
    }

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        boolean meditating = player.getData(ModAttachments.MEDITATION).active()
                && player.getData(ModAttachments.CULTIVATION).seeded();
        if (!meditating) {
            setPlace(player, null);
            return;
        }
        if (player.tickCount % 20 == 0) {
            setPlace(player, findPlace(player.level(), player.blockPosition()).orElse(null));
        }
        Place place = place(player);
        if (place == null || !PlaceRules.waveNow(place.anchor().asLong(), player.level().getGameTime())) {
            return;
        }
        wave(player, place.anchor(), place.kind());
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
