package io.github.verycooltimo.murim.world.herb;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.quest.HerbHabitats;
import io.github.verycooltimo.murim.quest.MedicinalPlant;
import io.github.verycooltimo.murim.quest.QuestLoader;
import io.github.verycooltimo.murim.registry.ModBlocks;
import io.github.verycooltimo.murim.sect.SectGameTests;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** K2 integration. API verified in supplied 1.21.1 sources: BlockStateBase#tick,
 * LevelTicks#hasScheduledTick, ResourceProvider#getResource; existing quest harvest fixtures.
 * These tests must be run by the tester, never on the corporate editing machine.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class WaveOneHerbsGameTests {
    @GameTest(template = "sect_yard", timeoutTicks = 60)
    public static void youngGinsengLeavesARegrowingRootAndWildAgeRatioIsNineToOne(GameTestHelper h) {
        var young = QuestLoader.herbs().stream().map(QuestLoader.Located::step)
                .filter(e -> e.id().equals("hundred_ginseng")).findFirst().orElseThrow();
        h.assertTrue(young.regrowDays() == 6, "young root must regrow in six days");
        int ancient = 0, common = 0;
        for (int roll = 0; roll < 10; roll++) {
            var plant = WildHerbFeature.ginsengPlant(roll);
            if (plant.is(ModBlocks.THOUSAND_GINSENG_PLANT.get())) ancient++;
            if (plant.is(ModBlocks.HUNDRED_GINSENG_PLANT.get())) common++;
        }
        h.assertTrue(common == 9 && ancient == 1, "wild ginseng age slots are not 9:1");
        var resources = h.getLevel().getServer().getResourceManager();
        for (String name : new String[] {"wild_ginseng", "wild_lingzhi"}) {
            h.assertTrue(resources.getResource(ResourceLocation.parse("murim:worldgen/configured_feature/" + name + ".json")).isPresent()
                    && resources.getResource(ResourceLocation.parse("murim:worldgen/placed_feature/" + name + ".json")).isPresent()
                    && resources.getResource(ResourceLocation.parse("murim:neoforge/biome_modifier/" + name + ".json")).isPresent(),
                    "wild herb has no worldgen supply table: " + name);
        }
        var p = SectGameTests.fakePlayer(h);
        var pos = h.absolutePos(new BlockPos(10, 2, 12));
        h.getLevel().setBlock(pos.below(), Blocks.DIRT.defaultBlockState(), 3);
        h.getLevel().setBlock(pos, ModBlocks.HUNDRED_GINSENG_PLANT.get().defaultBlockState(), 3);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHEARS));
        h.assertTrue(MedicinalPlant.harvest(p, pos), "young ginseng was not harvested");
        h.assertTrue(!MedicinalPlant.harvest(p, pos), "cut root yielded twice");
        h.assertTrue(h.getLevel().getBlockTicks().hasScheduledTick(pos, ModBlocks.HUNDRED_GINSENG_PLANT.get()),
                "harvest did not schedule regrowth");
        // Exercise the scheduled callback directly; do not wait six Minecraft days in this fixture.
        var cut = h.getLevel().getBlockState(pos);
        cut.tick(h.getLevel(), pos, RandomSource.create(1101));
        h.assertTrue(!h.getLevel().getBlockState(pos).getValue(MedicinalPlant.CUT), "root did not regrow");
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 40)
    public static void woodHerbUsesHorizontalSpawnAnnulusAndCarriesBookMetadata(GameTestHelper h) {
        var herb = QuestLoader.herbs().stream().map(QuestLoader.Located::step)
                .filter(e -> e.id().equals("purple_wood_herb")).findFirst().orElseThrow();
        var spawn = new BlockPos(123, 200, -456);
        h.assertTrue(!HerbHabitats.withinSpawnDistance(spawn.offset(899, -200, 0), spawn, herb), "inside inner boundary");
        h.assertTrue(HerbHabitats.withinSpawnDistance(spawn.offset(900, -200, 0), spawn, herb), "inner boundary rejected");
        h.assertTrue(HerbHabitats.withinSpawnDistance(spawn.offset(1500, -200, 2000), spawn, herb), "outer diagonal rejected");
        h.assertTrue(!HerbHabitats.withinSpawnDistance(spawn.offset(2501, -200, 0), spawn, herb), "outside outer boundary");
        h.assertTrue(herb.waterRange() > 0 && herb.maxY() <= 65 && herb.rarity() == 1, "wood herb is not a common wet valley plant");
        for (var e : QuestLoader.herbs()) {
            h.assertTrue(!e.step().origin().isBlank() && !e.step().rawEffect().isBlank() && !e.step().habitat().isBlank(),
                    "missing herbarium data: " + e.step().id());
        }
        h.succeed();
    }

    private WaveOneHerbsGameTests() { }
}
