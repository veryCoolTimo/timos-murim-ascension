package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectPose;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;

import static io.github.verycooltimo.murim.sect.SectPlaytestRig.*;

/** Release 0.1.0: voluntary arrival, seniority, stable housing and peaceful hall enforcement.
 * API checked in /tmp/mr-ref-1121: Minecraft GameTestHelper/Sequence, Entity#move,
 * ServerPlayer#getRespawnPosition/setRespawnPosition and Inventory; NeoForge gametest annotations.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectFirstImpressionGameTests {
    private SectFirstImpressionGameTests() { }

    private static ResourceLocation dialogue(String name) { return id(name); }

    private static void choose(GameTestHelper h, ServerPlayer p, String text) {
        var session = p.getData(ModAttachments.DIALOGUE);
        var node = DialogueLoader.get(session.dialogue()).nodes().get(session.node());
        for (int i = 0; i < session.shown().size(); i++) {
            int raw = session.shown().get(i);
            if (raw < node.options().size() && raw >= 0 && text.equals(node.options().get(raw).text())) {
                DialogueService.choose(p, i);
                return;
            }
        }
        h.fail("missing answer " + text + " in " + session.node());
    }

    @GameTest(template = "sect_yard", batch = "first_impression_tour", timeoutTicks = 40)
    public static void decliningTourLeavesLessonsAndHeadInvitationAvailable(GameTestHelper h) {
        var layout = only(h, "mentor", "main_hall");
        ServerPlayer p = player(h, 12, 12, SectState.NONE.joined().with("arrival.tour")
                .with("arrival.ceremony_pending").with("intro.done").with("basics.offered"));
        SectDisciple mentor = npc(h, layout, "gyeong_pil", 13, 12, false);
        h.assertTrue(DialogueService.openDialogue(p, mentor, dialogue("mentor"), "tour_hall"), "tour did not open");
        choose(h, p, "dialogue.murim.tour.opt.skip");
        h.assertTrue(!SectService.state(p).has("arrival.tour"), "declining left tour pending");
        choose(h, p, "dialogue.murim.tour.opt.done");
        h.assertTrue(SectService.state(p).has("arrival.ceremony_pending"), "declining cancelled head invitation");
        h.assertTrue(DialogueService.openDialogue(p, mentor, dialogue("mentor"), "lessons"), "lessons blocked by head invitation");
        h.assertTrue(!"tour_resume".equals(DialogueService.route(p, mentor).node()), "mentor offered declined tour again");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "first_impression_guide", timeoutTicks = 40)
    public static void desireToStudyDoesNotOrderAGuide(GameTestHelper h) {
        var layout = only(h, "sect_gate", "mentor");
        ServerPlayer p = player(h, 12, 12, SectState.NONE);
        SectDisciple guard = npc(h, layout, "gatekeeper", 13, 12, false);
        h.assertTrue(DialogueService.openDialogue(p, guard, SectArrival.DIALOGUE, "question"), "gate talk unavailable");
        var d = DialogueLoader.get(SectArrival.DIALOGUE);
        var options = d.nodes().get("question").options();
        String answer = options.stream().filter(o -> o.actions().stream().anyMatch(a -> "arrival_invite".equals(a.type())))
                .findFirst().orElseThrow().text();
        choose(h, p, answer);
        h.assertTrue(SectService.state(p).has(SectArrival.INVITED), "invitation missing");
        h.assertTrue(!SectService.state(p).has(SectArrival.ORDERED) && !guard.escortGoal().forPlayer(p), "study summoned a guide");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "first_impression_bows", timeoutTicks = 40)
    public static void approachBowsOnlyUpTheSectHierarchy(GameTestHelper h) {
        var layout = only(h, "training");
        ServerPlayer p = player(h, 12, 12, SectState.NONE.joined());
        SectDisciple senior = npc(h, layout, "tae_seong", 13, 12, false);
        SectApproach.arrive(senior, p, "greet");
        h.assertTrue(senior.pose() != SectPose.BOW && "nod".equals(senior.gesture()), "senior bowed to novice");
        SectDisciple junior = npc(h, layout, "yul_jin", 11, 12, false);
        p.setData(ModAttachments.SECT, SectState.NONE.joined().with(SectPositions.ELDER));
        SectApproach.arrive(junior, p, "greet");
        h.assertTrue(junior.pose() == SectPose.BOW, "junior did not bow to elder");
        p.setData(ModAttachments.SECT, SectState.NONE);
        h.assertTrue(!SectSocial.mayBow(junior, p, true), "stranger was owed a bow");
        p.setData(ModAttachments.SECT, SectState.NONE.joined().with(SectStanding.LESSON_ONE));
        h.assertTrue(!SectSocial.mayBow(junior, p, false) && SectSocial.mayBow(junior, p, true), "equals need a deed");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "first_impression_hall_exit", timeoutTicks = 40)
    public static void hallExitRemainsOpenAfterPermissionExpires(GameTestHelper h) {
        var layout = only(h, "main_hall");
        ServerPlayer p = player(h, 12, 12, SectState.NONE.joined());
        BlockPos door = BlockPos.containing(layout.at("main_hall", 0, -10));
        h.assertTrue(SectKnock.shutFor(p, door, layout, true) == null, "junior cannot leave the hall");
        p.setData(ModAttachments.SECT, SectState.NONE);
        h.assertTrue(SectKnock.shutFor(p, door, layout, true) == null, "stranger cannot leave the hall");
        Vec3 out = layout.at("main_hall", 0, -13);
        p.moveTo(out.x, out.y, out.z, 0, 0);
        h.assertTrue("main_hall".equals(SectKnock.shutFor(p, door, layout, true)), "exit exception also granted entry");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "first_impression_hall_escort", timeoutTicks = 380)
    public static void hallIntruderIsCarriedOutWithoutDamage(GameTestHelper h) {
        clearYard(h);
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        var layout = new Hall(h.absoluteVec(new Vec3(12.5, 2, 12.5)));
        ServerPlayer p = player(h, 11, 11, SectState.NONE);
        SectDisciple guard = npc(h, layout, "gyeong_tak", 12, 11, true);
        SectDisciple senior = npc(h, layout, "gyeong_pil", 14, 11, false);
        var rule = SectAccess.RULES.stream().filter(r -> "main_hall".equals(r.id())).findFirst().orElseThrow();
        var trespass = new SectWatch.Trespass();
        float health = p.getHealth();
        SectWatch.escalate(p, guard, rule, layout, SectStanding.OUTSIDER, trespass, p.level().getGameTime());
        h.assertTrue(guard.escortGoal().forcedFor(p) && guard.guardTarget() == null, "hall intruder was not peacefully escorted");
        boolean[] moved = {false};
        h.onEachTick(() -> {
            // Mock ServerPlayer has no client to integrate the normal collision-aware escort velocity.
            if (guard.escortGoal().forcedFor(p)) {
                Vec3 velocity = p.getDeltaMovement();
                moved[0] |= velocity.horizontalDistanceSqr() > 0.0001D;
                p.move(net.minecraft.world.entity.MoverType.SELF, velocity.multiply(1, 0, 1));
                p.setDeltaMovement(Vec3.ZERO);
            }
        });
        h.startSequence().thenWaitUntil(() -> {
            h.assertTrue(moved[0] && !SectAccess.inside(layout, rule, p.position(), 0), "intruder has not been led out");
        }).thenExecute(() -> {
            h.assertTrue(p.getHealth() == health && !SectWar.atWar(p), "hall escort caused damage or war");
            guard.escortGoal().cancel();
            trespass.lastLeadOut = Long.MIN_VALUE / 2;
            Vec3 back = layout.at("main_hall", -1, -1);
            p.moveTo(back.x, back.y, back.z, 0, 0);
            SectWatch.leadOut(p, guard, rule, layout, trespass, p.level().getGameTime());
            h.assertTrue("murim.sect.guard.hall_repeat".equals(senior.bubbleKey()), "repeat intrusion lacked senior reprimand");
            h.assertTrue(guard.guardTarget() == null, "repeat intrusion started combat");
        }).thenSucceed();
    }

    private record Hall(Vec3 origin) implements SectLayout {
        @Override public Vec3 at(String zone, double u, double v) { return "main_hall".equals(zone) ? origin.add(u, 0, v) : null; }
        @Override public float yaw(double u, double v) { return SectLayout.yawOf(u, v); }
        @Override public double[] half(String zone) { return "main_hall".equals(zone) ? new double[] {4, 4} : null; }
        @Override public double[] local(String zone, Vec3 p) { return "main_hall".equals(zone) ? new double[] {p.x - origin.x, p.z - origin.z} : null; }
    }

    private record Dorms(Vec3 origin, SectLayoutFile file) implements SectLayout {
        @Override public Vec3 at(String zone, double u, double v) { return origin.add(u, 0, v); }
        @Override public float yaw(double u, double v) { return SectLayout.yawOf(u, v); }
        @Override public double[] half(String zone) { return new double[] {10, 10}; }
        @Override public double[] local(String zone, Vec3 p) { return new double[] {p.x - origin.x, p.z - origin.z}; }
    }

    @GameTest(template = "sect_yard", batch = "first_impression_rooms", timeoutTicks = 40)
    public static void promotionClaimsSecondGenerationSpareAndRespawn(GameTestHelper h) {
        clearYard(h);
        bed(h, 6, 8); bed(h, 16, 8);
        BlockPos old = h.absolutePos(new BlockPos(6, 2, 9)), better = h.absolutePos(new BlockPos(16, 2, 9));
        var file = new SectLayoutFile(new SectLayoutFile.Frame(0, 0, 0, 0), Map.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new SectLayoutFile.Bed("old", old, old.east(), 0, "third", "dorm_3rd", "spare", null, 1, "ok"),
                        new SectLayoutFile.Bed("better", better, better.east(), 0, "second", "dorm_2nd", "spare", null, 2, "ok")),
                List.of(), List.of(), List.of(), List.of());
        var layout = new Dorms(h.absoluteVec(new Vec3(12.5, 2, 12.5)), file);
        ServerPlayer p = player(h, 7, 9, SectState.NONE.joined());
        SectRooms.assign(p, layout, false);
        h.assertTrue(old.equals(SectRooms.bed(p)), "newcomer did not get third-generation bed");
        p.setData(ModAttachments.SECT, SectService.state(p).with(SectStanding.LESSON_TWO));
        SectRooms.assign(p, layout, false);
        h.assertTrue(better.equals(SectRooms.bed(p)) && better.equals(p.getRespawnPosition()), "promotion did not move bed and respawn");
        h.assertTrue(SectRooms.available(h.getLevel(), old, "other"), "old bed not released");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "first_impression_far_room", timeoutTicks = 40)
    public static void unloadedBedSurvivesPromotionUntilBetterRoomLoads(GameTestHelper h) {
        clearYard(h);
        ServerPlayer p = player(h, 12, 12, SectState.NONE.joined().record(SectBouts.GRANTED, SectStanding.GRADUATE.ordinal())
                .with("room.assigned").with("room.bed").record("room.zone", 0));
        h.assertTrue(SectService.standing(p) == SectStanding.GRADUATE,
                "promotion fixture standing=" + SectService.standing(p));
        var housing = h.getLevel().getDataStorage().computeIfAbsent(SectHousingData.FACTORY, "murim_sect_rooms");
        String owner = "player:" + p.getUUID();
        // SavedData outlives a test invocation. Never reuse another run's fixed far-bed claim.
        BlockPos far = new BlockPos(20000000, 120, 20000000);
        while (housing.beds.containsKey(far.asLong())) far = far.east();
        h.assertTrue(!p.level().isLoaded(far), "fixture bed is loaded");
        p.setData(ModAttachments.SECT, SectService.state(p).record("room.pos", far.asLong()));
        h.assertTrue(SectRooms.claim(h.getLevel(), far, owner),
                "fixture claim failed: " + roomState(p, housing, far));
        try {
            // A loaded lower-tier spare must not replace the retained bed while the better chunk is absent.
            bed(h, 6, 8);
            BlockPos lower = h.absolutePos(new BlockPos(6, 2, 9));
            BlockPos better = h.absolutePos(new BlockPos(1000000, 2, 9));
            h.assertTrue(!p.level().isLoaded(better), "better room fixture is already loaded");
            var file = new SectLayoutFile(new SectLayoutFile.Frame(0, 0, 0, 0), Map.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(new SectLayoutFile.Bed("lower", lower, lower.east(), 0, "third", "dorm_3rd", "spare", null, 1, "ok"),
                            new SectLayoutFile.Bed("better", better, better.east(), 0, "second", "dorm_2nd", "spare", null, 2, "ok")),
                    List.of(), List.of(), List.of(), List.of());
            var layout = new Dorms(h.absoluteVec(new Vec3(12.5, 2, 12.5)), file);
            SectRooms.assign(p, layout, false);
            h.assertTrue(far.equals(SectRooms.bed(p)) && owner.equals(housing.beds.get(far.asLong()))
                            && SectService.state(p).has("room.bed"),
                    "far bed was freed or replaced by mat: " + roomState(p, housing, far));
            // Block placement loads the better chunk; no ticking ticket is needed for this synchronous assignment check.
            bed(h, 1000000, 8);
            h.setBlock(new BlockPos(1000001, 1, 9), net.minecraft.world.level.block.Blocks.STONE);
            h.setBlock(new BlockPos(1000001, 2, 9), net.minecraft.world.level.block.Blocks.AIR);
            h.setBlock(new BlockPos(1000001, 3, 9), net.minecraft.world.level.block.Blocks.AIR);
            h.assertTrue(p.level().isLoaded(better), "better room did not load");
            SectRooms.assign(p, layout, false);
            h.assertTrue(better.equals(SectRooms.bed(p)) && better.equals(p.getRespawnPosition()), "loaded better room was not assigned: expected=" + better + ", respawn=" + p.getRespawnPosition()
                        + ", " + roomState(p, housing, far));
            h.assertTrue(SectRooms.available(h.getLevel(), far, "other"), "promotion retained the old claim after moving: " + roomState(p, housing, far));
            h.succeed();
        } finally {
            SectRooms.release(p);
        }
    }

    private static String roomState(ServerPlayer p, SectHousingData housing, BlockPos far) {
        return "bed=" + SectRooms.bed(p) + ", farOwner=" + housing.beds.get(far.asLong())
                + ", room.bed=" + SectService.state(p).has("room.bed")
                + ", standing=" + SectService.standing(p) + ", zone=" + SectService.state(p).entry("room.zone", -1);
    }

    @GameTest(template = "sect_yard", batch = "first_impression_home", timeoutTicks = 40)
    public static void joiningPreservesHomeRespawn(GameTestHelper h) {
        ServerPlayer p = player(h, 12, 12, SectState.NONE);
        BlockPos home = h.absolutePos(new BlockPos(4, 2, 4));
        p.setRespawnPosition(p.level().dimension(), home, 37, true, false);
        SectService.join(p);
        SectBedMark.admissionRespawn(p, only(h, "dorm_3rd"));
        h.assertTrue(home.equals(p.getRespawnPosition()) && p.getRespawnAngle() == 37, "joining overwrote home");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "first_impression_manual", timeoutTicks = 40)
    public static void lostUnreadManualAnswerReplacesOnlyOncePerDay(GameTestHelper h) {
        ServerPlayer p = player(h, 12, 12, SectState.NONE.joined().with("lesson.six"));
        SectDisciple mentor = npc(h, only(h, "mentor"), "gyeong_pil", 13, 12, false);
        p.getInventory().clearContent();
        h.assertTrue(DialogueService.openDialogue(p, mentor, dialogue("mentor"), "lessons"), "lessons unavailable");
        choose(h, p, "dialogue.murim.mentor.opt.lost_book");
        h.assertTrue(p.getInventory().countItem(ModItems.TECHNIQUE_MANUAL.get()) == 1, "lost manual was not replaced");
        h.assertTrue(SectService.lostBook(p) == null, "carried book offered again");
        p.getInventory().clearContent();
        h.assertTrue(!SectService.replaceBook(p, mentor), "second replacement on same day");
        long nextDay = SectSchedule.day(p.level().getDayTime()) + 1;
        p.setData(ModAttachments.SECT, SectService.state(p).record("book.replaced.day", nextDay - 2));
        h.assertTrue(SectService.replaceBook(p, mentor), "later-day request blocked");
        var arts = List.of(SectService.SIX,
                io.github.verycooltimo.murim.technique.Styles.SEVEN_STARS.forms().getFirst(),
                io.github.verycooltimo.murim.technique.Styles.BAMBOO_HAND.forms().getFirst());
        String[] lessons = {"lesson.six", "lesson.seven_stars", "lesson.bamboo_hand"};
        for (int i = 0; i < arts.size(); i++) {
            p.getInventory().clearContent();
            p.setData(ModAttachments.SECT, SectState.NONE.joined().with(lessons[i]));
            h.assertTrue(arts.get(i).equals(SectService.lostBook(p)), "wrong lost manual for " + lessons[i]);
            h.assertTrue(SectService.replaceBook(p, mentor), "replacement refused for " + lessons[i]);
            p.getInventory().clearContent();
            p.setData(ModAttachments.MASTERY, p.getData(ModAttachments.MASTERY).with(arts.get(i),
                    io.github.verycooltimo.murim.mastery.TechniqueProgress.learned(0, 12)));
            p.setData(ModAttachments.SECT, SectService.state(p).record("book.replaced.day", -100));
            h.assertTrue(SectService.lostBook(p) == null, "learned art offered another manual");
        }
        h.succeed();
    }
}
