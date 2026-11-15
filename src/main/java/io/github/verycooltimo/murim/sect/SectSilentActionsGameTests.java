package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.network.DialoguePayloads;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

import static io.github.verycooltimo.murim.sect.SectPlaytestRig.*;

/**
 * Author 15.11: «нажимаю 20 раз — ничего не происходит». A dialogue action that fails must make the person say why (his own
 * line in the dialogue), and an option the player is shown must not do nothing.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectSilentActionsGameTests {
    private SectSilentActionsGameTests() { }

    private static final String YARD = "sect_yard";

    /** Every dialogue payload the server sends while {@code run} runs. */
    private static List<DialoguePayloads.Open> record(Runnable run) {
        List<DialoguePayloads.Open> sent = new ArrayList<>();
        DialogueService.tap = pl -> { if (pl instanceof DialoguePayloads.Open o) sent.add(o); };
        try {
            run.run();
        } finally {
            DialogueService.tap = null;
        }
        return sent;
    }

    private static String key(DialoguePayloads.Open o) {
        return o.line().getContents() instanceof TranslatableContents t ? t.getKey() : "literal:" + o.line().getString();
    }

    private static void choose(GameTestHelper h, ServerPlayer p, String text) {
        var session = p.getData(ModAttachments.DIALOGUE);
        var node = DialogueService.nodeOf(DialogueLoader.get(session.dialogue()), session.node());
        for (int i = 0; i < session.shown().size(); i++) {
            int raw = session.shown().get(i);
            if (raw >= 0 && raw < node.options().size() && text.equals(node.options().get(raw).text())) {
                DialogueService.choose(p, i);
                return;
            }
        }
        h.fail("missing answer " + text + " in " + session.node());
    }

    private static void said(GameTestHelper h, List<DialoguePayloads.Open> sent, String expected) {
        h.assertTrue(!sent.isEmpty(), "the click produced no reply at all");
        String got = key(sent.get(sent.size() - 1));
        h.assertTrue(expected.equals(got), "the person said " + got + " instead of " + expected);
    }

    private static SectState disciple() {
        return SectState.NONE.joined().with(SectStanding.LESSON_ONE).with(SectStanding.LESSON_TWO);
    }

    @GameTest(template = YARD, batch = "silent_actions_merit", timeoutTicks = 60)
    public static void meritBuyWithoutMeritNamesTheLack(GameTestHelper h) {
        var layout = only(h, "treasury");
        ServerPlayer p = player(h, 12, 12, disciple());
        SectDisciple elder = npc(h, layout, "tae_gyun", 13, 12, false);
        h.assertTrue(DialogueService.openDialogue(p, elder, elder.dialogue(), "exchange"), "exchange did not open");
        var sent = record(() -> choose(h, p, "dialogue.murim.merit.sword"));
        said(h, sent, "dialogue.murim.no.merit.merit");
        h.assertTrue(p.getInventory().items.stream().noneMatch(s -> !s.isEmpty()), "an item came for nothing");
        h.succeed();
    }

    @GameTest(template = YARD, batch = "silent_actions_merit", timeoutTicks = 60)
    public static void plumBlossomPillIsOnSaleAtTheHealer(GameTestHelper h) {
        var layout = only(h, "dining");
        ServerPlayer p = player(h, 12, 12, disciple().contribute(5));
        SectDisciple healer = npc(h, layout, "gyeong_cho", 13, 12, false);
        h.assertTrue(DialogueService.openDialogue(p, healer, healer.dialogue(), "pills_more"), "pills did not open");
        int merit = p.getData(ModAttachments.SECT).merit();
        choose(h, p, "dialogue.murim.merit.plum_blossom");
        h.assertTrue(p.getData(ModAttachments.SECT).merit() == merit - 1, "the pill was not paid for");
        h.assertTrue(p.getInventory().items.stream().anyMatch(s -> s.is(io.github.verycooltimo.murim.registry.ModItems.PILL_PLUM_BLOSSOM.get())),
                "no pill came");
        h.succeed();
    }

    @GameTest(template = YARD, batch = "silent_actions_review", timeoutTicks = 60)
    public static void reviewSignUpOnAnEarlyDayTellsHowLongToWait(GameTestHelper h) {
        var layout = only(h, "training");
        ServerPlayer p = player(h, 12, 12, disciple());
        SectDisciple senior = npc(h, layout, "seo_rang", 13, 12, false);
        long saved = h.getLevel().getDayTime();
        try {
            boolean found = false;
            for (long day = 0; day < 8 && !found; day++) {
                h.getLevel().setDayTime(day * 24000L + 1000L);
                found = !SectReview.signupOpen(p);
            }
            h.assertTrue(found, "setup: the sign-up is open every day");
            h.assertTrue(DialogueService.openDialogue(p, senior, senior.dialogue(), "greet"), "senior did not open");
            // the opening menu folds the other matters behind one row; the review sits in them
            DialogueService.show(p, senior, senior.dialogue(), DialogueLoader.get(senior.dialogue()), "@matters:greet", "");
            var sent = record(() -> choose(h, p, "dialogue.murim.senior.opt.review"));
            said(h, sent, "dialogue.murim.no.review.too_early");
            h.assertTrue(!SectService.state(p).has(SectReview.SIGNED), "signed although refused");
        } finally {
            h.getLevel().setDayTime(saved);
        }
        h.succeed();
    }

    @GameTest(template = YARD, batch = "silent_actions_meal", timeoutTicks = 60)
    public static void cookWithAFullPackSaysToMakeRoom(GameTestHelper h) {
        var layout = only(h, "dining");
        ServerPlayer p = player(h, 12, 12, SectState.NONE.joined());
        SectDisciple cook = npc(h, layout, "cook_kim", 13, 12, false);
        long saved = h.getLevel().getDayTime();
        try {
            h.getLevel().setDayTime(5 * 24000L + 9500L);
            h.assertTrue(SectMeals.mealId(h.getLevel().getDayTime()) >= 0, "setup: no meal");
            for (int i = 0; i < p.getInventory().items.size(); i++) p.getInventory().items.set(i, new ItemStack(Items.STICK));
            var sent = record(() -> h.assertTrue(DialogueService.openDialogue(p, cook, cook.dialogue(), "serving"), "cook did not open"));
            said(h, sent, "dialogue.murim.no.meal.full");
        } finally {
            h.getLevel().setDayTime(saved);
        }
        h.succeed();
    }

    @GameTest(template = YARD, batch = "silent_actions_assist", timeoutTicks = 60)
    public static void elixirHelpWithoutAnElixirSaysWhatIsNeeded(GameTestHelper h) {
        var layout = only(h, "dining");
        ServerPlayer p = player(h, 12, 12, disciple().contribute(40));
        SectDisciple healer = npc(h, layout, "gyeong_cho", 13, 12, false);
        h.assertTrue(DialogueService.openDialogue(p, healer, healer.dialogue(), "pills"), "pills did not open");
        var sent = record(() -> choose(h, p, "dialogue.murim.pill.assist"));
        said(h, sent, "dialogue.murim.no.assist.elixir");
        h.assertTrue(p.getData(ModAttachments.DIALOGUE).active(), "the talk closed without a word");
        h.succeed();
    }

    @GameTest(template = YARD, batch = "silent_actions_donate", timeoutTicks = 60)
    public static void donationKeepsTheGiftWhenTheDayHoldsLessThanItIsWorth(GameTestHelper h) {
        var layout = only(h, "treasury");
        ServerPlayer p = player(h, 12, 12, disciple());
        SectDisciple steward = npc(h, layout, "steward_mun", 13, 12, false);
        p.getInventory().add(new ItemStack(Items.GOLD_INGOT, 2));
        h.assertTrue(SectService.donate(p, "minecraft:gold_ingot*1=5"), "setup: first gift refused");
        int merit = p.getData(ModAttachments.SECT).merit();
        h.assertTrue(DialogueService.openDialogue(p, steward, steward.dialogue(), "help"), "help did not open");
        var sent = record(() -> choose(h, p, "dialogue.murim.steward.opt.gold"));
        said(h, sent, "dialogue.murim.no.donate.partial");
        h.assertTrue(p.getInventory().countItem(Items.GOLD_INGOT) == 1, "the ingot was taken for 1 merit");
        h.assertTrue(p.getData(ModAttachments.SECT).merit() == merit, "merit changed");
        h.succeed();
    }

    @GameTest(template = YARD, batch = "silent_actions_donate", timeoutTicks = 60)
    public static void donationAfterTheDailyCapSaysEnough(GameTestHelper h) {
        var layout = only(h, "treasury");
        ServerPlayer p = player(h, 12, 12, disciple());
        SectDisciple steward = npc(h, layout, "steward_mun", 13, 12, false);
        p.getInventory().add(new ItemStack(Items.GOLD_INGOT, 3));
        h.assertTrue(SectService.donate(p, "minecraft:gold_ingot*1=3") && SectService.donate(p, "minecraft:gold_ingot*1=3"), "setup");
        h.assertTrue(DialogueService.openDialogue(p, steward, steward.dialogue(), "help"), "help did not open");
        var sent = record(() -> choose(h, p, "dialogue.murim.steward.opt.gold"));
        said(h, sent, "dialogue.murim.no.donate.enough");
        h.assertTrue(p.getInventory().countItem(Items.GOLD_INGOT) == 1, "an ingot went for nothing");
        h.succeed();
    }

    @GameTest(template = YARD, batch = "silent_actions_guide", timeoutTicks = 60)
    public static void guideRequestWithNobodyFreeSaysSo(GameTestHelper h) {
        var layout = only(h, "sect_gate");
        ServerPlayer p = player(h, 12, 12, SectState.NONE.with(SectArrival.INVITED));
        SectDisciple guard = npc(h, layout, "gatekeeper", 13, 12, false);
        h.assertTrue(DialogueService.openDialogue(p, guard, SectArrival.DIALOGUE, "invited"), "gate talk unavailable");
        var sent = record(() -> choose(h, p, "dialogue.murim.arrival.opt.follow"));
        said(h, sent, "dialogue.murim.arrival.guide_none");
        h.succeed();
    }

    @GameTest(template = YARD, batch = "silent_actions_lessons", timeoutTicks = 60)
    public static void doneSixOptionIsShownInTheLessonsMenu(GameTestHelper h) {
        var layout = only(h, "mentor");
        ServerPlayer p = player(h, 12, 12, SectState.NONE.joined().with("lesson.six").with("met_mentor"));
        SectDisciple mentor = npc(h, layout, "gyeong_pil", 13, 12, false);
        io.github.verycooltimo.murim.quest.Quests.mark(p, "mentor_strike", "done");
        p.setData(ModAttachments.MASTERY, io.github.verycooltimo.murim.mastery.MasteryState.EMPTY.with(SectService.SIX,
                io.github.verycooltimo.murim.mastery.TechniqueProgress.learned(2, 6)));
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withRank(1));
        h.assertTrue(DialogueService.openDialogue(p, mentor, mentor.dialogue(), "lessons"), "lessons did not open");
        var session = p.getData(ModAttachments.DIALOGUE);
        var node = DialogueLoader.get(session.dialogue()).nodes().get("lessons");
        boolean shown = session.shown().stream().anyMatch(i -> i >= 0 && i < node.options().size()
                && node.options().get(i).next().orElse("").startsWith("six_given_"));
        h.assertTrue(shown, "the hand-in option is hidden in the lessons menu");
        h.succeed();
    }

    @GameTest(template = YARD, batch = "silent_actions_war", timeoutTicks = 260)
    public static void personOfTheSectAtWarAnswersWithAHostileWord(GameTestHelper h) {
        var layout = only(h, "training");
        ServerPlayer p = player(h, 12, 12, disciple());
        SectDisciple victim = npc(h, layout, "yul_jin", 13, 12, false);
        SectDisciple other = npc(h, layout, "yul_seok", 11, 12, false);
        SectWar.declare(p, victim, "second");
        h.assertTrue(SectWar.atWar(p), "setup: no war");
        // the war cry is over after a few seconds; then a right click must still be answered
        h.runAfterDelay(150, () -> {
            try {
                other.interact(p, net.minecraft.world.InteractionHand.MAIN_HAND);
                h.assertTrue(other.bubbleKey().startsWith("murim.sect.war.shun."), "right click at war was silent: '" + other.bubbleKey() + "'");
                h.assertTrue(!p.getData(ModAttachments.DIALOGUE).active(), "a talk opened at war");
            } finally {
                SectWar.data(h.getLevel()).wars.remove(p.getUUID());
            }
            h.succeed();
        });
    }

    @GameTest(template = YARD, batch = "silent_actions_farewell", timeoutTicks = 60)
    public static void someoneNotYetAcceptedIsNotToldToTrain(GameTestHelper h) {
        var layout = only(h, "sect_gate");
        ServerPlayer p = player(h, 12, 12, SectState.NONE);
        SectDisciple guard = npc(h, layout, "gatekeeper", 13, 12, false);
        for (int i = 0; i < 12; i++) {
            SectGeneralTalk.farewell(p, guard);
            h.assertTrue(guard.bubbleKey().startsWith(SectGeneralTalk.BUBBLE_STEM + SectGeneralTalk.OUTSIDER_TONE + "."),
                    "an outsider heard " + guard.bubbleKey());
        }
        h.succeed();
    }
}
