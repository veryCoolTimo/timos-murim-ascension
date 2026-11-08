package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.seal.PenanceEnforcement;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static io.github.verycooltimo.murim.sect.SectPlaytestRig.empty;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.npc;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.player;

/**
 * Bug 1 of the 19.10 playtest (docs/adr/0013): a blow on the instructor of an open lesson is practice, never an assault.
 * Every test has its own batch (they spawn named people: two of one key within 256 blocks are folded into one).
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectLessonSanctionGameTests {

    private static final String YARD = "sect_yard";

    private SectLessonSanctionGameTests() {
    }

    /**
     * «Я его бью, и мне пишут: вы нарушили закон секты, и меня отправляют в тюрьму. Потом просят извиниться перед ним.» The
     * first lesson sends the pupil to use the Six on a partner; the mentor takes the blows as a practice bout.
     */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "playtest1019_lesson_mentor")
    public static void blowsOnTheMentorInTheFirstLessonAreAPracticeBoutNotAnAssault(GameTestHelper h) {
        SectHierarchyGameTests.Only layout = empty(h);
        ServerPlayer p = player(h, 8, 8, SectState.NONE.joined().contribute(20).with("lesson.six"));
        SectDisciple mentor = npc(h, layout, "gyeong_pil", 10, 8, false);
        for (int i = 0; i < 4; i++) {
            mentor.hurt(p.damageSources().playerAttack(p), 1.0F);
        }
        SectState s = p.getData(ModAttachments.SECT);
        h.assertTrue(!PenanceEnforcement.active(p), "blows on the teacher in his lesson started a warrant (rampage)");
        h.assertTrue(!s.has(SectProgression.REPAIR) && !s.has("discipline.assault"), "the lesson blow was written down as an assault: " + s.flags());
        h.assertTrue(s.merit() == 20, "merit was taken for a lesson blow: " + s.merit());
        h.assertTrue(!p.getPersistentData().contains("murim_sect_offences"), "an offence is on record");
        h.assertTrue(mentor.spar() != SectDisciple.Spar.NONE && p.getUUID().equals(mentor.partner()),
                "the teacher did not take the blows as a practice bout: " + mentor.spar());
        h.succeed();
    }

    /** The sanction ends with the lesson: the same blow after the first lesson is handed in is still an assault. */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "playtest1019_lesson_done")
    public static void blowsOnTheMentorAfterTheLessonAreStillAnAssault(GameTestHelper h) {
        SectHierarchyGameTests.Only layout = empty(h);
        ServerPlayer p = player(h, 8, 8, SectState.NONE.joined().contribute(20).with("lesson.six").with(SectStanding.LESSON_ONE));
        SectDisciple mentor = npc(h, layout, "gyeong_pil", 10, 8, false);
        mentor.hurt(p.damageSources().playerAttack(p), 1.0F);
        // Everybody forgives one light blow; the second within five minutes is war, not an offence (docs/design/50-sect-war.md).
        h.assertTrue(!SectWar.atWar(p), "a single light blow on the mentor started a war");
        mentor.hurt(p.damageSources().playerAttack(p), 1.0F);
        h.assertTrue(SectWar.atWar(p), "the sanction outlived the lesson: no war for a plain blow on the mentor");
        SectWar.data(h.getLevel()).reset();
        h.assertTrue(mentor.spar() == SectDisciple.Spar.NONE, "the mentor fought a player who had no lesson");
        h.succeed();
    }

    /** The second lesson (three clean strikes): a senior's challenge is not counted as an assault either. */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "playtest1019_lesson_senior")
    public static void aChallengeToTheSeniorInTheSecondLessonLeavesNoAssaultOnRecord(GameTestHelper h) {
        SectHierarchyGameTests.Only layout = empty(h);
        ServerPlayer p = player(h, 8, 8, SectState.NONE.joined().contribute(20).with(SectStanding.LESSON_ONE).with(SectService.LESSON_SPAR));
        SectDisciple senior = npc(h, layout, "seo_rang", 10, 8, false);
        senior.hurt(p.damageSources().playerAttack(p), 1.0F);
        h.assertTrue(senior.spar() != SectDisciple.Spar.NONE && p.getUUID().equals(senior.partner()), "the senior did not answer the blow with a bout");
        var hits = p.getPersistentData().getCompound("murim_penance_assaults").getList("hits", net.minecraft.nbt.Tag.TAG_COMPOUND);
        h.assertTrue(hits.isEmpty(), "the opening blow of a lesson bout was counted as an assault: " + hits.size() + " on record");
        h.assertTrue(!p.getData(ModAttachments.SECT).has(SectProgression.REPAIR), "the challenge cost an offence");
        h.succeed();
    }

    /** The strikes drill that the mentor set after a lost bout: «twenty hits on any target» covers the teacher who stands by. */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "playtest1019_lesson_drill")
    public static void blowsOnTheTeacherOfTheOpenStrikesDrillAreNotAnAssault(GameTestHelper h) {
        SectHierarchyGameTests.Only layout = empty(h);
        long day = SectSchedule.day(h.getLevel().getDayTime());
        // drill id 2 is the strikes drill (SectDefeat.Drill.STRIKES ordinal 1 + 1), set today, not done
        SectState state = SectState.NONE.joined().contribute(20).record("defeat.drill", 2L).record("defeat.day", day);
        ServerPlayer p = player(h, 8, 8, state);
        SectDisciple mentor = npc(h, layout, "gyeong_pil", 10, 8, false);
        for (int i = 0; i < 4; i++) {
            mentor.hurt(p.damageSources().playerAttack(p), 1.0F);
        }
        h.assertTrue(!PenanceEnforcement.active(p) && !p.getData(ModAttachments.SECT).has(SectProgression.REPAIR),
                "a hit on the teacher of the strikes drill was an assault");
        // the teacher is the mentor; done today, or another day, or another person: no sanction
        h.assertTrue(SectLessonSanction.drillTeaches(state, day, "gyeong_pil"), "the open drill does not name the mentor");
        h.assertTrue(!SectLessonSanction.drillTeaches(state, day, "tae_seong"), "the drill names a person who is not the teacher");
        h.assertTrue(!SectLessonSanction.drillTeaches(state.record("defeat.done", day), day, "gyeong_pil"), "the finished drill still sanctions blows");
        h.assertTrue(!SectLessonSanction.drillTeaches(state, day + 1, "gyeong_pil"), "yesterday's drill still sanctions blows");
        h.succeed();
    }
}
