package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import static io.github.verycooltimo.murim.sect.SectPlaytestRig.id;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.npc;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.only;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.player;

/** The gate guard (author 21.10): stands at the gate facing the trail, walks up to every outsider, leaves members alone. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectGateWatchGameTests {
    private static final String YARD = "sect_yard";

    private SectGateWatchGameTests() {
    }

    /** A flat strip of floor north of the yard (the trail), so a guard can walk 30 blocks. */
    private static void trail(GameTestHelper h) {
        // The strip leaves the structure: its chunks must load and tick, or the walker freezes at a chunk border.
        BlockPos a = h.absolutePos(new BlockPos(4, 1, -34));
        BlockPos b = h.absolutePos(new BlockPos(20, 1, 24));
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                h.getLevel().setChunkForced(cx, cz, true);
            }
        }
        for (int x = 4; x <= 20; x++) {
            for (int z = -34; z <= 24; z++) {
                h.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
                for (int y = 2; y <= 6; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    /** One person with a key lives once (SectLife drops the younger twin): earlier tests must not leave a gatekeeper behind. */
    private static void lonely(GameTestHelper h) {
        for (SectDisciple d : h.getLevel().getEntitiesOfClass(SectDisciple.class,
                new net.minecraft.world.phys.AABB(h.absoluteVec(new Vec3(12.5D, 2.0D, 12.5D)), h.absoluteVec(new Vec3(12.5D, 2.0D, 12.5D))).inflate(300.0D))) {
            if ("gatekeeper".equals(d.memberKey())) d.discard();
        }
    }

    private static boolean talking(ServerPlayer p) {
        return p.getData(ModAttachments.DIALOGUE).active();
    }

    // ------------------------------------------------------------------ the post, at every rotation

    @GameTest(template = YARD, timeoutTicks = 40, batch = "gate_watch_post")
    public static void theGatekeeperStandsAtTheGateFacingTheTrailAtEveryRotation(GameTestHelper h) {
        SectRoster m = SectRoster.of("gatekeeper").orElseThrow();
        SectSchedule.Task task = SectSchedule.task(m, SectSchedule.Period.TRAINING, 0);
        h.assertTrue("sect_gate".equals(task.zone()) && task.kind() == SectSchedule.Kind.GUARD, "the gatekeeper's post is " + task.zone());
        var f = SectLayoutFile.shipped().frame();
        for (int r = 0; r < 4; r++) {
            MountHuaSite site = MountHuaSite.placement(f.centerX(), f.centerZ(), f.baseY(), r);
            SectLayoutFile file;
            try (Reader in = new InputStreamReader(SectGateWatchGameTests.class.getResourceAsStream(SectLayoutFile.RESOURCE), StandardCharsets.UTF_8)) {
                file = SectLayoutFile.parse(in, frame -> SectLayoutFile.Anchor.to(frame, site));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            SectPlaces.Pick pick = SectPlaces.pick(file, m, task, 6000L, null, 0L, null);
            h.assertTrue(pick != null, "rotation " + r + ": the layout has no post for the gatekeeper");
            BlockPos inside = file.anchor("gate_inside").orElseThrow().pos();
            BlockPos trailEnd = file.anchor("trail_approach").orElseThrow().pos();
            Vec3 out = new Vec3(trailEnd.getX() - inside.getX(), 0.0D, trailEnd.getZ() - inside.getZ()).normalize();
            double along = (pick.x() - inside.getX() - 0.5D) * out.x + (pick.z() - inside.getZ() - 0.5D) * out.z;
            double flat = Math.hypot(pick.x() - inside.getX() - 0.5D, pick.z() - inside.getZ() - 0.5D);
            h.assertTrue(along > 1.0D && flat < 8.0D, "rotation " + r + ": the post is not at the opening, on the trail side (along " + along + ", flat " + flat + ")");
            double yawRad = Math.toRadians(pick.yaw());
            double faceOut = -Math.sin(yawRad) * out.x + Math.cos(yawRad) * out.z;
            h.assertTrue(faceOut > 0.9D, "rotation " + r + ": the gatekeeper does not face the trail (" + faceOut + ")");
            // the plan's own idea of "out" (used by the detection) agrees with the baked gate
            SectLayout hua = SectLayout.hua(site);
            Vec3 planOut = SectGateWatch.out(hua);
            h.assertTrue(planOut != null && planOut.x * out.x + planOut.z * out.z > 0.9D, "rotation " + r + ": the plan's trail side differs from the baked gate");
            Vec3 gate = hua.at("sect_gate", 0, 0);
            h.assertTrue(Math.hypot(gate.x - inside.getX(), gate.z - inside.getZ()) < 16.0D, "rotation " + r + ": the gate zone is far from the baked gate");
        }
        h.succeed();
    }

    @GameTest(template = YARD, batch = "gate_post_fallback")
    public static void occupiedGatePostsKeepBothGuardsInside(GameTestHelper h) {
        SectLayoutFile file = SectLayoutFile.shipped();
        SectSpots claims = new SectSpots();
        for (var spot : file.spots()) {
            if ("guard_post".equals(spot.kind()) && "gate".equals(spot.group())) {
                claims.claim(spot.id(), "blocked:" + spot.id(), 0);
            }
        }
        var inside = file.anchor("gate_inside").orElseThrow().pos();
        for (String key : java.util.List.of("gatekeeper", "gyeong_tak")) {
            SectRoster guard = SectRoster.of(key).orElseThrow();
            SectPlaces.Pick pick = SectPlaces.pick(file, guard, SectSchedule.stopTask("sect_gate"),
                    4000, claims, 0, java.util.Map.of());
            h.assertTrue(pick != null, key + " fell back to the road-side plan point");
            h.assertTrue(pick.x() == inside.getX() + 0.5 && pick.z() == inside.getZ() + 0.5,
                    key + " did not wait in the inner yard");
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ an outsider is met

    @GameTest(template = YARD, timeoutTicks = 700, batch = "gate_watch_meet")
    public static void anOutsiderAtTwentyFiveBlocksIsMetAndTheTalkOpensAtNight(GameTestHelper h) {
        trail(h);
        lonely(h);
        SectGameTests.setPeriod(h, SectSchedule.Period.NIGHT);
        h.getLevel().setRainLevel(1.0F);
        var layout = only(h, "sect_gate");
        // At night the gatekeeper sleeps (guards-alive 21.10) and the night guard of post 0 holds the gate: he is the one who meets.
        long day = SectSchedule.day(h.getLevel().getDayTime());
        SectDisciple sleeper = npc(h, layout, "gatekeeper", 6, 6, false);
        // No AI means the fixture must put him to bed; otherwise he is an awake, immobile greeter.
        SectPlaytestRig.bed(h, 6, 6);
        sleeper.startSleeping(h.absolutePos(new BlockPos(6, 2, 7)));
        h.assertTrue(sleeper.isSleeping(), "the gatekeeper fixture is not asleep");
        SectDisciple g = npc(h, layout, SectRota.nightWatch(day, 0).key(), 12, 12, true);
        ServerPlayer p = player(h, 12, -13, SectState.NONE);
        Vec3 gate = layout.at("sect_gate", 0, 0);
        h.assertTrue(SectGateWatch.greeter(p, gate) == g, "the covering night guard must greet the outsider");
        h.assertTrue(Math.sqrt(p.distanceToSqr(g)) > 24.0D, "setup: the player is not far");
        double[] farthest = {0.0D};
        h.onEachTick(() -> {
            if (h.getLevel().getGameTime() % 10 == 4) SectGateWatch.tick(p, layout);
            farthest[0] = Math.max(farthest[0], Math.hypot(g.getX() - gate.x, g.getZ() - gate.z));
            h.assertTrue(farthest[0] <= SectGateWatch.LEASH + 3.0D, "the gatekeeper left his leash: " + farthest[0]);
        });
        h.succeedWhen(() -> {
            h.assertTrue(talking(p) && p.getData(ModAttachments.DIALOGUE).npc() == g.getId(), "the gatekeeper did not walk up and open the talk");
            h.assertTrue(g.bubbleAt() > 0L, "no call-out");
            h.assertTrue(farthest[0] > 5.0D, "he did not walk");
        });
    }

    @GameTest(template = YARD, timeoutTicks = 300, batch = "gate_watch_flyer")
    public static void aFlyerLandingNearTheGateIsStillNoticed(GameTestHelper h) {
        trail(h);
        lonely(h);
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        var layout = only(h, "sect_gate");
        SectDisciple g = npc(h, layout, "gatekeeper", 12, 12, true);
        ServerPlayer p = player(h, 12, 2, SectState.NONE);
        p.moveTo(p.getX(), p.getY() + 9.0D, p.getZ(), 0.0F, 0.0F);
        SectGateWatch.tick(p, layout);
        h.assertTrue(g.approachingDialogue(p, SectArrival.DIALOGUE), "a player in the air at the gate was not noticed");
        h.succeed();
    }

    // ------------------------------------------------------------------ members

    @GameTest(template = YARD, timeoutTicks = 200, batch = "gate_watch_member")
    public static void aMemberIsNotStoppedOnlyNodded(GameTestHelper h) {
        trail(h);
        lonely(h);
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        var layout = only(h, "sect_gate");
        SectDisciple g = npc(h, layout, "gatekeeper", 12, 12, true);
        ServerPlayer p = player(h, 12, -8, SectState.NONE.joined());
        SectGateWatch.tick(p, layout);
        h.assertTrue(!g.blocking() && !g.approachingDialogue(p, SectArrival.DIALOGUE), "a member was stopped");
        p.moveTo(p.getX(), p.getY(), g.getZ() - 5.0D, 0.0F, 0.0F);
        long before = g.bubbleAt();
        SectGateWatch.tick(p, layout);
        h.assertTrue(!g.blocking() && !talking(p), "a member was stopped at the gate");
        h.assertTrue(g.bubbleAt() > before, "no nod for a member at the gate");
        h.succeed();
    }

    // ------------------------------------------------------------------ after the talk

    @GameTest(template = YARD, timeoutTicks = 900, batch = "gate_watch_return")
    public static void afterTheTalkHeReturnsToHisPostAndDoesNotPester(GameTestHelper h) {
        trail(h);
        lonely(h);
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        var layout = only(h, "sect_gate");
        SectDisciple g = npc(h, layout, "gatekeeper", 12, 12, true);
        ServerPlayer p = player(h, 12, -6, SectState.NONE);
        Vec3 post = SectLife.resolve(g).spot();
        int[] phase = {0};
        long[] closedAt = {0L};
        h.onEachTick(() -> {
            long now = h.getLevel().getGameTime();
            if (now % 10 == 4) SectGateWatch.tick(p, layout);
            if (phase[0] == 0 && talking(p)) {
                phase[0] = 1;
                closedAt[0] = now;
                DialogueService.close(p, g);   // the player declines
            } else if (phase[0] == 1) {
                h.assertTrue(!talking(p), "he pestered the player again after the player declined");
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(phase[0] == 1, "the talk never opened");
            h.assertTrue(h.getLevel().getGameTime() - closedAt[0] > 200L, "still waiting for him to walk back");
            h.assertTrue(!g.blocking() && g.position().distanceTo(post) < 2.0D, "he did not go back to his post: " + g.position().distanceTo(post));
        });
    }

    // ------------------------------------------------------------------ knocking

    @GameTest(template = YARD, timeoutTicks = 400, batch = "gate_knock_outsider")
    public static void aKnockWithoutATabletKeepsTheGateShutAndTheGatekeeperComes(GameTestHelper h) {
        trail(h);
        lonely(h);
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        var layout = only(h, "sect_gate");
        SectDisciple g = npc(h, layout, "gatekeeper", 12, 12, true);
        ServerPlayer p = player(h, 12, 4, SectState.NONE);
        BlockPos door = BlockPos.containing(layout.at("sect_gate", 0, 0));
        net.minecraft.world.level.block.Block gate = io.github.verycooltimo.murim.decor.GateDoors.GATE_DOOR.get();
        h.assertTrue("gate".equals(SectKnock.shutFor(p, door, layout)), "no tablet, but the gate is not shut for him");
        h.assertTrue(SectKnock.knock(p, door, door, gate, -1, layout), "the click was not a knock");
        h.assertTrue(g.approachingDialogue(p, SectArrival.DIALOGUE), "the gatekeeper did not set out to the knocker");
        g.clearBlock();
        h.assertTrue(SectKnock.knock(p, door, door, gate, -1, layout), "a second knock was not a knock");
        h.assertTrue(!g.approachingDialogue(p, SectArrival.DIALOGUE), "the knocker summoned him again at once");
        // with the tablet the gate opens as before
        p.getInventory().add(new net.minecraft.world.item.ItemStack(io.github.verycooltimo.murim.registry.ModItems.JADE_SECT_TABLET.get()));
        h.assertTrue(SectKnock.shutFor(p, door, layout) == null && !SectKnock.knock(p, door, door, gate, -1, layout), "the tablet did not open the gate");
        // an ordinary door far from the gate and every closed place is not touched
        BlockPos dorm = door.offset(0, 0, 30);
        ServerPlayer q = player(h, 12, 5, SectState.NONE);
        h.assertTrue(SectKnock.shutFor(q, dorm, layout) == null, "an ordinary door asked for a knock");
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 400, batch = "gate_knock_member")
    public static void aMemberWithoutATabletKnocksAndTheGuardComesAndAnswers(GameTestHelper h) {
        trail(h);
        lonely(h);
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        var layout = only(h, "sect_gate");
        SectDisciple g = npc(h, layout, "gatekeeper", 12, 12, true);
        ServerPlayer p = player(h, 12, 4, SectState.NONE.joined());
        BlockPos door = BlockPos.containing(layout.at("sect_gate", 0, 0));
        net.minecraft.world.level.block.Block gate = io.github.verycooltimo.murim.decor.GateDoors.GATE_DOOR.get();
        h.assertTrue(SectKnock.knock(p, door, door, gate, -1, layout), "a member without a tablet did not knock");
        h.assertTrue(g.blocking() && !talking(p), "the guard did not come to a member");
        h.assertTrue(g.bubbleAt() > 0L, "no answer over the guard's head");
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 200, batch = "gate_first_blow")
    public static void aFirstBlowBeforeTheGreetingStartsNoFight(GameTestHelper h) {
        trail(h);
        lonely(h);
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        var layout = only(h, "sect_gate");
        SectDisciple g = npc(h, layout, "gatekeeper", 12, 12, true);
        ServerPlayer p = player(h, 12, 11, SectState.NONE);
        g.hurt(h.getLevel().damageSources().playerAttack(p), 2.0F);
        h.assertTrue(g.bubbleAt() > 0L, "the gatekeeper did not answer the blow");
        h.assertTrue(g.getTarget() == null && g.guardTarget() == null, "the blow started a fight");
        h.succeed();
    }
}
