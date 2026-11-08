package io.github.verycooltimo.murim.quest.world;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.world.hua.HuaBaked;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The stele ledge and its steps are laid over the mountain after the trail. The last steps of the run end on the trail bed, 15 blocks
 * under the ledge, and filled their columns up to the ledge's height: a plug of rock in the trail (author's world 23.10, 3 columns
 * 15 blocks above the plan). The trail columns must keep the height the plan gives them, at every rotation.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class HuaSteleTrailGameTests {
    /** Columns this close to a trail sample are the trail bed (as the trail mend counts it). */
    private static final double TRAIL_BED = 2.1;

    private HuaSteleTrailGameTests() {
    }

    @GameTest(template = "camp_floor", timeoutTicks = 600)
    public static void steleLeavesTheTrailBedAlone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos corner = helper.absolutePos(BlockPos.ZERO);
        List<String> problems = new ArrayList<>();
        int[] seen = new int[2];
        for (int rot = 0; rot < 4; rot++) {
            MountHuaSite site = new MountHuaSite(corner.getX() + 3000 + rot * 600, corner.getZ() + 34000, 67, rot, HuaBaked.CANONICAL_SEED, true);
            HuaStele.Frame frame = HuaStele.Frame.of(site);
            BoundingBox box = frame.box();
            BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
            // The rock of the mountain under the ledge: solid up to the plan, air above.
            for (int x = box.minX() - 2; x <= box.maxX() + 2; x++) {
                for (int z = box.minZ() - 2; z <= box.maxZ() + 2; z++) {
                    int plan = (int) Math.round(site.worldY(site.shape().height(site.centerU(x, z), site.centerV(x, z))));
                    for (int y = plan - 12; y <= plan + 30; y++) {
                        level.setBlock(at.set(x, y, z), y <= plan ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
            for (int cx = (box.minX() - 2) >> 4; cx <= (box.maxX() + 2) >> 4; cx++) {
                for (int cz = (box.minZ() - 2) >> 4; cz <= (box.maxZ() + 2) >> 4; cz++) {
                    HuaStele.apply(level, new ChunkPos(cx, cz), site);
                }
            }
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    int plan = (int) Math.round(site.worldY(site.shape().height(site.centerU(x, z), site.centerV(x, z))));
                    double[] trail = site.shape().trailAt(site.centerU(x, z), site.centerV(x, z));
                    if (trail == null || trail[0] > TRAIL_BED) {
                        // outside the trail the ledge must be there (the check measures something)
                        if (!level.getBlockState(at.set(x, frame.level() - 1, z)).isAir()) {
                            seen[0]++;
                        }
                        continue;
                    }
                    seen[1]++;
                    for (int y = plan + 2; y <= plan + 20; y++) {
                        if (!level.getBlockState(at.set(x, y, z)).isAir()) {
                            if (problems.size() < 8) {
                                problems.add("rotation " + rot + " at " + x + "," + z + ": " + (y - plan) + " blocks over the plan");
                            }
                            break;
                        }
                    }
                }
            }
        }
        helper.assertTrue(seen[0] > 100, "the ledge was not laid: " + seen[0]);
        helper.assertTrue(seen[1] > 0, "the stele area holds no trail column: the check measures nothing");
        helper.assertTrue(problems.isEmpty(), "rock over the trail bed, e.g.\n" + String.join("\n", problems));
        helper.succeed();
    }
}
