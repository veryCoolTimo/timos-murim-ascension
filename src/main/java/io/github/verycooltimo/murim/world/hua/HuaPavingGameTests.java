package io.github.verycooltimo.murim.world.hua;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The sect terraces use the Hua paving family, not grass, dirt path or mossy cobble (author 13.10). */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class HuaPavingGameTests {

    private HuaPavingGameTests() {
    }

    @GameTest(template = "camp_floor", timeoutTicks = 20)
    public static void sectTerracesArePaved(GameTestHelper helper) {
        for (MountHuaPlan.Zone zone : MountHuaPlan.ZONES) {
            if (zone.cave() || zone.id().equals("grove")) {
                continue;
            }
            int paving = 0;
            int total = 0;
            for (int r = 0; r < 16; r++) {
                for (double path = -0.5; path <= 0.9; path += 0.7) {
                    BlockState inner = HuaPaving.surface(zone.id(), 6, r, path);
                    total++;
                    helper.assertTrue(!inner.is(Blocks.DIRT_PATH) && !inner.is(Blocks.MOSSY_COBBLESTONE) && !inner.is(Blocks.SAND),
                            "old ground block on " + zone.id());
                    if (HuaPaving.isPaving(inner)) {
                        paving++;
                    }
                }
            }
            helper.assertTrue(paving * 10 >= total * 8, zone.id() + " is not mostly Hua paving: " + paving + "/" + total);
        }
        helper.assertTrue(HuaPaving.surface("training", 6, 5, 0).is(HuaGround.HUA_COURTYARD_PAVERS.get()), "training pavers");
        helper.assertTrue(HuaPaving.surface("main_hall", 6, 5, 0).is(HuaGround.HUA_RECTANGULAR_PAVING.get()), "hall rectangular");
        helper.assertTrue(HuaPaving.surface("treasury", 6, 5, 0).is(HuaGround.HUA_FLAGSTONE_PAVING.get()), "yard flagstone");
        helper.assertTrue(HuaPaving.surface("sparring", 6, 5, 0).is(HuaGround.HUA_PACKED_EARTH.get()), "sparring earth");
        helper.assertTrue(HuaPaving.isPaving(HuaGround.HUA_COURTYARD_PAVERS_SLAB.get().defaultBlockState()), "slab is paving");
        helper.assertTrue(!HuaPaving.isPaving(Blocks.GRASS_BLOCK.defaultBlockState()), "grass is not paving");
        helper.succeed();
    }
}
