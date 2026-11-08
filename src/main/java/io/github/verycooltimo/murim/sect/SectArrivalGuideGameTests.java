package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static io.github.verycooltimo.murim.sect.SectPlaytestRig.clearYard;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.npc;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.only;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.player;

/**
 * Bug 5 of the 19.10 playtest (docs/adr/0013): the gate guard's guide really comes; an invited newcomer is not told off.
 * Every test has its own batch (they spawn named people: two of one key within 256 blocks are folded into one).
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectArrivalGuideGameTests {

    private static final String YARD = "sect_yard";

    private SectArrivalGuideGameTests() {
    }

    /** The gate zone for the guide tests: the gate at one end of the court, the mentor at the other. */
    private record Gate(Vec3 origin) implements SectLayout {
        @Override
        public Vec3 at(String zone, double u, double v) {
            return switch (zone) {
                case "sect_gate" -> origin.add(-8.0D + u, 0.0D, 0.0D + v);
                case "mentor" -> origin.add(8.0D + u, 0.0D, -9.0D + v);
                default -> null;
            };
        }

        @Override
        public float yaw(double u, double v) {
            return SectLayout.yawOf(u, v);
        }

        @Override
        public double[] half(String zone) {
            return at(zone, 0, 0) == null ? null : new double[] {3.0D, 3.0D};
        }

        @Override
        public double[] local(String zone, Vec3 p) {
            Vec3 c = at(zone, 0, 0);
            return c == null ? null : new double[] {p.x - c.x, p.z - c.z};
        }
    }

    /**
     * «Просишь, чтобы ученик тебя проводил — никто к тебе не подходит.» The nearest free disciple was taken for good: when he
     * does not move (his chunk does not tick, he is frozen), the request stayed "answered" until the errand timed out.
     */
    @GameTest(template = YARD, timeoutTicks = 1000, batch = "playtest1019_guide")
    public static void aGuideWhoDoesNotComeIsReplacedByOneWhoDoes(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        Gate layout = new Gate(h.absoluteVec(new Vec3(12.5D, 2.0D, 12.5D)));
        clearYard(h);
        SectDisciple tak = npc(h, layout, "gyeong_tak", 4, 12, false);
        npc(h, layout, "gyeong_pil", 20, 3, false);
        // the nearest is frozen (no AI, as in a chunk that does not tick); the second is awake and further
        SectDisciple frozen = npc(h, layout, "yul_jin", 11, 12, false);
        SectDisciple awake = npc(h, layout, "yul_seok", 20, 20, true);
        ServerPlayer p = player(h, 3, 12, SectState.NONE);
        h.assertTrue(SectArrival.invite(p, tak), "the gate guard did not invite the newcomer");
        h.onEachTick(() -> {
            // the player tick of SectArrival: once in 40 ticks the invited newcomer is given a guide
            if (h.getLevel().getGameTime() % 40 == 9) {
                SectArrival.guide(p, layout);
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(awake.escortGoal().forPlayer(p) && awake.distanceTo(p) < 7.0D,
                    "nobody came to the newcomer: frozen guide " + frozen.distanceTo(p) + " away, escort " + frozen.escortGoal().forPlayer(p)
                            + "; awake one " + awake.distanceTo(p) + " away, escort " + awake.escortGoal().forPlayer(p));
        });
    }

    /** «Если ты заходишь в секту, тебе пишут: ...» An invited newcomer inside the grounds is not told he is an outsider. */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "playtest1019_invited")
    public static void anInvitedNewcomerInsideTheGroundsIsNotTurnedBackAsAnOutsider(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        SectHierarchyGameTests.Only layout = only(h, "training");
        ServerPlayer p = player(h, 12, 12, SectState.NONE.with(SectArrival.INVITED));
        SectDisciple tak = npc(h, layout, "gyeong_tak", 12, 5, false);
        SectWatch.check(p, layout);
        h.assertTrue(!"warn".equals(SectWatch.of(p).last()) && tak.blockTarget() != p.getId(),
                "an invited newcomer was warned off the grounds: '" + SectWatch.of(p).last() + "'");
        h.succeed();
    }
}
