package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reactions to events and weather (docs/design/39-player-path-v2.md, «Реплики: объём и частота»): an event is answered once per
 * person and by at most three people, an old event is not answered, and the words follow the rank of the player (nobody
 * lectures an elder). Real attachments, real people; no client channel needed.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectEventsGameTests {
    private static final String YARD = "sect_yard";

    private SectEventsGameTests() { }

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

    /** A member of the sect; {@code office}: none, first or elder (set the way the author does it). */
    private static ServerPlayer player(GameTestHelper h, String office, int realmRank) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        Vec3 at = SectLife.stand(h.getLevel(), h.absoluteVec(new Vec3(8.5D, 2.0D, 12.5D)));
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

    /** The player may be answered again at once (the test steps over the twenty seconds between two reactions). */
    private static void rest(ServerPlayer p) {
        p.setData(ModAttachments.SECT_MEMORY, p.getData(ModAttachments.SECT_MEMORY).withStamp("gap", Long.MIN_VALUE / 2));
    }

    /** A late arrival cannot turn an already completed deed into something he witnessed. */
    @GameTest(template = YARD, timeoutTicks = 40, batch = "sect_voice_witness_snapshot")
    public static void lateArrivalCannotPraiseAnUnseenDeed(GameTestHelper h) {
        ServerPlayer p = player(h, "none", 3);
        SectDisciple witness = npc(h, "gyeong_on", 11, 12);
        h.assertTrue(witness.getSensing().hasLineOfSight(p), "fixture sight blocked");
        long now = h.getLevel().getGameTime();
        h.assertTrue(!witness.speechGuard().witnessed("bout_won", now), "deed invented before the bout");
        SectEvents.record(p, SectEvents.Event.BOUT_WON);
        h.assertTrue(witness.speechGuard().witnessed("bout_won", now), "visible deed not witnessed");
        SectDisciple late = npc(h, "gyeong_ha", 12, 13);
        h.assertTrue(late.getSensing().hasLineOfSight(p), "late arrival fixture sight blocked");
        h.assertTrue(!late.speechGuard().witnessed("bout_won", now), "late arrival inherited a witness receipt");
        h.assertTrue(!SectChatter.send(late, "murim.react.bout_won.senior.1",
                net.minecraft.network.chat.Component.translatable("murim.react.bout_won.senior.1")), "unseen deed praised");
        h.assertTrue(!witness.speechGuard().witnessed("bout_won", now + 1201), "witness receipt outlived sixty seconds");
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 40, batch = "sect_voice_occluded_witness")
    public static void aWallAtEventTimePreventsLaterPraise(GameTestHelper h) {
        ServerPlayer p = player(h, "none", 3);
        SectDisciple witness = npc(h, "gyeong_on", 11, 12);
        net.minecraft.core.BlockPos wall = net.minecraft.core.BlockPos.containing(
                (witness.getX() + p.getX()) / 2, p.getEyeY(), p.getZ());
        // API: 1.21.1 GameTestHelper#getLevel, Level#setBlockAndUpdate, BlockPos#containing,
        // Block#defaultBlockState and Sensing#tick (read-only /tmp/mr-ref-1121/minecraft-src).
        h.getLevel().setBlockAndUpdate(wall, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        witness.getSensing().tick();
        h.assertTrue(!witness.getSensing().hasLineOfSight(p), "fixture wall did not block the sight ray");
        SectEvents.record(p, SectEvents.Event.BOUT_WON);
        h.getLevel().setBlockAndUpdate(wall, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        witness.getSensing().tick();
        h.assertTrue(witness.getSensing().hasLineOfSight(p), "fixture sight did not reopen");
        h.assertTrue(!witness.speechGuard().witnessed("bout_won", h.getLevel().getGameTime()),
                "current sight was mistaken for event-time sight");
        h.succeed();
    }

    // ------------------------------------------------------------------ event reactions

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_lines_event_once")
    public static void aReactionFiresOncePerPersonAndStopsAtThree(GameTestHelper h) {
        long savedTime = h.getLevel().getDayTime();
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        ServerPlayer p = player(h, "none", 3);
        List<SectDisciple> people = List.of(npc(h, "cook_kim", 11, 12), npc(h, "yul_ak", 12, 11), npc(h, "seo_gyu", 12, 13),
                npc(h, "gyeong_on", 13, 12));
        h.assertTrue(SectEvents.react(p) == null, "a reaction with nothing to react to");
        SectEvents.record(p, SectEvents.Event.BOUT_WON);
        Set<String> who = new HashSet<>();
        for (int i = 0; i < SectEvents.MAX_REACTORS; i++) {
            rest(p);
            SectDisciple d = SectEvents.react(p);
            h.assertTrue(d != null, "nobody answered a won bout, reaction " + i);
            h.assertTrue(who.add(d.memberKey()), d.memberKey() + " answered the same bout twice");
            h.assertTrue(d.bubbleKey().startsWith("murim.react.bout_won." + SectVoice.register(d, SectRank.of(p)).id() + "."),
                    d.memberKey() + " said " + d.bubbleKey());
            // quiet a moment so the next one speaks (the fourth person was never asked)
        }
        h.assertTrue(SectEvents.reacted(p, SectEvents.Event.BOUT_WON, people.get(0)) || SectEvents.reacted(p, SectEvents.Event.BOUT_WON, people.get(1))
                || SectEvents.reacted(p, SectEvents.Event.BOUT_WON, people.get(2)), "the answers were not remembered");
        rest(p);
        h.assertTrue(SectEvents.react(p) == null, "a fourth person answered one bout");
        // a new occurrence clears it: the same people may answer the next bout
        SectEvents.record(p, SectEvents.Event.BOUT_WON);
        rest(p);
        for (SectDisciple d : people) {
            d.markBubble("");
        }
        h.assertTrue(!SectEvents.reacted(p, SectEvents.Event.BOUT_WON, people.get(0)), "the mask survived a new occurrence");
        // two reactions inside twenty seconds are one too many
        p.setData(ModAttachments.SECT_MEMORY, p.getData(ModAttachments.SECT_MEMORY).withStamp("gap", p.level().getGameTime()));
        h.assertTrue(SectEvents.react(p) == null, "two reactions within the gap");
        h.getLevel().setDayTime(savedTime);
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_lines_event_expiry")
    public static void anOldEventIsNotAnsweredAndAnOutsiderGetsNothing(GameTestHelper h) {
        long savedTime = h.getLevel().getDayTime();
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        ServerPlayer p = player(h, "none", 3);
        npc(h, "yul_ak", 11, 12);
        // an event of two days ago
        long now = h.getLevel().getGameTime();
        p.setData(ModAttachments.SECT_MEMORY, p.getData(ModAttachments.SECT_MEMORY).withEvent("bout_lost", now - 2 * SectEvents.LIVE));
        h.assertTrue(!SectEvents.live(p, SectEvents.Event.BOUT_LOST), "an event of two days ago is alive");
        rest(p);
        h.assertTrue(SectEvents.react(p) == null, "an old event was answered");
        // a stranger is not the sect's business
        ServerPlayer stranger = SectGameTests.fakePlayer(h);
        Vec3 at = SectLife.stand(h.getLevel(), h.absoluteVec(new Vec3(8.5D, 2.0D, 12.5D)));
        stranger.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        SectEvents.record(stranger, SectEvents.Event.REALM_UP);
        h.assertTrue(SectEvents.react(stranger) == null, "the sect reacted to an outsider");
        // a player in the middle of a sentence is not cheered
        ServerPlayer punished = player(h, "none", 3);
        punished.setData(ModAttachments.SECT, SectService.state(punished).with(io.github.verycooltimo.murim.sect.seal.PenanceService.SUMMONED));
        SectEvents.record(punished, SectEvents.Event.BOUT_WON);
        h.assertTrue(SectEvents.react(punished) == null, "a disciplined member was cheered");
        h.getLevel().setDayTime(savedTime);
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 200, batch = "sect_lines_event_rank")
    public static void reactionsFollowTheRankOfThePlayerAndNobodyLecturesAnElder(GameTestHelper h) {
        long savedTime = h.getLevel().getDayTime();
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        String[][] voices = {{"cook_kim", "lay"}, {"yul_ak", "third"}, {"seo_gyu", "second"}, {"gyeong_on", "first"}, {"tae_rok", "elder"}};
        for (SectEvents.Event event : List.of(SectEvents.Event.BOUT_WON, SectEvents.Event.BOUT_LOST, SectEvents.Event.REALM_UP,
                SectEvents.Event.STAR_UP, SectEvents.Event.CONTRACT, SectEvents.Event.RETURNED)) {
            for (String office : List.of("none", "first", "elder")) {
                for (String[] v : voices) {
                    ServerPlayer p = player(h, office, 3);
                    SectDisciple d = npc(h, v[0], 11, 12);
                    SectEvents.record(p, event);
                    SectDisciple spoke = SectEvents.react(p);
                    h.assertTrue(spoke == d, v[0] + " did not react to " + event + " of a " + office);
                    String register = d.bubbleKey().substring(("murim.react." + event.id() + ".").length()).split("\\.")[0];
                    String expected = switch (office) {
                        case "none" -> v[1].equals("lay") ? "lay" : v[1].equals("third") ? "peer" : "senior";
                        default -> v[1].equals("first") || v[1].equals("elder") ? "colleague" : "up";
                    };
                    h.assertTrue(register.equals(expected), v[0] + " spoke to a " + office + " in the register " + register + ", not " + expected);
                    // an elder or a first disciple never hears the words made for a disciple
                    if (!office.equals("none")) {
                        h.assertTrue(!register.equals("lay") && !register.equals("peer") && !register.equals("senior"), "a lecture for the " + office);
                    }
                    d.discard();
                }
            }
        }
        h.getLevel().setDayTime(savedTime);
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_lines_event_weather")
    public static void thunderAndTheNightAreAnsweredOncePerStormAndNight(GameTestHelper h) {
        long savedTime = h.getLevel().getDayTime();
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        ServerPlayer p = player(h, "none", 3);
        SectDisciple a = npc(h, "yul_ak", 11, 12);
        SectDisciple b = npc(h, "seo_gyu", 12, 12);
        h.assertTrue(SectEvents.react(p) == null, "a reaction on a calm day");
        h.getLevel().setThunderLevel(1.0F);
        h.getLevel().setRainLevel(1.0F);
        h.assertTrue(h.getLevel().isThundering(), "the setup is not a storm");
        SectDisciple first = SectEvents.react(p);
        h.assertTrue(first != null && first.bubbleKey().startsWith("murim.bubble.thunder."), "nobody spoke of the thunder: " + first);
        rest(p);
        SectDisciple second = SectEvents.react(p);
        h.assertTrue(second != null && second != first, "the same person spoke of the same storm twice");
        rest(p);
        h.assertTrue(SectEvents.react(p) == null, "a third person spoke although only two are near");
        h.getLevel().setThunderLevel(0.0F);
        h.getLevel().setRainLevel(0.0F);
        // the night: an awake person says a word to a wanderer, in the register of the rank
        SectGameTests.setPeriod(h, SectSchedule.Period.NIGHT);
        // The people who are awake at night stand the watch (the rest sleep, and a sleeper has nothing to say).
        long day = SectSchedule.day(h.getLevel().getDayTime());
        npc(h, SectRota.nightWatch(day, 0).key(), 11, 13);
        npc(h, SectRota.nightWatch(day, 1).key(), 12, 13);
        a.markBubble("");
        b.markBubble("");
        rest(p);
        SectDisciple night = SectEvents.react(p);
        h.assertTrue(night != null && night.bubbleKey().startsWith("murim.react.night_pass."), "nobody greeted the night wanderer: " + night);
        h.getLevel().setDayTime(savedTime);
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_lines_event_return")
    public static void aReturnIsAnEventAndTheMentorWhoScoldedDoesNotRepeatIt(GameTestHelper h) {
        long savedTime = h.getLevel().getDayTime();
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        ServerPlayer p = player(h, "none", 3);
        SectDisciple pil = npc(h, "gyeong_pil", 11, 12);
        SectDisciple ak = npc(h, "yul_ak", 12, 12);
        // away for three days, then back inside the land
        long now = h.getLevel().getGameTime();
        SectAbsence.track(p, true, now, 1_000L);
        h.assertTrue(SectAbsence.track(p, true, now + 3 * 24000L + 5, 1_000L + 1_000L) == 3, "three days away were not noticed");
        h.assertTrue(SectEvents.live(p, SectEvents.Event.RETURNED), "a return is not an event");
        h.assertTrue(SectAbsence.meet(p, pil), "the mentor said nothing");
        h.assertTrue(SectEvents.reacted(p, SectEvents.Event.RETURNED, pil), "the mentor will say it twice");
        pil.markBubble("");
        rest(p);
        SectDisciple other = SectEvents.react(p);
        h.assertTrue(other == ak && ak.bubbleKey().startsWith("murim.react.returned."), "the others did not welcome him back: " + other);
        h.getLevel().setDayTime(savedTime);
        h.succeed();
    }
}
