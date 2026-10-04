package io.github.verycooltimo.murim.world.camp;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.Bandit;
import io.github.verycooltimo.murim.entity.BanditArcher;
import io.github.verycooltimo.murim.entity.BanditSwordsman;
import io.github.verycooltimo.murim.registry.ModEntities;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.List;
import java.util.UUID;

/**
 * Жизнь лагерей бандитов (docs/design/24-bandit-camp.md §2): банда появляется, когда игрок
 * подходит к лагерю; погибшие возвращаются через игровые сутки, пока лагерь не разгромлен;
 * разгромленный (убит весь состав, главарь тоже) больше не заселяется.
 *
 * <p>Почему не спавн при генерации (как ведьма в хижине): состав нужно помнить — кто жив, кто
 * убит, разгромлен ли лагерь, — а сущность, созданная на потоке генерации, не попадает в
 * состояние лагеря. Здесь всё на главном потоке сервера: тик уровня раз в секунду и выход
 * сущности из мира.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/event/tick/LevelTickEvent.java,
 * reference/neoforge-src/net/neoforged/neoforge/event/entity/EntityLeaveLevelEvent.java,
 * reference/minecraft-src/net/minecraft/world/level/chunk/ChunkAccess.java#getReferencesForStructure/#getStartForStructure.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class BanditCamps {

    /** С какого расстояния до центра лагерь заселяется. */
    public static final double POPULATE_RANGE = 64.0D;

    /** Ближе этого банда не возвращается: никто не появляется у игрока на глазах. */
    public static final double REFILL_MIN_RANGE = 28.0D;

    /** Через сколько тиков после последней потери банда пополняется: игровые сутки. */
    public static final long RESPAWN_TICKS = 24000L;

    /** Тревога (колокол) не чаще раза в 30 с. */
    static final long ALARM_COOLDOWN = 600L;

    private BanditCamps() {
    }

    public static BanditCampData data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(BanditCampData.FACTORY, BanditCampData.NAME);
    }

    // ------------------------------------------------------------------ поиск лагерей у игроков

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % 20L != 7L) {
            return;
        }
        if (level.players().isEmpty()) {
            return;
        }
        Structure structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(BanditCamp.KEY);
        if (structure == null) {
            return;
        }
        LongSet seen = new LongOpenHashSet();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            ChunkPos pc = player.chunkPosition();
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    if (!level.hasChunk(pc.x + dx, pc.z + dz)) {
                        continue;
                    }
                    for (long start : level.getChunk(pc.x + dx, pc.z + dz).getReferencesForStructure(structure)) {
                        if (seen.add(start)) {
                            visit(level, structure, start);
                        }
                    }
                }
            }
        }
    }

    /** Найти кусок лагеря по чанку начала структуры. */
    public static BanditCampPiece piece(ServerLevel level, Structure structure, long start) {
        ChunkAccess chunk = level.getChunk(ChunkPos.getX(start), ChunkPos.getZ(start), ChunkStatus.STRUCTURE_STARTS, true);
        StructureStart s = chunk == null ? null : chunk.getStartForStructure(structure);
        if (s == null || !s.isValid()) {
            return null;
        }
        for (StructurePiece p : s.getPieces()) {
            if (p instanceof BanditCampPiece camp) {
                return camp;
            }
        }
        return null;
    }

    private static void visit(ServerLevel level, Structure structure, long start) {
        BanditCampPiece piece = piece(level, structure, start);
        if (piece == null) {
            return;
        }
        BlockPos centre = new BlockPos(piece.centreX(), piece.height(0), piece.centreZ());
        BanditCampData.Camp camp = data(level).getOrCreate(start, centre, piece.seed());
        tick(level, camp);
    }

    /**
     * Шаг жизни лагеря: заселить, если игрок близко и лагерь пуст; пополнить через сутки после
     * потерь, если игрок не вплотную. Общий для поиска у игроков, команды и GameTest.
     */
    public static void tick(ServerLevel level, BanditCampData.Camp camp) {
        if (camp.cleared || level.getDifficulty() == Difficulty.PEACEFUL) {
            return;
        }
        double nearest = nearestPlayer(level, camp.centre);
        if (nearest > POPULATE_RANGE || !ready(level, camp)) {
            return;
        }
        if (!camp.populated) {
            populate(level, camp);
            return;
        }
        List<CampRoster.Member> roster = CampRoster.plan(CampLayout.plan(camp.seed));
        if (camp.alive.size() < roster.size() && nearest > REFILL_MIN_RANGE
                && level.getGameTime() - camp.lastLoss >= RESPAWN_TICKS) {
            refill(level, camp);
        }
    }

    private static double nearestPlayer(ServerLevel level, BlockPos centre) {
        double best = Double.MAX_VALUE;
        for (ServerPlayer p : level.players()) {
            if (!p.isSpectator()) {
                best = Math.min(best, Math.sqrt(p.distanceToSqr(centre.getX() + 0.5D, p.getY(), centre.getZ() + 0.5D)));
            }
        }
        return best;
    }

    /** Чанки лагеря загружены и тикают сущности — можно ставить банду. */
    private static boolean ready(ServerLevel level, BanditCampData.Camp camp) {
        CampLayout plan = CampLayout.plan(camp.seed);
        for (CampLayout.Post p : plan.posts()) {
            if (!level.isPositionEntityTicking(camp.centre.offset(p.dx(), 0, p.dz()))) {
                return false;
            }
        }
        return true;
    }

    /** Первое заселение: весь состав. */
    public static void populate(ServerLevel level, BanditCampData.Camp camp) {
        camp.populated = true;
        camp.alive.clear();
        refill(level, camp);
        MurimMod.LOGGER.info("Лагерь бандитов {} заселён: {} бандитов", camp.centre.toShortString(), camp.alive.size());
    }

    /** Поставить недостающих по составу (главарь тоже возвращается, пока лагерь не разгромлен). */
    public static void refill(ServerLevel level, BanditCampData.Camp camp) {
        CampLayout plan = CampLayout.plan(camp.seed);
        List<CampRoster.Member> roster = CampRoster.plan(plan);
        for (CampRoster.Member m : roster) {
            if (camp.alive.containsKey(m.slot())) {
                continue;
            }
            Bandit b = spawn(level, camp, plan, m);
            if (b != null) {
                camp.alive.put(m.slot(), b.getUUID());
            }
        }
        data(level).setDirty();
    }

    private static Bandit spawn(ServerLevel level, BanditCampData.Camp camp, CampLayout plan, CampRoster.Member m) {
        CampLayout.Post post = plan.posts().get(Math.min(m.post(), plan.posts().size() - 1));
        int x = camp.centre.getX() + post.dx();
        int z = camp.centre.getZ() + post.dz();
        int y = LiveCampSink.surface(level, x, z) + 1;
        Bandit b = m.archer() ? ModEntities.BANDIT_ARCHER.get().create(level) : ModEntities.BANDIT_SWORDSMAN.get().create(level);
        if (b == null) {
            return null;
        }
        float yaw = (float) (Mth.atan2(camp.centre.getZ() - z, camp.centre.getX() - x) * Mth.RAD_TO_DEG) - 90.0F;
        b.moveTo(x + 0.5D, y, z + 0.5D, yaw, 0.0F);
        b.markCampSpawn();
        b.finalizeSpawn(level, level.getCurrentDifficultyAt(b.blockPosition()), MobSpawnType.STRUCTURE, null);
        b.joinCamp(camp.key, m.slot(), new BlockPos(x, y, z), post.onTower());
        if (m.chief() && b instanceof BanditSwordsman s) {
            s.makeChief(chiefName(camp.seed));
        } else if (m.qi() && b instanceof BanditSwordsman s) {
            s.makeElite();
        } else if (m.qi() && b instanceof BanditArcher a) {
            a.makeElite();
        }
        level.addFreshEntity(b);
        return b;
    }

    /** Имя главаря из зерна лагеря: прозвище и фамилия («Чёрный Тигр Ван»). */
    public static Component chiefName(long seed) {
        long h = CampLayout.hash(seed, 7, 11);
        return Component.translatable("murim.camp.chief_name",
                Component.translatable("murim.camp.epithet." + (h % 8L)),
                Component.translatable("murim.camp.surname." + ((h / 8L) % 8L))).withStyle(ChatFormatting.GOLD);
    }

    // ------------------------------------------------------------------ тревога и потери

    /** Бандит заметил игрока: поднять весь лагерь, ударить в колокол (не чаще раза в 30 с). */
    public static void alert(ServerLevel level, Bandit source, LivingEntity target) {
        BanditCampData.Camp camp = data(level).get(source.campKey());
        if (camp == null) {
            return;
        }
        AABB area = new AABB(camp.centre).inflate(40.0D);
        for (Bandit other : level.getEntitiesOfClass(Bandit.class, area,
                b -> b != source && b.campKey() == source.campKey() && b.isAlive() && b.getTarget() == null)) {
            other.setTarget(target);
        }
        if (level.getGameTime() - camp.lastAlarm >= ALARM_COOLDOWN) {
            camp.lastAlarm = level.getGameTime();
            level.playSound(null, camp.centre.above(3), SoundEvents.BELL_BLOCK, SoundSource.HOSTILE, 2.5F, 0.7F);
            if (target instanceof ServerPlayer player) {
                player.displayClientMessage(Component.translatable("murim.camp.alarm").withStyle(ChatFormatting.RED), true);
            }
        }
    }

    @SubscribeEvent
    static void onLeave(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof Bandit b) || b.campKey() == Bandit.NO_CAMP
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Entity.RemovalReason reason = b.getRemovalReason();
        if (reason != Entity.RemovalReason.KILLED && reason != Entity.RemovalReason.DISCARDED) {
            return;
        }
        BanditCampData data = data(level);
        BanditCampData.Camp camp = data.get(b.campKey());
        if (camp == null) {
            return;
        }
        UUID id = camp.alive.get(b.campSlot());
        if (id == null || !id.equals(b.getUUID())) {
            return;
        }
        camp.alive.remove(b.campSlot());
        data.setDirty();
        if (reason == Entity.RemovalReason.KILLED) {
            camp.lastLoss = level.getGameTime();
            if (camp.alive.isEmpty()) {
                clear(level, camp);
            }
        }
    }

    /** Лагерь разгромлен: больше не заселяется. */
    public static void clear(ServerLevel level, BanditCampData.Camp camp) {
        camp.cleared = true;
        data(level).setDirty();
        MurimMod.LOGGER.info("Лагерь бандитов {} разгромлен", camp.centre.toShortString());
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(camp.centre.getX(), p.getY(), camp.centre.getZ()) < 96.0D * 96.0D) {
                p.displayClientMessage(Component.translatable("murim.camp.cleared").withStyle(ChatFormatting.GOLD), false);
                level.playSound(null, p.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.8F, 1.0F);
            }
        }
    }
}
