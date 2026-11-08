package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static io.github.verycooltimo.murim.sect.SectPlaytestRig.empty;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.npc;

/**
 * The fire half of bug 6 of the 19.10 playtest (docs/adr/0013): fire in the grounds is cried out as «Пожар!», a string of alarms is one
 * alarm, and a fire out of everybody's sight is none.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectFireAlarmGameTests {

    private static final String YARD = "sect_yard";

    private SectFireAlarmGameTests() {
    }

    @GameTest(template = YARD, timeoutTicks = 60, batch = "playtest1019_fire")
    public static void fireInTheGroundsIsCriedOutOnceAndNotWhenNobodyIsNear(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        SectHierarchyGameTests.Only layout = empty(h);
        SectDisciple near = npc(h, layout, "yul_jin", 4, 12, false);
        Vec3 fire = h.absoluteVec(new Vec3(14.5D, 2.0D, 12.5D));
        // Adjacent GameTests can have listeners within HEAR_FIRE: require this listener's reaction,
        // not an exact level-wide count. Put the unheard incident above the shared test grid.
        // Like the walking alarm fixtures, wait until the spawned listener is queryable in its chunk.
        // Reset the level-wide cooldown only after this wait, immediately before the alarm assertions.
        // API: Minecraft 1.21.1 GameTestHelper#startSequence and GameTestSequence#thenWaitUntil/thenExecute/thenSucceed.
        h.startSequence().thenWaitUntil(() -> {
            h.assertTrue(h.getLevel().getEntitiesOfClass(SectDisciple.class,
                    near.getBoundingBox().inflate(1), d -> d == near).contains(near), "listener chunk is not ready");
        }).thenExecute(() -> {
            SectLife.data(h.getLevel()).lastAlarm = Long.MIN_VALUE / 2;
            h.assertTrue(SectAlarm.raise(h.getLevel(), fire.add(0.0D, 300.0D, 0.0D), SectAlarm.Kind.FIRE) == 0, "a fire 300 blocks above the yard was heard");
            h.assertTrue(SectAlarm.raise(h.getLevel(), fire, SectAlarm.Kind.FIRE) >= 1, "nobody heard the fire in the grounds");
            h.assertTrue(near.bubbleKey().startsWith("murim.bubble.alarm_curious"), "he did not cry fire: '" + near.bubbleKey() + "'");
            h.assertTrue(near.alarmGoal().mode(h.getLevel().getGameTime()) == io.github.verycooltimo.murim.entity.SectAlarmGoal.Mode.GAWK,
                    "he did not move to a safe viewing distance");
            h.assertTrue(SectAlarm.raise(h.getLevel(), fire, SectAlarm.Kind.FIRE) == 0, "a second alarm at once for the same fire");
        }).thenSucceed();
    }
}
