package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.network.SectGuidePayload;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Playtest 19.10: «непонятно к кому идти», «NPC не знают мои задачи», «нет опции сдать зачёт». The guide follows the person's
 * real current position; the person's opening menu carries the goal and the daily task; every word the new features say exists
 * in both languages.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectGuideGameTests {
    private static final String BATCH = "sect_guide";

    private SectGuideGameTests() { }

    private static ServerPlayer member(GameTestHelper h) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        Vec3 at = h.absoluteVec(new Vec3(2.5, 2, 2.5));
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.setData(ModAttachments.SECT, SectState.NONE.joined());
        h.getLevel().addNewPlayer(p);
        return p;
    }

    private static SectDisciple person(GameTestHelper h, String key, Vec3 rel) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setKeepAwake(true);
        d.setNoAi(true);
        Vec3 at = h.absoluteVec(rel);
        d.moveTo(at.x, at.y, at.z, 0, 0);
        h.getLevel().addFreshEntity(d);
        return d;
    }

    private static void leave(GameTestHelper h, ServerPlayer p) {
        h.getLevel().removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
    }

    @GameTest(template = "sect_yard", batch = BATCH, timeoutTicks = 60)
    public static void fullWallExamMarksExaminer(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        ServerPlayer p = member(h);
        SectDisciple mentor = person(h, "gyeong_pil", new Vec3(4.5, 2, 2.5));
        var profile = p.getData(ModAttachments.PROFILE).withTags("clear", "debug")
                .withAxes(60, 0.8, 0.4).withRank(io.github.verycooltimo.murim.cultivation.Realm.THIRD);
        p.setData(ModAttachments.PROFILE, profile.withPool(io.github.verycooltimo.murim.cultivation.Realm.wall(profile)));
        p.setData(ModAttachments.SECT, SectState.NONE.joined()
                .with("rank2.win.a").with("rank2.win.b").with("rank2.win.c"));
        var spots = SectGuide.spots(p);
        h.assertTrue(!spots.isEmpty() && spots.getFirst().goal() && spots.getFirst().entity() == mentor.getId(),
                "wall exam does not mark the mentor");
        h.assertTrue(spots.getFirst().what().getContents() instanceof TranslatableContents text
                && text.getKey().equals("murim.rank.wall.exam"), "marker has a different blocker");
        p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).with(SectProgression.SECOND_TEST));
        h.assertTrue(SectGuide.spots(p).stream().noneMatch(spot -> spot.id().equals("wall_teacher")),
                "wall marker remained after exam completion");
        leave(h, p);
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = BATCH, timeoutTicks = 60)
    public static void mentorPointsToHimselfInFirstPerson(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        ServerPlayer p = member(h);
        SectDisciple npc = person(h, "gyeong_pil", new Vec3(4.5, 2, 2.5));
        SectGeneralTalk.ask(p, 0);
        var answer = SectGeneralTalk.reply(p, npc);
        h.assertTrue(answer.getSiblings().stream().anyMatch(c -> c.getContents() instanceof TranslatableContents t
                && t.getKey().equals("dialogue.murim.general.where.here")), "mentor sent player to find himself");
        leave(h, p);
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = BATCH, timeoutTicks = 60)
    public static void markerHidesDuringMealsSleepAndScheduleTravel(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        ServerPlayer p = member(h);
        SectDisciple npc = person(h, "gyeong_pil", new Vec3(4.5, 2, 2.5));
        h.assertTrue(SectGuide.availableNow(p, npc, "method"), "idle mentor unavailable");
        npc.setPose(io.github.verycooltimo.murim.entity.SectPose.EAT);
        h.assertTrue(!SectGuide.availableNow(p, npc, "method"), "meal has quest marker");
        npc.setPose(io.github.verycooltimo.murim.entity.SectPose.NONE);
        npc.walking().walk(npc.position().add(8, 0, 0), 1.0D);
        h.assertTrue(!SectGuide.availableNow(p, npc, "method"), "scheduled travel has quest marker");
        npc.walking().stop();
        npc.setPose(io.github.verycooltimo.murim.entity.SectPose.SLEEP);
        h.assertTrue(!SectGuide.availableNow(p, npc, "method"), "sleep has quest marker");
        leave(h, p);
        h.succeed();
    }

    @GameTest(template = "sect_yard", templateNamespace = "murim", batch = BATCH, timeoutTicks = 60)
    public static void theGuideLeadsToThePersonWhereHeStandsNowAndFollowsHim(GameTestHelper h) {
        ServerPlayer p = member(h);
        SectJourney.Goal goal = SectJourney.next(p);
        SectDisciple npc = person(h, goal.person(), new Vec3(6.5, 2, 4.5));
        List<SectGuide.Spot> spots = SectGuide.spots(p);
        h.assertTrue(!spots.isEmpty(), "the guide shows nothing for a member with a goal");
        SectGuide.Spot main = spots.getFirst();
        h.assertTrue(main.goal() && main.id().equals(goal.id()), "the first spot is not the goal: " + main.id());
        h.assertTrue(main.entity() == npc.getId(), "the spot is not the live person");
        h.assertTrue(main.pos().distanceTo(npc.position()) < 0.01D, "the spot is not where he stands");
        // he walks to the other side: the spot follows without any waypoint being stored
        Vec3 to = h.absoluteVec(new Vec3(10.5, 2, 12.5));
        npc.moveTo(to.x, to.y, to.z, 0, 0);
        SectGuide.Spot moved = SectGuide.spots(p).getFirst();
        h.assertTrue(moved.pos().distanceTo(to) < 0.01D, "the spot did not follow the person");
        h.assertTrue(main.role().equals(SectRoleNames.of(goal.person())), "the role in the spot is not the person's role");
        leave(h, p);
        h.succeed();
    }

    @GameTest(template = "sect_yard", templateNamespace = "murim", batch = BATCH, timeoutTicks = 60)
    public static void theGuideIsSilentForAnOutsiderAndWithTheReminderOff(GameTestHelper h) {
        ServerPlayer p = member(h);
        person(h, SectJourney.next(p).person(), new Vec3(6.5, 2, 4.5));
        p.setData(ModAttachments.SECT, SectState.NONE);
        h.assertTrue(SectGuide.spots(p).isEmpty(), "an outsider is guided");
        p.setData(ModAttachments.SECT, SectState.NONE.joined().with(SectJourney.HUD_OFF));
        h.assertTrue(SectGuide.spots(p).isEmpty(), "the guide shows with the reminder off");
        leave(h, p);
        h.succeed();
    }

    @GameTest(template = "sect_yard", templateNamespace = "murim", batch = BATCH, timeoutTicks = 60)
    public static void aDailyDrillPointsToTheTeacherWhenTheGoalIsElsewhere(GameTestHelper h) {
        ServerPlayer p = member(h);
        SectDisciple teacher = person(h, SectDefeat.DEFAULT_TEACHER, new Vec3(8.5, 2, 8.5));
        long day = SectProgression.day(p);
        p.setData(ModAttachments.SECT, SectState.NONE.joined().record(SectDefeat.DRILL, SectDefeat.Drill.STRIKES.ordinal() + 1)
                .record(SectDefeat.DAY, day));
        SectGuide.Spot task = SectGuide.task(h.getLevel(), p, "tae_hwi");
        h.assertTrue(task != null && !task.goal() && task.id().equals("drill"), "the drill is not a daily task spot");
        h.assertTrue(task.entity() == teacher.getId(), "the drill does not point to the live teacher");
        // when the goal's own person is the teacher, one arrow is enough
        h.assertTrue(SectGuide.task(h.getLevel(), p, SectDefeat.DEFAULT_TEACHER) == null, "the same person is pointed to twice");
        leave(h, p);
        h.succeed();
    }

    @GameTest(template = "sect_yard", templateNamespace = "murim", batch = BATCH, timeoutTicks = 60)
    public static void theGuideIsSentOnlyWhenSomethingChanged(GameTestHelper h) {
        ServerPlayer p = member(h);
        SectDisciple npc = person(h, SectJourney.next(p).person(), new Vec3(6.5, 2, 4.5));
        h.assertTrue(SectGuide.sync(p), "the first guide was not sent");
        h.assertTrue(!SectGuide.sync(p), "an unchanged guide was sent again");
        Vec3 to = h.absoluteVec(new Vec3(14.5, 2, 12.5));
        npc.moveTo(to.x, to.y, to.z, 0, 0);
        h.assertTrue(SectGuide.sync(p), "a walking person was not re-sent");
        leave(h, p);
        h.succeed();
    }

    @GameTest(template = "sect_yard", templateNamespace = "murim", batch = BATCH, timeoutTicks = 60)
    public static void theGuidePayloadSurvivesTheWire(GameTestHelper h) {
        SectGuidePayload.Entry e = new SectGuidePayload.Entry(true, "take_six", Component.literal("lesson"), Component.literal("Gyeong Pil"),
                Component.translatable("murim.role.person.gyeong_pil"), Component.empty(), 1.5D, 64.0D, -3.25D, 77);
        SectGuidePayload in = new SectGuidePayload(List.of(e));
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        SectGuidePayload.STREAM_CODEC.encode(buf, in);
        SectGuidePayload out = SectGuidePayload.STREAM_CODEC.decode(buf);
        h.assertTrue(out.equals(in), "the payload changed on the wire: " + out);
        h.succeed();
    }

    // ------------------------------------------------------------------ the person knows

    @GameTest(template = "sect_yard", templateNamespace = "murim", batch = BATCH, timeoutTicks = 60)
    public static void theGoalsPersonOffersTheGoalFirstAndOthersDoNot(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        ServerPlayer p = member(h);
        SectJourney.Goal goal = SectJourney.next(p);
        SectDisciple npc = person(h, goal.person(), new Vec3(6.5, 2, 4.5));
        SectDisciple other = person(h, "yul_jin", new Vec3(8.5, 2, 4.5));
        Dialogue d = DialogueLoader.get(npc.dialogue());
        h.assertTrue(d != null && !d.start().isEmpty(), "the person has no dialogue");
        String hub = d.start().getFirst().node();
        h.assertTrue(SectGoalTalk.hub(p, npc, d, hub), "the opening menu is not a place for the hooks");
        SectGoalTalk.Hook hook = SectGoalTalk.hooks(p, npc, d)[0];
        h.assertTrue(hook != null && hook.id().equals(goal.id()), "the goal's person does not know the goal");
        Dialogue od = DialogueLoader.get(other.dialogue());
        h.assertTrue(od == null || SectGoalTalk.hooks(p, other, od)[0] == null, "a stranger offers the goal");
        leave(h, p);
        h.succeed();
    }

    @GameTest(template = "sect_yard", templateNamespace = "murim", batch = BATCH, timeoutTicks = 60)
    public static void theTeacherAsksForTheDrillAndAnswersWithTheCountInVariedWords(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        ServerPlayer p = member(h);
        SectDisciple teacher = person(h, SectDefeat.DEFAULT_TEACHER, new Vec3(6.5, 2, 4.5));
        long day = SectProgression.day(p);
        p.setData(ModAttachments.SECT, SectState.NONE.joined().record(SectDefeat.DRILL, SectDefeat.Drill.STRIKES.ordinal() + 1)
                .record(SectDefeat.DAY, day));
        Dialogue d = DialogueLoader.get(teacher.dialogue());
        SectGoalTalk.Hook task = SectGoalTalk.taskHook(p, teacher, d, "");
        h.assertTrue(task != null && task.id().equals("drill"), "the teacher does not know today's exercise");
        Component text = SectGoalTalk.text(p, "drill");
        h.assertTrue(text.getContents() instanceof TranslatableContents t && t.getKey().equals("dialogue.murim.hook.drill")
                && t.getArgs().length == 3, "the hook does not carry the count");
        p.getPersistentData().putString(SectGoalTalk.LAST, "drill");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 60; i++) {
            seen.add(((TranslatableContents) SectGoalTalk.reply(p, teacher).getContents()).getKey());
        }
        h.assertTrue(seen.size() >= 2, "the answer is always the same: " + seen);
        h.assertTrue(seen.stream().allMatch(k -> k.startsWith("dialogue.murim.hook.reply.drill.")), "wrong answers: " + seen);
        leave(h, p);
        h.succeed();
    }

    @GameTest(template = "sect_yard", templateNamespace = "murim", batch = BATCH, timeoutTicks = 60)
    public static void aTestAskedForTooEarlyIsAnsweredNotYetWithWhatIsMissing(GameTestHelper h) {
        h.assertTrue("notyet".equals(SectGoalTalk.adviceKind("submit_six")), "a test too early is not 'not yet'");
        h.assertTrue("notyet".equals(SectGoalTalk.adviceKind("submit_spar")), "the sparring test too early is not 'not yet'");
        h.assertTrue("advice".equals(SectGoalTalk.adviceKind("method")), "a plain goal is not advice");
        h.succeed();
    }

    // ------------------------------------------------------------------ words

    private static Set<String> lang(String file) throws Exception {
        try (InputStream in = SectGuideGameTests.class.getResourceAsStream("/assets/murim/lang/" + file)) {
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            return new HashSet<>(o.keySet());
        }
    }

    @GameTest(template = "sect_yard", batch = BATCH, timeoutTicks = 60)
    public static void everyRoleHasItsOwnBilingualSmallTalk(GameTestHelper h) throws Exception {
        for (String file : new String[] {"en_us.json", "ru_ru.json"}) {
            Set<String> keys = lang(file);
            for (SectRole role : SectRole.values()) {
                String suffix = role.name().toLowerCase(java.util.Locale.ROOT);
                h.assertTrue(keys.contains(SectGeneralTalk.SELF_STEM + suffix), "missing self voice: " + suffix + " in " + file);
                h.assertTrue(keys.contains(SectGeneralTalk.RUMOUR_STEM + "." + suffix), "missing news voice: " + suffix + " in " + file);
            }
            for (String key : List.of("dialogue.murim.general.opt.talk", "dialogue.murim.general.more_down",
                    "dialogue.murim.general.where.here", "dialogue.murim.head_intro.offer", "murim.sect.head_intro.here")) {
                h.assertTrue(keys.contains(key), "missing dialogue word: " + key + " in " + file);
            }
        }
        h.succeed();
    }

    @GameTest(template = "sect_yard", templateNamespace = "murim", batch = BATCH, timeoutTicks = 60)
    public static void everyWordOfTheGuideExistsInBothLanguages(GameTestHelper h) throws Exception {
        for (String file : new String[] {"en_us.json", "ru_ru.json"}) {
            Set<String> keys = lang(file);
            List<String> missing = new java.util.ArrayList<>();
            for (SectRoster r : SectRoster.ALL) {
                String k = SectRoleNames.key(r.key(), r.role());
                if (!keys.contains(k)) missing.add(k);
            }
            for (SectRole role : SectRole.values()) {
                if (!keys.contains("murim.role." + role.id())) missing.add("murim.role." + role.id());
            }
            for (String id : SectGoalTalk.WORDED) {
                if (!keys.contains("dialogue.murim.hook." + id)) missing.add("dialogue.murim.hook." + id);
            }
            for (String kind : new String[] {"drill", "drill_done", "favour", "request", "deliver_pills", "direct", "personal_lesson",
                    "apology", "repair", "ceremony", "ceremony_wait", "lessons", "none", "contract", "advice", "notyet"}) {
                for (int i = 1; i <= SectGoalTalk.VARIANTS; i++) {
                    if (!keys.contains("dialogue.murim.hook.reply." + kind + "." + i)) missing.add("reply." + kind + "." + i);
                }
            }
            for (String k : new String[] {"dialogue.murim.hook.generic", "dialogue.murim.hook.ok", "dialogue.murim.hook.clean_hint",
                    "murim.guide.task.drill", "murim.guide.task.contract", "murim.guide.task.contract_ready",
                    "murim.guide.task.favour.vault", "murim.guide.task.favour.training", "murim.guide.task.favour.pills",
                    "murim.guide.task.favour.bandits", "murim.sect.spar.counted", "murim.sect.spar.counted.lesson",
                    "murim.sect.spar.counted.clean", "murim.sect.spar.counted.clean_done", "murim.sect.spar.counted.partners",
                    "murim.tablet.mark.drill", "murim.tablet.mark.drill_done", "murim.rank.wall.boss_trial"}) {
                if (!keys.contains(k)) missing.add(k);
            }
            h.assertTrue(missing.isEmpty(), file + " lacks " + missing);
        }
        h.succeed();
    }
}
