package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** One whole simulated day, with real walking and a gate plane separating the road from the yard. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectGatePeriodGameTests {
    @GameTest(template = "sect_yard", batch = "gate_period_day", timeoutTicks = 25200)
    public static void onlyPortersCrossAndOnlyOncePerPeriod(GameTestHelper h) {
        SectPlaytestRig.clearYard(h);
        Vec3 gate = h.absoluteVec(new Vec3(12.5, 2, 8.5));
        SectLayout layout = new SectLayout() {
            public Vec3 at(String zone, double u, double v) {
                // Preserve body clearance between gate posts and pickup lanes: compressed posts
                // can let unrelated NPC collisions push guards across the measurement plane.
                return gate.add(u * ("sect_gate".equals(zone) ? 0.5 : 0.08), 0,
                        "sect_gate".equals(zone) ? v : 9 + v * 0.08);
            }
            public float yaw(double u, double v) { return 180; }
            public double[] half(String zone) { return new double[] {10, 10}; }
            public double[] local(String zone, Vec3 p) {
                return new double[] {(p.x - gate.x) / ("sect_gate".equals(zone) ? 0.5 : 0.08),
                        "sect_gate".equals(zone) ? p.z - gate.z : (p.z - gate.z - 9) / 0.08};
            }
        };
        String[] keys = {"porter_jang", "porter_oh", "gatekeeper", "gyeong_tak", "", "seo_gyu"};
        // Find the sweeper by role: this test should keep working when the staff's names change.
        keys[4] = SectRoster.ALL.stream().filter(m -> m.role() == SectRole.SWEEPER).findFirst().orElseThrow().key();
        SectDisciple[] people = new SectDisciple[keys.length];
        for (int i = 0; i < keys.length; i++) {
            SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
            d.setMember(SectRoster.of(keys[i]).orElseThrow());
            d.setLayout(layout);
            d.setKeepAwake(true);
            d.moveTo(gate.x + i, gate.y, gate.z + 3, 0, 0);
            h.getLevel().addFreshEntity(d);
            people[i] = d;
        }
        long originalTime = h.getLevel().getDayTime();
        long day = Math.floorDiv(originalTime, 24000L) * 24000L;
        int[] elapsed = {-1};
        boolean[] outside = new boolean[keys.length];
        int[] crossings = new int[keys.length];
        int[] totalCrossings = new int[keys.length];
        long[] lastPeriod = {Long.MIN_VALUE};
        // API: Minecraft 1.21.1 GameTestHelper#onEachTick; ServerLevel#isPositionEntityTicking/setDayTime.
        h.onEachTick(() -> {
            if (elapsed[0] < 0) {
                for (int z = 4; z <= 20; z += 4) {
                    if (!h.getLevel().isPositionEntityTicking(BlockPos.containing(h.absoluteVec(new Vec3(12.5, 2, z))))) return;
                }
                elapsed[0] = 0;
            }
            int t = elapsed[0]++;
            h.getLevel().setDayTime(day + t);
            long period = SectSchedule.day(day + t) * 8L + SectSchedule.at(day + t).ordinal();
            if (lastPeriod[0] != period) {
                java.util.Arrays.fill(crossings, 0);
                lastPeriod[0] = period;
            }
            for (int i = 0; i < people.length; i++) {
                boolean next = people[i].getZ() < gate.z - 0.5;
                if (outside[i] != next) {
                    if (SectTimings.ON) {
                        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/entity/Entity.java#position
                        MurimMod.LOGGER.info("Sect-gate: member={} tick={} period={} outside={} goal={} target={} position={} walk={}",
                                keys[i], h.getLevel().getGameTime(), SectSchedule.at(day + t), next,
                                people[i].doingKind(), people[i].walking().goal(), people[i].position(), people[i].walking().debug());
                    }
                    crossings[i]++;
                    totalCrossings[i]++;
                }
                outside[i] = next;
                h.assertTrue(crossings[i] <= (i < 2 ? 2 : 0),
                        keys[i] + " repeatedly crossed the gate during " + SectSchedule.at(day + t));
            }
            if (t >= 23999) {
                for (int i = 0; i < 2; i++) h.assertTrue(totalCrossings[i] >= 2, keys[i] + " never performed the outside job");
                for (SectDisciple d : people) d.discard();
                h.getLevel().setDayTime(originalTime);
                h.succeed();
            }
        });
    }

    /** Force recovery without relying on a random crowd or wall-clock server load. */
    @GameTest(template = "sect_yard", batch = "gate_recovery", timeoutTicks = 600)
    public static void stalledInsideWorkRejectsOutsideSidesteps(GameTestHelper h) {
        SectPlaytestRig.clearYard(h);
        // No AI movement: the real walker must recover from a stationary actor, on both alternating sides.
        SectDisciple d = SectPlaytestRig.npc(h, SectPlaytestRig.empty(h), "sweeper_ma", 8, 9, false);
        Vec3 start = d.position();
        Vec3 target = start.add(6, 0, 0);
        int[] ticks = {0};
        int[] rejected = {0};
        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/gametest/framework/GameTestHelper.java#onEachTick/assertTrue
        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/level/pathfinder/Path.java#getTarget
        h.onEachTick(() -> {
            if (!h.getLevel().isPositionEntityTicking(BlockPos.containing(target))) return;
            d.walking().walk(target, 0, point -> {
                boolean inside = point.z >= start.z - 0.25D;
                if (!inside) rejected[0]++;
                return inside;
            });
            var path = d.getNavigation().getPath();
            h.assertTrue(path == null || Vec3.atBottomCenterOf(path.getTarget()).z >= start.z - 0.25D,
                    "inside work recovery issued an outside path: " + d.walking().debug());
            if (++ticks[0] >= 250) {
                h.assertTrue(rejected[0] > 0, "outside sidestep was never exercised");
                d.discard();
                h.succeed();
            }
        });
    }

    private SectGatePeriodGameTests() { }
}
