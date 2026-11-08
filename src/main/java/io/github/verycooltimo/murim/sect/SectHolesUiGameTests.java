package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.registry.ModItems;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Holes found in the review of 21.10 (path, progress, UI, robustness): a favour whose pills were lost, Peaceful worlds, the
 * mentor menu that hid the lost-tablet door, a mastered art's page, an old world with no built sect, a blocked board cell.
 * Each test has its own batch: some of them change the world difficulty and put it back.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectHolesUiGameTests {
    private static final String YARD = "sect_yard";

    private SectHolesUiGameTests() { }

    private static SectDisciple npc(GameTestHelper h, String key) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setKeepAwake(true);
        Vec3 at = h.absoluteVec(new Vec3(6.5D, 2.0D, 6.5D));
        d.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        h.getLevel().addFreshEntity(d);
        return d;
    }

    private static ServerPlayer member(GameTestHelper h) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        Vec3 at = h.absoluteVec(new Vec3(7.5D, 2.0D, 7.5D));
        p.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        p.setOnGround(true);
        p.setData(ModAttachments.SECT, SectState.NONE.joined().with("met_mentor"));
        return p;
    }

    private static int pills(ServerPlayer p) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (s.is(ModItems.PILL_SNOW_PLUM.get())) n += s.getCount();
        return n;
    }

    @GameTest(template = YARD, timeoutTicks = 40, batch = "sect_dialogue_closing")
    public static void generalQuestionsReturnAndCloseWithAFarewell(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        ServerPlayer p = member(h);
        SectDisciple d = npc(h, "yul_ak");
        var route = DialogueService.route(p, d);
        h.assertTrue(route != null, "no dialogue route");
        h.assertTrue(DialogueService.openDialogue(p, d, route.id(), route.node()), "no opening menu");
        for (int q = 0; q < SectGeneralTalk.QUESTIONS.size(); q++) {
            var opening = p.getData(ModAttachments.DIALOGUE);
            h.assertTrue(opening.shown().size() <= 7, "opening menu exceeds seven rows");
            h.assertTrue(!opening.shown().contains(SectGeneralTalk.BASE + q), "small talk leaked into opening menu");
            DialogueService.choose(p, opening.shown().indexOf(SectGeneralTalk.OPEN));
            var menu = p.getData(ModAttachments.DIALOGUE);
            int index = menu.shown().indexOf(SectGeneralTalk.BASE + q);
            h.assertTrue(index >= 0, "missing general question " + q);
            DialogueService.choose(p, index);
            var answer = p.getData(ModAttachments.DIALOGUE);
            h.assertTrue(SectGeneralTalk.NODE.equals(answer.node()), "question did not show its answer");
            h.assertTrue(answer.shown().contains(SectGeneralTalk.CLOSE), "answer has no polite exit");
            DialogueService.choose(p, answer.shown().indexOf(0));
            h.assertTrue(!SectGeneralTalk.NODE.equals(p.getData(ModAttachments.DIALOGUE).node()), "back did not return");
        }
        var menu = p.getData(ModAttachments.DIALOGUE);
        DialogueService.choose(p, menu.shown().indexOf(SectGeneralTalk.CLOSE));
        h.assertTrue(!p.getData(ModAttachments.DIALOGUE).active(), "closing left a live session");
        h.assertTrue(d.bubbleKey().startsWith(SectGeneralTalk.BUBBLE_STEM), "no farewell bubble");
        h.assertTrue(!SectChatter.idleKey(d.bubbleKey()), "farewell classified as idle");
        h.assertTrue(SectChatter.idleKey(SectBubbles.Group.MEAL.key(1)), "meal chatter classified as important");
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 40, batch = "sect_dialogue_terminal")
    public static void terminalLinesHaveAPoliteExitAndEscapeStillCloses(GameTestHelper h) {
        ServerPlayer p = member(h);
        SectDisciple d = npc(h, "yul_ak");
        Dialogue dialogue = DialogueLoader.get(d.dialogue());
        h.assertTrue(dialogue.nodes().get("busy").options().isEmpty(), "fixture is no longer terminal");
        h.assertTrue(DialogueService.openDialogue(p, d, d.dialogue(), "busy"), "terminal did not open");
        h.assertTrue(p.getData(ModAttachments.DIALOGUE).shown().contains(SectGeneralTalk.CLOSE), "terminal lacks closing option");
        DialogueService.choose(p, p.getData(ModAttachments.DIALOGUE).shown().indexOf(SectGeneralTalk.CLOSE));
        h.assertTrue(!p.getData(ModAttachments.DIALOGUE).active(), "terminal stayed open");
        h.assertTrue(d.bubbleKey().startsWith(SectGeneralTalk.BUBBLE_STEM), "terminal lacks farewell");
        var route = DialogueService.route(p, d);
        h.assertTrue(route != null && DialogueService.openDialogue(p, d, route.id(), route.node()), "cannot reopen");
        DialogueService.choose(p, -1);
        h.assertTrue(!p.getData(ModAttachments.DIALOGUE).active() && d.talkingTo() < 0, "Escape left the actor locked");
        h.succeed();
    }

    // ------------------------------------------------------------------ path-7

    @GameTest(template = YARD, timeoutTicks = 60, batch = "sect_holes_pills")
    public static void lostFavourPillsAreGivenAgain(GameTestHelper h) {
        ServerPlayer p = member(h);
        SectDisciple gyun = npc(h, "tae_gyun");
        SectDisciple cho = npc(h, "gyeong_cho");
        h.assertTrue(SectRequests.start(p, gyun, "pills") && pills(p) == 3, "the favour did not hand over three pills: " + pills(p));
        h.assertTrue(!SectRequests.canStart(p, gyun, "pills"), "a favour with all its pills could be started again");
        h.assertTrue(SectRequests.canComplete(p, cho, "pills"), "the delivery with three pills was refused");
        // One pill is eaten, one is lost with a death: the giver hands over what is missing and nothing more.
        p.getInventory().clearContent();
        p.getInventory().add(new ItemStack(ModItems.PILL_SNOW_PLUM.get(), 1));
        h.assertTrue(!SectRequests.canComplete(p, cho, "pills"), "two missing pills were accepted");
        h.assertTrue(SectRequests.canStart(p, gyun, "pills"), "the giver does not offer the lost pills again");
        h.assertTrue(SectRequests.start(p, gyun, "pills"), "the second hand-over failed");
        h.assertTrue(pills(p) == 3, "the pills were not made up to three: " + pills(p));
        h.assertTrue(!SectRequests.canStart(p, gyun, "pills"), "a third hand-over is offered with all pills present");
        h.assertTrue(SectRequests.canComplete(p, cho, "pills") && SectRequests.complete(p, cho, "pills"), "the delivery failed after the reissue");
        h.assertTrue(!SectRequests.canStart(p, gyun, "pills"), "a done favour could be started again");
        h.succeed();
    }

    // ------------------------------------------------------------------ path-4 + progress-3

    @GameTest(template = YARD, timeoutTicks = 60, batch = "sect_holes_peaceful")
    public static void peacefulHasNoCampContractAndNoBanditFavour(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Difficulty before = level.getDifficulty();
        try {
            level.getServer().setDifficulty(Difficulty.PEACEFUL, true);
            h.assertTrue(level.getDifficulty() == Difficulty.PEACEFUL, "the difficulty did not change");
            h.assertTrue(SectContracts.campsFor(level).isEmpty(), "Peaceful still offers camps");
            List<SectContracts.Offer> day = SectContracts.generate(1L, 5L, SectContracts.campsFor(level),
                    List.of(new SectContracts.RouteRef(EscortRoutes.FOOT, BlockPos.ZERO)), BlockPos.ZERO);
            h.assertTrue(day.stream().noneMatch(o -> o.kind() == SectContracts.Kind.CAMP), "the board offers a camp on Peaceful: " + day);
            h.assertTrue(day.size() == SectContracts.OFFERS_PER_DAY, "the board lost a slot: " + day.size());
            ServerPlayer p = member(h);
            SectDisciple tak = npc(h, "gyeong_tak");
            p.setData(ModAttachments.SECT, SectService.state(p).with(SectStanding.LESSON_ONE).with(SectStanding.LESSON_TWO));
            h.assertTrue(!SectRequests.canStart(p, tak, "bandits"), "the bandit favour is offered on Peaceful");
            // The elder office does not ask for a bandit that cannot exist.
            SectState ready = SectState.NONE.joined().with(SectStanding.APPROVAL).with(SectTalk.REVIEW_WON).with(SectPositions.FIRST)
                    .with("request.training.done").with("request.vault.done").contribute(200);
            h.assertTrue(SectPositions.eligible(ready, 4, "elder", true), "the elder office is closed on Peaceful");
            h.assertTrue(!SectPositions.eligible(ready, 4, "elder", false), "the elder office opened without the bandit favour on a normal world");
            level.getServer().setDifficulty(Difficulty.NORMAL, true);
            h.assertTrue(SectRequests.canStart(p, tak, "bandits"), "the bandit favour is not offered on Normal");
        } finally {
            level.getServer().setDifficulty(before, true);
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ ui-1

    @GameTest(template = YARD, timeoutTicks = 20, batch = "sect_holes_menu")
    public static void aLongMenuKeepsTheLostTabletAndOtherMattersReachable(GameTestHelper h) {
        List<Integer> shown = new ArrayList<>(List.of(7, 0, 1, 2, 3, 4, 5, 6, 8, 9, 10));
        List<Component> options = new ArrayList<>();
        for (int i = 0; i < shown.size(); i++) options.add(Component.literal("o" + i));
        List<String> keys = new ArrayList<>(List.of("", "dialogue.murim.escort.opt.menu", "dialogue.murim.mentor.opt.journey",
                "dialogue.murim.social.opt.business", "dialogue.murim.bout.opt.ring", "dialogue.murim.mentor.opt.lost_tablet",
                "dialogue.murim.social.opt.topics", "a1", "a2", "a3", "a4"));
        DialogueService.trimOptions(shown, options, keys);
        h.assertTrue(shown.size() == Dialogue.MAX_OPTIONS && options.size() == shown.size() && keys.size() == shown.size(),
                "the menu was not cut to the cap: " + keys);
        h.assertTrue(keys.contains("dialogue.murim.mentor.opt.lost_tablet") && keys.contains("dialogue.murim.social.opt.topics"),
                "a pinned option was cut: " + keys);
        h.assertTrue(keys.get(0).isEmpty() && shown.get(0) == 7, "the goal hook was cut or moved: " + keys);
        h.succeed();
    }

    // ------------------------------------------------------------------ progress-6

    @GameTest(template = YARD, timeoutTicks = 20, batch = "sect_holes_page")
    public static void aMasteredArtSellsNoPage(GameTestHelper h) {
        ResourceLocation plum = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "seven_plum_basic");
        ServerPlayer p = SectGameTests.fakePlayer(h);
        p.setData(ModAttachments.SECT, SectState.NONE.joined().with(SectStanding.LESSON_ONE).contribute(100));
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(plum, TechniqueProgress.learned(12, 12)));
        MeritShop.Offer page = MeritShop.offer("page").orElseThrow();
        int merit = p.getData(ModAttachments.SECT).merit();
        h.assertTrue(MeritShop.buy(p, "page") == MeritShop.Result.LIMIT, "a page of a mastered art was sold");
        h.assertTrue(p.getData(ModAttachments.SECT).merit() == merit, "a refused page took merit");
        h.succeed();
    }

    // ------------------------------------------------------------------ robust-1

    @GameTest(template = YARD, timeoutTicks = 20, batch = "sect_holes_canonical")
    public static void anOldWorldHasNoBakedLayoutFallback(GameTestHelper h) {
        MountHuaSite old = MountHuaSite.placement(0, 0, 64, 0);
        h.assertTrue(!old.canonical(), "the probe site is canonical");
        SectLayout layout = SectLayout.hua(old);
        h.assertTrue(SectMap.fileOf(layout) == null, "an old world got the baked layout for its map, board and tablet");
        h.assertTrue(SectMap.fileOf(null) == null, "null layout");
        h.succeed();
    }

    // ------------------------------------------------------------------ robust-4

    @GameTest(template = YARD, timeoutTicks = 60, batch = "sect_holes_board")
    public static void aBlockedBoardCellTakesTheBoardNearbyAndNeverRetriesForever(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos spot = h.absolutePos(new BlockPos(12, 2, 12));
        MountHuaSite site = MountHuaSite.placement(spot.getX(), spot.getZ(), spot.getY(), 0);
        level.setBlock(spot, Blocks.STONE.defaultBlockState(), 3);
        h.assertTrue(!SectBoardPlacement.ensure(level, spot, site), "the plain ensure replaced a built cell");
        h.assertTrue(SectBoardPlacement.ensureOrNearby(level, spot, site), "a blocked cell left the board pending");
        h.assertTrue(level.getBlockState(spot).is(Blocks.STONE), "the built cell was replaced");
        h.assertTrue(SectBoardPlacement.hasBoard(level, spot), "no board stands near the blocked spot");
        // Everything near is taken: the spot is given up (true = stop retrying) and nothing is replaced.
        for (BlockPos c : BlockPos.betweenClosed(spot.offset(-3, -1, -3), spot.offset(3, 1, 3))) {
            if (!level.getBlockState(c).is(io.github.verycooltimo.murim.decor.SectMissionBoard.BOARD.get())) {
                level.setBlock(c, Blocks.STONE.defaultBlockState(), 3);
            }
        }
        level.setBlock(spot.above(), Blocks.STONE.defaultBlockState(), 3);
        for (BlockPos c : BlockPos.betweenClosed(spot.offset(-3, -1, -3), spot.offset(3, 1, 3))) {
            if (level.getBlockState(c).is(io.github.verycooltimo.murim.decor.SectMissionBoard.BOARD.get())) {
                level.setBlock(c, Blocks.STONE.defaultBlockState(), 3);
            }
        }
        h.assertTrue(!SectBoardPlacement.hasBoard(level, spot), "the board was not cleared for the second half");
        h.assertTrue(SectBoardPlacement.ensureOrNearby(level, spot, site), "a fully blocked spot is retried for ever");
        h.assertTrue(!SectBoardPlacement.hasBoard(level, spot), "a board appeared in a fully blocked spot");
        h.succeed();
    }
}
