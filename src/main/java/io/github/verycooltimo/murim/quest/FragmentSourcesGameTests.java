package io.github.verycooltimo.murim.quest;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.sect.SectGameTests;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Loaded rewards and the production extraction ledger; no clue blocks or injected fragment flags. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class FragmentSourcesGameTests {
    private FragmentSourcesGameTests() { }

    @GameTest(template = "sect_yard", batch = "sect_journey", timeoutTicks = 100)
    public static void allSixFragmentsArePersonalChestItemsAndSurviveReopening(GameTestHelper h) {
        var first = SectGameTests.fakePlayer(h);
        var second = SectGameTests.fakePlayer(h);
        BlockPos at = h.absolutePos(new BlockPos(3, 2, 3));
        h.getLevel().setBlock(at.below(), Blocks.STONE.defaultBlockState(), 3);
        PersonalRewards.ensureModel(h.getLevel(), at);
        h.assertTrue(h.getLevel().getBlockState(at).is(Blocks.CHEST), "ordinary chest was not placed");
        int count = 0;
        for (var source : QuestLoader.personalChests()) {
            if (!source.quest().equals("fragments")) continue;
            h.assertTrue(source.step().marker().startsWith("zone:"), "source depends on a parked site");
            var contents = new PersonalRewards.Ledger(first, source, at);
            for (int slot = 0; slot < source.step().items().size(); slot++) {
                var item = contents.getItem(slot).copy();
                h.assertTrue(item.getItem() instanceof ClueItem, "fragment lost its readable item");
                String clue = ClueItem.clueOf(item);
                if (slot % 2 == 0) contents.removeItem(slot, 1);
                else {
                    // ChestMenu quick move mutates the stack then calls setChanged.
                    contents.getItem(slot).shrink(1);
                    contents.setChanged();
                }
                h.assertTrue(Clues.known(first, clue), "taking a fragment did not record it: " + clue);
                count++;
            }
            h.assertTrue(new PersonalRewards.Ledger(first, source, at).isEmpty(), "reopening duplicated fragments");
            h.assertTrue(!new PersonalRewards.Ledger(second, source, at).isEmpty(), "first player stole second player's fragments");
        }
        h.assertTrue(count == 6, "not all six fragments have sources");
        for (int i = 0; i < 6; i++) h.assertTrue(Clues.known(first, PlumRestoration.clue(i)), "missing fragment " + i);
        h.assertTrue(PlumRestoration.restored(PlumRestoration.known(first)).equals(java.util.List.of(0))
                && PlumRestoration.cap(PlumRestoration.known(first)) == 5, "fragments exceeded Rainfall at five stars");
        h.succeed();
    }
}
