package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.FoundationForms;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.TrainingDummy;
import io.github.verycooltimo.murim.mastery.MasteryRules;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.mastery.YardRules;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The master's correction (Track C, docs/design/research/hua-main-1810.md section 6, T2): after a round the mentor names one
 * mistake from the fight log in a bubble; fixing it in the next round gives «lesson learned», practice x1.5 for ten minutes,
 * once a sect day. A lost round keeps the walk-up and the drill of the defeat analysis.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectCorrectionGameTests {
    private static final String YARD = "sect_yard";
    private static final String BATCH = "sect_correction";

    private SectCorrectionGameTests() {
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

    private static ServerPlayer player(GameTestHelper h, Flat f) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        Vec3 at = ring(f);
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.setOnGround(true);
        p.setData(ModAttachments.SECT, SectState.NONE.joined());
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(SectService.SIX, TechniqueProgress.learned(1, 6)));
        return p;
    }

    private static SectDisciple npc(GameTestHelper h, Flat f, String key, Vec3 at) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setKeepAwake(true);
        d.setNoAi(true);
        d.setLayout(f);
        d.moveTo(at.x, at.y, at.z, 0, 0);
        h.getLevel().addFreshEntity(d);
        return d;
    }

    /** A bout as the supervisor would have recorded it, put straight into the session. */
    private static SectBouts.Bout bout(GameTestHelper h, ServerPlayer p, SectDisciple foe) {
        long now = h.getLevel().getGameTime();
        SectBouts.Bout b = new SectBouts.Bout(foe.getUUID(), foe.memberKey(), SectBouts.Mode.TRAIN, now - 500, 1.0D);
        b.fightStart = now - 400;
        b.dealt = 4;
        b.taken = 1;
        b.swings = 6;
        b.forms.put(0, 2);
        b.forms.put(1, 2);
        b.forms.put(2, 2);
        b.distance = 3.0D;
        b.samples = 1;
        SectBoutSession.of(p).active.put(p.getUUID(), b);
        return b;
    }

    /** A blow of the partner on the player, as the damage pipeline announces it. */
    private static net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent blow(GameTestHelper h, ServerPlayer p, SectDisciple foe) {
        return new net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent(p,
                new net.neoforged.neoforge.common.damagesource.DamageContainer(h.getLevel().damageSources().mobAttack(foe), 2.0F));
    }

    /** The round of the bad habit: ten swings, three of them landed (the others were dropped before they landed). */
    private static void droppedForms(SectBouts.Bout b) {
        b.swings = 10;
        b.forms.clear();
        b.forms.put(0, 4);
        b.forms.put(1, 3);
        b.forms.put(2, 3);
        b.dealt = 3;
    }

    private static boolean says(net.minecraft.network.chat.Component c, String key) {
        if (c.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t) {
            if (t.getKey().equals(key)) {
                return true;
            }
            for (Object a : t.getArgs()) {
                if (a instanceof net.minecraft.network.chat.Component ac && says(ac, key)) {
                    return true;
                }
            }
        }
        return c.getSiblings().stream().anyMatch(x -> says(x, key));
    }

    private static double practice(ServerPlayer p) {
        TechniqueProgress t = p.getData(ModAttachments.MASTERY).techniques().get(SectService.SIX);
        return t.progress() + t.unprocessed();
    }

    /** What one plain hit on a dummy pays now. */
    private static double plainHit(GameTestHelper h, ServerPlayer p) {
        TrainingDummy d = new TrainingDummy(ModEntities.DUMMY.get(), h.getLevel());
        d.moveTo(p.getX() + 2.0D, p.getY(), p.getZ(), 0, 0);
        double before = practice(p);
        MasteryService.onHit(p, SectService.SIX, d, YardRules.formKey(FoundationForms.Form.THRUST.ordinal()));
        return practice(p) - before;
    }

    // ------------------------------------------------------------------ the mentor names one mistake

    /** A round with a mistake in the log: one word from the mentor, in a bubble, in the next beat after the bow. */
    @GameTest(template = YARD, batch = BATCH + "_thementornamesonemistakeoftheroundinabubble", timeoutTicks = 200)
    public static void theMentorNamesOneMistakeOfTheRoundInABubble(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        Flat f = flat(h);
        ServerPlayer p = player(h, f);
        SectDisciple foe = npc(h, f, "ham_doyun", ring(f).add(1.5, 0, 0));
        SectDisciple mentor = npc(h, f, "gyeong_pil", ring(f).add(0, 0, -2.5));
        SectBouts.Bout b = bout(h, p, foe);
        droppedForms(b);
        SectBouts.ended(p, foe, true);
        h.assertTrue(SectCorrection.named(p) == SectCorrection.Mistake.WHIFF, "the mistake of the log was not named: " + SectCorrection.named(p));
        h.assertTrue(SectBoutSession.of(p).corrections.containsKey(p.getUUID()), "no word queued");
        h.assertTrue(!SectBoutSession.of(p).pending.containsKey(p.getUUID()), "a won round started the defeat analysis");
        p.tickCount = 4;
        SectCorrection.tick(p);
        h.assertTrue(SectBoutSession.of(p).corrections.containsKey(p.getUUID()), "the mentor spoke before the bow was over");
        h.runAfterDelay(90, () -> {
            p.tickCount = 4;
            SectCorrection.tick(p);
            h.assertTrue(!SectBoutSession.of(p).corrections.containsKey(p.getUUID()), "the mentor did not speak");
            h.assertTrue(mentor.bubbleKey().equals("murim.correction.say.whiff"), "the bubble over the mentor: " + mentor.bubbleKey());
            p.tickCount = 14;
            SectCorrection.tick(p);
            h.assertTrue(mentor.bubbleKey().equals("murim.correction.say.whiff"), "said twice");
            h.succeed();
        });
    }

    /** A clean round, or one too short to judge, names nothing: the mentor says nothing and the journal stays empty. */
    @GameTest(template = YARD, batch = BATCH + "_acleanorthinroundnamesnothing", timeoutTicks = 60)
    public static void aCleanOrThinRoundNamesNothing(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        Flat f = flat(h);
        ServerPlayer p = player(h, f);
        SectDisciple foe = npc(h, f, "ham_doyun", ring(f).add(1.5, 0, 0));
        bout(h, p, foe);
        SectBouts.ended(p, foe, true);
        h.assertTrue(SectCorrection.named(p) == null && !SectBoutSession.of(p).corrections.containsKey(p.getUUID()), "a clean round was corrected");
        SectBouts.Bout thin = bout(h, p, foe);
        droppedForms(thin);
        thin.fightStart = h.getLevel().getGameTime() - 30;
        thin.dealt = 0;
        thin.taken = 0;
        SectBouts.ended(p, foe, true);
        h.assertTrue(SectCorrection.named(p) == null && !SectBoutSession.of(p).corrections.containsKey(p.getUUID()), "a round of a second was corrected");
        h.succeed();
    }

    /** A blow taken while a technique winds up, and a swing into the partner's strike, are both read from the live fight. */
    @GameTest(template = YARD, batch = BATCH + "_thelivefightfeedsthelog", timeoutTicks = 60)
    public static void theLiveFightFeedsTheLog(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        Flat f = flat(h);
        ServerPlayer p = player(h, f);
        SectDisciple foe = npc(h, f, "ham_doyun", ring(f).add(1.5, 0, 0));
        SectBouts.Bout b = bout(h, p, foe);
        // a swing begun while the partner is not striking leaves no mark
        b.intoStrikeAt = Long.MIN_VALUE / 2;
        long now = h.getLevel().getGameTime();
        // two swings begun into a strike, each followed by a blow within the window
        for (int i = 0; i < 2; i++) {
            b.intoStrikeAt = now;
            SectCorrection.onIncoming(blow(h, p, foe));
        }
        h.assertTrue(b.punished == 2, "swings into the strike were not counted: " + b.punished);
        // a blow long after a swing is not punished
        b.intoStrikeAt = now - 100;
        SectCorrection.onIncoming(blow(h, p, foe));
        h.assertTrue(b.punished == 2, "an old swing was counted");
        // a blow while a technique winds up
        p.setData(ModAttachments.TECHNIQUE_STATE,
                new io.github.verycooltimo.murim.combat.TechniqueState(SectService.SIX, 3, false, now));
        for (int i = 0; i < 2; i++) {
            SectCorrection.onIncoming(blow(h, p, foe));
        }
        h.assertTrue(b.windupHits == 2, "blows in the wind-up were not counted: " + b.windupHits);
        p.setData(ModAttachments.TECHNIQUE_STATE, io.github.verycooltimo.murim.combat.TechniqueState.IDLE);
        h.assertTrue(SectCorrection.pick(b.log(now, 3)) == SectCorrection.Mistake.WINDUP_HIT, "the log does not name the windup first");
        h.succeed();
    }

    // ------------------------------------------------------------------ lesson learned

    /** Fixing the named mistake in the next round: ten minutes of practice at one and a half, once a sect day. */
    @GameTest(template = YARD, batch = BATCH + "_fixingthemistakegivesalessonthatpaysoneandahalf", timeoutTicks = 200)
    public static void fixingTheMistakeGivesALessonThatPaysOneAndAHalf(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        Flat f = flat(h);
        ServerPlayer p = player(h, f);
        SectDisciple foe = npc(h, f, "ham_doyun", ring(f).add(1.5, 0, 0));
        SectDisciple mentor = npc(h, f, "gyeong_pil", ring(f).add(0, 0, -2.5));
        SectBouts.Bout first = bout(h, p, foe);
        droppedForms(first);
        SectBouts.ended(p, foe, true);
        h.assertTrue(SectCorrection.named(p) == SectCorrection.Mistake.WHIFF, "not named");
        // Prime repeated practice so both measured hits receive the same beginner bonus.
        plainHit(h, p);
        double plain = plainHit(h, p);
        h.assertTrue(plain > 0.0D && SectCorrection.lessonFactor(p) == 1.0D, "a lesson before one was learned");

        // the same mistake again: not fixed
        SectBouts.Bout again = bout(h, p, foe);
        droppedForms(again);
        SectBouts.ended(p, foe, true);
        h.assertTrue(SectCorrection.lessonLeft(p) == 0L, "the same mistake gave a lesson");

        // a round with the mistake gone
        bout(h, p, foe);
        SectBouts.ended(p, foe, true);
        h.assertTrue(SectCorrection.lessonLeft(p) == SectCorrection.LESSON_TICKS, "no lesson: " + SectCorrection.lessonLeft(p));
        h.assertTrue(SectCorrection.named(p) == null, "the fixed mistake is still named");
        h.assertTrue(SectCorrection.lessonFactor(p) == 1.5D, "the lesson does not count one and a half");
        double roomBefore = MasteryService.helpRoom(p, SectService.SIX);
        double learned = plainHit(h, p);
        h.assertTrue(Math.abs(learned - 1.5D * plain) < 1.0E-9, "a hit in the lesson paid " + learned + ", wanted " + 1.5D * plain);
        // the extra half is the mentor's help: it is taken from the ONE help pool of the star
        double taken = roomBefore - MasteryService.helpRoom(p, SectService.SIX);
        h.assertTrue(Math.abs(taken - 0.5D * plain) < 1.0E-9, "the lesson did not take its half from the help pool: " + taken);

        // the mentor says it is good, in a bubble
        h.runAfterDelay(90, () -> {
            p.tickCount = 4;
            SectCorrection.tick(p);
            h.assertTrue(mentor.bubbleKey().startsWith("murim.bubble.lesson."), "no word of praise: " + mentor.bubbleKey());

            // once a sect day: a second mistake named and fixed the same day gives no new lesson
            SectBouts.Bout third = bout(h, p, foe);
            droppedForms(third);
            SectBouts.ended(p, foe, true);
            SectState s = SectProgression.state(p);
            SectProgression.save(p, s.record("correct.until", h.getLevel().getGameTime() - 1L));
            h.assertTrue(SectCorrection.lessonFactor(p) == 1.0D, "the lesson did not end after its ten minutes");
            bout(h, p, foe);
            SectBouts.ended(p, foe, true);
            h.assertTrue(SectCorrection.lessonLeft(p) == 0L, "a second lesson the same day");
            h.succeed();
        });
    }

    /** The extra half of a lesson is help: with the help pool of the star full, a hit in the lesson pays what a plain one does. */
    @GameTest(template = YARD, batch = BATCH + "_thelessonstaysinsidethesharedhelppool", timeoutTicks = 60)
    public static void theLessonStaysInsideTheSharedHelpPool(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        Flat f = flat(h);
        ServerPlayer p = player(h, f);
        SectDisciple foe = npc(h, f, "ham_doyun", ring(f).add(1.5, 0, 0));
        npc(h, f, "gyeong_pil", ring(f).add(0, 0, -2.5));
        droppedForms(bout(h, p, foe));
        SectBouts.ended(p, foe, true);
        // Prime repeated practice so the full-pool comparison changes only lesson help.
        plainHit(h, p);
        double plain = plainHit(h, p);
        bout(h, p, foe);
        SectBouts.ended(p, foe, true);
        h.assertTrue(SectCorrection.lessonFactor(p) == 1.5D, "no lesson learned");
        // a page, a reading and the like have filled the pool of the star
        MasteryService.help(p, SectService.SIX, MasteryRules.HelpSource.PAGE, 1000.0D, false);
        h.assertTrue(MasteryService.helpRoom(p, SectService.SIX) < 1.0E-9, "the pool is not full");
        double paid = plainHit(h, p);
        h.assertTrue(Math.abs(paid - plain) < 1.0E-9, "a lesson gave help over the full pool: " + paid + " against " + plain);
        h.succeed();
    }

    /** A lost round: the defeat analysis keeps the walk-up and the drill, and its reason is the mistake to fix. */
    @GameTest(template = YARD, batch = BATCH + "_alostroundkeepsthedefeatanalysisanditsreasonisthemistake", timeoutTicks = 60)
    public static void aLostRoundKeepsTheDefeatAnalysisAndItsReasonIsTheMistake(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        Flat f = flat(h);
        ServerPlayer p = player(h, f);
        SectDisciple foe = npc(h, f, "ham_doyun", ring(f).add(1.5, 0, 0));
        npc(h, f, "gyeong_pil", ring(f).add(0, 0, -2.5));
        SectBouts.Bout b = bout(h, p, foe);
        b.taken = 6;
        b.dealt = 1;
        SectBouts.ended(p, foe, false);
        h.assertTrue(SectBoutSession.of(p).pending.containsKey(p.getUUID()), "no defeat analysis");
        h.assertTrue(!SectBoutSession.of(p).corrections.containsKey(p.getUUID()), "the mentor would speak twice");
        h.assertTrue(SectCorrection.named(p) == SectCorrection.Mistake.HITS, "the reason of the defeat is not the mistake: " + SectCorrection.named(p));
        h.assertTrue(SectService.state(p).contribution() == 0, "a loss cost merit");
        h.succeed();
    }

    /** A lost round supersedes the word of the round before that still waits for its speaker: one word, and it is the journal's. */
    @GameTest(template = YARD, batch = BATCH + "_alostroundsupersedesawordstillwaiting", timeoutTicks = 60)
    public static void aLostRoundSupersedesAWordStillWaiting(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        Flat f = flat(h);
        ServerPlayer p = player(h, f);
        SectDisciple foe = npc(h, f, "ham_doyun", ring(f).add(1.5, 0, 0));
        npc(h, f, "gyeong_pil", ring(f).add(0, 0, -2.5));
        droppedForms(bout(h, p, foe));
        SectBouts.ended(p, foe, true);
        h.assertTrue(SectBoutSession.of(p).corrections.containsKey(p.getUUID()), "no word queued by the won round");
        SectBouts.Bout lost = bout(h, p, foe);
        lost.taken = 6;
        lost.dealt = 1;
        SectBouts.ended(p, foe, false);
        h.assertTrue(!SectBoutSession.of(p).corrections.containsKey(p.getUUID()), "the old word is still waiting beside the defeat analysis");
        h.assertTrue(SectCorrection.named(p) == SectCorrection.Mistake.HITS, "the journal does not hold the latest reason");
        h.succeed();
    }

    /** Somebody who is not in the sect is not corrected. */
    @GameTest(template = YARD, batch = BATCH + "_nobodyoutsidethesectiscorrected", timeoutTicks = 60)
    public static void nobodyOutsideTheSectIsCorrected(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        Flat f = flat(h);
        ServerPlayer p = player(h, f);
        SectDisciple foe = npc(h, f, "ham_doyun", ring(f).add(1.5, 0, 0));
        SectBouts.Bout b = bout(h, p, foe);
        droppedForms(b);
        p.setData(ModAttachments.SECT, SectState.NONE);
        SectBouts.ended(p, foe, true);
        h.assertTrue(!SectBoutSession.of(p).corrections.containsKey(p.getUUID()), "a stranger was corrected");
        h.succeed();
    }
}
