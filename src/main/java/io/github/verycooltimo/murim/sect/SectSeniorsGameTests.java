package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SeatStyle;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.*;

/** Round 3 fixtures (elders-1..8). Same rig idea as the teaching tests: a private session, no teleports by the executor. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectSeniorsGameTests {
    private SectSeniorsGameTests() { }

    private record Yard(Vec3 origin, SectLessons session) implements SectLayout {
        public SectLessons lessons(net.minecraft.server.level.ServerLevel level) { return session; }
        public SectLayoutFile file() { return null; }
        public Vec3 at(String zone, double u, double v) { return origin.add(u * .2, 0, v * .2); }
        public float yaw(double u, double v) { return SectLayout.yawOf(u, v); }
        public double[] half(String zone) { return new double[]{40, 40}; }
        public double[] local(String zone, Vec3 at) { return new double[]{(at.x - origin.x) * 5, (at.z - origin.z) * 5}; }
    }

    private static final class Rig {
        final GameTestHelper h;
        final SectLessons s = new SectLessons();
        final List<SectDisciple> people = new ArrayList<>();
        final List<ServerPlayer> players = new ArrayList<>();
        final Yard yard;
        Rig(GameTestHelper h, SectSchedule.Period period) {
            this.h = h;
            SectGameTests.setPeriod(h, period);
            yard = new Yard(SectLife.stand(h.getLevel(), h.absoluteVec(new Vec3(10.5, 2, 12.5))), s);
        }
        SectDisciple npc(String key, SectSchedule.Kind kind, Vec3 at) {
            SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
            d.setMember(SectRoster.of(key).orElseThrow()); d.setLayout(yard); d.setKeepAwake(true); d.setNoAi(true);
            d.moveTo(at.x, at.y, at.z, 0, 0); d.setDoingKind(kind); h.getLevel().addFreshEntity(d); people.add(d); return d;
        }
        ServerPlayer player(Vec3 at, SectState state) {
            ServerPlayer p = SectGameTests.fakePlayer(h); p.setData(ModAttachments.SECT, state);
            p.moveTo(at.x, at.y, at.z, 0, 0); players.add(p); return p;
        }
        ServerPlayer player(Vec3 at) { return player(at, SectState.NONE.joined()); }
        void sample() { s.sample(h.getLevel(), people, players); }
        Vec3 o(double dx, double dz) { return yard.origin.add(dx, 0, dz); }
        long day() { return SectSchedule.day(h.getLevel().getDayTime()); }
    }
    private static SectState elder() {
        return SectState.NONE.joined().with(SectPositions.ELDER).with("pass.main_hall").with("pass.scriptures").with("pass.treasury").with("pass.elders");
    }

    // ------------------------------------------------------------------ elders-4

    @GameTest(template = "sect_yard", batch = "elders_greet")
    public static void seatedElderGreetsOncePerPlayer(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.TRAINING);
        var d = r.npc("gyeong_cho", SectSchedule.Kind.GRIND, r.yard.origin); // not the owner of a guarded room
        var a = r.player(r.o(2, 0)); var b = r.player(r.o(3, 0));
        r.sample();
        h.assertTrue(d.attending() == a && d.bowedToday(a, r.day()), "the seated elder did not greet the first player");
        h.assertTrue(!d.bowedToday(b, r.day()), "the second player's greeting was spent");
        // the elder finishes with the first (a pause of 30-40 ticks), then the second player is served
        h.runAfterDelay(50, () -> {
            r.sample();
            h.assertTrue(d.attending() == b && d.bowedToday(b, r.day()), "the second player was not served after the first: attending=" + d.attending() + " bowedB=" + d.bowedToday(b, r.day()) + " avail=" + SectLessons.available(d) + " free=" + d.free() + " engaged=" + r.s.engaged(d));
            h.succeed();
        });
    }

    @GameTest(template = "sect_yard", batch = "elders_leader")
    public static void leaderStandsForElderOffice(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.TRAINING);
        var d = r.npc("tae_hwi", SectSchedule.Kind.RECEIVE, r.yard.origin); d.sit(SeatStyle.LOTUS);
        r.player(r.o(2, 0), elder());
        h.assertTrue(d.sitting(), "fixture: head not seated");
        r.sample();
        h.assertTrue(!d.sitting() && d.attending() != null, "the head did not rise for an elder");
        h.assertTrue(d.bubbleKey().startsWith("murim.bubble.social.elder_peer."), "no office greeting: " + d.bubbleKey());
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "elders_council")
    public static void councilDoesNotGetInterrupted(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.TRAINING);
        var d = r.npc("tae_rok", SectSchedule.Kind.COUNCIL, r.yard.origin);
        r.player(r.o(2, 0)); r.sample();
        h.assertTrue(d.attending() == null && d.bubbleKey().isEmpty(), "a council member was interrupted");
        h.succeed();
    }

    // ------------------------------------------------------------------ elders-1

    @GameTest(template = "sect_yard", batch = "elders_voice")
    public static void elderWorkKindHasSpeech(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.TRAINING);
        var d = r.npc("tae_gyun", SectSchedule.Kind.COUNT, r.yard.origin);
        var herb = r.npc("gyeong_cho", SectSchedule.Kind.GRIND, r.o(0, 30));
        r.player(r.o(8, 0)); r.sample(); r.s.seniors.expedite();
        h.runAfterDelay(2, () -> {
            r.sample();
            h.assertTrue(d.bubbleKey().startsWith("murim.bubble.senior_affairs."), "no work speech: " + d.bubbleKey());
            h.assertTrue(herb.bubbleKey().isEmpty(), "an elder out of earshot spoke");
            h.succeed();
        });
    }

    @GameTest(template = "sect_yard", batch = "elders_reply")
    public static void elderReplyStaysInSameRoom(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.TRAINING);
        var d = r.npc("tae_gyun", SectSchedule.Kind.COUNT, r.yard.origin);
        var near = r.npc("tae_rok", SectSchedule.Kind.READ, r.o(3, 0));
        var far = r.npc("tae_seong", SectSchedule.Kind.WORK, r.o(14, 0));
        r.player(r.o(0, 8)); r.sample(); r.s.seniors.expedite();
        h.runAfterDelay(2, () -> {
            r.sample();
            h.assertTrue(near.pendingKey() != null && near.pendingKey().startsWith("murim.bubble.senior_affairs."), "the neighbour did not answer");
            h.assertTrue(far.pendingKey() == null || far.pendingKey().isEmpty(), "a far elder answered across the sect");
            h.succeed();
        });
    }

    @GameTest(template = "sect_yard", batch = "elders_quiet")
    public static void councilAndRevereStayQuiet(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.EVENING);
        var head = r.npc("tae_hwi", SectSchedule.Kind.REVERE, r.yard.origin);
        var elder = r.npc("tae_rok", SectSchedule.Kind.COUNCIL, r.o(2, 0));
        r.player(r.o(4, 0)); r.sample(); r.s.seniors.expedite();
        h.runAfterDelay(2, () -> {
            r.sample();
            h.assertTrue(head.bubbleKey().isEmpty() && elder.bubbleKey().isEmpty(), "reverence or council talk was broken");
            h.succeed();
        });
    }

    @GameTest(template = "sect_yard", batch = "elders_listeners")
    public static void twoListenersDoNotDoubleChatter(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.TRAINING);
        var d = r.npc("tae_gyun", SectSchedule.Kind.COUNT, r.yard.origin);
        r.player(r.o(8, 0)); r.player(r.o(9, 1)); r.sample(); r.s.seniors.expedite();
        h.runAfterDelay(2, () -> {
            r.sample();
            String first = d.bubbleKey(); long at = d.bubbleAt();
            h.assertTrue(first.startsWith("murim.bubble.senior_affairs."), "no speech");
            h.runAfterDelay(4, () -> { r.sample(); h.assertTrue(d.bubbleAt() == at, "a second listener caused a second line"); h.succeed(); });
        });
    }

    // ------------------------------------------------------------------ elders-7

    private static void walking(SectDisciple s, Vec3 to) {
        s.getNavigation().moveTo(to.x, to.y, to.z, 1.0D);
        s.setDeltaMovement(0.12D, 0, 0);
    }

    @GameTest(template = "sect_yard", batch = "elders_yield", timeoutTicks = 200)
    public static void porterYieldsToWalkingElder(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.TRAINING);
        var elder = r.npc("tae_rok", SectSchedule.Kind.WORK, r.yard.origin);
        var porter = r.npc("porter_jang", SectSchedule.Kind.CARRY, r.o(2, 0));
        r.player(r.o(0, 6));
        walking(elder, r.o(10, 0)); r.sample();
        h.assertTrue(SectSocial.yielding(porter), "the porter did not step aside");
        h.assertTrue(porter.getPersistentData().getCompound("murim_sect_yield").getDouble("z") != porter.getZ(), "no side step");
        // he steps to the point, then bows, then goes on
        Vec3 spot = new Vec3(porter.getPersistentData().getCompound("murim_sect_yield").getDouble("x"),
                porter.getPersistentData().getCompound("murim_sect_yield").getDouble("y"), porter.getPersistentData().getCompound("murim_sect_yield").getDouble("z"));
        porter.moveTo(spot.x, spot.y, spot.z, 0, 0);
        h.runAfterDelay(3, () -> {
            r.sample();
            h.assertTrue(porter.poseHeld(), "no bow after the step");
            h.assertTrue(porter.doingKind() == SectSchedule.Kind.CARRY, "the porter lost his work");
            h.succeed();
        });
    }

    @GameTest(template = "sect_yard", batch = "elders_cliff")
    public static void yieldNeverChoosesCliff(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.TRAINING);
        var elder = r.npc("tae_rok", SectSchedule.Kind.WORK, r.yard.origin);
        var porter = r.npc("porter_jang", SectSchedule.Kind.CARRY, r.o(2, 0));
        BlockPos at = porter.blockPosition();
        for (int dz = -2; dz <= 2; dz++) if (dz != 0) for (int dy = 1; dy <= 6; dy++) {
            h.getLevel().setBlockAndUpdate(at.offset(0, -dy, dz), Blocks.AIR.defaultBlockState());
        }
        for (int dz = -2; dz <= 2; dz++) if (dz != 0) h.getLevel().setBlockAndUpdate(at.offset(0, 0, dz), Blocks.AIR.defaultBlockState());
        r.player(r.o(0, 6)); walking(elder, r.o(10, 0)); r.sample();
        h.assertTrue(!SectSocial.yielding(porter), "the porter stepped toward a drop");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "elders_seated")
    public static void seatedDiscipleDoesNotLeaveSeat(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.DINNER);
        var elder = r.npc("tae_rok", SectSchedule.Kind.WORK, r.yard.origin);
        var eater = r.npc("yul_jin", SectSchedule.Kind.EAT, r.o(2, 0)); eater.sit(SeatStyle.LOTUS);
        r.player(r.o(0, 6)); walking(elder, r.o(10, 0)); r.sample();
        h.assertTrue(eater.sitting() && !SectSocial.yielding(eater), "the seated pupil left his seat");
        h.assertTrue(eater.attending() == elder, "he did not even pause to nod");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "elders_two")
    public static void twoSeniorsDoNotCauseOscillation(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.TRAINING);
        var a = r.npc("tae_rok", SectSchedule.Kind.WORK, r.yard.origin);
        var b = r.npc("tae_seong", SectSchedule.Kind.WORK, r.o(0, 1));
        var j = r.npc("yul_jin", SectSchedule.Kind.CHORE, r.o(2, 0.5));
        r.player(r.o(0, 6)); walking(a, r.o(10, 0)); walking(b, r.o(10, 1)); r.sample();
        Object first = j.getPersistentData().getCompound("murim_sect_yield").copy();
        r.s.seniors.hashCode();
        h.runAfterDelay(2, () -> {
            r.sample();
            h.assertTrue(first.equals(j.getPersistentData().getCompound("murim_sect_yield")), "a second senior re-aimed the junior");
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ elders-3

    @GameTest(template = "sect_yard", batch = "elders_owner", timeoutTicks = 200)
    public static void ownerNoticesJuniorInOwnRoom(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.TRAINING);
        var owner = r.npc("tae_gyun", SectSchedule.Kind.COUNT, r.yard.origin);
        var p = r.player(r.o(3, 0)); SectState before = SectService.state(p);
        r.sample();
        h.assertTrue(owner.bubbleKey().isEmpty(), "reacted before the dwell time");
        h.runAfterDelay(35, () -> {
            r.sample();
            h.assertTrue(owner.bubbleKey().startsWith("murim.bubble.owner_visit."), "no gentle request: " + owner.bubbleKey());
            h.assertTrue(SectService.state(p).equals(before), "the request changed the player's standing");
            h.succeed();
        });
    }

    @GameTest(template = "sect_yard", batch = "elders_trusted", timeoutTicks = 200)
    public static void trustedVisitorGetsSeat(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.TRAINING);
        var owner = r.npc("tae_gyun", SectSchedule.Kind.COUNT, r.yard.origin);
        r.player(r.o(3, 0), elder()); r.sample();
        h.runAfterDelay(35, () -> {
            r.sample();
            h.assertTrue(owner.bubbleKey().startsWith("murim.bubble.social.") || owner.bubbleKey().startsWith("murim.bubble.lesson_invite."),
                    "no welcome for a trusted visitor: " + owner.bubbleKey());
            h.succeed();
        });
    }

    @GameTest(template = "sect_yard", batch = "elders_adjacent")
    public static void adjacentRoomDoesNotTrigger(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.TRAINING);
        var owner = r.npc("tae_gyun", SectSchedule.Kind.COUNT, r.yard.origin);
        r.player(r.o(9, 0)); r.sample();
        h.runAfterDelay(35, () -> { r.sample(); h.assertTrue(owner.bubbleKey().isEmpty() && owner.attending() == null, "the owner reacted to a far passer-by"); h.succeed(); });
    }

    @GameTest(template = "sect_yard", batch = "elders_noguard", timeoutTicks = 260)
    public static void missingGuardDoesNotBlockOwnerReaction(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.TRAINING);
        var owner = r.npc("tae_gyun", SectSchedule.Kind.COUNT, r.yard.origin);
        r.player(r.o(3, 0)); r.sample();
        h.runAfterDelay(35, () -> r.sample());
        h.runAfterDelay(110, () -> { r.sample(); h.assertTrue(owner.bubbleKey().startsWith("murim.bubble.owner_visit."), "owner silent without guards"); h.succeed(); });
    }

    // ------------------------------------------------------------------ elders-5 / 6 / 8

    @GameTest(template = "sect_yard", batch = "elders_office")
    public static void officeAcceptanceRecordsEventOnce(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.TRAINING);
        var head = r.npc("tae_hwi", SectSchedule.Kind.RECEIVE, r.yard.origin);
        var p = r.player(r.o(2, 0), elder());
        SectSeniors.officeAccepted(p, head);
        h.assertTrue(SectSeniors.live(p, SectSeniors.OFFICE_UP), "the appointment was not recorded");
        h.assertTrue(head.bubbleKey().startsWith("murim.bubble.social."), "the head did not congratulate in office words");
        h.assertTrue(SectSocial.situation(p, r.npc("yul_jin", SectSchedule.Kind.CHORE, r.o(4, 0))).equals("office_elder"), "juniors still use the old address");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "elders_expire")
    public static void eventExpires(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.TRAINING);
        var p = r.player(r.o(2, 0), elder());
        p.setData(ModAttachments.SECT_MEMORY, p.getData(ModAttachments.SECT_MEMORY).withEvent(SectSeniors.OFFICE_UP, h.getLevel().getGameTime() - SectEvents.LIVE - 1));
        h.assertTrue(!SectSeniors.live(p, SectSeniors.OFFICE_UP), "the appointment news never expires");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "elders_meet", timeoutTicks = 100)
    public static void councilHostMeetsAtDoor(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.EVENING);
        var head = r.npc("tae_hwi", SectSchedule.Kind.REVERE, r.yard.origin);
        var p = r.player(r.o(18, 0), elder());
        SectSeniors.officeAccepted(p, null);
        r.sample();
        h.assertTrue("teacher:milestone".equals(head.approachKind()), "the head did not set out to meet the player: " + head.approachKind());
        // an ignored or refused invitation releases him and costs nothing
        SectApproach.decline(p, head);
        h.assertTrue(head.approachKind() == null, "the head stayed bound to a refused meeting");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "elders_far")
    public static void distantPlayerDoesNotDragLeaderAcrossSect(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.EVENING);
        var head = r.npc("tae_hwi", SectSchedule.Kind.REVERE, r.yard.origin);
        var p = r.player(r.o(60, 0), elder());
        SectSeniors.officeAccepted(p, null); r.sample();
        h.assertTrue(head.approachKind() == null, "the head crossed the sect for a distant player");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "elders_host_queue")
    public static void twoInvitationsShareOneHostQueue(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.EVENING);
        var head = r.npc("tae_hwi", SectSchedule.Kind.REVERE, r.yard.origin);
        var a = r.player(r.o(18, 0), elder()); var b = r.player(r.o(18, 3), elder());
        SectSeniors.officeAccepted(a, null); SectSeniors.officeAccepted(b, null);
        r.sample();
        h.assertTrue(head.blockTarget() == a.getId() || head.blockTarget() == b.getId(), "no host assigned");
        int id = head.blockTarget();
        r.s.seniors.hashCode();
        h.runAfterDelay(3, () -> { r.sample(); h.assertTrue(head.blockTarget() == id, "the host was re-assigned mid-walk"); h.succeed(); });
    }

    @GameTest(template = "sect_yard", batch = "elders_topic")
    public static void councilChoosesLiveTopicAndFreezesIt(GameTestHelper h) {
        Rig r = new Rig(h, SectSchedule.Period.TRAINING);
        var p = r.player(r.o(2, 0), elder());
        h.getLevel().addNewPlayer(p);
        long day = h.getLevel().getDayTime();
        h.assertTrue(r.s.seniors.councilTopic(h.getLevel(), day).equals("affairs") || r.s.seniors.councilTopic(h.getLevel(), day).equals("review"),
                "a quiet sect has a news topic");
        SectSeniors.officeAccepted(p, null);
        h.assertTrue(!r.s.seniors.councilTopic(h.getLevel(), day).equals("office"), "the topic changed in the middle of the sitting");
        r.s.seniors.councilTopic(h.getLevel(), day + 24000);
        h.assertTrue(r.s.seniors.councilTopic(h.getLevel(), day + 24000).equals("office"), "the next sitting ignored the fresh appointment");
        r.s.seniors.councilAnnounced(h.getLevel(), day + 24000);
        h.assertTrue(SectSeniors.live(p, "announced_" + SectSeniors.OFFICE_UP), "the news was not marked announced");
        h.succeed();
    }
}
