package io.github.verycooltimo.murim.world.camp;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.Bandit;
import io.github.verycooltimo.murim.entity.BanditArcher;
import io.github.verycooltimo.murim.entity.BanditSwordsman;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.sect.PlayerJournal;
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
 * подходит к лагерю; потери пополняются на рассвете без игрока рядом. Разгромленный лагерь
 * становится руинами, а через 5–7 игровых дней банда строит новый в другом месте (CampLife).
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
    public static final double REFILL_MIN_RANGE = 48.0D;

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
        // Snapshot: replacement may register a new camp while visiting an old ruin.
        List<BanditCampData.Camp> known = new java.util.ArrayList<>();
        data(level).all().forEach(known::add);
        for (BanditCampData.Camp camp : known) tick(level, camp);
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
        if (piece.shell()) {
            CampTemplate.attach(camp, piece, CampTemplate.templatePiece(level, structure, start));
        }
        tick(level, camp);
    }

    /**
     * Шаг жизни лагеря: расписание, руины и новый лагерь, заселение при подходе, тихое
     * пополнение на рассвете. Общий для поиска у игроков, команды и GameTest.
     */
    public static void tick(ServerLevel level, BanditCampData.Camp camp) {
        if (level.getDifficulty() == Difficulty.PEACEFUL) return;
        CampLife.tick(level, camp);
        if (camp.cleared || !ready(level, camp)) return;
        double nearest = nearestPlayer(level, camp.centre);
        if (!camp.populated) {
            if (nearest <= POPULATE_RANGE) populate(level, camp);
            return;
        }
        // An unloaded expedition slot is restored quietly, independently of ordinary losses.
        if (!camp.returning.isEmpty() && nearest > REFILL_MIN_RANGE) {
            restoreTravellers(level, camp);
        }
        long day = Math.floorDiv(level.getDayTime(), CampLife.DAY);
        long time = Math.floorMod(level.getDayTime(), CampLife.DAY);
        if (time < 1000L && camp.refillDay != day && nearest > REFILL_MIN_RANGE) {
            camp.refillDay = day;
            refill(level, camp);
            data(level).setDirty();
        }
    }

    public static void restoreTravellers(ServerLevel level, BanditCampData.Camp camp) {
        if (!ready(level, camp) || camp.cleared) return;
        for (CampRoster.Member member : CampTemplate.roster(camp)) {
            if (!camp.returning.contains(member.slot()) || camp.alive.containsKey(member.slot())) continue;
            Bandit b = spawn(level, camp, CampTemplate.posts(camp), member);
            if (b != null) {
                camp.alive.put(member.slot(), b.getUUID());
                camp.returning.remove(member.slot());
            }
        }
        data(level).setDirty();
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
        for (CampLayout.Post p : CampTemplate.posts(camp)) {
            if (!level.isPositionEntityTicking(camp.centre.offset(p.dx(), 0, p.dz()))) {
                return false;
            }
        }
        return true;
    }

    /** Первое заселение: весь состав. */
    public static void populate(ServerLevel level, BanditCampData.Camp camp) {
        if (camp.populated || camp.cleared) return;
        camp.populated = true;
        camp.refillDay = Math.floorDiv(level.getDayTime(), CampLife.DAY);
        refill(level, camp);
        MurimMod.LOGGER.info("Лагерь бандитов {} заселён: {} бандитов", camp.centre.toShortString(), camp.alive.size());
    }

    /** Поставить недостающих по составу (главарь тоже возвращается, пока лагерь не разгромлен). */
    public static void refill(ServerLevel level, BanditCampData.Camp camp) {
        List<CampRoster.Member> roster = CampTemplate.roster(camp);
        List<CampLayout.Post> posts = CampTemplate.posts(camp);
        for (CampRoster.Member m : roster) {
            if (camp.cleared || camp.alive.containsKey(m.slot()) || camp.returning.contains(m.slot())) {
                continue;
            }
            Bandit b = spawn(level, camp, posts, m);
            if (b != null) {
                camp.alive.put(m.slot(), b.getUUID());
            }
        }
        data(level).setDirty();
    }

    private static Bandit spawn(ServerLevel level, BanditCampData.Camp camp, List<CampLayout.Post> posts, CampRoster.Member m) {
        int index = Math.min(m.post(), posts.size() - 1);
        CampLayout.Post post = posts.get(index);
        int x = camp.centre.getX() + post.dx();
        int z = camp.centre.getZ() + post.dz();
        // Знак spawn:* шаблона стоит там, где бандит должен стоять (в шатре, на вышке), — не поверх крыши.
        int y = camp.templateFeet != null && index < camp.templateFeet.size() ? camp.templateFeet.get(index).getY()
                : LiveCampSink.surface(level, x, z) + 1;
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
        // Register before EntityJoinLevelEvent validates roster ownership.
        camp.alive.put(m.slot(), b.getUUID());
        if (!level.addFreshEntity(b)) {
            camp.alive.remove(m.slot(), b.getUUID());
            return null;
        }
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
        boolean away = camp.travellers.contains(source.campSlot());
        AABB area = new AABB(away ? source.blockPosition() : camp.centre).inflate(40.0D);
        for (Bandit other : level.getEntitiesOfClass(Bandit.class, area,
                b -> b != source && b.campKey() == source.campKey() && b.isAlive() && b.getTarget() == null
                        && camp.travellers.contains(b.campSlot()) == away)) {
            other.setTarget(target);
        }
        if (!away && level.getGameTime() - camp.lastAlarm >= ALARM_COOLDOWN) {
            camp.lastAlarm = level.getGameTime();
            level.playSound(null, camp.centre.above(3), SoundEvents.BELL_BLOCK, SoundSource.HOSTILE, 2.5F, 0.7F);
            if (target instanceof ServerPlayer player) {
                PlayerJournal.record(player, Component.translatable("murim.camp.alarm").withStyle(ChatFormatting.RED));
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
        BanditCampData data = data(level);
        BanditCampData.Camp camp = data.get(b.campKey());
        if (camp == null) {
            return;
        }
        UUID id = camp.alive.get(b.campSlot());
        if (id == null || !id.equals(b.getUUID())) {
            return;
        }
        boolean unloaded = reason != Entity.RemovalReason.KILLED && reason != Entity.RemovalReason.DISCARDED;
        if (unloaded && !camp.travellers.contains(b.campSlot())) return;
        if (unloaded) {
            returnUnloaded(level, b);
            return;
        }
        camp.alive.remove(b.campSlot());
        camp.travellers.remove(b.campSlot());
        data.setDirty();
        if (reason == Entity.RemovalReason.KILLED) {
            camp.lastLoss = level.getGameTime();
            camp.refillDay = Math.floorDiv(level.getDayTime(), CampLife.DAY);
            if (camp.alive.isEmpty() && camp.returning.isEmpty()) {
                clear(level, camp);
            }
        }
    }

    /** Transfer ownership before a replacement is spawned; late chunk loads fail onJoin's UUID check. */
    public static void returnUnloaded(ServerLevel level, Bandit b) {
        var c = data(level).get(b.campKey());
        if (c == null || !c.travellers.contains(b.campSlot()) || !b.getUUID().equals(c.alive.get(b.campSlot()))) return;
        c.alive.remove(b.campSlot());
        c.returning.add(b.campSlot());
        c.travellers.remove(b.campSlot());
        c.tripTarget = c.centre;
        data(level).setDirty();
    }

    /** Reject an expedition entity saved in an unloaded chunk after its slot was restored. */
    @SubscribeEvent
    static void onJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof Bandit b) || !(event.getLevel() instanceof ServerLevel level)
                || b.campKey() == Bandit.NO_CAMP) return;
        BanditCampData.Camp camp = data(level).get(b.campKey());
        if (camp == null || camp.cleared || !b.getUUID().equals(camp.alive.get(b.campSlot()))) event.setCanceled(true);
    }

    /** Лагерь разгромлен: больше не заселяется. */
    public static void clear(ServerLevel level, BanditCampData.Camp camp) {
        if (camp.cleared) return;
        camp.cleared = true;
        camp.nextCamp = level.getDayTime() + (5L + Math.floorMod(camp.seed, 3L)) * CampLife.DAY;
        CampLife.ruin(level, camp);
        data(level).setDirty();
        MurimMod.LOGGER.info("Лагерь бандитов {} разгромлен", camp.centre.toShortString());
        io.github.verycooltimo.murim.sect.SectContracts.campCleared(level, camp);
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(camp.centre.getX(), p.getY(), camp.centre.getZ()) < 96.0D * 96.0D) {
                io.github.verycooltimo.murim.advancement.MurimAdvancements.grant(p, "camp_cleared");
                PlayerJournal.record(p, Component.translatable("murim.camp.cleared").withStyle(ChatFormatting.GOLD));
                level.playSound(null, p.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.8F, 1.0F);
            }
        }
    }
}
