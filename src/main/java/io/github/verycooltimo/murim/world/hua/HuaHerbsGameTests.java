package io.github.verycooltimo.murim.world.hua;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModBlocks;
import io.github.verycooltimo.murim.registry.ModItems;
import io.github.verycooltimo.murim.world.herb.HerbPlantBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class HuaHerbsGameTests {
    private HuaHerbsGameTests() { }

    @GameTest(template = "small_floor", timeoutTicks = 100)
    public static void deterministicNicheDirectionAndTreasure(GameTestHelper h) {
        for (int rotation = 0; rotation < 4; rotation++) {
            MountHuaSite site = new MountHuaSite(0, 0, 67, rotation, 424242L, true);
            BlockPos at = HuaHerbs.blackLingzhiSpot(site);
            h.assertTrue(at.equals(HuaHerbs.blackLingzhiSpot(site)), "niche depends on random call order");
            int[] step = site.rotateDir(1, 0);
            h.assertTrue(HuaHerbs.dropDirection(site).getStepX() == step[0]
                    && HuaHerbs.dropDirection(site).getStepZ() == step[1], "niche faces into the cliff");
            h.assertTrue(HuaHerbs.spots(site).size() == 3, "mountain must have three ordinary plants");
        }
        h.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 100)
    public static void nicheGenerationPassPlacesAndProtectsPlant(GameTestHelper h) {
        // Exercise the actual final-generation herb pass on a measured rock column.
        // The full Overworld owner/neighbour order remains covered by /murim dev hua lingzhi check.
        BlockPos roof = h.absolutePos(new BlockPos(2, 4, 2));
        MountHuaSite initial = new MountHuaSite(0, 0, 67, 0, 424242L, true);
        BlockPos column = HuaHerbs.blackLingzhiSpot(initial);
        MountHuaSite site = new MountHuaSite(roof.getX() - column.getX(), roof.getZ() - column.getZ(),
                67, 0, 424242L, true);
        var level = h.getLevel();
        // The framework caps small_floor with barriers at local Y=5 (PillFlowGameTests#player).
        // realTop scans actual sturdy blocks, so open this column above the measured rock.
        h.setBlock(new BlockPos(2, 5, 2), Blocks.AIR);
        level.setBlock(roof, Blocks.STONE.defaultBlockState(), 3);
        BlockPos plant = roof.below();
        h.assertTrue(HuaHerbs.blackLingzhiSpot(level, site).equals(plant),
                "fixture must expose the measured rock surface before generation");
        // WorldGenLevel-only methods are not called by the herb pass except ensureCanWrite.
        var worldgen = (WorldGenLevel) java.lang.reflect.Proxy.newProxyInstance(
                HuaHerbsGameTests.class.getClassLoader(), new Class<?>[] {WorldGenLevel.class},
                (proxy, method, args) -> method.getName().equals("ensureCanWrite") ? true
                        : method.invoke(level, args));
        HuaHerbs.apply(worldgen, new ChunkPos(roof), site);
        h.assertTrue(HuaHerbs.blackLingzhiSpot(level, site).equals(plant), "niche moved from real rock surface");
        h.assertTrue(level.getBlockState(plant).is(ModBlocks.BLACK_LINGZHI_PLANT.get()), "niche has no plant after generation");
        h.assertTrue(level.getBlockState(plant).canSurvive(level, plant), "niche plant cannot survive");
        for (int dy = -1; dy <= 1; dy++) {
            h.assertTrue(!HuaStampBlocks.set(level, plant.offset(0, dy, 0), Blocks.AIR.defaultBlockState(), 2),
                    "neighbour stamp overwrote niche or shell");
        }
        h.succeed();
    }

    private static Block[] plants() {
        return new Block[] {ModBlocks.THOUSAND_GINSENG_PLANT.get(), ModBlocks.THOUSAND_LINGZHI_PLANT.get(),
                ModBlocks.BLACK_LINGZHI_PLANT.get()};
    }
    private static Item[] herbs() {
        return new Item[] {ModItems.THOUSAND_GINSENG.get(), ModItems.THOUSAND_LINGZHI.get(), ModItems.BLACK_LINGZHI.get()};
    }
    private static BlockPos prepare(GameTestHelper h, int i) {
        BlockPos at = new BlockPos(1 + i, 2, 2);
        h.setBlock(at.below(), i == 2 ? Blocks.STONE : Blocks.PODZOL);
        h.setBlock(at, plants()[i]);
        return at;
    }

    @GameTest(template = "small_floor", timeoutTicks = 100)
    public static void breakingEachPlantYieldsOneHerb(GameTestHelper h) {
        for (int i = 0; i < 3; i++) {
            BlockPos at = prepare(h, i);
            h.getLevel().destroyBlock(h.absolutePos(at), true);
            h.getLevel().destroyBlock(h.absolutePos(at), true);
            h.assertTrue(h.getBlockState(at).isAir(), "harvest left a plant");
            h.assertItemEntityCountIs(herbs()[i], at, 5, 1);
        }
        h.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 100)
    public static void rightClickEachPlantYieldsOneHerb(GameTestHelper h) {
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        for (int i = 0; i < 3; i++) {
            BlockPos at = prepare(h, i), world = h.absolutePos(at);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(world), Direction.UP, world, false);
            h.getBlockState(at).useWithoutItem(h.getLevel(), player, hit);
            h.getBlockState(at).useWithoutItem(h.getLevel(), player, hit);
            h.assertTrue(h.getBlockState(at).isAir(), "right click left a plant");
            h.assertItemEntityCountIs(herbs()[i], at, 5, 1);
        }
        h.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 100)
    public static void silkTouchStillYieldsHerbItems(GameTestHelper h) {
        var tool = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE);
        tool.enchant(h.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH), 1);
        for (int i = 0; i < 3; i++) {
            BlockPos at = prepare(h, i);
            var drops = Block.getDrops(h.getBlockState(at), h.getLevel(), h.absolutePos(at), null, null, tool);
            h.assertTrue(drops.size() == 1 && drops.getFirst().is(herbs()[i]) && drops.getFirst().getCount() == 1,
                    "Silk Touch must yield one herb item, never a plant block");
        }
        h.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 100)
    public static void unsupportedPlantsPopOff(GameTestHelper h) {
        for (int i = 0; i < 3; i++) {
            BlockPos at = prepare(h, i);
            h.setBlock(at.below(), Blocks.AIR);
            h.assertTrue(h.getBlockState(at).isAir(), "plant survived missing floor");
            h.assertItemEntityCountIs(herbs()[i], at, 5, 1);
        }
        BlockPos log = new BlockPos(2, 2, 4), at = log.north();
        h.setBlock(log, Blocks.SPRUCE_LOG);
        h.setBlock(at, ModBlocks.THOUSAND_LINGZHI_PLANT.get().defaultBlockState()
                .setValue(HerbPlantBlock.FACING, Direction.SOUTH));
        h.assertTrue(h.getBlockState(at).canSurvive(h.getLevel(), h.absolutePos(at)), "lingzhi cannot attach to log");
        h.setBlock(log, Blocks.AIR);
        h.assertTrue(h.getBlockState(at).isAir(), "lingzhi survived missing log");
        h.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 100)
    public static void chestLootContainsNoAncientHerbs(GameTestHelper h) throws Exception {
        var manager = h.getLevel().getServer().getResourceManager();
        for (var e : manager.listResources("loot_table/chests", id -> id.getNamespace().equals(MurimMod.MODID)
                && id.getPath().endsWith(".json")).entrySet()) {
            try (var reader = e.getValue().openAsReader()) {
                String json = com.google.gson.JsonParser.parseReader(reader).toString();
                for (String herb : new String[] {"thousand_ginseng", "thousand_lingzhi", "black_lingzhi"}) {
                    h.assertTrue(!json.contains("murim:" + herb), "herb remains in " + e.getKey());
                }
            }
        }
        h.succeed();
    }
}
