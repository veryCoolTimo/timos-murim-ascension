package io.github.verycooltimo.murim.world.camp;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.Bandit;
import io.github.verycooltimo.murim.entity.BanditArcher;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * GameTest лагеря бандитов (docs/design/24-bandit-camp.md §6): стройка по плану, заселение,
 * тревога, потери и возвращение банды, разгром и добыча на настоящих таблицах (1000 походов).
 *
 * <p>Шаблоны — плоские полы {@code data/murim/structure/camp_floor.nbt} и {@code small_floor.nbt}
 * (tools/gametest/floor_template.py). API: reference/neoforge-src/net/neoforged/neoforge/gametest/GameTestHolder.java,
 * reference/minecraft-src/net/minecraft/gametest/framework/GameTestHelper.java.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class BanditCampTests {

    private static final long SEED = 0x51A7E5L;

    private BanditCampTests() {
    }

    private static BlockPos centre(GameTestHelper helper) {
        return helper.absolutePos(new BlockPos(17, 2, 17));
    }

    private static BanditCampData.Camp camp(GameTestHelper helper, long seed) {
        ServerLevel level = helper.getLevel();
        BlockPos c = centre(helper);
        // Ключ лагеря в тесте — позиция центра (у настоящего лагеря — чанк начала структуры).
        return BanditCamps.data(level).getOrCreate(c.asLong() ^ seed, c, seed);
    }

    private static List<Bandit> members(GameTestHelper helper, BanditCampData.Camp camp) {
        return helper.getLevel().getEntitiesOfClass(Bandit.class, new AABB(camp.centre).inflate(24.0D),
                b -> b.campKey() == camp.key && b.isAlive());
    }

    /** Лагерь строится по плану: частокол, костёр, шатры, сундуки с таблицами добычи, рамки с оружием. */
    @GameTest(template = "camp_floor", timeoutTicks = 60)
    public static void campBuildsFromPlan(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos c = centre(helper);
        CampLayout plan = CampLayout.plan(SEED);
        AABB box = helper.getBounds();
        new CampBuilder(plan, c.getX(), c.getZ(), LiveCampSink.heights(level, plan, c.getX(), c.getZ()),
                new LiveCampSink(level, (int) box.minX, (int) box.minZ, (int) box.maxX - 1, (int) box.maxZ - 1)).build();
        int logs = 0;
        int lootable = 0;
        boolean fire = false;
        for (BlockPos p : BlockPos.betweenClosed((int) box.minX, c.getY() - 2, (int) box.minZ, (int) box.maxX - 1, c.getY() + 8, (int) box.maxZ - 1)) {
            if (level.getBlockState(p).is(Blocks.SPRUCE_LOG) || level.getBlockState(p).is(Blocks.DARK_OAK_LOG)
                    || level.getBlockState(p).is(Blocks.STRIPPED_SPRUCE_LOG)) {
                logs++;
            }
            fire |= level.getBlockState(p).is(Blocks.CAMPFIRE);
            BlockEntity be = level.getBlockEntity(p);
            if ((be instanceof ChestBlockEntity chest && chest.getLootTable() != null)
                    || (be instanceof BarrelBlockEntity barrel && barrel.getLootTable() != null)) {
                lootable++;
            }
        }
        int frames = level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class, box).size();
        helper.assertTrue(fire, "нет костра");
        helper.assertTrue(logs > 150, "частокол слишком редкий: брёвен " + logs);
        helper.assertTrue(lootable == 4, "сундуков с добычей " + lootable + " вместо 4 (2 ящика, телега, главарь)");
        helper.assertTrue(frames == 3, "рамок с оружием " + frames + " вместо 3");
        MurimMod.LOGGER.info("GameTest лагерь: брёвен {}, сундуков с добычей {}, рамок {}", logs, lootable, frames);
        helper.succeed();
    }

    /** Заселение: 5–8 бандитов, один главарь, хотя бы один с ци, есть лучники; все приписаны к лагерю. */
    @GameTest(template = "camp_floor", timeoutTicks = 40)
    public static void campPopulates(GameTestHelper helper) {
        BanditCampData.Camp camp = camp(helper, SEED + 1);
        BanditCamps.populate(helper.getLevel(), camp);
        List<Bandit> all = members(helper, camp);
        helper.assertTrue(all.size() >= 5 && all.size() <= 8, "бандитов " + all.size());
        helper.assertTrue(all.size() == camp.alive.size(), "состояние лагеря не совпадает с миром");
        long chiefs = all.stream().filter(Bandit::isChief).count();
        long qi = all.stream().filter(b -> b.isElite() && !b.isChief()).count();
        long archers = all.stream().filter(b -> b instanceof BanditArcher).count();
        helper.assertTrue(chiefs == 1, "главарей " + chiefs);
        helper.assertTrue(qi >= 1, "нет бандита с ци");
        helper.assertTrue(archers >= 1, "нет лучника");
        helper.assertTrue(all.stream().allMatch(b -> b.isPersistenceRequired() && b.post() != null), "бандит исчезнет вдали или без поста");
        Bandit chief = all.stream().filter(Bandit::isChief).findFirst().orElseThrow();
        helper.assertTrue(chief.hasCustomName() && chief.getMaxHealth() >= 60.0F && chief.rank() == 2, "главарь без имени, здоровья или ранга");
        helper.assertTrue(chief.getData(io.github.verycooltimo.murim.registry.ModAttachments.AURA).rank() == 2, "у главаря нет ауры");
        MurimMod.LOGGER.info("GameTest лагерь: {} бандитов, с ци {}, лучников {}", all.size(), qi, archers);
        helper.succeed();
    }

    /** Тревога: один заметил игрока — цель у всего лагеря. */
    @GameTest(template = "camp_floor", timeoutTicks = 40)
    public static void campAlertsEachOther(GameTestHelper helper) {
        BanditCampData.Camp camp = camp(helper, SEED + 2);
        BanditCamps.populate(helper.getLevel(), camp);
        List<Bandit> all = members(helper, camp);
        Player intruder = helper.makeMockPlayer(GameType.SURVIVAL);
        intruder.moveTo(camp.centre.getX() + 0.5D, camp.centre.getY(), camp.centre.getZ() + 0.5D);
        all.get(0).setTarget(intruder);
        long alerted = all.stream().filter(b -> b.getTarget() == intruder).count();
        helper.assertTrue(alerted == all.size(), "тревогу услышали " + alerted + " из " + all.size());
        helper.succeed();
    }

    /**
     * Потери и возвращение: убитые уходят из состава, лагерь не разгромлен, пока жив хоть один;
     * через сутки банда пополняется; когда убит весь состав — лагерь разгромлен и больше не заселяется.
     */
    @GameTest(template = "camp_floor", timeoutTicks = 200)
    public static void campRefillsUntilCleared(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BanditCampData.Camp camp = camp(helper, SEED + 3);
        BanditCamps.populate(level, camp);
        int roster = camp.alive.size();
        List<Bandit> first = members(helper, camp);
        // Убить всех, кроме одного рядового.
        Bandit survivor = first.stream().filter(b -> !b.isChief()).findFirst().orElseThrow();
        first.stream().filter(b -> b != survivor).forEach(b -> b.kill());
        helper.startSequence()
                .thenExecuteAfter(30, () -> {
                    helper.assertTrue(camp.alive.size() == 1, "после потерь в составе " + camp.alive.size());
                    helper.assertTrue(!camp.cleared, "лагерь разгромлен при живом бандите");
                    // Прошли сутки: банда возвращается (главарь тоже).
                    BanditCamps.refill(level, camp);
                    helper.assertTrue(camp.alive.size() == roster, "после суток " + camp.alive.size() + " из " + roster);
                    helper.assertTrue(members(helper, camp).stream().anyMatch(Bandit::isChief), "главарь не вернулся");
                    members(helper, camp).forEach(b -> b.kill());
                })
                .thenExecuteAfter(30, () -> {
                    helper.assertTrue(camp.cleared, "весь состав убит, а лагерь не разгромлен");
                    BanditCamps.tick(level, camp);
                    helper.assertTrue(members(helper, camp).isEmpty(), "разгромленный лагерь заселился снова");
                })
                .thenSucceed();
    }

    /** Добыча на настоящих таблицах, 1000 походов: поход даёт продвижение, а не только мусор. */
    @GameTest(template = "small_floor", timeoutTicks = 100)
    public static void campLootGivesProgress(GameTestHelper helper) {
        List<CampLootSim.Result> results = CampLootSim.run(helper.getLevel(), 1000, 20261004L);
        results.forEach(r -> MurimMod.LOGGER.info("GameTest добыча лагеря — {}", r));
        CampLootSim.Result fresh = results.get(0);
        CampLootSim.Result afterM2 = results.get(1);
        CampLootSim.Result veteran = results.get(2);
        helper.assertTrue(fresh.newTechnique() >= 0.8D, "новичку новая техника лишь в " + fresh.newTechnique());
        helper.assertTrue(afterM2.useful() >= 0.9D && afterM2.newTechnique() >= 0.5D, "после M2: " + afterM2);
        helper.assertTrue(veteran.useful() >= 0.9D, "ветерану: " + veteran);
        helper.assertTrue(afterM2.pages() < 12.0D, "страниц слишком много: " + afterM2.pages());
        helper.succeed();
    }
}
