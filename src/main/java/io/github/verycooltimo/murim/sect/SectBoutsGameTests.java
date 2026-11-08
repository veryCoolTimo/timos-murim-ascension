package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.MeditationState;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Ring bouts, challenges to seniors, the ceremony, the defeat analysis, the consented duel and the eight-place review
 * (docs/design/39-player-path-v2.md items 4 and 5, with the small points of the section "Мелкие моменты").
 * The layout is a flat test layout whose "training" zone is the yard origin, so the real ring offsets apply.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectBoutsGameTests {
    private static final String YARD = "sect_yard";
    private static final String BATCH = "sect_bouts";

    private SectBoutsGameTests() {
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

    /** Ring 0 of the showcase rings: centre (-9, -5) from the origin. */
    private static Vec3 ring(Flat f) {
        return f.at("training", SectSchedule.RINGS[0][0], SectSchedule.RINGS[0][1]);
    }

    private static Vec3 outsideRing(Flat f) {
        return f.at("training", -4.5D, 0.0D);
    }

    private static SectState member() {
        return SectState.NONE.joined();
    }

    private static ServerPlayer player(GameTestHelper h, Vec3 at) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.setOnGround(true);
        vulnerable(p);
        p.setData(ModAttachments.SECT, member());
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(SectService.SIX, TechniqueProgress.learned(1, 6)));
        return p;
    }

    /** A fresh server player ignores all damage for 60 ticks; a test player never ticks, so end that by hand. */
    private static void vulnerable(ServerPlayer p) {
        try {
            java.lang.reflect.Field field = ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");
            field.setAccessible(true);
            field.setInt(p, 0);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
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

    private static SectDisciple near(GameTestHelper h, Flat f, String key) {
        return npc(h, f, key, ring(f).add(1.5, 0, 0));
    }

    /** Seniors that are not on the post by the rota at the time of the test (a man on watch is not free). */
    private static List<String> seniors(GameTestHelper h) {
        long time = h.getLevel().getDayTime();
        return SectRoster.ALL.stream().filter(m -> (m.role() == SectRole.SENIOR || m.role() == SectRole.SECOND) && !SectRota.onDuty(m, time))
                .map(SectRoster::key).toList();
    }

    /** True if the line (or a part of it) is the translatable {@code key}. */
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

    private static void day(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
    }

    // ------------------------------------------------------------------ training bout

    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 60)
    public static void trainingBoutNeedsRingFreePartnerAndDaytime(GameTestHelper h) {
        day(h);
        Flat f = flat(h);
        ServerPlayer p = player(h, ring(f));
        SectDisciple d = near(h, f, "ham_doyun");
        h.assertTrue(SectBouts.canTrain(p, d), "free disciple in a ring refused");
        p.teleportTo(outsideRing(f).x, outsideRing(f).y, outsideRing(f).z);
        h.assertTrue(!SectBouts.canTrain(p, d), "bout outside a ring allowed");
        p.teleportTo(ring(f).x, ring(f).y, ring(f).z);
        d.setDoingKind(SectSchedule.Kind.EAT);
        h.assertTrue(!SectBouts.canTrain(p, d), "an eating disciple is not free");
        d.setDoingKind(SectSchedule.Kind.SPAR);
        d.setWounded(true);
        h.assertTrue(!SectBouts.canTrain(p, d), "a wounded disciple is not free");
        d.setWounded(false);
        SectGameTests.setPeriod(h, SectSchedule.Period.NIGHT);
        h.assertTrue(!SectBouts.canTrain(p, d), "bout at night");
        SectGameTests.setPeriod(h, SectSchedule.Period.DINNER);
        h.assertTrue(!SectBouts.canTrain(p, d), "bout at the meal");
        day(h);
        h.assertTrue(SectBouts.begin(p, d, SectBouts.Mode.TRAIN), "bout did not begin");
        h.assertTrue(d.spar() != SectDisciple.Spar.NONE, "no spar started");
        h.assertTrue(SectBouts.active(p) != null && SectBouts.active(p).mode == SectBouts.Mode.TRAIN, "bout not tracked");
        h.assertTrue(d.floorFor(p) >= p.getHealth() - p.getMaxHealth() * 0.5F - 0.01F && d.floorFor(p) >= 1.0F, "spar damage not safe");
        h.succeed();
    }

    /** Hitting no longer starts a spar, so every member who can spar offers «Challenge to a spar» in his opening menu. */
    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 80)
    public static void everySparringMemberOffersTheChallengeInTheOpeningMenu(GameTestHelper h) {
        day(h);
        Flat f = flat(h);
        ServerPlayer p = player(h, ring(f));
        int checked = 0;
        for (SectRoster m : SectRoster.ALL) {
            if (m.lay() || !(m.role().spars() || m.role() == SectRole.MENTOR)) {
                continue;
            }
            SectDisciple d = near(h, f, m.key());
            Dialogue dialogue = DialogueLoader.get(d.dialogue());
            h.assertTrue(dialogue != null, m.key() + " has no dialogue");
            boolean offered = false;
            for (Dialogue.Entry e : dialogue.start()) {
                Dialogue.Node node = DialogueService.nodeOf(dialogue, e.node());
                if (node == null || !SectGoalTalk.hub(p, d, dialogue, e.node())) {
                    continue;
                }
                offered |= SectBouts.sharedRow(p, d, dialogue, e.node()) || node.options().stream().anyMatch(o ->
                        o.text().equals("dialogue.murim.bout.opt.ring") || o.text().equals("dialogue.murim.bout.opt.menu"));
            }
            h.assertTrue(offered, m.key() + " does not offer a spar in his opening menu");
            d.discard();
            checked++;
        }
        h.assertTrue(checked > 10, "too few members checked: " + checked);
        h.succeed();
    }

    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 60)
    public static void busyRingMakesOthersWaitAtTheEdge(GameTestHelper h) {
        day(h);
        Flat f = flat(h);
        ServerPlayer p = player(h, ring(f));
        SectDisciple a = near(h, f, "yul_jin");
        SectDisciple b = npc(h, f, "yul_seok", ring(f).add(-1.5, 0, 0));
        SectDisciple third = npc(h, f, "ham_doyun", ring(f).add(0, 0, 2.0));
        h.assertTrue(SectBouts.canTrain(p, third), "free ring refused");
        a.sparWith(b, 0);
        // The square is shared (author 22.10): several pairs spar at once.
        h.assertTrue(SectBouts.canTrain(p, third), "the shared square refused a second bout");
        h.succeed();
    }

    // ------------------------------------------------------------------ challenge to seniors

    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 60)
    public static void onlySeniorsCanBeChallengedJuniorsGetPoliteRefusal(GameTestHelper h) {
        day(h);
        Flat f = flat(h);
        ServerPlayer p = player(h, ring(f));
        SectDisciple junior = near(h, f, "ham_doyun");
        SectDisciple senior = npc(h, f, seniors(h).get(0), ring(f).add(0, 0, 2.0));
        h.assertTrue(!SectBouts.canChallenge(p, junior) && SectBouts.canTrain(p, junior), "junior challenge not refused");
        h.assertTrue(says(SectBouts.menuLine(p, junior), "dialogue.murim.bout.menu.junior"), "no polite refusal line");
        h.assertTrue(SectBouts.canChallenge(p, senior), "senior challenge refused");
        h.assertTrue(says(SectBouts.menuLine(p, senior), "dialogue.murim.bout.strength.stronger") || says(SectBouts.menuLine(p, senior), "dialogue.murim.bout.strength.equal")
                || says(SectBouts.menuLine(p, senior), "dialogue.murim.bout.strength.weaker"), "no strength hint");
        h.succeed();
    }

    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 100)
    public static void fiveDifferentSeniorsOpenTheCeremonyAndPromoteOneStep(GameTestHelper h) {
        day(h);
        Flat f = flat(h);
        ServerPlayer p = player(h, ring(f));
        SectDisciple bystander = npc(h, f, "ham_doyun", ring(f).add(-5, 0, 0));
        List<String> seniors = seniors(h).subList(0, 5);
        SectStanding before = SectService.standing(p);
        int contribution = SectService.state(p).contribution();
        for (int i = 0; i < seniors.size(); i++) {
            SectDisciple s = near(h, f, seniors.get(i));
            h.assertTrue(SectBouts.begin(p, s, SectBouts.Mode.CHALLENGE), "challenge " + i + " refused: " + SectBouts.menuLine(p, s).getString() + " kind=" + s.doingKind() + " active=" + SectBouts.active(p));
            SectBouts.ended(p, s, true);
            h.assertTrue(SectBouts.wins(SectService.state(p)) == i + 1, "win " + i + " not counted: " + SectBouts.wins(SectService.state(p)));
            if (i == 0) {
                // The same senior again on the same day does not count (no farming one man).
                SectDisciple again = npc(h, f, seniors.get(0), ring(f).add(0, 0, -2.0));
                s.discard();
                h.assertTrue(!SectBouts.canChallenge(p, again), "the same senior counted twice in a day");
                h.assertTrue(SectBouts.canTrain(p, again), "training with him is still free");
            } else {
                s.discard();
            }
        }
        h.assertTrue(SectService.state(p).has(SectBouts.CEREMONY), "no ceremony after five wins");
        h.assertTrue(SectService.standing(p) == before, "promoted without the ceremony");
        h.assertTrue(SectService.state(p).contribution() > contribution, "no respect (merit) for the wins");
        h.assertTrue(SectBouts.rumourLine(p, bystander) != null, "the sect did not hear of the wins");
        h.assertTrue(says(SectJourney.line(p), "murim.sect.bout.progress_ceremony"), "journal does not show the ceremony");
        // The ceremony with the leader: two steps, the second needs the pause.
        SectDisciple leader = npc(h, f, "tae_hwi", ring(f).add(0, 0, 3.0));
        h.assertTrue(SectProgression.matches(p, leader, "promo") && !SectProgression.matches(p, leader, "promo_confirm"), "ceremony offer wrong");
        h.assertTrue(!SectProgression.act(p, leader, "promo_confirm"), "ceremony skipped the pause");
        h.assertTrue(SectProgression.act(p, leader, "promo"), "ceremony did not begin");
        h.assertTrue(!SectProgression.act(p, leader, "promo_confirm"), "ceremony confirmed at once");
        p.setData(ModAttachments.SECT, SectService.state(p).record(SectBouts.CEREMONY_AT, h.getLevel().getGameTime() - SectBouts.CEREMONY_TICKS));
        h.assertTrue(SectProgression.act(p, leader, "promo_confirm"), "ceremony did not finish");
        SectStanding after = SectService.standing(p);
        h.assertTrue(after.ordinal() == before.ordinal() + 1, "one step expected, got " + after);
        h.assertTrue(SectBouts.wins(SectService.state(p)) == 0 && !SectService.state(p).has(SectBouts.CEREMONY), "counter not reset");
        SectState saved = SectState.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
                SectState.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, SectService.state(p)).getOrThrow()).getOrThrow();
        h.assertTrue(SectStanding.of(saved, 0) == after, "promotion lost on save");
        h.assertTrue(!SectProgression.act(p, leader, "promo"), "ceremony repeated");
        h.succeed();
    }

    private static void rank(ServerPlayer p, int rank) {
        p.setData(ModAttachments.PROFILE, io.github.verycooltimo.murim.profile.DantianProfile.INITIAL.withTags("pure", "six").withRank(rank));
    }

    /** True if the line (or a part of it) is the translatable {@code key} with first argument {@code arg}. */
    private static boolean saysWith(net.minecraft.network.chat.Component c, String key, Object arg) {
        if (c.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t) {
            if (t.getKey().equals(key) && t.getArgs().length > 0 && arg.equals(t.getArgs()[0])) {
                return true;
            }
            for (Object a : t.getArgs()) {
                if (a instanceof net.minecraft.network.chat.Component ac && saysWith(ac, key, arg)) {
                    return true;
                }
            }
        }
        return c.getSiblings().stream().anyMatch(x -> saysWith(x, key, arg));
    }

    /** Option "В": five wins over seniors STRONGER than the player by rank give the step to trusted; weaker seniors give respect only. */
    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 100)
    public static void challengesPromoteToTrustedOnlyOverStrongerSeniors(GameTestHelper h) {
        day(h);
        Flat f = flat(h);
        ServerPlayer p = player(h, ring(f));
        p.setData(ModAttachments.SECT, member().with(SectStanding.LESSON_ONE).with(SectStanding.LESSON_TWO));
        h.assertTrue(SectService.standing(p) == SectStanding.GRADUATE, "setup: graduate expected, got " + SectService.standing(p));
        List<String> seniors = seniors(h);
        // A player of rank 3 is stronger than the second generation (rank 2): wins are respect, no step towards trust.
        rank(p, 3);
        SectDisciple weak = near(h, f, seniors.get(0));
        h.assertTrue(SectBouts.begin(p, weak, SectBouts.Mode.CHALLENGE), "challenge refused");
        SectBouts.ended(p, weak, true);
        h.assertTrue(SectBouts.wins(SectService.state(p)) == 0 && !SectService.state(p).has(SectBouts.CEREMONY), "a win over a not stronger senior counted for trust");
        h.assertTrue(SectService.state(p).entry(SectBouts.TOTAL, 0) == 1, "the win was not recorded at all");
        weak.discard();
        // Rank 2: the second generation is equal, which counts; five wins open the ceremony, the leader gives trusted.
        rank(p, 2);
        p.setData(ModAttachments.SECT, SectService.state(p).record(SectBouts.DAY_PREFIX + seniors.get(0), -1));
        for (int i = 0; i < 5; i++) {
            SectDisciple s = near(h, f, seniors.get(i));
            h.assertTrue(SectBouts.begin(p, s, SectBouts.Mode.CHALLENGE), "challenge " + i + " refused");
            SectBouts.ended(p, s, true);
            s.discard();
            h.assertTrue(SectBouts.wins(SectService.state(p)) == i + 1, "win " + i + " over an equal senior not counted");
        }
        h.assertTrue(SectService.state(p).has(SectBouts.CEREMONY), "no ceremony after five wins over equal seniors");
        SectDisciple leader = npc(h, f, "tae_hwi", ring(f).add(0, 0, 3.0));
        h.assertTrue(SectProgression.act(p, leader, "promo"), "ceremony did not begin");
        p.setData(ModAttachments.SECT, SectService.state(p).record(SectBouts.CEREMONY_AT, h.getLevel().getGameTime() - SectBouts.CEREMONY_TICKS));
        h.assertTrue(SectProgression.act(p, leader, "promo_confirm"), "ceremony did not finish");
        h.assertTrue(SectService.standing(p) == SectStanding.TRUSTED, "challenges did not give trusted: " + SectService.standing(p));
        SectState saved = SectState.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
                SectState.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, SectService.state(p)).getOrThrow()).getOrThrow();
        h.assertTrue(SectStanding.of(saved, 1) == SectStanding.TRUSTED, "trusted lost on save");
        h.succeed();
    }

    /** Mentors of the first generation: Peak and every strong second-generation senior beaten; elders and the leader never. */
    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 100)
    public static void mentorsAcceptChallengesOnlyAtThePeakAfterAllStrongSeniors(GameTestHelper h) {
        day(h);
        Flat f = flat(h);
        ServerPlayer p = player(h, ring(f));
        List<String> free = seniors(h);
        String last = free.get(0);
        SectDisciple mentor = npc(h, f, "gyeong_on", ring(f).add(1.5, 0, 0));
        h.assertTrue(SectBouts.gyeong(mentor) && SectBouts.challengeable(mentor) && !SectBouts.senior(mentor), "first-generation member is not a mentor target");
        // Strength reflects rank: a mentor hits harder and is tougher than a second-generation senior.
        SectDisciple second = npc(h, f, last, ring(f).add(-1.5, 0, 0));
        h.assertTrue(mentor.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)
                > second.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)
                && mentor.getMaxHealth() > second.getMaxHealth(), "mentor is not stronger than a second-generation senior");
        second.discard();
        // Not at the Peak yet.
        rank(p, 3);
        h.assertTrue(!SectBouts.canChallenge(p, mentor) && SectBouts.canTrain(p, mentor), "challenge allowed below the Peak, or no training");
        h.assertTrue(says(SectBouts.menuLine(p, mentor), "dialogue.murim.bout.menu.not_peak"), "no 'not at the Peak' refusal: " + SectBouts.menuLine(p, mentor));
        // At the Peak, but the strong seniors are not beaten.
        rank(p, 4);
        int strong = SectBouts.strongSecond().size();
        h.assertTrue(strong >= 5 && SectBouts.strongLeft(SectService.state(p)) == strong, "strong second generation not counted: " + strong);
        h.assertTrue(!SectBouts.canChallenge(p, mentor), "challenge allowed before the seniors are beaten");
        h.assertTrue(saysWith(SectBouts.menuLine(p, mentor), "dialogue.murim.bout.menu.elders_first", strong), "no 'seniors first: N left' refusal: " + SectBouts.menuLine(p, mentor));
        // All but one beaten: the count shows one left.
        SectState s = SectService.state(p);
        for (SectRoster m : SectBouts.strongSecond()) {
            if (!m.key().equals(last)) {
                s = s.record(SectBouts.BEAT_PREFIX + m.key(), 1);
            }
        }
        p.setData(ModAttachments.SECT, s);
        h.assertTrue(SectBouts.strongLeft(SectService.state(p)) == 1 && saysWith(SectBouts.menuLine(p, mentor), "dialogue.murim.bout.menu.elders_first", 1), "one senior left not shown");
        // Beat the last one in a real challenge: the key is remembered and the mentor accepts.
        SectDisciple lastOne = npc(h, f, last, ring(f).add(0, 0, 2.0));
        h.assertTrue(SectBouts.begin(p, lastOne, SectBouts.Mode.CHALLENGE), "challenge to the last senior refused");
        SectBouts.ended(p, lastOne, true);
        lastOne.discard();
        h.assertTrue(SectBouts.strongLeft(SectService.state(p)) == 0, "the win over the last senior was not remembered");
        h.assertTrue(SectBouts.canChallenge(p, mentor), "mentor refused at the Peak after all seniors: " + SectBouts.menuLine(p, mentor));
        // His free time only: a mentor at the meal or asleep does not agree.
        mentor.setDoingKind(SectSchedule.Kind.EAT);
        h.assertTrue(!SectBouts.canChallenge(p, mentor), "a mentor at the meal accepted");
        mentor.setDoingKind(SectSchedule.Kind.SPAR);
        h.assertTrue(SectBouts.canChallenge(p, mentor), "a free mentor refused");
        h.assertTrue(SectBouts.begin(p, mentor, SectBouts.Mode.CHALLENGE), "mentor challenge did not begin");
        SectBouts.ended(p, mentor, true);
        h.assertTrue(SectService.state(p).entry(SectBouts.TOTAL, 0) >= 2, "win over the mentor not counted");
        h.assertTrue(!SectBouts.canChallenge(p, mentor), "the same mentor counted twice in a day");
        // Elders and the leader cannot be challenged at all.
        SectDisciple elder = npc(h, f, "gyeong_tak", ring(f).add(0, 0, -2.0));
        SectDisciple leader = npc(h, f, "tae_hwi", ring(f).add(2.0, 0, 2.0));
        h.assertTrue(!SectBouts.challengeable(elder) && !SectBouts.challengeable(leader) && !SectBouts.canChallenge(p, elder)
                && !SectBouts.canChallenge(p, leader), "an elder or the leader can be challenged");
        h.succeed();
    }

    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 60)
    public static void lossesNeverPunishAndSendTheMentorAnalysis(GameTestHelper h) {
        day(h);
        Flat f = flat(h);
        ServerPlayer p = player(h, ring(f));
        SectDisciple s = near(h, f, seniors(h).get(0));
        int merit = SectService.state(p).contribution();
        h.assertTrue(SectBouts.begin(p, s, SectBouts.Mode.CHALLENGE), "challenge refused");
        SectBouts.active(p).taken = 5;
        SectBouts.active(p).dealt = 1;
        SectBouts.ended(p, s, false);
        SectState st = SectService.state(p);
        h.assertTrue(st.contribution() == merit && SectBouts.wins(st) == 0 && st.entry(SectBouts.LOSSES, 0) == 1, "a loss changed progress or merit");
        h.assertTrue(!st.has(SectProgression.REPAIR) && SectService.standing(p) == SectStanding.NOVICE, "a loss punished");
        h.assertTrue(SectBoutSession.of(p).pending.containsKey(p.getUUID()), "no analysis queued");
        h.assertTrue(SectDefeat.run(p) != null && SectDefeat.run(p).drill == SectDefeat.Drill.FOOTWORK, "no drill for taken hits");
        h.succeed();
    }

    // ------------------------------------------------------------------ the end of a bout

    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 100)
    public static void leavingTheRingDuringTheCountIsARefusalWithoutPenalty(GameTestHelper h) {
        day(h);
        Flat f = flat(h);
        ServerPlayer p = player(h, ring(f));
        SectDisciple s = near(h, f, seniors(h).get(0));
        h.assertTrue(SectBouts.begin(p, s, SectBouts.Mode.CHALLENGE), "challenge refused");
        h.assertTrue(s.spar() == SectDisciple.Spar.WAIT, "no count before the bow");
        Vec3 out = outsideRing(f).add(0, 0, 25);
        p.teleportTo(out.x, out.y, out.z);
        SectBouts.supervise(p, SectBouts.active(p));
        h.assertTrue(SectBouts.active(p) == null && s.spar() == SectDisciple.Spar.NONE, "the bout was not called off");
        h.assertTrue(!SectBoutSession.of(p).pending.containsKey(p.getUUID()) && SectService.state(p).entry(SectBouts.LOSSES, 0) == 0,
                "a refusal counted as a defeat");
        h.succeed();
    }

    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 400)
    public static void ringOutTimeoutAndDrawDecideTheFight(GameTestHelper h) {
        day(h);
        Flat f = flat(h);
        ServerPlayer p = player(h, ring(f));
        h.getLevel().addNewPlayer(p);
        SectDisciple s = near(h, f, seniors(h).get(0));
        h.assertTrue(SectBouts.begin(p, s, SectBouts.Mode.CHALLENGE), "challenge refused");
        h.runAfterDelay(SectBouts.COUNTDOWN + SectDisciple.BOW_TICKS + 12, () -> {
            h.assertTrue(s.spar() == SectDisciple.Spar.FIGHT, "fight did not start: " + s.spar());
            SectBouts.Bout b = SectBouts.active(p);
            SectBouts.supervise(p, b);
            h.assertTrue(b.fightStart >= 0, "fight start not noted");
            // 60 seconds with equal points: a draw, nobody loses.
            b.fightStart = h.getLevel().getGameTime() - SectBouts.FIGHT_LIMIT;
            SectBouts.supervise(p, b);
            h.assertTrue(SectBouts.active(p) == null && s.spar() == SectDisciple.Spar.NONE, "equal points were not a draw");
            h.assertTrue(SectService.state(p).entry(SectBouts.LOSSES, 0) == 0, "a draw counted as a loss");
            // A new fight: out of the ring for a second is a defeat.
            SectDisciple s2 = npc(h, f, seniors(h).get(1), ring(f).add(0, 0, 2.0));
            h.assertTrue(SectBouts.begin(p, s2, SectBouts.Mode.CHALLENGE), "second challenge refused");
            h.runAfterDelay(SectBouts.COUNTDOWN + SectDisciple.BOW_TICKS + 12, () -> {
                h.assertTrue(s2.spar() == SectDisciple.Spar.FIGHT, "second fight did not start: " + s2.spar());
                SectBouts.Bout b2 = SectBouts.active(p);
                Vec3 out = outsideRing(f).add(0, 0, 25);
                p.teleportTo(out.x, out.y, out.z);
                for (int i = 0; i < 25 && SectBouts.active(p) != null; i++) {
                    SectBouts.supervise(p, b2);
                }
                h.assertTrue(SectBouts.active(p) == null, "ring-out did not end the fight");
                h.assertTrue(SectService.state(p).entry(SectBouts.LOSSES, 0) == 1, "ring-out was not a defeat");
                h.getLevel().removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                h.succeed();
            });
        });
    }

    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 60)
    public static void aThirdPartyStrikingInStopsTheBoutWithoutResult(GameTestHelper h) {
        day(h);
        Flat f = flat(h);
        ServerPlayer p = player(h, ring(f));
        SectDisciple s = near(h, f, seniors(h).get(0));
        h.assertTrue(SectBouts.begin(p, s, SectBouts.Mode.CHALLENGE), "challenge refused");
        Zombie z = h.spawn(EntityType.ZOMBIE, new Vec3(2.5, 2, 2.5));
        z.setNoAi(true);
        p.hurt(p.damageSources().mobAttack(z), 1.0F);
        h.assertTrue(SectBouts.active(p) == null && s.spar() == SectDisciple.Spar.NONE, "interference did not stop the bout");
        h.assertTrue(SectService.state(p).entry(SectBouts.LOSSES, 0) == 0, "interference counted as a loss");
        h.succeed();
    }

    // ------------------------------------------------------------------ defeat analysis

    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 60)
    public static void diagnosisReadsTheBoutData(GameTestHelper h) {
        h.assertTrue(SectDefeat.diagnose(1, 5, 3.0, 0.9, 0.05) == SectDefeat.Reason.QI, "dry qi not named");
        h.assertTrue(SectDefeat.diagnose(5, 1, 3.0, 0.9, 0.8) == SectDefeat.Reason.HITS, "taken hits not named");
        h.assertTrue(SectDefeat.diagnose(0, 1, 6.0, 0.9, 0.8) == SectDefeat.Reason.FAR, "distance (far) not named");
        h.assertTrue(SectDefeat.diagnose(3, 3, 1.0, 0.9, 0.8) == SectDefeat.Reason.CLOSE, "distance (close) not named");
        h.assertTrue(SectDefeat.diagnose(1, 1, 3.0, 0.9, 0.8) == SectDefeat.Reason.PASSIVE, "passivity not named");
        h.assertTrue(SectDefeat.drillFor(SectDefeat.Reason.QI) == SectDefeat.Drill.MEDITATE && SectDefeat.Drill.MEDITATE.need() == 600,
                "qi needs half a minute of meditation");
        h.succeed();
    }

    // Own batch: the review test of this batch moves the world clock, and the drill of a lost bout belongs to a day.
    @GameTest(template = YARD, batch = "sect_defeat_drill", timeoutTicks = 200)
    public static void mentorComesOnceSaysOneReasonAndTheDrillPaysMastery(GameTestHelper h) {
        day(h);
        Flat f = flat(h);
        ServerPlayer p = player(h, ring(f));
        SectDisciple foe = near(h, f, "ham_doyun");
        SectDisciple mentor = npc(h, f, "gyeong_pil", ring(f).add(0, 0, -2.5));
        h.assertTrue(SectBouts.begin(p, foe, SectBouts.Mode.TRAIN), "bout refused");
        SectBouts.active(p).taken = 4;
        SectBouts.active(p).dealt = 1;
        SectBouts.ended(p, foe, false);
        h.assertTrue(SectBoutSession.of(p).pending.containsKey(p.getUUID()), "one analysis expected");
        SectDefeat.Run run = SectDefeat.run(p);
        h.assertTrue(run != null && run.drill == SectDefeat.Drill.FOOTWORK, "drill not chosen");
        SectDefeat.Pending first = SectBoutSession.of(p).pending.get(p.getUUID());
        // A second loss the same day does not add another drill, and the first drill stays.
        SectBouts.Bout again = new SectBouts.Bout(foe.getUUID(), "ham_doyun", SectBouts.Mode.TRAIN, h.getLevel().getGameTime(), 1.0);
        again.dealt = 1;
        SectDefeat.analyse(p, foe, again);
        h.assertTrue(SectDefeat.run(p).drill == SectDefeat.Drill.FOOTWORK, "the day's drill was replaced by another loss");
        h.runAfterDelay(80, () -> {
            SectDefeat.tick(p);
            h.assertTrue(!SectBoutSession.of(p).pending.containsKey(p.getUUID()), "the mentor did not speak");
            h.assertTrue(mentor.bubbleKey().startsWith("murim.sect.defeat.reason."), "the mentor said no reason: " + mentor.bubbleKey());
            SectDefeat.tick(p);
            h.assertTrue(!SectBoutSession.of(p).pending.containsKey(p.getUUID()), "the mentor came a second time");
            h.assertTrue(says(SectJourney.line(p), "murim.sect.defeat.progress"), "the drill is not in the journal");
            TechniqueProgress before = p.getData(ModAttachments.MASTERY).techniques().get(SectService.SIX);
            SectDefeat.add(p, SectDefeat.run(p), SectDefeat.Drill.FOOTWORK.need());
            TechniqueProgress after = p.getData(ModAttachments.MASTERY).techniques().get(SectService.SIX);
            h.assertTrue(after.stars() > before.stars() || after.progress() > before.progress(), "the drill gave no mastery");
            h.assertTrue(SectDefeat.run(p) == null, "the finished drill stayed open");
            h.succeed();
        });
        h.assertTrue(first != null, "pending lost");
    }

    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 60)
    public static void meditationDrillCountsMeditationTicks(GameTestHelper h) {
        day(h);
        Flat f = flat(h);
        ServerPlayer p = player(h, ring(f));
        SectDisciple foe = near(h, f, "ham_doyun");
        h.assertTrue(SectBouts.begin(p, foe, SectBouts.Mode.TRAIN), "bout refused");
        SectBouts.active(p).qiStart = 0.9D;
        SectBouts.active(p).qiLow = 0.02D;
        SectBouts.ended(p, foe, false);
        h.assertTrue(SectDefeat.run(p) != null && SectDefeat.run(p).drill == SectDefeat.Drill.MEDITATE, "no meditation drill after dry qi");
        p.setData(ModAttachments.MEDITATION, new MeditationState(true, 0, false, true, null, -1));
        for (int i = 0; i < SectDefeat.Drill.MEDITATE.need() - 1; i++) {
            SectDefeat.tick(p);
        }
        h.assertTrue(SectDefeat.run(p) != null, "drill ended early");
        SectDefeat.tick(p);
        h.assertTrue(SectDefeat.run(p) == null && SectService.state(p).entry(SectDefeat.DONE, -1) >= 0, "drill not recorded as done");
        h.succeed();
    }

    // ------------------------------------------------------------------ duel of two players

    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 200)
    public static void duelOnlyByConsentInARingWithSafeDamage(GameTestHelper h) {
        Flat f = flat(h);
        // The test server has PvP off; the duel rules must work where it is on.
        h.getLevel().getServer().setPvpAllowed(true);
        ServerPlayer a = player(h, ring(f));
        ServerPlayer b = player(h, ring(f).add(2, 0, 0));
        ServerPlayer c = player(h, ring(f).add(0, 0, 2));
        // With the default vanilla PvP policy, damage outside a duel lands.
        b.hurt(b.damageSources().playerAttack(a), 8.0F);
        h.assertTrue(b.getHealth() < 20.0F, "default vanilla PvP did not land");
        b.setHealth(20.0F);
        b.invulnerableTime = 0;
        // Not in the same ring: no request.
        Vec3 far = outsideRing(f);
        c.teleportTo(far.x, far.y, far.z);
        h.assertTrue(!SectDuel.request(a, c, f), "duel requested across rings");
        c.teleportTo(ring(f).x, ring(f).y, ring(f).z + 2);
        h.assertTrue(!SectDuel.accept(b, f), "accepted a request nobody made");
        h.assertTrue(SectDuel.request(a, b, f) && !SectDuel.inDuel(a), "a request started the duel");
        // The request lives 30 seconds.
        h.assertTrue(SectDuel.REQUEST_TICKS == 600, "request must live 30 s");
        SectBoutSession.of(b).requests.put(b.getUUID(), new SectDuel.Request(a, h.getLevel().getGameTime() - 1, ring(f)));
        h.assertTrue(!SectDuel.accept(b, f), "an expired request was accepted");
        h.assertTrue(SectDuel.request(a, b, f), "second request refused");
        UUID[] winner = new UUID[1];
        h.assertTrue(SectDuel.accept(b, f) && SectDuel.inDuel(a) && SectDuel.inDuel(b), "accepted request did not start the duel");
        // Replace the callback-less duel by one with a callback for the result.
        SectDuel.cancel(h.getLevel(), a.getUUID());
        SectDuel.start(a, b, ring(f), w -> winner[0] = w);
        // During the count the blows do not land.
        b.hurt(b.damageSources().playerAttack(a), 8.0F);
        h.assertTrue(b.getHealth() == 20.0F, "blow landed during the count");
        h.runAfterDelay(SectDuel.COUNTDOWN + 5, () -> {
            // A third party striking in stops the duel with no result and the blow does not land.
            ServerPlayer c2 = c;
            c2.teleportTo(ring(f).x, ring(f).y, ring(f).z + 3);
            boolean hit = b.hurt(b.damageSources().playerAttack(c2), 5.0F);
            h.assertTrue(!SectDuel.inDuel(a) && b.getHealth() == 20.0F, "interference did not stop the duel: pvp="
                    + h.getLevel().getServer().isPvpAllowed() + " hit=" + hit + " hp=" + b.getHealth() + " inDuel=" + SectDuel.inDuel(a));
            h.assertTrue(winner[0] == null, "interference gave a winner");
            // A new duel: damage is clamped at half, the loser yields, both end healthy.
            UUID[] w2 = new UUID[1];
            SectDuel.start(a, b, ring(f), w -> w2[0] = w);
            h.runAfterDelay(SectDuel.COUNTDOWN + 5, () -> {
                b.hurt(b.damageSources().playerAttack(a), 100.0F);
                h.assertTrue(!SectDuel.inDuel(a) && !SectDuel.inDuel(b), "the duel did not end");
                h.assertTrue(a.getUUID().equals(w2[0]), "wrong winner");
                h.assertTrue(b.isAlive() && b.getHealth() >= 10.0F, "the loser was not kept safe");
                h.assertTrue(b.getHealth() >= 10.0F && b.getHealth() <= b.getMaxHealth(), "health not restored");
                // Outside the duel again: vanilla damage.
                float hp = a.getHealth();
                a.hurt(a.damageSources().playerAttack(b), 8.0F);
                h.assertTrue(a.getHealth() < hp, "vanilla damage blocked after the duel");
                // Ring-out ends a duel.
                UUID[] w3 = new UUID[1];
                SectDuel.start(a, b, ring(f), w -> w3[0] = w);
                h.runAfterDelay(SectDuel.COUNTDOWN + 5, () -> {
                    Vec3 away = outsideRing(f).add(0, 0, 20);
                    b.teleportTo(away.x, away.y, away.z);
                    SectDuel.supervise(h.getLevel());
                    h.assertTrue(!SectDuel.inDuel(a) && a.getUUID().equals(w3[0]), "leaving the ring did not end the duel for the other's win");
                    h.getLevel().getServer().setPvpAllowed(false);
                    h.succeed();
                });
            });
        });
    }

    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 60)
    public static void leavingDuringTheDuelCountIsARefusal(GameTestHelper h) {
        Flat f = flat(h);
        ServerPlayer a = player(h, ring(f));
        ServerPlayer b = player(h, ring(f).add(2, 0, 0));
        UUID[] winner = {UUID.randomUUID()};
        SectDuel.start(a, b, ring(f), w -> winner[0] = w);
        Vec3 away = outsideRing(f).add(0, 0, 20);
        b.teleportTo(away.x, away.y, away.z);
        SectDuel.supervise(h.getLevel());
        h.assertTrue(!SectDuel.inDuel(a) && winner[0] == null, "leaving during the count was not a plain refusal");
        h.succeed();
    }

    // ------------------------------------------------------------------ the review

    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 100)
    public static void reviewTakesEightPlacesPlayersAndDisciplesTogether(GameTestHelper h) {
        Flat f = flat(h);
        long day = 1000;
        ServerPlayer a = player(h, ring(f));
        ServerPlayer b = player(h, ring(f).add(2, 0, 0));
        List<String> one = SectReview.bracket(day, List.of(SectReview.playerKey(a)));
        h.assertTrue(one.size() == 4 && one.contains(SectReview.playerKey(a)), "one player: four places expected");
        List<String> two = SectReview.bracket(day, List.of(SectReview.playerKey(a), SectReview.playerKey(b)));
        h.assertTrue(two.size() == 8 && new java.util.HashSet<>(two).size() == 8, "two players: eight different places expected");
        for (int i = 0; i < 8; i += 2) {
            h.assertTrue(!(SectReview.isPlayerKey(two.get(i)) && SectReview.isPlayerKey(two.get(i + 1))), "players meet each other in round one");
        }
        List<String> odd = SectReview.bracket(day, List.of(SectReview.playerKey(a), SectReview.playerKey(b), "p:" + UUID.randomUUID()));
        h.assertTrue(odd.size() == 8 && odd.stream().filter(SectReview::isPlayerKey).count() == 3, "odd players are filled with disciples");
        a.setData(ModAttachments.SECT, member().with(SectReview.SIGNED));
        b.setData(ModAttachments.SECT, member().with(SectReview.SIGNED));
        SectReview.State s = SectReview.begin(h.getLevel(), f, day, List.of(a, b), 48.0D);
        h.assertTrue(s.size() == 8 && s.bouts().size() == 4, "round one must have four bouts");
        h.assertTrue(s.bouts().stream().map(x -> x.ring).distinct().count() == 4, "four bouts need four different rings");
        h.assertTrue(!SectService.state(a).has(SectReview.SIGNED), "sign-up not consumed");
        int rounds = 0;
        while (SectReview.state(h.getLevel()) != null && rounds++ < 5) {
            for (SectReview.Bout x : List.copyOf(s.bouts())) {
                if (x.winner().isEmpty()) {
                    // Disciples win so that no player key pollutes the saved champion.
                    SectReview.decide(h.getLevel(), s, x, SectReview.isPlayerKey(x.a) ? x.b : x.a, "test");
                }
            }
            SectReview.step(h.getLevel(), f);
        }
        h.assertTrue(s.bouts().size() == 7 && s.done(), "eight places mean seven bouts, got " + s.bouts().size());
        h.assertTrue(SectReview.state(h.getLevel()) == null && SectLife.data(h.getLevel()).lastReview() == day, "review not closed");
        h.succeed();
    }

    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 60)
    public static void latePlayerIsReplacedByADiscipleAndSignUpOpensTheDayBefore(GameTestHelper h) {
        Flat f = flat(h);
        ServerPlayer a = player(h, ring(f));
        a.setData(ModAttachments.SECT, member().with(SectStanding.LESSON_ONE));
        SectReview.State s = SectReview.begin(h.getLevel(), f, 1001, a, 48.0D);
        SectReview.Bout mine = s.current(SectReview.playerKey(a));
        String key = SectReview.playerKey(a);
        String sub = SectReview.substitute(h.getLevel(), s, mine, key);
        h.assertTrue(!SectReview.isPlayerKey(sub) && s.bouts().stream().noneMatch(x -> x.has(sub)), "the replacement is not a fresh disciple: " + sub);
        SectReview.abort(h.getLevel(), "murim.sect.review.closed");
        // Sign-up only the day before (or the day of).
        long review = SectSchedule.day(h.getLevel().getDayTime());
        review += SectReview.daysUntil(review * 24000L);
        h.getLevel().setDayTime((review - 3) * 24000L + 3000);
        SectReview.signUp(a);
        h.assertTrue(!SectService.state(a).has(SectReview.SIGNED), "signed up three days ahead");
        h.getLevel().setDayTime((review - 1) * 24000L + 3000);
        SectReview.signUp(a);
        h.assertTrue(SectService.state(a).has(SectReview.SIGNED), "could not sign up the day before");
        // The morning reminder is sent once.
        h.getLevel().setDayTime(review * 24000L + 1000);
        SectSiteData data = SectLife.data(h.getLevel());
        data.session.remindedDay = Long.MIN_VALUE;
        SectReview.remind(h.getLevel(), data, review * 24000L + 1000, review);
        h.assertTrue(data.session.remindedDay == review, "no morning reminder");
        h.getLevel().setDayTime((review + 1) * 24000L + 2000L);
        h.succeed();
    }

    // ------------------------------------------------------------------ review 18.10

    /** Sneak + right click on the player who asked answers the request: the duel begins (the compare was player against uuid). */
    @GameTest(template = YARD, batch = "sect_duel_fixes", timeoutTicks = 60)
    public static void shiftClickOnTheAskerAcceptsTheDuel(GameTestHelper h) {
        Flat f = flat(h);
        ServerPlayer a = player(h, ring(f));
        ServerPlayer b = player(h, ring(f).add(2, 0, 0));
        SectDuel.shiftClick(a, b, f);
        h.assertTrue(SectBoutSession.of(b).requests.containsKey(b.getUUID()) && !SectDuel.inDuel(a), "the first click did not ask");
        SectDuel.shiftClick(b, a, f);
        h.assertTrue(SectDuel.inDuel(a) && SectDuel.inDuel(b), "the answering click did not start the duel");
        h.assertTrue(!SectBoutSession.of(b).requests.containsKey(b.getUUID()), "the answered request stayed");
        SectDuel.cancel(h.getLevel(), a.getUUID());
        h.succeed();
    }

    /** A click on somebody who did not ask is a new request, not an answer. */
    @GameTest(template = YARD, batch = "sect_duel_fixes", timeoutTicks = 60)
    public static void shiftClickOnAStrangerAsksInsteadOfAccepting(GameTestHelper h) {
        Flat f = flat(h);
        ServerPlayer a = player(h, ring(f));
        ServerPlayer b = player(h, ring(f).add(2, 0, 0));
        ServerPlayer c = player(h, ring(f).add(0, 0, 2));
        SectDuel.shiftClick(a, b, f);
        SectDuel.shiftClick(b, c, f);
        h.assertTrue(!SectDuel.inDuel(a) && !SectDuel.inDuel(b) && !SectDuel.inDuel(c), "a click on a stranger started a duel");
        h.assertTrue(SectBoutSession.of(c).requests.containsKey(c.getUUID()), "the click on a stranger asked nothing");
        SectBoutSession.of(a).requests.clear();
        h.succeed();
    }

    /** Requests that ran out are forgotten even when no duel is going on (the sweep used to return first). */
    @GameTest(template = YARD, batch = "sect_duel_fixes", timeoutTicks = 60)
    public static void expiredRequestsAreForgottenWithNoDuelRunning(GameTestHelper h) {
        Flat f = flat(h);
        ServerPlayer a = player(h, ring(f));
        ServerPlayer b = player(h, ring(f).add(2, 0, 0));
        SectBoutSession session = SectBoutSession.of(a);
        session.duels.clear();
        long now = h.getLevel().getGameTime();
        session.requests.put(b.getUUID(), new SectDuel.Request(a, now - 1, ring(f)));
        SectDuel.supervise(h.getLevel());
        h.assertTrue(!session.requests.containsKey(b.getUUID()), "an expired request was kept with no duel running");
        session.requests.put(b.getUUID(), new SectDuel.Request(a, now + SectDuel.REQUEST_TICKS, ring(f)));
        SectDuel.supervise(h.getLevel());
        h.assertTrue(session.requests.containsKey(b.getUUID()), "a live request was dropped");
        session.requests.clear();
        h.succeed();
    }

    /** The ring a bout began in is the ring: stepping into the next one over is leaving it (counted from the ring beside). */
    @GameTest(template = YARD, batch = BATCH, timeoutTicks = 100)
    public static void steppingIntoTheNeighbourRingIsLeavingTheBoutsRing(GameTestHelper h) {
        day(h);
        Flat f = flat(h);
        ServerPlayer p = player(h, ring(f));
        SectDisciple s = near(h, f, seniors(h).get(0));
        h.assertTrue(SectBouts.begin(p, s, SectBouts.Mode.CHALLENGE), "challenge refused");
        h.assertTrue(SectBouts.active(p).ring != null && SectBouts.active(p).ring.distanceToSqr(ring(f)) < 0.25D, "the bout did not note its ring");
        Vec3 next = f.at("training", SectSchedule.RINGS[1][0] + 1.5D, SectSchedule.RINGS[1][1]);
        h.assertTrue(SectBouts.ringCentre(f, next) != null, "setup: the point is not inside the next ring");
        p.teleportTo(next.x, next.y, next.z);
        SectBouts.supervise(p, SectBouts.active(p));
        h.assertTrue(SectBouts.active(p) == null && s.spar() == SectDisciple.Spar.NONE, "the next ring over counted as the same ring");
        h.succeed();
    }

    /**
     * The mentor of a defeat analysis walks up to the player on his own (the old walk never met its own beat). Its own batch:
     * tests of one batch run side by side, and two "gyeong_pil" in one level make SectLife.duplicate() discard one of them
     * by random UUID, which broke the other mentor test now and then.
     */
    @GameTest(template = YARD, batch = "sect_defeat_walkup", timeoutTicks = 60)
    public static void mentorWalksUpToThePlayerBeforeHeSpeaks(GameTestHelper h) {
        day(h);
        Flat f = flat(h);
        ServerPlayer p = player(h, ring(f));
        SectDisciple mentor = npc(h, f, "gyeong_pil", ring(f).add(0, 0, -15.0D));
        long now = h.getLevel().getGameTime();
        SectBoutSession.of(p).pending.put(p.getUUID(), new SectDefeat.Pending("gyeong_pil", SectDefeat.Reason.HITS,
                SectDefeat.Drill.FOOTWORK, 4, 1, 3.0D, 80, now, now));
        SectDefeat.tick(p);
        h.assertTrue(SectBoutSession.of(p).pending.containsKey(p.getUUID()), "the mentor spoke from across the square");
        h.assertTrue(mentor.blocking() && "defeat".equals(mentor.approachKind()), "the mentor did not set out to the player: blocking="
                + mentor.blocking() + " kind=" + mentor.approachKind());
        SectBoutSession.of(p).pending.remove(p.getUUID());
        h.succeed();
    }
}
