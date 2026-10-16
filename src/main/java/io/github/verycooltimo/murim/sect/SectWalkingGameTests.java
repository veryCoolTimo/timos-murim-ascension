package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.Config;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.sect.seal.PenanceService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Runtime regression gates for walking, manual buildings and guard escalation. No capture/dev settling. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectWalkingGameTests {
    private SectWalkingGameTests() { }
    private record Rooms(Vec3 origin) implements SectLayout {
        @Override public Vec3 at(String zone, double u, double v) {
            if (!"elders".equals(zone) && !"sect_gate".equals(zone) && !"dorm_3rd".equals(zone)) return null;
            return origin.add(u, 0, v - ("sect_gate".equals(zone) ? 10 : 0));
        }
        @Override public float yaw(double u, double v) { return SectLayout.yawOf(u, v); }
        @Override public double[] half(String zone) { return at(zone, 0, 0) == null ? null : new double[] {5, 5}; }
        @Override public double[] local(String zone, Vec3 position) {
            Vec3 c = at(zone, 0, 0);
            return c == null ? null : new double[] {position.x - c.x, position.z - c.z};
        }
    }
    private static Vec3 at(GameTestHelper h, double x, double z) { return h.absoluteVec(new Vec3(x, 2, z)); }
    private static SectDisciple npc(GameTestHelper h, SectLayout layout, String key, double x, double z) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow()); d.setLayout(layout); d.setKeepAwake(true);
        Vec3 p = at(h, x, z); d.moveTo(p.x, p.y, p.z, 0, 0);
        h.getLevel().addFreshEntity(d);
        return d;
    }
    private static ServerPlayer player(GameTestHelper h, boolean member) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        p.setData(ModAttachments.SECT, member ? SectState.NONE.joined() : SectState.NONE);
        Vec3 at = at(h, 12.5, 12.5); p.moveTo(at.x, at.y, at.z, 0, 0); p.setOnGround(true);
        return p;
    }
    private static void continuous(GameTestHelper h, SectDisciple d, Vec3[] previous) {
        h.assertTrue(d.position().distanceTo(previous[0]) < 1.6D, "NPC jumped instead of walking: " + previous[0] + " -> " + d.position());
        previous[0] = d.position();
    }

    /** A post near a block corner must be reached by walking, then get its guard pose. */
    @GameTest(template = "sect_yard", timeoutTicks = 400, batch = "sect_walking_fractional_post")
    public static void fractionalPostFinishesThePathAndStartsGuarding(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        var layout = new SectGameTests.Yard(at(h, 12.02, 12.02), 0.5D);
        SectDisciple guard = npc(h, layout, "gatekeeper", 4.5, 4.5);
        Vec3 target = SectLife.resolve(guard).spot();
        Vec3[] previous = {guard.position()};
        h.onEachTick(() -> continuous(h, guard, previous));
        h.succeedWhen(() -> {
            h.assertTrue(guard.position().distanceTo(target) < 0.5D, "guard stopped at the path node instead of its fractional post");
            h.assertTrue(guard.pose() == io.github.verycooltimo.murim.entity.SectPose.GUARD
                    && guard.getNavigation().isDone(), "guard did not start guarding after arrival");
        });
    }

    /** A complete 24,000-tick day, including late arrivals and dormancy far from players. */
    @GameTest(template = "sect_yard", timeoutTicks = 24050, batch = "sect_walking_full_day")
    public static void fullSectDayChangesPositionsContinuously(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.FORMATION);
        var layout = new SectGameTests.Yard(at(h, 12.5, 12.5), 0.3D);
        var people = java.util.List.of(npc(h, layout, "cook_kim", 4.5, 4.5),
                npc(h, layout, "porter_jang", 20.5, 4.5), npc(h, layout, "steward_mun", 4.5, 20.5));
        for (SectDisciple d : people) d.setKeepAwake(false);
        Vec3[] previous = people.stream().map(SectDisciple::position).toArray(Vec3[]::new);
        double[] walked = new double[people.size()];
        int[] ticks = {0};
        h.onEachTick(() -> {
            for (int i = 0; i < people.size(); i++) {
                SectDisciple d = people.get(i);
                double step = d.position().distanceTo(previous[i]);
                h.assertTrue(step < 1.6, "routine warp: " + d.memberKey() + " step=" + step);
                walked[i] += step; previous[i] = d.position();
            }
            if (++ticks[0] == 24000) {
                for (double distance : walked) h.assertTrue(distance > 5, "NPC did not walk during the day");
                h.succeed();
            }
        });
    }

    @GameTest(template = "sect_yard", timeoutTicks = 500, batch = "sect_walking_escort")
    public static void escortWaitsForPlayerWithoutTeleporting(GameTestHelper h) {
        var layout = new Rooms(at(h, 12.5, 12.5));
        SectDisciple guide = npc(h, layout, "steward_mun", 12.5, 11.5);
        ServerPlayer p = player(h, true);
        Vec3 original = p.position();
        Vec3 target = at(h, 12.5, 2.5);
        h.assertTrue(guide.escortGoal().begin(p, target, false), "escort refused");
        Vec3[] previous = {guide.position()};
        int[] ticks = {0};
        h.onEachTick(() -> {
            continuous(h, guide, previous);
            h.assertTrue(p.position().equals(original), "escort teleported the player");
            if (++ticks[0] == 300) {
                h.assertTrue(guide.position().distanceTo(original) > 4, "guide did not lead ahead");
                h.assertTrue(guide.distanceTo(p) < 9, "guide left player behind");
                h.assertTrue(guide.getNavigation().isDone(), "guide did not wait");
                h.succeed();
            }
        });
    }

    @GameTest(template = "sect_yard", timeoutTicks = 600, batch = "sect_walking_follow")
    public static void escortAndFollowingPlayerReachDestination(GameTestHelper h) {
        SectDisciple guide = npc(h, new Rooms(at(h, 12.5, 12.5)), "steward_mun", 12.5, 11.5);
        ServerPlayer p = player(h, true);
        Vec3 target = at(h, 12.5, 3.5);
        h.assertTrue(guide.escortGoal().begin(p, target, false), "escort refused");
        Vec3[] previous = {guide.position()};
        h.onEachTick(() -> {
            continuous(h, guide, previous);
            // Simulated user input follows at a normal walking speed, using collision-aware movement.
            Vec3 delta = guide.position().subtract(p.position());
            if (delta.lengthSqr() > 1) p.move(MoverType.SELF, delta.multiply(1, 0, 1).normalize().scale(0.16D));
        });
        h.succeedWhen(() -> {
            h.assertTrue(p.position().distanceTo(target) < 2 && guide.position().distanceTo(target) < 2, "escort did not arrive");
            h.assertTrue(!guide.escorting(), "escort did not finish");
        });
    }

    @GameTest(template = "sect_yard", timeoutTicks = 1000, batch = "sect_walking_trespass")
    public static void outsiderIsWarnedEscortedThenAttackedForRefusal(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        var layout = new Rooms(at(h, 12.5, 12.5));
        SectDisciple guard = npc(h, layout, "gyeong_tak", 12.5, 11.5);
        ServerPlayer p = player(h, false);
        Vec3 original = p.position();
        boolean[] warned = {false}, escorted = {false};
        h.onEachTick(() -> {
            if (h.getLevel().getGameTime() % SectWatch.PERIOD == 0) SectWatch.check(p, layout);
            warned[0] |= "warn".equals(SectWatch.of(p).last());
            escorted[0] |= "escort".equals(SectWatch.of(p).last());
            h.assertTrue(p.position().equals(original), "guard teleported an outsider");
        });
        h.succeedWhen(() -> {
            h.assertTrue(warned[0] && escorted[0], "guard skipped the warning/escort");
            h.assertTrue(guard.guardTarget() == p, "refusal did not escalate to attack");
        });
    }

    @GameTest(template = "sect_yard", timeoutTicks = 600, batch = "sect_walking_repeat")
    public static void repeatedJailAndTrespassProvokeAttack(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        var layout = new Rooms(at(h, 12.5, 12.5));
        SectDisciple guard = npc(h, layout, "gyeong_tak", 12.5, 11.5);
        ServerPlayer p = player(h, true);
        long[] history = new long[Config.SECT_JAIL_THRESHOLD.get()];
        java.util.Arrays.fill(history, h.getLevel().getGameTime());
        p.getPersistentData().putLongArray("murim_penance_history", history);
        h.onEachTick(() -> { if (h.getLevel().getGameTime() % SectWatch.PERIOD == 0) SectWatch.check(p, layout); });
        h.succeedWhen(() -> h.assertTrue(guard.guardTarget() == p, "repeat offender was not attacked"));
    }

    @GameTest(template = "sect_yard", timeoutTicks = 450, batch = "sect_walking_member")
    public static void memberInGoodStandingIsNeverAttackedForRoaming(GameTestHelper h) {
        var layout = new Rooms(at(h, 12.5, 12.5));
        SectDisciple guard = npc(h, layout, "gyeong_tak", 12.5, 11.5);
        ServerPlayer p = player(h, true);
        p.getPersistentData().putLongArray("murim_penance_history", new long[] {h.getLevel().getGameTime() - Config.SECT_JAIL_DAYS.get() * 24000L - 1});
        int[] ticks = {0};
        h.onEachTick(() -> {
            SectWatch.check(p, layout);
            h.assertTrue(guard.guardTarget() == null && !guard.escortGoal().forPlayer(p) && guard.spar() == SectDisciple.Spar.NONE,
                    "good member was attacked for walking");
            h.assertTrue(p.getData(ModAttachments.SECT).contribution() == 0, "roaming or absence was penalized");
            if (++ticks[0] == 400) h.succeed();
        });
    }

    @GameTest(template = "sect_yard", timeoutTicks = 50, batch = "sect_walking_damage")
    public static void guardCannotKillAndStartsWalkingExpulsion(GameTestHelper h) {
        SectDisciple guard = npc(h, new Rooms(at(h, 12.5, 12.5)), "gyeong_tak", 12.5, 11.5);
        ServerPlayer p = player(h, false);
        // Mock players are not ticked by the level. Let vanilla's 60-tick spawn
        // protection expire so this test exercises real damage and its event hooks.
        for (int i = 0; i < 61; i++) p.tick();
        Vec3 at = at(h, 12.5, 12.5);
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.invulnerableTime = 0;
        h.assertTrue(guard.guardGoal().begin(p, at(h, 12.5, 2.5)), "guard refused combat");
        Vec3 original = p.position();
        h.assertTrue(p.hurt(guard.damageSources().mobAttack(guard), 10000), "guard hit was rejected before damage hooks");
        h.assertTrue(p.isAlive() && p.getHealth() >= SectWatch.guardFloor(p) - 0.01F, "enforcement killed the player");
        h.assertTrue(p.getHealth() <= SectWatch.guardFloor(p) + 0.01F, "guard hit did not reach the defeat floor");
        h.assertTrue("defeated".equals(SectWatch.of(p).last()) && guard.escortGoal().forPlayer(p), "no defeated expulsion");
        h.assertTrue(p.position().equals(original), "defeat teleported the player");
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 200, batch = "sect_building_beds")
    public static void manualBedsAreFoundAndReservedWhileWalking(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        var layout = new Rooms(at(h, 12.5, 12.5));
        SectDisciple a = npc(h, layout, "yul_jin", 10.5, 12.5);
        SectDisciple b = npc(h, layout, "yul_pyo", 11.5, 12.5);
        a.setNoAi(true); b.setNoAi(true);
        for (int z : new int[] {10, 14}) {
            var bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST);
            h.getLevel().setBlock(h.absolutePos(new BlockPos(8, 2, z)), bed.setValue(BedBlock.PART, BedPart.FOOT), 2);
            h.getLevel().setBlock(h.absolutePos(new BlockPos(9, 2, z)), bed.setValue(BedBlock.PART, BedPart.HEAD), 2);
        }
        h.succeedWhen(() -> {
            BlockPos first = SectLife.findBed(a, "dorm_3rd"), second = SectLife.findBed(b, "dorm_3rd");
            h.assertTrue(first != null && second != null && !first.equals(second), "bed scan/reservations failed");
        });
    }

    @GameTest(template = "sect_yard", timeoutTicks = 700, batch = "sect_building_doors")
    public static void walksThroughWoodenDoorInManualHall(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.NIGHT);
        var layout = new SectGameTests.Yard(at(h, 20.5, 12.5), 0.05D);
        for (int z = 1; z <= 23; z++) for (int y = 2; y <= 4; y++) h.setBlock(new BlockPos(12, y, z), Blocks.OAK_PLANKS);
        var door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
        h.setBlock(new BlockPos(12, 2, 12), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        h.setBlock(new BlockPos(12, 3, 12), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        SectDisciple d = npc(h, layout, "cook_kim", 4.5, 12.5);
        Vec3[] previous = {d.position()};
        h.onEachTick(() -> continuous(h, d, previous));
        h.succeedWhen(() -> h.assertTrue(d.getX() > h.absolutePos(new BlockPos(16, 2, 12)).getX(), "NPC did not walk through the door"));
    }
}
