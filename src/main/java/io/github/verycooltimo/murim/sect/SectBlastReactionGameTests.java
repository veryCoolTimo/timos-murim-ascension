package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectAlarmGoal;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.seal.PenanceEnforcement;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

import static io.github.verycooltimo.murim.sect.SectPlaytestRig.clearYard;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.empty;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.npc;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.player;

/**
 * Playtest 21.10: «если начинаю взрывать динамитом все здания — никто не реагирует». The alarm fired, but only one guard ran
 * and the rest merely turned for five seconds. Now the whole sect shows it: everybody within earshot reacts at once with a
 * bubble, guards and the elder run to the spot, servants run away, disciples run to look and stop, and the elder confronts the
 * one who lit the TNT. Every test has its own batch (named people: two of one key are folded into one).
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectBlastReactionGameTests {

    private static final String YARD = "sect_yard";

    private SectBlastReactionGameTests() {
    }

    private static Vec3 blast(GameTestHelper h) {
        return h.absoluteVec(new Vec3(3.5D, 2.0D, 3.5D));
    }

    private static void ready(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        SectLife.data(h.getLevel()).lastAlarm = Long.MIN_VALUE / 2;
        clearYard(h);
    }

    @GameTest(template = YARD, timeoutTicks = 500, batch = "blast1021_everyone")
    public static void everyoneReactsAtOnceAndEachInHisOwnWay(GameTestHelper h) {
        ready(h);
        SectHierarchyGameTests.Only layout = empty(h);
        long day = SectSchedule.day(h.getLevel().getDayTime());
        SectDisciple guard = npc(h, layout, SectRota.dayWatch(day, 0).key(), 20, 20, true);
        SectDisciple elder = npc(h, layout, "tae_rok", 20, 3, true);
        SectDisciple gawker = npc(h, layout, "yul_jin", 20, 14, true);
        SectDisciple looker = npc(h, layout, "yul_seok", 8, 4, true);
        SectDisciple porter = npc(h, layout, "porter_oh", 3, 20, true);
        SectDisciple cook = npc(h, layout, "cook_kim", 9, 9, true);
        List<SectDisciple> all = List.of(guard, elder, gawker, looker, porter, cook);
        double cookStart = cook.position().distanceTo(blast(h));
        Vec3 blast = blast(h);
        h.getLevel().explode(null, blast.x, blast.y, blast.z, 2.0F, Level.ExplosionInteraction.NONE);
        // within two seconds everybody has an errand and a word over his head (not one guard and five seconds of staring)
        h.runAfterDelay(40, () -> {
            for (SectDisciple d : all) {
                h.assertTrue(d.alarmGoal().mode(h.getLevel().getGameTime()) != null, d.memberKey() + " did not react");
                h.assertTrue(d.bubbleKey().startsWith("murim.bubble.alarm_"), d.memberKey() + " said nothing: '" + d.bubbleKey() + "'");
            }
            h.assertTrue(guard.alarmGoal().mode(h.getLevel().getGameTime()) == SectAlarmGoal.Mode.RUN, "the guard does not run");
            h.assertTrue(elder.alarmGoal().mode(h.getLevel().getGameTime()) == SectAlarmGoal.Mode.ELDER, "the elder does not come");
            h.assertTrue(gawker.alarmGoal().mode(h.getLevel().getGameTime()) == SectAlarmGoal.Mode.GAWK, "the disciple does not run to look");
            h.assertTrue(cook.alarmGoal().mode(h.getLevel().getGameTime()) == SectAlarmGoal.Mode.FLEE, "the cook does not flee");
            h.assertTrue(porter.alarmGoal().mode(h.getLevel().getGameTime()) == SectAlarmGoal.Mode.FLEE, "the porter does not flee");
            h.assertTrue(cook.bubbleKey().startsWith("murim.bubble.alarm_flee"), "the cook cried: '" + cook.bubbleKey() + "'");
            h.assertTrue(gawker.bubbleKey().startsWith("murim.bubble.alarm_curious"), "the gawker cried: '" + gawker.bubbleKey() + "'");
        });
        h.runAfterDelay(45, () -> h.succeedWhen(() -> {
            h.assertTrue(guard.position().distanceTo(blast) < 9.0D, "the guard did not reach the spot: " + guard.position().distanceTo(blast));
            h.assertTrue(elder.position().distanceTo(blast) < 9.0D, "the elder did not reach the spot: " + elder.position().distanceTo(blast));
            double g = gawker.position().distanceTo(blast);
            h.assertTrue(g > 10.0D && g < 17.0D, "the gawker did not stop at a safe distance: " + g);
            h.assertTrue(cook.position().distanceTo(blast) > cookStart + 4.0D, "the cook did not run away: " + cook.position().distanceTo(blast));
            h.assertTrue(porter.bubbleKey().startsWith("murim.bubble.alarm_servant"), "the servant did not say he will mend it: '" + porter.bubbleKey() + "'");
        }));
    }

    @GameTest(template = YARD, timeoutTicks = 500, batch = "blast1021_culprit")
    public static void theElderConfrontsTheOneWhoLitTheTnt(GameTestHelper h) {
        ready(h);
        SectHierarchyGameTests.Only layout = empty(h);
        ServerPlayer p = player(h, 5, 5, SectState.NONE.joined().contribute(20));
        SectDisciple elder = npc(h, layout, "tae_rok", 21, 5, true);
        npc(h, layout, "yul_jin", 21, 21, true);
        h.assertTrue(SectAlarm.culprit(h.getLevel(), blast(h), p, 14.0D, false) == p, "the lighter of the TNT is not the culprit");
        PrimedTnt tnt = new PrimedTnt(h.getLevel(), blast(h).x, blast(h).y, blast(h).z, p);
        tnt.setFuse(2);
        h.getLevel().addFreshEntity(tnt);
        h.succeedWhen(() -> {
            h.assertTrue(elder.alarmGoal().mode(h.getLevel().getGameTime()) == SectAlarmGoal.Mode.CONFRONT, "the elder is not after the culprit");
            h.assertTrue(elder.distanceTo(p) < 5.0D, "the elder did not walk up: " + elder.distanceTo(p));
            h.assertTrue(elder.bubbleKey().startsWith("murim.bubble.alarm_confront"), "no sharp word: '" + elder.bubbleKey() + "'");
            h.assertTrue(elder.getData(ModAttachments.AURA).present() && elder.getData(ModAttachments.AURA).rank() > 3, "no aura on the elder");
            h.assertTrue(SectAlarm.strikes(p) == 1, "strikes " + SectAlarm.strikes(p));
            h.assertTrue(!PenanceEnforcement.active(p), "first blast must not be a warrant");
        });
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "blast1021_creative")
    public static void aCreativePlayerIsNotConfrontedButTheSectReacts(GameTestHelper h) {
        ready(h);
        SectHierarchyGameTests.Only layout = empty(h);
        ServerPlayer p = player(h, 5, 5, SectState.NONE);
        p.setGameMode(GameType.CREATIVE);
        SectDisciple elder = npc(h, layout, "tae_rok", 21, 5, true);
        SectDisciple guard = npc(h, layout, "yul_jin", 21, 21, true);
        h.assertTrue(SectAlarm.raise(h.getLevel(), blast(h), SectAlarm.Kind.BLAST, p) == 2, "not everybody heard");
        long now = h.getLevel().getGameTime();
        h.assertTrue(elder.alarmGoal().mode(now) == SectAlarmGoal.Mode.ELDER, "the elder did not come to look");
        h.assertTrue(guard.alarmGoal().mode(now) != null && guard.bubbleKey().startsWith("murim.bubble.alarm_"), "the disciple did not react");
        h.assertTrue(SectAlarm.strikes(p) == 0, "a creative player got a strike");
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 500, batch = "blast1021_third")
    public static void theThirdStrikeOfTheDayIsAWarrantAndTheSecondIsSharper(GameTestHelper h) {
        ready(h);
        SectHierarchyGameTests.Only layout = empty(h);
        ServerPlayer p = player(h, 5, 5, SectState.NONE.joined().contribute(20));
        SectDisciple elder = npc(h, layout, "tae_rok", 6, 5, true);
        var tag = p.getPersistentData();
        long now = h.getLevel().getGameTime();
        tag.putLong("murim_alarm_day", SectSchedule.day(h.getLevel().getDayTime()) + 1);
        tag.putInt("murim_alarm_n", 2);
        tag.putLong("murim_alarm_at", now - 2000L);
        h.assertTrue(SectAlarm.raise(h.getLevel(), blast(h), SectAlarm.Kind.BLAST, p) >= 1, "nobody heard");
        // Hearing extends beyond a test yard; verify this fixture's elder rather than an exact world-wide count.
        h.assertTrue(elder.alarmGoal().mode(now) == SectAlarmGoal.Mode.CONFRONT, "fixture elder did not confront culprit");
        h.assertTrue(SectAlarm.strikes(p) == 3, "the third blast of the day is strike " + SectAlarm.strikes(p));
        h.succeedWhen(() -> h.assertTrue(PenanceEnforcement.active(p), "no warrant after the third strike"));
    }

    @GameTest(template = YARD, timeoutTicks = 20, batch = "blast1021_same")
    public static void aStringOfBlastsIsOneStrike(GameTestHelper h) {
        ServerPlayer p = player(h, 5, 5, SectState.NONE);
        long now = h.getLevel().getGameTime();
        int a = SectAlarm.strike(p, now, false);
        int b = SectAlarm.strike(p, now + 100L, false);
        int c = SectAlarm.strike(p, now + 100L + SectAlarm.STRIKE_GAP, false);
        h.assertTrue(a == 1 && b == 1 && c == 2, "strikes " + a + "," + b + "," + c);
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 60, batch = "blast1021_crowd")
    public static void everyListenerShoutsEvenAfterRecentChatter(GameTestHelper h) {
        ready(h);
        var layout = empty(h);
        java.util.ArrayList<SectDisciple> crowd = new java.util.ArrayList<>();
        int i = 0;
        for (SectRoster member : SectRoster.ALL) {
            SectDisciple d = npc(h, layout, member.key(), 8 + i % 6 * 2, 8 + i / 6 * 2, false);
            d.markBubble("recent_chatter");
            crowd.add(d);
            i++;
        }
        SectAlarm.raise(h.getLevel(), blast(h), SectAlarm.Kind.BLAST);
        for (SectDisciple d : crowd) {
            h.assertTrue(d.bubbleKey().startsWith("murim.bubble.alarm_"), d.memberKey() + " did not shout");
        }
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 20, batch = "blast1021_fire_strikes")
    public static void fireEscalatesAndANewDayClearsTheCount(GameTestHelper h) {
        ServerPlayer p = player(h, 5, 5, SectState.NONE);
        long now = h.getLevel().getGameTime();
        p.getPersistentData().remove("murim_alarm_day");
        h.assertTrue(SectAlarm.strike(p, now, true) == 1, "first fire");
        h.assertTrue(SectAlarm.strike(p, now + SectAlarm.STRIKE_GAP, true) == 2, "second fire");
        h.assertTrue(SectAlarm.strike(p, now + SectAlarm.STRIKE_GAP * 2, true) == 3, "third fire cannot reach penance");
        p.getPersistentData().putLong("murim_alarm_day", SectSchedule.day(h.getLevel().getDayTime()));
        h.assertTrue(SectAlarm.strikes(p) == 0, "yesterday's strikes survived");
        h.assertTrue(SectAlarm.strike(p, now, false) == 1, "new day's first strike");
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 800, batch = "blast1021_cleanup")
    public static void confrontationRestoresAuraAndReleasesTheRoutine(GameTestHelper h) {
        ready(h);
        var layout = empty(h);
        ServerPlayer p = player(h, 10, 10, SectState.NONE);
        SectDisciple elder = npc(h, layout, "tae_rok", 11, 10, true);
        var before = elder.getData(ModAttachments.AURA);
        SectAlarm.raise(h.getLevel(), p.position(), SectAlarm.Kind.BLAST, p);
        h.runAfterDelay(60, () -> {
            h.assertTrue(elder.alarmGoal().arrived(), "elder never confronted the nearby culprit");
            h.assertTrue(elder.getData(ModAttachments.AURA).rank() > 3, "alarm aura was restored by the NPC tick");
            h.assertTrue(!PenanceEnforcement.active(p), "first incident entered penance");
        });
        h.runAfterDelay(740, () -> {
            h.assertTrue(elder.alarmGoal().mode(h.getLevel().getGameTime()) == null, "alarm still blocks the schedule");
            h.assertTrue(elder.getData(ModAttachments.AURA).equals(before), "temporary aura was not restored");
            h.succeed();
        });
    }

    @GameTest(template = YARD, timeoutTicks = 300, batch = "blast1021_safe")
    public static void aDiscipleInsideTheDangerZoneRetreatsToWatch(GameTestHelper h) {
        ready(h);
        var layout = empty(h);
        Vec3 at = h.absoluteVec(new Vec3(12.5D, 2.0D, 12.5D));
        SectDisciple disciple = npc(h, layout, "yul_jin", 16, 12, true);
        SectAlarm.raise(h.getLevel(), at, SectAlarm.Kind.FIRE);
        h.succeedWhen(() -> {
            h.assertTrue(disciple.alarmGoal().arrived(), "disciple did not reach the safe gathering point");
            double distance = disciple.position().distanceTo(at);
            h.assertTrue(distance >= SectAlarm.GAWK_STANDOFF - 2 && distance <= SectAlarm.GAWK_STANDOFF + 2,
                    "disciple stopped in danger: " + distance);
        });
    }

    /** The general 1.5-block alarm arrival tolerance must not cut a servant's retreat short. */
    @GameTest(template = YARD, timeoutTicks = 100, batch = "blast1021_flee_arrival")
    public static void aServantFinishesTheRetreatBeforePromisingRepairs(GameTestHelper h) {
        ready(h);
        var layout = empty(h);
        Vec3 incident = h.absoluteVec(new Vec3(5.5D, 2.0D, 12.5D));
        Vec3 refuge = h.absoluteVec(new Vec3(22.5D, 2.0D, 12.5D));
        SectDisciple servant = npc(h, layout, "porter_oh", 21.25D, 12.5D, true);
        // Already inside the old arrival radius, but still less than sixteen blocks from danger.
        servant.alarmGoal().assign(SectAlarmGoal.Mode.FLEE, incident, refuge,
                h.getLevel().getGameTime() + 200L);
        h.succeedWhen(() -> {
            h.assertTrue(servant.alarmGoal().arrived(), "servant never finished the retreat");
            h.assertTrue(servant.position().distanceTo(incident) > 16.0D,
                    "servant stopped short of safety: " + servant.position().distanceTo(incident));
            h.assertTrue(servant.position().distanceTo(refuge) < 0.5D,
                    "servant did not reach the refuge");
            h.assertTrue(servant.bubbleKey().startsWith("murim.bubble.alarm_servant"),
                    "servant did not promise repairs after reaching safety");
        });
    }

    /** Supported floor behind a wall must not win over another reachable point on the safety ring. */
    @GameTest(template = YARD, timeoutTicks = 300, batch = "blast1021_safe_wall")
    public static void aDiscipleChoosesAReachableGatheringPoint(GameTestHelper h) {
        ready(h);
        var layout = empty(h);
        Vec3 at = h.absoluteVec(new Vec3(8.5D, 2.0D, 12.5D));
        // The direct fourteen-block point is on good floor at x=22.5, behind a three-block wall.
        for (int z = 0; z < 24; z++) {
            for (int y = 2; y <= 4; y++) {
                h.setBlock(new BlockPos(20, y, z), net.minecraft.world.level.block.Blocks.STONE);
                h.setBlock(new BlockPos(23, y, z), net.minecraft.world.level.block.Blocks.STONE);
            }
        }
        // Seal the ends too: going around the wall must not make the direct point reachable.
        for (int x = 21; x < 23; x++) {
            for (int y = 2; y <= 4; y++) {
                h.setBlock(new BlockPos(x, y, 0), net.minecraft.world.level.block.Blocks.STONE);
                h.setBlock(new BlockPos(x, y, 23), net.minecraft.world.level.block.Blocks.STONE);
            }
        }
        SectDisciple disciple = npc(h, layout, "yul_jin", 12, 12, true);
        SectAlarm.raise(h.getLevel(), at, SectAlarm.Kind.FIRE);
        h.succeedWhen(() -> {
            h.assertTrue(disciple.alarmGoal().arrived(), "disciple never reached a point around the wall");
            double distance = disciple.position().distanceTo(at);
            h.assertTrue(distance >= SectAlarm.GAWK_STANDOFF - 2 && distance <= SectAlarm.GAWK_STANDOFF + 2,
                    "disciple failed to keep the safety distance: " + distance);
            h.assertTrue(disciple.getX() < h.absolutePos(new BlockPos(20, 2, 12)).getX(),
                    "disciple chose the far side of the wall");
        });
    }

    @GameTest(template = YARD, timeoutTicks = 20, batch = "blast1021_nearest")
    public static void onlyTheNearestElderConfrontsTheCulprit(GameTestHelper h) {
        ready(h);
        var layout = empty(h);
        ServerPlayer p = player(h, 10, 10, SectState.NONE);
        SectDisciple near = npc(h, layout, "tae_rok", 11, 10, false);
        SectDisciple far = npc(h, layout, "tae_gyun", 21, 20, false);
        SectAlarm.raise(h.getLevel(), p.position(), SectAlarm.Kind.BLAST, p);
        long now = h.getLevel().getGameTime();
        h.assertTrue(near.alarmGoal().mode(now) == SectAlarmGoal.Mode.CONFRONT, "nearest elder did not take responsibility");
        h.assertTrue(far.alarmGoal().mode(now) != SectAlarmGoal.Mode.CONFRONT, "two elders confront one culprit");
        h.succeed();
    }

}
