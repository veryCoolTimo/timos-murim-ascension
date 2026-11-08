package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.SwordGuard;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.function.Consumer;

/** Reuse the existing yard, no new structure templates. Each synchronous fixture owns and releases its scene.
 * API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/gametest/framework/{GameTestHelper,GameTestSequence}.java;
 * /tmp/mr-ref-1121/neoforge-src/net/neoforged/neoforge/{gametest,event/entity/player}.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectBladeExamGameTests {
    private SectBladeExamGameTests() { }

    private record Rig(GameTestHelper h, SectLayout layout, ServerPlayer p, SectDisciple mentor) { }

    private static ServerPlayer pupil(GameTestHelper h, Vec3 at) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.setOnGround(true);
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withTags("pure", "murim:six_harmonies").withRank(2));
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(SectService.SIX, TechniqueProgress.learned(2, 6))
                .with(SectJourney.PETAL, TechniqueProgress.learned(0, 6)));
        p.setData(ModAttachments.SECT, SectState.NONE.joined().with(SectStanding.LESSON_ONE)
                .with(SectService.LESSON_SPAR).with(SectService.SPAR_CLEAN));
        return p;
    }

    private static void fixture(GameTestHelper h, Consumer<Rig> check) {
        SectLayout layout = new SectGameTests.Yard(h.absoluteVec(new Vec3(12.5, 2, 14.5)), 1);
        Vec3 ring = layout.at("training", 0, -5);
        ServerPlayer p = pupil(h, ring);
        SectDisciple m = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        m.setMember(SectRoster.of("gyeong_pil").orElseThrow());
        m.setNoAi(true); m.setKeepAwake(true);
        m.moveTo(ring.x - BladeExamGeometry.START_DISTANCE, ring.y, ring.z, 0, 0);
        h.getLevel().addFreshEntity(m);
        h.startSequence().thenWaitUntil(() -> h.assertTrue(h.getLevel().getEntitiesOfClass(SectDisciple.class,
                m.getBoundingBox().inflate(1), d -> d == m).contains(m), "mentor chunk not queryable"))
                .thenExecute(() -> {
                    h.assertTrue(SectBladeExam.eligible(p), "fixture must reach submit_spar");
                    try { check.accept(new Rig(h, layout, p, m)); }
                    finally {
                        SectBladeExam.Scene s = SectLife.data(h.getLevel()).bladeExam;
                        if (s != null) SectBladeExam.end(h.getLevel(), s, false, false);
                        SectRooms.release(p);
                        m.discard();
                    }
                }).thenSucceed();
    }

    @GameTest(template = "sect_yard", batch = "blade_exam", timeoutTicks = 100)
    public static void thirdRankPassUnlocksBreakthroughBeforeLessonReward(GameTestHelper h) {
        fixture(h, r -> {
            r.p.setData(ModAttachments.PROFILE, r.p.getData(ModAttachments.PROFILE).withRank(io.github.verycooltimo.murim.cultivation.Realm.THIRD));
            r.p.setData(ModAttachments.SECT, SectService.state(r.p)
                    .with("rank2.win.a").with("rank2.win.b").with("rank2.win.c"));
            h.assertTrue(SectBladeExam.eligible(r.p), "third-rank pupil not eligible");
            SectBladeExam.call(h.getLevel(), r.layout, List.of(r.p));
            h.assertTrue(SectBladeExam.invited(r.p, r.mentor), "review did not call third-rank pupil");
            SectBladeExam.decline(r.p, r.mentor);
            h.assertTrue(SectBouts.trialOpen(r.p), "declining removed the old trial fallback");
            int merit = SectService.state(r.p).merit();
            SectBladeExam.Scene scene = start(r);
            holdStart(r, scene);
            SectBladeExam.step(h.getLevel(), scene.start + SectBladeExam.WINDUP + SectBladeExam.CUT + SectBladeExam.HOLD);
            h.assertTrue(SectProgression.secondSparProof(r.p), "pass did not supply second test");
            h.assertTrue(!SectService.state(r.p).has(SectStanding.LESSON_TWO)
                    && SectService.state(r.p).merit() == merit, "lesson rewards before breakthrough");
            h.assertTrue(!SectBladeExam.eligible(r.p), "passed exam repeats before breakthrough");
            r.p.setData(ModAttachments.PROFILE, r.p.getData(ModAttachments.PROFILE).withRank(2));
            h.assertTrue(SectBladeExam.complete(r.p, r.mentor)
                    && SectService.state(r.p).has(SectStanding.LESSON_TWO), "rank-two hand-in lost");
        });
    }

    private static SectBladeExam.Scene start(Rig r) {
        SectBladeExam.call(r.h.getLevel(), r.layout, List.of(r.p));
        SectBladeExam.Scene s = SectLife.data(r.h.getLevel()).bladeExam;
        r.h.assertTrue(s != null && SectBladeExam.invited(r.p, r.mentor), "eligible pupil not called");
        SectBladeExam.step(r.h.getLevel(), s.called);
        r.h.assertTrue(s.start >= 0 && r.mentor.drawn(), "ring entry did not draw real sword");
        return s;
    }

    private static void holdStart(Rig r, SectBladeExam.Scene s) {
        for (int t = 1; t <= SectBladeExam.WINDUP + SectBladeExam.CUT; t++) {
            SectBladeExam.step(r.h.getLevel(), s.start + t);
        }
    }

    @GameTest(template = "sect_yard", batch = "blade_exam", timeoutTicks = 100)
    public static void fullSizeStraightBladeStopsBeforeForehead(GameTestHelper h) {
        fixture(h, r -> {
            SectBladeExam.Scene scene = start(r);
            holdStart(r, scene);
            double gap = Math.sqrt(r.p.position().subtract(r.mentor.position()).multiply(1, 0, 1).lengthSqr());
            // Independently apply the renderer's Rx then Ry to the full shoulder-to-tip vector.
            double pitch = BladeExamGeometry.HOLD_PITCH, yaw = BladeExamGeometry.HOLD_YAW;
            double tipX = BladeExamGeometry.SHOULDER_X
                    + BladeExamGeometry.REACH * Math.sin(pitch) * Math.sin(yaw);
            double tipY = BladeExamGeometry.SHOULDER_Y - BladeExamGeometry.REACH * Math.cos(pitch);
            double tipForward = -BladeExamGeometry.REACH * Math.sin(pitch) * Math.cos(yaw);
            double clearance = gap - BladeExamGeometry.FACE_FORWARD - tipForward;
            h.assertTrue(Math.abs(tipX) < 0.001 && Math.abs(tipY - BladeExamGeometry.FOREHEAD_Y) < 0.001,
                    "straight blade misses centre of forehead");
            h.assertTrue(clearance >= 0.1 && clearance <= 0.2,
                    "full-size blade tip must stop 0.1-0.2 blocks before forehead: " + clearance);
        });
    }

    @GameTest(template = "sect_yard", batch = "blade_exam", timeoutTicks = 100)
    public static void serverTickDoesNotDeclineSceneInOtherDimensions(GameTestHelper h) {
        fixture(h, r -> {
            SectBladeExam.call(h.getLevel(), r.layout, List.of(r.p));
            SectBladeExam.Scene scene = SectLife.data(h.getLevel()).bladeExam;
            h.assertTrue(scene != null, "fixture was not invited");
            // API: /tmp/mr-ref-1121/neoforge-src/net/neoforged/neoforge/event/tick/ServerTickEvent.java#Post
            var server = h.getLevel().getServer();
            int levels = 0;
            for (var level : server.getAllLevels()) levels++;
            h.assertTrue(levels > 1, "regression requires multiple dimensions");
            SectBladeExam.tick(new net.neoforged.neoforge.event.tick.ServerTickEvent.Post(() -> true, server));
            h.assertTrue(SectLife.data(h.getLevel()).bladeExam == scene,
                    "foreign dimension declined the shared scene");
            h.assertTrue(scene.start >= 0, "owning dimension did not start the scene");
        });
    }

    @GameTest(template = "sect_yard", batch = "blade_exam", timeoutTicks = 100)
    public static void onlyEligiblePresentPupilIsCalledAtReviewEnd(GameTestHelper h) {
        fixture(h, r -> {
            SectBladeExam.call(h.getLevel(), r.layout, List.of());
            h.assertTrue(SectLife.data(h.getLevel()).bladeExam == null, "called absent pupil");
            r.p.setData(ModAttachments.SECT, SectService.state(r.p).without(SectService.SPAR_CLEAN));
            SectBladeExam.call(h.getLevel(), r.layout, List.of(r.p));
            h.assertTrue(SectLife.data(h.getLevel()).bladeExam == null, "called ineligible pupil");
            r.p.setData(ModAttachments.SECT, SectService.state(r.p).with(SectService.SPAR_CLEAN));
            Vec3 at = r.p.position();
            r.p.moveTo(at.x + 25, at.y, at.z, 0, 0);
            SectBladeExam.call(h.getLevel(), r.layout, List.of(r.p));
            h.assertTrue(SectLife.data(h.getLevel()).bladeExam == null, "called pupil outside square");
            r.p.moveTo(at.x, at.y, at.z, 0, 0);
            // The real review finish hook reads the square's players, including spectators who did not sign up.
            h.getLevel().players().add(r.p);
            try {
                SectReview.State review = SectReview.begin(h.getLevel(), r.layout, 1000, List.of(), 48);
                for (int rounds = 0; rounds < 3 && SectReview.state(h.getLevel()) != null; rounds++) {
                    for (SectReview.Bout b : List.copyOf(review.bouts())) {
                        if (b.winner().isEmpty()) SectReview.decide(h.getLevel(), review, b, b.a, "fixture");
                    }
                    SectReview.step(h.getLevel(), r.layout);
                }
                h.assertTrue(SectBladeExam.invited(r.p, r.mentor), "review did not call eligible spectator");
                h.assertTrue(r.mentor.bubbleKey().equals("murim.bubble.blade_exam.call"), "missing call bubble");
            } finally { h.getLevel().players().remove(r.p); }
        });
    }

    @GameTest(template = "sect_yard", batch = "blade_exam", timeoutTicks = 100)
    public static void earlyReviewEndCallsAndWalkingAwayDeclines(GameTestHelper h) {
        fixture(h, r -> {
            SectState before = SectService.state(r.p);
            h.getLevel().players().add(r.p);
            try {
                SectReview.begin(h.getLevel(), r.layout, 1001, List.of(), 48);
                SectReview.abort(h.getLevel(), "murim.sect.review.closed", r.layout);
                h.assertTrue(SectBladeExam.invited(r.p, r.mentor), "early review end omitted call");
                Vec3 at = r.p.position();
                r.p.moveTo(at.x + 25, at.y, at.z, 0, 0);
                SectBladeExam.step(h.getLevel(), h.getLevel().getGameTime());
                h.assertTrue(SectLife.data(h.getLevel()).bladeExam == null && before.equals(SectService.state(r.p)),
                        "walking away did not decline without cost");
            } finally { h.getLevel().players().remove(r.p); }
        });
    }

    @GameTest(template = "sect_yard", batch = "blade_exam", timeoutTicks = 100)
    public static void threeBreathsMatchDialogueRewardsAndNeverHurt(GameTestHelper h) {
        fixture(h, r -> {
            ServerPlayer fallback = pupil(h, r.p.position());
            try {
                h.assertTrue(SectBladeExam.complete(fallback, r.mentor), "fallback option unavailable");
                float health = r.p.getHealth();
                Vec3 velocity = r.p.getDeltaMovement();
                SectBladeExam.Scene s = start(r);
                holdStart(r, s);
                for (int tick = 1; tick < SectBladeExam.HOLD; tick++) {
                    // Head turns are permitted.
                    r.p.setYRot(tick * 9); r.p.setXRot(tick % 80);
                    SectBladeExam.step(h.getLevel(), s.start + SectBladeExam.WINDUP + SectBladeExam.CUT + tick);
                    h.assertTrue(!SectService.state(r.p).has(SectStanding.LESSON_TWO), "passed before 180 ticks");
                    h.assertTrue(r.p.getHealth() == health && r.p.getDeltaMovement().equals(velocity), "blade hurt or knocked pupil");
                }
                SectBladeExam.step(h.getLevel(), s.start + SectBladeExam.WINDUP + SectBladeExam.CUT + SectBladeExam.HOLD);
                SectState sceneState = SectService.state(r.p);
                SectState dialogueState = SectService.state(fallback);
                // Graduation now assigns housing. Distinct players must receive distinct places, while all rewards match.
                h.assertTrue(sceneState.has("room.assigned") && dialogueState.has("room.assigned"), "graduation did not assign housing");
                String place = sceneState.has("room.bed") ? "room.pos" : "room.slot";
                h.assertTrue(sceneState.entry(place, -1) != dialogueState.entry(place, -1), "graduates share the same housing");
                h.assertTrue(sceneState.record("room.pos", 0).record("room.slot", 0)
                        .equals(dialogueState.record("room.pos", 0).record("room.slot", 0)),
                        "scene and dialogue grant different state beyond personal housing");
                // The style manual carries the awarded Cut id; SectJourney.SEVEN is the basic it teaches first.
                var manual = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "seven_plum_blossoms");
                long books = r.p.getInventory().items.stream().filter(i -> manual.equals(i.get(ModDataComponents.TECHNIQUE.get()))).count();
                long fallbackBooks = fallback.getInventory().items.stream().filter(i -> manual.equals(i.get(ModDataComponents.TECHNIQUE.get()))).count();
                h.assertTrue(fallbackBooks == books, "scene and dialogue grant different books");
                h.assertTrue(books == 1 && r.p.getHealth() == health, "missing/duplicate book or blade damage");
                h.assertTrue(SectLife.data(h.getLevel()).bladeExam == null && !r.mentor.drawn() && r.mentor.isNoAi(), "actor not restored");
                h.assertTrue(!SectBladeExam.complete(r.p, r.mentor), "duplicate completion accepted");
            } finally {
                SectRooms.release(fallback);
            }
        });
    }

    @GameTest(template = "sect_yard", batch = "blade_exam", timeoutTicks = 100)
    public static void flinchesFailImmediatelyWithoutStateCost(GameTestHelper h) {
        fixture(h, r -> {
            for (int kind = 0; kind < 5; kind++) {
                Vec3 ring = r.layout.at("training", 0, -5);
                r.p.moveTo(ring.x, ring.y, ring.z, 0, 0); r.p.setOnGround(true);
                r.mentor.moveTo(ring.x - BladeExamGeometry.START_DISTANCE, ring.y, ring.z, 0, 0);
                SectState before = SectService.state(r.p);
                SectStanding standing = SectService.standing(r.p);
                float hp = r.p.getHealth();
                SectBladeExam.Scene s = start(r); holdStart(r, s);
                switch (kind) {
                    case 0 -> r.p.moveTo(ring.x + 0.5, ring.y, ring.z, 0, 0);
                    case 1 -> { r.p.setOnGround(false); r.p.moveTo(ring.x, ring.y + 0.4, ring.z, 0, 0); }
                    case 2 -> {
                        AttackEntityEvent attack = new AttackEntityEvent(r.p, r.mentor);
                        SectBladeExam.attack(attack);
                        h.assertTrue(attack.isCanceled(), "scene attack reached brother");
                    }
                    case 3 -> {
                        r.p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.HUASHAN_SWORD.get()));
                        SwordGuard.hold(r.p, true);
                    }
                    case 4 -> {
                        PlayerInteractEvent.RightClickItem use = new PlayerInteractEvent.RightClickItem(r.p, InteractionHand.MAIN_HAND);
                        SectBladeExam.use(use);
                        h.assertTrue(use.isCanceled(), "item use not stopped");
                    }
                }
                SectBladeExam.step(h.getLevel(), s.start + SectBladeExam.WINDUP + SectBladeExam.CUT + 1);
                h.assertTrue(SectLife.data(h.getLevel()).bladeExam == null, "flinch did not end scene: " + kind);
                h.assertTrue(before.equals(SectService.state(r.p)) && standing == SectService.standing(r.p)
                        && r.p.getHealth() == hp, "flinch charged a penalty: " + kind);
            }
        });
    }

    @GameTest(template = "sect_yard", batch = "blade_exam", timeoutTicks = 100)
    public static void ignoredCallStillAllowsDialogueExam(GameTestHelper h) {
        fixture(h, r -> {
            SectState before = SectService.state(r.p);
            SectBladeExam.call(h.getLevel(), r.layout, List.of(r.p));
            SectBladeExam.decline(r.p, r.mentor);
            h.assertTrue(SectLife.data(h.getLevel()).bladeExam == null && before.equals(SectService.state(r.p))
                    && SectBladeExam.fallbackAvailable(r.p, r.mentor), "declined invitation lost the old lesson option");
            SectBladeExam.call(h.getLevel(), r.layout, List.of(r.p));
            SectBladeExam.Scene s = SectLife.data(h.getLevel()).bladeExam;
            Dialogue.Option accept = DialogueLoader.get(r.mentor.dialogue()).nodes().get("blade_exam").options().getFirst();
            h.assertTrue(DialogueService.applyHeadless(r.p, r.mentor, accept), "dialogue acceptance unavailable");
            SectBladeExam.step(h.getLevel(), s.called + SectBladeExam.INVITE);
            h.assertTrue(SectLife.data(h.getLevel()).bladeExam == null && before.equals(SectService.state(r.p)), "ignored call costs state");
            h.assertTrue(SectBladeExam.complete(r.p, r.mentor) && SectService.state(r.p).has(SectStanding.LESSON_TWO), "fallback lost after skip");
        });
    }

    @GameTest(template = "sect_yard", batch = "blade_exam", timeoutTicks = 100)
    public static void reviewWinsCountThreeDistinctPartnersBeforeLessonTwo(GameTestHelper h) {
        fixture(h, r -> {
            SectReview.State review = new SectReview.State(1000, List.of(r.p.getUUID()), 4, r.p.position(), 48);
            List<String> rivals = SectRoster.generation(3).stream().map(SectRoster::key).limit(3).toList();
            for (String key : List.of(rivals.get(0), rivals.get(0), rivals.get(1), rivals.get(2))) {
                SectReview.Bout b = new SectReview.Bout(SectReview.playerKey(r.p), key, 1, 0);
                b.started = 0;
                SectReview.decide(h.getLevel(), review, b, SectReview.playerKey(r.p), "bout", List.of(r.p));
                int merit = SectService.state(r.p).merit();
                SectReview.decide(h.getLevel(), review, b, SectReview.playerKey(r.p), "bout", List.of(r.p));
                h.assertTrue(merit == SectService.state(r.p).merit(), "same result rewarded twice");
            }
            h.assertTrue(SectService.state(r.p).flags().stream().filter(f -> f.startsWith("mentor.partner.")).count() == 3,
                    "review wins did not count distinct partners 3/3");
        });
    }
}
