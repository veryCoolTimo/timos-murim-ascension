package io.github.verycooltimo.murim.sect;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectPose;
import io.github.verycooltimo.murim.mastery.TechniqueTier;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.EnumSet;
import java.util.Set;

/**
 * How people of the sect address an office holder (docs/design/39-player-path-v2.md «Обращение по положению»): the social rank
 * is the office if held, otherwise the standing. An elder (set the way the author does it, with {@code /murim dev sect position
 * elder}, so his ladder standing stays a novice's) is greeted by the juniors as an elder and by the first generation and the
 * council as a colleague; no newcomer topic reaches him. Real attachments, loaded dialogues; no client channel needed.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectAddressGameTests {
    private static final String YARD = "sect_yard";

    private SectAddressGameTests() { }

    private static SectDisciple npc(GameTestHelper h, String key, double x, double z) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setLayout(new SectGameTests.Yard(SectLife.stand(h.getLevel(), h.absoluteVec(new Vec3(12.5D, 2.0D, 12.5D))), 1.0D));
        d.setKeepAwake(true);
        d.setNoAi(true);
        Vec3 at = SectLife.stand(h.getLevel(), h.absoluteVec(new Vec3(x + 0.5D, 2.0D, z + 0.5D)));
        d.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        h.getLevel().addFreshEntity(d);
        return d;
    }

    /** The author's way: a fresh member, then {@code position elder|first}; the ladder standing stays what it was. */
    private static ServerPlayer holder(GameTestHelper h, String office, int realmRank) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        Vec3 at = SectLife.stand(h.getLevel(), h.absoluteVec(new Vec3(3.5D, 2.0D, 12.5D)));
        p.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        p.setOnGround(true);
        p.tickCount = 2000;
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withRank(realmRank));
        SectState s = SectState.NONE.joined().with("met_mentor");
        if (!"none".equals(office)) s = s.with(SectPositions.FIRST).with("pass.main_hall").with("pass.scriptures");
        if ("elder".equals(office)) s = s.with(SectPositions.ELDER).with("pass.elders").with("pass.treasury");
        p.setData(ModAttachments.SECT, s);
        return p;
    }

    private static String key(Component c) {
        return c.getContents() instanceof TranslatableContents t ? t.getKey() : "";
    }

    private static boolean office(String node) {
        return node.startsWith("office");
    }

    // ------------------------------------------------------------------ the greeting: every person of the mountain

    @GameTest(template = YARD, timeoutTicks = 200, batch = "sect_address_elder_greeting")
    public static void elderIsGreetedAsElderByEveryone(GameTestHelper h) {
        long savedTime = h.getLevel().getDayTime();
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        ServerPlayer p = holder(h, "elder", 0);
        h.assertTrue(SectRank.of(p) == SectRank.ELDER && SectService.standing(p) == SectStanding.NOVICE,
                "the setup is an elder with a novice's ladder standing: " + SectRank.of(p) + "/" + SectService.standing(p));
        for (SectRoster m : SectRoster.ALL) {
            SectDisciple d = npc(h, m.key(), 10, 10);
            DialogueService.Route r = DialogueService.route(p, d);
            h.assertTrue(r != null && !r.intercepted(), m.key() + ": the elder was stopped or got no route");
            h.assertTrue(office(r.node()), m.key() + ": an elder gets '" + r.node() + "', a newcomer's node");
            Dialogue.Node node = r.dialogue().nodes().get(r.node());
            // The node is the elder's: a personal line of the person, or the voice's «office_elder» context.
            for (String line : node.random()) {
                h.assertTrue(line.equals("@context") || line.contains(".office"), m.key() + ": line " + line);
            }
            // The first generation and the council speak as to a colleague, never to a junior.
            if (m.generation() <= 1 && !m.lay()) {
                h.assertTrue(r.node().equals("office_elder") || r.node().equals("office"), m.key() + ": " + r.node());
            }
            d.discard();
        }
        // Juniors say «elder»: the voice lines are the office_elder ones for the voice of the speaker.
        SectDisciple yul = npc(h, "yul_ak", 10, 10);
        h.assertTrue(key(DialogueService.talk(p, yul, "@context")).startsWith("dialogue.murim.social.junior.office_elder."),
                "a junior did not greet the elder: " + key(DialogueService.talk(p, yul, "@context")));
        SectDisciple seo = npc(h, "seo_gyu", 11, 10);
        h.assertTrue(key(DialogueService.talk(p, seo, "@context")).startsWith("dialogue.murim.social.second.office_elder."),
                "a second-generation disciple did not greet the elder");
        // A Gyeong mentor and an elder of the council greet him as a colleague with their own words.
        SectDisciple pil = npc(h, "gyeong_pil", 12, 10);
        DialogueService.Route mentor = DialogueService.route(p, pil);
        h.assertTrue(mentor.node().equals("office_elder"), "mentor: " + mentor.node());
        h.assertTrue(mentor.dialogue().nodes().get("office_elder").random().get(0).startsWith("dialogue.murim.gyeong_pil.office_elder."),
                "the mentor does not speak to an elder in his own words");
        SectDisciple tae = npc(h, "tae_seong", 13, 10);
        h.assertTrue(DialogueService.route(p, tae).node().equals("office_elder"), "council elder: " + DialogueService.route(p, tae).node());
        SectDisciple head = npc(h, "tae_hwi", 14, 10);
        h.assertTrue(DialogueService.route(p, head).node().equals("office"), "head: " + DialogueService.route(p, head).node());
        h.assertTrue(key(DialogueService.talk(p, head, "@context")).startsWith("dialogue.murim.social.head.office_elder."),
                "the head did not greet the elder as an elder");
        // The ladder condition reads the social rank: an elder passes every threshold and is above every rung.
        h.assertTrue(condition("{\"min_standing\":\"trusted\"}", p, yul) && condition("{\"min_standing\":\"elder\"}", p, yul)
                && !condition("{\"below_standing\":\"trusted\"}", p, yul), "min/below_standing do not read the social rank");
        h.getLevel().setDayTime(savedTime);
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 200, batch = "sect_address_first_greeting")
    public static void firstDisciplePeersTreatHimAsASeniorDisciple(GameTestHelper h) {
        long savedTime = h.getLevel().getDayTime();
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        ServerPlayer p = holder(h, "first", 3);
        h.assertTrue(SectRank.of(p) == SectRank.FIRST, "first disciple rank: " + SectRank.of(p));
        for (SectRoster m : SectRoster.ALL) {
            SectDisciple d = npc(h, m.key(), 10, 10);
            DialogueService.Route r = DialogueService.route(p, d);
            h.assertTrue(r != null && !r.intercepted() && office(r.node()), m.key() + ": a first disciple gets '" + (r == null ? "-" : r.node()) + "'");
            if (m.generation() <= 1 && !m.lay() && !m.key().equals("tae_hwi")) {
                h.assertTrue(r.node().equals("office_first"), m.key() + ": " + r.node());
            }
            d.discard();
        }
        SectDisciple yul = npc(h, "yul_ak", 10, 10);
        h.assertTrue(key(DialogueService.talk(p, yul, "@context")).startsWith("dialogue.murim.social.junior.office_first."), "junior to first disciple");
        SectDisciple pil = npc(h, "gyeong_pil", 12, 10);
        h.assertTrue(DialogueService.route(p, pil).dialogue().nodes().get("office_first").random().get(0)
                .startsWith("dialogue.murim.gyeong_pil.office_first."), "mentor to a first disciple");
        h.getLevel().setDayTime(savedTime);
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_address_ladder_unchanged")
    public static void ladderPlayersKeepTheirTalk(GameTestHelper h) {
        long savedTime = h.getLevel().getDayTime();
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        ServerPlayer p = holder(h, "none", 2);
        p.setData(ModAttachments.SECT, SectService.state(p).with(SectStanding.LESSON_ONE).with(SectStanding.LESSON_TWO)
                .with(SectStanding.APPROVAL).contribute(80));
        h.assertTrue(SectRank.of(p) == SectRank.TRUSTED && !SectRank.of(p).office(), "trusted without an office: " + SectRank.of(p));
        SectDisciple yul = npc(h, "yul_ak", 10, 10);
        h.assertTrue(!office(DialogueService.route(p, yul).node()), "a trusted disciple was greeted as an office holder");
        SectDisciple pil = npc(h, "gyeong_pil", 12, 10);
        h.assertTrue(!office(DialogueService.route(p, pil).node()), "the mentor treats a trusted disciple as a colleague");
        h.assertTrue(!SectSocial.situation(p, yul).startsWith("office"), "situation: " + SectSocial.situation(p, yul));
        h.getLevel().setDayTime(savedTime);
        h.succeed();
    }

    private static boolean condition(String json, ServerPlayer p, SectDisciple npc) {
        return DialogueService.test(p, npc, Dialogue.Condition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow());
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_address_cook_still_serves")
    public static void cookStillHandsTheBowlToAnElderInTheirOwnWords(GameTestHelper h) {
        long savedTime = h.getLevel().getDayTime();
        ServerPlayer p = holder(h, "elder", 4);
        SectDisciple cook = npc(h, "cook_kim", 6, 12);
        long day = Math.floorDiv(h.getLevel().getDayTime(), 24000L) * 24000L;
        h.getLevel().setDayTime(day + 1300L);
        DialogueService.Route route = DialogueService.route(p, cook);
        h.assertTrue("office_serving".equals(route.node()), "the cook does not serve an elder at breakfast: " + route.node());
        h.assertTrue(DialogueService.openDialogue(p, cook, route.id(), route.node()), "the cook did not open the dialogue");
        h.assertTrue(SectMeals.served(p), "an elder talking to the cook at breakfast got no bowl");
        h.assertTrue(route.dialogue().nodes().get("office_serving").random().get(0).startsWith("dialogue.murim.cook.office."),
                "the cook serves with the newcomer's words");
        h.getLevel().setDayTime(savedTime);
        h.succeed();
    }

    // ------------------------------------------------------------------ people who walk up

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_address_elder_approach")
    public static void elderGetsNoNewcomerApproaches(GameTestHelper h) {
        long savedTime = h.getLevel().getDayTime();
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        ServerPlayer p = holder(h, "elder", 4);
        SectDisciple rang = npc(h, "seo_rang", 19, 10);
        SectDisciple gyun = npc(h, "tae_gyun", 19, 14);
        npc(h, "gyeong_on", 21, 12);
        // Everything that would call a newcomer is on offer: just arrived, a review won, a bout lost, a favour open.
        long today = SectSchedule.day(h.getLevel().getDayTime());
        p.setData(ModAttachments.SECT, SectService.state(p).record("arrival.day", today).with(SectTalk.REVIEW_WON).with(SectService.SPAR_LOST));
        SectEvents.record(p, SectEvents.Event.BOUT_WON);
        SectEvents.record(p, SectEvents.Event.BOUT_LOST);
        Set<SectApproach.Kind> kinds = EnumSet.noneOf(SectApproach.Kind.class);
        for (int roll = 0; roll < 60; roll++) {
            SectApproach.Plan plan = SectApproach.choose(p, roll);
            h.assertTrue(plan != null, "nobody came to an idle elder, roll " + roll);
            kinds.add(plan.kind());
        }
        for (SectApproach.Kind k : Set.of(SectApproach.Kind.GOAL, SectApproach.Kind.HELP, SectApproach.Kind.WELCOME,
                SectApproach.Kind.FORMATION)) {
            h.assertTrue(!kinds.contains(k), "an elder is approached with the newcomer topic " + k);
        }
        h.assertTrue(kinds.contains(SectApproach.Kind.GREET) && kinds.contains(SectApproach.Kind.CONGRATS)
                && kinds.contains(SectApproach.Kind.CONSOLE), "elder's approaches: " + kinds);
        // What is said: a junior speaks up to him, the council member speaks as to a colleague; the junior may ask for a bout.
        h.assertTrue(key(SectApproach.line(rang, p, SectApproach.Kind.GREET, "")).startsWith("murim.approach.office.elder.up.greet."),
                "junior greeting: " + key(SectApproach.line(rang, p, SectApproach.Kind.GREET, "")));
        h.assertTrue(key(SectApproach.line(gyun, p, SectApproach.Kind.GREET, "")).startsWith("murim.approach.office.elder.peer.greet."),
                "peer greeting: " + key(SectApproach.line(gyun, p, SectApproach.Kind.GREET, "")));
        h.assertTrue(SectApproach.line(rang, p, SectApproach.Kind.SPAR, "") != null
                && SectApproach.line(gyun, p, SectApproach.Kind.SPAR, "") == null, "only juniors ask an elder for a bout");
        for (SectApproach.Kind k : Set.of(SectApproach.Kind.GOAL, SectApproach.Kind.HELP, SectApproach.Kind.WELCOME, SectApproach.Kind.FORMATION)) {
            h.assertTrue(SectApproach.line(rang, p, k, "pills") == null, "a line for the newcomer topic " + k);
        }
        // The formation bell: nobody calls an elder to the line.
        SectGameTests.setPeriod(h, SectSchedule.Period.FORMATION);
        p.setData(ModAttachments.SECT, SectService.state(p).without(SectApproach.LAST));
        h.assertTrue(SectApproach.choose(p, 0) == null, "an elder was called to the line");
        // A first disciple of Yul leads the line, so his generation may remind him.
        ServerPlayer first = holder(h, "first", 3);
        h.assertTrue(SectApproach.choose(first, 0) != null && SectApproach.choose(first, 0).kind() == SectApproach.Kind.FORMATION,
                "no call to the line for the first disciple");
        h.assertTrue(key(SectApproach.line(rang, first, SectApproach.Kind.FORMATION, "")).startsWith("murim.approach.office.first.up.formation."),
                "formation line");
        h.getLevel().setDayTime(savedTime);
        h.succeed();
    }

    // ------------------------------------------------------------------ absence, bubbles, bows

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_address_absence")
    public static void elderIsWelcomedBackNotScolded(GameTestHelper h) {
        ServerPlayer p = holder(h, "elder", 4);
        SectDisciple pil = npc(h, "gyeong_pil", 5, 12);
        p.setData(ModAttachments.SECT, SectService.state(p).record(SectAbsence.PENDING, 3));
        h.assertTrue(SectAbsence.meet(p, pil), "the mentor said nothing to the returning elder");
        h.assertTrue(pil.bubbleKey().startsWith("murim.sect.absence.elder.3."), "absence line: " + pil.bubbleKey());
        ServerPlayer first = holder(h, "first", 3);
        SectDisciple rang = npc(h, "seo_rang", 6, 12);
        first.setData(ModAttachments.SECT, SectService.state(first).record(SectAbsence.PENDING, 7));
        h.assertTrue(SectAbsence.meet(first, rang) && rang.bubbleKey().startsWith("murim.sect.absence.first.7."), "first disciple: " + rang.bubbleKey());
        ServerPlayer novice = holder(h, "none", 0);
        SectDisciple pil2 = npc(h, "gyeong_pil", 7, 12);
        novice.setData(ModAttachments.SECT, SectService.state(novice).record(SectAbsence.PENDING, 1));
        h.assertTrue(SectAbsence.meet(novice, pil2) && pil2.bubbleKey().startsWith("murim.sect.absence.1."), "a novice is still asked: " + pil2.bubbleKey());
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_address_bubbles")
    public static void juniorsBowAndPeersNodToAnElderEvenWhenHisRealmIsLow(GameTestHelper h) {
        // Realm 1 under an elder's seat is «weak» by the power rule (a title above the stance); the address still follows the office.
        ServerPlayer p = holder(h, "elder", 1);
        SectDisciple junior = npc(h, "yul_jin", 4, 12);
        h.assertTrue(SectSocial.power(p, junior) == SectSocial.Power.WEAK, "the setup is a weak elder");
        SectSocial.react(p, junior);
        h.assertTrue(junior.pose() == SectPose.BOW, "a junior did not bow to the elder");
        h.assertTrue(junior.bubbleKey().startsWith("murim.bubble.social.elder_up."), "junior bubble: " + junior.bubbleKey());
        SectDisciple coach = npc(h, "gyeong_pil", 5, 12);
        SectSocial.react(p, coach);
        h.assertTrue(coach.pose() != SectPose.BOW && coach.bubbleKey().startsWith("murim.bubble.social.elder_peer."),
                "mentor bubble: " + coach.bubbleKey());
        // No mocking, no drill: the weak-elder bubbles are not in the air.
        h.assertTrue(!junior.bubbleKey().contains(".mock.") && !coach.bubbleKey().contains(".advice."), "an elder was mocked or advised");
        // The first disciple gets the first_* words.
        ServerPlayer first = holder(h, "first", 3);
        SectDisciple junior2 = npc(h, "yul_ak", 4, 13);
        SectSocial.react(first, junior2);
        h.assertTrue(junior2.bubbleKey().startsWith("murim.bubble.social.first_up."), "first disciple bubble: " + junior2.bubbleKey());
        // Servants bow to the office whatever his realm.
        SectDisciple cook = npc(h, "cook_kim", 6, 12);
        h.assertTrue(SectReactions.bowReason(cook, p) == SectReactions.Bow.SENIOR, "a servant does not bow to a weak elder");
        h.assertTrue(SectReactions.bowReason(junior, p) == SectReactions.Bow.SENIOR, "a junior does not bow to a weak elder");
        // Higher art seen by the first generation: noted, not advised.
        SectSocial.highArt(p, TechniqueTier.ADVANCED);
        h.assertTrue(coach.bubbleKey().startsWith("murim.bubble.social.art_peer.") || coach.bubbleKey().startsWith("murim.bubble.social.elder_peer."),
                "mentor reaction to the art: " + coach.bubbleKey());
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_address_secret_art")
    public static void secretArtOfAnElderIsRecognisedNotPunished(GameTestHelper h) {
        ResourceLocation twentyFour = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "twenty_four_plum_cut");
        ServerPlayer elder = holder(h, "elder", 4);
        h.assertTrue(SectReactions.plumReaction(twentyFour, TechniqueTier.SECRET, SectService.standing(elder)) == SectReactions.PlumReaction.SERIOUS,
                "the setup: a novice's ladder standing would be punished");
        h.assertTrue(SectReactions.plumReaction(twentyFour, TechniqueTier.SECRET, SectRank.of(elder).standing()) == SectReactions.PlumReaction.SUMMONS,
                "an elder using a secret art must be recognised, not punished");
        Dialogue d = DialogueLoader.get(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "plum_witness"));
        h.assertTrue(d.nodes().containsKey("recognized_office"), "no recognition node for an office holder");
        h.succeed();
    }
}
