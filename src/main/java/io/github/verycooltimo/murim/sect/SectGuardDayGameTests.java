package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectPose;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static io.github.verycooltimo.murim.sect.SectPlaytestRig.bed;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.clearYard;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.empty;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.npc;

/**
 * Behaviour review 21.10, round 1 ("the sect people are statues"): the day of a guard (round, meal relief, rain, the gatekeeper's
 * night), rank at the post (a bow to the head), and the watch's eye for a creature (the alarm wakes the sleepers). One batch for each
 * test: they spawn named people.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectGuardDayGameTests {

    private static final String YARD = "sect_yard";

    private SectGuardDayGameTests() {
    }

    private static SectGameTests.Yard yard(GameTestHelper helper, double scale) {
        return new SectGameTests.Yard(SectLife.stand(helper.getLevel(), helper.absoluteVec(new Vec3(12.5D, 2.0D, 12.5D))), scale);
    }

    private static SectDisciple member(GameTestHelper helper, SectLayout layout, String key, double x, double z) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), helper.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setLayout(layout);
        d.setKeepAwake(true);
        Vec3 at = SectLife.stand(helper.getLevel(), helper.absoluteVec(new Vec3(x + 0.5D, 2.0D, z + 0.5D)));
        d.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        helper.getLevel().addFreshEntity(d);
        return d;
    }

    private static void setTime(GameTestHelper helper, int timeOfDay) {
        long day = Math.floorDiv(helper.getLevel().getDayTime(), 24000L) * 24000L;
        helper.getLevel().setDayTime(day + timeOfDay);
    }

    private static Vec3 spotIn(GameTestHelper helper, SectGameTests.Yard yard, SectSchedule.Task t) {
        return SectLife.stand(helper.getLevel(), yard.at(t.zone(), t.du(), t.dv()));
    }

    private static Vec3 rel(GameTestHelper helper, Vec3 abs) {
        return abs.subtract(helper.absoluteVec(Vec3.ZERO));
    }

    /** Training hours: the guard of the treasury walks his round, from the penance cave back to the treasury (watch-2). */
    @GameTest(template = YARD, timeoutTicks = 900, batch = "guard_day_patrol")
    public static void patrolMovesBetweenWaypoints(GameTestHelper helper) {
        setTime(helper, 3700);
        SectGameTests.Yard yard = yard(helper, 1.0D);
        long day = SectSchedule.day(helper.getLevel().getDayTime());
        SectRoster g = SectRota.dayWatch(day, 1);
        SectSchedule.Task first = SectSchedule.round(new SectRota.Duty(1, false), 3700L);
        SectSchedule.Task second = SectSchedule.round(new SectRota.Duty(1, false), 4900L);
        helper.assertTrue(!first.zone().equals(second.zone()), "the fixture has one stop twice: " + first.zone());
        Vec3 a = rel(helper, spotIn(helper, yard, first));
        SectDisciple d = member(helper, yard, g.key(), a.x - 0.5D, a.z - 0.5D);
        Vec3 b = spotIn(helper, yard, second);
        boolean[] phase = new boolean[1];
        helper.onEachTick(() -> {
            if (!phase[0] && d.position().distanceTo(spotIn(helper, yard, first)) < 1.5D && d.getNavigation().isDone()) {
                phase[0] = true;
                setTime(helper, 4900);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(phase[0], "the guard never stood at the first stop " + first.zone());
            helper.assertTrue(d.position().distanceTo(b) < 1.5D, "he did not walk on to " + second.zone() + ": "
                    + String.format("%.1f", d.position().distanceTo(b)) + " away");
        });
    }

    /** Dinner, second third: the guard of post 0 eats, the guard of post 1 stands in the hall in his place. */
    @GameTest(template = YARD, timeoutTicks = 600, batch = "guard_day_meal")
    public static void mealReliefCoversThePost(GameTestHelper helper) {
        setTime(helper, SectSchedule.Period.DINNER.start() + 800);
        SectGameTests.Yard yard = yard(helper, 1.0D);
        long day = SectSchedule.day(helper.getLevel().getDayTime());
        SectDisciple eater = member(helper, yard, SectRota.dayWatch(day, 0).key(), 12, 2);
        SectDisciple cover = member(helper, yard, SectRota.dayWatch(day, 1).key(), 20, 20);
        helper.assertTrue(SectRota.eating(eater.member().orElseThrow(), helper.getLevel().getDayTime()), "the fixture time is not his meal");
        Vec3 hall = spotIn(helper, yard, SectSchedule.stopTask("main_hall"));
        helper.onEachTick(() -> setTime(helper, SectSchedule.Period.DINNER.start() + 800));
        helper.succeedWhen(() -> {
            helper.assertTrue(eater.doingKind() == SectSchedule.Kind.EAT, "he does not eat: " + eater.doingKind());
            helper.assertTrue(!SectLife.onWatch(eater), "he eats and is still on the watch");
            helper.assertTrue(cover.position().distanceTo(hall) < 1.6D, "nobody covers the hall post: " + String.format("%.1f", cover.position().distanceTo(hall)));
            helper.assertTrue(SectLife.onWatch(cover), "the cover is not on the watch");
        });
    }

    /** Night: the gatekeeper waits for the night guard of post 0, who takes the gate; then he goes to sleep. */
    @GameTest(template = YARD, timeoutTicks = 700, batch = "guard_day_gatekeeper")
    public static void gatekeeperIsRelievedAtNight(GameTestHelper helper) {
        setTime(helper, SectSchedule.Period.NIGHT.start() + SectSchedule.HANDOVER + 40);
        SectGameTests.Yard yard = yard(helper, 0.5D);
        long day = SectSchedule.day(helper.getLevel().getDayTime());
        SectDisciple gk = member(helper, yard, "gatekeeper", 6, 6);
        SectDisciple night = member(helper, yard, SectRota.nightWatch(day, 0).key(), 20, 20);
        Vec3 gate = spotIn(helper, yard, SectSchedule.stopTask("sect_gate"));
        int[] held = {0};
        helper.onEachTick(() -> {
            boolean holding = gk.doingKind() == SectSchedule.Kind.SLEEP
                    && night.position().distanceTo(gate) < 1.6D && SectLife.onWatch(night);
            held[0] = holding ? held[0] + 1 : 0;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(gk.doingKind() == SectSchedule.Kind.SLEEP, "the gatekeeper stands at night: " + gk.doingKind());
            helper.assertTrue(night.position().distanceTo(gate) < 1.6D, "the night guard does not hold the gate: " + String.format("%.1f", night.position().distanceTo(gate)));
            helper.assertTrue(SectLife.onWatch(night), "the covering guard is not on the watch");
            helper.assertTrue(held[0] >= 100, "the night guard has not held the gate for five seconds");
        });
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "guard_day_wait_relief")
    public static void gatekeeperWaitsForActualRelief(GameTestHelper helper) {
        setTime(helper, SectSchedule.Period.NIGHT.start() + SectSchedule.HANDOVER + 40);
        SectGameTests.Yard layout = yard(helper, 0.5D);
        SectDisciple gatekeeper = member(helper, layout, "gatekeeper", 11, 12);
        gatekeeper.setNoAi(true);
        helper.assertTrue(SectLife.resolve(gatekeeper).task().kind() == SectSchedule.Kind.GUARD,
                "the gatekeeper left before his relief arrived");
        long day = SectSchedule.day(helper.getLevel().getDayTime());
        SectDisciple cover = member(helper, layout, SectRota.nightWatch(day, 0).key(), 11, 12);
        cover.setNoAi(true);
        Vec3 post = spotIn(helper, layout, SectSchedule.stopTask("sect_gate"));
        cover.moveTo(post.x + 2.0D, post.y, post.z, 0.0F, 0.0F);
        helper.assertTrue(SectLife.resolve(gatekeeper).task().kind() == SectSchedule.Kind.GUARD,
                "the gatekeeper accepted relief short of the post");
        cover.moveTo(post.x, post.y, post.z, 0.0F, 0.0F);
        helper.assertTrue(SectLife.resolve(gatekeeper).task().kind() == SectSchedule.Kind.SLEEP,
                "the gatekeeper cannot sleep after the relief arrives");
        helper.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "guard_day_player_rank")
    public static void guardBowsToPlayerElder(GameTestHelper helper) {
        setTime(helper, 3000);
        SectHonour.reset(helper.getLevel());
        var player = SectGameTests.fakePlayer(helper);
        player.setData(io.github.verycooltimo.murim.registry.ModAttachments.SECT,
                SectState.NONE.joined().with(SectPositions.ELDER));
        Vec3 at = helper.absoluteVec(new Vec3(10.5D, 2.0D, 8.5D));
        player.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        helper.getLevel().addNewPlayer(player);
        try {
            long day = SectSchedule.day(helper.getLevel().getDayTime());
            SectDisciple guard = member(helper, yard(helper, 0.5D), SectRota.dayWatch(day, 0).key(), 8, 8);
            guard.setNoAi(true);
            SectHonour.tick(guard);
            helper.assertTrue(guard.pose() == SectPose.BOW && guard.poseHeld(), "no bow to the player elder");
            helper.assertTrue(guard.bubbleKey().startsWith("murim.bubble.guard_bow_head"), "no rank greeting");
        } finally {
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "guard_day_keepers")
    public static void closedPlacesKeepTheirGuardsDuringRoundsAndMeals(GameTestHelper helper) {
        for (int time : new int[] {2100, 3700, 4900, 9300, 9800, 10500, 14000, 18000, 22000}) {
            setTime(helper, time);
            long clock = helper.getLevel().getDayTime();
            for (SectAccess.Rule rule : SectAccess.RULES) {
                if (SectAccess.GROUNDS.equals(rule.zone())) continue;
                SectRoster keeper = SectRota.keeper(rule.zone(), clock);
                helper.assertTrue(keeper != null, rule.zone() + " has no keeper at " + time);
                SectDisciple guard = member(helper, yard(helper, 0.5D), keeper.key(), 8, 8);
                guard.setNoAi(true);
                helper.assertTrue(SectWatch.keeps(guard, rule), "watch does not recognise the keeper of " + rule.zone());
                helper.assertTrue(SectWatch.guards(guard) && !SectRota.eating(keeper, clock), "keeper is off duty or eating");
                guard.discard();
            }
        }
        helper.succeed();
    }

    /** Rain: the guard leaves the open post for the roof next to it, like the others. */
    @GameTest(template = YARD, timeoutTicks = 900, batch = "guard_day_rain", skyAccess = true)
    public static void guardShelterInTheRain(GameTestHelper helper) {
        setTime(helper, 3000);
        SectGameTests.Yard yard = yard(helper, 1.0D);
        long day = SectSchedule.day(helper.getLevel().getDayTime());
        SectSchedule.Task stop = SectSchedule.round(new SectRota.Duty(0, false), 3000L);
        Vec3 at = rel(helper, spotIn(helper, yard, stop));
        int cx = (int) Math.floor(at.x) + 4;
        int cz = (int) Math.floor(at.z);
        for (int x = cx - 1; x <= cx + 2; x++) {
            for (int z = cz - 1; z <= cz + 2; z++) {
                if (x >= 0 && z >= 0) {
                    helper.setBlock(new BlockPos(x, 5, z), Blocks.OAK_PLANKS);
                }
            }
        }
        SectDisciple d = member(helper, yard, SectRota.dayWatch(day, 0).key(), at.x - 0.5D, at.z - 0.5D);
        d.setRainOverride(true);
        helper.succeedWhen(() -> {
            helper.assertTrue(d.doingKind() == SectSchedule.Kind.SHELTER, "the guard stays in the rain: " + d.doingKind());
            helper.assertTrue(SectWeather.covered(d.level(), d.blockPosition()), "not under the roof");
        });
    }

    /** The head passes the guard at his post: the guard bows (once a day for him). */
    @GameTest(template = YARD, timeoutTicks = 200, batch = "guard_day_bow")
    public static void guardBowsToTheHead(GameTestHelper helper) {
        setTime(helper, 3000);
        SectGameTests.Yard yard = yard(helper, 0.5D);
        SectHonour.reset(helper.getLevel());
        long day = SectSchedule.day(helper.getLevel().getDayTime());
        SectDisciple guard = member(helper, yard, SectRota.dayWatch(day, 0).key(), 8, 8);
        guard.setNoAi(true);
        SectDisciple head = member(helper, yard, "tae_hwi", 10, 8);
        head.setNoAi(true);
        SectHonour.tick(guard);
        helper.assertTrue(guard.pose() == SectPose.BOW && guard.poseHeld(), "the guard did not bow to the head: " + guard.pose().id());
        helper.assertTrue(guard.bubbleKey().startsWith("murim.bubble.guard_bow_head"), "no word of respect: '" + guard.bubbleKey() + "'");
        helper.runAfterDelay(60, () -> {
            SectHonour.tick(guard);
            helper.assertTrue(!guard.poseHeld(), "he bowed to the same person twice in a day");
            helper.succeed();
        });
    }

    /** Night: a creature stands 16 blocks from a non-fighting servant; the sleeper in the bed wakes and cries out (night-1). */
    @GameTest(template = YARD, timeoutTicks = 300, batch = "guard_day_monster")
    public static void alarmWakesTheSleepers(GameTestHelper helper) {
        setTime(helper, SectSchedule.Period.NIGHT.start() + SectSchedule.HANDOVER + 200);
        SectLife.data(helper.getLevel()).lastAlarm = Long.MIN_VALUE / 2;
        SectLife.forgetMonsters(helper.getLevel());
        SectHierarchyGameTests.Only layout = empty(helper);
        clearYard(helper);
        bed(helper, 3, 3);
        SectDisciple sleeper = npc(helper, layout, "yul_jin", 3, 3, true);
        sleeper.startSleeping(helper.absolutePos(new BlockPos(3, 2, 4)));
        helper.assertTrue(sleeper.isSleeping(), "the fixture did not put the disciple to bed");
        SectDisciple guard = npc(helper, layout, "porter_jang", 4, 20, false);
        helper.assertTrue(guard.role().lay(), "the observer must be a non-fighter");
        Zombie z = EntityType.ZOMBIE.create(helper.getLevel());
        Vec3 zAt = SectLife.stand(helper.getLevel(), helper.absoluteVec(new Vec3(20.5D, 2.0D, 20.5D)));
        z.moveTo(zAt.x, zAt.y, zAt.z, 0.0F, 0.0F);
        z.setNoAi(true);
        z.setPersistenceRequired();
        helper.getLevel().addFreshEntity(z);
        helper.assertTrue(guard.distanceTo(z) > 12.0D, "the creature is inside the old ten blocks: " + guard.distanceTo(z));
        helper.succeedWhen(() -> {
            helper.assertTrue(!sleeper.isSleeping(), "the sleeper slept through the alarm");
            helper.assertTrue(sleeper.bubbleKey().startsWith("murim.bubble.alarm_"), "nobody cried out: '" + sleeper.bubbleKey() + "'");
        });
    }
}
