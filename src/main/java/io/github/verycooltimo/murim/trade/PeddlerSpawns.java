package io.github.verycooltimo.murim.trade;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Где встречается торговец (docs/design/24-bandit-camp.md §7).
 *
 * <ul>
 *   <li><b>Деревни.</b> Игрок вошёл в деревню — в первый раз торговец там уже есть, дальше он
 *       заходит раз в трое суток с вероятностью 1/2, стоит у центра деревни двое суток и уходит.</li>
 *   <li><b>Подножие Хуашань.</b> Лавка у дороги, в 20 блоках ниже ворот (вне террасы ворот):
 *       навес на жердях и бочка, торговец не уходит. Убит — новый через сутки. Код секты не тронут:
 *       берётся только место массива ({@link MountHuaSites}).</li>
 * </ul>
 *
 * <p>Состояние — {@code data/murim_peddlers.dat}: когда следующий заход в каждую деревню, UUID лавочника.
 * Клиенту не синхронизируется: клиент видит только самого торговца.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/StructureManager.java#getStructureWithPieceAt(BlockPos, TagKey),
 * reference/minecraft-src/net/minecraft/tags/StructureTags.java#VILLAGE,
 * reference/minecraft-src/net/minecraft/world/level/saveddata/SavedData.java#Factory.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class PeddlerSpawns {

    /** Как часто торговец может зайти в одну деревню: раз в трое суток. */
    static final long VILLAGE_INTERVAL = 72000L;
    /** Не зашёл (бросок) — следующая попытка через сутки. */
    static final long VILLAGE_RETRY = 24000L;
    static final double VILLAGE_CHANCE = 0.5D;
    /** Лавочник у Хуашань убит — новый через сутки. */
    static final long STALL_RESPAWN = 24000L;

    /** Лавка у Хуашань: точка в плане массива (u, v), в 20 блоках ниже террасы ворот, сбоку от дороги. */
    static final double STALL_U = -93.0D;
    static final double STALL_V = -426.0D;

    private PeddlerSpawns() {
    }

    // ------------------------------------------------------------------ состояние

    public static final class Data extends SavedData {
        static final String NAME = "murim_peddlers";
        static final Factory<Data> FACTORY = new Factory<>(Data::new, Data::load);

        final Map<Long, Long> villageNext = new HashMap<>();
        UUID stall;
        long stallRespawn;
        boolean stallBuilt;

        private static Data load(CompoundTag tag, HolderLookup.Provider registries) {
            Data d = new Data();
            CompoundTag v = tag.getCompound("villages");
            for (String k : v.getAllKeys()) {
                d.villageNext.put(Long.parseLong(k), v.getLong(k));
            }
            if (tag.hasUUID("stall")) {
                d.stall = tag.getUUID("stall");
            }
            d.stallRespawn = tag.getLong("stall_respawn");
            d.stallBuilt = tag.getBoolean("stall_built");
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            CompoundTag v = new CompoundTag();
            villageNext.forEach((k, t) -> v.putLong(Long.toString(k), t));
            tag.put("villages", v);
            if (stall != null) {
                tag.putUUID("stall", stall);
            }
            tag.putLong("stall_respawn", stallRespawn);
            tag.putBoolean("stall_built", stallBuilt);
            return tag;
        }
    }

    public static Data data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Data.FACTORY, Data.NAME);
    }

    // ------------------------------------------------------------------ тик

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD
                || level.getGameTime() % 100L != 37L || level.players().isEmpty()) {
            return;
        }
        for (ServerPlayer p : level.players()) {
            if (!p.isSpectator()) {
                villageVisit(level, p);
            }
        }
        huaStall(level);
    }

    /** Игрок в деревне: пора ли торговцу зайти. */
    static void villageVisit(ServerLevel level, ServerPlayer player) {
        StructureStart start = village(level, player.blockPosition());
        if (start == null) {
            return;
        }
        Data data = data(level);
        long key = start.getChunkPos().toLong();
        Long next = data.villageNext.get(key);
        long now = level.getGameTime();
        if (next != null && now < next) {
            return;
        }
        BlockPos centre = start.getPieces().get(0).getBoundingBox().getCenter();
        // Центр деревни должен быть прогружен: иначе карта высот пуста и торговец встаёт на дно мира (стенд 04.10).
        if (!level.isPositionEntityTicking(centre)
                || !level.getEntitiesOfClass(Peddler.class, new AABB(centre).inflate(96.0D, 256.0D, 96.0D)).isEmpty()) {
            return;
        }
        // Первый раз — наверняка: игрок должен узнать, что торговец есть. Дальше — бросок.
        if (next != null && level.random.nextDouble() >= VILLAGE_CHANCE) {
            data.villageNext.put(key, now + VILLAGE_RETRY);
            data.setDirty();
            return;
        }
        Peddler peddler = arrive(level, centre, Peddler.VILLAGE_STAY);
        data.villageNext.put(key, now + VILLAGE_INTERVAL);
        data.setDirty();
        if (peddler != null) {
            player.displayClientMessage(Component.translatable("murim.peddler.arrived").withStyle(ChatFormatting.GOLD), true);
            MurimMod.LOGGER.info("Торговец пришёл в деревню у {}", peddler.blockPosition().toShortString());
        }
    }

    /**
     * Деревня, в чьей общей коробке стоит игрок (по x и z), а не в коробке отдельной постройки: коробки улиц
     * низкие, и ноги игрока на дороге могут оказаться над ними [НЕПРОВЕРЕНО: сравнить с
     * {@code getStructureWithPieceAt} на дорогах деревни]. Проверено {@code /murim peddler} на сиде 20261004.
     */
    static StructureStart village(ServerLevel level, BlockPos pos) {
        var registry = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
        for (StructureStart s : level.structureManager().startsForStructure(new net.minecraft.world.level.ChunkPos(pos),
                st -> registry.wrapAsHolder(st).is(StructureTags.VILLAGE))) {
            var box = s.getBoundingBox();
            if (s.isValid() && !s.getPieces().isEmpty() && pos.getX() >= box.minX() && pos.getX() <= box.maxX()
                    && pos.getZ() >= box.minZ() && pos.getZ() <= box.maxZ()) {
                return s;
            }
        }
        return null;
    }

    /** Поставить торговца на поверхность у точки: {@code stay} тиков (0 — навсегда). */
    public static Peddler arrive(ServerLevel level, BlockPos at, int stay) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
        BlockPos pos = new BlockPos(at.getX(), y, at.getZ());
        Peddler p = y <= level.getMinBuildHeight() ? null : ModTrade.PEDDLER.get().create(level);
        if (p == null) {
            return null;
        }
        p.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, level.random.nextFloat() * 360.0F, 0.0F);
        p.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
        p.settle(pos, stay);
        level.addFreshEntity(p);
        return p;
    }

    /** Лавка у подножия Хуашань: стоит, пока жив лавочник; погиб — новый через сутки. */
    static void huaStall(ServerLevel level) {
        MountHuaSite site = MountHuaSites.get(level.getServer());
        if (site == null) {
            return;
        }
        int[] xz = site.toWorld(STALL_U, STALL_V);
        BlockPos at = new BlockPos(xz[0], 0, xz[1]);
        boolean near = level.players().stream().anyMatch(p -> !p.isSpectator()
                && p.distanceToSqr(xz[0] + 0.5D, p.getY(), xz[1] + 0.5D) < 64.0D * 64.0D);
        if (!near || !level.isPositionEntityTicking(at)) {
            return;
        }
        Data data = data(level);
        if (data.stall != null || level.getGameTime() < data.stallRespawn) {
            return;
        }
        if (!data.stallBuilt) {
            buildStall(level, at);
            data.stallBuilt = true;
        }
        Peddler p = arrive(level, at, 0);
        if (p != null) {
            p.setCustomName(Component.translatable("murim.peddler.hua_name"));
            data.stall = p.getUUID();
            MurimMod.LOGGER.info("Лавка у подножия Хуашань: {}", p.blockPosition().toShortString());
        }
        data.setDirty();
    }

    /**
     * Навес лавки: четыре жерди, крыша из полублоков, бочка с товаром и тюк. Ставится только в воздух
     * и траву — ничего чужого не ломает; на склоне круче блока не строится.
     */
    static void buildStall(ServerLevel level, BlockPos at) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int g = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX() + dx, at.getZ() + dz);
                if (Math.abs(g - y) > 1) {
                    return;
                }
            }
        }
        BlockState fence = Blocks.SPRUCE_FENCE.defaultBlockState();
        for (int dx : new int[] {-1, 1}) {
            for (int dz : new int[] {-1, 1}) {
                for (int k = 0; k < 2; k++) {
                    place(level, at.offset(dx, y - at.getY() + k, dz), fence);
                }
            }
        }
        BlockState slab = Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                place(level, at.offset(dx, y - at.getY() + 2, dz), slab);
            }
        }
        place(level, at.offset(0, y - at.getY(), 1), Blocks.BARREL.defaultBlockState());
        place(level, at.offset(1, y - at.getY(), 0), Blocks.HAY_BLOCK.defaultBlockState());
    }

    private static void place(ServerLevel level, BlockPos pos, BlockState state) {
        BlockState was = level.getBlockState(pos);
        if (was.isAir() || was.canBeReplaced()) {
            level.setBlock(pos, state, 3);
        }
    }

    @SubscribeEvent
    static void onLeave(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof Peddler p) || !(event.getLevel() instanceof ServerLevel level)
                || p.getRemovalReason() != Entity.RemovalReason.KILLED) {
            return;
        }
        Data data = data(level);
        if (p.getUUID().equals(data.stall)) {
            data.stall = null;
            data.stallRespawn = level.getGameTime() + STALL_RESPAWN;
            data.setDirty();
        }
    }
}
