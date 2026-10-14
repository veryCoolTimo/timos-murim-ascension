package io.github.verycooltimo.murim.sect;

import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.SectMissionBoard;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.sect.seal.PenanceService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real sessions, inventory, attachments and world snapshots. No negotiated client channel is needed. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectProgressionGameTests {
    private SectProgressionGameTests() { }
    private static SectState graduate() {
        return SectState.NONE.joined().with(SectStanding.LESSON_ONE).with(SectStanding.LESSON_TWO)
                .with(SectStanding.APPROVAL).contribute(40);
    }
    private static ServerPlayer player(GameTestHelper h) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        Vec3 at = h.absoluteVec(new Vec3(10.5, 2, 10.5));
        p.moveTo(at.x, at.y, at.z, 0, 0); p.setOnGround(true);
        p.setData(ModAttachments.SECT, graduate());
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(SectService.SIX, TechniqueProgress.learned(1, 6)));
        return p;
    }
    private record Route(Vec3 origin) implements SectLayout {
        @Override public Vec3 at(String zone, double u, double v) {
            double offset = "sect_gate".equals(zone) ? -4 : "dining".equals(zone) ? 4 : 0;
            return origin.add(offset + u, 0, v);
        }
        @Override public float yaw(double u, double v) { return SectLayout.yawOf(u, v); }
        @Override public double[] half(String zone) { return new double[]{4, 4}; }
        @Override public double[] local(String zone, Vec3 p) { return new double[]{p.x - at(zone, 0, 0).x, p.z - origin.z}; }
    }
    private static SectDisciple npc(GameTestHelper h, String key) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow()); d.setKeepAwake(true); d.setNoAi(true);
        Vec3 at = h.absoluteVec(new Vec3(11.5, 2, 10.5));
        d.setLayout(new Route(at)); d.moveTo(at.x, at.y, at.z, 0, 0);
        h.getLevel().addFreshEntity(d);
        return d;
    }
    private static SectState roundTrip(SectState s) {
        return SectState.CODEC.parse(JsonOps.INSTANCE, SectState.CODEC.encodeStart(JsonOps.INSTANCE, s).getOrThrow()).getOrThrow();
    }

    @GameTest(template = "sect_yard", batch = "sect_progression", timeoutTicks = 40)
    public static void discipleshipNeedsDistinctPartnersAndServerCeremony(GameTestHelper h) {
        ServerPlayer p = player(h); SectDisciple mentor = npc(h, SectProgression.MENTOR);
        p.setData(ModAttachments.SECT, graduate().with("request.pills.done"));
        SectDisciple a = npc(h, "gyeong_on"), b = npc(h, "gyeong_dan"), c = npc(h, "gyeong_ha");
        for (int i = 0; i < 3; i++) SectProgression.sparEnded(p, a, true);
        h.assertTrue(!SectProgression.directEligible(SectService.state(p)), "one repeated partner unlocked discipleship");
        SectProgression.sparEnded(p, b, false); SectProgression.sparEnded(p, c, true);
        h.assertTrue(SectProgression.directEligible(SectService.state(p)), "three different completed spars not counted");
        h.assertTrue(!SectProgression.beginDiscipleship(p, a), "wrong elder accepted disciple");
        h.assertTrue(SectProgression.beginDiscipleship(p, mentor), "ceremony not started");
        h.assertTrue(!SectProgression.finishDiscipleship(p, mentor), "client skipped five-second ceremony");
        long at = SectService.state(p).entry("mentor.ceremony.at", -1);
        h.assertTrue(SectProgression.beginDiscipleship(p, mentor)
                && SectService.state(p).entry("mentor.ceremony.at", -1) == at, "reopening reset ceremony");
        p.setData(ModAttachments.SECT, roundTrip(SectService.state(p)).record("mentor.ceremony.at", p.level().getGameTime() - 100));
        h.assertTrue(SectProgression.finishDiscipleship(p, mentor), "saved ceremony could not finish");
        h.assertTrue(!SectProgression.finishDiscipleship(p, mentor), "disciple accepted twice");
        h.assertTrue(SectService.state(p).generation() == 3, "personal discipleship changed generation");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_progression", timeoutTicks = 40)
    public static void personalLessonRequiresNewPracticeAndPaysOnce(GameTestHelper h) {
        ServerPlayer p = player(h); SectDisciple mentor = npc(h, SectProgression.MENTOR);
        p.setData(ModAttachments.SECT, graduate().with(SectProgression.DIRECT));
        h.assertTrue(!SectProgression.lesson(p, mentor), "lesson without practice");
        SectProgression.sparEnded(p, npc(h, "seo_rang"), false);
        h.assertTrue(DialogueService.openDialogue(p, mentor, SectProgression.DIALOGUE, "training"), "missing training dialogue");
        h.assertTrue(SectProgression.canLesson(p, mentor), "talking to mentor hid lesson option");
        var option = DialogueLoader.get(SectProgression.DIALOGUE).nodes().get("training").options().get(1);
        h.assertTrue(DialogueService.applyHeadless(p, mentor, option), "loaded lesson action failed");
        double progress = p.getData(ModAttachments.MASTERY).techniques().get(SectService.SIX).progress();
        h.assertTrue(progress > 0 && !DialogueService.applyHeadless(p, mentor, option), "lesson not paid once");
        h.assertTrue(p.getData(ModAttachments.MASTERY).techniques().get(SectService.SIX).progress() == progress, "repeat changed mastery");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_progression", timeoutTicks = 40)
    public static void supplyDutyConsumesItemsAndSurvivesReloadWithoutRepayment(GameTestHelper h) {
        ServerPlayer p = player(h); SectDisciple giver = npc(h, "tae_gyun");
        h.assertTrue(SectProgression.startMission(p, giver, "supply"), "supply unavailable");
        h.assertTrue(!SectProgression.finishMission(p, giver), "supply finished with no items");
        p.getInventory().add(new ItemStack(Items.OAK_PLANKS, 16)); p.getInventory().add(new ItemStack(Items.BREAD, 8));
        int before = SectService.state(p).contribution();
        h.assertTrue(SectProgression.finishMission(p, giver), "supply rejected");
        h.assertTrue(new SectService.ItemNeed(Items.OAK_PLANKS, 1).count(p) == 0
                && new SectService.ItemNeed(Items.BREAD, 1).count(p) == 0, "supplies not consumed");
        h.assertTrue(SectService.state(p).contribution() == before + 4, "wrong reward");
        p.setData(ModAttachments.SECT, roundTrip(SectService.state(p)));
        h.assertTrue(!SectProgression.finishMission(p, giver) && !SectProgression.startMission(p, giver, "patrol"), "reload bypassed daily limit");
        h.assertTrue(SectLife.data(h.getLevel()).suppliedUntil() > SectSchedule.day(h.getLevel().getDayTime()), "no visible supply state");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_progression", timeoutTicks = 40)
    public static void patrolNeedsOrderedStopsAndReturn(GameTestHelper h) {
        ServerPlayer p = player(h); SectDisciple giver = npc(h, "tae_gyun");
        h.assertTrue(SectProgression.startMission(p, giver, "patrol"), "patrol unavailable");
        h.assertTrue(!SectProgression.finishMission(p, giver), "dialogue forged patrol proof");
        for (int i = 0; i < 3; i++) {
            BlockPos stop = BlockPos.of(SectService.state(p).entry("duty.stop." + i, 0));
            p.moveTo(stop.getX()+0.5, stop.getY(), stop.getZ()+0.5, 0, 0); p.setOnGround(true);
            if (i == 0) {
                p.getAbilities().flying = true; SectProgression.patrolTick(p);
                h.assertTrue(SectService.state(p).entry("duty.step", 0) == 0, "flying counted as patrol");
                p.getAbilities().flying = false;
            }
            SectProgression.patrolTick(p);
            h.assertTrue(SectService.state(p).entry("duty.step", 0) == i+1, "ordered stop not recorded");
            p.setData(ModAttachments.SECT, roundTrip(SectService.state(p)));
        }
        p.moveTo(giver.getX()+30, giver.getY(), giver.getZ(), 0, 0);
        h.assertTrue(!SectProgression.finishMission(p, giver), "remote completion accepted");
        p.moveTo(giver.getX(), giver.getY(), giver.getZ(), 0, 0);
        h.assertTrue(SectProgression.finishMission(p, giver) && !SectProgression.finishMission(p, giver), "patrol reward not exactly once");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_progression", timeoutTicks = 40)
    public static void abandonedDutyAndLateCompletionCannotBeFarmed(GameTestHelper h) {
        ServerPlayer p = player(h); SectDisciple giver = npc(h, "tae_gyun");
        int contribution = SectService.state(p).contribution();
        SectProgression.startMission(p, giver, "patrol"); SectProgression.abandonMission(p);
        h.assertTrue(!SectProgression.startMission(p, giver, "supply") && !SectProgression.finishMission(p, giver), "abandon restored today's slot");
        h.assertTrue(SectService.state(p).contribution() == contribution && !SectService.state(p).has(SectProgression.REPAIR), "abandon was an offence");
        long today = SectSchedule.day(p.level().getDayTime());
        p.setData(ModAttachments.SECT, graduate().with("duty.active").with("duty.supply").record("duty.day", today-1));
        p.getInventory().add(new ItemStack(Items.OAK_PLANKS, 16));
        p.getInventory().add(new ItemStack(Items.BREAD, 8));
        p.setData(ModAttachments.SECT, roundTrip(SectService.state(p)).record("duty.origin", giver.blockPosition().asLong())
                .record("duty.day", today - 100));
        h.assertTrue(SectProgression.finishMission(p, giver), "late return lost accepted duty");
        p.setData(ModAttachments.SECT, roundTrip(SectService.state(p)));
        h.assertTrue(!SectProgression.finishMission(p, giver) && !SectProgression.startMission(p, giver, "supply"),
                "late completion bypassed one reward per day");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_progression", timeoutTicks = 40)
    public static void archiveChecksStandingKnowledgeAndDailyLimit(GameTestHelper h) {
        SectState disciple = SectState.NONE.joined().with(SectStanding.LESSON_ONE);
        h.assertTrue(SectProgression.archiveAllowed(disciple, 1, 0) && !SectProgression.archiveAllowed(disciple, 1, 1), "archive tier ignored");
        h.assertTrue(SectProgression.archiveAllowed(graduate().without(SectStanding.APPROVAL), 2, 1)
                && !SectProgression.archiveAllowed(graduate().without(SectStanding.APPROVAL), 2, 2), "graduate read restricted tier");
        h.assertTrue(!SectProgression.archiveAllowed(graduate().with(PenanceService.SUMMONED), 4, 0), "summons ignored");
        ServerPlayer p = player(h); SectDisciple librarian = npc(h, "tae_rok");
        h.assertTrue(!SectProgression.read(p, librarian, 2), "reading granted an unknown art");
        h.assertTrue(SectProgression.read(p, librarian, 0) && !SectProgression.read(p, librarian, 0), "daily reading limit missing");
        h.assertTrue(!SectService.state(p).has("vault.permitted"), "archive opened vault");
        p.setData(ModAttachments.SECT, roundTrip(SectService.state(p)));
        h.assertTrue(!SectProgression.read(p, librarian, 0), "reload refreshed archive limit");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_progression", timeoutTicks = 40)
    public static void rivalRemembersResultsAndWaitsTwoDays(GameTestHelper h) {
        long today = SectSchedule.day(h.getLevel().getDayTime());
        while (SectRota.onDuty(SectRoster.of(SectProgression.RIVAL).orElseThrow(), today * 24000 + 6000)) today++;
        h.getLevel().setDayTime(today * 24000 + 6000); // Named rival must be off watch, independently of test launch day.
        ServerPlayer p = player(h); SectDisciple rival = npc(h, SectProgression.RIVAL);
        h.assertTrue(!SectProgression.rival(p, npc(h, "seo_gyu")), "wrong rival");
        h.assertTrue(SectProgression.rival(p, rival), "first duel unavailable");
        h.assertTrue(SectProgression.rivalChain(rival, p) == 1, "initial rival series wrong");
        SectProgression.sparEnded(p, rival, false);
        p.setData(ModAttachments.SECT, roundTrip(SectService.state(p)));
        h.assertTrue(SectService.state(p).entry("rival.losses", 0) == 1 && SectProgression.rivalStage(SectService.state(p)) == 1,
                "rival memory lost");
        SectDisciple returningRival = npc(h, SectProgression.RIVAL); // Free NPC after unloading/reloading.
        h.assertTrue(!SectProgression.canRival(p, returningRival), "immediate rematch accepted");
        p.setData(ModAttachments.SECT, SectService.state(p).record("rival.day", today - 1));
        h.assertTrue(!SectProgression.canRival(p, returningRival), "one-day rematch accepted");
        p.setData(ModAttachments.SECT, SectService.state(p).record("rival.day", today - 2));
        h.assertTrue(SectProgression.rival(p, returningRival) && SectProgression.rivalChain(returningRival, p) == 2,
                "two-day rematch did not escalate to two cuts");
        h.assertTrue(!SectService.state(p).has(SectProgression.REPAIR), "spar loss punished");
        h.assertTrue(SectProgression.rivalStage(SectService.state(p).record("rival.bouts", 100)) == 2, "rival escalates without bound");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_progression", timeoutTicks = 40)
    public static void councilSnapshotAndReceiptPersistWithoutRewards(GameTestHelper h) {
        ServerPlayer p = player(h); SectDisciple head = npc(h, "tae_hwi");
        SectSiteData site = SectLife.data(h.getLevel());
        long week = SectSchedule.day(h.getLevel().getDayTime()) / 7;
        site.council(week, "rain", "@TestPlayer");
        String news = SectProgression.councilNews(p).getString();
        int merit = SectService.state(p).merit();
        h.assertTrue(SectProgression.council(p, head) && !SectProgression.council(p, head), "weekly announcement repeated");
        p.setData(ModAttachments.SECT, roundTrip(SectService.state(p)));
        h.assertTrue(!SectProgression.council(p, head) && SectService.state(p).merit() == merit, "reload rewarded council again");
        SectSiteData loaded = SectSiteData.load(site.save(new net.minecraft.nbt.CompoundTag(), h.getLevel().registryAccess()), h.getLevel().registryAccess());
        h.assertTrue(loaded.councilWeek() == week && loaded.councilTopic().equals("rain")
                && loaded.councilChampion().equals("@TestPlayer"), "world news snapshot lost");
        h.assertTrue(SectProgression.councilNews(p).getString().equals(news), "same week rerolled news");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_progression", timeoutTicks = 40)
    public static void disciplineCostsAvailableMeritAndRepairCannotPayTwice(GameTestHelper h) {
        ServerPlayer p = player(h); SectDisciple victim = npc(h, "gyeong_on"), mentor = npc(h, SectProgression.MENTOR);
        SectStanding before = SectService.standing(p);
        int merit = SectService.state(p).merit(), contribution = SectService.state(p).contribution();
        SectProgression.offence(p, "assault", victim);
        h.assertTrue(SectService.state(p).merit() == merit-3 && SectService.state(p).contribution() == contribution
                && SectService.standing(p) == before, "penalty changed lifetime standing");
        h.assertTrue(!SectProgression.startMission(p, mentor, "patrol") && !SectProgression.apologise(p, mentor), "discipline ignored victim");
        p.setData(ModAttachments.SECT, roundTrip(SectService.state(p)));
        h.assertTrue(SectProgression.apologise(p, victim) && !SectProgression.apologise(p, victim), "apology replayed");
        p.getInventory().add(new ItemStack(Items.BREAD, 8));
        p.setData(ModAttachments.SECT, SectService.state(p).with(PenanceService.SUMMONED));
        h.assertTrue(!SectProgression.repair(p, mentor), "restitution bypassed sentence");
        p.setData(ModAttachments.SECT, SectService.state(p).without(PenanceService.SUMMONED));
        h.assertTrue(SectProgression.repair(p, mentor) && !SectProgression.repair(p, mentor), "restitution repeated");
        h.assertTrue(SectService.state(p).merit() == merit-3 && !SectService.state(p).has(SectProgression.REPAIR), "restitution farmed merit");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_progression", timeoutTicks = 40)
    public static void journalMigrationAndDialogueData(GameTestHelper h) {
        var old = com.google.gson.JsonParser.parseString("{\"version\":4,\"member\":true,\"generation\":3,\"flags\":[],\"contribution\":20,\"spent\":2}");
        SectState migrated = SectState.CODEC.parse(JsonOps.INSTANCE, old).getOrThrow();
        h.assertTrue(migrated.version() == 5 && migrated.journal().isEmpty() && migrated.merit() == 18, "old save migration broken");
        SectState recorded = migrated.record("duty.day", 12).record("duty.stop.0", 54321).with("duty.active");
        h.assertTrue(roundTrip(recorded).equals(recorded), "mission journal lost");
        h.assertTrue(DialogueService.validate(DialogueLoader.get(SectProgression.DIALOGUE)).isEmpty(), "invalid progression dialogue");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_progression", timeoutTicks = 40)
    public static void boardHasFourStatesAndNeedsWallSupport(GameTestHelper h) {
        BlockPos pos = h.absolutePos(new BlockPos(10, 3, 10));
        var north = SectMissionBoard.BOARD.get().defaultBlockState().setValue(SectMissionBoard.FACING, Direction.NORTH);
        h.getLevel().setBlockAndUpdate(pos.south(), Blocks.STONE.defaultBlockState());
        h.assertTrue(north.canSurvive(h.getLevel(), pos), "board rejected wall support");
        h.assertTrue(north.getShape(h.getLevel(), pos).bounds().minZ == 14.0D / 16.0D,
                "north board shape disagrees with wall-mounted model");
        h.getLevel().setBlockAndUpdate(pos.south(), Blocks.AIR.defaultBlockState());
        h.assertTrue(!north.canSurvive(h.getLevel(), pos), "board can float without support");
        h.assertTrue(SectMissionBoard.BOARD.get().getStateDefinition().getPossibleStates().size() == 4, "board model state count inflated");
        h.succeed();
    }
}
