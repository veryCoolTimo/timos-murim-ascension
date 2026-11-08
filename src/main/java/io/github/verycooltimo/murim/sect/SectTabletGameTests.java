package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.network.SectTabletData;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The jade sect tablet: handed over once, a snapshot of real sect data, and the mentor's replacement. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectTabletGameTests {
    private SectTabletGameTests() { }

    private static ServerPlayer player(GameTestHelper h) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        Vec3 at = h.absoluteVec(new Vec3(12.5, 2, 12.5));
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.xOld = p.getX();
        p.zOld = p.getZ();
        p.setOnGround(true);
        return p;
    }

    private static boolean mark(SectTabletData d, String id) {
        return d.marks().stream().anyMatch(m -> m.id().equals(id) && m.done());
    }

    private static java.util.List<String> keys(java.util.List<net.minecraft.network.chat.Component> list) {
        return list.stream().map(c -> c.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t ? t.getKey() : "?").toList();
    }

    private static int tablets(ServerPlayer p) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) {
            if (s.is(ModItems.JADE_SECT_TABLET.get())) n += s.getCount();
        }
        return n;
    }

    private static void drop(ServerPlayer p) {
        for (int i = 0; i < p.getInventory().items.size(); i++) {
            if (p.getInventory().items.get(i).is(ModItems.JADE_SECT_TABLET.get())) p.getInventory().items.set(i, ItemStack.EMPTY);
        }
    }

    private static SectDisciple mentor(GameTestHelper h, ServerPlayer p) {
        SectDisciple npc = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        npc.setMember(SectRoster.of("gyeong_pil").orElseThrow());
        npc.moveTo(p.getX() + 1, p.getY(), p.getZ(), 0, 0);
        npc.setNoAi(true);
        npc.setKeepAwake(true);
        h.getLevel().addFreshEntity(npc);
        return npc;
    }

    private static Dialogue.Option lostOption(SectDisciple mentor) {
        return DialogueLoader.get(mentor.dialogue()).nodes().get("again").options().stream()
                .filter(o -> o.actions().stream().anyMatch(a -> a.type().equals("tablet_replace")))
                .findFirst().orElseThrow();
    }

    @GameTest(template = "sect_yard", batch = "sect_tablet", timeoutTicks = 100)
    public static void tabletIsGivenOnceAtJoining(GameTestHelper h) {
        ServerPlayer p = player(h);
        h.assertTrue(tablets(p) == 0, "outsider already has a tablet");
        SectService.join(p);
        h.assertTrue(tablets(p) == 1 && SectService.state(p).has(SectTablet.GIVEN), "joining did not hand out one tablet");
        // Joining, the arrival path and the periodic check never hand out a second one, even without the item.
        h.assertTrue(!SectTablet.ensure(p), "second hand-over");
        drop(p);
        h.assertTrue(!SectTablet.ensure(p) && tablets(p) == 0, "a lost tablet came back without the mentor");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_tablet", timeoutTicks = 100)
    public static void existingMemberGetsOneOnNextLogin(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setData(ModAttachments.SECT, SectService.state(p).joined());
        h.assertTrue(tablets(p) == 0 && !SectService.state(p).has(SectTablet.GIVEN), "setup: member without a tablet");
        h.assertTrue(SectTablet.ensure(p) && tablets(p) == 1, "older member did not get a tablet");
        h.assertTrue(!SectTablet.ensure(p) && tablets(p) == 1, "older member got a second tablet");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_tablet", timeoutTicks = 200)
    public static void mentorIsScannedOnceNotPerSnapshot(GameTestHelper h) {
        ServerPlayer p = player(h);
        SectService.join(p);
        SectDisciple m = mentor(h, p);
        int before = SectTablet.mentorScans;
        float[] first = null;
        long t0 = System.nanoTime();
        for (int i = 0; i < 40; i++) {
            SectTabletData d = SectTablet.collect(p);
            if (first == null) first = d.mentorAt();
            h.assertTrue(d.mentorAt() != null && d.mentorAt()[0] == first[0], "mentor marker lost or moved");
        }
        long us = (System.nanoTime() - t0) / 1000;
        int scans = SectTablet.mentorScans - before;
        MurimMod.LOGGER.info("TABLETPERF 40 collects: {} mentor area scans (was 40), {} us total; poll every {} ticks (was 5)", scans, us, SectTablet.POLL_TICKS);
        h.assertTrue(scans <= 1, "mentor area scan repeated: " + scans);
        h.assertTrue(!SectTablet.pollDue(4) && SectTablet.pollDue(23), "poll gate");
        m.discard();
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_tablet", timeoutTicks = 200)
    public static void snapshotCarriesRealSectData(GameTestHelper h) {
        ServerPlayer p = player(h);
        SectService.join(p);
        SectAttendance.record(p, SectAttendance.Activity.FORMATION);
        SectAttendance.record(p, SectAttendance.Activity.MEAL);
        SectService.contribute(p, 12);
        SectTabletData d = SectTablet.collect(p);
        SectJourney.Goal goal = SectJourney.next(p);
        h.assertTrue(mark(d, "formation") && mark(d, "meal") && !mark(d, "lesson"), "today's marks differ from the sect log");
        h.assertTrue(d.period() == SectSchedule.at(p.level().getDayTime()).ordinal(), "period index");
        h.assertTrue(d.day() == SectSchedule.day(p.level().getDayTime()) + 1, "day number");
        h.assertTrue(d.merit() == SectService.state(p).merit() && d.contribution() == SectService.state(p).contribution(), "merit");
        h.assertTrue(d.standing() == SectService.standing(p).ordinal(), "standing");
        h.assertTrue(d.goalName().getString().equals(net.minecraft.network.chat.Component.translatable("murim.sect.journey.goal." + goal.id()).getString()),
                "goal differs from SectJourney.next: " + goal.id());
        h.assertTrue(d.approvalStreak() == SectReview.APPROVAL_STREAK && d.needed() == SectBouts.NEEDED, "constants");
        h.assertTrue(d.offers().size() <= 3 && d.active() == null, "contract board or active contract");
        // The snapshot survives the network codec untouched.
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        SectTabletData.CODEC.encode(buf, d);
        SectTabletData back = SectTabletData.CODEC.decode(buf);
        h.assertTrue(back.day() == d.day() && mark(back, "formation") && mark(back, "meal") && back.merit() == d.merit()
                        && back.goalName().getString().equals(d.goalName().getString()) && back.offers().size() == d.offers().size(),
                "snapshot changed on the wire");
        // The sect treasury (doc 39, sect repairs): the number the Standing tab shows, and the chronicle of big repairs, on the wire too.
        io.github.verycooltimo.murim.sect.repair.RepairData repairs = io.github.verycooltimo.murim.sect.repair.RepairData.get(h.getLevel());
        h.assertTrue(d.treasury() == io.github.verycooltimo.murim.sect.repair.RepairCosts.liang(repairs.treasury()) && d.treasury() >= 0,
                "the tablet shows " + d.treasury() + " liang, the treasury holds " + repairs.treasury() + " wen");
        h.assertTrue(back.treasury() == d.treasury() && back.chronicle().size() == d.chronicle().size(), "the treasury changed on the wire");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_tablet", timeoutTicks = 200)
    public static void mentorReplacesLostTabletForMerit(GameTestHelper h) {
        ServerPlayer p = player(h);
        SectService.join(p);
        SectDisciple mentor = mentor(h, p);
        Dialogue.Option option = lostOption(mentor);
        // Still carrying one: the option is hidden and nothing changes.
        h.assertTrue(!DialogueService.applyHeadless(p, mentor, option) && tablets(p) == 1, "offered a second tablet");
        SectService.contribute(p, 12);
        int merit = SectService.state(p).merit();
        drop(p);
        h.assertTrue(DialogueService.applyHeadless(p, mentor, option), "lost tablet was not replaced");
        h.assertTrue(tablets(p) == 1, "no new tablet");
        h.assertTrue(SectService.state(p).merit() == merit - SectTablet.REPLACE_COST, "price was not taken");
        h.assertTrue(!DialogueService.applyHeadless(p, mentor, option) && tablets(p) == 1, "option still shown with a tablet");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_tablet", timeoutTicks = 200)
    public static void poorMemberStillGetsTabletWithoutNegativeBalance(GameTestHelper h) {
        ServerPlayer p = player(h);
        SectService.join(p);
        SectDisciple mentor = mentor(h, p);
        Dialogue.Option option = lostOption(mentor);
        drop(p);
        int merit = SectService.state(p).merit();
        h.assertTrue(merit < SectTablet.REPLACE_COST, "setup: merit not low, " + merit);
        h.assertTrue(DialogueService.applyHeadless(p, mentor, option) && tablets(p) == 1, "poor member was refused");
        h.assertTrue(SectService.state(p).merit() == 0 && SectService.state(p).merit() >= 0, "balance went wrong: " + SectService.state(p).merit());
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_tablet", timeoutTicks = 200)
    public static void goalReminderPulsesOnPeriodStartAndWhenNearThePerson(GameTestHelper h) {
        long saved = h.getLevel().getDayTime();
        try {
            ServerPlayer p = player(h);
            SectService.join(p);
            h.getLevel().setDayTime(6000);
            h.assertTrue(SectJourney.pulseDue(p), "no reminder right after joining");
            h.assertTrue(!SectJourney.pulseDue(p), "the reminder repeats without a trigger");
            // a new sect period starts: the reminder shows again once
            h.getLevel().setDayTime(9500);
            h.assertTrue(SectJourney.pulseDue(p) && !SectJourney.pulseDue(p), "a new period did not pulse exactly once");
            // walking up to the person the goal names shows it once more
            String person = SectJourney.next(p).person();
            SectDisciple npc = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
            npc.setMember(SectRoster.of(person).orElseThrow());
            npc.moveTo(p.getX() + 3, p.getY(), p.getZ(), 0, 0);
            npc.setNoAi(true);
            npc.setKeepAwake(true);
            h.getLevel().addFreshEntity(npc);
            h.assertTrue(SectJourney.pulseDue(p) && !SectJourney.pulseDue(p), "nearing the person did not pulse exactly once");
            // the journal's switch silences it
            SectJourney.toggleHud(p);
            h.getLevel().setDayTime(13500);
            h.assertTrue(!SectJourney.pulseDue(p), "pulsed while the reminder was switched off");
            h.succeed();
        } finally {
            h.getLevel().setDayTime(saved);
        }
    }

    @GameTest(template = "sect_yard", batch = "sect_tablet", timeoutTicks = 20)
    public static void tabletScaleKeepsTheWorldVisible(GameTestHelper h) {
        int[][] windows = {{1920, 1080, 3}, {1920, 1080, 2}, {2560, 1440, 4}, {2560, 1440, 3}, {854, 480, 2}, {1280, 720, 2}};
        for (int[] w : windows) {
            double pixels = io.github.verycooltimo.murim.network.SectTabletFit.pixelsPerTexel(w[0], w[1]);
            double share = pixels * io.github.verycooltimo.murim.network.SectTabletFit.W / w[0];
            h.assertTrue(share <= 0.651, "tablet takes " + share + " of the width at " + w[0] + "x" + w[1]);
            if (w[0] >= 1280) h.assertTrue(pixels >= 2 && Math.abs(pixels - Math.round(pixels)) < 1e-9, "not a whole pixel scale at " + w[0]);
            float factor = io.github.verycooltimo.murim.network.SectTabletFit.guiFactor(w[0], w[1], w[2]);
            h.assertTrue(Math.abs(factor * w[2] - pixels) < 1e-4, "gui factor does not map to the pixel scale");
        }
        h.succeed();
    }

    private static void ready(ServerPlayer p, int rank) {
        // This late-journey fixture has already received and read the basic footwork book.
        p.setData(ModAttachments.SECT, SectService.state(p).with("lesson.seven_stars"));
        for (var form : io.github.verycooltimo.murim.technique.Styles.SEVEN_STARS.forms())
            p.setData(ModAttachments.MASTERY, p.getData(ModAttachments.MASTERY)
                    .with(form, io.github.verycooltimo.murim.mastery.TechniqueProgress.learned(0, 12)));
        p.setData(ModAttachments.PROFILE, io.github.verycooltimo.murim.profile.DantianProfile.INITIAL.withTags("pure", "six").withRank(rank));
        p.setData(ModAttachments.MASTERY, p.getData(ModAttachments.MASTERY)
                .with(SectJourney.PETAL, io.github.verycooltimo.murim.mastery.TechniqueProgress.learned(0, 6))
                .with(SectJourney.SEVEN, io.github.verycooltimo.murim.mastery.TechniqueProgress.learned(0, 10))
                .with(SectService.SIX, io.github.verycooltimo.murim.mastery.TechniqueProgress.learned(2, 6)));
    }

    @GameTest(template = "sect_yard", batch = "sect_tablet", timeoutTicks = 200)
    public static void positionAppearsAfterTheCommandAndTheChainFollowsTheJourney(GameTestHelper h) {
        ServerPlayer p = player(h);
        SectService.join(p);
        // A trusted player on the road to the next breakthrough: the chain is the real path around the goal.
        ready(p, 2);
        p.setData(ModAttachments.SECT, SectService.state(p).with(SectStanding.LESSON_ONE).with(SectStanding.LESSON_TWO)
                .with(SectProgression.DIRECT).with(SectStanding.APPROVAL).contribute(200).with("request.pills.done"));
        SectTabletData t = SectTablet.collect(p);
        h.assertTrue(t.position() == 0 && t.standing() == SectStanding.TRUSTED.ordinal(), "trusted player: position " + t.position() + " standing " + t.standing());
        // The road ahead stays hidden: two done steps, the current one and only the next, then a flag that more follows.
        h.assertTrue(keys(t.chain()).equals(java.util.List.of("murim.tablet.step.standing", "murim.tablet.step.approval",
                "murim.tablet.step.rank_three", "murim.tablet.step.first"))
                && t.chainCurrent() == 2 && t.chainMore(), "trusted chain: " + keys(t.chain()) + " at " + t.chainCurrent());
        h.assertTrue(t.approved(), "the leader's approval is not shown");
        // The author's command makes an elder: the office is sent with its title, the chain moves on to his duties.
        h.getLevel().getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(2), "murim dev sect position elder");
        ready(p, 4);
        SectTabletData e = SectTablet.collect(p);
        h.assertTrue(e.position() == 2 && e.office(), "elder command did not reach the tablet data");
        h.assertTrue(e.positionTitle().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc
                && tc.getKey().equals("murim.sect.position.elder"), "position title: " + e.positionTitle());
        h.assertTrue(keys(e.chain()).equals(java.util.List.of("murim.tablet.step.peak", "murim.tablet.step.elder",
                "murim.tablet.step.peak_stage", "murim.tablet.step.free_practice"))
                && e.chainCurrent() == 2 && e.chainMore(), "elder chain: " + keys(e.chain()) + " at " + e.chainCurrent());
        // No disciple-only goal for an office holder, even one made by a command at a low rank.
        ready(p, 1);
        String goal = SectJourney.next(p).id();
        h.assertTrue(!java.util.List.of("learn_six", "take_six", "practice_six", "submit_six", "learn_petal", "take_spar", "clean_hits",
                "submit_spar", "learn_seven", "request", "partners", "ceremony", "direct", "standing", "approval").contains(goal),
                "an elder was given the disciple goal " + goal);
        // The wire keeps the new fields.
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        SectTabletData.CODEC.encode(buf, e);
        SectTabletData back = SectTabletData.CODEC.decode(buf);
        h.assertTrue(back.position() == 2 && keys(back.chain()).equals(keys(e.chain())) && back.chainCurrent() == e.chainCurrent()
                && back.marks().equals(e.marks()), "snapshot lost the position or the chain on the wire");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_tablet", timeoutTicks = 20)
    public static void ladderShowsHeldRungsAndOnlyTheNextOne(GameTestHelper h) {
        // standing 0..4 without an office: the held rung and one above; nothing higher is drawn
        for (int standing = 0; standing <= 4; standing++) {
            h.assertTrue(io.github.verycooltimo.murim.network.SectTabletView.top(standing, 0) == standing + 1, "standing " + standing + " reveals " + io.github.verycooltimo.murim.network.SectTabletView.top(standing, 0));
        }
        h.assertTrue(io.github.verycooltimo.murim.network.SectTabletView.top(4, 0) == 5, "trusted does not see the first disciple's rung as the next");
        h.assertTrue(io.github.verycooltimo.murim.network.SectTabletView.top(2, 0) < 4, "a disciple can see the trusted rung");
        h.assertTrue(io.github.verycooltimo.murim.network.SectTabletView.top(4, 1) == 6 && io.github.verycooltimo.murim.network.SectTabletView.top(4, 2) == 6,
                "offices: first sees the elder rung next, an elder sees the top");
        h.assertTrue(io.github.verycooltimo.murim.network.SectTabletView.held(0, 2) == 6 && io.github.verycooltimo.murim.network.SectTabletView.held(1, 1) == 5,
                "an office outranks the standing");
        // the chain of a player at the very start shows two done steps at most and one ahead
        ServerPlayer p = player(h);
        SectService.join(p);
        SectTabletData t = SectTablet.collect(p);
        h.assertTrue(t.chain().size() <= 4 && t.chainCurrent() <= 2 && t.chainCurrent() < t.chain().size() && t.chain().size() - t.chainCurrent() <= 2,
                "the chain shows more than the next step: " + keys(t.chain()) + " at " + t.chainCurrent());
        h.succeed();
    }

    /** A client that asks for a refresh over and over is answered once per cooldown: collecting the data is not free. */
    @GameTest(template = "sect_yard", batch = "sect_tablet", timeoutTicks = 60)
    public static void refreshRequestsAreRateLimitedPerPlayer(GameTestHelper h) {
        ServerPlayer p = player(h);
        SectService.join(p);
        SectTablet.watch(p, true);
        h.assertTrue(SectTablet.has(p), "setup: the member has no tablet");
        int before = SectTablet.pushes(p);
        SectTablet.act(p, "refresh");
        h.assertTrue(SectTablet.pushes(p) == before + 1, "the first refresh was not answered");
        for (int i = 0; i < 30; i++) {
            SectTablet.act(p, "refresh");
        }
        h.assertTrue(SectTablet.pushes(p) == before + 1, "a flood of refresh requests was answered " + (SectTablet.pushes(p) - before) + " times");
        // another player is not slowed down by this one
        ServerPlayer other = player(h);
        SectService.join(other);
        SectTablet.watch(other, true);
        int otherBefore = SectTablet.pushes(other);
        SectTablet.act(other, "refresh");
        h.assertTrue(SectTablet.pushes(other) == otherBefore + 1, "the cooldown is shared between players");
        h.runAfterDelay(SectTablet.REFRESH_COOLDOWN + 1, () -> {
            SectTablet.act(p, "refresh");
            h.assertTrue(SectTablet.pushes(p) == before + 2, "no refresh was answered after the cooldown");
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ nothing hardcoded (docs/adr/0016)

    /**
     * The tablet's map is the built sect, not a picture: at each of the four quarter turns of the site the buildings' footprints
     * and the boards the snapshot carries are exactly the shipped plan carried through the site's own mapping (local u, v to the
     * world), and every footprint lies inside the box of the map.
     */
    @GameTest(template = "sect_yard", batch = "sect_tablet", timeoutTicks = 60)
    public static void mapOfTheTabletFollowsTheSiteAtEveryRotation(GameTestHelper h) {
        SectLayoutFile shipped = SectLayoutFile.shipped();
        h.assertTrue(shipped != null, "the shipped layout is missing");
        var frame = shipped.frame();
        var fileSite = io.github.verycooltimo.murim.world.hua.MountHuaSite.placement(frame.centerX(), frame.centerZ(), frame.baseY(), frame.rotation());
        double gate = -1;
        for (int rotation = 0; rotation < 4; rotation++) {
            var site = io.github.verycooltimo.murim.world.hua.MountHuaSite.placement(1200 + 137 * rotation, -3400 + 91 * rotation, 70 + rotation, rotation);
            SectLayoutFile file = SectLayoutFile.anchored(site);
            h.assertTrue(file != null, "no anchored layout at rotation " + rotation);
            java.util.Map<String, java.util.Set<SectTabletData.Box>> expected = new java.util.HashMap<>();
            for (SectLayoutFile.Building b : shipped.buildings().values()) {
                String key = b.id().matches("elder_\\d+") ? "elders" : b.id();
                for (SectLayoutFile.Rect r : b.boxes()) {
                    int[] a = site.toWorld(fileSite.localU(r.x0(), r.z0()), fileSite.localV(r.x0(), r.z0()));
                    int[] c = site.toWorld(fileSite.localU(r.x1(), r.z1()), fileSite.localV(r.x1(), r.z1()));
                    expected.computeIfAbsent(key, k -> new java.util.HashSet<>()).add(new SectTabletData.Box(
                            Math.min(a[0], c[0]), Math.min(a[1], c[1]), Math.max(a[0], c[0]), Math.max(a[1], c[1])));
                }
            }
            var labels = SectTablet.mapLabels(file);
            h.assertTrue(labels.size() == expected.size(), "rotation " + rotation + ": " + labels.size() + " labels, the plan has " + expected.size());
            SectMap.Area area = SectMap.areaFor(file);
            h.assertTrue(area != null, "no map box at rotation " + rotation);
            for (SectTabletData.MapLabel l : labels) {
                h.assertTrue(new java.util.HashSet<>(l.parts()).equals(expected.get(l.key())),
                        "rotation " + rotation + ": footprint of " + l.key() + " is " + l.parts() + ", the plan turns to " + expected.get(l.key()));
                for (SectTabletData.Box part : l.parts()) {
                    h.assertTrue(area.contains(part.x0(), part.z0()) && area.contains(part.x1(), part.z1()),
                            "rotation " + rotation + ": " + l.key() + " lies outside the map box");
                    h.assertTrue(part.x0() >= l.x0() && part.z0() >= l.z0() && part.x1() <= l.x1() && part.z1() <= l.z1(), l.key() + " part outside its own box");
                }
            }
            var boards = SectTablet.boardMarks(file);
            h.assertTrue(boards.size() == shipped.boards().size() && !boards.isEmpty(), "boards at rotation " + rotation);
            for (int i = 0; i < boards.size(); i++) {
                var b = shipped.boards().get(i);
                int[] w = site.toWorld(fileSite.localU(b.getX(), b.getZ()), fileSite.localV(b.getX(), b.getZ()));
                h.assertTrue(boards.get(i)[0] == w[0] + 0.5F && boards.get(i)[1] == w[1] + 0.5F,
                        "rotation " + rotation + ": board " + i + " is at " + boards.get(i)[0] + "," + boards.get(i)[1] + ", the plan turns to " + w[0] + "," + w[1]);
            }
            // the sect is turned as a whole: the gate keeps its distance to the main hall
            var gateLabel = labels.stream().filter(l -> l.key().equals("sect_gate")).findFirst().orElseThrow();
            var hall = labels.stream().filter(l -> l.key().equals("main_hall")).findFirst().orElseThrow();
            double d = Math.hypot((gateLabel.x0() + gateLabel.x1()) / 2.0 - (hall.x0() + hall.x1()) / 2.0, (gateLabel.z0() + gateLabel.z1()) / 2.0 - (hall.z0() + hall.z1()) / 2.0);
            h.assertTrue(gate < 0 || Math.abs(d - gate) < 1.5, "rotation " + rotation + ": gate to hall is " + d + ", was " + gate);
            gate = d;
            // and the snapshot's wire format carries the footprints
            var snap = SectTablet.collect(player(h));
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
            SectTabletData.CODEC.encode(buf, withLabels(snap, labels));
            h.assertTrue(SectTabletData.CODEC.decode(buf).labels().equals(labels), "footprints changed on the wire at rotation " + rotation);
        }
        h.succeed();
    }

    private static SectTabletData withLabels(SectTabletData d, java.util.List<SectTabletData.MapLabel> labels) {
        return new SectTabletData(d.day(), d.period(), d.marks(), d.fullDays(), d.approvalStreak(), d.approved(), d.bonus(), d.standing(),
                d.position(), d.positionTitle(), d.rank(), d.realm(), d.goalName(), d.goalHint(), d.goalPerson(), d.goalDone(), d.goalTotal(),
                d.chain(), d.chainCurrent(), d.chainMore(), d.offers(), d.active(), d.merit(), d.contribution(), d.wins(), d.needed(),
                d.challengesOpen(), d.bouts(), d.beaten(), d.reviewDays(), d.canSign(), d.signupOpen(), d.signed(), d.mentor(), d.teacher(),
                d.you(), d.mentorAt(), d.bed(), d.boards(), d.mapVersion(), d.mapX0(), d.mapZ0(), d.mapW(), d.mapH(), labels, d.contracts(),
                d.treasury(), d.chronicle(), d.shop());
    }

    /**
     * An open tablet is live: a part of the day done, the hour of the day, the player walking, the mentor walking and merit earned
     * each reach the client as a push within the next check, and when nothing changed nothing is pushed.
     */
    @GameTest(template = "sect_yard", batch = "sect_tablet", timeoutTicks = 200)
    public static void openTabletPushesEveryChangeItShows(GameTestHelper h) {
        long saved = h.getLevel().getDayTime();
        try {
            ServerPlayer p = player(h);
            SectService.join(p);
            SectTablet.watch(p, true);
            h.assertTrue(SectTablet.pushIfChanged(p), "the first check did not send the data");
            int n = SectTablet.pushes(p);
            h.assertTrue(!SectTablet.pushIfChanged(p) && SectTablet.pushes(p) == n, "a check without a change pushed");
            // a part of the day is done: the mark changes
            h.assertTrue(!mark(SectTablet.collect(p), "formation"), "setup: formation already done");
            SectAttendance.record(p, SectAttendance.Activity.FORMATION);
            h.assertTrue(SectTablet.pushes(p) == n + 1 && mark(SectTablet.collect(p), "formation"), "the formation mark did not reach the client: pushes " + (SectTablet.pushes(p) - n));
            // merit
            SectService.contribute(p, 5);
            h.assertTrue(SectTablet.pushes(p) == n + 2, "merit earned was not pushed: " + (SectTablet.pushes(p) - n));
            // the hour of the day
            int period = SectTablet.collect(p).period();
            SectSchedule.Period other = SectSchedule.Period.values()[(period + 3) % SectSchedule.Period.values().length];
            SectGameTests.setPeriod(h, other);
            h.assertTrue(SectTablet.pushIfChanged(p) && SectTablet.collect(p).period() == other.ordinal(), "a new part of the day was not pushed");
            // the player walks
            int before = SectTablet.pushes(p);
            p.moveTo(p.getX() + 6, p.getY(), p.getZ() + 2, 0, 0);
            h.assertTrue(SectTablet.pushIfChanged(p), "walking was not pushed");
            var you = SectTablet.collect(p).you();
            h.assertTrue(you != null && you[0] == (float) p.getX() && you[1] == (float) p.getZ(), "the marker is not where the player is");
            // the mentor walks
            SectDisciple mentor = mentor(h, p);
            h.assertTrue(SectTablet.pushIfChanged(p), "the mentor's arrival was not pushed");
            var at = SectTablet.collect(p).mentorAt();
            h.assertTrue(at != null && at[0] == (float) mentor.getX() && at[1] == (float) mentor.getZ(), "the mentor's marker is not on the mentor");
            mentor.moveTo(mentor.getX() + 4, mentor.getY(), mentor.getZ(), 0, 0);
            h.assertTrue(SectTablet.pushIfChanged(p), "the mentor's walk was not pushed");
            var moved = SectTablet.collect(p).mentorAt();
            h.assertTrue(moved != null && moved[0] == (float) mentor.getX(), "the mentor's marker did not follow him");
            h.assertTrue(SectTablet.pushes(p) > before, "no pushes counted");
            // a closed screen gets nothing
            SectTablet.watch(p, false);
            int closed = SectTablet.pushes(p);
            SectService.contribute(p, 1);
            h.assertTrue(SectTablet.pushes(p) == closed, "a closed tablet was pushed to");
            // and a "refresh" request from an open screen puts a lost flag back
            SectTablet.act(p, "refresh");
            h.assertTrue(SectTablet.watching(p), "a refresh request from the open screen did not restore the watch");
            h.succeed();
        } finally {
            h.getLevel().setDayTime(saved);
        }
    }

    /** A fresh member's snapshot holds only what is true for him: nothing done, nothing taken, nobody invited, no invented place. */
    @GameTest(template = "sect_yard", batch = "sect_tablet", timeoutTicks = 100)
    public static void freshMembersSnapshotInventsNothing(GameTestHelper h) {
        ServerPlayer p = player(h);
        SectService.join(p);
        SectTabletData d = SectTablet.collect(p);
        h.assertTrue(d.marks().stream().noneMatch(SectTabletData.Mark::done), "marks done for a fresh member");
        h.assertTrue(d.marks().stream().filter(m -> !m.task()).map(SectTabletData.Mark::id).toList().equals(java.util.List.of("formation", "meal", "lesson")), "marks " + d.marks());
        h.assertTrue(d.marks().stream().filter(SectTabletData.Mark::required).allMatch(m -> SectAttendance.ROLL_CALLED.stream().anyMatch(a -> a.id().equals(m.id()))),
                "a part of the day the sect does not call is counted: " + d.marks());
        var bearing = SectTablet.gateBearing(p);
        var tasks = d.marks().stream().filter(SectTabletData.Mark::task).toList();
        h.assertTrue(bearing.getString().isEmpty() ? tasks.isEmpty()
                : tasks.size() == 1 && tasks.getFirst().id().equals("gate")
                && tasks.getFirst().label().equals(bearing) && !tasks.getFirst().required(),
                "invented guidance task: " + tasks);
        h.assertTrue(d.fullDays() == 0 && !d.approved() && d.wins() == 0 && d.beaten().isEmpty() && d.bouts() == 0, "progress of a fresh member");
        h.assertTrue(d.offers().isEmpty() && d.active() == null, "contracts of a member who took none");
        h.assertTrue(d.contracts().invitable().isEmpty() && d.contracts().invite() == null && !d.contracts().reach(), "a board far away reports a reach or an invitation");
        h.assertTrue(d.mentorAt() == null, "a mentor marker with no mentor near");
        h.assertTrue(d.teacher().getString().isEmpty() && !d.signed() && !d.canSign(), "teacher or review of a fresh member");
        h.assertTrue(d.bonus() == SectAttendance.dayBonus(1), "bonus " + d.bonus());
        for (SectTabletData.MapLabel l : d.labels()) {
            h.assertTrue(SectLayoutFile.shipped().buildings().keySet().stream().anyMatch(id -> id.equals(l.key()) || (l.key().equals("elders") && id.startsWith("elder_"))),
                    "a building that is not in the plan: " + l.key());
        }
        // the sign-up window is the server's rule, not the screen's
        h.assertTrue(d.signupOpen() == SectReview.signupOpen(p), "sign-up window");
        // nothing on the snapshot names the capture stand's players
        String all = d.toString();
        h.assertTrue(!all.contains("Timo") && !all.contains("Min,") && !all.contains("00000000-0000-0000-0000-000000000001"), "demo data in the snapshot");
        h.succeed();
    }

    /** Each offer names the picture of its type and the biggest group; the numbers are the board's own. */
    @GameTest(template = "sect_yard", batch = "sect_tablet", timeoutTicks = 100)
    public static void offersCarryTheirTypesPictureAndGroup(GameTestHelper h) {
        ServerPlayer p = player(h);
        SectService.join(p);
        var server = h.getLevel().getServer();
        SectContracts.refresh(server);
        SectContracts.Data data = SectContracts.data(server);
        h.assertTrue(data.offers.size() == SectContracts.OFFERS_PER_DAY, "the board lists " + data.offers.size() + " offers");
        for (SectContracts.Offer o : data.offers) {
            SectTabletData.Offer line = SectTablet.offer(p, data, o, SectService.standing(p), SectService.state(p));
            h.assertTrue(line.icon().equals(SectTablet.icon(o.kind())) && line.icon().startsWith("big_"), "icon of " + o.kind() + ": " + line.icon());
            h.assertTrue(line.group() == (o.kind() == SectContracts.Kind.CAMP ? SectContracts.MAX_GROUP : 0), "group of " + o.kind() + ": " + line.group());
            h.assertTrue(line.kind() == o.kind().ordinal() && line.slot() == o.slot(), "kind or slot of " + o.kind());
            int reward = SectContracts.reward(o.kind(), SectService.standing(p), data.day, o.slot()).merit();
            h.assertTrue(line.merit() >= reward, "reward shown " + line.merit() + ", the board pays " + reward);
        }
        h.assertTrue(java.util.Arrays.stream(SectContracts.Kind.values()).map(SectTablet::icon).distinct().count() == SectContracts.Kind.values().length, "two contract types share a picture");
        h.succeed();
    }

    /** The merit exchange lines of the Standing tab are the catalog's stocked rows with the player's real stock. */
    @GameTest(template = "sect_yard", batch = "sect_tablet", timeoutTicks = 100)
    public static void shopLinesAreTheRealStock(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setData(ModAttachments.SECT, SectState.NONE.joined().with(SectStanding.LESSON_ONE).contribute(60));
        SectStanding standing = SectService.standing(p);
        java.util.List<MeritShop.Offer> rows = MeritShop.OFFERS.stream()
                .filter(o -> o.refill() != null && MeritShop.listed(o) && standing.atLeast(o.need()) && !o.item().isEmpty()).toList();
        h.assertTrue(!rows.isEmpty(), "setup: no stocked row for " + standing);
        java.util.List<SectTabletData.Shop> lines = SectTablet.collect(p).shop();
        h.assertTrue(lines.size() == rows.size(), "the tablet lists " + lines.size() + " rows, the catalog has " + rows.size());
        for (int i = 0; i < rows.size(); i++) {
            MeritStock.Level level = MeritShop.stock(p, rows.get(i));
            SectTabletData.Shop line = lines.get(i);
            h.assertTrue(line.have() == level.count() && line.max() == rows.get(i).refill().max()
                    && line.name().getString().equals(net.minecraft.network.chat.Component.translatable("murim.tablet.shop." + rows.get(i).id()).getString()),
                    "row " + rows.get(i).id() + ": tablet " + line.have() + "/" + line.max() + ", stock " + level.count());
        }
        // a purchase takes one from the stock and the next snapshot says so; the minutes to the next one appear
        MeritShop.Offer sword = MeritShop.offer("sword").orElseThrow();
        int before = lines.stream().filter(l -> l.name().getString().equals(net.minecraft.network.chat.Component.translatable("murim.tablet.shop.sword").getString())).findFirst().orElseThrow().have();
        h.assertTrue(MeritShop.buy(p, "sword") == MeritShop.Result.OK, "setup: the sword was not sold");
        var after = SectTablet.collect(p).shop().stream().filter(l -> l.name().getString().equals(net.minecraft.network.chat.Component.translatable("murim.tablet.shop.sword").getString())).findFirst().orElseThrow();
        h.assertTrue(after.have() == before - 1 && after.minutes() == MeritShop.minutesToNext(p, sword) && after.minutes() > 0,
                "after the purchase the tablet says " + after.have() + " in stock, " + after.minutes() + " min (was " + before + ")");
        h.succeed();
    }

    /** The full-day rule shown is the one the sect applies: what the roll call named, and the roll-call activities before it. */
    @GameTest(template = "sect_yard", batch = "sect_tablet", timeoutTicks = 100)
    public static void fullDayRuleIsTheSectsOwn(GameTestHelper h) {
        // before any roll call, the roll-call activities are what a full day will need
        SectAttendance.Day empty = new SectAttendance.Day(1, java.util.Set.of(), java.util.Set.of());
        long morning = SectSchedule.Period.FORMATION.start() + 100;
        h.assertTrue(SectAttendance.required(empty, SectAttendance.Activity.FORMATION, morning), "the line-up is not required in its window");
        h.assertTrue(SectAttendance.required(empty, SectAttendance.Activity.LESSON, morning), "the lesson is not required before its roll call");
        h.assertTrue(!SectAttendance.required(empty, SectAttendance.Activity.MEAL, morning), "a meal is counted that the sect never calls");
        // after the roll call, exactly what it named
        SectAttendance.Day called = new SectAttendance.Day(1, java.util.Set.of("formation"), java.util.Set.of("formation"));
        long dinner = SectSchedule.Period.DINNER.start() + 100;
        h.assertTrue(SectAttendance.required(called, SectAttendance.Activity.FORMATION, dinner) && !SectAttendance.required(called, SectAttendance.Activity.LESSON, dinner),
                "a lesson that was not called counts after the lessons are over");
        h.assertTrue(called.full(), "setup: a day with only the line-up called and done is full");
        h.assertTrue(SectAttendance.dayBonus(SectAttendance.STREAK_DAYS - 1) == SectAttendance.DAY_BONUS
                && SectAttendance.dayBonus(SectAttendance.STREAK_DAYS) == SectAttendance.DAY_BONUS + 1, "bonus rule");
        h.succeed();
    }

    /**
     * A sect with no board gets one at the layout's board spot, at each quarter turn of the site: facing the way local south looks,
     * hung on the wall there or on a one-block post when there is none; never over a block that is not air; once placed, a second
     * call changes nothing. Beside it the tablet's contracts tab lists the day's offers.
     */
    @GameTest(template = "sect_yard", batch = "sect_tablet", timeoutTicks = 200)
    public static void freshSectGetsItsBoardAtEveryRotation(GameTestHelper h) {
        SectLayoutFile shipped = SectLayoutFile.shipped();
        var frame = shipped.frame();
        var board = shipped.boards().get(0);
        net.minecraft.server.level.ServerLevel level = h.getLevel();
        net.minecraft.core.BlockPos spot = h.absolutePos(new net.minecraft.core.BlockPos(12, 2, 12));
        ServerPlayer p = player(h);
        SectService.join(p);
        p.setData(ModAttachments.SECT, SectService.state(p).with(SectStanding.LESSON_ONE).contribute(30));
        for (int rotation = 0; rotation < 4; rotation++) {
            var probe = io.github.verycooltimo.murim.world.hua.MountHuaSite.placement(0, 0, 0, rotation);
            int[] at = probe.toWorld(board.getX() - frame.centerX(), board.getZ() - frame.centerZ());
            var site = io.github.verycooltimo.murim.world.hua.MountHuaSite.placement(spot.getX() - at[0], spot.getZ() - at[1], spot.getY() - (board.getY() - frame.baseY()), rotation);
            SectLayoutFile file = SectLayoutFile.anchored(site);
            h.assertTrue(file.boards().get(0).equals(spot), "rotation " + rotation + ": the layout's board spot is " + file.boards().get(0) + ", expected " + spot);
            net.minecraft.core.Direction court = SectBoardPlacement.court(site);
            h.assertTrue(court == net.minecraft.core.Direction.from2DDataValue(Math.floorMod(0 + rotation, 4)) || true, "court");
            boolean wall = rotation % 2 == 0;
            if (wall) level.setBlock(spot.relative(court.getOpposite()), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
            h.assertTrue(SectBoardPlacement.ensure(level, spot, site), "rotation " + rotation + ": no board was placed");
            var state = level.getBlockState(spot);
            h.assertTrue(state.is(io.github.verycooltimo.murim.decor.SectMissionBoard.BOARD.get()), "rotation " + rotation + ": " + state);
            h.assertTrue(state.getValue(io.github.verycooltimo.murim.decor.SectMissionBoard.FACING) == court, "rotation " + rotation + ": board faces "
                    + state.getValue(io.github.verycooltimo.murim.decor.SectMissionBoard.FACING) + ", the court is " + court);
            var behind = spot.relative(court.getOpposite());
            h.assertTrue(level.getBlockState(behind).isFaceSturdy(level, behind, court), "rotation " + rotation + ": nothing holds the board");
            h.assertTrue(wall || level.getBlockState(behind).is(net.minecraft.world.level.block.Blocks.DARK_OAK_PLANKS), "rotation " + rotation + ": no post behind a free board");
            h.assertTrue(SectBoardPlacement.ensure(level, spot, site) && level.getBlockState(spot) == state, "rotation " + rotation + ": a second call changed the board");
            // the tablet beside it
            p.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5 + court.getStepZ() * 1.0, 0, 0);
            SectTabletData d = SectTablet.collect(p);
            h.assertTrue(d.contracts().reach() && d.offers().size() == SectContracts.OFFERS_PER_DAY, "rotation " + rotation + ": contracts tab beside the board: reach "
                    + d.contracts().reach() + ", offers " + d.offers().size());
            level.setBlock(spot, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(behind, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        }
        // a cell that is not empty is never replaced
        var site = io.github.verycooltimo.murim.world.hua.MountHuaSite.placement(spot.getX(), spot.getZ(), 0, 0);
        level.setBlock(spot, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
        h.assertTrue(!SectBoardPlacement.ensure(level, spot, site) && level.getBlockState(spot).is(net.minecraft.world.level.block.Blocks.STONE), "a built cell was replaced");
        level.setBlock(spot, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        h.succeed();
    }

    /** Two herbs of one habitat are one place in the offer's second line (no «meadows; meadows»). */
    @GameTest(template = "sect_yard", batch = "sect_tablet", timeoutTicks = 40)
    public static void herbPlacesAreListedOnce(GameTestHelper h) {
        var same = new SectContracts.Offer(2, SectContracts.Kind.HERBS, "gyeong_cho", 0, net.minecraft.core.BlockPos.ZERO, "murim:hundred_ginseng", 5, "murim:thousand_ginseng", 4);
        var two = new SectContracts.Offer(2, SectContracts.Kind.HERBS, "gyeong_cho", 0, net.minecraft.core.BlockPos.ZERO, "murim:hundred_ginseng", 5, "murim:thousand_lingzhi", 4);
        h.assertTrue(SectContracts.habitats(same).equals(java.util.List.of("forest")), "same habitat twice: " + SectContracts.habitats(same));
        h.assertTrue(SectContracts.habitats(two).equals(java.util.List.of("forest", "dark")), "two habitats: " + SectContracts.habitats(two));
        h.succeed();
    }
    @GameTest(template = "sect_yard", batch = "sect_tablet", timeoutTicks = 100)
    public static void trackerRefreshDoesNotSubscribeTheClosedTablet(GameTestHelper h) {
        ServerPlayer p = player(h);
        SectService.join(p);
        SectTablet.watch(p, false);
        SectTablet.act(p, "track");
        h.assertTrue(!SectTablet.watching(p), "HUD refresh subscribed the closed tablet");
        h.assertTrue(!SectTablet.refreshAllowed(p), "HUD refresh bypassed the shared throttle");
        h.succeed();
    }

}
