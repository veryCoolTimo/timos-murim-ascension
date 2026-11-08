package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.quest.QuestLoader;
import io.github.verycooltimo.murim.quest.Quests;
import io.github.verycooltimo.murim.quest.def.QuestDef;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.function.BiConsumer;

/**
 * Spars with a condition (quest step {@code trial}, docs/design/45-quest-format.md): who offers them, that the bout begins and
 * is judged by its rule, that a win which keeps the rule pays once (merit, a share of the next star) and nothing else does.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectTrialsGameTests {
    private static final String YARD = "sect_yard";
    /** The merit of the trials (murim_quests/trials.json); the ordinary practice credit of a first bout of the day comes on top. */
    private static final int REWARD_CLEAN = 3, REWARD_SIX = 2, REWARD_PARRY = 4;

    private SectTrialsGameTests() { }

    private record Flat(Vec3 origin) implements SectLayout {
        @Override public Vec3 at(String zone, double u, double v) { return origin.add(u, 0, v); }
        @Override public float yaw(double u, double v) { return SectLayout.yawOf(u, v); }
        @Override public double[] half(String zone) { return new double[] {30, 30}; }
        @Override public double[] local(String zone, Vec3 p) { return new double[] {p.x - origin.x, p.z - origin.z}; }
    }

    private static Flat flat(GameTestHelper h) {
        return new Flat(h.absoluteVec(new Vec3(11.5, 2, 10.5)));
    }

    private static Vec3 ring(Flat f) {
        return f.at("training", SectSchedule.RINGS[0][0], SectSchedule.RINGS[0][1]);
    }

    /** A member who came to the sect five days ago, knows the Six Elements Sword and stands in the ring. */
    private static ServerPlayer player(GameTestHelper h, Vec3 at) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.setOnGround(true);
        try {
            java.lang.reflect.Field field = ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");
            field.setAccessible(true);
            field.setInt(p, 0);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        long today = SectSchedule.day(h.getLevel().getDayTime());
        p.setData(ModAttachments.SECT, SectState.NONE.joined().record("arrival.day", today - 5));
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(SectService.SIX, TechniqueProgress.learned(1, 12)));
        return p;
    }

    private static SectDisciple npc(GameTestHelper h, Flat f, String key, Vec3 at) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setUUID(new java.util.UUID(Long.MIN_VALUE, Long.MAX_VALUE - (serial++)));
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setKeepAwake(true);
        d.neverGuards = true;
        d.setNoAi(true);
        d.setLayout(f);
        d.moveTo(at.x, at.y, at.z, 0, 0);
        h.getLevel().addFreshEntity(d);
        return d;
    }

    private static long serial;

    /**
     * Sets a training-hours day on which this person is not on watch (SectRota moves the second generation's posts from day to day,
     * and a man on watch is not free for a bout). Call before the player is made: the days in the sect are counted from now.
     */
    private static void freeDay(GameTestHelper h, String key) {
        SectRoster m = SectRoster.of(key).orElseThrow();
        long base = Math.floorDiv(h.getLevel().getDayTime(), 24000L) * 24000L;
        for (int d = 0; d < 16; d++) {
            long t = base + d * 24000L + SectSchedule.Period.TRAINING.start() + SectStagger.LAG_MAX + 20;
            if (!SectRota.onDuty(m, t) && !SectRota.atHallDoor(m, t)) {
                h.getLevel().setDayTime(t);
                return;
            }
        }
        throw new IllegalStateException(key + " is on watch every day");
    }

    private static void blow(GameTestHelper h, ServerPlayer p, SectDisciple d) {
        d.invulnerableTime = 0;
        d.hurt(h.getLevel().damageSources().playerAttack(p), 3.0F);
    }

    /**
     * Begins the trial of {@code trial} with the senior {@code key} and, on the first tick of the fight, runs {@code during}, wins the
     * bout (or loses it) and runs {@code after}. Everything goes through the real offer, the real begin and the real end of the bout.
     */
    private static void run(GameTestHelper h, String key, String trial, boolean win, BiConsumer<ServerPlayer, SectDisciple> during,
                            BiConsumer<ServerPlayer, SectDisciple> after) {
        run(h, key, trial, win, p -> { }, during, after);
    }

    private static void run(GameTestHelper h, String key, String trial, boolean win, java.util.function.Consumer<ServerPlayer> setup,
                            BiConsumer<ServerPlayer, SectDisciple> during, BiConsumer<ServerPlayer, SectDisciple> after) {
        freeDay(h, key);
        Flat f = flat(h);
        ServerPlayer p = player(h, ring(f));
        setup.accept(p);
        h.getLevel().addNewPlayer(p);
        SectDisciple d = npc(h, f, key, ring(f).add(1.5, 0, 0));
        h.assertTrue(SectTrials.offered(p, d, trial), "the trial " + trial + " is not offered by " + key);
        h.assertTrue(SectTrials.start(p, d, trial), "the trial did not begin: " + SectTrials.refusal(p, d, trial));
        h.assertTrue(SectBouts.active(p) != null && SectBouts.active(p).mode == SectBouts.Mode.RULE
                && trial.equals(SectBouts.active(p).trial), "the bout is not a tracked rule bout");
        boolean[] done = {false};
        h.onEachTick(() -> {
            // a test player is ticked by nothing: the bout supervisor (the count, the time of a trial) runs in doTick
            if (!done[0]) p.doTick();
            SectBouts.Bout b = SectBouts.active(p);
            if (done[0] || d.spar() != SectDisciple.Spar.FIGHT || b == null || b.fightStart < 0) {
                return;
            }
            done[0] = true;
            during.accept(p, d);
            if (win) {
                for (int i = 0; i < 60 && d.spar() == SectDisciple.Spar.FIGHT; i++) {
                    if ("six_only".equals(trial)) {
                        // Use the real swing entry point before damage, as the client does for a plain form.
                        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/entity/Entity.java#tickCount;
                        // /tmp/mr-ref-1121/neoforge-src/net/neoforged/neoforge/attachment/IAttachmentHolder.java.
                        io.github.verycooltimo.murim.combat.FoundationService.onSwing(p, 0);
                        h.assertTrue(p.getData(ModAttachments.FOUNDATION_SWING)[1] == p.tickCount,
                                "plain form swing was not accepted");
                    }
                    blow(h, p, d);
                }
                h.assertTrue(d.spar() != SectDisciple.Spar.FIGHT, "the bout never ended");
            } else {
                d.endSpar(false);
            }
            after.accept(p, d);
            h.getLevel().removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            h.succeed();
        });
    }

    private static boolean won(ServerPlayer p, String trial) {
        return Quests.has(p, SectTrials.wonFlag("trials", trial));
    }

    private static long merit(ServerPlayer p) {
        return SectService.state(p).contribution();
    }

    // ------------------------------------------------------------------ the data and the offers

    @GameTest(template = YARD, batch = "sect_trials_data", timeoutTicks = 40)
    public static void fourTrialsAreLoadedAndEachHasItsSenior(GameTestHelper h) {
        for (String id : java.util.List.of("clean", "six_only", "parry", "swift")) {
            var t = QuestLoader.trial(id);
            h.assertTrue(t != null, "the trial " + id + " is not loaded");
            h.assertTrue(SectRoster.of(t.step().npc()).isPresent() && QuestDef.TRIAL_RULES.contains(t.step().rule()), id + " refers to nothing");
        }
        h.assertTrue(QuestLoader.trials().size() >= 4, "trials: " + QuestLoader.trials().size());
        h.succeed();
    }

    @GameTest(template = YARD, batch = "sect_trials_data", timeoutTicks = 40)
    public static void aTrialIsOfferedByItsSeniorOnlyAfterTheFirstDays(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        Flat f = flat(h);
        ServerPlayer p = player(h, ring(f));
        SectDisciple rang = npc(h, f, "seo_rang", ring(f).add(1.5, 0, 0));
        SectDisciple gyu = npc(h, f, "seo_gyu", ring(f).add(-1.5, 0, 0));
        h.assertTrue(SectTrials.offered(p, rang, "clean"), "Seo Rang does not offer the clean bout");
        h.assertTrue(!SectTrials.offered(p, gyu, "clean"), "Seo Gyu offers the trial of Seo Rang");
        h.assertTrue(SectTrials.offeredAny(p, rang) && SectTrials.offeredAny(p, gyu), "a senior offers nothing");
        h.assertTrue(SectSocial.matches(p, rang, "quest:trial:clean") && SectSocial.matches(p, rang, "quest:trial_any"),
                "the dialogue condition does not see the offer");
        h.assertTrue(!SectSocial.matches(p, gyu, "quest:trial:clean"), "the dialogue condition offers another man's trial");
        // the third day: the parry trial of Seo Gyu is not open yet to a man of the second day
        long today = SectSchedule.day(h.getLevel().getDayTime());
        p.setData(ModAttachments.SECT, SectState.NONE.joined().record("arrival.day", today - 2));
        h.assertTrue(SectTrials.offered(p, rang, "clean") && !SectTrials.offered(p, gyu, "parry"), "the gate of the days does not hold");
        // not a member: nothing
        p.setData(ModAttachments.SECT, SectState.NONE);
        h.assertTrue(!SectTrials.offeredAny(p, rang), "an outsider is offered a trial");
        h.succeed();
    }

    @GameTest(template = YARD, batch = "sect_trials_data", timeoutTicks = 40)
    public static void theRuleIsJudgedByItsCounters(GameTestHelper h) {
        var clean = QuestLoader.trial("clean").step();
        var six = QuestLoader.trial("six_only").step();
        var parry = QuestLoader.trial("parry").step();
        var swift = QuestLoader.trial("swift").step();
        h.assertTrue(SectTrials.kept(clean, 0, 0, false, 500) && !SectTrials.kept(clean, 1, 0, false, 500), "no_hits");
        h.assertTrue(SectTrials.kept(six, 3, 0, false, 500) && !SectTrials.kept(six, 0, 0, true, 500), "six_only");
        h.assertTrue(SectTrials.kept(parry, 4, 1, false, 500) && !SectTrials.kept(parry, 0, 0, false, 500), "parry");
        int limit = swift.value() * 20;
        h.assertTrue(SectTrials.kept(swift, 9, 0, false, limit) && !SectTrials.kept(swift, 0, 0, false, limit + 1)
                && !SectTrials.kept(swift, 0, 0, false, -1), "time");
        h.succeed();
    }

    // ------------------------------------------------------------------ the bout

    @GameTest(template = YARD, batch = "sect_trials_clean", timeoutTicks = 400)
    public static void cleanWinPaysMeritAndAShareOfAStarOnce(GameTestHelper h) {
        double[] before = new double[2];
        run(h, "seo_rang", "clean", true, (p, d) -> {
            before[0] = merit(p);
            before[1] = p.getData(ModAttachments.MASTERY).progress(SectService.SIX).progress();
        }, (p, d) -> {
            h.assertTrue(won(p, "clean"), "a clean win did not mark the trial");
            h.assertTrue(merit(p) - before[0] >= REWARD_CLEAN, "a clean win paid no merit: " + (merit(p) - before[0]));
            h.assertTrue(p.getData(ModAttachments.MASTERY).progress(SectService.SIX).progress() > before[1], "no share of the star was paid");
            // the second win pays nothing: the trial is not offered again and the reward flag holds
            h.assertTrue(!SectTrials.offered(p, d, "clean"), "a won trial is offered again");
        });
    }

    @GameTest(template = YARD, batch = "sect_trials_touched", timeoutTicks = 400)
    public static void aTouchedWinIsNotAPassedTrial(GameTestHelper h) {
        long[] before = new long[1];
        run(h, "seo_rang", "clean", true, (p, d) -> {
            before[0] = merit(p);
            p.invulnerableTime = 0;
            p.hurt(h.getLevel().damageSources().mobAttack(d), 1.0F);
            h.assertTrue(SectBouts.active(p).taken == 1, "the touch was not counted: " + SectBouts.active(p).taken);
        }, (p, d) -> {
            h.assertTrue(!won(p, "clean"), "a touched win passed the trial");
            h.assertTrue(merit(p) - before[0] < REWARD_CLEAN, "a failed trial paid its reward: " + (merit(p) - before[0]));
            h.assertTrue(SectTrials.offered(p, d, "clean"), "a failed trial cannot be tried again");
        });
    }

    @GameTest(template = YARD, batch = "sect_trials_lost", timeoutTicks = 400)
    public static void aLostBoutCostsNothingAndCanBeTriedAgain(GameTestHelper h) {
        long[] before = new long[1];
        run(h, "seo_rang", "clean", false, (p, d) -> before[0] = merit(p), (p, d) -> {
            h.assertTrue(!won(p, "clean") && merit(p) - before[0] < REWARD_CLEAN, "a lost bout paid the reward");
            h.assertTrue(SectTrials.offered(p, d, "clean"), "a lost bout closed the trial");
        });
    }

    /** The Six Elements Sword as the foundation, a sword in hand, an awakened body: what the trial asks before it begins. */
    private static void sixInHand(ServerPlayer p) {
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.item.ItemStack(io.github.verycooltimo.murim.registry.ModItems.HUASHAN_SWORD.get()));
        p.setData(ModAttachments.LOADOUT, p.getData(ModAttachments.LOADOUT).withFoundation(java.util.Optional.of(SectService.SIX)));
        var profile = io.github.verycooltimo.murim.profile.DantianProfile.INITIAL.withTags("clear", "debug").withAxes(60.0D, 0.8D, 0.4D)
                .withRank(io.github.verycooltimo.murim.cultivation.Realm.THIRD);
        p.setData(ModAttachments.PROFILE, profile.withPool(io.github.verycooltimo.murim.cultivation.Realm.wall(profile) * 0.3D));
    }

    @GameTest(template = YARD, batch = "sect_trials_six", timeoutTicks = 100)
    public static void sixOnlyNeedsTheSwordAndTheArtBeforeItBegins(GameTestHelper h) {
        freeDay(h, "mok_hayeon");
        Flat f = flat(h);
        ServerPlayer p = player(h, ring(f));
        SectDisciple d = npc(h, f, "mok_hayeon", ring(f).add(1.5, 0, 0));
        h.assertTrue("murim.trial.six_needed".equals(SectTrials.refusal(p, d, "six_only")),
                "the trial begins without the Six Elements Sword in hand: " + SectTrials.refusal(p, d, "six_only"));
        sixInHand(p);
        h.assertTrue(SectTrials.refusal(p, d, "six_only") == null, "the trial is refused with the sword and the art: " + SectTrials.refusal(p, d, "six_only"));
        h.succeed();
    }

    @GameTest(template = YARD, batch = "sect_trials_six2", timeoutTicks = 400)
    public static void sixOnlyPassesWithPlainFormsAndFailsAfterATechnique(GameTestHelper h) {
        long[] before = new long[1];
        run(h, "mok_hayeon", "six_only", true, SectTrialsGameTests::sixInHand, (p, d) -> before[0] = merit(p), (p, d) -> {
            h.assertTrue(won(p, "six_only") && merit(p) - before[0] >= REWARD_SIX, "plain forms and a win did not pass the trial");
        });
    }

    @GameTest(template = YARD, batch = "sect_trials_six3", timeoutTicks = 400)
    public static void sixOnlyFailsAfterATechnique(GameTestHelper h) {
        long[] before = new long[1];
        run(h, "mok_hayeon", "six_only", true, SectTrialsGameTests::sixInHand, (p, d) -> {
            before[0] = merit(p);
            SectBouts.onCast(p);
            h.assertTrue(SectBouts.active(p).cast, "a technique was not counted");
        }, (p, d) -> {
            h.assertTrue(!won(p, "six_only") && merit(p) - before[0] < REWARD_SIX, "a win after a technique passed the Six Elements trial");
        });
    }

    @GameTest(template = YARD, batch = "sect_trials_six_unlinked", timeoutTicks = 400)
    public static void sixOnlyFailsAfterAnUnlinkedVanillaHit(GameTestHelper h) {
        long[] before = new long[1];
        run(h, "mok_hayeon", "six_only", true, SectTrialsGameTests::sixInHand, (p, d) -> {
            before[0] = merit(p);
            blow(h, p, d);
            h.assertTrue(SectBouts.active(p).cast, "unlinked vanilla damage did not break the rule");
        }, (p, d) -> {
            h.assertTrue(!won(p, "six_only") && merit(p) - before[0] < REWARD_SIX,
                    "later valid forms erased an unlinked hit");
        });
    }

    @GameTest(template = YARD, batch = "sect_trials_parry", timeoutTicks = 400)
    public static void parryTrialNeedsAParriedStrike(GameTestHelper h) {
        long[] before = new long[1];
        run(h, "seo_gyu", "parry", true, (p, d) -> before[0] = merit(p), (p, d) -> {
            h.assertTrue(!won(p, "parry"), "a win without a parry passed the parry trial");
            h.assertTrue(merit(p) - before[0] < REWARD_PARRY, "a failed parry trial paid its reward: " + (merit(p) - before[0]));
        });
    }

    @GameTest(template = YARD, batch = "sect_trials_parry2", timeoutTicks = 400)
    public static void parryTrialPassesWithAParriedStrike(GameTestHelper h) {
        long[] before = new long[1];
        run(h, "seo_gyu", "parry", true, (p, d) -> {
            before[0] = merit(p);
            net.minecraft.world.entity.animal.Cow cow = h.spawn(net.minecraft.world.entity.EntityType.COW, new Vec3(2.5D, 2.0D, 2.5D));
            SectBouts.onParry(p, cow);
            h.assertTrue(SectBouts.active(p).parries == 0, "a parry of a stranger counted for the bout");
            SectBouts.onParry(p, d);
            h.assertTrue(SectBouts.active(p).parries == 1, "the partner's parried strike was not counted: " + SectBouts.active(p).parries);
        }, (p, d) -> {
            h.assertTrue(won(p, "parry"), "a parried strike and a win did not pass the trial");
            h.assertTrue(merit(p) - before[0] >= REWARD_PARRY, "no merit for the parry trial");
        });
    }

    @GameTest(template = YARD, batch = "sect_trials_swift", timeoutTicks = 1500)
    public static void swiftTrialIsLostWhenTheTimeIsOut(GameTestHelper h) {
        freeDay(h, "seo_ho");
        Flat f = flat(h);
        ServerPlayer p = player(h, ring(f));
        h.getLevel().addNewPlayer(p);
        SectDisciple d = npc(h, f, "seo_ho", ring(f).add(1.5, 0, 0));
        h.assertTrue(SectTrials.start(p, d, "swift"), "the swift trial did not begin");
        long[] fightAt = {-1};
        h.onEachTick(() -> {
            if (h.getLevel().getPlayers(x -> x == p).isEmpty()) return;
            p.doTick();
            SectBouts.Bout b = SectBouts.active(p);
            if (b == null) {
                return;
            }
            if (b.fightStart >= 0 && fightAt[0] < 0) {
                fightAt[0] = b.fightStart;
                h.assertTrue(!SectTrials.expired(b, b.fightStart + 40 * 20), "expired at the limit itself");
                h.assertTrue(SectTrials.expired(b, b.fightStart + 40 * 20 + 1), "not expired past the limit");
            }
        });
        // the supervisor ends the bout past the limit as a lost one and nothing is paid
        h.succeedWhen(() -> {
            h.assertTrue(fightAt[0] >= 0 && h.getLevel().getGameTime() - fightAt[0] > 40 * 20 + 2, "waiting for the time to run out");
            h.assertTrue(d.spar() != SectDisciple.Spar.FIGHT, "the bout went on past the time limit: " + d.spar());
            h.assertTrue(!won(p, "swift"), "a lost trial was paid");
            h.getLevel().removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        });
    }
    @GameTest(template = YARD, batch = "sect_presence_rule", timeoutTicks = 400)
    public static void acceptedRuleBreakChangesMoteAndSpeaksOnce(GameTestHelper h) {
        run(h, "seo_rang", "clean", false, (p, d) -> {
            var b = SectBouts.active(p);
            h.assertTrue(SectTrials.marker(p).id().equals("trial_intact"), "accepted rule has no intact mote");
            p.invulnerableTime = 0;
            p.hurt(h.getLevel().damageSources().mobAttack(d), 1.0F);
            h.assertTrue(SectGuide.spots(p).getFirst().id().equals("trial_broken"), "server event did not flip mote");
            h.assertTrue(d.bubbleKey().equals("murim.presence.trial.touched"), "no examiner line");
            d.markBubble("presence_test_sentinel");
            p.invulnerableTime = 0;
            p.hurt(h.getLevel().damageSources().mobAttack(d), 1.0F);
            h.assertTrue(d.bubbleKey().equals("presence_test_sentinel"), "violation spoke twice");
        }, (p, d) -> h.assertTrue(SectTrials.marker(p) == null, "finished trial retained its mote"));
    }

}
