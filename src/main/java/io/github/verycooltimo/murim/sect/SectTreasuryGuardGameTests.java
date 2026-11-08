package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static io.github.verycooltimo.murim.sect.SectPlaytestRig.npc;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.only;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.player;

/**
 * Bug 4 of the 19.10 playtest (docs/adr/0013): the treasury guard reacts to a member who walks in, wall or no wall. Every
 * test has its own batch (they spawn named people: two of one key within 256 blocks are folded into one).
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectTreasuryGuardGameTests {

    private static final String YARD = "sect_yard";

    private SectTreasuryGuardGameTests() {
    }

    /**
     * «Иду в сокровищницу, и меня никто не останавливает. Там охранник просто снаружи остаётся, ему пофиг.» A member who is not
     * yet allowed in is warned, the guard steps in his way and then leads him out; no blow, no merit lost.
     */
    @GameTest(template = YARD, timeoutTicks = 900, batch = "playtest1019_treasury")
    public static void aMemberWhoWalksIntoTheTreasuryIsWarnedAndLedOutPolitely(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        SectHierarchyGameTests.Only layout = only(h, "treasury");
        ServerPlayer p = player(h, 12, 12, SectState.NONE.joined().contribute(8));
        long day = SectSchedule.day(h.getLevel().getDayTime());
        SectDisciple guard = npc(h, layout, SectRota.dayWatch(day, 1).key(), 12, 3, true);
        boolean[] warned = {false};
        boolean[] blocked = {false};
        h.onEachTick(() -> {
            if (h.getLevel().getGameTime() % SectWatch.PERIOD == 0) {
                SectWatch.check(p, layout);
            }
            warned[0] |= "warn".equals(SectWatch.of(p).last()) || "escort".equals(SectWatch.of(p).last());
            blocked[0] |= guard.blockTarget() == p.getId();
            h.assertTrue(guard.guardTarget() == null && guard.spar() == SectDisciple.Spar.NONE, "a member was attacked for walking in");
            h.assertTrue(p.getData(ModAttachments.SECT).contribution() == 8 && p.getData(ModAttachments.SECT).merit() == 8, "merit was taken");
        });
        h.succeedWhen(() -> {
            h.assertTrue(warned[0] && blocked[0], "the guard did not react to a member in the treasury (warned " + warned[0] + ", in the way " + blocked[0] + ")");
            h.assertTrue("escort".equals(SectWatch.of(p).last()) || guard.escortGoal().forPlayer(p), "the guard did not lead him out: " + SectWatch.of(p).last());
        });
    }

    /** The post is at the edge of the court behind a wall: the guard of the door notices who is inside without a line of sight. */
    @GameTest(template = YARD, timeoutTicks = 200, batch = "playtest1019_treasury_wall")
    public static void theTreasuryGuardBehindAWallStillNoticesAMemberInside(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        SectHierarchyGameTests.Only layout = only(h, "treasury");
        for (int x = 0; x < 24; x++) {
            for (int y = 2; y <= 4; y++) {
                h.setBlock(new BlockPos(x, y, 8), Blocks.STONE);
            }
        }
        ServerPlayer p = player(h, 12, 13, SectState.NONE.joined().contribute(8));
        long day = SectSchedule.day(h.getLevel().getDayTime());
        SectDisciple guard = npc(h, layout, SectRota.dayWatch(day, 1).key(), 12, 4, false);
        h.assertTrue(!guard.getSensing().hasLineOfSight(p), "the wall does not hide the player");
        h.onEachTick(() -> {
            if (h.getLevel().getGameTime() % SectWatch.PERIOD == 0) {
                SectWatch.check(p, layout);
            }
        });
        h.succeedWhen(() -> h.assertTrue("warn".equals(SectWatch.of(p).last()) || "escort".equals(SectWatch.of(p).last()),
                "the guard of the treasury door did not notice a member inside: '" + SectWatch.of(p).last() + "'"));
    }
}
