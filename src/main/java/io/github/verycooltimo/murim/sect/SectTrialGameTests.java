package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.boss.BossRegistry;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Rank-two art gates and real safe teacher bouts (author 21.10). */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectTrialGameTests {
    private static final String YARD = "sect_yard";
    private static final String BATCH = "sect_trial";

    private SectTrialGameTests() {
    }

    private record Flat(Vec3 origin) implements SectLayout {
        @Override public Vec3 at(String zone, double u, double v) {
            return origin.add(u, 0, v);
        }
        @Override public float yaw(double u, double v) {
            return SectLayout.yawOf(u, v);
        }
        @Override public double[] half(String zone) {
            return new double[] {30, 30};
        }
        @Override public double[] local(String zone, Vec3 p) {
            return new double[] {p.x - origin.x, p.z - origin.z};
        }
    }

    private static Flat flat(GameTestHelper h) {
        return new Flat(h.absoluteVec(new Vec3(11.5, 2, 10.5)));
    }

    private static Vec3 ring(Flat f) {
        return f.at("training", SectSchedule.RINGS[0][0], SectSchedule.RINGS[0][1]);
    }

    /** A member of the sect at the third rank, in the ring, with a pool at 30 % of the wall. */
    private static ServerPlayer player(GameTestHelper h, Flat f, int rank) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        Vec3 at = ring(f);
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.setOnGround(true);
        p.setData(ModAttachments.SECT, SectState.NONE.joined());
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(SectService.SIX, TechniqueProgress.learned(1, 6)));
        DantianProfile profile = DantianProfile.INITIAL.withTags("clear", "debug").withAxes(60.0D, 0.8D, 0.4D).withRank(rank);
        p.setData(ModAttachments.PROFILE, profile.withPool(Realm.wall(profile) * 0.3D));
        return p;
    }

    /**
     * SectLife#duplicate removes the person with the larger UUID when another one of the same roster key is within 256 blocks, and
     * the GameTest grid puts every other test's "gyeong_pil" (or a leftover of an earlier one) that close. A trial fixture must not
     * lose its teacher to that: it gets a UUID below any random one, and a later fixture a smaller one than an earlier fixture.
     */
    private static long fixtureSerial;

    private static SectDisciple npc(GameTestHelper h, Flat f, String key, Vec3 at) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setUUID(new java.util.UUID(Long.MIN_VALUE, Long.MAX_VALUE - fixtureSerial++));
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setKeepAwake(true);
        d.setNoAi(true);
        d.setLayout(f);
        d.moveTo(at.x, at.y, at.z, 0, 0);
        h.getLevel().addFreshEntity(d);
        return d;
    }

    private static void difficulty(GameTestHelper h, Difficulty d) {
        h.getLevel().getServer().setDifficulty(d, true);
    }

    /** Runs the body in a world at {@code d} and puts the difficulty back, whatever the body does. */
    private static void in(GameTestHelper h, Difficulty d, Runnable body) {
        Difficulty before = h.getLevel().getDifficulty();
        difficulty(h, d);
        try {
            body.run();
        } finally {
            difficulty(h, before);
        }
    }


    private static void arts(ServerPlayer p, int six, int petal) {
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY
                .with(SectService.SIX, TechniqueProgress.learned(six, 12))
                .with(SectJourney.PETAL, TechniqueProgress.learned(petal, 12)));
    }
    private static SectState proof() {
        return SectState.NONE.joined().with(SectProgression.SECOND_TEST)
                .with("rank2.win.seo_rang").with("rank2.win.seo_gyu").with("rank2.win.seo_ho");
    }
    private static Realm.Blocker gate(ServerPlayer p) {
        return Realm.check(p.getData(ModAttachments.PROFILE), p.getData(ModAttachments.MASTERY).combatStars(),
                SectProgression.secondSparProof(p));
    }
    private static ServerPlayer prepared(GameTestHelper h) {
        ServerPlayer p = player(h, flat(h), Realm.THIRD);
        var profile = p.getData(ModAttachments.PROFILE);
        p.setData(ModAttachments.PROFILE, profile.withPool(Realm.wall(profile)));
        p.setData(ModAttachments.SECT, proof());
        arts(p, 6, 3);
        return p;
    }

    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 40)
    public static void sixHarmoniesMustReachSixStars(GameTestHelper h) {
        ServerPlayer p = prepared(h);
        arts(p, 5, 3);
        h.assertTrue(gate(p) == Realm.Blocker.NO_SECT_SKILLS, "five stars accepted");
        arts(p, 6, 3);
        h.assertTrue(gate(p) == Realm.Blocker.NONE, "six stars rejected without a boss kill");
        h.succeed();
    }

    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 40)
    public static void fallingPetalMustReachThreeStars(GameTestHelper h) {
        ServerPlayer p = prepared(h);
        arts(p, 6, 2);
        h.assertTrue(gate(p) == Realm.Blocker.NO_SECT_SKILLS, "two stars accepted");
        arts(p, 6, 3);
        h.assertTrue(gate(p) == Realm.Blocker.NONE, "three stars rejected");
        h.succeed();
    }

    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 40)
    public static void onlyWinsAgainstThreeDistinctDisciplesCount(GameTestHelper h) {
        ServerPlayer p = prepared(h);
        p.setData(ModAttachments.SECT, SectState.NONE.joined().with(SectProgression.SECOND_TEST));
        Flat f = flat(h);
        SectDisciple a = npc(h, f, "seo_rang", ring(f)), b = npc(h, f, "seo_gyu", ring(f)),
                c = npc(h, f, "seo_ho", ring(f)), teacher = npc(h, f, "gyeong_pil", ring(f));
        SectProgression.sparEnded(p, a, false);
        SectProgression.sparEnded(p, teacher, true);
        h.assertTrue(SectProgression.secondWins(SectService.state(p)) == 0, "loss or teacher counted");
        for (int i = 0; i < 3; i++) SectProgression.sparEnded(p, a, true);
        SectProgression.sparEnded(p, b, true);
        h.assertTrue(gate(p) == Realm.Blocker.NO_SECT_SKILLS, "repeat partner supplied third win");
        SectProgression.sparEnded(p, c, true);
        h.assertTrue(gate(p) == Realm.Blocker.NONE, "third distinct win missing");
        var saved = SectState.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, SectService.state(p)).getOrThrow();
        p.setData(ModAttachments.SECT, SectState.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, saved).getOrThrow());
        h.assertTrue(gate(p) == Realm.Blocker.NONE, "promotion proof lost on reload");
        h.succeed();
    }

    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 40)
    public static void teacherProofIsRequiredAndBossCannotReplaceIt(GameTestHelper h) {
        ServerPlayer p = prepared(h);
        p.setData(ModAttachments.SECT, proof().without(SectProgression.SECOND_TEST));
        p.setData(BossRegistry.BOSS_DEFEATED, true);
        h.assertTrue(gate(p) == Realm.Blocker.NO_SECT_SKILLS, "boss bypassed teacher test");
        h.assertTrue(SectBouts.trialOpen(p), "boss hid teacher test");
        p.setData(ModAttachments.SECT, proof());
        p.setData(BossRegistry.BOSS_DEFEATED, false);
        h.assertTrue(gate(p) == Realm.Blocker.NONE, "boss still required");
        var profile = p.getData(ModAttachments.PROFILE);
        p.setData(ModAttachments.PROFILE, profile.withPool(0));
        h.assertTrue(gate(p) == Realm.Blocker.NOT_AT_WALL, "skills bypassed qi wall");
        h.succeed();
    }

    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 40)
    public static void teacherTestIsOfferedInEveryDifficulty(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        Flat f = flat(h);
        ServerPlayer p = player(h, f, Realm.THIRD);
        SectDisciple teacher = npc(h, f, "gyeong_pil", ring(f).add(1.5, 0, 0));
        for (Difficulty d : Difficulty.values()) in(h, d, () -> {
            h.assertTrue(SectBouts.canTrial(p, teacher), "teacher test missing in " + d);
            h.assertTrue(SectProgression.matches(p, teacher, "trial"), "dialogue option missing");
        });
        p.setData(ModAttachments.SECT, proof());
        h.assertTrue(!SectBouts.trialOpen(p), "completed test offered again");
        p.setData(ModAttachments.SECT, SectState.NONE.joined());
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withRank(Realm.SECOND));
        h.assertTrue(!SectBouts.trialOpen(p), "test offered after promotion");
        h.succeed();
    }

    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 40)
    public static void tabletAndMentorShareEveryCheckmark(GameTestHelper h) {
        ServerPlayer p = prepared(h);
        p.setData(ModAttachments.SECT, proof().with(SectStanding.LESSON_ONE)
                .with(SectService.LESSON_SPAR).with(SectService.SPAR_CLEAN));
        // The checklist is the current goal only after the newly introduced footwork lesson.
        p.setData(ModAttachments.SECT, SectService.state(p).with("lesson.seven_stars"));
        for (var form : io.github.verycooltimo.murim.technique.Styles.SEVEN_STARS.forms())
            p.setData(ModAttachments.MASTERY, p.getData(ModAttachments.MASTERY)
                    .with(form, TechniqueProgress.learned(0, 12)));
        p.getPersistentData().putString(SectGoalTalk.LAST, "rank_two");
        var expected = SectProgression.secondChecklist(p);
        h.assertTrue(SectTablet.collect(p).goalHint().equals(expected), "tablet omitted rank-two checklist");
        h.assertTrue(SectGoalTalk.reply(p, null).equals(expected), "mentor checklist differs");
        h.assertTrue(SectProgression.secondSparProof(p) && Realm.secondArtsMet(p.getData(ModAttachments.MASTERY).combatStars()),
                "completed checklist still blocks rank-two requirements");
        var content = (net.minecraft.network.chat.contents.TranslatableContents) expected.getContents();
        Object[] args = content.getArgs();
        h.assertTrue("✔".equals(args[0]) && "✔".equals(args[1]) && "✔".equals(args[2]) && "✔".equals(args[4]),
                "completed parts are not checked");
        arts(p, 5, 2);
        p.setData(ModAttachments.SECT, SectState.NONE.joined());
        args = ((net.minecraft.network.chat.contents.TranslatableContents) SectProgression.secondChecklist(p).getContents()).getArgs();
        h.assertTrue("✘".equals(args[0]) && "✘".equals(args[1]) && "✘".equals(args[2]) && "✘".equals(args[4]),
                "missing parts are not crossed");
        h.succeed();
    }

    private static void fighting(GameTestHelper h, java.util.function.BiConsumer<ServerPlayer, SectDisciple> body) {
        fighting(h, body, (p, teacher) -> {});
    }

    private static void fighting(GameTestHelper h, java.util.function.BiConsumer<ServerPlayer, SectDisciple> body,
                                 java.util.function.BiConsumer<ServerPlayer, SectDisciple> eachFightTick) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        Flat f = flat(h);
        ServerPlayer p = player(h, f, Realm.THIRD);
        h.getLevel().addNewPlayer(p);
        SectDisciple teacher = npc(h, f, "gyeong_pil", ring(f).add(1.5, 0, 0));
        h.assertTrue(SectBouts.begin(p, teacher, SectBouts.Mode.TRIAL), "test refused");
        boolean[] done = {false};
        h.onEachTick(() -> {
            try {
                // SectBouts#tick normally runs from PlayerTickEvent.Post. This synthetic
                // player's connection is not ticked by PlayerList; drive supervision here.
                SectBouts.Bout bout = SectBouts.active(p);
                if (bout != null) SectBouts.supervise(p, bout);
                if (!done[0] && teacher.spar() == SectDisciple.Spar.FIGHT) {
                    done[0] = true;
                    body.accept(p, teacher);
                }
                if (done[0]) eachFightTick.accept(p, teacher);
            } catch (RuntimeException e) {
                h.getLevel().removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                throw e;
            }
        });
    }

    private static void mentorWon(GameTestHelper h, SectDisciple teacher) {
        try {
            var field = SectDisciple.class.getDeclaredField("partnerWon");
            field.setAccessible(true);
            h.assertTrue(!field.getBoolean(teacher), "mentor lost the skills test");
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private static void finish(GameTestHelper h, ServerPlayer p) {
        h.getLevel().removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        h.succeed();
    }

    // SectLife#duplicate removes repeated roster keys within 256 blocks every 200 ticks.
    // Give every ticking mentor bout its own batch, including the short ones: duplicate
    // checks are staggered by entity id and can run during the countdown or bow.
    @GameTest(template = YARD, batch = BATCH + "_survival", timeoutTicks = 900)
    public static void thirtySecondsPassesButMentorStillWins(GameTestHelper h) {
        long[] fightStart = {-1L};
        boolean[] checkedBefore = {false};
        fighting(h, (p, teacher) -> fightStart[0] = SectBouts.active(p).fightStart, (p, teacher) -> {
            long elapsed = p.level().getGameTime() - fightStart[0];
            if (elapsed == SectBouts.TEACHER_TICKS - 1) {
                h.assertTrue(teacher.spar() == SectDisciple.Spar.FIGHT, "fight ended before thirty seconds");
                h.assertTrue(!SectService.state(p).has(SectProgression.SECOND_TEST), "test passed before thirty seconds");
                checkedBefore[0] = true;
            }
            if (elapsed == SectBouts.TEACHER_TICKS) {
                h.assertTrue(checkedBefore[0], "pre-timeout boundary was not checked");
                h.assertTrue(SectService.state(p).has(SectProgression.SECOND_TEST), "survival not credited");
                h.assertTrue(teacher.spar() == SectDisciple.Spar.BOW_OUT && SectBouts.active(p) == null,
                        "survival did not end the teacher bout");
                mentorWon(h, teacher);
                h.assertTrue(!p.getData(BossRegistry.BOSS_DEFEATED), "test granted optional boss reward");
                h.assertTrue(!SectBoutSession.of(p).pending.containsKey(p.getUUID()) && SectDefeat.run(p) == null,
                        "teacher victory assigned a defeat drill");
                finish(h, p);
            }
        });
    }

    @GameTest(template = YARD, batch = BATCH + "_clean_hits", timeoutTicks = 240)
    public static void threeCleanHitsPassBeforeTimeLimit(GameTestHelper h) {
        fighting(h, (p, teacher) -> {
            for (int i = 0; i < SectBouts.TEACHER_HITS; i++) {
                try {
                    var field = SectDisciple.class.getDeclaredField("lastSwing");
                    field.setAccessible(true);
                    field.setLong(teacher, p.level().getGameTime());
                } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                teacher.invulnerableTime = 0;
                teacher.hurt(h.getLevel().damageSources().playerAttack(p), 1.0F);
                h.assertTrue(SectService.state(p).has(SectProgression.SECOND_TEST) == (i == 2), "wrong clean-hit threshold");
            }
            h.assertTrue(teacher.spar() == SectDisciple.Spar.BOW_OUT, "third clean hit did not end test");
            mentorWon(h, teacher);
            h.assertTrue(!p.getData(BossRegistry.BOSS_DEFEATED), "clean hits granted boss trophy");
            finish(h, p);
        });
    }

    @GameTest(template = YARD, batch = BATCH + "_ordinary_hits", timeoutTicks = 240)
    public static void ordinaryHitsCannotDefeatTeacherOrPassTest(GameTestHelper h) {
        fighting(h, (p, teacher) -> {
            // Attendance grants merit on the first bout of the day; isolate the trial's penalty policy.
            SectAttendance.record(p, SectAttendance.Activity.LESSON);
            int merit = SectService.state(p).merit();
            double pool = p.getData(ModAttachments.PROFILE).pool();
            teacher.invulnerableTime = 0;
            teacher.hurt(h.getLevel().damageSources().playerAttack(p), 10000.0F);
            h.assertTrue(teacher.spar() == SectDisciple.Spar.BOW_OUT, "health floor did not finish the teacher bout");
            mentorWon(h, teacher);
            h.assertTrue(!SectService.state(p).has(SectProgression.SECOND_TEST), "ordinary damage passed test");
            h.assertTrue(SectService.state(p).merit() == merit && !SectService.state(p).has(SectProgression.REPAIR), "test penalised disciple");
            h.assertTrue(p.getData(ModAttachments.PROFILE).pool() == pool, "test changed qi");
            h.assertTrue(!SectBoutSession.of(p).pending.containsKey(p.getUUID()) && SectDefeat.run(p) == null, "failed test assigned drill");
            finish(h, p);
        });
    }

    @GameTest(template = YARD, batch = BATCH + "_leave_ring", timeoutTicks = 240)
    public static void leavingTeacherRingGivesNoProofOrPenalty(GameTestHelper h) {
        fighting(h, (p, teacher) -> {
            SectBouts.Bout b = SectBouts.active(p);
            p.moveTo(p.getX() + SectBouts.OUT_OF_RING + 2, p.getY(), p.getZ(), 0, 0);
            b.outside = 19;
            SectBouts.supervise(p, b);
            h.assertTrue(teacher.spar() == SectDisciple.Spar.BOW_OUT, "leaving ring did not end test");
            mentorWon(h, teacher);
            h.assertTrue(!SectService.state(p).has(SectProgression.SECOND_TEST), "leaving ring granted proof");
            h.assertTrue(!SectBoutSession.of(p).pending.containsKey(p.getUUID()) && SectDefeat.run(p) == null,
                    "leaving teacher test assigned a drill");
            finish(h, p);
        });
    }

}
