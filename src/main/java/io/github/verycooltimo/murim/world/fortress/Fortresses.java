package io.github.verycooltimo.murim.world.fortress;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.boss.BossRegistry;
import io.github.verycooltimo.murim.entity.boss.FortressMaster;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.registry.ModItems;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Жизнь крепостей Зелёного Леса (docs/design/26-boss.md §2, §5): хозяин появляется в кресле,
 * когда игрок подходит; плац защищён, пока идёт бой; победа даёт флаг прорыва 2 и открывает
 * сокровищницу. Через {@link #RESPAWN_TICKS} крепость заселяется снова — только для тех,
 * у кого флага ещё нет (мультиплеер).
 *
 * <p>Почему не спавн при генерации: как и у лагеря — хозяина нужно помнить (жив, повержен,
 * когда), а сущность с потока генерации не попадает в это состояние.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/event/tick/LevelTickEvent.java,
 * .../event/level/BlockEvent.java (BreakEvent, EntityPlaceEvent),
 * reference/minecraft-src/net/minecraft/world/level/storage/loot/LootTable.java#getRandomItems,
 * reference/minecraft-src/net/minecraft/server/ReloadableServerRegistries.java#getLootTable.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class Fortresses {

    /** С какого расстояния до плаца хозяин садится в кресло. */
    public static final double POPULATE_RANGE = 56.0D;

    /** Через сколько крепость заселяется снова: три игровых дня. */
    public static final long RESPAWN_TICKS = 72000L;

    /** Продвинутые книги, «украденные у путников», и их веса (docs/design/26 §5). */
    static final String[] BOOKS = {"seven_plum_blossoms", "wind_god_steps", "tang_twelve_daggers", "tang_five_thunders"};
    static final int[] BOOK_WEIGHTS = {4, 3, 2, 1};

    /**
     * Что лежит в сундуке вместо страницы 24 Движений (автор 23.10: искусство утрачено, крепость его не отдаёт): страница из тетради
     * торговца — улика ({@code murim_quests/vault.json}) о том, где куски искусства и как открыть хранилище главы.
     */
    public static final String SECRET_CLUE = "fortress_page";

    /** Имён хозяев — из канонического списка Зелёного Леса (вики Return of the Mount Hua Sect). */
    static final int NAMES = 6;
    static final int FORTRESS_NAMES = 6;

    private Fortresses() {
    }

    public static FortressData data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FortressData.FACTORY, FortressData.NAME);
    }

    /** Имя хозяина из зерна: первая крепость — Ба Тешань, Крепость Чёрного Ветра. */
    public static Component masterName(long seed) {
        int n = (int) Math.floorMod(seed >>> 3, (long) NAMES);
        int f = (int) Math.floorMod(seed >>> 11, (long) FORTRESS_NAMES);
        return Component.translatable("murim.fortress.master_title",
                Component.translatable("murim.fortress.master." + n), Component.translatable("murim.fortress.name." + f));
    }

    // ------------------------------------------------------------------ поиск крепостей у игроков

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % 20L != 11L || level.players().isEmpty()) {
            return;
        }
        Structure structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(Fortress.KEY);
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
                            FortressPiece piece = piece(level, structure, start);
                            if (piece != null) {
                                tick(level, entry(level, start, piece));
                            }
                        }
                    }
                }
            }
        }
        // Крепости, построенные командой (стенд), — без структуры мира.
        for (FortressData.Entry e : List.copyOf(data(level).all())) {
            if (!seen.contains(e.key)) {
                tick(level, e);
            }
        }
    }

    public static FortressPiece piece(ServerLevel level, Structure structure, long start) {
        ChunkAccess chunk = level.getChunk(ChunkPos.getX(start), ChunkPos.getZ(start), ChunkStatus.STRUCTURE_STARTS, true);
        StructureStart s = chunk == null ? null : chunk.getStartForStructure(structure);
        if (s == null || !s.isValid()) {
            return null;
        }
        for (StructurePiece p : s.getPieces()) {
            if (p instanceof FortressPiece f) {
                return f;
            }
        }
        return null;
    }

    public static FortressData.Entry entry(ServerLevel level, long key, FortressPiece piece) {
        return data(level).getOrCreate(key, new BlockPos(piece.centreX(), piece.floorY(), piece.centreZ()), piece.rotation(), piece.seed());
    }

    /** Живой хозяин крепости или null (ищется по плацу и залу). */
    public static FortressMaster master(ServerLevel level, FortressData.Entry e) {
        FortressMaster found = null;
        if (e.master != null && level.getEntity(e.master) instanceof FortressMaster m && m.isAlive() && m.fortressKey() == e.key) {
            found = m;
        }
        AABB box = new AABB(e.yard).inflate(34.0D, 12.0D, 34.0D);
        for (FortressMaster m : level.getEntitiesOfClass(FortressMaster.class, box, FortressMaster::isAlive)) {
            if (m.fortressKey() != e.key || m == found) {
                continue;
            }
            if (found == null) {
                found = m;
            } else {
                // A duplicate (a reload race): one master per fortress. Keep the one in a fight.
                FortressMaster drop = m;
                if (m.arena() && !found.arena()) {
                    drop = found;
                    found = m;
                }
                drop.discard();
                MurimMod.LOGGER.warn("Fortress {}: duplicate master removed", e.key);
            }
        }
        if (found != null && !found.getUUID().equals(e.master)) {
            e.master = found.getUUID();
            data(level).changed();
        }
        return found;
    }

    /**
     * Шаг жизни крепости: игрок рядом и хозяина нет — посадить его в кресло (первый раз или
     * через три дня после победы, если рядом есть игрок без флага).
     */
    public static void tick(ServerLevel level, FortressData.Entry e) {
        placeHiddenTreasure(level, e, data(level.getServer().overworld()));
        // The master also lives on Peaceful. Wait until the throne chunk ticks entities: a master saved there must
        // be loaded before we decide that there is none (no duplicate after a reload).
        if (!level.isLoaded(e.throne()) || !level.isPositionEntityTicking(e.throne())) {
            return;
        }
        boolean near = false;
        boolean fresh = false;
        for (ServerPlayer p : level.players()) {
            if (!p.isSpectator() && p.distanceToSqr(Vec3.atCenterOf(e.yard)) < POPULATE_RANGE * POPULATE_RANGE) {
                near = true;
                fresh |= !p.getData(BossRegistry.BOSS_DEFEATED);
            }
        }
        if (!near || master(level, e) != null) {
            return;
        }
        if (e.defeatedAt != 0L && (level.getGameTime() - e.defeatedAt < RESPAWN_TICKS || !fresh)) {
            return;
        }
        spawnMaster(level, e);
    }

    public static FortressMaster spawnMaster(ServerLevel level, FortressData.Entry e) {
        FortressMaster m = BossRegistry.FORTRESS_MASTER.get().create(level);
        if (m == null) {
            return null;
        }
        m.settle(e.key, e.throne(), e.yard.getX() + 0.5D, e.yard.getY(), e.yard.getZ() + 0.5D);
        m.finalizeSpawn(level, level.getCurrentDifficultyAt(e.throne()), MobSpawnType.STRUCTURE, null);
        m.setCustomName(masterName(e.seed));
        m.setCustomNameVisible(false);
        level.addFreshEntity(m);
        e.master = m.getUUID();
        FortressBuilder.vaultDoor(liveSink(level), e.yard.getX(), e.yard.getY(), e.yard.getZ(), e.rotation, false);
        data(level).changed();
        MurimMod.LOGGER.info("Крепость {}: хозяин {} в кресле {}", e.key, m.getName().getString(), e.throne());
        return m;
    }

    // ------------------------------------------------------------------ победа

    /** Хозяин повержен: флаг участникам, сокровищница, запись о победе. */
    public static void onMasterDefeated(ServerLevel level, FortressMaster master) {
        List<ServerPlayer> winners = new ArrayList<>();
        for (UUID id : master.participants()) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
            if (p != null) {
                winners.add(p);
            }
        }
        if (winners.isEmpty() && master.getLastHurtByMob() instanceof ServerPlayer p) {
            winners.add(p);
        }
        defeat(level, master, winners);
    }

    /** Победа над хозяином: флаг победителям, запись, сокровищница (GameTest зовёт напрямую). */
    public static void defeat(ServerLevel level, FortressMaster master, List<ServerPlayer> winners) {
        FortressData data = data(level);
        FortressData.Entry e = data.get(master.fortressKey());
        for (ServerPlayer p : winners) {
            // The flag of the breakthrough to the second rank and, once, the qi still missing at its wall (D-2, D-3).
            BossCredit.award(p, Component.translatable("murim.fortress.defeated", master.getDisplayName()));
        }
        if (e == null) {
            return;
        }
        e.defeatedAt = level.getGameTime();
        e.defeats++;
        e.master = null;
        data.changed();
        BlockPos chest = e.vaultChest();
        fillVault(level, chest, facing(e), winners.isEmpty() ? null : winners.get(0), level.getRandom());
        placeDragonTreasure(level, chest, data(level.getServer().overworld()));
        FortressBuilder.vaultDoor(liveSink(level), e.yard.getX(), e.yard.getY(), e.yard.getZ(), e.rotation, true);
        level.playSound(null, chest, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 1.0F, 0.7F);
        MurimMod.LOGGER.info("Крепость {}: хозяин повержен ({} раз), победители {}", e.key, e.defeats,
                winners.stream().map(p -> p.getName().getString()).toList());
    }

    /** Buried beneath the back corner of the vault; diggable from outside, independent of boss and locked door. */
    static BlockPos hiddenTreasure(FortressData.Entry e) {
        return FortressBuilder.world(e.yard.getX(), e.yard.getY() - 1, e.yard.getZ(), e.rotation, 3, 30);
    }

    /** First discovered fortress claims the world's single hidden pill; never refill even after the chest is broken. */
    static void placeHiddenTreasure(ServerLevel level, FortressData.Entry e, FortressData world) {
        BlockPos at = hiddenTreasure(e);
        if (world.greatRestorationPlaced || !level.isLoaded(at)) {
            return;
        }
        level.setBlock(at, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing(e)), Block.UPDATE_ALL);
        if (level.getBlockEntity(at) instanceof ChestBlockEntity chest) {
            chest.setItem(0, new ItemStack(ModItems.PILL_GREAT_RESTORATION.get()));
            world.greatRestorationPlaced = true;
            world.changed();
        }
    }

    /** Only the first boss treasure in the world contains an Ascending Dragon Pill. */
    static void placeDragonTreasure(ServerLevel level, BlockPos at, FortressData world) {
        if (world.ascendingDragonPlaced || !(level.getBlockEntity(at) instanceof ChestBlockEntity chest)) {
            return;
        }
        for (int i = 0; i < chest.getContainerSize(); i++) {
            if (chest.getItem(i).isEmpty()) {
                chest.setItem(i, new ItemStack(ModItems.PILL_ASCENDING_DRAGON.get()));
                world.ascendingDragonPlaced = true;
                world.changed();
                return;
            }
        }
    }

    private static Direction facing(FortressData.Entry e) {
        // Сундук смотрит в зал: локально −Z.
        return e.rotation.rotate(Direction.NORTH);
    }

    /**
     * Сундук сокровищницы с добычей: пилюли и серебро из таблицы {@link Fortress#LOOT_VAULT}, плюс
     * продвинутая книга, которой победитель не знает, и страница-улика о 24 Движениях (самой техники в ней нет).
     *
     * @return что положено в сундук
     */
    public static List<ItemStack> fillVault(ServerLevel level, BlockPos at, Direction facing, ServerPlayer winner, RandomSource random) {
        level.setBlock(at, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing), Block.UPDATE_ALL);
        List<ItemStack> items = new ArrayList<>(level.getServer().reloadableRegistries().getLootTable(Fortress.LOOT_VAULT)
                .getRandomItems(new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(at))
                        .create(LootContextParamSets.CHEST), random));
        ItemStack book = new ItemStack(ModItems.TECHNIQUE_MANUAL.get());
        book.set(ModDataComponents.TECHNIQUE.get(), pickBook(winner, random));
        items.add(0, book);
        items.add(1, io.github.verycooltimo.murim.quest.Clues.stack(SECRET_CLUE));
        if (level.getBlockEntity(at) instanceof ChestBlockEntity chest) {
            chest.clearContent();
            int slot = 0;
            for (ItemStack s : items) {
                if (slot < chest.getContainerSize()) {
                    chest.setItem(slot, s);
                    slot += 2;
                    if (slot >= chest.getContainerSize()) {
                        slot = 1;
                    }
                }
            }
        }
        return items;
    }

    /** Книга, которой победитель ещё не знает (веса §5); знает все — любая (даст «Перечитать»). */
    public static ResourceLocation pickBook(ServerPlayer winner, RandomSource random) {
        List<Integer> open = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < BOOKS.length; i++) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, BOOKS[i]);
            if (winner == null || !MasteryService.knows(winner, id)) {
                open.add(i);
                total += BOOK_WEIGHTS[i];
            }
        }
        if (open.isEmpty()) {
            return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, BOOKS[random.nextInt(BOOKS.length)]);
        }
        int roll = random.nextInt(total);
        for (int i : open) {
            roll -= BOOK_WEIGHTS[i];
            if (roll < 0) {
                return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, BOOKS[i]);
            }
        }
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, BOOKS[open.get(0)]);
    }

    // ------------------------------------------------------------------ плац неразрушаем в бою

    @SubscribeEvent
    static void onBreak(BlockEvent.BreakEvent event) {
        if (inFight(event.getLevel(), event.getPos())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (inFight(event.getLevel(), event.getPos())) {
            event.setCanceled(true);
        }
    }

    /** Идёт ли бой на плацу, которому принадлежит блок (пол, воздух над ним до 10 блоков). */
    public static boolean inFight(LevelAccessor level, BlockPos pos) {
        if (!(level instanceof ServerLevel server)) {
            return false;
        }
        for (FortressMaster m : server.getEntitiesOfClass(FortressMaster.class, new AABB(pos).inflate(40.0D), FortressMaster::arena)) {
            org.joml.Vector3f y = m.yard();
            if (Math.abs(pos.getX() + 0.5D - y.x) <= FortressMaster.YARD_HALF + 0.5D && Math.abs(pos.getZ() + 0.5D - y.z) <= FortressMaster.YARD_HALF + 0.5D
                    && pos.getY() >= y.y - 3 && pos.getY() <= y.y + 10) {
                return true;
            }
        }
        return false;
    }

    /** Блоки в живом мире (тест, команда, двери сокровищницы). */
    public static FortressBuilder.Sink liveSink(ServerLevel level) {
        return new FortressBuilder.Sink() {
            @Override
            public boolean owns(int x, int z) {
                return true;
            }

            @Override
            public BlockState get(int x, int y, int z) {
                return level.getBlockState(new BlockPos(x, y, z));
            }

            @Override
            public void set(int x, int y, int z, BlockState state) {
                level.setBlock(new BlockPos(x, y, z), state, Block.UPDATE_ALL);
            }
        };
    }

    /** Ближайшая крепость к точке: по структурам вокруг (радиус в чанках) и по уже известным. */
    public static FortressData.Entry nearest(ServerLevel level, BlockPos from) {
        FortressData.Entry best = null;
        double bestD = Double.MAX_VALUE;
        for (FortressData.Entry e : data(level).all()) {
            double d = e.yard.distSqr(from);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }
}
