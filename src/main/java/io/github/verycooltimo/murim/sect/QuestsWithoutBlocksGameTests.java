package io.github.verycooltimo.murim.sect;

import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.*;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.mastery.*;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.quest.*;
import io.github.verycooltimo.murim.quest.def.QuestDef;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.List;

/** Author 31.10 acceptance cases; real dialogue options, menu extraction and tracked spar events.
 * Not run on the corporate editing machine. Fixtures follow SideQuestGameTests/SectTrialsGameTests.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class QuestsWithoutBlocksGameTests {
    private static final String YARD = "sect_yard";
    private QuestsWithoutBlocksGameTests() { }
    private static ServerPlayer player(GameTestHelper h) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        Vec3 at = h.absoluteVec(new Vec3(10.5, 2, 10.5));
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.setOnGround(true);
        p.setData(ModAttachments.SECT, SectState.NONE.joined().with("lesson.six").record("arrival.day", -10));
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(SectService.SIX, TechniqueProgress.learned(1, 12)));
        return p;
    }
    private record Flat(Vec3 origin) implements SectLayout {
        public Vec3 at(String zone, double u, double v) { return origin.add(u, 0, v); }
        public float yaw(double u, double v) { return SectLayout.yawOf(u, v); }
        public double[] half(String zone) { return new double[] {30, 30}; }
        public double[] local(String zone, Vec3 p) { return new double[] {p.x - origin.x, p.z - origin.z}; }
    }
    private static SectDisciple npc(GameTestHelper h, String key, Vec3 at, SectLayout layout) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setKeepAwake(true);
        d.setNoAi(true);
        d.neverGuards = true;
        if (layout != null) d.setLayout(layout);
        d.moveTo(at.x, at.y, at.z, 0, 0);
        h.getLevel().addFreshEntity(d);
        return d;
    }
    private static Dialogue.Option option(SectDisciple npc, String node, String text) {
        return DialogueService.nodeOf(DialogueLoader.get(npc.dialogue()), node).options().stream()
                .filter(o -> o.text().equals(text)).findFirst().orElseThrow();
    }
    private static void enter(ServerPlayer p, SectDisciple npc, String node) {
        DialogueService.openDialogue(p, npc, npc.dialogue(), node);
    }
    private static void roundTrip(ServerPlayer p) {
        var json = SectState.CODEC.encodeStart(JsonOps.INSTANCE, p.getData(ModAttachments.SECT)).getOrThrow();
        p.setData(ModAttachments.SECT, SectState.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
    }

    @GameTest(template = YARD, timeoutTicks = 60, batch = "quests_noblocks")
    public static void newQuestItemsAndSurfacesResolve(GameTestHelper h) {
        for (var herb : QuestLoader.herbs()) {
            h.assertTrue(QuestService.stack(herb.step().item()) != null, "unregistered sample " + herb.step().item());
            h.assertTrue(net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(ResourceLocation.parse(herb.step().block())),
                    "unregistered plant " + herb.step().block());
        }
        for (var chest : QuestLoader.personalChests()) for (String item : chest.step().items()) {
            h.assertTrue(QuestService.stack(item) != null, "unregistered personal reward " + item);
        }
        ServerPlayer p = player(h);
        BlockPos fern = h.absolutePos(new BlockPos(10, 2, 12));
        h.getLevel().setBlock(fern, ModQuests.RED_VEIN_FERN.get().defaultBlockState(), 3);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHEARS));
        h.assertTrue(MedicinalPlant.harvest(p, fern), "shears did not gather age-100 sample");
        h.assertFalse(MedicinalPlant.harvest(p, fern), "cut root yielded twice");
        h.assertTrue(Quests.has(p, "herb.h06.wild"), "wild harvest was not recorded");
        ServerPlayer buyer = player(h);
        Quests.mark(buyer, "red_vein", "asked");
        buyer.getInventory().add(new ItemStack(ModItems.SILVER_TAEL.get()));
        h.assertTrue(QuestScenes.handIn(buyer, "red_vein_buy"), "alternative ingredient set unavailable");
        h.assertFalse(Quests.has(buyer, "herb.h06.wild") || Quests.has(buyer, "herb.h11.wild"), "purchase forged wild discoveries");
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 420, batch = "quests_noblocks")
    public static void overheardRepeatsThreeTimesThenExpires(GameTestHelper h) {
        ServerPlayer p = player(h);
        SectDisciple healer = npc(h, "gyeong_cho", p.position().add(2, 0, 0), null);
        h.getLevel().addNewPlayer(p);
        String id = "red_vein_rumour";
        h.runAtTickTime(1, () -> {
            Overheard.tickPlayer(h.getLevel(), p, System.currentTimeMillis());
            h.assertTrue(Overheard.repeatCount(p, id) == 1 && Overheard.fresh(p, id), "initial audible line was not heard");
            // Another nearby listener's poll must not use up this player's repeats.
            Overheard.onBubble(p, Overheard.line(id).key());
            h.assertTrue(Overheard.repeatCount(p, id) == 1, "rapid duplicate consumed a repeat");
        });
        h.runAtTickTime(122, () -> {
            Overheard.markHeard(p, id, System.currentTimeMillis() - 60_001);
            Overheard.tickPlayer(h.getLevel(), p, System.currentTimeMillis());
            h.assertTrue(Overheard.repeatCount(p, id) == 2, "second line missing");
        });
        h.runAtTickTime(243, () -> {
            Overheard.markHeard(p, id, System.currentTimeMillis() - 60_001);
            Overheard.tickPlayer(h.getLevel(), p, System.currentTimeMillis());
            h.assertTrue(Overheard.repeatCount(p, id) == 3, "third line missing");
            roundTrip(p);
            h.assertTrue(Overheard.fresh(p, id, Overheard.heardAt(p, id) + 59_999), "last repeat did not refresh window");
            h.assertFalse(Overheard.fresh(p, id, Overheard.heardAt(p, id) + 60_001), "last window never expired");
        });
        h.runAtTickTime(364, () -> {
            Overheard.markHeard(p, id, System.currentTimeMillis() - 60_001);
            Overheard.tickPlayer(h.getLevel(), p, System.currentTimeMillis());
            h.assertTrue(Overheard.repeatCount(p, id) == 3 && !Overheard.fresh(p, id), "series restarted after expiring");
            h.assertTrue(DialogueService.applyHeadless(p, healer, option(healer, "greet", "dialogue.murim.red_vein.opt.ask")), "expired rumour blocked asking");
            h.getLevel().removePlayerImmediately(p, Entity.RemovalReason.DISCARDED);
            h.succeed();
        });
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "quests_noblocks")
    public static void personalRewardsSurviveBreakingAndIgnoreHoppers(GameTestHelper h) {
        ServerPlayer p = player(h), other = player(h);
        BlockPos at = h.absolutePos(new BlockPos(10, 3, 12));
        h.getLevel().setBlock(at.below(), Blocks.HOPPER.defaultBlockState(), 3);
        h.getLevel().setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        var reward = new QuestLoader.Located<>("fixture", new QuestDef.PersonalChest("shared", "unused",
                List.of("minecraft:diamond*10"), QuestDef.Gate.NONE));
        PersonalRewards.open(p, reward, at);
        ChestMenu menu = (ChestMenu) p.containerMenu;
        h.assertTrue(menu.getContainer().removeItem(0, 3).getCount() == 3, "partial extraction failed");
        roundTrip(p);
        h.runAtTickTime(40, () -> {
            h.assertTrue(((Container) h.getLevel().getBlockEntity(at.below())).isEmpty(), "hopper extracted private loot");
            h.assertTrue(((Container) h.getLevel().getBlockEntity(at)).isEmpty(), "private loot entered physical chest");
            h.getLevel().destroyBlock(at, true);
            p.doCloseContainer();
            PersonalRewards.ensureModel(h.getLevel(), at);
            PersonalRewards.open(p, reward, at);
            ChestMenu reopened = (ChestMenu) p.containerMenu;
            h.assertTrue(reopened.getContainer().getItem(0).getCount() == 7, "breaking lost or reset remainder");
            PersonalRewards.open(other, reward, at);
            h.assertTrue(((ChestMenu) other.containerMenu).getContainer().getItem(0).getCount() == 10, "one player's extraction consumed another's reward");
            for (int slot = 0; slot < p.getInventory().items.size(); slot++) p.getInventory().items.set(slot, new ItemStack(Items.STONE, 64));
            reopened.quickMoveStack(p, 0);
            h.assertTrue(reopened.getContainer().getItem(0).getCount() == 7, "full inventory consumed reward");
            p.getInventory().clearContent();
            reopened.quickMoveStack(p, 0);
            roundTrip(p);
            h.assertTrue(new PersonalRewards.Ledger(p, reward, at).isEmpty(), "shift-click duplicated reward");
            h.succeed();
        });
    }

    @GameTest(template = YARD, timeoutTicks = 80, batch = "quests_noblocks")
    public static void medicineFromRumourToPersonalPill(GameTestHelper h) {
        ServerPlayer p = player(h);
        SectDisciple healer = npc(h, "gyeong_cho", p.position().add(2, 0, 0), null);
        SectDisciple elder = npc(h, "tae_seong", p.position().add(3, 0, 0), null);
        Overheard.onBubble(p, Overheard.line("red_vein_rumour").key());
        enter(p, healer, "red_vein_ask");
        int merit = SectService.state(p).contribution();
        p.getInventory().add(new ItemStack(ModQuests.RED_VEIN_FERN_100.get()));
        var hand = option(healer, "red_vein_ask", "dialogue.murim.red_vein.opt.hand_in");
        h.assertFalse(DialogueService.applyHeadless(p, healer, hand), "incomplete ingredients accepted");
        h.assertTrue(p.getInventory().countItem(ModQuests.RED_VEIN_FERN_100.get()) == 1, "partial hand-in consumed fern");
        p.getInventory().add(new ItemStack(ModQuests.SILVER_VEIN_MUGWORT_100.get(), 2));
        h.assertTrue(DialogueService.applyHeadless(p, healer, hand), "complete ingredients refused");
        h.assertTrue(SectService.state(p).contribution() == merit + 2, "medicine merit differs");
        h.assertTrue(Quests.done(p, "red_vein", "elder_medicine"), "elder's story medicine missing");
        h.assertFalse(DialogueService.applyHeadless(p, healer, hand), "hand-in repeated");
        var reward = QuestLoader.personalChests().stream().filter(l -> l.quest().equals("red_vein")).findFirst().orElseThrow();
        var chest = new PersonalRewards.Ledger(p, reward, p.blockPosition());
        h.assertTrue(chest.removeItem(0, 1).is(ModItems.PILL_MENDING.get()) && chest.isEmpty(), "N05 reward missing");
        enter(p, elder, "red_vein_elder");
        h.assertTrue(Quests.done(p, "red_vein", "elder_thanked") && SectService.state(p).contribution() == merit + 2, "optional thanks paid again");
        roundTrip(p);
        h.assertTrue(new PersonalRewards.Ledger(p, reward, p.blockPosition()).isEmpty(), "medicine reward reset on reload");
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 100, batch = "quests_noblocks")
    public static void stillTraceNeedsAnUnbrokenSecondAndCannotSeeThroughWalls(GameTestHelper h) {
        ServerPlayer p = player(h);
        BlockPos trace = h.absolutePos(new BlockPos(10, 3, 13));
        BlockPos wall = trace.offset(0, 0, -1);
        h.getLevel().setBlock(trace, ModQuests.RED_VEIN_FERN.get().defaultBlockState(), 3);
        Vec3 aim = new Vec3(trace.getX() + 0.5, trace.getY() + 0.5, trace.getZ() + 0.5).subtract(p.getEyePosition());
        p.setYRot((float) Math.toDegrees(Math.atan2(-aim.x, aim.z)));
        float pitch = (float) -Math.toDegrees(Math.atan2(aim.y, Math.sqrt(aim.x * aim.x + aim.z * aim.z)));
        p.setXRot(pitch);
        var observation = QuestLoader.observations().stream().filter(l -> l.step().id().equals("red_vein_niche")).findFirst().orElseThrow();
        h.getLevel().setBlock(wall, Blocks.STONE.defaultBlockState(), 3);
        h.runAtTickTime(5, () -> {
            Observations.tickAt(p, observation, trace);
            h.assertFalse(Quests.done(p, "red_vein", "niche"), "trace seen through wall");
            h.getLevel().removeBlock(wall, false);
        });
        h.onEachTick(() -> Observations.tickAt(p, observation, trace));
        h.runAtTickTime(23, () -> {
            h.assertFalse(Quests.done(p, "red_vein", "niche"), "less than a second completed observation");
            p.setXRot(-80); // turn away: discard the unfinished look
        });
        h.runAtTickTime(25, () -> p.setXRot(pitch));
        h.runAtTickTime(46, () -> {
            h.assertTrue(Quests.done(p, "red_vein", "niche"), "continuous second did not record trace");
            roundTrip(p);
            h.getLevel().removeBlock(trace, false);
            h.assertTrue(Quests.done(p, "red_vein", "niche"), "destroying the trace erased knowledge");
            h.succeed();
        });
    }

    @GameTest(template = YARD, timeoutTicks = 420, batch = "quests_noblocks")
    public static void lessonParryThenReplyPaysWithoutVictory(GameTestHelper h) {
        h.getLevel().setDayTime(24000 + SectSchedule.Period.TRAINING.start() + 500);
        Flat flat = new Flat(h.absoluteVec(new Vec3(11.5, 2, 10.5)));
        Vec3 ring = flat.at("training", SectSchedule.RINGS[0][0], SectSchedule.RINGS[0][1]);
        ServerPlayer p = player(h);
        p.moveTo(ring.x, ring.y, ring.z, 0, 0);
        h.getLevel().addNewPlayer(p);
        SectDisciple mentor = npc(h, "gyeong_pil", ring.add(1.5, 0, 0), flat);
        h.assertTrue(SectBouts.begin(p, mentor, SectBouts.Mode.TRAIN), "initial practice unavailable");
        int[] phase = {0};
        boolean[] done = {false};
        h.onEachTick(() -> {
            if (done[0]) return;
            p.doTick();
            if (phase[0] == 1) {
                if (mentor.spar() != SectDisciple.Spar.NONE) return;
                enter(p, mentor, "second_strike_ask");
                h.assertTrue(QuestLessons.start(p, mentor, "second_strike"), "voluntary lesson unavailable");
                phase[0] = 2;
                return;
            }
            SectBouts.Bout b = SectBouts.active(p);
            if (b == null || b.fightStart < 0 || mentor.spar() != SectDisciple.Spar.FIGHT) return;
            if (phase[0] == 0) {
                phase[0] = 1;
                mentor.endSpar(false);
                h.assertTrue(Quests.done(p, "second_strike", "lost"), "registered defeat did not open lesson");
                return;
            }
            done[0] = true;
            // A reply before a parry is insufficient. Only this bout's owner/partner counts.
            mentor.invulnerableTime = 0;
            mentor.hurt(h.getLevel().damageSources().playerAttack(p), 1);
            h.assertFalse(Quests.done(p, "second_strike", "practiced"), "reply without defence counted");
            SectBouts.onParry(p, mentor);
            mentor.invulnerableTime = 0;
            mentor.hurt(h.getLevel().damageSources().playerAttack(p), 1);
            h.assertTrue(Quests.done(p, "second_strike", "practiced"), "parry/reply not recorded");
            mentor.endSpar(false);
            var before = p.getData(ModAttachments.MASTERY).progress(SectService.SIX);
            int merit = SectService.state(p).contribution();
            double cost = MasteryService.nextCost(p, SectService.SIX);
            h.assertTrue(QuestLessons.review(p, mentor, "second_strike"), "review refused after lost lesson bout");
            var after = p.getData(ModAttachments.MASTERY).progress(SectService.SIX);
            h.assertTrue(Math.abs(after.help() - before.help() - cost * 0.1) < 0.0001, "lesson did not grant 10% help");
            h.assertTrue(SectService.state(p).contribution() == merit, "lesson granted merit");
            QuestLessons.review(p, mentor, "second_strike");
            h.assertTrue(p.getData(ModAttachments.MASTERY).progress(SectService.SIX).equals(after), "review paid twice");
            h.getLevel().removePlayerImmediately(p, Entity.RemovalReason.DISCARDED);
            h.succeed();
        });
    }

    @GameTest(template = YARD, timeoutTicks = 80, batch = "quests_noblocks")
    public static void thresholdAdviceChecksReadinessAndGivesNothing(GameTestHelper h) {
        ServerPlayer p = player(h);
        SectDisciple mentor = npc(h, "gyeong_pil", p.position().add(2, 0, 0), null);
        DantianProfile ready = DantianProfile.INITIAL.withTags("clear", "debug").withRank(0);
        ready = ready.withPool(Realm.wall(ready));
        p.setData(ModAttachments.PROFILE, ready);
        io.github.verycooltimo.murim.training.TrainingService.setPoints(p,
                io.github.verycooltimo.murim.training.BodyRules.threshold(io.github.verycooltimo.murim.training.BodyRules.breakthroughNeed(1)));
        h.assertTrue(MeditationService.readiness(p) == null, "fixture is not actually ready");
        int merit = SectService.state(p).contribution();
        var mastery = p.getData(ModAttachments.MASTERY);
        var profile = p.getData(ModAttachments.PROFILE);
        h.assertTrue(QuestLessons.thresholdOffered(p, mentor, "changed_breath"), "ready threshold not offered");
        QuestLessons.tickPlayer(p);
        h.assertTrue(Quests.done(p, "changed_breath", "invited.0.0"), "first ready meeting gave no invitation");
        enter(p, mentor, "changed_breath_inspect");
        h.assertTrue(DialogueService.applyHeadless(p, mentor, option(mentor, "changed_breath_inspect", "dialogue.murim.changed_breath.opt.prepare")), "preparation choice refused");
        // Recheck on return: advice must describe loss of readiness, and postponement remains available.
        p.setData(ModAttachments.PROFILE, profile.withPool(0));
        h.assertTrue(QuestLessons.thresholdOffered(p, mentor, "changed_breath") && MeditationService.readiness(p) != null, "return lost after readiness changed");
        QuestLessons.inspect(p, mentor, "changed_breath");
        p.setData(ModAttachments.PROFILE, profile);
        h.assertTrue(p.getData(ModAttachments.MASTERY).equals(mastery) && SectService.state(p).contribution() == merit
                && p.getInventory().isEmpty() && p.getData(ModAttachments.PROFILE).equals(profile), "advice granted something");
        h.assertFalse(p.getData(ModAttachments.MEDITATION).active(), "advice forced meditation");
        roundTrip(p);
        h.assertTrue(Quests.done(p, "changed_breath", "prepare.0.0"), "threshold decision not saved");
        h.succeed();
    }
}
